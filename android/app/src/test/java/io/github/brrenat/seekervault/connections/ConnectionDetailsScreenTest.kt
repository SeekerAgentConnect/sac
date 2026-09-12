package io.github.brrenat.seekervault.connections

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import io.github.brrenat.seekervault.policy.PolicyTags
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
            )
        }
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
        compose.onNodeWithTag(ConnectionsTags.BACK).performClick()
        assertEquals(listOf("refresh", "disconnect", "back"), calls)
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
        compose.onNodeWithText(context.getString(R.string.disconnect_text)).assertExists()
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
