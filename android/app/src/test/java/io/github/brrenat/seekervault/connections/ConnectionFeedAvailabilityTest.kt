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
import io.github.brrenat.seekervault.designsystem.ServerRowState
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.feeds.FeedAvailability
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.feeds.FeedStatusState
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
import io.github.brrenat.seekervault.sync.ForegroundUpdatesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the owner is shown about a feed whose publisher has stopped, while the gateway is perfectly
 * reachable (SEE-150).
 *
 * This is the reported bug at the point it was visible: the row said "Connected" with a pending
 * count, and it said so because the gateway answered — which is true, and is about the gateway. The
 * feed had not moved in hours. So every test here holds the feed listener at `Live`, meaning this
 * phone's own connection is in order, and varies only what the gateway said about the publisher.
 *
 * The row stays `Connected` on purpose. Nothing about this phone's connection is wrong, and there
 * is nothing for the owner to retry — what changes is the sentence, which now says which of the two
 * things is down.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionFeedAvailabilityTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun home(availability: FeedAvailability, feed: Connection = feed()) =
        homeScreenState(
            connectionsState =
                ConnectionsUiState(
                    connections = listOf(feed),
                    loaded = true,
                    updates = ForegroundUpdatesState(foreground = true),
                    feeds =
                        ForegroundFeedsState(
                            foreground = true,
                            gateways = mapOf(feed.serverUrl to FeedListenerState.Live(1)),
                        ),
                    feedStatus =
                        FeedStatusState(
                            foreground = true,
                            feeds = mapOf(feed.id to availability),
                        ),
                ),
            inboxSummary = null,
            wallet = null,
            pendingItems = listOf(signal(feed)),
            requestAssessments = emptyMap(),
        )

    /** The bug: a reachable gateway said "Connected" about a publisher that had stopped. */
    @Test
    fun anOfflinePublisherIsSaidSoOnTheRowWhileTheGatewayIsReachable() {
        val row = home(FeedAvailability.Offline).servers.single()

        assertEquals("${HomeCopy.FeedOffline} · 1 pending", row.model.statusText)
        // Still Connected, and still no retry control: this phone reaches the gateway, the signals
        // already published are readable, and there is nothing here for the owner to retry.
        assertEquals(ServerRowState.Connected, row.rowState)
    }

    @Test
    fun anOnlinePublisherKeepsTheOrdinaryLine() {
        assertEquals(
            "Connected · 1 pending",
            home(FeedAvailability.Online).servers.single().model.statusText,
        )
    }

    /**
     * Not having been told is not evidence of anything. A gateway too old to answer, a read that
     * has not landed yet and a value this build cannot parse all arrive here, and none of them is a
     * reason to tell the owner their feed has stopped.
     */
    @Test
    fun anUnknownPublisherKeepsTheOrdinaryLineAndIsNotAProblem() {
        val row = home(FeedAvailability.Unknown).servers.single()

        assertEquals("Connected · 1 pending", row.model.statusText)
        assertEquals(ServerRowState.Connected, row.rowState)
        assertFalse(
            hasProblem(
                feed(),
                live = ForegroundConnectionState.Revoked,
                support = ServerSupport.Supported,
                feed = FeedListenerState.Live(1),
                availability = FeedAvailability.Unknown,
            )
        )
    }

    /**
     * An unreachable gateway is what the owner hears about first: it is the more basic fact, and
     * the phone has been told nothing about the publisher behind a gateway it cannot reach. So the
     * row is the gateway's, not the publisher's.
     */
    @Test
    fun anUnreachableGatewayIsSaidBeforeAnythingAboutThePublisher() {
        val feed = feed()
        val row =
            homeScreenState(
                    connectionsState =
                        ConnectionsUiState(
                            connections = listOf(feed),
                            loaded = true,
                            feeds =
                                ForegroundFeedsState(
                                    foreground = true,
                                    gateways =
                                        mapOf(
                                            feed.serverUrl to
                                                FeedListenerState.Unreachable(
                                                    CheckOutcome.Unreachable
                                                )
                                        ),
                                ),
                            feedStatus =
                                FeedStatusState(feeds = mapOf(feed.id to FeedAvailability.Offline)),
                        ),
                    inboxSummary = null,
                    wallet = null,
                    pendingItems = listOf(signal(feed)),
                    requestAssessments = emptyMap(),
                )
                .servers
                .single()

        assertEquals(ServerRowState.Unreachable, row.rowState)
        assertEquals("${HomeCopy.Unreachable} · 1 pending", row.model.statusText)
    }

    /** Each feed's answer is its own: one publisher stopping says nothing about the next. */
    @Test
    fun oneOfflineFeedLeavesTheOthersAlone() {
        val first = feed()
        val second =
            feed().copy(id = "2b3c4d5e-6f70-4812-9394-a5b6c7d8e9f0", label = "Copy trading")
        val home =
            homeScreenState(
                connectionsState =
                    ConnectionsUiState(
                        connections = listOf(first, second),
                        loaded = true,
                        feeds =
                            ForegroundFeedsState(
                                foreground = true,
                                gateways = mapOf(first.serverUrl to FeedListenerState.Live(2)),
                            ),
                        feedStatus =
                            FeedStatusState(
                                foreground = true,
                                feeds =
                                    mapOf(
                                        first.id to FeedAvailability.Offline,
                                        second.id to FeedAvailability.Online,
                                    ),
                            ),
                    ),
                inboxSummary = null,
                wallet = null,
                pendingItems = emptyList(),
                requestAssessments = emptyMap(),
            )

        assertEquals("${HomeCopy.FeedOffline} · 0 pending", home.servers[0].model.statusText)
        assertEquals("Connected · 0 pending", home.servers[1].model.statusText)
    }

    /**
     * A direct connection has no publisher behind it, so nothing about presence reaches its row.
     */
    @Test
    fun aDirectConnectionIsUnaffected() {
        val direct = HOME
        val home =
            homeScreenState(
                connectionsState =
                    ConnectionsUiState(
                        connections = listOf(direct),
                        loaded = true,
                        updates =
                            ForegroundUpdatesState(
                                foreground = true,
                                connections = mapOf(direct.id to ForegroundConnectionState.Live),
                            ),
                        // As if a feed at the same origin had been answered about, which cannot be
                        // this connection's answer: presence is keyed by connection and this one is
                        // not a feed.
                        feedStatus =
                            FeedStatusState(feeds = mapOf(direct.id to FeedAvailability.Offline)),
                    ),
                inboxSummary = null,
                wallet = null,
                pendingItems = emptyList(),
                requestAssessments = emptyMap(),
            )

        assertEquals(ServerRowState.Connected, home.servers.single().rowState)
        // A direct connection's count is its own last sync's, which the fixture holds at two.
        assertEquals("Connected · 2 pending", home.servers.single().model.statusText)
        assertFalse(
            hasProblem(
                direct,
                live = ForegroundConnectionState.Live,
                availability = FeedAvailability.Offline,
            )
        )
    }

    /** The row renders the sentence it was given, so the owner actually reads it. */
    @Test
    fun theOfflineLineIsOnScreen() {
        val state = home(FeedAvailability.Offline)

        compose.setContent {
            SeekerTheme {
                HomeScreen(
                    state = state,
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

        compose.onNodeWithText("${HomeCopy.FeedOffline} · 1 pending").assertExists()
        compose.onNodeWithText("Connected · 1 pending").assertDoesNotExist()
    }

    /**
     * On the connection's own screen there is room for the whole sentence: what is still readable
     * as well as what has stopped. An offline publisher is the owner's business, so the card is
     * styled as a problem — the feed has quietly stopped moving, which is what nobody could see
     * before.
     */
    @Test
    fun theDetailScreenSaysWhatIsStillReadable() {
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
                    availability = FeedAvailability.Offline,
                )
            }
        }

        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_feed_offline))
        assertTrue(
            hasProblem(
                feed,
                live = ForegroundConnectionState.Revoked,
                support = ServerSupport.Supported,
                feed = FeedListenerState.Live(1),
                availability = FeedAvailability.Offline,
            )
        )
    }

    /**
     * A gateway with no broker is a working deployment, and it is where this mattered most: no
     * stream to look wrong, and a feed that had simply stopped moving. The feed's own state is then
     * the whole of what there is to say about it.
     */
    @Test
    fun aGatewayWithNoBrokerStillSaysTheFeedIsOffline() {
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
                    support = ServerSupport.Supported,
                    feed = FeedListenerState.NoStream,
                    availability = FeedAvailability.Offline,
                )
            }
        }

        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_feed_offline))
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
}
