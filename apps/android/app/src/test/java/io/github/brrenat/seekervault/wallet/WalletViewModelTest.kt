package io.github.brrenat.seekervault.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.server.v1.SolanaNetwork
import io.github.brrenat.seekervault.servers.ALL_NETWORKS
import io.github.brrenat.seekervault.servers.directManifest
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The Wallets screen's state and a connection's wallet picker (SEE-174), against a fake wallet, the
 * real connection repository and fake sidecars. Coroutines run eagerly.
 *
 * Every test says which profile, which connection and which network were used, and which server
 * heard what: a wallet profile that merely exists proves nothing about who signs with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WalletViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val other = gateway.serve(OTHER_URL)
    private val key = softwareKey()
    private val adapter = FakeWalletAdapter()

    /** Set while a test needs this phone's storage to be unavailable, as a locked Keystore is. */
    private var storageFails = false

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

    private val store by lazy {
        WalletStore(File(folder.root, "wallet"), File(folder.root, "no_backup/wallet")) {
            if (storageFails) throw GeneralSecurityException("the keystore went away") else key
        }
    }

    private val repository by lazy {
        WalletRepository(store, adapter, connections, io = Dispatchers.Unconfined)
    }

    @Before
    fun runEagerly() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun reset() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): WalletViewModel {
        runBlocking { connections.load() }
        return WalletViewModel(repository, connections)
    }

    /**
     * Pairs with [sidecar] at [url], which declares [networks], and reads its manifest the way a
     * refresh does: a server that hasn't said which networks it supports can't be bound to anything
     * a test could then check the network of.
     */
    private fun pair(
        sidecar: FakeConnectionGateway.Server = server,
        url: String = URL,
        networks: List<SolanaNetwork> = ALL_NETWORKS,
    ): Connection = runBlocking {
        sidecar.manifest = directManifest(sidecar.serverId, url, networks = networks)
        connections.load()
        val connection = connections.pair(sidecar.issue(url))
        connections.resolveManifest(connection.id)
        connection
    }

    /** Adds [address] on [network] through the screen, and returns the profile it saved. */
    private fun WalletViewModel.add(
        address: String,
        network: WalletNetwork,
        token: String = "authorization-$address-$network",
    ): WalletProfile {
        adapter.answerConnected(address, authToken = token)
        chooseNetwork(network)
        connect()
        val id = state.value.added.single()
        return state.value.profiles.single { it.id == id }
    }

    private fun binding(address: String, network: Network) = address to network

    private val FakeConnectionGateway.Server.bound
        get() = wallet?.let { it.wallet to it.network }

    @Test
    fun startsWithNoProfilesAndMainnetChosen() {
        val model = viewModel()
        assertTrue(model.state.value.loaded)
        assertEquals(emptyList<WalletProfile>(), model.state.value.profiles)
        assertEquals(WalletNetwork.Mainnet, model.state.value.network)
        assertEquals(emptyList<String>(), model.state.value.added)
    }

    @Test
    fun addsAProfileOnTheChosenNetworkAndBindsItToNothing() {
        val connection = pair()
        val model = viewModel()
        adapter.answerConnected(WALLET_A, authToken = TOKEN)
        model.chooseNetwork(WalletNetwork.Devnet)
        model.connect()

        // Always a fresh authorization, on the network the owner chose: no stored token is
        // offered, so the wallet asks which account to authorize.
        assertEquals(listOf(WalletNetwork.Devnet to null), adapter.connects)
        val profile = model.state.value.profiles.single()
        assertEquals(WALLET_A to WalletNetwork.Devnet, profile.address to profile.network)
        assertEquals(listOf(profile.id), model.state.value.added)
        assertFalse(model.state.value.connecting)
        assertNull(model.state.value.problem)
        // Adding a profile chooses it for nobody: the connection still has no wallet, and its
        // server has only ever been told so.
        assertNull(connections.connection(connection.id)?.walletProfileId)
        assertEquals(WalletReadiness.NoProfile, model.state.value.readiness[connection.id])
        assertNull(model.state.value.walletFor(connection.id))
        assertTrue(gateway.published.all { (_, binding) -> binding == null })
        assertNull(server.wallet)
    }

    @Test
    fun theSameAddressOnAnotherNetworkIsASecondProfile() {
        val model = viewModel()
        val main = model.add(WALLET_A, WalletNetwork.Mainnet)
        val dev = model.add(WALLET_A, WalletNetwork.Devnet)

        assertNotEquals(main.id, dev.id)
        assertEquals(
            listOf(WALLET_A to WalletNetwork.Mainnet, WALLET_A to WalletNetwork.Devnet),
            model.state.value.profiles.map { it.address to it.network },
        )
        assertEquals(listOf(dev.id), model.state.value.added)

        // The same account on the same network again is the profile it already was, refreshed —
        // which is what keeps every connection naming it pointing at the same thing.
        val again = model.add(WALLET_A, WalletNetwork.Mainnet)
        assertEquals(main.id, again.id)
        assertEquals(listOf(main.id, dev.id), model.state.value.profiles.map { it.id })
    }

    @Test
    fun keepsEveryAccountTheWalletAuthorizedAtOnce() {
        val model = viewModel()
        adapter.answerAccounts(WALLET_A, WALLET_B)
        model.chooseNetwork(WalletNetwork.Testnet)
        model.connect()

        val profiles = model.state.value.profiles
        assertEquals(
            listOf(WALLET_A to WalletNetwork.Testnet, WALLET_B to WalletNetwork.Testnet),
            profiles.map { it.address to it.network },
        )
        // Both are offered first by the connection's picker that asked for them.
        assertEquals(profiles.map { it.id }, model.state.value.added)
    }

    @Test
    fun reportsEachRefusalTheWalletCanGive() {
        pair()
        val model = viewModel()
        val expected =
            mapOf(
                WalletResult.NoWallet to WalletProblem.NoWallet,
                WalletResult.Declined to WalletProblem.Declined,
                WalletResult.AuthorizationExpired to WalletProblem.AuthorizationExpired,
                WalletResult.NetworkUnsupported to WalletProblem.NetworkUnsupported,
                WalletResult.Failed("the wallet timed out") to WalletProblem.Failed,
            )
        for ((result, problem) in expected) {
            adapter.answer(result)
            model.connect()
            assertEquals(result.toString(), problem, model.state.value.problem)
            // Cancelling or failing in the wallet changes nothing: no profile, no connection.
            assertEquals(emptyList<WalletProfile>(), model.state.value.profiles)
            assertEquals(emptyList<String>(), model.state.value.added)
            model.problemShown()
            assertNull(model.state.value.problem)
        }
    }

    @Test
    fun showsWhatTheWalletSaidOnAnUnknownFailure() {
        val model = viewModel()
        adapter.answer(WalletResult.Failed("the wallet timed out"))
        model.connect()
        assertEquals(WalletProblem.Failed, model.state.value.problem)
        assertEquals("the wallet timed out", model.state.value.detail)
    }

    @Test
    fun saysSoWhenThisPhoneCouldNotStoreTheAuthorization() {
        val model = viewModel()
        storageFails = true
        adapter.answerConnected(WALLET_A)
        model.connect()

        assertEquals(WalletProblem.Storage, model.state.value.problem)
        assertEquals(emptyList<WalletProfile>(), model.state.value.profiles)
        assertEquals(emptyList<String>(), model.state.value.added)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun reconnectsAProfileInItsOwnWalletAppOnItsOwnNetwork() {
        adapter.installed = listOf(SEEKER)
        val model = viewModel()
        adapter.answerConnected(WALLET_A, authToken = TOKEN, route = SEEKER_ROUTE)
        model.chooseNetwork(WalletNetwork.Devnet)
        model.connect()
        val profile = model.state.value.profiles.single()

        adapter.answerConnected(WALLET_A, authToken = "$TOKEN-renewed", route = SEEKER_ROUTE)
        model.reconnect(profile.id)

        // The authorization it had is offered back, on its network and in its app — not a fresh
        // question on whatever network the screen happens to show.
        assertEquals(WalletNetwork.Devnet to TOKEN, adapter.connects.last())
        assertEquals(SEEKER.packageName, adapter.routes.last()?.packageName)
        assertNull(model.state.value.problem)
        assertNull(model.state.value.working)
        assertEquals(listOf(profile.id), model.state.value.profiles.map { it.id })
    }

    @Test
    fun refusesAReconnectThatAuthorizedAnotherAccount() {
        val model = viewModel()
        val profile = model.add(WALLET_A, WalletNetwork.Mainnet)

        adapter.answerConnected(WALLET_B)
        model.reconnect(profile.id)

        assertEquals(WalletProblem.Failed, model.state.value.problem)
        // The other account isn't saved from here, and the profile is the one it was.
        assertEquals(listOf(profile), model.state.value.profiles)
    }

    @Test
    fun renamesAProfileAndAnEmptyNameGoesBackToTheWallets() {
        val model = viewModel()
        adapter.answerConnected(WALLET_A, label = "Account 1")
        model.connect()
        val profile = model.state.value.profiles.single()

        model.startRename(profile.id)
        assertEquals(profile.id, model.state.value.renaming)
        model.rename(profile.id, "  Trading  ")
        assertNull(model.state.value.renaming)
        assertEquals("Trading", model.state.value.profiles.single().displayLabel)

        model.rename(profile.id, "")
        assertEquals("Account 1", model.state.value.profiles.single().displayLabel)
    }

    @Test
    fun bindsADirectConnectionAndTellsOnlyItsOwnServer() {
        val first = pair()
        val second = pair(other, OTHER_URL)
        val model = viewModel()
        val dev = model.add(WALLET_A, WalletNetwork.Devnet)
        var then = 0

        model.bind(first.id, dev.id) { then++ }

        assertEquals(BindingNotice(first.id, BindOutcome.Published(0)), model.state.value.binding)
        assertEquals(1, then)
        assertEquals(dev.id, connections.connection(first.id)?.walletProfileId)
        assertEquals(binding(WALLET_A, Network.NETWORK_DEVNET), server.bound)
        // The other server heard nothing but "no wallet", and its connection still has none.
        assertNull(other.wallet)
        assertEquals(
            listOf(URL to binding(WALLET_A, Network.NETWORK_DEVNET)),
            gateway.published.mapNotNull { (url, bound) ->
                bound?.let { url to (it.wallet to it.network) }
            },
        )
        assertNull(connections.connection(second.id)?.walletProfileId)
        assertEquals(WalletReadiness.Ready(dev), model.state.value.readiness[first.id])
        assertEquals(WalletReadiness.NoProfile, model.state.value.readiness[second.id])
        assertEquals(dev.id, model.state.value.walletFor(first.id)?.profileId)
        assertNull(model.state.value.walletFor(second.id))
        assertFalse(model.state.value.busy)
    }

    @Test
    fun givesTwoConnectionsTheirOwnProfilesOnTheirOwnNetworks() {
        val first = pair()
        val second = pair(other, OTHER_URL)
        val model = viewModel()
        val aDev = model.add(WALLET_A, WalletNetwork.Devnet)
        val bMain = model.add(WALLET_B, WalletNetwork.Mainnet)

        model.bind(first.id, aDev.id)
        model.bind(second.id, bMain.id)

        assertEquals(binding(WALLET_A, Network.NETWORK_DEVNET), server.bound)
        assertEquals(binding(WALLET_B, Network.NETWORK_MAINNET), other.bound)
        assertEquals(aDev.selected(), model.state.value.walletFor(first.id))
        assertEquals(bMain.selected(), model.state.value.walletFor(second.id))
    }

    @Test
    fun refusesAProfileOnANetworkTheServerDoesNotDeclare() {
        val connection = pair(networks = listOf(SolanaNetwork.SOLANA_NETWORK_MAINNET))
        val model = viewModel()
        val dev = model.add(WALLET_A, WalletNetwork.Devnet)
        var then = 0

        model.bind(connection.id, dev.id) { then++ }

        assertEquals(
            BindingNotice(connection.id, BindOutcome.Incompatible),
            model.state.value.binding,
        )
        // Nothing changed, and nothing that follows a binding ran.
        assertEquals(0, then)
        assertNull(connections.connection(connection.id)?.walletProfileId)
        assertNull(server.wallet)
        model.bindingShown()
        assertNull(model.state.value.binding)
    }

    @Test
    fun removingAProfileLeavesItsConnectionWithoutAWalletAndPicksNoOther() {
        val first = pair()
        val second = pair(other, OTHER_URL)
        val model = viewModel()
        val a = model.add(WALLET_A, WalletNetwork.Mainnet, token = TOKEN)
        val b = model.add(WALLET_B, WalletNetwork.Mainnet)
        model.bind(first.id, a.id)
        model.bind(second.id, b.id)
        assertEquals(binding(WALLET_A, Network.NETWORK_MAINNET), server.bound)

        model.askToRemove(a.id)
        assertEquals(a.id, model.state.value.removing)
        assertEquals(listOf(first.id), model.state.value.usersOf(a.id).map { it.id })
        model.confirmRemove()

        assertNull(model.state.value.removing)
        assertEquals(listOf(b.id), model.state.value.profiles.map { it.id })
        // Left without a wallet — not moved onto B, which is on the same network and would fit —
        // and its server was told there is none. The other connection keeps its own.
        assertNull(connections.connection(first.id)?.walletProfileId)
        assertEquals(WalletReadiness.NoProfile, model.state.value.readiness[first.id])
        assertNull(model.state.value.walletFor(first.id))
        assertNull(server.wallet)
        assertEquals(b.id, connections.connection(second.id)?.walletProfileId)
        assertEquals(binding(WALLET_B, Network.NETWORK_MAINNET), other.bound)
        // Only the removed profile's authorization is forgotten.
        assertEquals(listOf(TOKEN), adapter.disconnects)
    }

    @Test
    fun cancellingARemovalKeepsTheProfile() {
        val model = viewModel()
        val profile = model.add(WALLET_A, WalletNetwork.Mainnet)

        model.askToRemove(profile.id)
        model.cancelRemove()

        assertNull(model.state.value.removing)
        assertEquals(listOf(profile), model.state.value.profiles)
        assertEquals(emptyList<String>(), adapter.disconnects)
    }

    @Test
    fun namesTheServerItCouldNotTellAndTellsItAgain() {
        val connection = pair()
        val model = viewModel()
        val dev = model.add(WALLET_A, WalletNetwork.Devnet)
        server.failure = GatewayException.Kind.Unreachable

        model.bind(connection.id, dev.id)

        // Bound, but not ready: nothing signs against a binding the server may not have.
        assertEquals(
            BindingNotice(connection.id, BindOutcome.PublicationFailed),
            model.state.value.binding,
        )
        assertEquals(dev.id, connections.connection(connection.id)?.walletProfileId)
        assertEquals(
            WalletReadiness.PublicationPending(dev),
            model.state.value.readiness[connection.id],
        )
        assertNull(model.state.value.walletFor(connection.id))

        // Coming back to the app tries again, and names the server that still couldn't be told.
        model.onAppHidden()
        model.onAppVisible()
        assertEquals(listOf(connection.id), model.state.value.unpublished.map { it.id })

        server.failure = null
        model.publishAgain()
        assertEquals(emptyList<Connection>(), model.state.value.unpublished)
        assertEquals(binding(WALLET_A, Network.NETWORK_DEVNET), server.bound)
        assertEquals(WalletReadiness.Ready(dev), model.state.value.readiness[connection.id])
    }

    @Test
    fun tellsAConnectionPairedWhileTheScreenIsOpenThatItHasNoWallet() {
        val model = viewModel()
        model.add(WALLET_A, WalletNetwork.Mainnet)
        // No connection yet, so there was nothing to tell.
        assertEquals(emptyList<Any>(), gateway.published)

        // The owner pairs without leaving the app, so the new sidecar hears straight away — and
        // what it hears is "no wallet", because a saved profile is nobody's until it is chosen.
        val connection = pair()
        assertEquals(listOf(URL to null), gateway.published)
        assertEquals(1, server.publications)
        assertEquals(emptyList<Connection>(), model.state.value.unpublished)
        assertEquals(WalletReadiness.NoProfile, model.state.value.readiness[connection.id])
    }

    @Test
    fun aNewConnectionChoosesItsWalletNext() {
        val connection = pair()
        val model = viewModel()
        model.add(WALLET_A, WalletNetwork.Mainnet)

        model.beginSetup(connection.id)
        assertEquals(connection.id, model.state.value.setup)
        // What an earlier addition saved isn't offered to it as its own.
        assertEquals(emptyList<String>(), model.state.value.added)

        model.endSetup()
        assertNull(model.state.value.setup)
    }

    // SEE-159: which wallet app a profile is added from, and who decides.

    @Test
    fun offersNoChoiceWhenThisPhoneHasOneWalletApp() {
        adapter.installed = listOf(SEEKER)
        adapter.answerConnected(WALLET_A, route = SEEKER_ROUTE)
        val model = viewModel()

        assertEquals(listOf(SEEKER), model.state.value.apps)
        assertNull(model.state.value.chosen)
        // One installed wallet needs no question, and adding is not held up by one: it is aimed at
        // that app all the same.
        assertTrue(model.state.value.canConnect)
        model.connect()
        assertEquals(SEEKER_ROUTE, adapter.routes.single())
        assertEquals(SEEKER.label, model.state.value.profiles.single().walletApp)
    }

    @Test
    fun waitsForTheOwnerToPickWhenThisPhoneHasSeveral() {
        adapter.installed = listOf(SEEKER, OTHER)
        adapter.answerConnected(WALLET_A)
        val model = viewModel()

        // Adding without a pick is what leaves Android asking them at every approval, so the owner
        // picks here instead — once.
        assertFalse(model.state.value.canConnect)
        model.connect()
        assertEquals(emptyList<Any>(), adapter.connects)
        assertEquals(emptyList<WalletProfile>(), model.state.value.profiles)

        model.chooseWalletApp(OTHER.packageName)

        assertEquals(OTHER, model.state.value.chosen)
        assertTrue(model.state.value.canConnect)
        model.connect()
        assertEquals(
            WalletRouting(packageName = OTHER.packageName, appLabel = OTHER.label),
            adapter.routes.single(),
        )
    }

    @Test
    fun ignoresAPickThisPhoneNeverOffered() {
        adapter.installed = listOf(SEEKER, OTHER)
        val model = viewModel()

        model.chooseWalletApp("com.example.nothinglikethat")

        assertNull(model.state.value.chosen)
        assertFalse(model.state.value.canConnect)
    }

    @Test
    fun forgetsAPickWhoseWalletAppWasUninstalledWhileTheAppWasAway() {
        adapter.installed = listOf(SEEKER, OTHER)
        val model = viewModel()
        model.chooseWalletApp(OTHER.packageName)

        adapter.installed = listOf(SEEKER)
        model.onAppHidden()
        model.onAppVisible()

        assertEquals(listOf(SEEKER), model.state.value.apps)
        assertNull(model.state.value.chosen)
    }

    @Test
    fun keepsTheWalletAppApartFromTheAccountLabel() {
        adapter.installed = listOf(SEEKER)
        adapter.answerConnected(WALLET_A, label = "phantom", route = SEEKER_ROUTE)
        val model = viewModel()
        model.connect()

        // The account's own label is the account's; the wallet is the app that holds it (SEE-159).
        val profile = model.state.value.profiles.single()
        assertEquals("phantom", profile.displayLabel)
        assertEquals(SEEKER.label, profile.walletApp)
    }

    private companion object {
        val SEEKER = InstalledWallet("com.example.seekerwallet", "Seeker Wallet")
        val OTHER = InstalledWallet("com.example.otherwallet", "Other Wallet")
        val SEEKER_ROUTE = WalletRouting(packageName = SEEKER.packageName, appLabel = SEEKER.label)
        const val URL = "http://127.0.0.1:8080"
        const val OTHER_URL = "http://127.0.0.1:8081"
        const val WALLET_A = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val WALLET_B = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        const val TOKEN = "authorization-the-wallet-issued-0123456789"
    }
}
