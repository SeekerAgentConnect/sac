package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.Inbox
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.policy.PolicyAssessment
import io.github.brrenat.seekervault.policy.PolicyDecision
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.solFacts
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.signMessageAction
import io.github.brrenat.seekervault.ui.SeekerVaultTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answering a request with a finger (SEE-57), and the line the gesture does not cross.
 *
 * A swipe is an answer given by hand, which is what this app asks for. What it may answer is not
 * symmetric, on purpose:
 *
 * - **Left rejects anything.** Refusing needs nothing read and reaches no wallet.
 * - **Right answers only an acknowledgement.** A transfer's transaction has not been built yet at
 *   this point, and a message's bytes are not on this screen, so for both the swipe opens the
 *   review — where the transaction is read and the message is shown — rather than approving
 *   something nobody has seen.
 */
@RunWith(AndroidJUnit4::class)
class SwipeRowTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<RequestKey>()
    private val answered = mutableListOf<Pair<RequestKey, Answer>>()

    private fun show(state: InboxUiState) = compose.setContent {
        SeekerVaultTheme {
            PendingRequestsScreen(
                state = state,
                connectionId = null,
                now = NOW,
                onOpen = { opened += it },
                onRefresh = {},
                onAnswer = { key, answer -> answered += key to answer },
            )
        }
    }

    private fun row(key: RequestKey) = compose.onNodeWithTag(InboxTags.item(key)).performScrollTo()

    @Test
    fun swipingAnAcknowledgementRightAnswersItOutright() {
        show(stateWith(ACK))
        row(ACK.key).performTouchInput { swipeRight() }
        assertEquals(listOf(ACK.key to Answer.Acknowledge), answered)
        assertEquals(emptyList<RequestKey>(), opened)
    }

    @Test
    fun swipingAnythingLeftRejectsIt() {
        show(stateWith(ACK, MESSAGE))
        row(ACK.key).performTouchInput { swipeLeft() }
        row(MESSAGE.key).performTouchInput { swipeLeft() }
        assertEquals(
            listOf(ACK.key to Answer.Reject, MESSAGE.key to Answer.Reject),
            answered,
        )
    }

    @Test
    fun swipingAMessageRightOpensTheReviewAndAnswersNothing() {
        // The wallet is never skipped, and neither is reading what would be signed: the gesture
        // takes the owner to the review rather than to the wallet.
        show(stateWith(MESSAGE))
        row(MESSAGE.key).performTouchInput { swipeRight() }
        assertEquals(emptyList<Pair<RequestKey, Answer>>(), answered)
        assertEquals(listOf(MESSAGE.key), opened)
    }

    @Test
    fun aDragThatStopsShortAnswersNothing() {
        show(stateWith(ACK))
        row(ACK.key).performTouchInput {
            down(centerLeft + Offset(4f, 0f))
            moveBy(Offset(12f, 0f))
            up()
        }
        assertEquals(emptyList<Pair<RequestKey, Answer>>(), answered)
        assertEquals(emptyList<RequestKey>(), opened)
    }

    @Test
    fun aRowClaimsNothingAboutRulesThatHaveNotBeenAppliedToIt() {
        // A transfer whose transaction has not been read is not "restricted": nothing has been
        // read about it, and a pill that claimed otherwise would be contradicted by the review.
        show(stateWith(ACK))
        compose.onNodeWithTag(InboxTags.RULE_PILL).assertDoesNotExist()
    }

    @Test
    fun aRowSaysWhatTheRulesMadeOfTheRequestOnceTheyHaveBeenApplied() {
        show(stateWith(ACK).copy(assessments = mapOf(ACK.key to restricted())))
        compose
            .onNodeWithTag(InboxTags.RULE_PILL)
            .performScrollTo()
            .assertTextContains(context.getString(R.string.rule_pill_restricted))
    }

    private fun stateWith(vararg pending: io.github.brrenat.seekervault.request.v1.ActionRequest) =
        InboxUiState(
            connections = listOf(HOME),
            inbox = Inbox(pending = mapOf(HOME.id to pending.toList())),
        )

    private fun restricted() =
        RequestAssessment(
            decision =
                PolicyDecision(
                    assessment = PolicyAssessment.UnderRestrictions,
                    checks = emptyList(),
                    reason = PolicyReason.NoPolicyConfigured,
                ),
            facts = solFacts(connectionId = HOME.id),
            at = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-13T12:00:00Z")
        val HOME =
            Connection(
                id = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c",
                label = "Home Mac",
                serverUrl = "https://mac.tailnet.ts.net",
                serverId = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a",
                deviceName = "Seeker",
                pairedAt = NOW.minusSeconds(86_400),
                lastCheck = Connection.Check(NOW, CheckOutcome.Ok, pending = 2),
            )
        val ACK =
            FakeConnectionGateway.request(
                HOME.id,
                "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3",
                "Deploy finished",
                createdAt = NOW.minusSeconds(300),
            )
        val MESSAGE = actionRequest {
            ref = requestRef {
                connectionId = HOME.id
                requestId = "de03846e-d435-4705-b2e3-ec67da539f12"
            }
            action = action {
                signMessage = signMessageAction {
                    wallet = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
                    text = "Sign in to Example"
                }
            }
            state = RequestState.REQUEST_STATE_PENDING
            createdAt = timestamp { seconds = NOW.minusSeconds(120).epochSecond }
            expiresAt = timestamp { seconds = NOW.plusSeconds(86_400).epochSecond }
        }
    }
}
