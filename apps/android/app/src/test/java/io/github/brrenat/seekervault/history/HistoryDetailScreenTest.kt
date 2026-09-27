package io.github.brrenat.seekervault.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.CONNECTION
import io.github.brrenat.seekervault.activity.ackRequest
import io.github.brrenat.seekervault.activity.connection
import io.github.brrenat.seekervault.activity.result
import io.github.brrenat.seekervault.activity.signatureBytes
import io.github.brrenat.seekervault.activity.transferRequest
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.Inbox
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.designsystem.HistoryDetailCallbacks
import io.github.brrenat.seekervault.designsystem.HistoryDetailModel
import io.github.brrenat.seekervault.designsystem.HistoryDetailScreen
import io.github.brrenat.seekervault.designsystem.HistoryDetailTags
import io.github.brrenat.seekervault.designsystem.InboxTab
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.InboxRoute
import io.github.brrenat.seekervault.inbox.InboxRouteCallbacks
import io.github.brrenat.seekervault.inbox.InboxScreenTags
import io.github.brrenat.seekervault.inbox.InboxUiState
import io.github.brrenat.seekervault.inbox.InboxViewState
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private val clock = HistoryDetailClock(ZoneOffset.UTC, Locale.US)
    private val owner = connection("Personal MCP")

    @Test
    fun aDeclineOmitsEveryOptionalSectionAndOffersNoDecision() {
        show(
            privateHistoryDetail(
                result(transferRequest(), answer = Answer.Reject, approved = false),
                owner,
                clock,
            )
        )

        compose.onNodeWithTag(HistoryDetailTags.Status).assertExists()
        compose.onNodeWithTag(HistoryDetailTags.Response).assertExists()
        compose.onNodeWithTag(HistoryDetailTags.Original).assertExists()
        compose.onNodeWithTag(HistoryDetailTags.Delivery).assertDoesNotExist()
        compose.onNodeWithTag(HistoryDetailTags.Execution).assertDoesNotExist()
        compose.onNodeWithTag(HistoryDetailTags.Transactions).assertDoesNotExist()
        compose.onNodeWithTag(HistoryDetailTags.Footnote).assertExists()
        // Read-only: nothing on the page can answer, edit or simulate.
        listOf("Approve", "Decline", "Reject", "Edit", "Simulate").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Inbox").assertDoesNotExist()
    }

    @Test
    fun sendAgainCopyAndExplorerAreTheOnlyActions() {
        val signature = signatureBytes()
        val events = mutableListOf<String>()
        show(
            privateHistoryDetail(
                result(
                    transferRequest(
                        network = Network.NETWORK_MAINNET,
                        state = RequestState.REQUEST_STATE_SUBMITTED,
                        signature = signature,
                    ),
                    delivery = Delivery.Waiting,
                    signing = SigningOutcome.Sent(signature),
                ),
                owner,
                clock,
            ),
            HistoryDetailCallbacks(
                onBack = { events += "back" },
                onSendAgain = { events += "send" },
                onCopy = { events += "copy/$it" },
                onOpenExplorer = { events += "open/$it" },
            ),
        )
        val base58 = encodeBase58(signature.toByteArray())

        compose.onNodeWithTag(HistoryDetailTags.SendAgain).performScrollTo().performClick()
        compose.onNodeWithTag(HistoryDetailTags.copy(0)).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Copied").assertExists()
        compose.onNodeWithTag(HistoryDetailTags.explorer(0)).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(
            listOf(
                "send",
                "copy/$base58",
                "open/https://explorer.solana.com/tx/$base58",
                "back",
            ),
            events,
        )
    }

    @Test
    fun aPendingTransferUpdatesInPlaceWhenTheSidecarLearnsTheOutcome() {
        val signature = signatureBytes()
        val pending =
            result(
                transferRequest(
                    network = Network.NETWORK_MAINNET,
                    state = RequestState.REQUEST_STATE_SUBMITTED,
                    signature = signature,
                ),
                signing = SigningOutcome.Sent(signature),
            )
        var stored by mutableStateOf(pending)
        val checks = mutableListOf<Int>()
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                HistoryDetailRoute(
                    identity =
                        io.github.brrenat.seekervault.ReviewIdentity.Private(
                            stored.connectionId,
                            stored.requestId,
                        ),
                    connections = listOf(owner),
                    inboxState = InboxUiState(inbox = Inbox(results = listOf(stored))),
                    feedRecords = emptyList(),
                    feedStanding = { error("no signals here") },
                    signalChoice = { emptyList() },
                    onSendAgain = {},
                    onCheckStatus = { checks += 1 },
                    onBack = {},
                    clock = clock,
                    // One check on opening; the next would come after a wait the test never ends.
                    pollMillis = Long.MAX_VALUE,
                )
            }
        }

        compose.onNodeWithText("Waiting for network confirmation").assertExists()
        compose.onNodeWithText("Pending").assertExists()
        // While it waits, the page asks the sidecar what it has learned.
        compose.waitForIdle()
        assertEquals(1, checks.size)

        stored =
            pending.copy(
                request =
                    pending.request
                        .toBuilder()
                        .setState(RequestState.REQUEST_STATE_CONFIRMED)
                        .build()
            )
        compose.onNodeWithText("Confirmed on Mainnet").assertExists()
        compose.onNodeWithText("Pending").assertDoesNotExist()
        compose.onNodeWithText("Waiting for network confirmation").assertDoesNotExist()
    }

    @Test
    fun aRecordNoLongerOnThePhoneSaysSoRatherThanShowingAnything() {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                HistoryDetailRoute(
                    identity =
                        io.github.brrenat.seekervault.ReviewIdentity.Private(CONNECTION, "gone"),
                    connections = listOf(owner),
                    inboxState = InboxUiState(),
                    feedRecords = emptyList(),
                    feedStanding = { error("no signals here") },
                    signalChoice = { emptyList() },
                    onSendAgain = {},
                    onCheckStatus = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag(HistoryDetailRouteTags.Gone).assertExists()
        compose.onNodeWithTag(HistoryDetailTags.Page).assertDoesNotExist()
    }

    @Test
    fun leavingTheInboxAndComingBackKeepsTheTabTheFilterAndTheScrollOffset() {
        val answers: List<LocalResult> =
            (0 until 20).map { index ->
                result(ackRequest(requestId = "request-$index"), answer = Answer.Acknowledge)
                    .copy(
                        answeredAt = Instant.parse("2026-09-11T12:00:00Z").plusSeconds(index * 60L)
                    )
            }
        val view = InboxViewState(tab = InboxTab.History, sourceFilter = CONNECTION)
        var inboxShown by mutableStateOf(true)
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                val kept = remember { view }
                if (inboxShown) {
                    InboxRoute(
                        state =
                            InboxUiState(
                                connections = listOf(owner),
                                inbox = Inbox(results = answers),
                            ),
                        feedRecords = emptyList(),
                        feedStanding = { error("no signals here") },
                        now = Instant.parse("2026-09-11T13:00:00Z"),
                        callbacks =
                            InboxRouteCallbacks(
                                onRefresh = {},
                                onOpenRequest = {},
                                onOpenSignal = {},
                                navigation = ScreenNavigationCallbacks({}, {}, {}, {}),
                                onOpenHistory = { inboxShown = false },
                            ),
                        view = kept,
                    )
                }
            }
        }
        compose.onNodeWithTag(InboxScreenTags.Filter).assertExists()
        compose.runOnIdle { runBlocking { view.historyScroll.scrollTo(600) } }
        compose.waitForIdle()
        val offset = view.historyScroll.value

        // Away to a record's details and back.
        compose.runOnIdle { inboxShown = false }
        compose.waitForIdle()
        compose.runOnIdle { inboxShown = true }
        compose.waitForIdle()

        assertEquals(InboxTab.History, view.tab)
        assertEquals(CONNECTION, view.sourceFilter)
        assertEquals(offset, view.historyScroll.value)
        compose.onNodeWithTag(InboxScreenTags.Filter).assertExists()
    }

    private fun show(
        model: HistoryDetailModel,
        callbacks: HistoryDetailCallbacks = HistoryDetailCallbacks(onBack = {}),
    ) = compose.setContent {
        SeekerTheme(darkTheme = true) { HistoryDetailScreen(model = model, callbacks = callbacks) }
    }
}
