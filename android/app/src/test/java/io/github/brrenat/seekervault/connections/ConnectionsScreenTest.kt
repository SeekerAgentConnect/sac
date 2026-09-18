package io.github.brrenat.seekervault.connections

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.RequestAssessment
import io.github.brrenat.seekervault.inbox.key
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckResult
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.assess
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import io.github.brrenat.seekervault.sync.ForegroundUpdatesState
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

    private fun show(
        state: ConnectionsUiState,
        activity: Int? = null,
        inbox: InboxSummary? = null,
        requests: List<io.github.brrenat.seekervault.request.v1.ActionRequest> = emptyList(),
        assessments: Map<RequestKey, RequestAssessment> = emptyMap(),
    ) = compose.setContent {
        SeekerTheme {
            ConnectionsScreen(
                state = state,
                onOpen = { opened += it },
                onAdd = { actions += "add" },
                onLiveTest = { actions += "live" },
                onMessageShown = {},
                activity = activity,
                onActivity = { actions += "activity" },
                onGlobalRules = { actions += "global-rules" },
                inbox = inbox,
                onInbox = { actions += "inbox" },
                requests = requests,
                requestAssessments = assessments,
            )
        }
    }

    @Test
    fun requestCarouselBrowsesWithoutAnsweringAndShowsItsRuleState() {
        val request = FakeConnectionGateway.request(HOME.id, text = "Still here?")
        show(
            state = ConnectionsUiState(connections = listOf(HOME), loaded = true),
            inbox = InboxSummary(waitingForYou = 1, toSend = 0),
            requests = listOf(request),
            assessments = mapOf(request.key to assessment(request, warns = true)),
        )

        compose.onNodeWithText(context.getString(R.string.waiting_for_you)).assertExists()
        compose.onNodeWithText("Still here?").assertExists()
        compose.onNodeWithText(context.getString(R.string.request_one_warning)).assertExists()
        val railBounds =
            compose.onNodeWithTag(ConnectionsTags.CAROUSEL).fetchSemanticsNode().boundsInRoot
        val onlyBounds =
            compose
                .onNodeWithTag(ConnectionsTags.request(request.key))
                .assertIsSelected()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(
            railBounds.left + with(compose.density) { 20.dp.toPx() },
            onlyBounds.left,
            1f,
        )
        compose
            .onNodeWithTag(ConnectionsTags.INBOX)
            .assertTextContains(context.getString(R.string.requests_see_all, 1))
            .performClick()
        assertEquals(listOf("inbox"), actions)
    }

    @Test
    fun requestCarouselDoesNotCallAnUnassessedOrNoPolicyRequestInRules() {
        val request = FakeConnectionGateway.request(HOME.id, text = "Still here?")
        show(
            state = ConnectionsUiState(connections = listOf(HOME), loaded = true),
            inbox = InboxSummary(waitingForYou = 1, toSend = 0),
            requests = listOf(request),
            assessments = mapOf(request.key to assessment(request, warns = false)),
        )

        compose.onNodeWithText(context.getString(R.string.request_not_checked)).assertExists()
        compose.onNodeWithText(context.getString(R.string.request_in_rules)).assertDoesNotExist()
    }

    @Test
    fun requestCarouselKeepsTheSameRequestIdFromTwoConnectionsApart() {
        val requestId = "e69eb47f-1ee5-42bd-8504-271f04f05ac3"
        show(
            state = ConnectionsUiState(connections = listOf(HOME, VPS), loaded = true),
            inbox = InboxSummary(waitingForYou = 2, toSend = 0),
            requests =
                listOf(
                    FakeConnectionGateway.request(HOME.id, requestId, "First request"),
                    FakeConnectionGateway.request(VPS.id, requestId, "Second request"),
                ),
        )

        compose.waitForIdle()
        compose.onNodeWithText("First request").assertExists()
    }

    @Test
    fun requestCarouselAlignsEndpointsToEdgesAndSnapsMiddleItemsToCenter() {
        val requests =
            (1..5).map {
                FakeConnectionGateway.request(HOME.id, "request-$it", "Request $it")
            }
        show(
            state = ConnectionsUiState(connections = listOf(HOME), loaded = true),
            inbox = InboxSummary(waitingForYou = requests.size, toSend = 0),
            requests = requests,
        )

        val rail = compose.onNodeWithTag(ConnectionsTags.CAROUSEL)
        val railBounds = rail.fetchSemanticsNode().boundsInRoot
        val first =
            compose.onNodeWithTag(ConnectionsTags.request(requests[0].key)).assertIsSelected()
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val edgeInset = with(compose.density) { 20.dp.toPx() }
        assertEquals(railBounds.left + edgeInset, firstBounds.left, 1f)

        rail.performTouchInput {
            val travel = 240.dp.toPx()
            swipe(
                start = center.copy(x = center.x + travel / 2),
                end = center.copy(x = center.x - travel / 2),
                durationMillis = 500,
            )
        }
        compose.waitForIdle()

        val middle =
            requests
                .subList(1, requests.lastIndex)
                .map { compose.onNodeWithTag(ConnectionsTags.request(it.key)) }
                .single { it.fetchSemanticsNode().config[SemanticsProperties.Selected] }
        assertEquals(
            railBounds.center.x,
            middle.fetchSemanticsNode().boundsInRoot.center.x,
            1f,
        )

        repeat(4) {
            rail.performTouchInput {
                val travel = 240.dp.toPx()
                swipe(
                    start = center.copy(x = center.x + travel / 2),
                    end = center.copy(x = center.x - travel / 2),
                    durationMillis = 500,
                )
            }
            compose.waitForIdle()
        }

        val focused =
            compose.onNodeWithTag(ConnectionsTags.request(requests.last().key)).assertIsSelected()
        val focusedBounds = focused.fetchSemanticsNode().boundsInRoot
        assertEquals(railBounds.right - edgeInset, focusedBounds.right, 1f)
    }

    @Test
    fun requestCarouselCanRightAlignTheLastOfTwoItems() {
        val requests =
            listOf(
                FakeConnectionGateway.request(HOME.id, "request-1", "First"),
                FakeConnectionGateway.request(HOME.id, "request-2", "Second"),
            )
        show(
            state = ConnectionsUiState(connections = listOf(HOME), loaded = true),
            inbox = InboxSummary(waitingForYou = requests.size, toSend = 0),
            requests = requests,
        )

        val rail = compose.onNodeWithTag(ConnectionsTags.CAROUSEL)
        val railBounds = rail.fetchSemanticsNode().boundsInRoot
        rail.performTouchInput {
            val travel = 240.dp.toPx()
            swipe(
                start = center.copy(x = center.x + travel / 2),
                end = center.copy(x = center.x - travel / 2),
                durationMillis = 500,
            )
        }
        compose.waitForIdle()

        val last =
            compose.onNodeWithTag(ConnectionsTags.request(requests.last().key)).assertIsSelected()
        assertEquals(
            railBounds.right - with(compose.density) { 20.dp.toPx() },
            last.fetchSemanticsNode().boundsInRoot.right,
            1f,
        )
    }

    @Test
    fun offersTheHistoryWhetherOrNotAnyConnectionIsLeft() {
        // The record of what this phone did is the owner's, and it doesn't depend on the agent
        // that asked (SAW-023).
        show(ConnectionsUiState(loaded = true), activity = 3)
        compose.onNodeWithTag(ConnectionsTags.LIST).performScrollToIndex(5)
        compose
            .onNodeWithTag(ConnectionsTags.ACTIVITY)
            .assertTextContains(context.getString(R.string.activity_row))
            .assertTextContains("3", substring = true)
        compose.onNodeWithTag(ConnectionsTags.ACTIVITY).performClick()
        assertEquals(listOf("activity"), actions)
    }

    @Test
    fun offersGlobalRulesFromTheConnectionsDashboard() {
        show(ConnectionsUiState(loaded = true))
        compose
            .onNodeWithTag(ConnectionsTags.GLOBAL_RULES)
            .assertTextContains(context.getString(R.string.global_rules_row))
            .assertTextContains(context.getString(R.string.global_rules_row_note))
            .performClick()
        assertEquals(listOf("global-rules"), actions)
    }

    @Test
    fun saysWhenNothingHasBeenRecordedYet() {
        show(ConnectionsUiState(loaded = true), activity = 0)
        compose.onNodeWithTag(ConnectionsTags.LIST).performScrollToIndex(5)
        compose
            .onNodeWithTag(ConnectionsTags.ACTIVITY)
            .assertTextContains(context.getString(R.string.activity_row_none), substring = true)
    }

    @Test
    fun listsEachConnectionWithItsAddressAndStatus() {
        show(ConnectionsUiState(connections = listOf(HOME, VPS, LAPTOP), loaded = true))
        compose.onNodeWithTag(ConnectionsTags.LIST).performScrollToIndex(4)
        compose
            .onNodeWithTag(ConnectionsTags.item(HOME.id))
            .assertTextContains("Home Mac")
            .assertTextContains(context.getString(R.string.connection_status_ok, 2))
        compose
            .onNodeWithTag(ConnectionsTags.item(VPS.id))
            .assertTextContains(context.getString(R.string.connection_status_revoked))
        compose.onNodeWithTag(ConnectionsTags.LIST).performScrollToIndex(6)
        compose
            .onNodeWithTag(ConnectionsTags.item(LAPTOP.id))
            .assertTextContains(context.getString(R.string.connection_status_certificate))
        compose.onNodeWithTag(ConnectionsTags.item(VPS.id)).performClick()
        assertEquals(listOf(VPS.id), opened)
        compose.onNodeWithTag(ConnectionsTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun liveStateReplacesStaleCheckTextWithoutChangingItsStoredTimestamp() {
        show(
            ConnectionsUiState(
                connections = listOf(HOME),
                loaded = true,
                updates =
                    ForegroundUpdatesState(
                        foreground = true,
                        connections = mapOf(HOME.id to ForegroundConnectionState.Live),
                    ),
            )
        )

        compose
            .onNodeWithTag(ConnectionsTags.item(HOME.id))
            .assertTextContains(context.getString(R.string.connection_status_live))
    }

    @Test
    fun saysHowToAddTheFirstConnection() {
        show(ConnectionsUiState(loaded = true))
        compose
            .onNodeWithTag(ConnectionsTags.EMPTY)
            .assertTextEquals(context.getString(R.string.connections_empty))
        compose.onNodeWithTag(ConnectionsTags.LIST).performScrollToIndex(5)
        compose.onNodeWithTag(ConnectionsTags.ADD).performClick()
        compose.onNodeWithTag(ConnectionsTags.LIVE_TEST).performClick()
        assertEquals(listOf("add", "live"), actions)
    }

    private fun assessment(
        request: io.github.brrenat.seekervault.request.v1.ActionRequest,
        warns: Boolean,
    ): RequestAssessment {
        val checks =
            PolicyCheck.entries.map {
                if (warns && it == PolicyCheck.Action) {
                    PolicyCheckResult.failed(it, PolicyReason.ActionNotAllowed)
                } else {
                    PolicyCheckResult.notConfigured(it)
                }
            }
        return RequestAssessment(
            decision = assess(checks),
            facts =
                RequestFacts.movesNothing(
                    request.ref.connectionId,
                    PolicyAction.Acknowledgement,
                    request.ref.requestId,
                ),
            at = Instant.EPOCH,
        )
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
