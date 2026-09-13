package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.NOW
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckResult
import io.github.brrenat.seekervault.policy.PolicyDecision
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.assess
import io.github.brrenat.seekervault.policy.noPolicy
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.ui.SeekerVaultTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the owner's own rules look like on the screen where they answer a request (SAW-028).
 *
 * Every one of these is about words. The verdict, each check, what could not be checked, and what
 * nothing covered are all said in language, so that a reader who sees no colour, or who hears the
 * screen rather than seeing it, is told exactly what a sighted reader is told.
 */
@RunWith(AndroidJUnit4::class)
class PolicyReviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val answers = mutableListOf<Answer>()
    private val ticks = mutableListOf<Boolean>()

    private fun show(
        decision: PolicyDecision?,
        request: ActionRequest = ACK,
        acknowledged: Boolean = false,
    ) = compose.setContent {
        SeekerVaultTheme {
            RequestDetailsScreen(
                request = request,
                source = HOME,
                result = null,
                sending = false,
                now = NOW,
                onAnswer = { answers += it },
                onApprove = {},
                onSendAgain = {},
                onBack = {},
                assessment = decision?.let { assessment(it) },
                acknowledged = acknowledged,
                onAcknowledge = { ticks += it },
            )
        }
    }

    private fun assessment(decision: PolicyDecision) =
        RequestAssessment(
            decision,
            RequestFacts.movesNothing(HOME.id, PolicyAction.Acknowledgement),
            Instant.parse("2026-09-11T12:00:00Z"),
        )

    private fun check(check: PolicyCheck) = compose.onNodeWithTag(InboxTags.policyCheck(check))

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun saysItIsReadingTheRulesBeforeItHasReadThem() {
        // A gap here could be read as "nothing to say about your rules", which is a different
        // thing entirely from "not read yet".
        show(null)
        compose
            .onNodeWithTag(InboxTags.POLICY_PENDING)
            .performScrollTo()
            .assertTextEquals(text(R.string.policy_review_pending))
    }

    @Test
    fun namesEveryCheckWhatItReadAndWhatNothingCovered() {
        show(
            assess(
                checks(PolicyCheck.Action to PolicyCheckResult.passed(PolicyCheck.Action, "ack"))
            )
        )
        compose
            .onNodeWithTag(InboxTags.POLICY_VERDICT)
            .performScrollTo()
            .assertTextEquals(text(R.string.policy_verdict_allowed))
        check(PolicyCheck.Action)
            .performScrollTo()
            .assertTextContains(text(R.string.policy_review_check_action), substring = true)
        check(PolicyCheck.Action)
            .assertTextContains(text(R.string.policy_status_passed, "ack"), substring = true)
        // Every check is on screen, including the five nobody wrote a rule for.
        PolicyCheck.entries.forEach { check(it).performScrollTo().assertExists() }
        check(PolicyCheck.Recipient)
            .assertTextContains(text(R.string.policy_status_not_configured), substring = true)
        // And what ALLOWED does not say anything about is named, not left to be inferred.
        compose
            .onNodeWithTag(InboxTags.POLICY_UNCOVERED)
            .performScrollTo()
            .assertTextContains(text(R.string.policy_review_check_recipient), substring = true)
        // A match asks for no word from the owner: there is nothing to overrule.
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).performScrollTo().assertIsEnabled()
    }

    @Test
    fun theLineThatNeverChangesIsUnderEveryVerdict() {
        val verdicts =
            listOf(
                assess(checks(PolicyCheck.Action to PolicyCheckResult.passed(PolicyCheck.Action))),
                noPolicy(PolicyReason.NoPolicyConfigured),
                noPolicy(PolicyReason.PolicyUnreadable),
                overThreshold(),
            )
        val showing = mutableStateOf(verdicts.first())
        compose.setContent {
            SeekerVaultTheme {
                RequestDetailsScreen(
                    request = ACK,
                    source = HOME,
                    result = null,
                    sending = false,
                    now = NOW,
                    onAnswer = {},
                    onApprove = {},
                    onSendAgain = {},
                    onBack = {},
                    assessment = assessment(showing.value),
                )
            }
        }
        for (verdict in verdicts) {
            showing.value = verdict
            compose
                .onNodeWithTag(InboxTags.POLICY_MANUAL)
                .performScrollTo()
                .assertTextEquals(text(R.string.policy_review_manual))
        }
    }

    @Test
    fun aThresholdWarningSaysWhatItReadAndWhatItWasComparedWith() {
        show(overThreshold())
        compose
            .onNodeWithTag(InboxTags.POLICY_VERDICT)
            .performScrollTo()
            .assertTextEquals(text(R.string.policy_verdict_restricted))
        check(PolicyCheck.PerOperationLimit)
            .performScrollTo()
            .assertTextContains(
                text(R.string.policy_status_failed, "2.5 SOL of 1 SOL"),
                substring = true,
            )
    }

    @Test
    fun aRecipientWarningNamesTheAddressItRead() {
        show(
            assess(
                checks(
                    PolicyCheck.Recipient to
                        PolicyCheckResult.failed(
                            PolicyCheck.Recipient,
                            PolicyReason.RecipientNotAllowed,
                            RECIPIENT,
                        )
                )
            )
        )
        check(PolicyCheck.Recipient)
            .performScrollTo()
            .assertTextContains(
                text(R.string.policy_status_failed, RECIPIENT),
                substring = true,
            )
    }

    @Test
    fun contentThatCouldNotBeVerifiedSaysWhatCouldNotBeCheckedAndWhy() {
        val decision =
            PolicyDecision(
                assessment =
                    assess(
                            checks(
                                PolicyCheck.Recipient to
                                    PolicyCheckResult.unverified(
                                        PolicyCheck.Recipient,
                                        PolicyReason.RecipientUnverified,
                                        "the bytes don't establish who receives it",
                                    )
                            )
                        )
                        .assessment,
                checks =
                    checks(
                        PolicyCheck.Recipient to
                            PolicyCheckResult.unverified(
                                PolicyCheck.Recipient,
                                PolicyReason.RecipientUnverified,
                                "the bytes don't establish who receives it",
                            )
                    ),
                reason = PolicyReason.RequestUnverified,
            )
        show(decision)
        // Could not be checked, said as that and never as a match.
        check(PolicyCheck.Recipient)
            .performScrollTo()
            .assertTextContains(
                text(
                    R.string.policy_status_unverified,
                    "the bytes don't establish who receives it",
                ),
                substring = true,
            )
        compose
            .onNodeWithTag(InboxTags.POLICY_REASON)
            .performScrollTo()
            .assertTextEquals(text(R.string.policy_reason_request_unverified))
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).performScrollTo().assertExists()
    }

    @Test
    fun havingNoRulesAtAllAsksForNoWordFromTheOwner() {
        show(noPolicy(PolicyReason.NoPolicyConfigured))
        compose
            .onNodeWithTag(InboxTags.POLICY_REASON)
            .performScrollTo()
            .assertTextEquals(text(R.string.policy_reason_no_policy))
        // Otherwise every request on a phone with no rules would ask for one.
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).assertDoesNotExist()
        compose
            .onNodeWithTag(InboxTags.ACKNOWLEDGE)
            .performScrollTo()
            .assertTextEquals(text(R.string.acknowledge))
    }

    @Test
    fun rulesThatCannotBeReadDoAskForOne() {
        show(noPolicy(PolicyReason.PolicyUnreadable))
        compose
            .onNodeWithTag(InboxTags.POLICY_REASON)
            .performScrollTo()
            .assertTextEquals(text(R.string.policy_reason_unreadable))
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).performScrollTo().assertExists()
    }

    @Test
    fun theAnswerWaitsForTheOwnersWordAndTheButtonSaysWhatItWouldDo() {
        show(overThreshold())
        compose
            .onNodeWithTag(InboxTags.ACKNOWLEDGE)
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextEquals(text(R.string.acknowledge_despite_warnings))
        // Rejecting never waits for anything: saying no is the safe answer.
        compose.onNodeWithTag(InboxTags.REJECT).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(Answer.Reject), answers)

        val tick = compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE)
        tick.performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(true), ticks)
    }

    @Test
    fun onceTheOwnerHasSaidSoTheAnswerGoesThrough() {
        show(overThreshold(), acknowledged = true)
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).performScrollTo().assertIsOn()
        compose
            .onNodeWithTag(InboxTags.ACKNOWLEDGE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf(Answer.Acknowledge), answers)
    }

    /** Every check, in order, with [configured] in place of the ones nobody configured. */
    private fun checks(
        vararg configured: Pair<PolicyCheck, PolicyCheckResult>
    ): List<PolicyCheckResult> {
        val byCheck = configured.toMap()
        return PolicyCheck.entries.map { byCheck[it] ?: PolicyCheckResult.notConfigured(it) }
    }

    private fun overThreshold() =
        assess(
            checks(
                PolicyCheck.PerOperationLimit to
                    PolicyCheckResult.failed(
                        PolicyCheck.PerOperationLimit,
                        PolicyReason.OverPerOperationLimit,
                        "2.5 SOL of 1 SOL",
                    )
            )
        )

    private companion object {
        const val RECIPIENT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        val ACK: ActionRequest =
            FakeConnectionGateway.request(HOME.id, createdAt = NOW.minusSeconds(60))
    }
}
