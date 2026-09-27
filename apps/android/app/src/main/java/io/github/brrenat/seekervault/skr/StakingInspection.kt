package io.github.brrenat.seekervault.skr

import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.StakingAction
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.DecodeFailure
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.ReadInstruction
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_2022_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.transactions.decodeTransaction
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Checking a prepared staking transaction against the request and the chain (SEE-146).
 *
 * Everything here is read out of the transaction's own bytes and out of accounts this phone fetched
 * for itself. The server's account of what it built is never evidence: it built the bytes, and
 * these are the bytes.
 *
 * The four operations need different things established, and saying so is the point of the ticket.
 * Staking commits tokens the owner holds. Unstaking commits nothing and moves nothing — it changes
 * a position and starts a clock. Cancelling undoes that. Withdrawing is the only one that brings
 * tokens back, and it is the only one an owner might confuse with unstaking. A review that treated
 * all four as "an outgoing transfer" would be wrong about three of them.
 */

/** The request's staking action, or null when it is not a staking request. */
fun ActionRequest.staking(): StakingAction? =
    if (action.kindCase == Action.KindCase.STAKING) action.staking else null

/**
 * What this phone read off the chain for itself, at preparation time.
 *
 * It is passed in rather than fetched here because inspection must not suspend: a review that could
 * block on a network is a review that can be left half-open. The read happens once, beside the
 * preparation it belongs to, and [StakingInspection] is a pure function of it.
 */
data class SkrReading(
    val addresses: SkrAddresses,
    val config: SkrStakeConfig,
    val guardian: SkrGuardianPool,
    /** The owner's stake account, or null when they have never staked with this guardian. */
    val stake: SkrUserStake?,
    /** The owner's SKR account, or null when it does not exist yet. */
    val ownerTokenAccount: SkrTokenAccount?,
)

/**
 * How far above the approved amount an unstake's shares may be worth.
 *
 * Two things make an exact equality wrong. The program floors, so a token amount converted to
 * shares and back loses up to one base unit; and the share price rises when rewards land, so the
 * price this phone reads can be a little higher than the one the shares were computed against
 * moments earlier. One part in ten thousand covers both, and the absolute cap is stricter anyway:
 * the shares can never exceed the position the owner actually holds.
 */
private const val UNSTAKE_TOLERANCE_BPS = 1L

/** What the app found wrong, or could not account for, in a prepared staking transaction. */
enum class StakingFinding {
    /** The server's content hash is not the SHA-256 of the bytes it sent. */
    HashMismatch,
    /** The bytes are not a transaction this app can read. */
    Malformed,
    /** A message version this app does not read. */
    UnsupportedVersion,
    /** The message loads accounts from a lookup table, so what it touches cannot be seen here. */
    AddressTableLookup,
    /** A signature slot is already filled. The wallet must be handed something unsigned. */
    AlreadySigned,
    /** No wallet is connected, so there is nothing to check the transaction against. */
    NoWallet,
    /** The request names a wallet other than the connected one. */
    OtherWallet,
    /** The request's network is not the one the connected wallet was selected for. */
    NetworkMismatch,
    /** The fee payer is not the owner's wallet. */
    FeePayerNotTheWallet,
    /** Something other than the owner's wallet would have to sign. */
    ExtraSigner,
    /**
     * This phone could not read the chain, and the operation cannot be interpreted without it. An
     * unstake burns shares, so what it is worth is a fact about the share price; a cancellation and
     * a withdrawal act on a pending amount only the chain knows. None of those can be put in front
     * of an owner as a reviewed amount.
     */
    PositionNotRead,
    /**
     * This phone could not read the owner's balance or the program's minimum.
     *
     * Only a stake reports this, and it does not invalidate. A stake names its amount in the
     * instruction itself, and every account in it is derived here, so what it does is established
     * without any read; what is missing is only whether the chain will accept it, which the chain
     * will say for itself. The review says so rather than implying the balance was checked.
     */
    BalanceNotRead,
    /** The transaction contains no staking instruction. */
    NoStakingInstruction,
    /** It contains more than one, so which one the owner is approving is not a single fact. */
    ExtraStakingInstruction,
    /** The staking instruction is not the operation the request asked for. */
    OperationMismatch,
    /** An account names a staking deployment other than the one this app carries. */
    OtherDeployment,
    /**
     * One of the instruction's fixed accounts — the program's own ID, its event authority, or a
     * runtime program it calls into — is not what it has to be. Nobody has a reason to change one,
     * so one that changed is a reason to stop.
     */
    UnexpectedAccount,
    /** The stake account is not the one derived for this owner and guardian. */
    OtherStakeAccount,
    /** The instruction acts for a wallet other than the request's. */
    NotTheOwnersPosition,
    /** The guardian pool is not the one this app stakes into. */
    OtherGuardianPool,
    /** The pool takes no new stake, so a stake would fail on chain. */
    GuardianPoolInactive,
    /** The token account is not the owner's own associated account for SKR. */
    OtherTokenAccount,
    /** The amount in the transaction is not the amount the request names. */
    AmountMismatch,
    /** The stake is below the smallest the program accepts. */
    BelowMinimumStake,
    /** The owner does not hold the SKR this would stake. */
    InsufficientBalance,
    /** There is no active stake for an unstake to burn. */
    NothingStaked,
    /** The shares to be burned are worth more than the amount the owner approved. */
    SharesExceedApprovedAmount,
    /** A whole-position unstake must burn the position's own share count, and this does not. */
    NotTheWholePosition,
    /** There is no pending unstake to cancel or withdraw. */
    NothingPending,
    /** The cooldown has not finished, so a withdrawal would fail on chain. */
    CooldownNotFinished,
    /** A finished cooldown must be withdrawn before more is unstaked. */
    WithdrawFirst,
    /** A token account would be created for somebody other than the owner, or for another mint. */
    AccountCreationForSomeoneElse,
    /** The transaction moves value beyond the staking instruction itself. */
    ExtraTransfer,
    /** The staking program is asked to do something this app does not read. */
    UnreadableStakingInstruction,
    /** A program that can move value is asked to do something this app does not read. */
    UnreadableValueInstruction,
    /** An instruction this app cannot read at all. It is not thereby safe. */
    UnrecognizedInstruction,
}

