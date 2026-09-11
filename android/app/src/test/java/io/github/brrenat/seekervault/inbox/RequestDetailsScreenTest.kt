package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.NOW
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Request details on Robolectric: the answer buttons, progress, and the stored outcome. */
@RunWith(AndroidJUnit4::class)
class RequestDetailsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val answers = mutableListOf<Answer>()
    private var sentAgain = 0

    private fun show(
        request: ActionRequest = REQUEST,
        result: LocalResult? = null,
        sending: Boolean = false,
    ) = compose.setContent {
        SeekerVaultTheme {
            RequestDetailsScreen(
                request = request,
                source = HOME,
                result = result,
                sending = sending,
                now = NOW,
                onAnswer = { answers += it },
                onSendAgain = { sentAgain++ },
                onBack = {},
            )
        }
    }

    private fun status(id: Int, vararg args: Any) =
        compose.onNodeWithTag(InboxTags.STATUS).assertTextEquals(context.getString(id, *args))

    @Test
    fun showsThePendingRequestAndOffersAcknowledgeAndReject() {
        show()
        status(R.string.status_waiting_for_you)
        compose
            .onNodeWithTag(InboxTags.field("from"))
            .assertTextContains("Home Mac (mac.tailnet.ts.net)")
        // The text's own node: its list item merges it with the field's label.
        compose.onNodeWithTag(InboxTags.MESSAGE, useUnmergedTree = true).assertTextEquals(TEXT)
        compose.onNodeWithTag(InboxTags.NOTE).assertTextContains("Nightly release")
        compose
            .onNodeWithTag(InboxTags.field("requestId"))
            .assertTextContains(REQUEST.ref.requestId)
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).performScrollTo().performClick()
        compose.onNodeWithTag(InboxTags.REJECT).performScrollTo().performClick()
        assertEquals(listOf(Answer.Acknowledge, Answer.Reject), answers)
    }

    @Test
    fun disablesTheButtonsWhileTheAnswerIsSent() {
        show(sending = true)
        status(R.string.status_sending)
        compose.onNodeWithTag(InboxTags.SENDING).assertExists()
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(InboxTags.REJECT).assertIsNotEnabled()
    }

    @Test
    fun showsTheStoredOutcomeInsteadOfTheButtons() {
        val done = REQUEST.toBuilder().setState(RequestState.REQUEST_STATE_COMPLETED).build()
        show(done, result(done, Delivery.Accepted))
        status(R.string.status_acknowledged)
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.REJECT).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.SEND_AGAIN).assertDoesNotExist()
    }

    @Test
    fun offersToSendAWaitingAnswerAgain() {
        show(
            result = result(REQUEST, Delivery.Waiting).copy(lastFailure = CheckOutcome.Unreachable)
        )
        status(R.string.status_to_send_acknowledged)
        compose
            .onNodeWithText(
                context.getString(
                    R.string.status_last_failure,
                    context.getString(R.string.connection_status_unreachable),
                )
            )
            .assertExists()
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.SEND_AGAIN).performScrollTo().performClick()
        assertEquals(1, sentAgain)
    }

    @Test
    fun saysWhenTheAgentCancelledFirst() {
        val cancelled = REQUEST.toBuilder().setState(RequestState.REQUEST_STATE_CANCELLED).build()
        show(cancelled, result(cancelled, Delivery.Superseded))
        status(R.string.status_superseded_cancelled)
    }

    @Test
    fun offersNothingForAnExpiredRequest() {
        show(FakeConnectionGateway.request(HOME.id, text = TEXT, expiresAt = NOW.minusSeconds(1)))
        status(R.string.status_expired)
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
    }

    @Test
    fun saysWhenAnAnswerCantBeSent() {
        show(result = result(REQUEST, Delivery.Undeliverable))
        status(R.string.status_undeliverable)
        compose.onNodeWithTag(InboxTags.SEND_AGAIN).assertDoesNotExist()
    }

    private fun result(request: ActionRequest, delivery: Delivery) =
        LocalResult(
            HOME.id,
            request.ref.requestId,
            Answer.Acknowledge,
            NOW,
            request,
            delivery,
        )

    private companion object {
        // Looks like markup and a link, and must be shown exactly as written.
        const val TEXT = "Deploy <b>finished</b> — see https://example.com [ok]"
        val REQUEST: ActionRequest =
            FakeConnectionGateway.request(HOME.id, text = TEXT, createdAt = NOW.minusSeconds(120))
                .toBuilder()
                .setAgentNote("Nightly release")
                .build()
    }
}
