package io.github.brrenat.seekervault.wallet

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.common.ProtocolContract
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the adapter does with one wallet session (SEE-84): which authorization it keeps, what it
 * refuses to put in front of the wallet, when a session is reused and when it is dropped, and what
 * it makes of a session that ends badly.
 *
 * The wallet is a [FakeWalletClient]: no wallet app, no activity, and no Mobile Wallet Adapter. The
 * sender is never asked for one, because a session is what reaches the wallet.
 */
@RunWith(AndroidJUnit4::class)
class MwaWalletAdapterSessionTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val key = publicKeyBytes(pair)
    private val address = encodeBase58(key)
    private val selected =
        SelectedWallet(
            address = address,
            network = WalletNetwork.Devnet,
            selectedAt = Instant.parse("2026-09-16T10:00:00Z"),
        )

    private val opened = mutableListOf<FakeWalletClient>()
    private var wallet: FakeWalletClient.() -> Unit = { authorizes(address, REFRESHED) }
    private val adapter =
        MwaWalletAdapter(
            identity =
                ConnectionIdentity(
                    identityUri = Uri.parse("https://seekervault.example"),
                    iconUri = Uri.parse("icon.png"),
                    identityName = "Seeker Vault",
                ),
            // A session reaches the wallet, so the screen is never asked for here.
            sender = { null },
            targets = FakeWalletTargets(),
            io = Dispatchers.Unconfined,
            clients = { network, target ->
                FakeWalletClient(network, target).apply(wallet).also { opened += it }
            },
        )

    private val transaction = ByteString.copyFrom(ByteArray(215) { (it * 5).toByte() })
    private val signature = ByteArray(64) { 7 }

    @Test
    fun keepsTheAuthorizationTheWalletHandsBackWhileItSends() = runBlocking {
        wallet = {
            authorizes(address, REFRESHED)
            sends = listOf(signature)
        }

        val answer = adapter.signAndSendTransaction(transaction, selected, SECRET)

        assertEquals(SendResult.Sent(ByteString.copyFrom(signature)), answer.result)
        // The token the wallet replaced went to it; the replacement is what the phone now keeps.
        assertEquals(listOf<String?>(SECRET), opened.single().offered)
        assertEquals(REFRESHED, answer.authToken)
        // The bytes the wallet was handed are the ones it was given, unaltered.
        assertEquals(transaction.toByteArray().toList(), opened.single().sendings.single().toList())
    }

    @Test
    fun keepsItWhenTheOwnerDeclinesTheTransferInTheWallet() = runBlocking {
        wallet = {
            authorizes(address, REFRESHED)
            failsWhileAsking = WalletError(code = ProtocolContract.ERROR_NOT_SIGNED)
        }

        val answer = adapter.signAndSendTransaction(transaction, selected, SECRET)

        // Declining says something about the transaction, not about this phone's authorization.
        assertEquals(SendResult.Declined, answer.result)
        assertEquals(REFRESHED, answer.authToken)
    }

    @Test
    fun keepsItWhenNobodyKnowsWhetherTheTransactionWasSent() = runBlocking {
        wallet = {
            authorizes(address, REFRESHED)
            failsWhileAsking = WalletError(message = "the session ended")
        }

        val answer = adapter.signAndSendTransaction(transaction, selected, SECRET)

        assertEquals(SendResult.Unknown("the session ended"), answer.result)
        assertEquals(REFRESHED, answer.authToken)
    }

    @Test
    fun aFailureBeforeTheTransactionReachedTheWalletIsAFailureAndNotAnUnknown() = runBlocking {
        wallet = {
            authorizes(address, REFRESHED)
            failsBeforeAsking = WalletError(message = "the wallet never opened")
        }

        val answer = adapter.signAndSendTransaction(transaction, selected, SECRET)

        // Nothing was ever put to the wallet, so nothing can have been sent: this phone can say so
        // rather than leaving an outcome open that nobody will ever settle.
        assertEquals(SendResult.Failed("the wallet never opened"), answer.result)
        assertEquals(emptyList<ByteArray>(), opened.single().sendings)
    }

    @Test
    fun aScreenThatClosedBeforeTheWalletOpenedSentNothing() = runBlocking {
        wallet = { opens = WalletOutcome.NoActivity }
        assertEquals(
            SendResult.Failed(MwaWalletAdapter.NO_ACTIVITY),
            adapter.signAndSendTransaction(transaction, selected, SECRET).result,
        )
        assertEquals(
            SignResult.Failed(MwaWalletAdapter.NO_ACTIVITY),
            adapter.signMessage(ByteString.copyFromUtf8("m"), selected, SECRET).result,
        )
    }

    @Test
    fun reportsAPhoneWithNoWalletApp() = runBlocking {
        wallet = { opens = WalletOutcome.NoWallet }
        assertEquals(
            SendResult.NoWallet,
            adapter.signAndSendTransaction(transaction, selected, SECRET).result,
        )
        assertEquals(WalletResult.NoWallet, adapter.connect(WalletNetwork.Devnet, SECRET))
    }

    @Test
    fun asksTheWalletNothingWhenItNoLongerAuthorizesTheReviewedAccount() = runBlocking {
        wallet = {
            // The wallet reauthorized this app, for another account than the one reviewed.
            authorizes(OTHER_WALLET, REFRESHED)
            sends = listOf(signature)
            signs = listOf(SignedMessage(ByteArray(1), listOf(signature), listOf(key)))
        }

        val sending = adapter.signAndSendTransaction(transaction, selected, SECRET)
        val signing = adapter.signMessage(ByteString.copyFromUtf8("m"), selected, SECRET)

        // Nothing was signed and nothing was sent: the owner reviewed an account this wallet no
        // longer offers, so the request needs another look rather than another account's signature.
        assertEquals(SendResult.Changed, sending.result)
        assertEquals(SignResult.Changed, signing.result)
        val session = opened.single()
        assertEquals(emptyList<ByteArray>(), session.sendings)
        assertEquals(emptyList<Any>(), session.signings)
    }

    @Test
    fun refusesAnAccountTheWalletSaysIsNotOnTheReviewedNetwork() = runBlocking {
        wallet = {
            // The account is there, and the wallet says it doesn't serve devnet for it.
            authorizes(address, REFRESHED, chains = listOf("solana:mainnet"))
            sends = listOf(signature)
        }

        assertEquals(
            SendResult.Changed,
            adapter.signAndSendTransaction(transaction, selected, SECRET).result,
        )
        assertEquals(emptyList<ByteArray>(), opened.single().sendings)
    }

    @Test
    fun takesAWalletThatListsNoChainsAsSayingNothing() = runBlocking {
        wallet = {
            // No chains listed is not a contradiction, and not a confirmation either.
            authorizes(address, REFRESHED, chains = emptyList())
            sends = listOf(signature)
        }

        assertEquals(
            SendResult.Sent(ByteString.copyFrom(signature)),
            adapter.signAndSendTransaction(transaction, selected, SECRET).result,
        )
    }

    @Test
    fun signsWithTheAccountTheOwnerReviewedAndKeepsTheReplacedAuthorization() = runBlocking {
        val message = "Sign in to example.com".toByteArray()
        val made = sign(message)
        wallet = {
            authorizes(address, REFRESHED)
            signs = listOf(SignedMessage(message, listOf(made), listOf(key)))
        }

        val answer = adapter.signMessage(ByteString.copyFrom(message), selected, SECRET)

        assertEquals(
            SignResult.Signed(
                ByteString.copyFrom(message),
                address,
                ByteString.copyFrom(made),
            ),
            answer.result,
        )
        assertEquals(REFRESHED, answer.authToken)
        val asked = opened.single().signings.single()
        assertEquals(message.toList(), asked.first.toList())
        assertEquals(key.toList(), asked.second.toList())
    }

    @Test
    fun keepsOneSessionAcrossConnectingSigningAndSending() = runBlocking {
        wallet = {
            authorizes(address, REFRESHED)
            sends = listOf(signature)
        }

        adapter.connect(WalletNetwork.Devnet, null)
        adapter.signAndSendTransaction(transaction, selected, SECRET)
        adapter.signAndSendTransaction(transaction, selected, REFRESHED)

        // One session for the wallet, so what it learned about where that wallet answers from is
        // still there for the next operation rather than thrown away with the client.
        val session = opened.single()
        assertEquals(WalletNetwork.Devnet, session.network)
        assertEquals(listOf(null, SECRET, REFRESHED), session.offered)
    }

    @Test
    fun startsAnotherSessionForAnotherNetwork() = runBlocking {
        adapter.connect(WalletNetwork.Devnet, null)
        adapter.connect(WalletNetwork.Mainnet, null)

        assertEquals(
            listOf(WalletNetwork.Devnet, WalletNetwork.Mainnet),
            opened.map { it.network },
        )
    }

    @Test
    fun forgetsTheSessionWhenTheWalletRefusesThisPhonesAuthorization() = runBlocking {
        wallet = {
            authorizes(address, REFRESHED)
            failsWhileAsking = WalletError(code = ProtocolContract.ERROR_AUTHORIZATION_FAILED)
        }

        assertEquals(
            SendResult.AuthorizationExpired,
            adapter.signAndSendTransaction(transaction, selected, SECRET).result,
        )

        // The session that authorization belonged to is over: the next attempt opens another.
        wallet = { authorizes(address, REFRESHED) }
        adapter.connect(WalletNetwork.Devnet, null)
        assertEquals(2, opened.size)
    }

    @Test
    fun forgetsTheSessionWhenTheOwnerDisconnects() = runBlocking {
        adapter.connect(WalletNetwork.Devnet, null)
        adapter.disconnect(selected, SECRET)

        // The wallet is told, and nothing on this phone is left pointing at that wallet.
        assertEquals(listOf<String?>(SECRET), opened.single().closes)
        adapter.connect(WalletNetwork.Devnet, null)
        assertEquals(2, opened.size)
        assertTrue(opened[0] !== opened[1])
    }

    @Test
    fun readsTheAccountAndTheAuthorizationTheWalletReturnedWhenItConnects() = runBlocking {
        wallet = {
            authorizes =
                WalletAuthorization(
                    REFRESHED,
                    listOf(WalletAccount(address, "Account 1", listOf("solana:devnet"))),
                )
        }

        val result = adapter.connect(WalletNetwork.Devnet, null)

        val connected = result as WalletResult.Connected
        assertEquals(address, connected.account.address)
        assertEquals(listOf("solana:devnet"), connected.account.chains)
        assertEquals(REFRESHED, connected.authToken)
        // A wallet that returned no authorization has not connected anything.
        wallet = { authorizes(address, null) }
        adapter.disconnect(selected, REFRESHED)
        assertTrue(adapter.connect(WalletNetwork.Devnet, null) is WalletResult.Failed)
    }

    @Test
    // The factory that takes a list of accounts needs one this app can't build, so the single-key
    // one — deprecated, and what a wallet reporting a legacy authorization comes back as — stands
    // in for it here.
    @Suppress("DEPRECATION")
    fun readsWhatMobileWalletAdapterReportedIntoThisAppsOwnTerms() {
        val reported =
            authorizationOf(
                AuthorizationResult.create(
                    REFRESHED,
                    key,
                    "  ",
                    Uri.parse("https://wallet.example"),
                )
            )

        assertEquals(REFRESHED, reported.token)
        assertEquals(address, reported.accounts.single().address)
        // A label the wallet left blank is no label, and chains it didn't list are no chains.
        assertNull(reported.accounts.single().label)
        assertEquals(emptyList<String>(), reported.accounts.single().chains)
        // A wallet that reported nothing at all is read as an authorization that says nothing.
        assertEquals(WalletAuthorization(null, emptyList()), authorizationOf(null))
    }

    private fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519")
            .apply {
                initSign(pair.private)
                update(message)
            }
            .sign()

    /** The 32 bytes a Solana address is: y little-endian, with x's sign in the top bit. */
    private fun publicKeyBytes(pair: KeyPair): ByteArray {
        val point = (pair.public as EdECPublicKey).point
        val bytes = point.y.toByteArray().reversedArray()
        val encoded = ByteArray(32) { if (it < bytes.size) bytes[it] else 0 }
        if (point.isXOdd) encoded[31] = (encoded[31].toInt() or 0x80).toByte()
        return encoded
    }

    private companion object {
        const val SECRET = "authorization-the-wallet-issued-0123456789"
        const val REFRESHED = "authorization-the-wallet-issued-later-9876543210"
        const val OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
    }
}
