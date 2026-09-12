package io.github.brrenat.seekervault.wallet

import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.SignMessagesResult
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the adapter makes of the wallet's own answer (SAW-016, SAW-024). The wallet is another app:
 * its answer is checked here before anything is believed, and a wallet that answers with something
 * that isn't a signature has failed to sign rather than signed.
 *
 * The keys are the test's own, made and used only here.
 */
class MwaWalletAdapterTest {
    private val pair = keyPair()
    private val key = publicKeyBytes(pair)
    private val address = encodeBase58(key)
    private val message = "Sign in to example.com\nNonce: 4711".toByteArray()

    @Test
    fun takesASignatureTheSelectedWalletReallyMade() {
        val signature = sign(message)
        val result = MwaWalletAdapter.signed(answer(message, signature, key), address, key, message)
        assertTrue(result is SignResult.Signed)
        val signed = result as SignResult.Signed
        assertEquals(address, signed.address)
        assertEquals(message.toList(), signed.message.toByteArray().toList())
        assertEquals(signature.toList(), signed.signature.toByteArray().toList())
    }

    @Test
    fun refusesSixtyFourBytesThatAreNotASignature() {
        // The case that used to be believed: the sidecar verifies every signature it is sent and
        // refuses anything else with INVALID_PARAMETERS, and the first outcome stored for an
        // approval stands — so this answer would have been resent for ever, with the request left
        // PROCESSING and nothing able to settle it.
        val result =
            MwaWalletAdapter.signed(
                answer(message, ByteArray(64) { 3 }, key),
                address,
                key,
                message,
            )
        assertEquals(
            SignResult.Failed(
                "the wallet's answer is not this wallet's signature over this message"
            ),
            result,
        )
    }

    @Test
    fun refusesASignatureOverOtherBytes() {
        val signature = sign("Sign in to example.com\nNonce: 4712".toByteArray())
        val result = MwaWalletAdapter.signed(answer(message, signature, key), address, key, message)
        assertTrue(result is SignResult.Failed)
    }

    @Test
    fun refusesASignatureFromAnotherKey() {
        val other = keyPair()
        val signature =
            Signature.getInstance("Ed25519")
                .apply {
                    initSign(other.private)
                    update(message)
                }
                .sign()
        val result = MwaWalletAdapter.signed(answer(message, signature, key), address, key, message)
        assertTrue(result is SignResult.Failed)
    }

    @Test
    fun refusesAnAnswerFromAnotherWalletBeforeItLooksAtTheSignature() {
        val other = keyPair()
        val result =
            MwaWalletAdapter.signed(
                answer(message, sign(message), publicKeyBytes(other)),
                address,
                key,
                message,
            )
        val failure = result as SignResult.Failed
        assertTrue(
            failure.message
                .orEmpty()
                .startsWith("the wallet signed with ${encodeBase58(publicKeyBytes(other))}")
        )
    }

    @Test
    fun refusesAnAnswerWithNothingUsableInIt() {
        assertEquals(
            SignResult.Failed("the wallet returned no signed message"),
            MwaWalletAdapter.signed(SignMessagesResult(emptyArray()), address, key, message),
        )
        assertEquals(
            SignResult.Failed("the wallet returned no signed message"),
            MwaWalletAdapter.signed(null, address, key, message),
        )
        assertEquals(
            SignResult.Failed("the wallet returned a signature of the wrong size"),
            MwaWalletAdapter.signed(answer(message, ByteArray(63), key), address, key, message),
        )
    }

    private fun answer(message: ByteArray, signature: ByteArray, signer: ByteArray) =
        SignMessagesResult(
            arrayOf(
                SignMessagesResult.SignedMessage(
                    message,
                    arrayOf(signature),
                    arrayOf(signer),
                )
            )
        )

    private fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519")
            .apply {
                initSign(pair.private)
                update(message)
            }
            .sign()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** The 32 bytes a Solana address is: y little-endian, with x's sign in the top bit. */
    private fun publicKeyBytes(pair: KeyPair): ByteArray {
        val point = (pair.public as EdECPublicKey).point
        val bytes = point.y.toByteArray().reversedArray()
        val encoded = ByteArray(32) { if (it < bytes.size) bytes[it] else 0 }
        if (point.isXOdd) encoded[31] = (encoded[31].toInt() or 0x80).toByte()
        return encoded
    }
}