/**
 * Whether a finding means the preparation must not be approved, or only that the review does not
 * cover all of it. As with a transfer, only a wholly unread instruction is the latter.
 */
val StakingFinding.invalidates: Boolean
    get() = this != StakingFinding.UnrecognizedInstruction && this != StakingFinding.BalanceNotRead

/** The programs that can move the owner's funds, or hand somebody else the power to. */
private val VALUE_PROGRAMS =
    setOf(SYSTEM_PROGRAM, TOKEN_PROGRAM, TOKEN_2022_PROGRAM, ASSOCIATED_TOKEN_PROGRAM)

/** What the transaction turned out to do to the owner's position. */
data class StakingFacts(
    val operation: StakingOperation,
    /** The wallet whose position changes, and the only signer. */
    val wallet: String,
    /** The program-derived account the position lives in. */
    val stakeAccount: String,
    /**
     * The SKR the operation moves or commits, in base units, as far as the bytes and the chain
     * establish it: the stake for a stake, the value of the burned shares for an unstake, the
     * pending amount for a cancellation or a withdrawal.
     */
    val amount: ULong?,
    /** The shares an unstake burns; null for the other three. */
    val shares: BigInteger?,
    val decimals: Int,
    /**
     * Whether SKR leaves or reaches the wallet. False for unstaking and cancelling, which change a
     * position and move nothing — counting either as an outgoing transfer would be a lie, and
     * calling either harmless would be another.
     */
    val movesValue: Boolean,
    /** True when the SKR moves towards the owner, which only a withdrawal does. */
    val incoming: Boolean,
    /** True when the transaction also creates the owner's SKR account, and pays its rent. */
    val createsTokenAccount: Boolean,
    /** The cooldown this deployment keeps, read from its configuration. */
    val cooldownSeconds: ULong?,
    /** When the pending unstake became, or will become, withdrawable. */
    val cooldownEndsAt: Long?,
    /**
     * True when this unstake restarts a cooldown that is already running, which postpones SKR the
     * owner may believe is nearly free.
     */
    val resetsExistingCooldown: Boolean,
    /** The SKR that stays staked after an unstake, at the price this phone read. */
    val remainingStake: ULong?,
    val programs: List<String>,
    val computeUnitPrice: ULong?,
    val instructionCount: Int,
    /** How many of [instructionCount] were read. Fewer means the review is not complete. */
    val recognizedInstructions: Int,
)

