package io.github.brrenat.seekervault.connections

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.RequestCarouselItem
import io.github.brrenat.seekervault.designsystem.RequestTileKind
import io.github.brrenat.seekervault.designsystem.RequestTileModel
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ServerRowModel
import io.github.brrenat.seekervault.designsystem.ServerRowState
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.feeds.ForegroundFeedsState
import io.github.brrenat.seekervault.inbox.PendingItem
import io.github.brrenat.seekervault.plugins.OperationId
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.proposals.PREDICTION
import io.github.brrenat.seekervault.proposals.PROPOSAL_A
import io.github.brrenat.seekervault.proposals.PROPOSAL_B
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalValue
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val actions = mutableListOf<String>()

    @Test
    fun homeUsesTheReferenceCopyAndDelegatesEveryAction() {
        show(screenState())

        compose.onNodeWithText(HomeCopy.Title).assertExists()
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        compose.onNodeWithText("1 · see all").performClick()
        compose.onNodeWithText("Still here?").performClick()
        compose
            .onNodeWithTag(ConnectionsTags.GLOBAL_RULES)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose
            .onNodeWithContentDescription("Open studio-mac")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose
            .onNodeWithTag(ConnectionsTags.ADD)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(
            listOf(
                "wallet",
                "inbox",
                "pending/private/server/new",
                "rules",
                "server/server",
                "add",
            ),
            actions,
        )
    }

    @Test
    fun homeShowsDesignedEmptyStatesOnlyAfterServersLoad() {
        show(screenState().copy(pendingCount = 0, pending = emptyList(), servers = emptyList()))

        compose
            .onNodeWithTag(ConnectionsTags.PENDING_EMPTY)
            .assertTextContains(HomeCopy.EmptyPendingTitle)
        compose
            .onNodeWithTag(ConnectionsTags.EMPTY)
            .performScrollTo()
            .assertTextContains(HomeCopy.EmptyServersTitle)
        compose.onNodeWithText(HomeCopy.CarouselCaption).assertDoesNotExist()
    }

    @Test
    fun homeHidesTheServerEmptyStateBeforeServersLoad() {
        show(
            screenState()
                .copy(
                    pendingCount = 0,
                    pending = emptyList(),
                    serversLoaded = false,
                    servers = emptyList(),
                )
        )

        compose.onNodeWithTag(ConnectionsTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun mapperSortsPendingNewestFirstAndMapsServerStates() {
        val old =
            PendingItem.Private(
                FakeConnectionGateway.request(
                    connectionId = HOME.id,
                    requestId = "old",
                    text = "Older",
                    createdAt = Instant.parse("2026-09-19T10:00:00Z"),
                )
            )
        val newest =
            PendingItem.Private(
                FakeConnectionGateway.request(
                    connectionId = HOME.id,
                    requestId = "new",
                    text = "Newest",
                    createdAt = Instant.parse("2026-09-19T11:00:00Z"),
                )
            )
        val state =
            homeScreenState(
                connectionsState =
                    ConnectionsUiState(
                        connections = listOf(HOME, OFFLINE, REVOKED),
                        loaded = true,
                        updates =
                            io.github.brrenat.seekervault.sync.ForegroundUpdatesState(
                                connections =
                                    mapOf(
                                        OFFLINE.id to
                                            ForegroundConnectionState.Unreachable(
                                                CheckOutcome.Unreachable
                                            )
                                    )
                            ),
                    ),
                inboxSummary = InboxSummary(waitingForYou = 2, toSend = 0),
                wallet = null,
                pendingItems = listOf(old, newest),
                requestAssessments = emptyMap(),
                formatTime = { "9:48 PM" },
            )

        assertEquals(listOf("Newest", "Older"), state.pending.map { it.tile.title })
        assertEquals(
            listOf(
                ServerRowState.Connected,
                ServerRowState.Unreachable,
                ServerRowState.Disconnected,
            ),
            state.servers.map(HomeServerState::rowState),
        )
        assertEquals("Couldn’t reach the server · 9:48 PM", state.servers[1].model.statusText)
        assertEquals("Wallet", state.wallet.name)
        assertEquals(false, state.wallet.canCopy)
    }

    @Test
    fun feedUsesLiveTransportAndCurrentOpenSignalsForStatusAndPredictionCopy() {
        val feed =
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
        val first = prediction(feed, PROPOSAL_A, "Will SOL close above \$200?", "polymarket")
        val second = prediction(feed, PROPOSAL_B, "Will the Fed cut rates?", "jupiter-prediction")
        val live =
            homeScreenState(
                connectionsState =
                    ConnectionsUiState(
                        connections = listOf(feed),
                        loaded = true,
                        feeds =
                            ForegroundFeedsState(
                                foreground = true,
                                gateways = mapOf(feed.serverUrl to FeedListenerState.Live(1)),
                            ),
                    ),
                inboxSummary = null,
                wallet = null,
                pendingItems = listOf(first, second),
                requestAssessments = emptyMap(),
            )

        assertEquals(ServerRowState.Connected, live.servers.single().rowState)
        assertEquals("Connected · 2 pending", live.servers.single().model.statusText)
        assertEquals("Will SOL close above \$200?", live.pending.first().tile.title)
        assertEquals("Polymarket", live.pending.first().tile.footerText)

        val reconnecting =
            homeScreenState(
                connectionsState =
                    ConnectionsUiState(
                        connections = listOf(feed),
                        loaded = true,
                        feeds =
                            ForegroundFeedsState(
                                foreground = true,
                                gateways =
                                    mapOf(feed.serverUrl to FeedListenerState.Reconnecting(2)),
                            ),
                    ),
                inboxSummary = null,
                wallet = null,
                pendingItems = listOf(second),
                requestAssessments = emptyMap(),
            )

        assertEquals(ServerRowState.Unreachable, reconnecting.servers.single().rowState)
        assertEquals("Reconnecting · 1 pending", reconnecting.servers.single().model.statusText)
    }

    private fun prediction(
        feed: Connection,
        id: String,
        title: String,
        provider: String,
    ): PendingItem.Signal {
        val base = proposal()
        return PendingItem.Signal(
            ProposalRecord(
                connectionId = feed.id,
                proposal =
                    base.copy(
                        key = base.key.copy(proposalId = id),
                        title = title,
                        operation = OperationId(PREDICTION),
                        values = listOf(ProposalValue("provider", provider)),
                    ),
            )
        )
    }

    private fun show(state: HomeScreenState) = compose.setContent {
        SeekerTheme {
            HomeScreen(
                state = state,
                callbacks =
                    HomeScreenCallbacks(
                        onWallet = { actions += "wallet" },
                        onCopyWalletAddress = { actions += "copy" },
                        onSeeAll = { actions += "inbox" },
                        onPending = { actions += "pending/$it" },
                        onGlobalRules = { actions += "rules" },
                        onServer = { actions += "server/$it" },
                        onRetryServer = { actions += "retry/$it" },
                        onAddConnection = { actions += "add" },
                        navigation = ScreenNavigationCallbacks({}, {}, {}, {}),
                    ),
            )
        }
    }

    private fun screenState() =
        HomeScreenState(
            wallet = HomeWalletState("renatnomad.skr", WALLET_ADDRESS, true),
            pendingCount = 1,
            pending =
                listOf(
                    RequestCarouselItem(
                        id = "private/server/new",
                        kind = RequestTileKind.Acknowledgement,
                        tile =
                            RequestTileModel(
                                title = "Still here?",
                                sourceName = "studio-mac",
                                supportingText = "studio-mac asks",
                                warningCount = 0,
                            ),
                    )
                ),
            serversLoaded = true,
            servers =
                listOf(
                    HomeServerState(
                        id = "server",
                        model = ServerRowModel("studio-mac", "SM", "Connected · 2 pending"),
                        rowState = ServerRowState.Connected,
                    )
                ),
        )

    companion object {
        private const val WALLET_ADDRESS = "Bzy2LsonMmTZmLpKX3dAZ4NLaEqTQs772CzUm2B16K54"
        private val PAIRED = Instant.parse("2026-09-11T12:00:00Z")
        val HOME =
            Connection(
                id = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c",
                label = "Home Mac",
                serverUrl = "https://mac.tailnet.ts.net",
                serverId = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a",
                deviceName = "Seeker",
                pairedAt = PAIRED,
                lastCheck = Connection.Check(PAIRED, CheckOutcome.Ok, pending = 2),
            )
        val VPS =
            HOME.copy(
                id = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f",
                label = "VPS",
                serverUrl = "https://vps.example.com:8443",
                serverId = "1f0e2d3c-4b5a-4698-8776-5a4b3c2d1e0f",
                revokedAt = PAIRED.plusSeconds(3600),
                lastCheck = null,
                hasCredential = false,
            )
        private val OFFLINE =
            HOME.copy(
                id = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3",
                label = "hermes-box",
                lastCheck = Connection.Check(PAIRED, CheckOutcome.Unreachable),
            )
        private val REVOKED = VPS
    }
}
