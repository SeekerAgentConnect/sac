package io.github.brrenat.seekervault.transactions

import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.TransferAction
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.security.MessageDigest

/**
 * Checking a prepared transfer against the request the agent made and the wallet the owner selected
 * (docs/security.md#inspecting-a-transfer).
 *
 * Everything here is derived from the transaction's own bytes. The sidecar's description of what it
 * built is never read, and never treated as evidence: it is the agent's and the server's account of
 * the transaction, and this is the transaction.
 */

/** The request's transfer, or null when it isn't a transfer request. */
fun ActionRequest.transfer(): TransferAction? =
    if (action.kindCase == Action.KindCase.TRANSFER) action.transfer else null

/** The mint a transfer sends, or null for native SOL. */
fun TransferAction.mint(): String? =
    if (asset.kindCase == Asset.KindCase.TOKEN_MINT) asset.tokenMint else null

/** Lamports have nine decimals; SOL is shown with them and a token with the mint's own. */
const val LAMPORT_DECIMALS = 9

/** What the app found wrong, or could not account for, in a prepared transaction. */
enum class Finding {
    /** The sidecar's own content hash isn't the SHA-256 of the bytes it sent. */
    HashMismatch,
    /** The bytes aren't a transaction this app can read at all. */
    Malformed,
    /** A message version this app doesn't read. */
    UnsupportedVersion,
    /** The message loads accounts from a lookup table, so what it touches can't be seen offline. */
    AddressTableLookup,
    /** A signature slot is already filled. The wallet must be handed something unsigned. */
    AlreadySigned,
    /** The fee payer isn't the owner's wallet. */
    FeePayerNotTheWallet,
    /** Something other than the owner's wallet would have to sign. */
    ExtraSigner,
    /**
     * No wallet is connected on this phone, so there is nothing to check the transaction against.
     */
    NoWallet,
    /** The request names a wallet other than the one connected. */
    OtherWallet,
    /** The request's network isn't the one the connected wallet was selected for. */
    NetworkMismatch,
    /** The transaction contains no transfer of the asset the request names. */
    NoTransfer,
    /** The transaction moves funds more than once, or moves something else as well. */
    ExtraTransfer,
    /** The transfer goes somewhere other than the recipient the request names. */
    RecipientMismatch,
    /** The amount in the transaction isn't the amount the request names. */
    AmountMismatch,
    /** The token in the transaction isn't the mint the request names. */
    MintMismatch,
    /** The tokens would come from an account that isn't the owner's own for this mint. */
    SourceNotOwnersAccount,
    /** The tokens would go to an account that isn't the recipient's own for this mint. */
    DestinationNotRecipientsAccount,
    /**
     * The destination is the address the recipient's associated token account derives to, but
     * nothing in the transaction makes the chain check that it is still theirs. A classic SPL token
     * account's authority can be handed to somebody else after its address was derived, so the
     * address alone establishes nothing about who receives the tokens.
     */
    DestinationOwnerUnchecked,
    /** A token account would be created for someone other than the recipient. */
    AccountCreationForSomeoneElse,
    /** The transaction contains an instruction this app can't read. It is not thereby safe. */
    UnrecognizedInstruction,
    /**
     * A program that moves value — System, either token program, or the associated-account program
     * — is asked to do something this app doesn't read. A program being one a transfer may call is
     * no reason to accept every instruction it offers: `Approve` hands a delegate the account, and
     * `SetAuthority` hands over the account itself.
     */
    UnreadableValueInstruction,
}

/** The programs that can move the owner's funds, or hand someone else the power to. */
private val VALUE_PROGRAMS =
    setOf(SYSTEM_PROGRAM, TOKEN_PROGRAM, TOKEN_2022_PROGRAM, ASSOCIATED_TOKEN_PROGRAM)

/**
 * Whether a finding means the preparation must not be approved, or only that the review doesn't
 * cover all of it. Only [Finding.UnrecognizedInstruction] is the latter: everything else is a
 * disagreement between the bytes and the request, and no disagreement is acceptable.
 */
