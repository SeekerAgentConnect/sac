package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.Inbox
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.request.v1.RequestState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pending requests on Robolectric: sections, source, age, expiry, and the empty and offline states.
 */
@RunWith(AndroidJUnit4::class)
class PendingRequestsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<RequestKey>()
    private var refreshes = 0

    private fun show(state: InboxUiState, connectionId: String? = null) = compose.setContent {
        SeekerVaultTheme {
            PendingRequestsScreen(
                state = state,
                connectionId = connectionId,
                now = NOW,
                onOpen = { opened += it },
                onRefresh = { refreshes++ },
                onBack = {},
            )
        }
    }

    @Test
    fun showsEachRequestWithItsSourceActionAndContext() {
        show(STATE)
        compose.onNodeWithTag(InboxTags.SECTION_PENDING).assertExists()
        compose.onNodeWithTag(InboxTags.item(PENDING.key)).assertExists()
        compose.onAllNodesWithText("Home Mac", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Deploy finished", substring = true).assertExists()
        compose
            .onAllNodesWithText(context.getString(R.string.action_ack), substring = true)
            .assertCountEquals(1)
        compose
            .onNodeWithTag(InboxTags.LIST)
            .performScrollToNode(hasTestTag(InboxTags.item(PENDING.key)))
        compose
            .onNode(hasText(context.getString(R.string.acknowledge)) and hasClickAction())
            .performClick()
        // The prominent action opens the mandatory review; the list itself never answers.
        assertEquals(listOf(PENDING.key), opened)
    }

    @Test
    fun separatesAnswersWaitingToBeSentFromSettledOnes() {
        show(STATE)
        compose.onNodeWithTag(InboxTags.SECTION_TO_SEND).assertExists()
        val acknowledged = context.getString(R.string.answer_acknowledged)
        compose
            .onNodeWithTag(InboxTags.item(WAITING.key))
            .assertTextContains(context.getString(R.string.summary_waiting, acknowledged))
        // Below the fold: a lazy list composes it only once it's scrolled into view.
        compose
            .onNodeWithTag(InboxTags.LIST)
            .performScrollToNode(hasTestTag(InboxTags.SECTION_TO_SEND))
        compose.onNodeWithText(context.getString(R.string.inbox_tab_answered, 1)).performClick()
        compose
            .onNodeWithTag(InboxTags.LIST)
            .performScrollToNode(hasTestTag(InboxTags.item(ANSWERED.key)))
        compose.onNodeWithTag(InboxTags.SECTION_ANSWERED).assertExists()
        compose.onNodeWithTag(InboxTags.item(ANSWERED.key)).assertTextContains(acknowledged)
    }

    @Test
    fun saysWhichServerCouldNotBeReached() {
        show(STATE)
        compose
            .onNodeWithTag(InboxTags.PROBLEM)
            .assertTextEquals(
                context.getString(
                    R.string.inbox_problem,
                    "VPS",
                    context.getString(R.string.connection_status_unreachable),
                )
            )
    }

    @Test
    fun showsOneConnectionsRequestsOnly() {
        show(STATE, connectionId = VPS.id)
        compose.onNodeWithText(context.getString(R.string.inbox_from, "VPS")).assertExists()
        compose.onNodeWithTag(InboxTags.item(PENDING.key)).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.EMPTY).assertExists()
    }

    @Test
    fun saysWhenThereIsNothingOrNoServer() {
        show(InboxUiState(connections = listOf(HOME)))
        compose
            .onNodeWithTag(InboxTags.EMPTY)
            .assertTextEquals(context.getString(R.string.inbox_empty))
        compose.onNodeWithTag(InboxTags.NO_CONNECTIONS).assertDoesNotExist()
    }

    @Test
    fun asksToPairWhenThereIsNoServer() {
        show(InboxUiState())
        compose
            .onNodeWithTag(InboxTags.NO_CONNECTIONS)
            .assertTextEquals(context.getString(R.string.inbox_no_connections))
    }

    @Test
    fun refreshesOnRequestButNotTwiceAtOnce() {
        show(STATE)
        compose.onNodeWithTag(InboxTags.REFRESH).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun disablesRefreshWhileOneRuns() {
        show(STATE.copy(refreshing = true))
        compose.onNodeWithTag(InboxTags.REFRESH).assertIsNotEnabled()
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-09-11T12:00:00Z")
        val HOME =
            Connection(
                id = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c",
                label = "Home Mac",
                serverUrl = "https://mac.tailnet.ts.net",
                serverId = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a",
                deviceName = "Seeker",
                pairedAt = NOW.minusSeconds(86_400),
                lastCheck = Connection.Check(NOW, CheckOutcome.Ok, pending = 1),
            )
        val VPS =
            HOME.copy(
                id = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f",
                label = "VPS",
                serverUrl = "https://vps.example.com",
                serverId = "1f0e2d3c-4b5a-4698-8776-5a4b3c2d1e0f",
                lastCheck = Connection.Check(NOW, CheckOutcome.Unreachable),
            )
        val PENDING =
            FakeConnectionGateway.request(
                HOME.id,
                "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3",
                "Deploy finished",
                createdAt = NOW.minusSeconds(300),
            )
        val WAITING =
            LocalResult(
                HOME.id,
                "de03846e-d435-4705-b2e3-ec67da539f12",
                Answer.Acknowledge,
                NOW.minusSeconds(60),
                FakeConnectionGateway.request(
                    HOME.id,
                    "de03846e-d435-4705-b2e3-ec67da539f12",
                    "Backup done",
                ),
                Delivery.Waiting,
                CheckOutcome.Unreachable,
            )
        val ANSWERED =
            WAITING.copy(
                requestId = "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d",
                request =
                    FakeConnectionGateway.request(
                            HOME.id,
                            "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d",
                            "Release shipped",
                        )
                        .toBuilder()
                        .setState(RequestState.REQUEST_STATE_COMPLETED)
                        .build(),
                delivery = Delivery.Accepted,
                lastFailure = null,
            )
        val STATE =
            InboxUiState(
                connections = listOf(HOME, VPS),
                inbox =
                    Inbox(
                        pending = mapOf(HOME.id to listOf(PENDING)),
                        results = listOf(WAITING, ANSWERED),
                    ),
            )
    }
}