/** The result of inspecting one prepared staking transaction. */
data class StakingInspection(
    val verdict: Verdict,
    val findings: List<StakingFinding>,
    val facts: StakingFacts?,
    /** The prepared version this reading was made from; an approval names the same one. */
    val version: Int,
) {
    /** Whether this may be put in front of the owner to approve. */
    val approvable: Boolean
        get() = verdict == Verdict.Verified
}

/**
 * Reads [prepared] and checks it against [request], [wallet] and [reading].
 *
 * The bytes are read first and judged afterwards, so nothing the server said about them steers the
 * reading. [now] is the second the cooldown is judged against, passed in so the boundary is
 * testable rather than whatever the clock happened to say.
 */
fun inspectStaking(
    request: ActionRequest,
    prepared: PreparedTransaction,
    wallet: SelectedWallet?,
    reading: SkrReading?,
    now: Long,
): StakingInspection {
    val findings = mutableListOf<StakingFinding>()
    val action =
        request.staking()
            ?: return StakingInspection(
                Verdict.Invalid,
                listOf(StakingFinding.NoStakingInstruction),
                null,
                prepared.version,
            )
    val bytes = prepared.transaction.toByteArray()

    // The server's hash is a claim about its own bytes, not evidence about them. One that disagrees
    // with the bytes says the preparation cannot even be trusted to be self-consistent.
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    if (!digest.contentEquals(prepared.contentHash.toByteArray())) {
        findings += StakingFinding.HashMismatch
    }

    val decoded =
        when (val result = decodeTransaction(bytes)) {
            is DecodeResult.Decoded -> result.transaction
            is DecodeResult.Failed -> {
                findings +=
                    when (result.failure) {
                        DecodeFailure.Malformed -> StakingFinding.Malformed
                        DecodeFailure.UnsupportedVersion -> StakingFinding.UnsupportedVersion
                        DecodeFailure.AddressTableLookup -> StakingFinding.AddressTableLookup
                    }
                return StakingInspection(Verdict.Invalid, findings, null, prepared.version)
            }
        }

    if (!decoded.unsigned) findings += StakingFinding.AlreadySigned
    if (wallet == null) {
        findings += StakingFinding.NoWallet
    } else {
        if (wallet.address != action.wallet) findings += StakingFinding.OtherWallet
        if (wallet.network.network != action.network) findings += StakingFinding.NetworkMismatch
    }
    // The owner pays and nothing else signs. A withdrawal needs no signature from the program's
    // point of view, so the fee payer's is the only thing that makes it the owner's transaction.
    if (decoded.feePayer != action.wallet) findings += StakingFinding.FeePayerNotTheWallet
    if (decoded.signers != listOf(action.wallet)) findings += StakingFinding.ExtraSigner

    val read = decoded.instructions.map { decoded.readStakingStep(it) }
    if (read.any { it == null }) {
        findings += StakingFinding.Malformed
        return StakingInspection(Verdict.Invalid, findings, null, prepared.version)
    }
    val steps = read.filterNotNull()

    val staking = steps.filter { it.isStaking }
    if (staking.isEmpty()) findings += StakingFinding.NoStakingInstruction
    if (staking.size > 1) findings += StakingFinding.ExtraStakingInstruction
    val step = staking.firstOrNull()

    // Cancelling and withdrawing act on the whole pending unstake, so the protocol requires their
    // amount to be empty. A request carrying one describes a choice the program does not offer, and
    // the review says so rather than quietly showing the chain's number instead of the agent's.
    if (action.amount.isNotEmpty() && !takesAnAmount(action.operation)) {
        findings += StakingFinding.AmountMismatch
    }

    if (reading == null) {
        // Which operations can be read without the chain is not a matter of taste. A stake's
        // amount is in its own instruction and all of its accounts are derived here; the other
        // three mean something only in terms of a share price or a pending amount.
        findings +=
            if (action.operation == StakingOperation.STAKING_OPERATION_STAKE) {
                StakingFinding.BalanceNotRead
            } else {
                StakingFinding.PositionNotRead
            }
    }

    val facts = if (step == null) null else checkStep(action, step, steps, reading, now, findings)

    checkOthers(action, steps, findings)

    val verdict =
        when {
            findings.any { it.invalidates } || facts == null -> Verdict.Invalid
            findings.isNotEmpty() -> Verdict.Unverified
            else -> Verdict.Verified
        }
    return StakingInspection(verdict, findings.distinct(), facts, prepared.version)
}

