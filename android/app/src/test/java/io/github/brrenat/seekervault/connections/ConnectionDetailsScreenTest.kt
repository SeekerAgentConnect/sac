package io.github.brrenat.seekervault.connections

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
import io.github.brrenat.seekervault.connections.ConnectionsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.connections.ConnectionsScreenTest.Companion.VPS
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.policy.PolicyTags
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Connection details screen and its dialogs on Robolectric. */
@RunWith(AndroidJUnit4::class)
class ConnectionDetailsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val calls = mutableListOf<String>()

    private fun show(
        connection: Connection,
        disconnect: DisconnectState? = null,
        refreshing: Boolean = false,
        live: ForegroundConnectionState? = null,
        support: ServerSupport? = null,
    ) = compose.setContent {
        SeekerVaultTheme {
            ConnectionDetailsScreen(
                connection = connection,
                refreshing = refreshing,
                disconnect = disconnect,
                message = null,
                onBack = { calls += "back" },
                onRefresh = { calls += "refresh" },
                onRename = { label ->
                    labelProblem(label).also { if (it == null) calls += "rename $label" }
                },
                onDisconnect = { calls += "disconnect" },
                onConfirmDisconnect = { calls += "confirm disconnect" },
                onConfirmRemove = { calls += "confirm remove" },
                onDismissDisconnect = { calls += "dismiss" },
                onMessageShown = {},
                onRules = { calls += "rules" },
                live = live,
                support = support,
            )
        }
    }

    @Test
    fun aSharedFeedSaysWhatItIsRatherThanThatItsCredentialIsMissing() {
        // A feed holds no credential and never did (SEE-88). Reading its absence as a fault would
        // put a warning on every feed the owner has.
        val manifest =
            ServerManifest(
                serverId = HOME.serverId,
                protocolVersion = SERVER_PROTOCOL,
                settingsRevision = 1,
                mode = ConnectionMode.GatewayFeed,
                reference =
                    ServerReference.Feed("https://gateway.example.com", channelFor(HOME.serverId)),
                environments = setOf(PluginEnvironment.Production),
            )
        val feed =
            HOME.copy(
                hasCredential = false,
                lastCheck = null,
                mode = ConnectionMode.GatewayFeed,
                server = ServerRecord.Known(manifest),
            )

        show(feed, support = ServerSupport.Supported)

        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_feed))
    }

    @Test
    fun aServerThisBuildDoesNotSupportSaysWhichPartIsMissing() {
        show(HOME, support = ServerSupport.PluginMissing(listOf(PluginId("jupiter.prediction"))))

        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_unsupported_plugin))
    }

    @Test
    fun aServerSpeakingANewerProtocolSaysTheAppIsTheOneToUpdate() {
        show(HOME, support = ServerSupport.ProtocolUnsupported(9))

        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_unsupported_protocol))
    }

    @Test
    fun showsWhatThePhoneKnowsAboutTheConnection() {
        show(HOME)
        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_ok, 2))
        compose.onNodeWithTag(ConnectionsTags.field("server")).assertTextContains(HOME.serverUrl)
        compose.onNodeWithTag(ConnectionsTags.field("serverId")).assertTextContains(HOME.serverId)
        compose.onNodeWithTag(ConnectionsTags.field("connectionId")).assertTextContains(HOME.id)
        compose.onNodeWithTag(ConnectionsTags.field("deviceName")).assertTextContains("Seeker")
        compose
            .onNodeWithTag(ConnectionsTags.REFRESH)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag(ConnectionsTags.DISCONNECT).performScrollTo().performClick()
        compose.onNodeWithTag(ConnectionsTags.REMOVE).assertDoesNotExist()
        compose.onNodeWithTag(ConnectionsTags.CLOSE).performClick()
        assertEquals(listOf("refresh", "disconnect", "back"), calls)
    }

    @Test
    fun distinguishesLiveReconnectingAndBackgroundFromTheLastSync() {
        show(HOME, live = ForegroundConnectionState.Reconnecting(2))
        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_reconnecting))
        compose
            .onNodeWithText(context.getString(R.string.checked_at, "").trim(), substring = true)
            .assertExists()
    }

    @Test
    fun offersOnlyRemovalOnceTheServerStoppedAcceptingThePhone() {
        show(VPS)
        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_revoked))
        compose.onNodeWithTag(ConnectionsTags.REFRESH).assertIsNotEnabled()
        compose.onNodeWithTag(ConnectionsTags.DISCONNECT).assertDoesNotExist()
        compose.onNodeWithTag(ConnectionsTags.REMOVE).performScrollTo().performClick()
        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun offersTheConnectionsRulesWhateverStateItIsIn() {
        // A connection the server stopped accepting is exactly when the owner may want to read
        // what they had asked of it, so the rules are reachable when nothing else is.
        show(VPS)
        compose.onNodeWithTag(PolicyTags.RULES).performScrollTo().performClick()
        assertEquals(listOf("rules"), calls)
    }

    @Test
    fun disablesRefreshWhileOneIsRunning() {
        show(HOME, refreshing = true)
        compose.onNodeWithTag(ConnectionsTags.REFRESH).assertIsNotEnabled()
    }

    @Test
    fun renamesAndSaysWhyANameIsRefused() {
        show(HOME)
        compose.onNodeWithTag(ConnectionsTags.RENAME).performScrollTo().performClick()
        compose.onNodeWithTag(ConnectionsTags.LABEL_FIELD).performTextReplacement("   ")
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        compose.onNodeWithText(context.getString(R.string.rename_blank)).assertExists()
        compose.onNodeWithTag(ConnectionsTags.LABEL_FIELD).performTextReplacement("x".repeat(65))
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        compose.onNodeWithText(context.getString(R.string.rename_too_long)).assertExists()
        compose.onNodeWithTag(ConnectionsTags.LABEL_FIELD).performTextReplacement("Studio")
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        compose.onNodeWithTag(ConnectionsTags.LABEL_FIELD).assertDoesNotExist()
        assertEquals(listOf("rename Studio"), calls)
    }

    @Test
    fun asksBeforeDisconnecting() {
        show(HOME, disconnect = DisconnectState.Confirm(HOME.id))
        compose
            .onNodeWithText(context.getString(R.string.disconnect_title, "Home Mac"))
            .assertExists()
        compose.onAllNodesWithText(context.getString(R.string.disconnect_text)).assertCountEquals(2)
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        compose.onNodeWithTag(ConnectionsTags.DIALOG_DISMISS).performClick()
        assertEquals(listOf("confirm disconnect", "dismiss"), calls)
    }

    @Test
    fun offersToRemoveTheConnectionWhenItsServerCantBeTold() {
        show(HOME, disconnect = DisconnectState.NotReached(HOME.id, CheckOutcome.Unreachable))
        val reason = context.getString(R.string.connection_status_unreachable)
        compose.onNodeWithText(context.getString(R.string.not_reached_text, reason)).assertExists()
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        assertEquals(listOf("confirm remove"), calls)
    }

    @Test
    fun confirmsARemovalAndShowsProgress() {
        show(VPS, disconnect = DisconnectState.ConfirmRemove(VPS.id))
        compose.onNodeWithText(context.getString(R.string.remove_title, "VPS")).assertExists()
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        assertEquals(listOf("confirm remove"), calls)
    }

    @Test
    fun offersNoButtonsWhileWorking() {
        show(HOME, disconnect = DisconnectState.Working(HOME.id))
        compose.onNodeWithText(context.getString(R.string.working)).assertExists()
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).assertDoesNotExist()
    }
}
