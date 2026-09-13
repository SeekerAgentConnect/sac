package io.github.brrenat.seekervault.connections

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.inbox.InboxTags
import io.github.brrenat.seekervault.inbox.InboxUiState
import io.github.brrenat.seekervault.inbox.key
import io.github.brrenat.seekervault.ui.ChromeTags
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.SeekerVaultTheme
import io.github.brrenat.seekervault.ui.Tab
import io.github.brrenat.seekervault.ui.TabBar
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Connections list on Robolectric. */
@RunWith(AndroidJUnit4::class)
class ConnectionsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<String>()
    private val actions = mutableListOf<String>()
    private val openedRequests = mutableListOf<RequestKey>()

    private fun show(
        state: ConnectionsUiState,
        inbox: InboxUiState = InboxUiState(),
        wallet: SelectedWallet? = null,
    ) = compose.setContent {
        SeekerVaultTheme {
            ConnectionsScreen(
                state = state,
                inboxState = inbox,
                onOpen = { opened += it },
                onOpenRequest = { openedRequests += it },
                onAdd = { actions += "add" },
                onLiveTest = { actions += "live" },
                onMessageShown = {},
                wallet = wallet,
                tabs =
                    TabBar(
                        tabs =
                            listOf(
                                Tab("Home", Glyph.Home, ChromeTags.HOME),
                                Tab("Requests", Glyph.Requests, ChromeTags.REQUESTS),
                                Tab("Wallet", Glyph.Wallet, ChromeTags.WALLET),
                                Tab("Activity", Glyph.Activity, ChromeTags.ACTIVITY),
                            ),
                        selected = 0,
                        onSelect = { actions += "tab:$it" },
                    ),
            )
        }
    }

    @Test
    fun offersTheHistoryWhetherOrNotAnyConnectionIsLeft() {
        // The record of what this phone did is the owner's, and it doesn't depend on the agent
        // that asked (SAW-023). Since SEE-57 it is a tab of its own rather than a row in a list,
        // so it is reachable from every root without a connection existing at all.
        show(ConnectionsUiState(loaded = true))
        compose.onNodeWithTag(ChromeTags.ACTIVITY).performClick()
        assertEquals(listOf("tab:3"), actions)
    }

    @Test
    fun theCarouselShowsWhatIsWaitingAndOpensItWithoutAnsweringIt() {
        // Home browses. Tapping a tile opens the review; nothing on the carousel answers anything,
        // which is why it has no controls of its own (SEE-57).
        val request = FakeConnectionGateway.request(HOME.id, WAITING_ID, "Deploy finished")
        show(
            ConnectionsUiState(connections = listOf(HOME), loaded = true),
            InboxUiState(
                connections = listOf(HOME),
                inbox = Inbox(pending = mapOf(HOME.id to listOf(request))),
            ),
            wallet = CONNECTED,
        )
        compose.onNodeWithTag(InboxTags.CAROUSEL).assertExists()
        compose
            .onNodeWithTag(InboxTags.tile(request.key))
            .performScrollTo()
            .assertTextContains("Deploy finished", substring = true)
            .assertTextContains(context.getString(R.string.stake_acknowledge), substring = true)
            .performClick()
        assertEquals(listOf(request.key), openedRequests)
        compose.onNodeWithTag(InboxTags.SEE_ALL).assertExists()
    }

    @Test
    fun saysSoWhenNothingIsWaiting() {
        show(ConnectionsUiState(connections = listOf(HOME), loaded = true))
        compose.onNodeWithText(context.getString(R.string.home_nothing_waiting)).assertExists()
        compose.onNodeWithTag(InboxTags.CAROUSEL).assertDoesNotExist()
    }

    @Test
    fun asksForAWalletUntilThereIsOneAndThenStopsAsking() {
        show(ConnectionsUiState(connections = listOf(HOME), loaded = true))
        compose.onNodeWithTag(ConnectionsTags.WALLET).performScrollTo().assertExists()
    }

    @Test
    fun aConnectedWalletTakesTheCardAwayAndNamesItsNetworkInTheHeader() {
        show(ConnectionsUiState(connections = listOf(HOME), loaded = true), wallet = CONNECTED)
        compose.onNodeWithTag(ConnectionsTags.WALLET).assertDoesNotExist()
        compose
            .onNodeWithTag(ChromeTags.TAG)
            .assertTextContains(
                context.getString(R.string.wallet_network_devnet),
                substring = true,
            )
    }

    @Test
    fun marksTheRequestsTabWhenSomethingIsWaiting() {
        show(ConnectionsUiState(connections = listOf(HOME), loaded = true))
        compose.onNodeWithTag(ChromeTags.PENDING_DOT).assertDoesNotExist()
    }

    @Test
    fun listsEachConnectionWithItsAddressAndStatus() {
        show(ConnectionsUiState(connections = listOf(HOME, VPS, LAPTOP), loaded = true))
        compose
            .onNodeWithTag(ConnectionsTags.item(HOME.id))
            .assertTextContains("Home Mac")
            .assertTextContains(context.getString(R.string.connection_status_ok, 2))
        compose
            .onNodeWithTag(ConnectionsTags.item(VPS.id))
            .assertTextContains("VPS")
            .assertTextContains(context.getString(R.string.connection_status_revoked))
        compose
            .onNodeWithTag(ConnectionsTags.item(LAPTOP.id))
            .assertTextContains(context.getString(R.string.connection_status_certificate))
        compose.onNodeWithTag(ConnectionsTags.item(VPS.id)).performScrollTo().performClick()
        assertEquals(listOf(VPS.id), opened)
        compose.onNodeWithTag(ConnectionsTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun saysHowToAddTheFirstConnection() {
        show(ConnectionsUiState(loaded = true))
        compose
            .onNodeWithTag(ConnectionsTags.EMPTY)
            .assertTextEquals(context.getString(R.string.connections_empty))
        compose.onNodeWithTag(ConnectionsTags.ADD).performScrollTo().performClick()
        compose.onNodeWithTag(ConnectionsTags.LIVE_TEST).performClick()
        assertEquals(listOf("add", "live"), actions)
    }

    @Test
    fun showsNoEmptyStateBeforeTheConnectionsAreRead() {
        show(ConnectionsUiState(loaded = false))
        compose.onNodeWithTag(ConnectionsTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun showsAMessage() {
        show(
            ConnectionsUiState(
                connections = listOf(HOME),
                loaded = true,
                message = ConnectionMessage.Disconnected("VPS"),
            )
        )
        compose
            .onNodeWithText(context.getString(R.string.message_disconnected, "VPS"))
            .assertExists()
    }

    companion object {
        private const val WAITING_ID = "c1f1b8a6-4e2d-4f31-9a0b-16f6a1d4a2b8"
        private val CONNECTED =
            SelectedWallet(
                address = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW",
                network = WalletNetwork.Devnet,
                selectedAt = Instant.parse("2026-09-12T10:00:00Z"),
            )
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
        val LAPTOP =
            HOME.copy(
                id = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3",
                label = "Laptop",
                serverUrl = "https://laptop.example.com",
                lastCheck = Connection.Check(PAIRED, CheckOutcome.CertificateRejected),
            )
    }
}
