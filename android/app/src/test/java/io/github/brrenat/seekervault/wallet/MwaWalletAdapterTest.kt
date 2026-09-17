package io.github.brrenat.seekervault.wallet

import com.google.protobuf.ByteString
import com.solana.mobilewalletadapter.common.ProtocolContract
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.time.Instant
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
        // A wallet that signed nothing has nothing to report, and one that reported a message
        // without a signature has not signed it either.
        assertEquals(
            SignResult.Failed("the wallet returned no signed message"),
            MwaWalletAdapter.signed(null, address, key, message),
        )
        assertEquals(
            SignResult.Failed("the wallet returned no signature"),
            MwaWalletAdapter.signed(
                SignedMessage(message, emptyList(), listOf(key)),
                address,
                key,
                message,
            ),
        )
        assertEquals(
            SignResult.Failed("the wallet returned a signature of the wrong size"),
            MwaWalletAdapter.signed(answer(message, ByteArray(63), key), address, key, message),
        )
    }

    @Test
    fun readsTheAccountsAWalletAuthorizedAgainstTheOneTheOwnerReviewed() {
        val reviewed =
            SelectedWallet(
                address = address,
                network = WalletNetwork.Devnet,
                selectedAt = Instant.parse("2026-09-16T10:00:00Z"),
            )
        fun authorization(vararg accounts: WalletAccount) =
            WalletAuthorization("token", accounts.toList())

        // The reviewed account is there, among others, and nothing contradicts its network.
        assertEquals(
            AccountCheck.Matches,
            MwaWalletAdapter.authorizes(
                authorization(
                    WalletAccount(OTHER_WALLET, null, listOf("solana:devnet")),
                    WalletAccount(address, null, listOf("solana:mainnet", "solana:devnet")),
                ),
                reviewed,
            ),
        )
        // A wallet that lists no chains for the account has said nothing about any network.
        assertEquals(
            AccountCheck.Matches,
            MwaWalletAdapter.authorizes(
                authorization(WalletAccount(address, null, emptyList())),
                reviewed,
            ),
        )
        // An account that isn't there, and an authorization that names none at all.
        assertEquals(
            AccountCheck.NotAuthorized,
            MwaWalletAdapter.authorizes(
                authorization(WalletAccount(OTHER_WALLET, null, listOf("solana:devnet"))),
                reviewed,
            ),
        )
        assertEquals(
            AccountCheck.NotAuthorized,
            MwaWalletAdapter.authorizes(authorization(), reviewed),
        )
        // The account is there, and the wallet says it isn't on the network that was reviewed.
        assertEquals(
            AccountCheck.ChainMismatch,
            MwaWalletAdapter.authorizes(
                authorization(WalletAccount(address, null, listOf("solana:mainnet"))),
                reviewed,
            ),
        )
    }

    @Test
    fun tellsAFailureBeforeTheWalletApartFromOneNobodyCanResolve() {
        val broke = WalletError(message = "the session ended")
        // The same error, on either side of the transaction reaching the wallet.
        assertEquals(
            SendResult.Failed("the session ended"),
            MwaWalletAdapter.classifySending(broke, dispatched = false),
        )
        assertEquals(
            SendResult.Unknown("the session ended"),
            MwaWalletAdapter.classifySending(broke, dispatched = true),
        )
        // With nothing said about what went wrong, a failure before the wallet still says which
        // side of the sending it was on.
        assertEquals(
            SendResult.Failed(MwaWalletAdapter.BEFORE_THE_WALLET),
            MwaWalletAdapter.classifySending(WalletError(), dispatched = false),
        )
        // A wallet that answered for itself is believed whichever side it was on: declining is
        // declining, and a signature it says it didn't submit is never a refusal.
        for (dispatched in listOf(true, false)) {
            assertEquals(
                SendResult.Declined,
                MwaWalletAdapter.classifySending(
                    WalletError(code = ProtocolContract.ERROR_NOT_SIGNED),
                    dispatched,
                ),
            )
            assertEquals(
                SendResult.AuthorizationExpired,
                MwaWalletAdapter.classifySending(
                    WalletError(code = ProtocolContract.ERROR_AUTHORIZATION_FAILED),
                    dispatched,
                ),
            )
            assertEquals(
                SendResult.Unknown(
                    "the wallet signed the transaction but reported that it didn't send it"
                ),
                MwaWalletAdapter.classifySending(
                    WalletError(
                        code = ProtocolContract.ERROR_NOT_SUBMITTED,
                        notSubmitted = true,
                        message = "not submitted",
                    ),
                    dispatched,
                ),
            )
        }
    }

    @Test
    fun readsTheWalletsAnswerAboutATransactionItSent() {
        val signature = ByteArray(64) { 5 }
        assertEquals(
            SendResult.Sent(ByteString.copyFrom(signature)),
            MwaWalletAdapter.sent(listOf(signature)),
        )
        // Anything this phone can't match to the one transaction it asked about may still have
        // been sent, so it is unknown rather than failed.
        assertEquals(
            SendResult.Unknown("the wallet returned no signature for this transaction"),
            MwaWalletAdapter.sent(null),
        )
        assertEquals(
            SendResult.Unknown("the wallet returned no signature for this transaction"),
            MwaWalletAdapter.sent(listOf(signature, signature)),
        )
        assertEquals(
            SendResult.Unknown("the wallet returned a signature of the wrong size"),
            MwaWalletAdapter.sent(listOf(ByteArray(63))),
        )
        assertEquals(
            SendResult.Unknown("the wallet returned a signature of the wrong size"),
            MwaWalletAdapter.sent(listOf(null)),
        )
    }

    private fun answer(message: ByteArray, signature: ByteArray, signer: ByteArray) =
        SignedMessage(message, listOf(signature), listOf(signer))

    private fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519")
            .apply {
                initSign(pair.private)
                update(message)
            }
            .sign()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private companion object {
        const val OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
    }

    /** The 32 bytes a Solana address is: y little-endian, with x's sign in the top bit. */
    private fun publicKeyBytes(pair: KeyPair): ByteArray {
        val point = (pair.public as EdECPublicKey).point
        val bytes = point.y.toByteArray().reversedArray()
        val encoded = ByteArray(32) { if (it < bytes.size) bytes[it] else 0 }
        if (point.isXOdd) encoded[31] = (encoded[31].toInt() or 0x80).toByte()
        return encoded
    }
}
