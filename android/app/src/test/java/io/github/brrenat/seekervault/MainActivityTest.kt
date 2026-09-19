package io.github.brrenat.seekervault

import android.content.Intent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.LegacyUpdateTransport
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.live.FakeSidecar
import io.github.brrenat.seekervault.live.LiveCommandTags
import io.github.brrenat.seekervault.live.command
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The live-test screen's lifecycle on Robolectric, against a fake sidecar. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val sidecar = FakeSidecar()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        app.liveCommandTransports = sidecar
        app.connectionGateway = { FakeConnectionGateway() }
        app.updateTransport = { LegacyUpdateTransport() }
        app.credentialKey = softwareKey().let { key -> { key } }
        scenario =
            ActivityScenario.launch(
                Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_LIVE_TEST, true)
            )
    }

    @After fun close() = scenario.close()

    private fun connectAndReceive() {
        compose.onNodeWithTag(LiveCommandTags.PHONE_TOKEN).performTextInput("p".repeat(64))
        compose.onNodeWithTag(LiveCommandTags.CONNECT).performClick()
        compose.runOnIdle {
            sidecar.stream.ready()
            sidecar.stream.send(command(expiresAt = Instant.now().plusSeconds(600)))
        }
        compose.onNodeWithTag(LiveCommandTags.COMMAND_TEXT).assertTextEquals("Hello Seeker")
    }

    private fun assertCommandStatus(id: Int) =
        compose.onNodeWithTag(LiveCommandTags.COMMAND_STATUS).assertTextEquals(app.getString(id))

    @Test
    fun rotationKeepsTheStreamTheCommandAndASingleOk() {
        connectAndReceive()
        assertTrue(app.foregroundUpdates.state.value.foreground)
        scenario.recreate()
        assertTrue(app.foregroundUpdates.state.value.foreground)
        compose.onNodeWithTag(LiveCommandTags.COMMAND_TEXT).assertTextEquals("Hello Seeker")
        assertEquals(1, sidecar.streams.size)
        assertTrue(sidecar.stream.open)
        compose.onNodeWithTag(LiveCommandTags.OK).performScrollTo().performClick()
        scenario.recreate()
        assertCommandStatus(R.string.command_acknowledged)
        compose.onNodeWithTag(LiveCommandTags.OK).assertIsNotEnabled()
        assertEquals(listOf("c1"), sidecar.acknowledgements)
        assertEquals(1, sidecar.streams.size)
    }

    @Test
    fun rapidDoubleTapSendsOneOk() {
        connectAndReceive()
        val answer = CompletableDeferred<Unit>()
        sidecar.onAcknowledge = { answer.await() }
        compose.onNodeWithTag(LiveCommandTags.OK).performScrollTo().performClick()
        compose.onNodeWithTag(LiveCommandTags.OK).performScrollTo().performClick()
        assertCommandStatus(R.string.command_sending)
        compose.runOnIdle { answer.complete(Unit) }
        assertCommandStatus(R.string.command_acknowledged)
        assertEquals(listOf("c1"), sidecar.acknowledgements)
    }

    @Test
    fun backgroundClosesTheStreamAndForegroundOpensANewOne() {
        connectAndReceive()
        scenario.moveToState(Lifecycle.State.CREATED) // onStop without a configuration change
        compose.waitForIdle()
        assertFalse(app.foregroundUpdates.state.value.foreground)
        assertFalse(sidecar.streams[0].open)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertTrue(app.foregroundUpdates.state.value.foreground)
        assertEquals(2, sidecar.streams.size)
        compose.onNodeWithTag(LiveCommandTags.COMMAND_TEXT).assertDoesNotExist()
        compose.runOnIdle { sidecar.stream.ready() }
        compose
            .onNodeWithTag(LiveCommandTags.CONNECTION_STATUS)
            .assertTextEquals(app.getString(R.string.status_connected))
    }

    @Test
    fun leavingTheLiveTestClosesItsStream() {
        connectAndReceive()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(sidecar.stream.open)
        compose.onNodeWithTag(ConnectionsTags.ADD).performScrollTo().assertExists()
        compose.onNodeWithTag(LiveCommandTags.COMMAND_TEXT).assertDoesNotExist()
    }
}
