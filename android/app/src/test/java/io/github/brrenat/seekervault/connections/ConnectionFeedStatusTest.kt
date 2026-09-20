package io.github.brrenat.seekervault.connections

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.ConnectionsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.designsystem.ServerRowState
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.feeds.ForegroundFeedsState
import io.github.brrenat.seekervault.inbox.PendingItem
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.proposals.PROPOSAL_A
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import io.github.brrenat.seekervault.sync.ForegroundUpdateManager
import io.github.brrenat.seekervault.sync.ForegroundUpdatesState
import io.github.brrenat.seekervault.sync.LocalRequestState
import io.github.brrenat.seekervault.sync.SyncConnection
import io.github.brrenat.seekervault.sync.SynchronizationHost
import io.github.brrenat.seekervault.sync.SynchronizationRepository
import io.github.brrenat.seekervault.sync.UpdateEndpoint
import io.github.brrenat.seekervault.sync.UpdateTransport
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * SEE-143: a GatewayFeed must not inherit [ForegroundUpdateManager]'s Revoked/credential state, and
 * a Direct connection must not inherit a feed listener keyed by a shared origin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ConnectionFeedStatusTest {
    @get:Rule val folder = TemporaryFolder()

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun managerPublishesRevokedForAFeedAndTheHomeRowStaysLiveWithPending() {
        val feed = feed()
        ConnectionStore(File(folder.root, "connections")).put(feed)
        val connections = MutableStateFlow(listOf(feed))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager =
            ForegroundUpdateManager(
                connections = connections,
                synchronization =
                    SynchronizationRepository(
                        store = SyncStore(File(folder.root, "sync")),
                        transport = UnusedTransport,
                        host = EmptyHost,
                        io = Dispatchers.Unconfined,
                    ),
                dispatcher = Dispatchers.Unconfined,
                ownerScope = owner,
            )

        manager.onForeground()
        assertEquals(ForegroundConnectionState.Revoked, manager.state.value.connections[feed.id])

        val feeds =
            MutableStateFlow(
                ForegroundFeedsState(
                    foreground = true,
                    gateways = mapOf(feed.serverUrl to FeedListenerState.Live(1)),
                )
            )
        val viewModel =
            ConnectionsViewModel(
                repository =
                    ConnectionRepository(
                        store = ConnectionStore(File(folder.root, "connections")),
                        vault = CredentialVault(File(folder.root, "credentials")) { softwareKey() },
                        results = ResultStore(File(folder.root, "results")),
                        gateway = FakeConnectionGateway(),
                        deviceName = "Seeker",
                        io = Dispatchers.Unconfined,
                    ),
                foregroundUpdates = manager.state,
                foregroundFeeds = feeds,
                cleartextPermitted = { false },
            )

        assertEquals(
            ForegroundConnectionState.Revoked,
            viewModel.state.value.updates.connections[feed.id],
        )
        val home =
            homeScreenState(
                connectionsState = viewModel.state.value,
                inboxSummary = null,
                wallet = null,
                pendingItems = listOf(signal(feed)),
                requestAssessments = emptyMap(),
            )
        assertEquals(ServerRowState.Connected, home.servers.single().rowState)
        assertEquals("Connected · 1 pending", home.servers.single().model.statusText)
        assertFalse(
            hasProblem(
                feed,
                live = ForegroundConnectionState.Revoked,
                support = ServerSupport.Supported,
                feed = FeedListenerState.Live(1),
            )
        )

        compose.setContent {
            SeekerTheme {
                HomeScreen(
                    state = home,
                    callbacks =
                        HomeScreenCallbacks(
                            onWallet = {},
                            onCopyWalletAddress = {},
                            onSeeAll = {},
                            onPending = {},
                            onGlobalRules = {},
                            onServer = {},
                            onRetryServer = {},
                            onAddConnection = {},
                            navigation =
                                io.github.brrenat.seekervault.designsystem
                                    .ScreenNavigationCallbacks({}, {}, {}, {}),
                        ),
                )
            }
        }
        compose.onNodeWithText("Connected · 1 pending").assertExists()
        compose.onNodeWithText(HomeCopy.Disconnected).assertDoesNotExist()

        manager.onBackground()
        owner.cancel()
        assertEquals(ForegroundConnectionState.Revoked, manager.state.value.connections[feed.id])
        assertEquals(
            ServerRowState.Connected,
            homeScreenState(
                    connectionsState =
                        viewModel.state.value.copy(
                            feeds =
                                ForegroundFeedsState(
                                    foreground = false,
                                    gateways = mapOf(feed.serverUrl to FeedListenerState.NoStream),
                                )
                        ),
                    inboxSummary = null,
                    wallet = null,
                    pendingItems = listOf(signal(feed)),
                    requestAssessments = emptyMap(),
                )
                .servers
                .single()
                .rowState,
        )
    }

    @Test
    fun feedListenerStatesMapWithoutInheritingDirectRevoked() {
        val feed = feed()
        val pending = listOf(signal(feed))
        val cases =
            listOf(
                FeedListenerState.Connecting to (ServerRowState.Connected to false),
                FeedListenerState.Live(1) to (ServerRowState.Connected to false),
                FeedListenerState.Reconnecting(2) to (ServerRowState.Unreachable to false),
                FeedListenerState.Unreachable(CheckOutcome.Unreachable) to
                    (ServerRowState.Unreachable to true),
                FeedListenerState.NoStream to (ServerRowState.Connected to false),
                FeedListenerState.Refused(3501) to (ServerRowState.Unreachable to true),
            )
        cases.forEach { (listener, expected) ->
            val (row, problem) = expected
            val home =
                homeScreenState(
                    connectionsState =
                        ConnectionsUiState(
                            connections = listOf(feed),
                            loaded = true,
                            updates =
                                ForegroundUpdatesState(
                                    foreground = true,
                                    connections =
                                        mapOf(feed.id to ForegroundConnectionState.Revoked),
                                ),
                            feeds =
                                ForegroundFeedsState(
                                    foreground = true,
                                    gateways = mapOf(feed.serverUrl to listener),
                                ),
                        ),
                    inboxSummary = null,
                    wallet = null,
                    pendingItems = pending,
                    requestAssessments = emptyMap(),
                )
            assertEquals(listener.toString(), row, home.servers.single().rowState)
            assertEquals(
                "Connected · 1 pending".takeIf { row == ServerRowState.Connected }
                    ?: if (listener is FeedListenerState.Reconnecting) {
                        "Reconnecting · 1 pending"
                    } else {
                        "Couldn’t reach the server · 1 pending"
                    },
                home.servers.single().model.statusText,
            )
            assertEquals(
                listener.toString(),
                problem,
                hasProblem(
                    feed,
                    live = ForegroundConnectionState.Revoked,
                    support = ServerSupport.Supported,
                    feed = listener,
                ),
            )
        }
    }

    @Test
    fun aDirectConnectionAtTheSameOriginIgnoresTheFeedListener() {
        val feed = feed()
        val direct = HOME.copy(serverUrl = feed.serverUrl)
        val home =
            homeScreenState(
                connectionsState =
                    ConnectionsUiState(
                        connections = listOf(direct, feed),
                        loaded = true,
                        updates =
                            ForegroundUpdatesState(
                                foreground = true,
                                connections =
                                    mapOf(
                                        direct.id to ForegroundConnectionState.Live,
                                        feed.id to ForegroundConnectionState.Revoked,
                                    ),
                            ),
                        feeds =
                            ForegroundFeedsState(
                                foreground = true,
                                gateways =
                                    mapOf(
                                        feed.serverUrl to
                                            FeedListenerState.Unreachable(CheckOutcome.Unreachable)
                                    ),
                            ),
                    ),
                inboxSummary = null,
                wallet = null,
                pendingItems = listOf(signal(feed)),
                requestAssessments = emptyMap(),
            )
        assertEquals(ServerRowState.Connected, home.servers.first { it.id == direct.id }.rowState)
        assertEquals(ServerRowState.Unreachable, home.servers.first { it.id == feed.id }.rowState)
        assertFalse(
            hasProblem(
                direct,
                live = ForegroundConnectionState.Live,
                feed = FeedListenerState.Unreachable(CheckOutcome.Unreachable),
            )
        )
    }

    @Test
    fun genuineDirectRevocationStaysDisconnected() {
        val revoked =
            HOME.copy(revokedAt = Instant.parse("2026-09-20T12:00:00Z"), hasCredential = false)
        val home =
            homeScreenState(
                connectionsState =
                    ConnectionsUiState(
                        connections = listOf(revoked),
                        loaded = true,
                        updates =
                            ForegroundUpdatesState(
                                connections = mapOf(revoked.id to ForegroundConnectionState.Revoked)
                            ),
                        feeds =
                            ForegroundFeedsState(
                                gateways = mapOf(revoked.serverUrl to FeedListenerState.Live(1))
                            ),
                    ),
                inboxSummary = null,
                wallet = null,
                pendingItems = emptyList(),
                requestAssessments = emptyMap(),
            )
        assertEquals(ServerRowState.Disconnected, home.servers.single().rowState)
        assertTrue(
            hasProblem(
                revoked,
                live = ForegroundConnectionState.Revoked,
                feed = FeedListenerState.Live(1),
            )
        )
    }

    @Test
    fun detailsStayLiveWithoutErrorStylingWhenTheDirectManagerSaysRevoked() {
        val feed = feed()
        compose.setContent {
            SeekerTheme {
                ConnectionDetailsScreen(
                    connection = feed,
                    refreshing = false,
                    disconnect = null,
                    message = null,
                    onBack = {},
                    onRefresh = {},
                    onRename = { null },
                    onDisconnect = {},
                    onConfirmDisconnect = {},
                    onConfirmRemove = {},
                    onDismissDisconnect = {},
                    onMessageShown = {},
                    live = ForegroundConnectionState.Revoked,
                    support = ServerSupport.Supported,
                    feed = FeedListenerState.Live(1),
                )
            }
        }
        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_live))
        assertFalse(
            hasProblem(
                feed,
                live = ForegroundConnectionState.Revoked,
                support = ServerSupport.Supported,
                feed = FeedListenerState.Live(1),
            )
        )
    }

    private fun feed() =
        HOME.copy(
            id = "8d7c6b5a-4938-4271-a0b9-c8d7e6f5a4b3",
            label = "Prediction feed",
            serverUrl = "https://gateway.example.com",
            hasCredential = false,
            mode = ConnectionMode.GatewayFeed,
            lastCheck = null,
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = HOME.serverId,
                        protocolVersion = SERVER_PROTOCOL,
                        settingsRevision = 1,
                        mode = ConnectionMode.GatewayFeed,
                        reference =
                            ServerReference.Feed(
                                "https://gateway.example.com",
                                channelFor(HOME.serverId),
                            ),
                        required = emptyList(),
                        environments = setOf(PluginEnvironment.Production),
                    )
                ),
        )

    private fun signal(feed: Connection): PendingItem.Signal {
        val base = proposal()
        return PendingItem.Signal(
            ProposalRecord(
                connectionId = feed.id,
                proposal = base.copy(key = base.key.copy(proposalId = PROPOSAL_A)),
            )
        )
    }

    private object UnusedTransport : UpdateTransport {
        override suspend fun discover(serverUrl: String, credential: String, connectionId: String) =
            error("a feed must not use the direct update transport")

        override suspend fun sync(
            endpoint: UpdateEndpoint,
            credential: String,
            request: SyncRequest,
        ): SyncResponse = error("a feed must not use the direct update transport")
    }

    private object EmptyHost : SynchronizationHost {
        override suspend fun connectionIds() = emptySet<String>()

        override suspend fun access(connectionId: String): SyncConnection? = null

        override suspend fun retryRecordedResults(connectionId: String) = Unit

        override suspend fun nonterminalActivity(connectionId: String) =
            emptyList<LocalRequestState>()

        override suspend fun authoritativeRequests(connectionId: String) =
            emptyMap<String, io.github.brrenat.seekervault.request.v1.ActionRequest>()

        override suspend fun applyCache(
            state: io.github.brrenat.seekervault.sync.ConnectionSyncState
        ) = Unit

        override suspend fun recordFailure(connectionId: String, failure: CheckOutcome) = Unit

        override suspend fun revoke(connectionId: String) = Unit
    }
}
