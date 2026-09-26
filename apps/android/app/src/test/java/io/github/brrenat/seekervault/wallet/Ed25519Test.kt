package io.github.brrenat.seekervault.wallet

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's own Ed25519 verification, against the JDK's (SAW-024). The app can't use the JDK's:
 * Android gets `Signature.getInstance("Ed25519")` in API 33 and this app supports 31. So the check
 * here is that the two agree — on real signatures, and on every way an answer can be wrong.
 *
 * The keys are generated in the test and never leave it. Nothing in `main/` holds or makes a key.
 */
class Ed25519Test {
    private val random = Random(20260912)

    @Test
    fun agreesWithTheJdkOnSignaturesTheJdkMade() {
        repeat(25) { round ->
            val message = ByteArray(round * 7).also(random::nextBytes)
            val signer = keyPair()
            val signature = sign(signer, message)
            assertTrue(
                "round $round verifies",
                verifiesSignature(publicKeyBytes(signer), message, signature),
            )
            assertTrue("the JDK agrees", jdkVerifies(signer, message, signature))
        }
    }

    @Test
    fun refusesASignatureForAnotherMessage() {
        val signer = keyPair()
        val signature = sign(signer, "Sign in to example.com\nNonce: 4711".toByteArray())
        val other = "Sign in to example.com\nNonce: 4712".toByteArray()
        assertFalse(verifiesSignature(publicKeyBytes(signer), other, signature))
        assertFalse(jdkVerifies(signer, other, signature))
    }

    @Test
    fun refusesASignatureFromAnotherKey() {
        val message = "the owner's message".toByteArray()
        val signer = keyPair()
        val other = keyPair()
        val signature = sign(signer, message)
        assertFalse(verifiesSignature(publicKeyBytes(other), message, signature))
        assertFalse(jdkVerifies(other, message, signature))
    }

    @Test
    fun refusesEveryOneBitChangeOfASignature() {
        val message = "one bit is one bit".toByteArray()
        val signer = keyPair()
        val key = publicKeyBytes(signer)
        val signature = sign(signer, message)
        for (bit in listOf(0, 7, 63, 128, 255, 300, 400, 511)) {
            val tampered = signature.copyOf()
            tampered[bit / 8] = (tampered[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            assertFalse("bit $bit", verifiesSignature(key, message, tampered))
        }
    }

    @Test
    fun refusesWhatIsNotASignatureAtAll() {
        val message = "nothing was signed".toByteArray()
        val signer = keyPair()
        val key = publicKeyBytes(signer)
        // The wallet answering with 64 bytes of its own choosing, which is the case that used to be
        // believed here and refused by the sidecar for ever.
        assertFalse(verifiesSignature(key, message, ByteArray(64)))
        assertFalse(verifiesSignature(key, message, ByteArray(64) { 0xff.toByte() }))
        assertFalse(verifiesSignature(key, message, ByteArray(64).also(random::nextBytes)))
        // And an answer of the wrong size is no signature either.
        assertFalse(verifiesSignature(key, message, ByteArray(63)))
        assertFalse(verifiesSignature(key, message, ByteArray(65)))
        assertFalse(verifiesSignature(ByteArray(31), message, sign(signer, message)))
    }

    @Test
    fun refusesAScalarThatIsNotCanonical() {
        val message = "S must be below the group order".toByteArray()
        val signer = keyPair()
        val key = publicKeyBytes(signer)
        val signature = sign(signer, message)
        // S + L is another spelling of the same scalar. OpenSSL, which the sidecar verifies with,
        // refuses it, so this phone must not accept it either.
        val s = BigInteger(1, signature.copyOfRange(32, 64).reversedArray())
        val order =
            BigInteger.valueOf(2).pow(252) + BigInteger("27742317777372353535851937790883648493")
        val malleable = signature.copyOf()
        littleEndian32(s + order).copyInto(malleable, 32)
        assertTrue(verifiesSignature(key, message, signature))
        assertFalse(verifiesSignature(key, message, malleable))
        assertFalse(jdkVerifies(signer, message, malleable))
    }

    @Test
    fun refusesAPointThatIsNotCanonicallyEncoded() {
        val message = "a y above the prime is no point".toByteArray()
        val signer = keyPair()
        val signature = sign(signer, message)
        // p, p+1, … are y values no encoder writes: they are above the field prime.
        val prime = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)
        val nonCanonical = signature.copyOf()
        littleEndian32(prime).copyInto(nonCanonical, 0)
        assertFalse(verifiesSignature(publicKeyBytes(signer), message, nonCanonical))
        assertFalse(verifiesSignature(littleEndian32(prime), message, signature))
        // And 32 bytes whose y is on no curve point at all.
        val offCurve = ByteArray(32).also { it[0] = 2 }
        assertFalse(verifiesSignature(offCurve, message, signature))
    }

    @Test
    fun verifiesAnEmptyMessage() {
        val signer = keyPair()
        val signature = sign(signer, ByteArray(0))
        assertTrue(verifiesSignature(publicKeyBytes(signer), ByteArray(0), signature))
    }

    @Test
    fun readsTheAddressTheWalletIsKnownBy() {
        // The wallet is named by its base58 address, which is what the check is given.
        val signer = keyPair()
        val key = publicKeyBytes(signer)
        assertEquals(key.size, 32)
        assertEquals(decodeBase58(encodeBase58(key))?.toList(), key.toList())
    }

    private fun keyPair() =
        KeyPairGenerator.getInstance("Ed25519")
            .apply { initialize(255, SecureRandom(ByteArray(8).also(random::nextBytes))) }
            .generateKeyPair()

    private fun sign(pair: java.security.KeyPair, message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519")
            .apply {
                initSign(pair.private)
                update(message)
            }
            .sign()

    private fun jdkVerifies(
        pair: java.security.KeyPair,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            Signature.getInstance("Ed25519")
                .apply {
                    initVerify(pair.public)
                    update(message)
                }
                .verify(signature)
        } catch (_: java.security.SignatureException) {
            false
        }

    /**
     * The 32 bytes a Solana address is: the point's y, little-endian, with x's sign in the top bit.
     * The JDK hands back the coordinates rather than the encoding.
     */
    private fun publicKeyBytes(pair: java.security.KeyPair): ByteArray {
        val point = (pair.public as EdECPublicKey).point
        val encoded = littleEndian32(point.y)
        if (point.isXOdd) encoded[31] = (encoded[31].toInt() or 0x80).toByte()
        return encoded
    }

    private fun littleEndian32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().reversedArray()
        return ByteArray(32) { if (it < bytes.size) bytes[it] else 0 }
    }
}
