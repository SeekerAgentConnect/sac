package io.github.brrenat.seekervault.live

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.ui.SeekerVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The stateless screen on Robolectric. */
@RunWith(AndroidJUnit4::class)
class LiveCommandScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var state by mutableStateOf(LiveCommandUiState())
    private var okTaps = 0

    private fun show(initial: LiveCommandUiState, onOk: () -> Unit = { okTaps++ }) {
        state = initial
        compose.setContent {
            SeekerVaultTheme {
                LiveCommandScreen(
                    state = state,
                    onServerUrlChange = {},
                    onPhoneTokenChange = {},
                    onConnect = {},
                    onDisconnect = {},
                    onOk = onOk,
                )
            }
        }
    }

    private fun connectedWith(
        text: String = "Hello Seeker",
        status: CommandStatus = CommandStatus.AwaitingOk,
    ) =
        LiveCommandUiState(
            phoneToken = "token",
            connection = ConnectionState.Connected,
            command = ReceivedCommand("c1", text, status),
        )

    private fun node(tag: String) = compose.onNodeWithTag(tag)

    /** The screen scrolls, so a control is brought into view before it is touched. */
    private fun reach(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Test
    fun showsTheReceivedTextExactlyAsPlainText() {
        val text = "Привет 👋🏽 你好 مرحبا é\nSecond line <b>not bold</b> https://example.com"
        show(connectedWith(text))
        node(LiveCommandTags.COMMAND_TEXT).assertTextEquals(text)
        node(LiveCommandTags.COMMAND_STATUS).assertTextEquals(string(R.string.command_awaiting))
    }

    @Test
    fun oneTapSendsOneOkAndDisablesTheButton() {
        show(connectedWith()) {
            okTaps++
            state = connectedWith(status = CommandStatus.Sending)
        }
        reach(LiveCommandTags.OK).performClick()
        reach(LiveCommandTags.OK).performClick()
        reach(LiveCommandTags.OK).assertIsNotEnabled()
        node(LiveCommandTags.COMMAND_STATUS).assertTextEquals(string(R.string.command_sending))
        assertEquals(1, okTaps)
    }

    @Test
    fun enablesOkOnlyWhileWaitingForTheUser() {
        show(connectedWith())
        node(LiveCommandTags.OK).assertIsEnabled()
        val messages =
            mapOf(
                CommandStatus.Sending to R.string.command_sending,
                CommandStatus.Acknowledged to R.string.command_acknowledged,
                CommandStatus.TimedOut to R.string.command_timed_out,
                CommandStatus.Failed(AcknowledgeFailure.Cancelled) to R.string.command_cancelled,
                CommandStatus.Failed(AcknowledgeFailure.UnknownCommand) to R.string.command_unknown,
                CommandStatus.Failed(AcknowledgeFailure.Unauthenticated) to
                    R.string.command_unauthenticated,
                CommandStatus.Failed(AcknowledgeFailure.Unreachable) to
                    R.string.command_unreachable,
                CommandStatus.Failed(AcknowledgeFailure.Other) to R.string.command_failed,
            )
        for ((status, message) in messages) {
            state = connectedWith(status = status)
            reach(LiveCommandTags.OK).assertIsNotEnabled()
            node(LiveCommandTags.COMMAND_STATUS).assertTextEquals(string(message))
        }
    }

    @Test
    fun keepsOkReachableBelowTheLongestText() {
        val text = "€".repeat(1365) + "!" // 4096 UTF-8 bytes, the protocol's maximum
        show(connectedWith(text))
        node(LiveCommandTags.COMMAND_TEXT).assertTextEquals(text)
        node(LiveCommandTags.OK).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, okTaps)
    }

    @Test
    fun locksTheFormWhileConnected() {
        show(connectedWith())
        reach(LiveCommandTags.SERVER_URL).assertIsNotEnabled()
        reach(LiveCommandTags.PHONE_TOKEN).assertIsNotEnabled()
        reach(LiveCommandTags.CONNECT).assertIsNotEnabled()
        node(LiveCommandTags.DISCONNECT).assertIsEnabled()
        node(LiveCommandTags.CONNECTION_STATUS).assertTextEquals(string(R.string.status_connected))
        state = LiveCommandUiState()
        node(LiveCommandTags.SERVER_URL).assertIsEnabled()
        node(LiveCommandTags.PHONE_TOKEN).assertIsEnabled()
        node(LiveCommandTags.CONNECT).assertIsEnabled()
        reach(LiveCommandTags.DISCONNECT).assertIsNotEnabled()
        node(LiveCommandTags.COMMAND_TEXT).assertDoesNotExist()
    }

    @Test
    fun explainsErrorsAndTheForegroundOnlyLimitation() {
        show(
            LiveCommandUiState(
                connection = ConnectionState.Disconnected(DisconnectReason.Lost("connection reset"))
            )
        )
        node(LiveCommandTags.CONNECTION_STATUS)
            .assertTextEquals(string(R.string.status_lost_detail, "connection reset"))
        node(LiveCommandTags.LIMITATION).assertIsDisplayed()
        val messages =
            mapOf(
                DisconnectReason.Unauthenticated to R.string.status_unauthenticated,
                DisconnectReason.Replaced to R.string.status_replaced,
                DisconnectReason.CleartextBlocked to R.string.status_cleartext_blocked,
                DisconnectReason.InvalidUrl to R.string.status_invalid_url,
                DisconnectReason.MissingToken to R.string.status_missing_token,
                DisconnectReason.Background to R.string.status_background,
                DisconnectReason.Lost(null) to R.string.status_lost,
            )
        for ((reason, message) in messages) {
            state = LiveCommandUiState(connection = ConnectionState.Disconnected(reason))
            node(LiveCommandTags.CONNECTION_STATUS).assertTextEquals(string(message))
        }
        state =
            LiveCommandUiState(
                connection = ConnectionState.Disconnected(DisconnectReason.Unauthenticated)
            )
        node(LiveCommandTags.LIMITATION).assertDoesNotExist()
        // Never connected, so nothing was cleared: the hint names the URL and adb reverse.
        state =
            LiveCommandUiState(
                connection =
                    ConnectionState.Disconnected(
                        DisconnectReason.Unreachable("http://127.0.0.1:8080", 8080)
                    )
            )
        node(LiveCommandTags.CONNECTION_STATUS)
            .assertTextEquals(string(R.string.status_unreachable, "http://127.0.0.1:8080", 8080))
        node(LiveCommandTags.LIMITATION).assertDoesNotExist()
    }
}
