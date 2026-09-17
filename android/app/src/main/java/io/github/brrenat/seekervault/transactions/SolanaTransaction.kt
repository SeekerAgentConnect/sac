package io.github.brrenat.seekervault.transactions

import io.github.brrenat.seekervault.wallet.encodeBase58

/**
 * Decoding a Solana transaction on the phone (docs/security.md#inspecting-a-transfer). This is what
 * makes the owner's review independent: the app reads the bytes the wallet would sign, and takes
 * nothing about them from the sidecar that built them.
 *
 * The parser only reads. It cannot sign, cannot reach a network, and holds no key. It refuses
 * anything it can't account for byte for byte, because a transaction that is only partly understood
 * must never be shown as understood.
 */

/** How many bytes an Ed25519 signature and a public key take. */
const val SIGNATURE_BYTES = 64
const val PUBLIC_KEY_BYTES = 32

/** One instruction, exactly as the message carries it: indexes into the account list, and data. */
data class DecodedInstruction(
    val programIdIndex: Int,
    val accountIndexes: List<Int>,
    val data: ByteArray,
) {
    override fun equals(other: Any?) =
        other is DecodedInstruction &&
            programIdIndex == other.programIdIndex &&
            accountIndexes == other.accountIndexes &&
            data.contentEquals(other.data)

    override fun hashCode() =
        (programIdIndex * 31 + accountIndexes.hashCode()) * 31 + data.contentHashCode()
}

/**
 * A decoded transaction. [accounts] holds the base58 addresses the message carries, in order:
 * `accounts[0]` is the fee payer, and the first [requiredSignatures] of them are the accounts that
 * must sign.
 */
data class DecodedTransaction(
    /** 0 for a versioned (v0) message; null for a legacy one, which has no version byte. */
    val version: Int?,
    /** How many signature slots the transaction carries, one per required signer. */
    val signatureCount: Int,
    /**
     * Which signature slots are still empty, by index, so a caller can say *whose* signature is
     * missing rather than only whether any are (SEE-94).
     *
     * A transfer or a swap is handed over with every slot empty. A provider that co-signs an order
     * hands over one already filled, and the difference between "one signature is missing and it is
     * the owner's" and "something else will sign alongside them" is exactly what this makes
     * checkable.
     */
    val emptySignatures: List<Int>,
    val requiredSignatures: Int,
    val readonlySignedAccounts: Int,
    val readonlyUnsignedAccounts: Int,
    val accounts: List<String>,
    val recentBlockhash: String,
    val instructions: List<DecodedInstruction>,
    /**
     * The address lookup tables the message loads its remaining accounts from, in the order it
     * names them, with the indexes it takes from each (SEE-94).
     *
     * A message with any of these is **not self-contained**: [accounts] holds only the addresses
     * written into it, and every index at or beyond that list resolves through these tables. What
     * the transaction touches therefore cannot be established from the bytes alone, which is why
     * [decodeTransaction] refuses such a message unless the caller says it can resolve them —
     * reading a table means reading the chain (`solana/AddressLookupTables.kt`).
     */
    val lookups: List<TableLookup> = emptyList(),
) {
    /** How many tables the message names. Zero for a message that is self-contained. */
    val addressTableLookups: Int
        get() = lookups.size

    /** True when every signature slot is still empty, which is what a sidecar must hand over. */
    val unsigned: Boolean
        get() = emptySignatures.size == signatureCount

    /** The accounts that have to sign, in order; the first is the fee payer. */
    val signers: List<String>
        get() = accounts.take(requiredSignatures)

    /** The fee payer, which is always the first account. */
    val feePayer: String?
        get() = accounts.firstOrNull()

    /** The program an instruction runs, or null when its index is outside the account list. */
    fun programOf(instruction: DecodedInstruction): String? =
        accounts.getOrNull(instruction.programIdIndex)

    /** The accounts an instruction names, or null when any index is outside the account list. */
    fun accountsOf(instruction: DecodedInstruction): List<String>? =
        instruction.accountIndexes.map { accounts.getOrNull(it) ?: return null }
}

/**
 * One address lookup table a message loads accounts from: the table's own account, and the indexes
 * into it that the message uses (SEE-94).
 *
 * The order matters and is the runtime's: every table's writable indexes come first, in the order
 * the message names the tables, and then every table's readonly indexes. An account list rebuilt in
 * any other order would resolve each instruction's indexes to the wrong addresses, which is why the
 * rebuilding is one function with its own tests rather than a step in a reviewer.
 */
data class TableLookup(
    val table: String,
    val writable: List<Int>,
    val readonly: List<Int>,
)

/** Why a transaction could not be read. Each one is a refusal, never a warning. */
enum class DecodeFailure {
    /** The bytes ran out, a length didn't fit, or something was left over at the end. */
    Malformed,
    /** A message version this app doesn't read. Only legacy and v0 exist today. */
    UnsupportedVersion,
    /** The message loads accounts from an address lookup table, which can't be checked offline. */
    AddressTableLookup,
}

sealed interface DecodeResult {
    data class Decoded(val transaction: DecodedTransaction) : DecodeResult

    data class Failed(val failure: DecodeFailure) : DecodeResult
}