private val StakingStep.isStaking: Boolean
    get() =
        this is StakingStep.Stake ||
            this is StakingStep.Unstake ||
            this is StakingStep.CancelUnstake ||
            this is StakingStep.Withdraw ||
            this is StakingStep.UnreadableStaking

/**
 * Checks the one staking instruction against the request and the chain, and reports what it does.
 */
private fun checkStep(
    action: StakingAction,
    step: StakingStep,
    steps: List<StakingStep>,
    reading: SkrReading?,
    now: Long,
    findings: MutableList<StakingFinding>,
): StakingFacts? {
    if (step is StakingStep.UnreadableStaking) {
        findings += StakingFinding.UnreadableStakingInstruction
        return null
    }
    if (!matchesOperation(action.operation, step)) {
        findings += StakingFinding.OperationMismatch
        return null
    }
    // Derived here, from this app's own constants, and compared against the bytes. The reading's
    // addresses are the same derivation; using them keeps one source for both.
    val addresses = reading?.addresses ?: SkrAddresses.derive()
    if (addresses == null) {
        findings += StakingFinding.PositionNotRead
        return null
    }
    val expectedStake = addresses.userStake(action.wallet)
    val ownerAccount = associatedTokenAddress(action.wallet, addresses.mint)

    // The configuration the program will read has to be the one this app derives, and the
    // configuration itself has to name the mint and vault this app expects. The first stops a
    // transaction pointed at another deployment; the second stops a deployment that was changed.
    if (reading != null) {
        if (reading.config.mint != addresses.mint) findings += StakingFinding.OtherDeployment
        if (reading.config.stakeVault != addresses.stakeVault) {
            findings += StakingFinding.OtherDeployment
        }
        if (
            reading.guardian.stakeConfig != addresses.stakeConfig ||
                reading.guardian.guardian != addresses.guardian
        ) {
            findings += StakingFinding.OtherGuardianPool
        }
    }

    checkAccounts(step, addresses, expectedStake, findings)
    if (accountedUser(step) != action.wallet) findings += StakingFinding.NotTheOwnersPosition

    val creation = steps.filterIsInstance<StakingStep.Other>().map { it.read }
    val creates =
        creation.filterIsInstance<ReadInstruction.CreateTokenAccount>().also { accounts ->
            for (create in accounts) {
                val wrong =
                    create.owner != action.wallet ||
                        create.mint != addresses.mint ||
                        create.payer != action.wallet ||
                        (ownerAccount != null && create.account != ownerAccount)
                if (wrong) findings += StakingFinding.AccountCreationForSomeoneElse
            }
        }
    val budget = creation.filterIsInstance<ReadInstruction.ComputeBudget>()

    val common =
        CommonFacts(
            operation = action.operation,
            wallet = action.wallet,
            stakeAccount = expectedStake ?: accountedStake(step),
            createsTokenAccount = creates.isNotEmpty(),
            cooldownSeconds = reading?.config?.cooldownSeconds,
            programs = steps.mapNotNull(::programOfStep).distinct(),
            computeUnitPrice = budget.firstNotNullOfOrNull { it.microLamportsPerUnit },
            instructionCount = steps.size,
            recognizedInstructions = steps.count { it.isRead },
        )

    return when (step) {
        is StakingStep.Stake -> stakeFacts(action, step, reading, ownerAccount, common, findings)
        is StakingStep.Unstake -> unstakeFacts(action, step, reading, now, common, findings)
        is StakingStep.CancelUnstake -> cancelFacts(reading, common, findings)
        is StakingStep.Withdraw -> withdrawFacts(step, reading, ownerAccount, now, common, findings)
        else -> null
    }
}

/** The fields every operation's facts share, so each branch only fills in its own. */
private data class CommonFacts(
    val operation: StakingOperation,
    val wallet: String,
    val stakeAccount: String?,
    val createsTokenAccount: Boolean,
    val cooldownSeconds: ULong?,
    val programs: List<String>,
    val computeUnitPrice: ULong?,
    val instructionCount: Int,
    val recognizedInstructions: Int,
)

