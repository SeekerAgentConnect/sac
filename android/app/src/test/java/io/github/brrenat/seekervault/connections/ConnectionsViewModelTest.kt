package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The connection screens' state against fake sidecars. Coroutines run eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ConnectionsViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val key = softwareKey()

    private val repository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "connections")),
            vault = CredentialVault(File(folder.root, "credentials")) { key },
            results = ResultStore(File(folder.root, "results")),
            gateway = gateway,
            deviceName = "Seeker",
            io = Dispatchers.Unconfined,
        )
    }

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    private fun viewModel() = ConnectionsViewModel(repository) { it == "127.0.0.1" }

    private fun text(code: PairingCode) =
        "seekervault://pair?v=1&url=${java.net.URLEncoder.encode(code.serverUrl, Charsets.UTF_8)}" +
            "&server=${code.serverId}&token=${code.token}"

    @Test
    fun fetchesAgainWhenTheAppComesBackToTheForegroundButNotOnARotation() {
        val connection = runBlocking { repository.pair(server.issue(URL)) }
        val viewModel = viewModel() // the app opens, and fetches
        assertEquals(0, viewModel.state.value.connections.single().lastCheck?.pending)
        server.addPending(connection.id)
        // A rotation stops and starts the activity without leaving the foreground.
        viewModel.onAppVisible()
        assertEquals(0, viewModel.state.value.connections.single().lastCheck?.pending)
        viewModel.onAppHidden()
        viewModel.onAppVisible()
        assertEquals(1, viewModel.state.value.connections.single().lastCheck?.pending)
    }

    @Test
    fun saysWhyACodeIsMalformedAndForgetsThatOnEdit() {
        val viewModel = viewModel()
        viewModel.onCodeDraftChange("seekervault://pair?v=2")
        viewModel.onCode("seekervault://pair?v=2")
        assertEquals(
            PairingState.Invalid(PairingCodeProblem.OtherVersion),
            viewModel.state.value.pairing,
        )
        viewModel.onCodeDraftChange("seekervault://pair?v=1")
        assertEquals(PairingState.Idle, viewModel.state.value.pairing)
    }

    @Test
    fun asksToConfirmTheServerThenPairs() {
        val viewModel = viewModel()
        val code = server.issue(URL)
        viewModel.onCode(text(code))
        val confirm = viewModel.state.value.pairing as PairingState.Confirm
        assertEquals(code, confirm.confirmation.code)
        // A second scan while confirming changes nothing.
        viewModel.onCode(text(server.issue(URL)))
        assertEquals(confirm, viewModel.state.value.pairing)

        viewModel.confirmPairing()
        val paired = viewModel.state.value.pairing as PairingState.Paired
        assertEquals(listOf(paired.connection.id), viewModel.state.value.connections.map { it.id })
        assertEquals(ConnectionMessage.Paired("vault.example.com"), viewModel.state.value.message)
        viewModel.resetPairing()
        assertEquals(PairingState.Idle, viewModel.state.value.pairing)
        assertEquals("", viewModel.state.value.codeDraft)
    }

    @Test
    fun notesAKnownServerAndFindsItsOldConnectionRevokedAfterPairing() {
        val viewModel = viewModel()
        val old = runBlocking { repository.pair(server.issue(URL)) }
        viewModel.onCode(text(server.issue(URL)))
        val confirm = viewModel.state.value.pairing as PairingState.Confirm
        assertEquals(listOf(old.id), confirm.confirmation.sameServer.map { it.id })
        viewModel.confirmPairing()
        assertNotNull(repository.connection(old.id)?.revokedAt)
    }

    @Test
    fun failsARefusedCodeWithoutOfferingARetry() {
        val viewModel = viewModel()
        viewModel.onCode(text(PairingCode(URL, server.serverId, newSecret())))
        viewModel.confirmPairing()
        val failed = viewModel.state.value.pairing as PairingState.Failed
        assertEquals(PairingFailure.CodeRefused, failed.failure)
        viewModel.confirmPairing()
        assertEquals(failed, viewModel.state.value.pairing)
        assertTrue(viewModel.state.value.connections.isEmpty())
    }

    @Test
    fun retriesWhenTheServerWasUnreachable() {
        val viewModel = viewModel()
        server.failure = GatewayException.Kind.Unreachable
        viewModel.onCode(text(server.issue(URL)))
        viewModel.confirmPairing()
        assertEquals(
            PairingFailure.Unreachable,
            (viewModel.state.value.pairing as PairingState.Failed).failure,
        )
        server.failure = null
        viewModel.confirmPairing()
        assertTrue(viewModel.state.value.pairing is PairingState.Paired)
    }

    @Test
    fun refusesPlainHttpToAnythingButLoopback() {
        val viewModel = viewModel()
        viewModel.onCode(
            text(PairingCode("http://192.168.1.20:8080", server.serverId, newSecret()))
        )
        assertEquals(
            PairingState.Invalid(PairingCodeProblem.InsecureServerUrl),
            viewModel.state.value.pairing,
        )
    }

    @Test
    fun disconnectsAndRemovesTheConnection() {
        val connection = runBlocking { repository.pair(server.issue(URL)) }
        val viewModel = viewModel()
        viewModel.askToDisconnect(connection.id)
        assertEquals(DisconnectState.Confirm(connection.id), viewModel.state.value.disconnect)
        viewModel.confirmDisconnect()
        assertNull(viewModel.state.value.disconnect)
        assertTrue(viewModel.state.value.connections.isEmpty())
        assertEquals(
            ConnectionMessage.Disconnected("vault.example.com"),
            viewModel.state.value.message,
        )
        assertTrue(connection.id in server.revoked)
    }

    @Test
    fun offersToRemoveAConnectionWhoseServerCantBeTold() {
        val connection = runBlocking { repository.pair(server.issue(URL)) }
        val viewModel = viewModel()
        server.failure = GatewayException.Kind.CertificateRejected
        viewModel.askToDisconnect(connection.id)
        viewModel.confirmDisconnect()
        assertEquals(
            DisconnectState.NotReached(connection.id, CheckOutcome.CertificateRejected),
            viewModel.state.value.disconnect,
        )
        viewModel.confirmRemove()
        assertTrue(viewModel.state.value.connections.isEmpty())
        assertEquals(ConnectionMessage.Removed("vault.example.com"), viewModel.state.value.message)
    }

    @Test
    fun removesARevokedConnectionLocally() {
        val connection = runBlocking { repository.pair(server.issue(URL)) }
        server.revoke(connection.id)
        val viewModel = viewModel() // refreshes on start, and learns of the revocation
        assertNotNull(repository.connection(connection.id)?.revokedAt)
        viewModel.askToDisconnect(connection.id)
        assertEquals(DisconnectState.ConfirmRemove(connection.id), viewModel.state.value.disconnect)
        viewModel.confirmRemove()
        assertTrue(viewModel.state.value.connections.isEmpty())
    }

    @Test
    fun renamesOrSaysWhyNot() {
        val connection = runBlocking { repository.pair(server.issue(URL)) }
        val viewModel = viewModel()
        assertEquals(LabelProblem.Blank, viewModel.rename(connection.id, "   "))
        assertEquals(LabelProblem.TooLong, viewModel.rename(connection.id, "x".repeat(65)))
        assertNull(viewModel.rename(connection.id, " Home "))
        assertEquals("Home", viewModel.state.value.connections.single().label)
        assertEquals(ConnectionMessage.Renamed("Home"), viewModel.state.value.message)
    }

    @Test
    fun refreshesTheConnectionsWhenTheAppOpens() {
        val connection = runBlocking { repository.pair(server.issue(URL)) }
        server.addPending(connection.id)
        val viewModel = viewModel()
        assertEquals(1, viewModel.state.value.connections.single().lastCheck?.pending)
        assertTrue(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.refreshing.isEmpty())
    }

    private companion object {
        const val URL = "https://vault.example.com"
    }
}
