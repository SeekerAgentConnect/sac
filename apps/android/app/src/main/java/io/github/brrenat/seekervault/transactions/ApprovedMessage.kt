package io.github.brrenat.seekervault.transactions

/**
 * Whether the message the chain holds is the one the owner approved (SEE-165), allowing for what a
 * wallet adds of its own before it sends.
 *
 * A wallet asked to sign and send may append instructions to the transaction it was handed: Seeker
 * Wallet adds a compute-unit limit and price, and Lighthouse assertions that make the transaction
 * fail if the accounts it touches changed. Neither moves value or acts for anybody; both only bound
 * or abort what was approved. A byte-for-byte comparison calls such a transaction a different one,
 * so this compares what the owner approved and only that:
 *
 * - the same message version, the same signers in the same order with the same writability, and the
 *   same fee payer, which is the first signer;
 * - the same recent blockhash and the same address lookup tables, each with the same indexes;
 * - with every [WALLET_ADDED_PROGRAMS] instruction left out of both, the same instructions in the
 *   same order: the same program, the same data, and the same accounts in the same positions, each
 *   with the same signer and writable flags.
 *
 * Any other difference — another program's instruction, an account moved or made writable, a
 * changed amount, another signer — is not a match. Bytes this can't take apart are not a match
 * either: a result is never settled on a guess.
 */
fun isApprovedMessage(approved: ByteArray, onChain: ByteArray): Boolean {
    if (approved.contentEquals(onChain)) return true
    val mine = messageOf(approved) ?: return false
    val theirs = messageOf(onChain) ?: return false
    if (mine.version != theirs.version) return false
    if (mine.recentBlockhash != theirs.recentBlockhash) return false
    if (mine.lookups != theirs.lookups) return false
    if (mine.requiredSignatures != theirs.requiredSignatures) return false
    if (mine.signers != theirs.signers) return false
    if (mine.signers.indices.any { mine.writable(it) != theirs.writable(it) }) return false
    val kept = mine.instructions.filterNot { mine.programOf(it) in WALLET_ADDED_PROGRAMS }
    val sent = theirs.instructions.filterNot { theirs.programOf(it) in WALLET_ADDED_PROGRAMS }
    if (kept.size != sent.size) return false
    return kept.zip(sent).all { (a, b) -> mine.sameInstruction(a, theirs, b) }
}

/**
 * The programs a wallet may add instructions for without making the transaction another one: the
 * compute budget, and Lighthouse, whose instructions only assert account state and abort the
 * transaction when it differs.
 */
val WALLET_ADDED_PROGRAMS: Set<String> = setOf(COMPUTE_BUDGET_PROGRAM, LIGHTHOUSE_PROGRAM)

/** Lighthouse, the assertion program wallets append to guard what a transaction may change. */
const val LIGHTHOUSE_PROGRAM: String = "L2TExMFKdjpN9kozasaurPirfHy9P8sbXoAN1qA3S95"

// A message alone, read with the same parser a whole transaction is: it is given an empty
// signature section, which the parser reads as no slots.
private fun messageOf(message: ByteArray): DecodedTransaction? =
    (decodeTransaction(byteArrayOf(0) + message, resolvable = true) as? DecodeResult.Decoded)
        ?.transaction

private fun DecodedTransaction.sameInstruction(
    mine: DecodedInstruction,
    theirs: DecodedTransaction,
    other: DecodedInstruction,
): Boolean {
    if (!mine.data.contentEquals(other.data)) return false
    if (reference(mine.programIdIndex) != theirs.reference(other.programIdIndex)) return false
    if (mine.accountIndexes.size != other.accountIndexes.size) return false
    return mine.accountIndexes.zip(other.accountIndexes).all { (a, b) ->
        reference(a) == theirs.reference(b) &&
            (a < requiredSignatures) == (b < theirs.requiredSignatures) &&
            writable(a) == theirs.writable(b)
    }
}

/**
 * What an account index names, comparable across two messages: a written-in address, or a position
 * among the accounts the lookup tables load — which name the same address in both when both load
 * the same tables with the same indexes, as [isApprovedMessage] requires.
 */
private fun DecodedTransaction.reference(index: Int): String? =
    accounts.getOrNull(index)
        ?: (index - accounts.size).takeIf { it >= 0 && it < loadedCount }?.let { "loaded:$it" }

private val DecodedTransaction.loadedCount: Int
    get() = lookups.sumOf { it.writable.size + it.readonly.size }

/** Whether the account at [index] is writable, by the message header and the tables' own split. */
private fun DecodedTransaction.writable(index: Int): Boolean {
    val written = accounts.size
    return when {
        index < requiredSignatures -> index < requiredSignatures - readonlySignedAccounts
        index < written -> index < written - readonlyUnsignedAccounts
        else -> index - written < lookups.sumOf { it.writable.size }
    }
}
