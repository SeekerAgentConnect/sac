package io.github.brrenat.seekervault.skr

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a staking review verifies, and everything it refuses (SEE-146).
 *
 * The server built the bytes; this reads them again and checks them against the request the owner
 * saw and against the position this phone read for itself. So for each way a transaction could
 * differ from what was asked for, there is one case here that changes exactly that and shows the
 * review refusing it — and no server would supply any of them on request.
 *
 * `approvable` is the gate the Approve button is behind, so every refusal below is also a screen
 * with nothing to press.
 */
class StakingInspectionTest {

    private val stake = StakingOperation.STAKING_OPERATION_STAKE
    private val unstake = StakingOperation.STAKING_OPERATION_UNSTAKE
    private val cancel = StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE
    private val withdraw = StakingOperation.STAKING_OPERATION_WITHDRAW

    /** Long after any cooldown a test starts, unless the test is about the cooldown. */
    private val now = 2_000_000L

    private fun inspect(
        request: ActionRequest,
        transaction: ByteString,
        reading: SkrReading? = reading(),
        wallet: SelectedWallet? = wallet(),
        now: Long = this.now,
        prepared: PreparedTransaction? = null,
    ): StakingInspection =
        inspectStaking(
            request = request,
            prepared = prepared ?: preparedFor(transaction),
            wallet = wallet,
            reading = reading,
            now = now,
        )

    // --- the four, as they are meant to be -------------------------------------------------

    @Test
    fun verifiesAStakeOfExactlyTheAmountThatWasAsked() {
        val request = stakingRequest(stake, amount = "25000000")
        val read = inspect(request, stakingTransaction(stake, amount = 25_000_000UL))
        assertEquals(emptyList<StakingFinding>(), read.findings)
        assertEquals(Verdict.Verified, read.verdict)
        assertTrue(read.approvable)
        val facts = checkNotNull(read.facts)
        assertEquals(25_000_000UL, facts.amount)
        assertEquals(OWNER, facts.wallet)
        assertEquals(OWNER_STAKE, facts.stakeAccount)
        assertEquals(SKR_DECIMALS, facts.decimals)
        // Staking commits tokens the owner holds: it is the one of the four that spends.
        assertTrue(facts.movesValue)
        assertFalse(facts.incoming)
        assertEquals(172_800UL, facts.cooldownSeconds)
        assertEquals(facts.instructionCount, facts.recognizedInstructions)
    }

    @Test
    fun verifiesAPartialUnstakeAndSaysWhatIsLeft() {
        val request = stakingRequest(unstake, amount = "40000000")
        val read =
            inspect(
                request,
                stakingTransaction(unstake, shares = BigInteger.valueOf(40_000_000L)),
            )
        assertEquals(emptyList<StakingFinding>(), read.findings)
        assertTrue(read.approvable)
        val facts = checkNotNull(read.facts)
        assertEquals(40_000_000UL, facts.amount)
        assertEquals(BigInteger.valueOf(40_000_000L), facts.shares)
        // Nothing reaches or leaves the wallet: a position changes and a clock starts.
        assertFalse(facts.movesValue)
        assertFalse(facts.incoming)
        assertEquals(60_000_000UL, facts.remainingStake)
        assertEquals(now + 172_800L, facts.cooldownEndsAt)
        assertFalse(facts.resetsExistingCooldown)
    }

    @Test
    fun verifiesAWholePositionUnstakeOnlyWhenItBurnsTheSharesTheAccountHolds() {
        // A price that does not divide evenly, so reconverting the position's value back into
        // shares would floor a second time and leave dust the owner could not close.
        val position = reading(sharePrice = 1_500_000_000L)
        val whole = BigInteger.valueOf(100_000_000L)
        val request = stakingRequest(unstake, amount = ULong.MAX_VALUE.toString())
        val exact = inspect(request, stakingTransaction(unstake, shares = whole), position)
        assertEquals(emptyList<StakingFinding>(), exact.findings)
        assertEquals(150_000_000UL, checkNotNull(exact.facts).amount)

        val nearly =
            inspect(
                request,
                stakingTransaction(unstake, shares = whole.subtract(BigInteger.ONE)),
                position,
            )
        assertTrue(StakingFinding.NotTheWholePosition in nearly.findings)
        assertFalse(nearly.approvable)
    }