val Finding.invalidates: Boolean
    get() = this != Finding.UnrecognizedInstruction

/** What the transaction turned out to do, as far as the app could read it. */
data class TransferFacts(
    /** The account that pays the fee and signs: the only signer a supported transfer has. */
    val payer: String,
    /**
     * The wallet the funds provably reach, or null when the bytes don't establish one. For SOL it
     * is the account the instruction names. For a token it is a wallet only when the transaction
     * itself has the chain establish that the destination token account is that wallet's, which an
     * address on its own never does.
     */
    val recipient: String?,
    /** For a token transfer, the token account the tokens go to; null for SOL. */
    val destinationAccount: String?,
    /** The amount in the asset's base units, exactly as the instruction carries it. */
    val amount: ULong,
    /** The token's mint address, or null for native SOL. */
    val mint: String?,
    /**
     * The decimals used to show the amount: nine for SOL, and for a token the value the
     * TransferChecked instruction carries, which the token program refuses unless it matches the
     * mint on chain.
     */
    val decimals: Int,
    /**
     * True when the transaction makes the chain vouch for the recipient's token account for this
     * mint: the associated-account program re-derives the address, creates the account if it is
     * missing, and fails the whole transaction unless it is the recipient's for this mint.
     */
    val ensuresRecipientAccount: Boolean,
    /**
     * Every program the transaction calls, in the order they first appear, including the ones whose
     * instructions the app couldn't read: an instruction it can't read still says which program
     * runs it. Compute budget is in the list like any other, because a policy that names the
     * programs a transaction may call is a list of what it may call, not a list with exceptions.
     */
    val programs: List<String>,
    val computeUnitLimit: UInt?,
    /** A priority price the owner would also pay, in micro-lamports per compute unit. */
    val computeUnitPrice: ULong?,
    val blockhash: String,
    val instructionCount: Int,
    /**
     * How many of [instructionCount] the app read. Fewer means the review doesn't cover them all.
     */
    val recognizedInstructions: Int,
)

/** The verdict on a prepared transaction. */
enum class Verdict {
    /** Every byte was read, and everything matches the request. */
    Verified,
    /** The transfer matches, but the transaction also does something the app can't read. */
    Unverified,
    /** The bytes and the request disagree, or the bytes can't be read. Do not approve. */
    Invalid,
}

/** The result of inspecting one prepared transaction. */
data class TransferInspection(
    val verdict: Verdict,
    val findings: List<Finding>,
    /** What the transaction does, when enough of it could be read to say. */
    val facts: TransferFacts?,
    /** The version this inspection was made from; an approval names the same one. */
    val version: Int,
) {
    /**
     * Whether this preparation may be put in front of the owner to approve. Only a transaction the
     * app read completely qualifies: an unread instruction is a gap in the review, and a review
     * with a gap in it is not a review.
     */
    val approvable: Boolean
        get() = verdict == Verdict.Verified
}

/**
 * Reads [prepared] and checks it against [request] and [wallet].
 *
 * The order matters: the bytes are read first and judged afterwards, so nothing the sidecar said
 * about them can steer the reading.
 */
