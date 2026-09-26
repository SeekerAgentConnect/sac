package io.github.brrenat.seekervault.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import java.security.GeneralSecurityException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Connecting, keeping, and disconnecting the owner's wallet, and what every paired sidecar is told
 * about it. The wallet is a [FakeWalletAdapter]: no wallet app and no activity are involved.
 */
@RunWith(AndroidJUnit4::class)
class WalletRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val key = softwareKey()
    private val adapter = FakeWalletAdapter()

    private val connections by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "connections")),
            vault = CredentialVault(File(folder.root, "credentials")) { key },
            results = ResultStore(File(folder.root, "results")),
            gateway = gateway,
            deviceName = "Seeker",
            io = Dispatchers.Unconfined,
        )
    }

    /** Set while a test needs this phone's storage to be unavailable, as a locked Keystore is. */
    private var storageFails = false

    private val store by lazy {
        WalletStore(File(folder.root, "wallet"), File(folder.root, "no_backup/wallet")) {
            if (storageFails) throw GeneralSecurityException("the keystore went away") else key
        }
    }

    private val repository by lazy {
        WalletRepository(store, adapter, connections, io = Dispatchers.Unconfined)
    }

    private fun pair(): Connection = runBlocking {
        connections.load()
        connections.pair(server.issue(URL))
    }

    @Test
    fun storesTheSelectedWalletAndTellsEveryConnection() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, label = "Account 1", chains = listOf("solana:devnet"))
        val result = repository.connect(WalletNetwork.Devnet)

        assertTrue(result.toString(), result is WalletResult.Connected)
        val selected = repository.wallet.value
        assertEquals(WALLET, selected?.address)
        assertEquals(WalletNetwork.Devnet, selected?.network)
        assertEquals("Account 1", selected?.label)
        assertTrue(selected?.networkConfirmed == true)
        assertEquals(WALLET, server.wallet?.wallet)
        assertEquals(Network.NETWORK_DEVNET, server.wallet?.network)
        // The phone never sets bound_at: the sidecar stamps it.
        assertFalse(server.wallet!!.hasBoundAt())
    }

    @Test
    fun keepsTheWalletsAuthorizationOffTheWire() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Mainnet)
        assertEquals(SECRET, store.authorization())
        assertTrue(gateway.sent.none { (_, secret) -> secret == SECRET })
        assertTrue(gateway.published.none { (_, binding) -> binding.toString().contains(SECRET) })
    }

    @Test
    fun reusesTheStoredAuthorizationOnTheNextConnect() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Mainnet)
        repository.connect(WalletNetwork.Mainnet)
        assertEquals(listOf(null, SECRET), adapter.connects.map { it.second })
    }

    @Test
    fun changesNothingWhenTheOwnerDeclines() = runBlocking {
        pair()
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Mainnet)
        adapter.answer(WalletResult.Declined)

        assertEquals(WalletResult.Declined, repository.connect(WalletNetwork.Devnet))
        assertEquals(WALLET, repository.wallet.value?.address)
        assertEquals(WalletNetwork.Mainnet, repository.wallet.value?.network)
        assertEquals(WALLET, server.wallet?.wallet)
    }

    @Test
    fun reportsNoWalletAndStoresNothing() = runBlocking {
        pair()
        adapter.answer(WalletResult.NoWallet)
        assertEquals(WalletResult.NoWallet, repository.connect(WalletNetwork.Mainnet))
        assertNull(repository.wallet.value)
        assertNull(store.selected())
        assertNull(server.wallet)
    }

    @Test
    fun forgetsAnAuthorizationTheWalletNoLongerAccepts() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Mainnet)
        adapter.answer(WalletResult.AuthorizationExpired)

        assertEquals(WalletResult.AuthorizationExpired, repository.connect(WalletNetwork.Mainnet))
        assertNull(repository.wallet.value)
        assertNull(store.authorization())
        // Every sidecar learns there's no wallet, so an agent gets WALLET_NOT_CONNECTED.
        assertNull(server.wallet)
        // The next attempt starts afresh, with no authorization to offer.
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Mainnet)
        assertNull(adapter.connects.last().second)
    }

    @Test
    fun reportsANetworkTheWalletDoesNotServe() = runBlocking {
        pair()
        adapter.answer(WalletResult.NetworkUnsupported)
        assertEquals(WalletResult.NetworkUnsupported, repository.connect(WalletNetwork.Devnet))
        assertNull(repository.wallet.value)
    }

    @Test
    fun saysSoWhenTheWalletDidNotConfirmTheNetwork() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, chains = listOf("solana:mainnet"))
        repository.connect(WalletNetwork.Devnet)
        assertFalse(repository.wallet.value!!.networkConfirmed)
        // A wallet that lists no chains hasn't contradicted anything.
        adapter.answerConnected(WALLET, chains = emptyList())
        repository.connect(WalletNetwork.Devnet)
        assertTrue(repository.wallet.value!!.networkConfirmed)
    }

    @Test
    fun tellsTheWalletAndEverySidecarWhenItIsDisconnected() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Mainnet)

        repository.disconnect()
        assertEquals(listOf(SECRET), adapter.disconnects)
        assertNull(repository.wallet.value)
        assertNull(store.selected())
        assertNull(store.authorization())
        assertNull(server.wallet)
    }

    @Test
    fun readsTheStoredWalletBack() = runBlocking {
        pair()
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Devnet)

        val next = WalletRepository(store, adapter, connections, io = Dispatchers.Unconfined)
        next.load()
        assertEquals(WALLET, next.wallet.value?.address)
        assertEquals(WalletNetwork.Devnet, next.wallet.value?.network)
    }

    @Test
    fun dropsAStoredWalletWhoseAuthorizationIsGone() = runBlocking {
        pair()
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Devnet)
        File(folder.root, "no_backup/wallet/wallet-session").delete()

        val next = WalletRepository(store, adapter, connections, io = Dispatchers.Unconfined)
        next.load()
        assertNull(next.wallet.value)
        assertNull(store.selected())
    }

    @Test
    fun reportsTheConnectionsItCouldNotTell() = runBlocking {
        pair()
        adapter.answerConnected(WALLET)
        server.failure = GatewayException.Kind.Unreachable
        repository.connect(WalletNetwork.Mainnet)
        val failed = repository.publish()
        assertEquals(1, failed.size)
        assertNull(server.wallet)

        server.failure = null
        assertEquals(emptyList<String>(), repository.publish())
        assertEquals(WALLET, server.wallet?.wallet)
    }

    @Test
    fun tellsOnlyTheConnectionsThatHaveNotHeardItYet() = runBlocking {
        val first = pair()
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Mainnet)
        val publications = server.publications

        // Nothing changed, so nothing is sent again.
        assertEquals(emptyList<String>(), repository.publish())
        assertEquals(publications, server.publications)

        // A newly paired connection hears it on the next publication.
        val second = gateway.serve(OTHER_URL)
        connections.pair(second.issue(OTHER_URL))
        assertEquals(emptyList<String>(), repository.publish())
        assertEquals(WALLET, second.wallet?.wallet)
        assertNotNull(first.id)
    }

    @Test
    fun tellsEveryConnectionAgainWhenTheOwnerAsks() = runBlocking {
        pair()
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Mainnet)
        val publications = server.publications
        assertEquals(emptyList<String>(), repository.publishAgain())
        assertEquals(publications + 1, server.publications)
    }

    @Test
    fun signsWithTheStoredAuthorizationAndTheSelectedWallet() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        val signature = ByteString.copyFrom(ByteArray(64) { 9 })
        adapter.signWith(signature)

        val message = ByteString.copyFromUtf8("Sign in to Example")
        val result = repository.sign(message, selected)

        assertEquals(SignResult.Signed(message, WALLET, signature), result)
        val asked = adapter.signings.single()
        assertEquals(message, asked.first)
        assertEquals(selected, asked.second)
        // The wallet's authorization is what reaches the wallet, and it never goes anywhere else.
        assertEquals(SECRET, asked.third)
        assertTrue(gateway.sent.none { (_, secret) -> secret == SECRET })
    }

    @Test
    fun asksTheWalletNothingWithoutAConnectedWallet() = runBlocking {
        pair()
        assertEquals(
            SignResult.NotConnected,
            repository.sign(ByteString.copyFromUtf8("x"), REVIEWED),
        )
        assertEquals(emptyList<Any>(), adapter.signings)
    }

    @Test
    fun asksTheWalletNothingWhenTheSelectionIsNotTheOneReviewed() = runBlocking {
        pair()
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        // The same wallet on another network is another selection: the owner reviewed one of them.
        for (reviewed in listOf(REVIEWED, selected.copy(network = WalletNetwork.Mainnet))) {
            assertEquals(
                SignResult.Changed,
                repository.sign(ByteString.copyFromUtf8("x"), reviewed),
            )
        }
        assertEquals(emptyList<Any>(), adapter.signings)
    }

    @Test
    fun keepsAnAuthorizationTheWalletReplacesWhileSigning() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        val publications = server.publications
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        // The wallet reauthorizes this app as it signs, and hands back another authorization.
        adapter.refreshedAuthorization = REFRESHED

        val message = ByteString.copyFromUtf8("Sign in to Example")
        assertTrue(repository.sign(message, selected) is SignResult.Signed)

        // The one it replaced went to the wallet; the replacement is what this phone now keeps.
        assertEquals(SECRET, adapter.signings.single().third)
        assertEquals(REFRESHED, store.authorization())
        // The owner's selection is untouched, and no sidecar was told anything new about it.
        assertEquals(selected, repository.wallet.value)
        assertEquals(selected, store.selected())
        assertEquals(WALLET, server.wallet?.wallet)
        assertEquals(Network.NETWORK_DEVNET, server.wallet?.network)
        assertEquals(publications, server.publications)

        // The next signing offers the replacement, and so does a repository that starts from the
        // files this phone stored, the way the app does when it is opened again.
        repository.sign(message, selected)
        assertEquals(REFRESHED, adapter.signings[1].third)
        val next = WalletRepository(store, adapter, connections, io = Dispatchers.Unconfined)
        next.load()
        assertEquals(selected, next.wallet.value)
        next.sign(message, checkNotNull(next.wallet.value))
        assertEquals(REFRESHED, adapter.signings[2].third)
    }

    @Test
    fun keepsTheReplacedAuthorizationWhenTheOwnerDeclinesTheSigning() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        adapter.answerSigning(SignResult.Declined)
        adapter.refreshedAuthorization = REFRESHED

        assertEquals(SignResult.Declined, repository.sign(ByteString.copyFromUtf8("x"), selected))

        // Declining says something about the message, not about this phone's authorization: the
        // replacement the wallet issued is still good, and the wallet is still connected.
        assertEquals(REFRESHED, store.authorization())
        assertEquals(selected, repository.wallet.value)
        assertEquals(WALLET, server.wallet?.wallet)
    }

    @Test
    fun forgetsAnAuthorizationTheWalletRefusesWhileSigning() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        adapter.answerSigning(SignResult.AuthorizationExpired)
        // A refused authorization is refused, whatever else the wallet reported with it.
        adapter.refreshedAuthorization = REFRESHED

        assertEquals(
            SignResult.AuthorizationExpired,
            repository.sign(ByteString.copyFromUtf8("x"), selected),
        )
        // Nothing is left to sign with, and every sidecar is told there is no wallet.
        assertNull(repository.wallet.value)
        assertNull(store.authorization())
        assertNull(server.wallet)
    }

    @Test
    fun handsTheWalletTheExactTransactionAndTheStoredAuthorization() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        val signature = ByteString.copyFrom(ByteArray(64) { 4 })
        adapter.sendWith(signature)

        val transaction = ByteString.copyFrom(ByteArray(215) { (it * 3).toByte() })
        val result = repository.signAndSend(transaction, selected)

        assertEquals(SendResult.Sent(signature), result)
        val asked = adapter.sendings.single()
        assertEquals(transaction, asked.first)
        assertEquals(selected, asked.second)
        assertEquals(SECRET, asked.third)
        // The wallet's authorization reaches the wallet and nothing else, sidecars included.
        assertTrue(gateway.sent.none { (_, secret) -> secret == SECRET })
    }

    @Test
    fun sendsNothingForASelectionTheOwnerDidNotReview() = runBlocking {
        pair()
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        val transaction = ByteString.copyFromUtf8("x")
        for (reviewed in listOf(REVIEWED, selected.copy(network = WalletNetwork.Mainnet))) {
            assertEquals(SendResult.Changed, repository.signAndSend(transaction, reviewed))
        }
        assertEquals(emptyList<Any>(), adapter.sendings)
        // And with no wallet at all there is nothing to ask.
        repository.disconnect()
        assertEquals(SendResult.NotConnected, repository.signAndSend(transaction, selected))
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun runsOneWalletInteractionAtATime() = runBlocking {
        pair()
        adapter.answerConnected(WALLET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 1 }))
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 2 }))
        val inTheWallet = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        adapter.beforeSending = {
            inTheWallet.complete(Unit)
            release.await()
        }

        coroutineScope {
            val sending = launch { repository.signAndSend(ByteString.copyFromUtf8("t"), selected) }
            inTheWallet.await()
            // A signature asked for while a transaction is in front of the owner waits its turn:
            // two wallet screens at once is how one approval signs the other's bytes.
            val signing = launch { repository.sign(ByteString.copyFromUtf8("m"), selected) }
            // Let it run as far as it can: without the lock it would reach the wallet right now.
            repeat(4) { yield() }
            assertEquals(emptyList<Any>(), adapter.signings)
            release.complete(Unit)
            sending.join()
            signing.join()
        }
        assertEquals(1, adapter.sendings.size)
        assertEquals(1, adapter.signings.size)
    }

    @Test
    fun forgetsAnAuthorizationTheWalletRefusesWhileSending() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        adapter.answerSending(SendResult.AuthorizationExpired)

        assertEquals(
            SendResult.AuthorizationExpired,
            repository.signAndSend(ByteString.copyFromUtf8("x"), selected),
        )
        assertNull(repository.wallet.value)
        assertNull(store.authorization())
        assertNull(server.wallet)
    }

    @Test
    fun keepsAnAuthorizationTheWalletReplacesWhileSending() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        val publications = server.publications
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 4 }))
        // The wallet reauthorizes this app as it sends, and hands back another authorization.
        adapter.refreshedAuthorization = REFRESHED

        val transaction = ByteString.copyFromUtf8("t")
        assertTrue(repository.signAndSend(transaction, selected) is SendResult.Sent)

        // The one it replaced went to the wallet; the replacement is what this phone now keeps.
        assertEquals(SECRET, adapter.sendings.single().third)
        assertEquals(REFRESHED, store.authorization())
        // The owner's selection is untouched, and no sidecar was told anything new about it.
        assertEquals(selected, repository.wallet.value)
        assertEquals(selected, store.selected())
        assertEquals(publications, server.publications)

        // The next operation offers the replacement, whichever one it is, and so does a repository
        // that starts from the record this phone stored, the way the app does when it is opened
        // again. Without this, a transfer would leave the next one offering a token the wallet has
        // already replaced.
        repository.signAndSend(transaction, selected)
        assertEquals(REFRESHED, adapter.sendings[1].third)
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        repository.sign(ByteString.copyFromUtf8("m"), selected)
        assertEquals(REFRESHED, adapter.signings.single().third)
        val next = WalletRepository(store, adapter, connections, io = Dispatchers.Unconfined)
        next.load()
        assertEquals(selected, next.wallet.value)
        next.signAndSend(transaction, checkNotNull(next.wallet.value))
        assertEquals(REFRESHED, adapter.sendings[2].third)
    }

    @Test
    fun keepsTheReplacedAuthorizationWhateverBecameOfTheTransaction() = runBlocking {
        // Declining says something about the transaction, and an outcome nobody knows says nothing
        // at all: neither says this phone's authorization is no good.
        pair()
        for (outcome in listOf(SendResult.Declined, SendResult.Unknown("no answer"))) {
            adapter.answerConnected(WALLET, authToken = SECRET)
            repository.connect(WalletNetwork.Devnet)
            val selected = checkNotNull(repository.wallet.value)
            adapter.answerSending(outcome)
            adapter.refreshedAuthorization = REFRESHED

            assertEquals(outcome, repository.signAndSend(ByteString.copyFromUtf8("t"), selected))

            assertEquals(REFRESHED, store.authorization())
            assertEquals(selected, repository.wallet.value)
            repository.disconnect()
            adapter.refreshedAuthorization = null
        }
    }

    @Test
    fun aStorageFailureNeverChangesWhatTheWalletDid() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        val signature = ByteString.copyFrom(ByteArray(64) { 4 })
        adapter.sendWith(signature)
        adapter.refreshedAuthorization = REFRESHED
        // This phone's storage goes away while the transaction is with the wallet.
        adapter.beforeSending = { storageFails = true }

        val result = repository.signAndSend(ByteString.copyFromUtf8("t"), selected)

        // The wallet sent it, and that is what is reported: a phone that couldn't write the
        // replacement token down does not turn a sent transaction into a failure, and it does not
        // ask the wallet for anything a second time.
        assertEquals(SendResult.Sent(signature), result)
        assertEquals(1, adapter.sendings.size)
        storageFails = false
        // The authorization is the one that was there, which is what an expired one leads to
        // anyway: the owner connects the wallet again.
        assertEquals(SECRET, store.authorization())
        assertEquals(selected, repository.wallet.value)
    }

    @Test
    fun asksTheWalletNothingWhenTheStoredRecordIsNotTheSelectionInHand() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        // A record naming another account can only come from a phone that was interrupted between
        // two writes, which is what the single record makes impossible. If one ever appeared, its
        // token is not this selection's, and nothing is signed or sent with it.
        store.put(selected.copy(address = OTHER_WALLET), "authorization-for-another-account")

        assertEquals(
            SendResult.NotConnected,
            repository.signAndSend(ByteString.copyFromUtf8("t"), selected),
        )
        assertEquals(
            SignResult.NotConnected,
            repository.sign(ByteString.copyFromUtf8("m"), selected),
        )
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(emptyList<Any>(), adapter.signings)
    }

    @Test
    fun forgetsTheWalletWhenItNoLongerAuthorizesTheReviewedAccount() = runBlocking {
        pair()
        adapter.answerConnected(WALLET, authToken = SECRET)
        repository.connect(WalletNetwork.Devnet)
        val selected = checkNotNull(repository.wallet.value)
        // The adapter reports this when the wallet's own reauthorization named another account.
        adapter.answerSending(SendResult.Changed)
        adapter.answerSigning(SignResult.Changed)

        assertEquals(
            SendResult.Changed,
            repository.signAndSend(ByteString.copyFromUtf8("t"), selected),
        )

        // Nothing is left to sign with: the owner connects the wallet again and reviews afresh,
        // and every sidecar is told there is no wallet rather than one this phone can't use.
        assertNull(repository.wallet.value)
        assertNull(store.session())
        assertNull(server.wallet)
        assertEquals(
            SignResult.NotConnected,
            repository.sign(ByteString.copyFromUtf8("m"), selected),
        )
    }

    // SEE-159: the wallet app the owner connected, stored with their account, and reused by every
    // signing — including the first one after the app was restarted.

    @Test
    fun storesTheWalletAppTheOwnerPickedAndTheUriTheWalletReported() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER, OTHER)
        adapter.answerConnected(
            WALLET,
            authToken = SECRET,
            route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
        )

        repository.connect(WalletNetwork.Devnet, SEEKER)

        // What the owner picked reached the wallet, and what came back is what is kept.
        assertEquals(
            WalletRouting(packageName = SEEKER.packageName, appLabel = SEEKER.label),
            adapter.routes.single(),
        )
        assertEquals(
            WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
            store.session()?.route,
        )
        // The screen names the app, so an account's own label can't be read as the wallet.
        assertEquals(SEEKER.label, repository.walletApp.value)
    }

    @Test
    fun needsNobodyToPickWhenThisPhoneHasOneWalletApp() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER)
        adapter.answerConnected(WALLET, authToken = SECRET)

        repository.connect(WalletNetwork.Devnet)

        // One installed wallet is one answer, and the system gave it: nothing asks the owner, and
        // nothing asks Android either.
        assertEquals(
            WalletRouting(packageName = SEEKER.packageName, appLabel = SEEKER.label),
            adapter.routes.single(),
        )
    }

    @Test
    fun opensTheSameWalletAppForEverySigningAndAfterARestart() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER, OTHER)
        adapter.answerConnected(
            WALLET,
            authToken = SECRET,
            route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
        )
        repository.connect(WalletNetwork.Devnet, SEEKER)
        val selected = checkNotNull(repository.wallet.value)
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        adapter.routes.clear()

        repository.sign(ByteString.copyFromUtf8("one"), selected)
        repository.sign(ByteString.copyFromUtf8("two"), selected)

        val route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label)
        assertEquals(listOf(route, route), adapter.routes)

        // A restart is a repository made afresh over the same stored record. The route is read
        // back, so the first approval after it opens the same wallet as the one before it.
        val restarted = WalletRepository(store, adapter, connections, io = Dispatchers.Unconfined)
        restarted.load()
        adapter.routes.clear()
        restarted.sign(ByteString.copyFromUtf8("three"), selected)

        assertEquals(listOf(route), adapter.routes)
        assertEquals(SEEKER.label, restarted.walletApp.value)
    }

    @Test
    fun keepsTheAssociationUriTheWalletMovedTo() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER)
        adapter.answerConnected(
            WALLET,
            authToken = SECRET,
            route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
        )
        repository.connect(WalletNetwork.Devnet, SEEKER)
        val selected = checkNotNull(repository.wallet.value)
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        adapter.reportedUriBase = MOVED

        repository.sign(ByteString.copyFromUtf8("one"), selected)

        // A wallet reauthorizes before it signs and may say it now lives somewhere else. That is
        // the one to use from now on, exactly as a replaced authorization is.
        assertEquals(
            WalletRouting(MOVED, SEEKER.packageName, SEEKER.label),
            store.session()?.route,
        )
        // And the selection itself is untouched: the owner reviewed that account, on that network.
        assertEquals(selected, store.selected())
    }

    @Test
    fun keepsTheRouteWhenTheWalletSaysNothingAboutWhereItLives() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER)
        val route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label)
        adapter.answerConnected(WALLET, authToken = SECRET, route = route)
        repository.connect(WalletNetwork.Devnet, SEEKER)
        val selected = checkNotNull(repository.wallet.value)
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 4 }))
        adapter.reportedUriBase = null

        repository.signAndSend(ByteString.copyFromUtf8("tx"), selected)

        assertEquals(route, store.session()?.route)
    }

    @Test
    fun picksAWalletAppOverTheOneTheOwnerHadAndKeepsNothingOfTheOldOne() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER, OTHER)
        adapter.answerConnected(
            WALLET,
            authToken = SECRET,
            route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
        )
        repository.connect(WalletNetwork.Devnet, SEEKER)
        adapter.routes.clear()
        adapter.answerConnected(
            OTHER_WALLET,
            authToken = REFRESHED,
            route = WalletRouting(null, OTHER.packageName, OTHER.label),
        )

        repository.connect(WalletNetwork.Devnet, OTHER)

        // Changing the wallet app carries nothing over from the one being left — least of all its
        // association URI, which would send the next approval back to it.
        assertEquals(
            WalletRouting(packageName = OTHER.packageName, appLabel = OTHER.label),
            adapter.routes.single(),
        )
        assertEquals(
            WalletRouting(packageName = OTHER.packageName, appLabel = OTHER.label),
            store.session()?.route,
        )
        assertEquals(OTHER.label, repository.walletApp.value)
    }

    @Test
    fun switchingWalletAppsDoesNotOfferTheOldAppsAuthorizationToTheNewOne() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER, OTHER)
        adapter.issuers[SECRET] = SEEKER.packageName
        adapter.answerConnected(
            WALLET,
            authToken = SECRET,
            route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
        )
        repository.connect(WalletNetwork.Devnet, SEEKER)
        adapter.answerConnected(
            OTHER_WALLET,
            authToken = REFRESHED,
            route = WalletRouting(null, OTHER.packageName, OTHER.label),
        )

        val result = repository.connect(WalletNetwork.Devnet, OTHER)

        // The new app is asked to authorize afresh: the old app's token is not its to honour, and
        // offering it would be refused and read as the selection being gone.
        assertTrue(result is WalletResult.Connected)
        assertEquals(null, adapter.connects.last().second)
        assertEquals(OTHER_WALLET, repository.wallet.value?.address)
        assertEquals(OTHER.label, repository.walletApp.value)
    }

    @Test
    fun reconnectingTheSameWalletAppOffersItsOwnAuthorization() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER, OTHER)
        adapter.issuers[SECRET] = SEEKER.packageName
        adapter.answerConnected(
            WALLET,
            authToken = SECRET,
            route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
        )
        repository.connect(WalletNetwork.Devnet, SEEKER)

        repository.connect(WalletNetwork.Devnet, SEEKER)
        repository.connect(WalletNetwork.Devnet)

        assertEquals(listOf(SECRET, SECRET), adapter.connects.drop(1).map { it.second })
    }

    @Test
    fun disconnectingTakesTheRouteWithIt() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER, OTHER)
        adapter.answerConnected(
            WALLET,
            authToken = SECRET,
            route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
        )
        repository.connect(WalletNetwork.Devnet, SEEKER)
        adapter.routes.clear()

        repository.disconnect()

        // The wallet app was told over its own route, and nothing aimed at it is left behind.
        assertEquals(
            listOf<WalletRouting?>(WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label)),
            adapter.routes,
        )
        assertNull(store.session())
        assertNull(repository.walletApp.value)
    }

    @Test
    fun forgetsTheRouteWithTheSessionWhenTheWalletRefusesTheAccount() = runBlocking {
        pair()
        adapter.installed = listOf(SEEKER)
        adapter.answerConnected(
            WALLET,
            authToken = SECRET,
            route = WalletRouting(URI_BASE, SEEKER.packageName, SEEKER.label),
        )
        repository.connect(WalletNetwork.Devnet, SEEKER)
        val selected = checkNotNull(repository.wallet.value)
        // The wallet reauthorized an account the owner never reviewed (SEE-84). Nothing is signed,
        // and nothing about that wallet — route included — is kept to sign with later.
        adapter.answerSigning(SignResult.Changed)

        assertEquals(SignResult.Changed, repository.sign(ByteString.copyFromUtf8("x"), selected))

        assertNull(store.session())
        assertNull(repository.wallet.value)
        assertNull(repository.walletApp.value)
    }

    private companion object {
        val SEEKER = InstalledWallet("com.example.seekerwallet", "Seeker Wallet")
        val OTHER = InstalledWallet("com.example.otherwallet", "Other Wallet")
        const val URI_BASE = "https://wallet.example/ul"
        const val MOVED = "https://wallet.example/ul/v2"
        const val URL = "http://127.0.0.1:8080"
        const val OTHER_URL = "http://127.0.0.1:8081"
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val SECRET = "authorization-the-wallet-issued-0123456789"
        const val REFRESHED = "authorization-the-wallet-issued-later-9876543210"
        const val OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        val REVIEWED =
            SelectedWallet(
                address = OTHER_WALLET,
                network = WalletNetwork.Devnet,
                selectedAt = Instant.parse("2026-09-11T12:00:00Z"),
            )
    }
}
