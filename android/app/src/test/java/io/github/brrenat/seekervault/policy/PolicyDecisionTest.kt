package io.github.brrenat.seekervault.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The evaluation semantics: one conjunction, two verdicts, and no branch that acts on either. */
class PolicyDecisionTest {
    @Test
    fun everyConfiguredCheckPassingIsAllowed() {
        val decision =
            assess(
                checks(
                    PolicyCheck.Action to PolicyCheckResult.passed(PolicyCheck.Action),
                    PolicyCheck.Asset to PolicyCheckResult.passed(PolicyCheck.Asset),
                )
            )

        assertEquals(PolicyAssessment.Allowed, decision.assessment)
        assertTrue(decision.allowed)
        assertEquals(emptyList<PolicyReason>(), decision.reasons)
    }

    @Test
    fun oneFailedCheckIsEnoughToPutTheWholeRequestUnderRestrictions() {
        val decision =
            assess(
                checks(
                    PolicyCheck.Action to PolicyCheckResult.passed(PolicyCheck.Action),
                    PolicyCheck.Recipient to
                        PolicyCheckResult.failed(
                            PolicyCheck.Recipient,
                            PolicyReason.RecipientNotAllowed,
                        ),
                )
            )

        assertEquals(PolicyAssessment.UnderRestrictions, decision.assessment)
        assertEquals(listOf(PolicyReason.RecipientNotAllowed), decision.reasons)
        assertEquals(listOf("recipient_not_allowed"), decision.reasonCodes)
    }

    @Test
    fun reasonsComeBackInCheckOrderHoweverTheChecksWereBuilt() {
        val decision =
            assess(
                checks(
                    PolicyCheck.DailyLimit to
                        PolicyCheckResult.failed(
                            PolicyCheck.DailyLimit,
                            PolicyReason.OverDailyLimit,
                        ),
                    PolicyCheck.Action to
                        PolicyCheckResult.failed(PolicyCheck.Action, PolicyReason.ActionNotAllowed),
                )
            )

        assertEquals(
            listOf(PolicyReason.ActionNotAllowed, PolicyReason.OverDailyLimit),
            decision.reasons,
        )
    }

    @Test
    fun aCheckThePhoneCouldNotVerifyIsNeverAllowed() {
        val decision =
            assess(
                checks(
                    PolicyCheck.Asset to PolicyCheckResult.passed(PolicyCheck.Asset),
                    PolicyCheck.Recipient to
                        PolicyCheckResult.unverified(
                            PolicyCheck.Recipient,
                            PolicyReason.RecipientUnverified,
                        ),
                )
            )

        assertEquals(PolicyAssessment.UnderRestrictions, decision.assessment)
        assertEquals(listOf(PolicyCheck.Recipient), decision.unverified)
        // Coverage is reported apart from the verdict: what failed, and what simply wasn't asked.
        assertEquals(
            listOf(
                PolicyCheck.Action,
                PolicyCheck.Program,
                PolicyCheck.PerOperationLimit,
                PolicyCheck.DailyLimit,
            ),
            decision.notChecked,
        )
    }

    @Test
    fun aCheckNobodyConfiguredNeitherPassesNorFails() {
        val decision =
            assess(checks(PolicyCheck.Action to PolicyCheckResult.passed(PolicyCheck.Action)))

        assertEquals(PolicyAssessment.Allowed, decision.assessment)
        assertFalse(decision.coversEveryCheck)
        assertEquals(PolicyCheck.entries - PolicyCheck.Action, decision.notChecked)
        assertEquals(emptyList<PolicyReason>(), decision.reasons)
    }

    @Test
    fun aPolicyThatChecksNothingIsNotAnAllowedOne() {
        val decision = assess(checks())

        assertEquals(PolicyAssessment.UnderRestrictions, decision.assessment)
        assertEquals(listOf(PolicyReason.NoPolicyConfigured), decision.reasons)
        assertEquals(PolicyCheck.entries.toList(), decision.notChecked)
    }

    @Test
    fun rulesThisBuildCannotReadAreNotTheAbsenceOfRules() {
        val unreadable = noPolicy(PolicyReason.PolicyUnreadable)
        val none = noPolicy(PolicyReason.NoPolicyConfigured)

        assertEquals(PolicyAssessment.UnderRestrictions, unreadable.assessment)
        assertEquals(listOf("policy_unreadable"), unreadable.reasonCodes)
        assertEquals(listOf("no_policy_configured"), none.reasonCodes)
        assertFalse(unreadable == none)
    }