fun inspectTransfer(
    request: ActionRequest,
    prepared: PreparedTransaction,
    wallet: SelectedWallet?,
): TransferInspection {
    val findings = mutableListOf<Finding>()
    val action =
        request.transfer()
            ?: return TransferInspection(
                Verdict.Invalid,
                listOf(Finding.NoTransfer),
                null,
                prepared.version,
            )
    val bytes = prepared.transaction.toByteArray()

    // The sidecar's hash is not evidence about the bytes; it is only a claim, and a claim that
    // doesn't match its own bytes says the preparation can't be trusted to be self-consistent.
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    if (!digest.contentEquals(prepared.contentHash.toByteArray())) findings += Finding.HashMismatch

    val decoded =
        when (val result = decodeTransaction(bytes)) {
            is DecodeResult.Decoded -> result.transaction
            is DecodeResult.Failed -> {
                findings +=
                    when (result.failure) {
                        DecodeFailure.Malformed -> Finding.Malformed
                        DecodeFailure.UnsupportedVersion -> Finding.UnsupportedVersion
                        DecodeFailure.AddressTableLookup -> Finding.AddressTableLookup
                    }
                return TransferInspection(Verdict.Invalid, findings, null, prepared.version)
            }
        }

    if (!decoded.unsigned) findings += Finding.AlreadySigned
    if (wallet == null) findings += Finding.NoWallet
    else {
        if (wallet.address != action.wallet) findings += Finding.OtherWallet
        if (wallet.network.network != action.network) findings += Finding.NetworkMismatch
    }
    // The owner's wallet pays, and nothing else signs. A second signer would be someone else's
    // approval riding along with theirs.
    if (decoded.feePayer != action.wallet) findings += Finding.FeePayerNotTheWallet
    if (decoded.signers != listOf(action.wallet)) findings += Finding.ExtraSigner

    val read = decoded.instructions.map { decoded.read(it) }
    if (read.any { it == null }) {
        findings += Finding.Malformed
        return TransferInspection(Verdict.Invalid, findings, null, prepared.version)
    }
    val instructions = read.filterNotNull()
    val unrecognized = instructions.filterIsInstance<ReadInstruction.Unrecognized>()
    if (unrecognized.any { it.program in VALUE_PROGRAMS }) {
        findings += Finding.UnreadableValueInstruction
    }
    if (unrecognized.any { it.program !in VALUE_PROGRAMS }) {
        findings += Finding.UnrecognizedInstruction
    }

    val mint = action.mint()
    val amount = action.amount.toULongOrNull()
    if (amount == null) findings += Finding.AmountMismatch
    val budget = instructions.filterIsInstance<ReadInstruction.ComputeBudget>()
    val facts =
        if (mint == null) {
            inspectSol(action, amount, decoded, instructions, budget, findings)
        } else {
            inspectToken(action, mint, amount, decoded, instructions, budget, findings)
        }

    val verdict =
        when {
            findings.any { it.invalidates } || facts == null -> Verdict.Invalid
            findings.isNotEmpty() -> Verdict.Unverified
            else -> Verdict.Verified
        }
    return TransferInspection(verdict, findings.distinct(), facts, prepared.version)
}

private fun inspectSol(
    action: TransferAction,
    amount: ULong?,
    decoded: DecodedTransaction,
    instructions: List<ReadInstruction>,
    budget: List<ReadInstruction.ComputeBudget>,
    findings: MutableList<Finding>,
): TransferFacts? {
    // Anything that moves value has to be the one transfer the request asked for, whatever program
    // it belongs to: one request, one movement.
    val tokenMoves = instructions.filterIsInstance<ReadInstruction.TokenTransfer>()
    val solMoves = instructions.filterIsInstance<ReadInstruction.SolTransfer>()
    if (tokenMoves.isNotEmpty() || solMoves.size > 1) findings += Finding.ExtraTransfer
    val move = solMoves.firstOrNull()
    if (move == null) {
        findings += Finding.NoTransfer
        return null
    }
    if (move.from != action.wallet) findings += Finding.FeePayerNotTheWallet
    if (move.to != action.recipient) findings += Finding.RecipientMismatch
    if (amount == null || move.lamports != amount) findings += Finding.AmountMismatch
    if (instructions.any { it is ReadInstruction.CreateTokenAccount }) {
        findings += Finding.ExtraTransfer
    }
    return facts(
        decoded = decoded,
        instructions = instructions,
        budget = budget,
        // Every fact comes out of the instruction, never out of the request it is checked against.
        recipient = move.to,
        destinationAccount = null,
        amount = move.lamports,
        mint = null,
        decimals = LAMPORT_DECIMALS,
        ensuresAccount = false,
    )
}

