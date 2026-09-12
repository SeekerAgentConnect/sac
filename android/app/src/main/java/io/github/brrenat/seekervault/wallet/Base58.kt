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

/**
 * Decodes base58 back to bytes, or returns null for a character outside the alphabet. The wallet
 * takes an account as its raw 32 key bytes, so an address has to be read back this way.
 */
fun decodeBase58(text: String): ByteArray? {
    if (text.isEmpty()) return null
    var value = BigInteger.ZERO
    val base = BigInteger.valueOf(58L)
    for (char in text) {
        val digit = ALPHABET.indexOf(char)
        if (digit < 0) return null
        value = value.multiply(base).add(BigInteger.valueOf(digit.toLong()))
    }
    // toByteArray() is two's complement, so it can carry a leading zero byte of its own.
    val bytes =
        value.toByteArray().let {
            if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
        }
    val body = if (value.signum() == 0) ByteArray(0) else bytes
    val leadingZeros = text.takeWhile { it == ALPHABET[0] }.length
    return ByteArray(leadingZeros) + body
}
