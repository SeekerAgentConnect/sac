package io.github.brrenat.seekervault.connections

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
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

    private fun show(state: ConnectionsUiState, activity: Int? = null) = compose.setContent {
        SeekerVaultTheme {
            ConnectionsScreen(
                state = state,
                onOpen = { opened += it },
                onAdd = { actions += "add" },
                onLiveTest = { actions += "live" },
                onMessageShown = {},
                activity = activity,
                onActivity = { actions += "activity" },
            )
        }
    }

    @Test
    fun offersTheHistoryWhetherOrNotAnyConnectionIsLeft() {
        // The record of what this phone did is the owner's, and it doesn't depend on the agent
        // that asked (SAW-023).
        show(ConnectionsUiState(loaded = true), activity = 3)
        compose
            .onNodeWithTag(ConnectionsTags.ACTIVITY)
            .assertTextContains(context.getString(R.string.activity_row))
            .assertTextContains("3", substring = true)
        compose.onNodeWithTag(ConnectionsTags.ACTIVITY).performClick()
        assertEquals(listOf("activity"), actions)
    }

    @Test
    fun saysWhenNothingHasBeenRecordedYet() {
        show(ConnectionsUiState(loaded = true), activity = 0)
        compose
            .onNodeWithTag(ConnectionsTags.ACTIVITY)
            .assertTextContains(context.getString(R.string.activity_row_none), substring = true)
    }

    @Test
    fun listsEachConnectionWithItsAddressAndStatus() {
        show(ConnectionsUiState(connections = listOf(HOME, VPS, LAPTOP), loaded = true))
        compose
            .onNodeWithTag(ConnectionsTags.item(HOME.id))
            .assertTextContains("Home Mac")
            .assertTextContains("mac.tailnet.ts.net")
            .assertTextContains(context.getString(R.string.connection_status_ok, 2))
        compose
            .onNodeWithTag(ConnectionsTags.item(VPS.id))
            .assertTextContains("vps.example.com:8443")
            .assertTextContains(context.getString(R.string.connection_status_revoked))
        compose
            .onNodeWithTag(ConnectionsTags.item(LAPTOP.id))
            .assertTextContains(context.getString(R.string.connection_status_certificate))
        compose.onNodeWithTag(ConnectionsTags.item(VPS.id)).performClick()
        assertEquals(listOf(VPS.id), opened)
        compose.onNodeWithTag(ConnectionsTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun saysHowToAddTheFirstConnection() {
        show(ConnectionsUiState(loaded = true))
        compose
            .onNodeWithTag(ConnectionsTags.EMPTY)
            .assertTextEquals(context.getString(R.string.connections_empty))
        compose.onNodeWithTag(ConnectionsTags.ADD).performClick()
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