private fun inspectToken(
    action: TransferAction,
    mint: String,
    amount: ULong?,
    decoded: DecodedTransaction,
    instructions: List<ReadInstruction>,
    budget: List<ReadInstruction.ComputeBudget>,
    findings: MutableList<Finding>,
): TransferFacts? {
    val tokenMoves = instructions.filterIsInstance<ReadInstruction.TokenTransfer>()
    val solMoves = instructions.filterIsInstance<ReadInstruction.SolTransfer>()
    if (solMoves.isNotEmpty() || tokenMoves.size > 1) findings += Finding.ExtraTransfer
    val move = tokenMoves.firstOrNull()
    if (move == null) {
        findings += Finding.NoTransfer
        return null
    }
    if (move.authority != action.wallet) findings += Finding.FeePayerNotTheWallet
    if (move.mint != mint) findings += Finding.MintMismatch
    if (amount == null || move.amount != amount) findings += Finding.AmountMismatch
    // A token account's address is derived from its owner and its mint, so the recipient's account
    // can only be this address. It being this address is necessary, and it is not sufficient.
    val source = associatedTokenAddress(action.wallet, mint)
    val destination = associatedTokenAddress(action.recipient, mint)
    if (source == null || move.source != source) findings += Finding.SourceNotOwnersAccount

    val creations = instructions.filterIsInstance<ReadInstruction.CreateTokenAccount>()
    if (creations.size > 1) findings += Finding.ExtraTransfer
    val creation = creations.firstOrNull()
    // The associated-account instruction is the only thing in the bytes that says anything about
    // who owns the destination now: its program re-derives the address, reads the account, and
    // fails the transaction unless the account's owner and mint are the recipient's. The phone
    // reaches no chain, so this is the whole of its evidence.
    val vouchedFor =
        creation != null &&
            creation.owner == action.recipient &&
            creation.mint == mint &&
            creation.account == destination &&
            creation.payer == action.wallet
    if (creation != null && !vouchedFor) findings += Finding.AccountCreationForSomeoneElse

    // Derivation alone would only say what the account is called. A classic SPL token account's
    // authority can be handed to somebody else afterwards, so an address that derives correctly
    // and nothing else leaves the owner unknown, and unknown is not approvable.
    val provenRecipient =
        if (destination != null && move.destination == destination && vouchedFor) action.recipient
        else null
    if (destination == null || move.destination != destination) {
        findings += Finding.DestinationNotRecipientsAccount
    } else if (!vouchedFor) {
        findings += Finding.DestinationOwnerUnchecked
    }
    return facts(
        decoded = decoded,
        instructions = instructions,
        budget = budget,
        recipient = provenRecipient,
        destinationAccount = move.destination,
        amount = move.amount,
        mint = move.mint,
        decimals = move.decimals,
        ensuresAccount = vouchedFor,
    )
}

private fun facts(
    decoded: DecodedTransaction,
    instructions: List<ReadInstruction>,
    budget: List<ReadInstruction.ComputeBudget>,
    recipient: String?,
    destinationAccount: String?,
    amount: ULong,
    mint: String?,
    decimals: Int,
    ensuresAccount: Boolean,
) =
    TransferFacts(
        payer = decoded.feePayer.orEmpty(),
        recipient = recipient,
        destinationAccount = destinationAccount,
        amount = amount,
        mint = mint,
        decimals = decimals,
        ensuresRecipientAccount = ensuresAccount,
        programs = decoded.instructions.mapNotNull(decoded::programOf).distinct(),
        computeUnitLimit = budget.firstNotNullOfOrNull { it.unitLimit },
        computeUnitPrice = budget.firstNotNullOfOrNull { it.microLamportsPerUnit },
        blockhash = decoded.recentBlockhash,
        instructionCount = instructions.size,
        recognizedInstructions = instructions.count { it !is ReadInstruction.Unrecognized },
    )

/**
 * An amount in base units written with its decimal point, exactly: whole numbers only, never
 * rounded, and never through a floating-point type. Trailing zeros after the point are dropped, so
 * 1500000 base units of a six-decimal token reads as "1.5".
 */
fun formatBaseUnits(amount: ULong, decimals: Int): String {
    if (decimals <= 0) return amount.toString()
    val digits = amount.toString().padStart(decimals + 1, '0')
    val whole = digits.dropLast(decimals)
    val fraction = digits.takeLast(decimals).trimEnd('0')
    return if (fraction.isEmpty()) whole else "$whole.$fraction"
}