private fun CommonFacts.into(
    amount: ULong?,
    shares: BigInteger?,
    movesValue: Boolean,
    incoming: Boolean,
    cooldownEndsAt: Long?,
    resetsExistingCooldown: Boolean,
    remainingStake: ULong?,
) =
    StakingFacts(
        operation = operation,
        wallet = wallet,
        stakeAccount = stakeAccount ?: "",
        amount = amount,
        shares = shares,
        decimals = SKR_DECIMALS,
        movesValue = movesValue,
        incoming = incoming,
        createsTokenAccount = createsTokenAccount,
        cooldownSeconds = cooldownSeconds,
        cooldownEndsAt = cooldownEndsAt,
        resetsExistingCooldown = resetsExistingCooldown,
        remainingStake = remainingStake,
        programs = programs,
        computeUnitPrice = computeUnitPrice,
        instructionCount = instructionCount,
        recognizedInstructions = recognizedInstructions,
    )

private fun stakeFacts(
    action: StakingAction,
    step: StakingStep.Stake,
    reading: SkrReading?,
    ownerAccount: String?,
    common: CommonFacts,
    findings: MutableList<StakingFinding>,
): StakingFacts {
    val requested = action.amount.toULongOrNull()
    if (requested == null || step.amount != requested) findings += StakingFinding.AmountMismatch
    if (step.payer != action.wallet) findings += StakingFinding.NotTheOwnersPosition
    if (ownerAccount != null && step.userTokenAccount != ownerAccount) {
        findings += StakingFinding.OtherTokenAccount
    }
    if (reading != null) {
        if (!reading.guardian.active) findings += StakingFinding.GuardianPoolInactive
        if (step.amount < reading.config.minStakeAmount) {
            findings += StakingFinding.BelowMinimumStake
        }
        val held = reading.ownerTokenAccount?.amount ?: 0UL
        if (step.amount > held) findings += StakingFinding.InsufficientBalance
    }
    return common.into(
        amount = step.amount,
        shares = null,
        // Staking commits tokens the owner holds: they leave the wallet for the vault.
        movesValue = true,
        incoming = false,
        cooldownEndsAt = null,
        resetsExistingCooldown = false,
        remainingStake = null,
    )
}

private fun unstakeFacts(
    action: StakingAction,
    step: StakingStep.Unstake,
    reading: SkrReading?,
    now: Long,
    common: CommonFacts,
    findings: MutableList<StakingFinding>,
): StakingFacts {
    val requested = action.amount.toULongOrNull()
    if (requested == null) findings += StakingFinding.AmountMismatch
    val stake = reading?.stake
    var value: ULong? = null
    var remaining: ULong? = null
    var resets = false
    if (reading != null) {
        if (stake == null || stake.shares.signum() <= 0) {
            findings += StakingFinding.NothingStaked
        } else {
            val price = reading.config.sharePrice
            val held = stake.shares
            value = sharesToAmount(step.shares, price)
            val position = sharesToAmount(held, price)
            if (step.shares > held) findings += StakingFinding.SharesExceedApprovedAmount
            if (requested != null && position != null) {
                if (requested >= position) {
                    // A whole-position unstake has to burn the position's own share count. Anything
                    // computed from its token value floors a second time and leaves dust behind,
                    // which is how an owner ends up unable to close a position they closed.
                    if (step.shares != held) findings += StakingFinding.NotTheWholePosition
                } else if (value != null && value > allowance(requested)) {
                    findings += StakingFinding.SharesExceedApprovedAmount
                }
            }
            remaining = sharesToAmount(held - step.shares, price)
            if (stake.hasPendingUnstake) {
                // The program refuses a further unstake once a cooldown has finished
                // (WithdrawRequired), and restarts the clock when one is still running. Both are
                // worth saying out loud, and only one of them is a refusal.
                val endsAt = stake.unstakeTimestamp + stake.unstakeCooldown(reading)
                if (now >= endsAt) findings += StakingFinding.WithdrawFirst else resets = true
            }
        }
    }
    val endsAt = reading?.config?.cooldownSeconds?.let { seconds -> now + seconds.toLong() }
    return common.into(
        amount = value,
        shares = step.shares,
        // Nothing reaches or leaves the wallet: a position changes and a clock starts.
        movesValue = false,
        incoming = false,
        cooldownEndsAt = endsAt,
        resetsExistingCooldown = resets,
        remainingStake = remaining,
    )
}

