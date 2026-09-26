package io.github.brrenat.seekervault.wallet

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification, worked out on the phone (docs/security.md#verifying-a-signature).
 *
 * The wallet is another app, and what it hands back is not evidence until it verifies. A wallet
 * that answered with 64 bytes that aren't a signature used to be believed here and refused by the
 * sidecar, which verifies every signature it is sent — and because the first outcome stored for an
 * approval stands, that answer would have been sent again for ever, leaving the request PROCESSING
 * with nothing able to settle it. Checking here turns that into one terminal failure the owner and
 * the agent can both read.
 *
 * This holds no key and makes no signature: verification needs the public key, the message, and the
 * signature, all of which are public. It is written out rather than taken from the platform because
 * `Signature.getInstance("Ed25519")` arrives in API 33 and this app supports 31, and a check that
 * quietly doesn't happen on some phones is not a check. Nothing here is secret, so nothing here
 * needs to be constant-time.
 *
 * The rules are RFC 8032's, with the strictness the sidecar's verifier (OpenSSL) also applies: the
 * scalar `S` must be canonical, both points must be canonically encoded, and the equation checked
 * is the cofactorless `[S]B = R + [k]A`.
 */

/** The field prime, 2^255 - 19. `BigInteger.TWO` needs API 33, and the app supports 31. */
private val P = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)

/** The curve constant d = -121665/121666 mod p. */
private val D =
    BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")

/** Twice d, which the addition formula uses. */
private val D2 = D.shiftLeft(1).mod(P)

/** The order of the prime-order subgroup, 2^252 + 27742317777372353535851937790883648493. */
private val L =
    BigInteger.valueOf(2).pow(252) + BigInteger("27742317777372353535851937790883648493")

/** A square root of -1 in the field, used to finish a decompression. */
private val SQRT_MINUS_ONE =
    BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

/** The base point B, whose multiples are every signature's left-hand side. */
private val BASE_X =
    BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
private val BASE_Y =
    BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

/** An Ed25519 signature is 64 bytes: the point R, then the scalar S, and a key is 32. */
private const val SIGNATURE_SIZE = 64
private const val KEY_SIZE = 32

/**
 * A point in extended twisted Edwards coordinates: x = X/Z, y = Y/Z, and T = XY/Z. The curve is
 * -x² + y² = 1 + d·x²·y², so the addition and doubling below are the a = -1 formulas.
 */
private data class Point(
    val x: BigInteger,
    val y: BigInteger,
    val z: BigInteger,
    val t: BigInteger,
)

private val ZERO = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

private val BASE = Point(BASE_X, BASE_Y, BigInteger.ONE, BASE_X.multiply(BASE_Y).mod(P))

/**
 * Whether [signature] is [key]'s signature over exactly [message]. It returns false for a key that
 * isn't a point, a signature of the wrong size or shape, and bytes the key doesn't verify; it never
 * throws, so a wallet's answer can always be judged rather than crashed on.
 */
fun verifiesSignature(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    if (key.size != KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
    val a = decompress(key) ?: return false
    val r = decompress(signature.copyOfRange(0, 32)) ?: return false
    val s = littleEndian(signature.copyOfRange(32, SIGNATURE_SIZE))
    // A scalar at or above the group order is a second spelling of a smaller one. OpenSSL, which
    // the sidecar verifies with, refuses it, so this phone must not accept what it would refuse.
    if (s >= L) return false
    val digest = MessageDigest.getInstance("SHA-512")
    digest.update(signature, 0, 32)
    digest.update(key)
    digest.update(message)
    val k = littleEndian(digest.digest()).mod(L)
    // [S]B = R + [k]A, compared without leaving projective coordinates.
    return equal(multiply(BASE, s), add(r, multiply(a, k)))
}

/**
 * The point 32 bytes encode, or null when they encode none: y is the low 255 bits, and the top bit
 * is x's sign. A y at or above the prime, or an x that doesn't exist, is not a point, and neither
 * is the second spelling of x = 0.
 */
private fun decompress(encoded: ByteArray): Point? {
    val little = encoded.copyOf()
    val sign = (little[31].toInt() shr 7) and 1
    little[31] = (little[31].toInt() and 0x7f).toByte()
    val y = BigInteger(1, little.reversedArray())
    if (y >= P) return null
    val ySquared = y.multiply(y).mod(P)
    val u = ySquared.subtract(BigInteger.ONE).mod(P)
    val v = D.multiply(ySquared).add(BigInteger.ONE).mod(P)
    if (v.signum() == 0) return null
    val xSquared = u.multiply(v.modInverse(P)).mod(P)
    if (xSquared.signum() == 0) {
        if (sign == 1) return null
        return Point(BigInteger.ZERO, y, BigInteger.ONE, BigInteger.ZERO)
    }
    // p ≡ 5 (mod 8), so a square root is the (p+3)/8 power, times sqrt(-1) if that one missed.
    var x = xSquared.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
    if (x.multiply(x).mod(P) != xSquared) x = x.multiply(SQRT_MINUS_ONE).mod(P)
    if (x.multiply(x).mod(P) != xSquared) return null
    if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
    return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
}

/** A little-endian unsigned integer, which is how Ed25519 writes every number. */
private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

/** The EFD `add-2008-hwcd-3` formula for a = -1. */
private fun add(one: Point, two: Point): Point {
    val a = one.y.subtract(one.x).multiply(two.y.subtract(two.x)).mod(P)
    val b = one.y.add(one.x).multiply(two.y.add(two.x)).mod(P)
    val c = D2.multiply(one.t).multiply(two.t).mod(P)
    val d = one.z.multiply(two.z).shiftLeft(1).mod(P)
    val e = b.subtract(a).mod(P)
    val f = d.subtract(c).mod(P)
    val g = d.add(c).mod(P)
    val h = b.add(a).mod(P)
    return Point(
        x = e.multiply(f).mod(P),
        y = g.multiply(h).mod(P),
        z = f.multiply(g).mod(P),
        t = e.multiply(h).mod(P),
    )
}

/** The EFD `dbl-2008-hwcd` formula for a = -1. */
private fun double(point: Point): Point {
    val a = point.x.multiply(point.x).mod(P)
    val b = point.y.multiply(point.y).mod(P)
    val c = point.z.multiply(point.z).shiftLeft(1).mod(P)
    val d = a.negate().mod(P)
    val e = point.x.add(point.y).let { it.multiply(it) }.subtract(a).subtract(b).mod(P)
    val g = d.add(b).mod(P)
    val f = g.subtract(c).mod(P)
    val h = d.subtract(b).mod(P)
    return Point(
        x = e.multiply(f).mod(P),
        y = g.multiply(h).mod(P),
        z = f.multiply(g).mod(P),
        t = e.multiply(h).mod(P),
    )
}

/** [scalar] times [point], by doubling and adding from the top bit down. */
private fun multiply(point: Point, scalar: BigInteger): Point {
    var result = ZERO
    for (bit in scalar.bitLength() - 1 downTo 0) {
        result = double(result)
        if (scalar.testBit(bit)) result = add(result, point)
    }
    return result
}

/** Whether two projective points are the same affine point, without dividing. */
private fun equal(one: Point, two: Point): Boolean =
    one.x.multiply(two.z).mod(P) == two.x.multiply(one.z).mod(P) &&
        one.y.multiply(two.z).mod(P) == two.y.multiply(one.z).mod(P)