    @Test
    fun verifiesACancellationOnlyWhenSomethingIsPending() {
        val request = stakingRequest(cancel)
        val pending = reading(unstakingAmount = 9_000_000UL, unstakeTimestamp = 1_000_000L)
        val read = inspect(request, stakingTransaction(cancel), pending)
        assertEquals(emptyList<StakingFinding>(), read.findings)
        assertTrue(read.approvable)
        val facts = checkNotNull(read.facts)
        // The program cancels all of it or none, so the amount is the pending one and not a choice.
        assertEquals(9_000_000UL, facts.amount)
        assertFalse(facts.movesValue)
        assertNull(facts.cooldownEndsAt)

        val idle = inspect(request, stakingTransaction(cancel), reading())
        assertTrue(StakingFinding.NothingPending in idle.findings)
        assertFalse(idle.approvable)
    }

    @Test
    fun verifiesAWithdrawalOnceTheCooldownHasFinished() {
        val request = stakingRequest(withdraw)
        val pending = reading(unstakingAmount = 9_000_000UL, unstakeTimestamp = 1_000_000L)
        val read = inspect(request, stakingTransaction(withdraw), pending)
        assertEquals(emptyList<StakingFinding>(), read.findings)
        assertTrue(read.approvable)
        val facts = checkNotNull(read.facts)
        // The only one of the four that brings SKR back, and its amount is the program's record.
        assertTrue(facts.movesValue)
        assertTrue(facts.incoming)
        assertEquals(9_000_000UL, facts.amount)
        assertEquals(1_000_000L + 172_800L, facts.cooldownEndsAt)
        // A withdrawal needs somewhere to land, so the idempotent create is a supporting
        // instruction it may carry — and the review says a token account is being created.
        assertTrue(facts.createsTokenAccount)
    }

    // --- the cooldown ------------------------------------------------------------------------

    @Test
    fun treatsTheBoundarySecondAsReadyBecauseTheProgramDoes() {
        val started = 1_000_000L
        val ends = started + 172_800L
        val request = stakingRequest(withdraw)
        val pending = reading(unstakingAmount = 5_000_000UL, unstakeTimestamp = started)
        val early = inspect(request, stakingTransaction(withdraw), pending, now = ends - 1)
        assertTrue(StakingFinding.CooldownNotFinished in early.findings)
        assertFalse(early.approvable)
        // The program's own check is `now >= timestamp + cooldown`. Anything stricter here would
        // refuse the one second the chain would accept.
        val onTheSecond = inspect(request, stakingTransaction(withdraw), pending, now = ends)
        assertEquals(emptyList<StakingFinding>(), onTheSecond.findings)
        assertTrue(onTheSecond.approvable)
    }

    @Test
    fun readsTheCooldownFromTheChainRatherThanAssumingFortyEightHours() {
        val started = 1_000_000L
        val pending =
            reading(
                cooldownSeconds = 604_800UL,
                unstakingAmount = 5_000_000UL,
                unstakeTimestamp = started,
            )
        val request = stakingRequest(withdraw)
        val read = inspect(request, stakingTransaction(withdraw), pending, now = started + 172_800L)
        assertTrue(StakingFinding.CooldownNotFinished in read.findings)
        assertEquals(started + 604_800L, checkNotNull(read.facts).cooldownEndsAt)
    }

    @Test
    fun warnsThatUnstakingAgainRestartsACooldownThatIsStillRunning() {
        val started = 1_900_000L
        val pending = reading(unstakingAmount = 5_000_000UL, unstakeTimestamp = started)
        val request = stakingRequest(unstake, amount = "10000000")
        val read =
            inspect(
                request,
                stakingTransaction(unstake, shares = BigInteger.valueOf(10_000_000L)),
                pending,
                now = started + 1_000L,
            )
        // It is allowed and it costs the owner the wait they have already served, so it is a
        // warning on the review rather than a refusal.
        assertEquals(emptyList<StakingFinding>(), read.findings)
        assertTrue(read.approvable)
        assertTrue(checkNotNull(read.facts).resetsExistingCooldown)
    }

