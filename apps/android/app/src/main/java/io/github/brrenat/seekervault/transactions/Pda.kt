package io.github.brrenat.seekervault.transactions

import io.github.brrenat.seekervault.wallet.decodeBase58
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Program-derived addresses, worked out on the phone. A token transfer names token accounts rather
 * than wallets, and the only way to know that an account really belongs to the recipient the
 * request names is to derive it here and compare. Deriving needs no chain and no server, which is
 * what makes it independent evidence (docs/security.md#inspecting-a-transfer).
 */

/** The 16 bytes appended to every program-derived address, so one can never be a real key. */
private const val PDA_MARKER = "ProgramDerivedAddress"

// Ed25519's field prime, 2^255 - 19, and the curve constant d = -121665/121666 mod p.
// BigInteger.TWO needs API 33, and the app supports 31.
private val P = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)
private val D =
    BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")

/**
 * Whether 32 bytes decompress to a point on Ed25519, which is what tells a real public key from a
 * program-derived address. A derived address must be off the curve: if it were on it, some private
 * key could sign for it.
 *
 * The check is the standard decompression: with `y` from the bytes, an `x` exists only when
 * `(y² - 1) / (d·y² + 1)` is a square in the field.
 */
fun isOnCurve(key: ByteArray): Boolean {
    if (key.size != PUBLIC_KEY_BYTES) return false
    val little = key.copyOf()
    val sign = (little[31].toInt() shr 7) and 1
    little[31] = (little[31].toInt() and 0x7f).toByte()
    val y = BigInteger(1, little.reversedArray())
    // A y at or above the prime is not a canonical encoding of any point.
    if (y >= P) return false
    val ySquared = y.multiply(y).mod(P)
    val u = ySquared.subtract(BigInteger.ONE).mod(P)
    val v = D.multiply(ySquared).add(BigInteger.ONE).mod(P)
    if (v.signum() == 0) return false
    val xSquared = u.multiply(v.modInverse(P)).mod(P)
    // x = 0 is a point only with the sign bit clear; dalek refuses the other spelling of it.
    if (xSquared.signum() == 0) return sign == 0
    // Euler's criterion: a non-zero square has (p-1)/2 as its exponent of one.
    return xSquared.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P) == BigInteger.ONE
}

/**
 * The address a program derives from [seeds], and the bump that produced it, or null in the
 * vanishingly unlikely case that no bump works. It is the same search the on-chain runtime does:
 * the highest bump whose hash is off the curve.
 */
fun findProgramAddress(seeds: List<ByteArray>, programId: String): Pair<String, Int>? {
    val program = decodeBase58(programId) ?: return null
    for (bump in 255 downTo 0) {
        val digest = MessageDigest.getInstance("SHA-256")
        seeds.forEach(digest::update)
        digest.update(byteArrayOf(bump.toByte()))
        digest.update(program)
        digest.update(PDA_MARKER.toByteArray(Charsets.UTF_8))
        val candidate = digest.digest()
        if (!isOnCurve(candidate)) return encodeBase58(candidate) to bump
    }
    return null
}

/**
 * [owner]'s associated token account for [mint] under the classic SPL Token program: the one
 * account a supported token transfer may read or create for a wallet. Returns null when either
 * address isn't base58.
 */
fun associatedTokenAddress(owner: String, mint: String): String? {
    val ownerKey = decodeBase58(owner)?.takeIf { it.size == PUBLIC_KEY_BYTES } ?: return null
    val mintKey = decodeBase58(mint)?.takeIf { it.size == PUBLIC_KEY_BYTES } ?: return null
    val tokenProgram = decodeBase58(TOKEN_PROGRAM) ?: return null
    return findProgramAddress(
            listOf(ownerKey, tokenProgram, mintKey),
            ASSOCIATED_TOKEN_PROGRAM,
        )
        ?.first
}