    @Test
    fun onlyHavingNoRulesToApplyIsAReasonWithoutACheck() {
        assertThrows(IllegalArgumentException::class.java) { noPolicy(PolicyReason.OverDailyLimit) }
    }

    @Test
    fun everyCheckIsAssessedExactlyOnce() {
        assertThrows(IllegalArgumentException::class.java) {
            assess(listOf(PolicyCheckResult.passed(PolicyCheck.Action)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            assess(checks().reversed())
        }
        assertThrows(IllegalArgumentException::class.java) {
            assess(checks() + PolicyCheckResult.passed(PolicyCheck.Action))
        }
    }

    @Test
    fun aPassingCheckCarriesNoReasonAndAFailingOneAlwaysDoes() {
        assertThrows(IllegalArgumentException::class.java) {
            PolicyCheckResult(
                PolicyCheck.Action,
                PolicyCheckStatus.Passed,
                PolicyReason.ActionNotAllowed,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyCheckResult(PolicyCheck.Action, PolicyCheckStatus.Failed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyCheckResult(PolicyCheck.Action, PolicyCheckStatus.Unverified)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyCheckResult(
                PolicyCheck.Action,
                PolicyCheckStatus.NotConfigured,
                PolicyReason.ActionNotAllowed,
            )
        }
    }

    @Test
    fun thereAreTwoVerdictsAndNeitherOfThemActs() {
        // No BLOCKED, and no third verdict of any kind: the owner decides both ways.
        assertEquals(
            listOf(PolicyAssessment.Allowed, PolicyAssessment.UnderRestrictions),
            PolicyAssessment.entries.toList(),
        )
        // A decision is a reading, not an instruction: it carries the verdict, the checks behind
        // it, and nothing that could act on either. `StageBoundaryTest` holds that line in the
        // source; here it is enough that neither verdict decides anything by itself.
        assertTrue(
            assess(checks(PolicyCheck.Action to PolicyCheckResult.passed(PolicyCheck.Action)))
                .allowed
        )
        assertFalse(noPolicy(PolicyReason.NoPolicyConfigured).allowed)
    }

    @Test
    fun everyReasonCodeIsItsOwn() {
        val codes = PolicyReason.entries.map { it.code }

        assertEquals(codes.distinct(), codes)
        assertEquals(codes.map { it.lowercase() }, codes)
    }

    @Test
    fun havingNoRulesAtAllIsNotSomethingToWarnAbout() {
        // Every request on a phone whose owner has written no rules is UNDER_RESTRICTIONS. Asking
        // them to tick past that every time would teach them to tick past warnings (SAW-028).
        assertFalse(noPolicy(PolicyReason.NoPolicyConfigured).warns)
        // Rules that are stored and can't be read are the other way round: something was written,
        // and this build can't say what.
        assertTrue(noPolicy(PolicyReason.PolicyUnreadable).warns)
    }

    @Test
    fun anythingOutsideTheRulesIsSomethingToWarnAbout() {
        val failed =
            assess(
                checks(
                    PolicyCheck.Recipient to
                        PolicyCheckResult.failed(
                            PolicyCheck.Recipient,
                            PolicyReason.RecipientNotAllowed,
                        )
                )
            )
        assertTrue(failed.warns)
        // A check that couldn't be applied warns too: coverage is not compliance.
        val unverified =
            assess(
                checks(
                    PolicyCheck.Recipient to
                        PolicyCheckResult.unverified(
                            PolicyCheck.Recipient,
                            PolicyReason.RecipientUnverified,
                        )
                )
            )
        assertTrue(unverified.warns)
        // And a match warns about nothing, which is not the same as approving anything.
        val matched =
            assess(checks(PolicyCheck.Action to PolicyCheckResult.passed(PolicyCheck.Action)))
        assertFalse(matched.warns)
        assertTrue(matched.allowed)
    }

    /** Every check, in order, with [configured] put in place of the checks nobody configured. */
    private fun checks(
        vararg configured: Pair<PolicyCheck, PolicyCheckResult>
    ): List<PolicyCheckResult> {
        val byCheck = configured.toMap()
        return PolicyCheck.entries.map {
            byCheck[it] ?: PolicyCheckResult.notConfigured(it)
        }
    }
}
