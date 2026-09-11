package io.github.brrenat.seekervault.wallet

import java.math.BigInteger

private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/**
 * Encodes bytes as base58 in the alphabet Solana writes addresses in. The sidecar's
 * `requests/action.ts` encodes the same way, so both sides write one address identically.
 */
fun encodeBase58(bytes: ByteArray): String {
    val text = StringBuilder()
    var value = BigInteger(1, bytes)
    val base = BigInteger.valueOf(58L)
    while (value.signum() > 0) {
        val (next, digit) = value.divideAndRemainder(base)
        text.append(ALPHABET[digit.toInt()])
        value = next
    }
    // Each leading zero byte is written as "1".
    for (byte in bytes) {
        if (byte.toInt() != 0) break
        text.append(ALPHABET[0])
    }
    return text.reverse().toString()
}