    @Test
    fun refusesAFurtherUnstakeOnceTheCooldownHasAlreadyFinished() {
        // The program answers this with `WithdrawRequired`, so the review says it first rather
        // than letting a transaction fail after the owner approved it.
        val pending = reading(unstakingAmount = 5_000_000UL, unstakeTimestamp = 1_000_000L)
        val request = stakingRequest(unstake, amount = "10000000")
        val read =
            inspect(
                request,
                stakingTransaction(unstake, shares = BigInteger.valueOf(10_000_000L)),
                pending,
            )
        assertTrue(StakingFinding.WithdrawFirst in read.findings)
        assertFalse(read.approvable)
        assertFalse(checkNotNull(read.facts).resetsExistingCooldown)
    }

    // --- amounts and shares ------------------------------------------------------------------

    @Test
    fun refusesAStakeForMoreThanTheRequestNames() {
        val request = stakingRequest(stake, amount = "25000000")
        val read = inspect(request, stakingTransaction(stake, amount = 25_000_001UL))
        assertTrue(StakingFinding.AmountMismatch in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesSharesWorthMoreThanTheSkrThatWasApproved() {
        // An unstake is asked for in SKR and carried out in shares, so what is checked is what the
        // shares are worth at the price this phone read — not the server's conversion of them.
        val request = stakingRequest(unstake, amount = "10000000")
        val read =
            inspect(
                request,
                stakingTransaction(unstake, shares = BigInteger.valueOf(30_000_000L)),
            )
        assertTrue(StakingFinding.SharesExceedApprovedAmount in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun allowsTheOneBaseUnitAFlooredConversionCosts() {
        // tokens -> shares -> tokens loses up to a unit because the program floors, so a request
        // that is right to the unit must not be refused for being a unit under or over.
        val position = reading(sharePrice = 1_000_000_001L)
        val request = stakingRequest(unstake, amount = "10000000")
        val read =
            inspect(
                request,
                stakingTransaction(unstake, shares = BigInteger.valueOf(10_000_000L)),
                position,
            )
        assertEquals(emptyList<StakingFinding>(), read.findings)
        assertTrue(read.approvable)
    }

    @Test
    fun refusesAnUnstakeFromAPositionThatIsNotThere() {
        val request = stakingRequest(unstake, amount = "10000000")
        val read =
            inspect(
                request,
                stakingTransaction(unstake, shares = BigInteger.valueOf(10_000_000L)),
                reading(shares = null),
            )
        assertTrue(StakingFinding.NothingStaked in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesAStakeBelowTheProgramsMinimumOrAboveTheOwnersBalance() {
        val small = stakingRequest(stake, amount = "500000")
        val below = inspect(small, stakingTransaction(stake, amount = 500_000UL))
        assertTrue(StakingFinding.BelowMinimumStake in below.findings)

        val large = stakingRequest(stake, amount = "600000000")
        val over = inspect(large, stakingTransaction(stake, amount = 600_000_000UL))
        assertTrue(StakingFinding.InsufficientBalance in over.findings)
        assertFalse(over.approvable)
    }

    @Test
    fun refusesAnAmountOnAnOperationThatTakesNone() {
        // Cancelling and withdrawing act on the whole pending unstake. An amount on either
        // describes a choice the program does not offer, and an ignored field is one an agent
        // could believe in.
        val asked = stakingRequest(withdraw)
        val request =
            asked
                .toBuilder()
                .setAction(
                    asked.action
                        .toBuilder()
                        .setStaking(asked.action.staking.toBuilder().setAmount("9000000"))
                )
                .build()
        val pending = reading(unstakingAmount = 9_000_000UL, unstakeTimestamp = 1_000_000L)
        val read = inspect(request, stakingTransaction(withdraw), pending)
        assertTrue(StakingFinding.AmountMismatch in read.findings)
        assertFalse(read.approvable)
    }

    // --- whose transaction it is ---------------------------------------------------------------

    @Test
    fun refusesAPreparationWhoseHashIsNotItsOwnBytes() {
        val request = stakingRequest(stake)
        val bytes = stakingTransaction(stake)
        val lying = preparedFor(bytes, hash = ByteString.copyFrom(ByteArray(32)))
        val read = inspect(request, bytes, prepared = lying)
        assertTrue(StakingFinding.HashMismatch in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesATransactionThatIsAlreadySigned() {
        val request = stakingRequest(stake)
        val read = inspect(request, stakingTransaction(stake, signed = true))
        assertTrue(StakingFinding.AlreadySigned in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesAWalletOrNetworkOtherThanTheConnectedOne() {
        val request = stakingRequest(stake)
        val bytes = stakingTransaction(stake)
        val elsewhere = inspect(request, bytes, wallet = wallet(address = STRANGER))
        assertTrue(StakingFinding.OtherWallet in elsewhere.findings)

        val devnet = inspect(request, bytes, wallet = wallet(network = WalletNetwork.Devnet))
        assertTrue(StakingFinding.NetworkMismatch in devnet.findings)

        val none = inspect(request, bytes, wallet = null)
        assertTrue(StakingFinding.NoWallet in none.findings)
        assertFalse(none.approvable)
    }

    @Test
    fun refusesATransactionSomebodyElsePaysForOrCoSigns() {
        val request = stakingRequest(stake)
        val theirs = inspect(request, stakingTransaction(stake, payer = STRANGER))
        assertTrue(StakingFinding.FeePayerNotTheWallet in theirs.findings)

        val extra = inspect(request, stakingTransaction(stake, signers = listOf(OWNER, STRANGER)))
        assertTrue(StakingFinding.ExtraSigner in extra.findings)
        assertFalse(extra.approvable)
    }

    @Test
    fun refusesAnInstructionActingForSomebodyElsesPosition() {
        val request = stakingRequest(stake)
        // The payer is the owner and the transaction is theirs, but the position being staked into
        // is not: the `user` slot, and the stake account derived from it, are somebody else's.
        val read =
            inspect(
                request,
                stakingTransaction(
                    stake,
                    user = STRANGER,
                    userStake = checkNotNull(ADDRESSES.userStake(STRANGER)),
                ),
            )
        assertTrue(StakingFinding.NotTheOwnersPosition in read.findings)
        assertTrue(StakingFinding.OtherStakeAccount in read.findings)
        assertFalse(read.approvable)
    }

    // --- which deployment it is ----------------------------------------------------------------

    @Test
    fun refusesAnotherProgramsStakingInstruction() {
        // The wire carries no program, so this is the whole point: the phone looks for its own
        // program's instruction and finds none.
        val request = stakingRequest(stake)
        val read =
            inspect(request, stakingTransaction(stake, programId = ELSEWHERE, budget = false))
        assertTrue(StakingFinding.NoStakingInstruction in read.findings)
        assertFalse(read.approvable)
        assertNull(read.facts)
    }

    @Test
    fun refusesAnotherConfigurationVaultOrMint() {
        val request = stakingRequest(stake)
        for (bytes in
            listOf(
                stakingTransaction(stake, stakeConfig = ELSEWHERE),
                stakingTransaction(stake, stakeVault = ELSEWHERE),
                stakingTransaction(stake, mint = ELSEWHERE),
            )) {
            val read = inspect(request, bytes)
            assertTrue(StakingFinding.OtherDeployment in read.findings)
            assertFalse(read.approvable)
        }
    }

    @Test
    fun refusesAnotherGuardiansPool() {
        val request = stakingRequest(stake)
        val read = inspect(request, stakingTransaction(stake, guardianPool = ELSEWHERE))
        assertTrue(StakingFinding.OtherGuardianPool in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesAPoolAccountThatIsNotTheDerivedOnesOwn() {
        // The pool address derives from the configuration and the guardian, so a pool account
        // naming another guardian means the chain reading and the derivation disagree.
        val request = stakingRequest(stake)
        val read = inspect(request, stakingTransaction(stake), reading(guardian = OTHER_GUARDIAN))
        assertTrue(StakingFinding.OtherGuardianPool in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesAPoolThatTakesNoMoreStake() {
        val request = stakingRequest(stake)
        val read = inspect(request, stakingTransaction(stake), reading(active = false))
        assertTrue(StakingFinding.GuardianPoolInactive in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesTheFixedAccountsNobodyHasAReasonToChange() {
        val request = stakingRequest(stake)
        for (bytes in
            listOf(
                stakingTransaction(stake, eventAuthority = ELSEWHERE),
                stakingTransaction(stake, tokenProgram = ELSEWHERE),
                stakingTransaction(stake, systemProgram = ELSEWHERE),
            )) {
            val read = inspect(request, bytes)
            assertTrue(StakingFinding.UnexpectedAccount in read.findings)
            assertFalse(read.approvable)
        }
    }

    @Test
    fun refusesAStakeOutOfAnAccountThatIsNotTheOwnersOwn() {
        val request = stakingRequest(stake)
        val theirs = checkNotNull(associatedTokenAddress(STRANGER, ADDRESSES.mint))
        val read = inspect(request, stakingTransaction(stake, ownerTokenAccount = theirs))
        assertTrue(StakingFinding.OtherTokenAccount in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesAWithdrawalIntoSomebodyElsesAccount() {
        val request = stakingRequest(withdraw)
        val pending = reading(unstakingAmount = 9_000_000UL, unstakeTimestamp = 1_000_000L)
        val theirs = checkNotNull(associatedTokenAddress(STRANGER, ADDRESSES.mint))
        val read =
            inspect(
                request,
                stakingTransaction(withdraw, ownerTokenAccount = theirs, createsAccount = false),
                pending,
            )
        assertTrue(StakingFinding.OtherTokenAccount in read.findings)
        assertFalse(read.approvable)
    }

    // --- what else the transaction contains ----------------------------------------------------

    @Test
    fun refusesATransactionThatDoesADifferentStakingActionThanTheRequest() {
        val request = stakingRequest(unstake, amount = "10000000")
        val read = inspect(request, stakingTransaction(stake, amount = 10_000_000UL))
        assertTrue(StakingFinding.OperationMismatch in read.findings)
        assertFalse(read.approvable)
        assertNull(read.facts)
    }

    @Test
    fun refusesMoreThanOneStakingInstruction() {
        // Which one the owner is approving would not be a single fact.
        val request = stakingRequest(stake)
        val second =
            Step(
                ADDRESSES.program,
                listOf(
                    OWNER_STAKE,
                    ADDRESSES.stakeConfig,
                    ADDRESSES.guardianPool,
                    OWNER,
                    OWNER,
                    OWNER_TOKENS,
                    ADDRESSES.stakeVault,
                    ADDRESSES.mint,
                    TOKEN_PROGRAM,
                    SYSTEM_PROGRAM,
                    ADDRESSES.eventAuthority,
                    ADDRESSES.program,
                ),
                STAKE_DISCRIMINATOR + u64(1_000_000UL),
            )
        val read = inspect(request, stakingTransaction(stake, extra = listOf(second)))
        assertTrue(StakingFinding.ExtraStakingInstruction in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesAFifthInstructionOfTheStakingProgram() {
        val request = stakingRequest(stake)
        val read =
            inspect(
                request,
                stakingTransaction(stake, discriminator = ByteArray(8) { 0x11 }),
            )
        assertTrue(StakingFinding.UnreadableStakingInstruction in read.findings)
        assertFalse(read.approvable)
        assertNull(read.facts)
    }

    @Test
    fun refusesATransferSmuggledInBesideTheStakingInstruction() {
        val request = stakingRequest(stake)
        val sweep =
            tokenTransfer(
                source = OWNER_TOKENS,
                destination = checkNotNull(associatedTokenAddress(STRANGER, ADDRESSES.mint)),
                authority = OWNER,
                amount = 400_000_000UL,
            )
        val read = inspect(request, stakingTransaction(stake, extra = listOf(sweep)))
        assertTrue(StakingFinding.ExtraTransfer in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesATokenAccountCreatedForSomebodyElse() {
        val request = stakingRequest(withdraw)
        val pending = reading(unstakingAmount = 9_000_000UL, unstakeTimestamp = 1_000_000L)
        val read = inspect(request, stakingTransaction(withdraw, createFor = STRANGER), pending)
        assertTrue(StakingFinding.AccountCreationForSomeoneElse in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun refusesAnAccountCreationCarriedByAnythingButAWithdrawal() {
        // A stake pays out of an account the owner already has; one that creates one is doing
        // something the operation does not need.
        val request = stakingRequest(stake)
        val read = inspect(request, stakingTransaction(stake, createsAccount = true))
        assertTrue(StakingFinding.ExtraTransfer in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun namesAnInstructionOfAProgramItDoesNotKnow() {
        val request = stakingRequest(stake)
        val unknown = Step(ELSEWHERE, listOf(OWNER), byteArrayOf(9, 9))
        val read = inspect(request, stakingTransaction(stake, extra = listOf(unknown)))
        // It is not refused outright — the program moves nothing the review can name — but the
        // transaction is no longer fully accounted for, so it cannot be approved either.
        assertTrue(StakingFinding.UnrecognizedInstruction in read.findings)
        assertEquals(Verdict.Unverified, read.verdict)
        assertFalse(read.approvable)
        val facts = checkNotNull(read.facts)
        assertFalse(facts.instructionCount == facts.recognizedInstructions)
    }

    // --- when the chain could not be read ------------------------------------------------------

    @Test
    fun refusesAnUnstakeItCouldNotReadThePositionFor() {
        // A share price is what an unstake means something in terms of. Without one, the server's
        // conversion would be checked against nothing.
        val request = stakingRequest(unstake, amount = "10000000")
        val read =
            inspect(
                request,
                stakingTransaction(unstake, shares = BigInteger.valueOf(10_000_000L)),
                reading = null,
            )
        assertTrue(StakingFinding.PositionNotRead in read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun readsAStakeWithoutTheChainButDoesNotCallItVerified() {
        // Everything a stake says is in its own instruction and in this app's own derivations, so
        // the review still shows the amount and the accounts — it just cannot say the owner holds
        // the SKR, and it says that rather than implying it.
        val request = stakingRequest(stake, amount = "25000000")
        val read = inspect(request, stakingTransaction(stake), reading = null)
        assertEquals(listOf(StakingFinding.BalanceNotRead), read.findings)
        assertEquals(Verdict.Unverified, read.verdict)
        assertFalse(read.approvable)
        assertEquals(25_000_000UL, checkNotNull(read.facts).amount)
    }

    // --- the bytes themselves -------------------------------------------------------------------

    @Test
    fun refusesBytesItCannotRead() {
        val request = stakingRequest(stake)
        val read = inspect(request, ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)))
        assertTrue(StakingFinding.Malformed in read.findings)
        assertFalse(read.approvable)
        assertNull(read.facts)
    }

    @Test
    fun refusesARequestThatIsNotAStakingActionAtAll() {
        val read =
            inspectStaking(
                request = ActionRequest.getDefaultInstance(),
                prepared = preparedFor(stakingTransaction(stake)),
                wallet = wallet(),
                reading = reading(),
                now = now,
            )
        assertEquals(listOf(StakingFinding.NoStakingInstruction), read.findings)
        assertFalse(read.approvable)
    }

    @Test
    fun carriesThePreparedVersionSoAnApprovalNamesTheOneThatWasReviewed() {
        val request = stakingRequest(stake)
        val bytes = stakingTransaction(stake)
        assertEquals(7, inspect(request, bytes, prepared = preparedFor(bytes, version = 7)).version)
    }

    @Test
    fun aDevnetRequestIsRefusedBecauseTheProgramIsOnMainnetOnly() {
        val request = stakingRequest(stake, network = Network.NETWORK_DEVNET)
        val read = inspect(request, stakingTransaction(stake), wallet = wallet())
        assertTrue(StakingFinding.NetworkMismatch in read.findings)
        assertFalse(read.approvable)
    }
}
