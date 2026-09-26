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
import io.github.brrenat.seekervault.connections.ConnectionsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.connections.ConnectionsScreenTest.Companion.VPS
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.policy.PolicyTags
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.PluginRequirement
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
        feed: FeedListenerState? = null,
        onEnvironment: ((PluginEnvironment) -> Unit)? = null,
    ) = compose.setContent {
        SeekerTheme {
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
                feed = feed,
                onEnvironment = onEnvironment,
            )
        }
    }

    @Test
    fun aSharedFeedShowsItsLiveStateWithoutTheProminentSharedFeedNotice() {
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
                required = listOf(PluginRequirement(PluginId("jupiter.prediction"), 1..1)),
                environments = setOf(PluginEnvironment.Production),
            )
        val feed =
            HOME.copy(
                hasCredential = false,
                lastCheck = null,
                mode = ConnectionMode.GatewayFeed,
                server = ServerRecord.Known(manifest),
            )

        show(
            feed,
            live = ForegroundConnectionState.Revoked,
            support = ServerSupport.Supported,
            feed = FeedListenerState.Live(1),
        )

        compose
            .onNodeWithTag(ConnectionsTags.STATUS)
            .assertTextEquals(context.getString(R.string.connection_status_live))
        compose
            .onNodeWithText(
                "A shared feed. This phone reads it through the gateway and holds no credential for it."
            )
            .assertDoesNotExist()
        compose
            .onNodeWithTag(ConnectionsTags.field("plugins"))
            .assertTextContains("jupiter.prediction")
        assertEquals(
            false,
            hasProblem(
                feed,
                live = ForegroundConnectionState.Revoked,
                support = ServerSupport.Supported,
                feed = FeedListenerState.Live(1),
            ),
        )
        compose
            .onNodeWithTag(ConnectionsTags.REFRESH)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun aFeedConnectingIsNotADirectRevocation() {
        assertFeedHeadline(
            FeedListenerState.Connecting,
            R.string.connection_status_connecting,
            false,
        )
    }

    @Test
    fun aFeedReconnectingIsNotADirectRevocation() {
        assertFeedHeadline(
            FeedListenerState.Reconnecting(2),
            R.string.connection_status_reconnecting,
            false,
        )
    }

    @Test
    fun aFeedWithNoStreamIsNotADirectRevocation() {
        assertFeedHeadline(
            FeedListenerState.NoStream,
            R.string.connection_status_feed_available,
            false,
        )
    }

    @Test
    fun aFeedUnreachableIsAProblemWithoutInheritingDirectRevokedCopy() {
        assertFeedHeadline(
            FeedListenerState.Unreachable(CheckOutcome.Unreachable),
            R.string.connection_status_unreachable,
            true,
        )
    }

    private fun assertFeedHeadline(listener: FeedListenerState, status: Int, problem: Boolean) {
        val connection = feed(setOf(PluginEnvironment.Production))
        show(
            connection,
            live = ForegroundConnectionState.Revoked,
            support = ServerSupport.Supported,
            feed = listener,
        )
        compose.onNodeWithTag(ConnectionsTags.STATUS).assertTextEquals(context.getString(status))
        assertEquals(
            problem,
            hasProblem(
                connection,
                live = ForegroundConnectionState.Revoked,
                support = ServerSupport.Supported,
                feed = listener,
            ),
        )
    }

    /** A feed of HOME's, whose publisher serves [served] (SEE-97). */
    private fun feed(served: Set<PluginEnvironment>) =
        HOME.copy(
            hasCredential = false,
            lastCheck = null,
            mode = ConnectionMode.GatewayFeed,
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
                        environments = served,
                    )
                ),
            environment =
                if (PluginEnvironment.Sandbox in served) PluginEnvironment.Sandbox
                else PluginEnvironment.Production,
        )

    @Test
    fun aFeedSaysWhichPromiseItKeepsAndOffersTheSwitchOnlyWhereThereIsAChoice() {
        // SEE-97. "This feed spends money" and "this feed is a demonstration" are the two things
        // an owner most needs to know about a feed, so the word is on the screen either way, and
        // the switch appears only where its publisher serves both.
        show(feed(setOf(PluginEnvironment.Sandbox)), support = ServerSupport.Supported)

        compose.onNodeWithTag(ConnectionsTags.ENVIRONMENT).assertExists()
        compose
            .onNodeWithText(context.getString(R.string.connection_environment_sandbox))
            .assertExists()
        compose
            .onNodeWithTag(ConnectionsTags.environment(PluginEnvironment.Production.code))
            .assertDoesNotExist()
    }

    @Test
    fun aFeedThatServesBothLetsTheOwnerSwitchAndNobodyElse() {
        val both = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox)
        val chosen = mutableListOf<PluginEnvironment>()
        show(
            feed(both).copy(environment = PluginEnvironment.Sandbox),
            support = ServerSupport.Supported,
            onEnvironment = { chosen += it },
        )

        compose
            .onNodeWithTag(ConnectionsTags.environment(PluginEnvironment.Production.code))
            .performScrollTo()
            .performClick()

        assertEquals(listOf(PluginEnvironment.Production), chosen)
        // The one it already keeps is not a button that does anything.
        compose
            .onNodeWithTag(ConnectionsTags.environment(PluginEnvironment.Sandbox.code))
            .assertIsNotEnabled()
    }

    @Test
    fun aDirectConnectionSaysNothingAboutEnvironmentsAtAll() {
        // It is always production, and a row that could only ever say one thing is noise on the
        // screen the private workflow uses (SEE-97).
        show(HOME)

        compose.onNodeWithTag(ConnectionsTags.ENVIRONMENT).assertDoesNotExist()
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
