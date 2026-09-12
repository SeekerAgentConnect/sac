package io.github.brrenat.seekervault.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The Wallet screen's state, against a fake wallet and a fake sidecar. Coroutines run eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WalletViewModelTest {
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

    private val store by lazy {
        WalletStore(File(folder.root, "wallet"), File(folder.root, "no_backup/wallet")) { key }
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

    private fun pair() = runBlocking {
        connections.load()
        connections.pair(server.issue(URL))
    }

    @Test
    fun startsWithNoWalletAndMainnetChosen() {
        val model = viewModel()
        assertTrue(model.state.value.loaded)
        assertNull(model.state.value.wallet)
        assertEquals(WalletNetwork.Mainnet, model.state.value.network)
    }

    @Test
    fun connectsOnTheChosenNetworkAndShowsTheAddress() {
        pair()
        val model = viewModel()
        adapter.answerConnected(WALLET)
        model.chooseNetwork(WalletNetwork.Devnet)
        model.connect()

        assertEquals(listOf(WalletNetwork.Devnet), adapter.connects.map { it.first })
        assertEquals(WALLET, model.state.value.wallet?.address)
        assertFalse(model.state.value.connecting)
        assertNull(model.state.value.problem)
        assertEquals(emptyList<Any>(), model.state.value.unpublished)
    }

    @Test
    fun keepsTheNetworkFixedWhileAWalletIsConnected() {
        pair()
        val model = viewModel()
        adapter.answerConnected(WALLET)
        model.connect()
        model.chooseNetwork(WalletNetwork.Testnet)
        assertEquals(WalletNetwork.Mainnet, model.state.value.network)
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
            assertNull(model.state.value.wallet)
            model.problemShown()
            assertNull(model.state.value.problem)
        }
    }

    @Test
    fun showsWhatTheWalletSaidOnAnUnknownFailure() {
        val model = viewModel()
        adapter.answer(WalletResult.Failed("the wallet timed out"))
        model.connect()
        assertEquals("the wallet timed out", model.state.value.detail)
    }

    @Test
    fun disconnectsAndTellsTheSidecar() {
        pair()
        val model = viewModel()
        adapter.answerConnected(WALLET)
        model.connect()
        model.disconnect()

        assertNull(model.state.value.wallet)
        assertNull(server.wallet)
        assertFalse(model.state.value.disconnecting)
    }

    @Test
    fun namesTheConnectionsItCouldNotTell() {
        val connection = pair()
        val model = viewModel()
        adapter.answerConnected(WALLET)
        server.failure = GatewayException.Kind.Unreachable
        model.connect()
        assertEquals(listOf(connection.id), model.state.value.unpublished.map { it.id })

        server.failure = null
        model.publishAgain()
        assertEquals(emptyList<Any>(), model.state.value.unpublished)
        assertEquals(WALLET, server.wallet?.wallet)
    }

    @Test
    fun tellsAConnectionPairedWhileTheScreenIsOpen() {
        val model = viewModel()
        adapter.answerConnected(WALLET)
        model.connect()
        // No connection yet, so there was nothing to tell.
        assertNull(server.wallet)

        // The owner pairs without leaving the app, so the new sidecar is told straight away
        // rather than after the app has been hidden and shown again.
        pair()
        assertEquals(WALLET, server.wallet?.wallet)
        assertEquals(emptyList<Any>(), model.state.value.unpublished)
    }

    @Test
    fun tellsAConnectionPairedLaterWhenTheAppComesBack() {
        val model = viewModel()
        adapter.answerConnected(WALLET)
        model.connect()
        // No connection yet, so there was nothing to tell.
        assertNull(server.wallet)

        pair()
        model.onAppHidden()
        model.onAppVisible()
        assertEquals(WALLET, server.wallet?.wallet)
    }

    private companion object {
        const val URL = "http://127.0.0.1:8080"
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
    }
}