private fun cancelFacts(
    reading: SkrReading?,
    common: CommonFacts,
    findings: MutableList<StakingFinding>,
): StakingFacts {
    val stake = reading?.stake
    if (reading != null && (stake == null || !stake.hasPendingUnstake)) {
        findings += StakingFinding.NothingPending
    }
    return common.into(
        // The whole pending amount goes back to work; the program cancels all of it or none.
        amount = stake?.unstakingAmount,
        shares = null,
        movesValue = false,
        incoming = false,
        cooldownEndsAt = null,
        resetsExistingCooldown = false,
        remainingStake = null,
    )
}

private fun withdrawFacts(
    step: StakingStep.Withdraw,
    reading: SkrReading?,
    ownerAccount: String?,
    now: Long,
    common: CommonFacts,
    findings: MutableList<StakingFinding>,
): StakingFacts {
    if (ownerAccount != null && step.userTokenAccount != ownerAccount) {
        findings += StakingFinding.OtherTokenAccount
    }
    val stake = reading?.stake
    var endsAt: Long? = null
    if (reading != null) {
        if (stake == null || !stake.hasPendingUnstake) {
            findings += StakingFinding.NothingPending
        } else {
            endsAt = stake.unstakeTimestamp + stake.unstakeCooldown(reading)
            // The program's own check is `now >= timestamp + cooldown`, so the boundary second is
            // ready. Anything stricter here would refuse the one second the chain would accept.
            if (now < endsAt) findings += StakingFinding.CooldownNotFinished
        }
    }
    return common.into(
        // The only one of the four that brings SKR back to the wallet.
        amount = stake?.unstakingAmount,
        shares = null,
        movesValue = true,
        incoming = true,
        cooldownEndsAt = endsAt,
        resetsExistingCooldown = false,
        remainingStake = null,
    )
}

/** Everything that is not the staking instruction, and what it is allowed to be. */
private fun checkOthers(
    action: StakingAction,
    steps: List<StakingStep>,
    findings: MutableList<StakingFinding>,
) {
    val withdrawing = action.operation == StakingOperation.STAKING_OPERATION_WITHDRAW
    for (step in steps) {
        if (step.isStaking) continue
        when (val read = (step as StakingStep.Other).read) {
            is ReadInstruction.ComputeBudget -> Unit
            is ReadInstruction.CreateTokenAccount ->
                // A withdrawal needs somewhere to land, so the associated-account program's
                // idempotent create is a supporting instruction it may carry. Its target was
                // already checked; what is checked here is that nothing else carries one.
                if (!withdrawing || !read.idempotent) {
                    findings += StakingFinding.ExtraTransfer
                }
            is ReadInstruction.SolTransfer,
            is ReadInstruction.TokenTransfer -> findings += StakingFinding.ExtraTransfer
            is ReadInstruction.Unrecognized ->
                findings +=
                    if (read.program in VALUE_PROGRAMS) {
                        StakingFinding.UnreadableValueInstruction
                    } else {
                        StakingFinding.UnrecognizedInstruction
                    }
        }
    }
}

private fun checkAccounts(
    step: StakingStep,
    addresses: SkrAddresses,
    expectedStake: String?,
    findings: MutableList<StakingFinding>,
) {
    val config: String
    val vault: String
    val pool: String?
    val mint: String?
    val stakeAccount: String
    // The positions a caller has no reason to choose: the program's own ID, the event authority it
    // derives, and the two runtime programs it calls into. Each is compared against a value this
    // app knows or derives, so "the accounts are the ones this instruction should have" is
    // established for every position rather than for the interesting ones.
    val fixed: List<Pair<String, String>>
    when (step) {
        is StakingStep.Stake -> {
            config = step.stakeConfig
            vault = step.stakeVault
            pool = step.guardianPool
            mint = step.mint
            stakeAccount = step.userStake
            fixed =
                listOf(
                    step.tokenProgram to TOKEN_PROGRAM,
                    step.systemProgram to SYSTEM_PROGRAM,
                    step.eventAuthority to addresses.eventAuthority,
                    step.programId to addresses.program,
                )
        }
        is StakingStep.Unstake -> {
            config = step.stakeConfig
            vault = step.stakeVault
            pool = step.guardianPool
            mint = step.mint
            stakeAccount = step.userStake
            fixed =
                listOf(
                    step.eventAuthority to addresses.eventAuthority,
                    step.programId to addresses.program,
                )
        }
        is StakingStep.CancelUnstake -> {
            config = step.stakeConfig
            vault = step.stakeVault
            pool = step.guardianPool
            mint = null
            stakeAccount = step.userStake
            fixed =
                listOf(
                    step.eventAuthority to addresses.eventAuthority,
                    step.programId to addresses.program,
                )
        }
        is StakingStep.Withdraw -> {
            config = step.stakeConfig
            vault = step.stakeVault
            pool = null
            mint = null
            stakeAccount = step.userStake
            fixed =
                listOf(
                    step.tokenProgram to TOKEN_PROGRAM,
                    step.eventAuthority to addresses.eventAuthority,
                    step.programId to addresses.program,
                )
        }
        else -> return
    }
    if (fixed.any { (named, expected) -> named != expected }) {
        findings += StakingFinding.UnexpectedAccount
    }
    if (config != addresses.stakeConfig || vault != addresses.stakeVault) {
        findings += StakingFinding.OtherDeployment
    }
    if (mint != null && mint != addresses.mint) findings += StakingFinding.OtherDeployment
    if (pool != null && pool != addresses.guardianPool) findings += StakingFinding.OtherGuardianPool
    if (expectedStake != null && stakeAccount != expectedStake) {
        findings += StakingFinding.OtherStakeAccount
    }
}

