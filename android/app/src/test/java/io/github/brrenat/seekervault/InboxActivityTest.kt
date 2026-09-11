package io.github.brrenat.seekervault

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.inbox.InboxTags
import io.github.brrenat.seekervault.request.v1.RequestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The inbox in the real activity, with the app's own storage and a fake sidecar: a request made
 * while the app was closed is fetched on opening, answered, and stays answered.
 */
@RunWith(AndroidJUnit4::class)
class InboxActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val other = gateway.serve(OTHER_URL)
    private val key = softwareKey()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useFakes() {
        app.connectionGateway = { gateway }
        app.credentialKey = { key }
        app.connectionIo = Dispatchers.Unconfined
    }

    @After fun close() = scenario?.close() ?: Unit

    private fun launch() = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    @Test
    fun aRequestMadeWhileTheAppWasClosedIsAnsweredAndStaysAnswered() {
        // Paired earlier; the agent asked while the app was closed.
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        val request = server.addPending(connection.id, text = "Deploy finished")
        val key = RequestKey(connection.id, request.ref.requestId)
        val scenario = launch()

        // Opening the app fetched the request, and answered nothing.
        compose
            .onNodeWithTag(ConnectionsTags.INBOX)
            .assertTextContains(app.getString(R.string.inbox_row_waiting, 1))
            .performClick()
        assertTrue(gateway.submits.isEmpty())
        compose
            .onNodeWithTag(InboxTags.item(key))
            .assertTextContains("Deploy finished")
            .performClick()
        compose
            .onNodeWithTag(InboxTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Deploy finished")
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).performScrollTo().performClick()
        compose
            .onNodeWithTag(InboxTags.STATUS)
            .assertTextEquals(app.getString(R.string.status_acknowledged))
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            server.stateOf(connection.id, request.ref.requestId),
        )

        // Back in the list it's answered; reopened, it shows the outcome, not the buttons.
        compose.onNodeWithTag(ConnectionsTags.BACK).performClick()
        compose.onNodeWithTag(InboxTags.SECTION_ANSWERED).assertExists()
        compose.onNodeWithTag(InboxTags.item(key)).performClick()
        scenario.recreate()
        compose
            .onNodeWithTag(InboxTags.STATUS)
            .assertTextEquals(app.getString(R.string.status_acknowledged))
        compose.onNodeWithTag(InboxTags.REJECT).assertDoesNotExist()
        assertEquals(1, gateway.submits.size)
    }

    @Test
    fun aConnectionsDetailsOpenItsOwnRequestsOnly() {
        val home = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        val vps = runBlocking { app.connectionRepository.pair(other.issue(OTHER_URL)) }
        val mine = server.addPending(home.id, text = "For home")
        val theirs = other.addPending(vps.id, text = "For the VPS")
        launch()
        compose.onNodeWithTag(ConnectionsTags.item(home.id)).performClick()
        compose.onNodeWithTag(ConnectionsTags.PENDING).performScrollTo().performClick()
        compose
            .onNodeWithTag(InboxTags.item(RequestKey(home.id, mine.ref.requestId)))
            .assertExists()
        compose
            .onNodeWithTag(InboxTags.item(RequestKey(vps.id, theirs.ref.requestId)))
            .assertDoesNotExist()
    }

    private companion object {
        const val URL = "https://mac.tailnet.ts.net"
        const val OTHER_URL = "https://vps.example.com"
    }
}
