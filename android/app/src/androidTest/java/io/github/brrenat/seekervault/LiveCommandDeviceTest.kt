package io.github.brrenat.seekervault

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.brrenat.seekervault.live.LiveCommandTags
import java.util.Base64
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Stage 1 round trip on a device or emulator, against the real sidecar and the real MCP path.
 * Run it with `pnpm test:hello --device`: that starts a sidecar with throwaway tokens, maps its
 * port with `adb reverse`, and sends the text with the test agent once this test has connected. The
 * test enters the token as the owner would, checks the exact text, and taps OK twice.
 */
@RunWith(AndroidJUnit4::class)
class LiveCommandDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun argument(name: String): String =
        checkNotNull(InstrumentationRegistry.getArguments().getString(name)) {
            "Missing instrumentation argument '$name': run this test with pnpm test:hello --device."
        }

    private fun textOf(tag: String): String? =
        compose
            .onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }

    /** Waits for [condition], and on timeout says what the screen showed instead. */
    private fun await(description: String, timeoutMillis: Long, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "No $description after $timeoutMillis ms. Connection status: " +
                    "\"${textOf(LiveCommandTags.CONNECTION_STATUS)}\", command status: " +
                    "\"${textOf(LiveCommandTags.COMMAND_STATUS)}\"",
                e,
            )
        }
    }

    @Test
    fun showsTheAgentsExactTextAndSendsOneOk() {
        val serverUrl = argument("serverUrl")
        val phoneToken = argument("phoneToken")
        val expected = String(Base64.getUrlDecoder().decode(argument("text")), Charsets.UTF_8)
        val connected = context.getString(R.string.status_connected)
        val acknowledged = context.getString(R.string.command_acknowledged)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag(LiveCommandTags.SERVER_URL).performTextReplacement(serverUrl)
            compose.onNodeWithTag(LiveCommandTags.PHONE_TOKEN).performTextInput(phoneToken)
            compose.onNodeWithTag(LiveCommandTags.CONNECT).performClick()
            await("connection", 30_000) {
                textOf(LiveCommandTags.CONNECTION_STATUS) == connected
            }

            // pnpm test:hello --device sends the text once the sidecar sees this connection.
            await("text from the agent", 120_000) {
                textOf(LiveCommandTags.COMMAND_TEXT) != null
            }
            compose.onNodeWithTag(LiveCommandTags.COMMAND_TEXT).assertTextEquals(expected)
            compose.onNodeWithTag(LiveCommandTags.OK).performClick()
            compose.onNodeWithTag(LiveCommandTags.OK).performClick() // ignored: one OK per command
            await("acknowledgement", 30_000) {
                textOf(LiveCommandTags.COMMAND_STATUS) == acknowledged
            }

            compose.onNodeWithTag(LiveCommandTags.DISCONNECT).performClick()
        }
    }
}