/**
 * The two operations the program takes an argument for; the other two act on the whole position.
 */
private fun takesAnAmount(operation: StakingOperation): Boolean =
    operation == StakingOperation.STAKING_OPERATION_STAKE ||
        operation == StakingOperation.STAKING_OPERATION_UNSTAKE

private fun matchesOperation(operation: StakingOperation, step: StakingStep): Boolean =
    when (operation) {
        StakingOperation.STAKING_OPERATION_STAKE -> step is StakingStep.Stake
        StakingOperation.STAKING_OPERATION_UNSTAKE -> step is StakingStep.Unstake
        StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE -> step is StakingStep.CancelUnstake
        StakingOperation.STAKING_OPERATION_WITHDRAW -> step is StakingStep.Withdraw
        else -> false
    }

private fun accountedUser(step: StakingStep): String? =
    when (step) {
        is StakingStep.Stake -> step.user
        is StakingStep.Unstake -> step.user
        is StakingStep.CancelUnstake -> step.user
        is StakingStep.Withdraw -> step.user
        else -> null
    }

private fun accountedStake(step: StakingStep): String? =
    when (step) {
        is StakingStep.Stake -> step.userStake
        is StakingStep.Unstake -> step.userStake
        is StakingStep.CancelUnstake -> step.userStake
        is StakingStep.Withdraw -> step.userStake
        else -> null
    }

private fun programOfStep(step: StakingStep): String? =
    when (step) {
        is StakingStep.Other ->
            when (val read = step.read) {
                is ReadInstruction.SolTransfer -> SYSTEM_PROGRAM
                is ReadInstruction.TokenTransfer -> TOKEN_PROGRAM
                is ReadInstruction.CreateTokenAccount -> ASSOCIATED_TOKEN_PROGRAM
                is ReadInstruction.ComputeBudget -> COMPUTE_BUDGET_PROGRAM
                is ReadInstruction.Unrecognized -> read.program
            }
        else -> SKR_STAKING_PROGRAM
    }

private val StakingStep.isRead: Boolean
    get() =
        when (this) {
            is StakingStep.UnreadableStaking -> false
            is StakingStep.Other -> read !is ReadInstruction.Unrecognized
            else -> true
        }

/**
 * The cooldown a pending unstake is measured against: the one the configuration names right now.
 *
 * The chain records when an unstake started but not which cooldown it started under, and the
 * program itself compares against the configuration's current value — so this is the same number
 * the program will use, not an approximation of a historical one.
 */
private fun SkrUserStake.unstakeCooldown(reading: SkrReading): Long =
    reading.config.cooldownSeconds.toLong()

/** The most an unstake may be worth and still be the amount the owner approved. */
private fun allowance(requested: ULong): ULong {
    val slack = requested / 10_000UL * UNSTAKE_TOLERANCE_BPS.toULong()
    return requested + if (slack < 1UL) 1UL else slack
}

/** What [shares] are worth at [price], floored as the program floors. Null if it exceeds a u64. */
private fun sharesToAmount(shares: BigInteger, price: BigInteger): ULong? {
    if (shares.signum() < 0) return null
    val scaled = shares.multiply(price).divide(BigInteger.valueOf(SHARE_PRICE_SCALE))
    if (scaled.bitLength() > 64) return null
    return scaled.toLong().toULong()
}
