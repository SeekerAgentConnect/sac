package io.github.brrenat.seekervault.transactions

/**
 * The message region of a wire transaction: everything the signatures cover, which is everything
 * after them (SEE-165). The approved bytes carry empty signature slots and the chain's copy carries
 * the wallet's, so two transactions are the same approved transaction exactly when these bytes are
 * equal — the server-side standard (`isApprovedTransaction`), read here by the one parser there is.
 *
 * Null when the bytes aren't a transaction this can take apart: no signature slot, a count past any
 * real transaction's, or nothing after the signatures.
 */
fun messageBytes(transaction: ByteArray): ByteArray? {
    val reader = Reader(transaction)
    val count = reader.compactU16() ?: return null
    if (count < 1 || count > MOST_SIGNATURES) return null
    reader.bytes(count * SIGNATURE_BYTES) ?: return null
    if (reader.remaining == 0) return null
    return transaction.copyOfRange(reader.offset, transaction.size)
}

/** The recent blockhash a transaction names, in base58, or null when it can't be read. */
fun recentBlockhashOf(transaction: ByteArray): String? =
    (decodeTransaction(transaction, resolvable = true) as? DecodeResult.Decoded)
        ?.transaction
        ?.recentBlockhash

/** The most signatures read before giving up; every transaction this app sees has one. */
private const val MOST_SIGNATURES = 64
