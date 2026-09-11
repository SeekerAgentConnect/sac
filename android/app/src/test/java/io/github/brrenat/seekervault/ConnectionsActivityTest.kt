package io.github.brrenat.seekervault

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.PairingCode
import io.github.brrenat.seekervault.connections.softwareKey
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The connection screens in the real activity, with the app's own storage and a fake sidecar:
 * pairing, rotation, renaming, disconnecting, and where the secrets end up.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val key = softwareKey()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useFakes() {
        app.connectionGateway = { gateway }
        app.credentialKey = { key }
    }

    @After fun close() = scenario?.close() ?: Unit

    private fun launch() = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun text(code: PairingCode) =
        "seekervault://pair?v=1&url=${URLEncoder.encode(code.serverUrl, Charsets.UTF_8)}" +
            "&server=${code.serverId}&token=${code.token}"

    @Test
    fun pairsRenamesAndKeepsTheSecretsOffScreenAndOutOfBackups() {
        val scenario = launch()
        val code = server.issue(URL)
        compose.onNodeWithTag(ConnectionsTags.EMPTY).assertExists()
        compose.onNodeWithTag(ConnectionsTags.ADD).performClick()
        compose.onNodeWithTag(ConnectionsTags.CODE_FIELD).performTextInput(text(code))
        compose.onNodeWithTag(ConnectionsTags.CONTINUE).performClick()
        compose.onNodeWithTag(ConnectionsTags.PAIR).performClick()

        // The new connection's details replace the Add screen, and survive a rotation.
        val id = server.connections.keys.single()
        val credential = server.connections.getValue(id)
        compose.onNodeWithTag(ConnectionsTags.field("connectionId")).assertTextContains(id)
        compose.onNodeWithText(app.getString(R.string.message_paired, HOST)).assertExists()
        // Let the snackbar go, so it doesn't cover the buttons below.
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText(app.getString(R.string.message_paired, HOST)).assertDoesNotExist()
        scenario.recreate()
        compose.onNodeWithTag(ConnectionsTags.field("connectionId")).assertTextContains(id)

        compose.onNodeWithTag(ConnectionsTags.RENAME).performScrollTo().performClick()
        compose.onNodeWithTag(ConnectionsTags.LABEL_FIELD).performTextReplacement("Home Mac")
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        compose.onNodeWithTag(ConnectionsTags.BACK).performClick()
        compose.onNodeWithTag(ConnectionsTags.item(id)).assertTextContains("Home Mac")

        for (secret in listOf(code.token, credential)) {
            compose
                .onAllNodes(hasText(secret, substring = true), useUnmergedTree = true)
                .assertCountEquals(0)
            // Nowhere in the app's data in plain text: not in the metadata, not in the vault.
            for (file in app.dataDir.walk().filter { it.isFile }) {
                assertFalse(file.path, secret in String(file.readBytes(), Charsets.ISO_8859_1))
            }
        }
        // The credential file is in noBackupFilesDir; the metadata is apart from it.
        assertTrue(File(app.noBackupFilesDir, "credentials/$id").isFile)
        assertTrue(File(app.filesDir, "connections/$id.json").isFile)
    }

    @Test
    fun disconnectingReturnsToTheListWithoutTheConnection() {
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        launch()
        compose.onNodeWithTag(ConnectionsTags.item(connection.id)).performClick()
        compose.onNodeWithTag(ConnectionsTags.DISCONNECT).performScrollTo().performClick()
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        compose.onNodeWithTag(ConnectionsTags.EMPTY).assertExists()
        compose.onNodeWithText(app.getString(R.string.message_disconnected, HOST)).assertExists()
        assertEquals(setOf(connection.id), server.revoked)
        assertFalse(File(app.noBackupFilesDir, "credentials/${connection.id}").exists())
    }

    private companion object {
        const val URL = "https://mac.tailnet.ts.net"
        const val HOST = "mac.tailnet.ts.net"
    }
}