/**
 * Reads a serialized transaction: the signature array, then the message. Returns [DecodeResult] so
 * a failure carries its reason; the caller never sees a half-read transaction.
 *
 * [resolvable] is how a caller says it can read the chain, and it defaults to false so that every
 * reviewer which reads bytes and nothing else keeps refusing a message it could not account for
 * (SAW-020's transfers, SEE-93's swaps). With it set, a message that names address lookup tables
 * comes back with them listed in [DecodedTransaction.lookups] for the caller to resolve — and such
 * a transaction is **not** readable until they are (SEE-94).
 */
fun decodeTransaction(bytes: ByteArray, resolvable: Boolean = false): DecodeResult {
    val reader = Reader(bytes)
    val signatureCount = reader.compactU16() ?: return failed(DecodeFailure.Malformed)
    val empty = mutableListOf<Int>()
    repeat(signatureCount) { slot ->
        val signature = reader.bytes(SIGNATURE_BYTES) ?: return failed(DecodeFailure.Malformed)
        if (signature.all { it.toInt() == 0 }) empty += slot
    }

    // A versioned message starts with the high bit set; a legacy one starts with its header, whose
    // first byte is a signer count and so is always below 0x80.
    val first = reader.byte() ?: return failed(DecodeFailure.Malformed)
    val version: Int?
    val requiredSignatures: Int
    if (first and 0x80 != 0) {
        version = first and 0x7f
        if (version != 0) return failed(DecodeFailure.UnsupportedVersion)
        requiredSignatures = reader.byte() ?: return failed(DecodeFailure.Malformed)
    } else {
        version = null
        requiredSignatures = first
    }
    val readonlySigned = reader.byte() ?: return failed(DecodeFailure.Malformed)
    val readonlyUnsigned = reader.byte() ?: return failed(DecodeFailure.Malformed)

    val accountCount = reader.compactU16() ?: return failed(DecodeFailure.Malformed)
    val accounts =
        List(accountCount) {
            val key = reader.bytes(PUBLIC_KEY_BYTES) ?: return failed(DecodeFailure.Malformed)
            encodeBase58(key)
        }
    val blockhash = reader.bytes(PUBLIC_KEY_BYTES) ?: return failed(DecodeFailure.Malformed)

    val instructionCount = reader.compactU16() ?: return failed(DecodeFailure.Malformed)
    val instructions =
        List(instructionCount) {
            val programIdIndex = reader.byte() ?: return failed(DecodeFailure.Malformed)
            val accountIndexCount = reader.compactU16() ?: return failed(DecodeFailure.Malformed)
            val indexes =
                List(accountIndexCount) {
                    reader.byte() ?: return failed(DecodeFailure.Malformed)
                }
            val dataLength = reader.compactU16() ?: return failed(DecodeFailure.Malformed)
            val data = reader.bytes(dataLength) ?: return failed(DecodeFailure.Malformed)
            DecodedInstruction(programIdIndex, indexes, data)
        }

    val lookups = mutableListOf<TableLookup>()
    if (version == 0) {
        val tables = reader.compactU16() ?: return failed(DecodeFailure.Malformed)
        // Each entry is the table's address and the indexes it supplies, writable then readonly.
        // They are read rather than skipped so that a message which really uses a table is
        // reported as using one — which is what the owner is shown, and the difference between
        // "this loads accounts I cannot see" and "these bytes are not a transaction" (SEE-93) —
        // and so that a caller which can read the chain has what it needs to resolve them (SEE-94).
        repeat(tables) {
            val table = reader.bytes(PUBLIC_KEY_BYTES) ?: return failed(DecodeFailure.Malformed)
            val writable = reader.compactU16() ?: return failed(DecodeFailure.Malformed)
            val writableIndexes = reader.bytes(writable) ?: return failed(DecodeFailure.Malformed)
            val readonly = reader.compactU16() ?: return failed(DecodeFailure.Malformed)
            val readonlyIndexes = reader.bytes(readonly) ?: return failed(DecodeFailure.Malformed)
            lookups +=
                TableLookup(
                    table = encodeBase58(table),
                    writable = writableIndexes.map { it.toInt() and 0xff },
                    readonly = readonlyIndexes.map { it.toInt() and 0xff },
                )
        }
    }
    // Every byte must be accounted for. Anything left over is content nobody read, and content
    // nobody read is exactly what must not be approved.
    if (!reader.exhausted) return failed(DecodeFailure.Malformed)
    if (lookups.isNotEmpty() && !resolvable) return failed(DecodeFailure.AddressTableLookup)
    if (requiredSignatures == 0 || accounts.size < requiredSignatures) {
        return failed(DecodeFailure.Malformed)
    }
    if (readonlySigned > requiredSignatures) return failed(DecodeFailure.Malformed)
    if (readonlySigned + readonlyUnsigned > accounts.size) return failed(DecodeFailure.Malformed)

    return DecodeResult.Decoded(
        DecodedTransaction(
            version = version,
            signatureCount = signatureCount,
            emptySignatures = empty,
            requiredSignatures = requiredSignatures,
            readonlySignedAccounts = readonlySigned,
            readonlyUnsignedAccounts = readonlyUnsigned,
            accounts = accounts,
            recentBlockhash = encodeBase58(blockhash),
            instructions = instructions,
            lookups = lookups,
        )
    )
}

private fun failed(failure: DecodeFailure): DecodeResult = DecodeResult.Failed(failure)
