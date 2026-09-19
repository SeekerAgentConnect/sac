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
import io.github.brrenat.seekervault.inbox.PendingItem
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
