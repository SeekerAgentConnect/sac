package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.FeedReferenceProblem
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.ManifestProblem
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import io.github.brrenat.seekervault.sync.ForegroundUpdatesState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun feedText(
        gateway: String = GATEWAY,
        serverId: String = SERVER_B,
        version: String = "1",
    ) =
        "seekervault://feed?v=$version&gateway=" +
            java.net.URLEncoder.encode(gateway, Charsets.UTF_8) +
            "&server=$serverId"

    private fun feedConnection() =
        Connection(
            id = "00000000-0000-4000-8000-000000000107",
            label = "Copy trading",
            serverUrl = GATEWAY,
            serverId = SERVER_B,
            deviceName = "",
            pairedAt = Instant.parse("2026-09-18T12:00:00Z"),
            hasCredential = false,
            mode = ConnectionMode.GatewayFeed,
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = SERVER_B,
                        protocolVersion = 1,
                        settingsRevision = 1,
                        mode = ConnectionMode.GatewayFeed,
                        reference = ServerReference.Feed(GATEWAY, channelFor(SERVER_B)),
                        required = listOf(PluginRequirement(PluginId("jupiter.swap"), 1..1)),
                        environments = setOf(PluginEnvironment.Production),
                        name = "Copy trading",
                    )
                ),
        )

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
    fun publishesApplicationScopedLivenessWithoutScreenRefresh() {
        val updates = MutableStateFlow(ForegroundUpdatesState())
        val viewModel = ConnectionsViewModel(repository, updates) { it == "127.0.0.1" }

        updates.value =
            ForegroundUpdatesState(
                foreground = true,
                connections = mapOf("connection" to ForegroundConnectionState.Live),
            )

        assertEquals(updates.value, viewModel.state.value.updates)
    }

    @Test
    fun saysWhyACodeIsMalformedAndForgetsThatOnEdit() {
        val viewModel = viewModel()
        viewModel.onCodeDraftChange("seekervault://pair?v=2")
        viewModel.onCode("seekervault://pair?v=2")
        assertEquals(
            AddConnectionState.PairingInvalid(PairingCodeProblem.OtherVersion),
            viewModel.state.value.adding,
        )
        viewModel.onCodeDraftChange("seekervault://pair?v=1")
        assertEquals(AddConnectionState.Idle, viewModel.state.value.adding)
    }

    @Test
    fun routesPairingCodesFeedsAndNeitherWithoutCrossingActions() {
        var feedCalls = 0
        val feed = feedConnection()
        val viewModel =
            ConnectionsViewModel(
                repository,
                addFeed = {
                    feedCalls++
                    FeedOutcome.Added(feed)
                },
                cleartextPermitted = { it == "127.0.0.1" },
            )

        val code = server.issue(URL)
        viewModel.onCode(text(code))
        assertTrue(viewModel.state.value.adding is AddConnectionState.ConfirmPairing)
        viewModel.confirmFeed()
        assertEquals(0, feedCalls)

        viewModel.resetAdding()
        viewModel.onCode(feedText())
        assertEquals(
            AddConnectionState.ConfirmFeed(FeedReference(GATEWAY, SERVER_B)),
            viewModel.state.value.adding,
        )
        viewModel.confirmPairing()
        assertEquals(0, feedCalls)
        viewModel.confirmFeed()
        assertEquals(1, feedCalls)
        assertEquals(AddConnectionState.FeedAdded(feed), viewModel.state.value.adding)

        viewModel.resetAdding()
        viewModel.onCode("not a URI at all")
        assertEquals(
            AddConnectionState.FeedInvalid(FeedReferenceProblem.NotAReference),
            viewModel.state.value.adding,
        )
    }

    @Test
    fun mapsEveryFeedReferenceProblemToItsOwnState() {
        val cases =
            mapOf(
                "not a URI at all" to FeedReferenceProblem.NotAReference,
                "https://example.com/feed" to FeedReferenceProblem.NotSeekerVault,
                feedText(version = "2") to FeedReferenceProblem.OtherVersion,
                feedText(gateway = "$GATEWAY/path") to FeedReferenceProblem.BadGatewayUrl,
                feedText(gateway = "http://gateway.example.com") to
                    FeedReferenceProblem.InsecureGatewayUrl,
                feedText(serverId = "publisher") to FeedReferenceProblem.BadServerId,
            )
        val viewModel = viewModel()

        cases.forEach { (text, problem) ->
            viewModel.onCode(text)
            assertEquals(
                AddConnectionState.FeedInvalid(problem),
                viewModel.state.value.adding,
            )
        }
    }

    @Test
    fun mapsEveryFeedOutcomeAndRetriesOnlyTransientFailures() {
        val feed = feedConnection()
        val outcomes =
            listOf<FeedOutcome>(
                FeedOutcome.Added(feed),
                FeedOutcome.Already(feed),
                FeedOutcome.Refused(ManifestProblem.ForeignChannel),
                *CheckOutcome.entries.map(FeedOutcome::Failed).toTypedArray(),
                FeedOutcome.NoGateway,
            )

        outcomes.forEach { outcome ->
            var calls = 0
            val viewModel =
                ConnectionsViewModel(
                    repository,
                    addFeed = {
                        calls++
                        outcome
                    },
                    cleartextPermitted = { false },
                )
            viewModel.onCode(feedText())
            viewModel.confirmFeed()

            val expected =
                when (outcome) {
                    is FeedOutcome.Added -> AddConnectionState.FeedAdded(feed)
                    is FeedOutcome.Already -> AddConnectionState.FeedAlready(feed)
                    is FeedOutcome.Refused ->
                        AddConnectionState.FeedFailed(
                            FeedReference(GATEWAY, SERVER_B),
                            FeedAddFailure.Refused(outcome.problem),
                        )
                    is FeedOutcome.Failed ->
                        AddConnectionState.FeedFailed(
                            FeedReference(GATEWAY, SERVER_B),
                            FeedAddFailure.Check(outcome.outcome),
                        )
                    FeedOutcome.NoGateway ->
                        AddConnectionState.FeedFailed(
                            FeedReference(GATEWAY, SERVER_B),
                            FeedAddFailure.NoGateway,
                        )
                }
            assertEquals(expected, viewModel.state.value.adding)
            viewModel.confirmFeed()
            val retryable =
                outcome is FeedOutcome.Failed &&
                    outcome.outcome in setOf(CheckOutcome.Unreachable, CheckOutcome.Failed)
            assertEquals(if (retryable) 2 else 1, calls)
        }
    }

    @Test
    fun confirmationAddsOnceAndCancelAddsNothing() {
        var calls = 0
        val feed = feedConnection()
        val viewModel =
            ConnectionsViewModel(
                repository,
                addFeed = {
                    calls++
                    FeedOutcome.Added(feed)
                },
                cleartextPermitted = { false },
            )

        viewModel.onCode(feedText())
        assertEquals(0, calls)
        viewModel.confirmFeed()
        viewModel.confirmFeed()
        assertEquals(1, calls)

        viewModel.resetAdding()
        viewModel.onCode(feedText(serverId = "00000000-0000-4000-8000-000000000108"))
        viewModel.resetAdding()
        viewModel.confirmFeed()
        assertEquals(1, calls)
        assertEquals(AddConnectionState.Idle, viewModel.state.value.adding)
    }

    @Test
    fun asksToConfirmTheServerThenPairs() {
        val viewModel = viewModel()
        val code = server.issue(URL)
        viewModel.onCode(text(code))
        val confirm = viewModel.state.value.adding as AddConnectionState.ConfirmPairing
        assertEquals(code, confirm.confirmation.code)
        // A second scan while confirming changes nothing.
        viewModel.onCode(text(server.issue(URL)))
        assertEquals(confirm, viewModel.state.value.adding)

        viewModel.confirmPairing()
        val paired = viewModel.state.value.adding as AddConnectionState.Paired
        assertEquals(listOf(paired.connection.id), viewModel.state.value.connections.map { it.id })
        assertEquals(ConnectionMessage.Paired("vault.example.com"), viewModel.state.value.message)
        viewModel.resetAdding()
        assertEquals(AddConnectionState.Idle, viewModel.state.value.adding)
        assertEquals("", viewModel.state.value.codeDraft)
    }

    @Test
    fun notesAKnownServerAndFindsItsOldConnectionRevokedAfterPairing() {
        val viewModel = viewModel()
        val old = runBlocking { repository.pair(server.issue(URL)) }
        viewModel.onCode(text(server.issue(URL)))
        val confirm = viewModel.state.value.adding as AddConnectionState.ConfirmPairing
        assertEquals(listOf(old.id), confirm.confirmation.sameServer.map { it.id })
        viewModel.confirmPairing()
        assertNotNull(repository.connection(old.id)?.revokedAt)
    }

    @Test
    fun failsARefusedCodeWithoutOfferingARetry() {
        val viewModel = viewModel()
        viewModel.onCode(text(PairingCode(URL, server.serverId, newSecret())))
        viewModel.confirmPairing()
        val failed = viewModel.state.value.adding as AddConnectionState.PairingFailed
        assertEquals(PairingFailure.CodeRefused, failed.failure)
        viewModel.confirmPairing()
        assertEquals(failed, viewModel.state.value.adding)
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
            (viewModel.state.value.adding as AddConnectionState.PairingFailed).failure,
        )
        server.failure = null
        viewModel.confirmPairing()
        assertTrue(viewModel.state.value.adding is AddConnectionState.Paired)
    }

    @Test
    fun refusesPlainHttpToAnythingButLoopback() {
        val viewModel = viewModel()
        viewModel.onCode(
            text(PairingCode("http://192.168.1.20:8080", server.serverId, newSecret()))
        )
        assertEquals(
            AddConnectionState.PairingInvalid(PairingCodeProblem.InsecureServerUrl),
            viewModel.state.value.adding,
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
