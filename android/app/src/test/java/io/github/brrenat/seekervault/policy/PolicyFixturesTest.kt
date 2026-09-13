package io.github.brrenat.seekervault.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture table in [POLICY_CASES], run case by case (docs/policy.md#test-fixtures). Each one
 * names the rules, the facts, the day's counters, and every reason code the verdict must carry.
 */
class PolicyFixturesTest {
    @Test
    fun everyCaseReachesTheVerdictItNames() {
        for (case in POLICY_CASES) {
            val decision = evaluate(case.policy, case.facts, case.spentToday)

            assertEquals(case.name, case.assessment, decision.assessment)
            assertEquals(case.name, case.reasons, decision.reasonCodes)
            assertEquals(case.name, case.notChecked, decision.notChecked)
        }
    }

    @Test
    fun everyCaseAssessesEveryCheckExactlyOnce() {
        for (case in POLICY_CASES) {
            val decision = evaluate(case.policy, case.facts, case.spentToday)

            assertEquals(case.name, PolicyCheck.entries.toList(), decision.checks.map { it.check })
        }
    }

    @Test
    fun allowedMeansEveryCheckThatRanPassed() {
        for (case in POLICY_CASES.filter { it.assessment == PolicyAssessment.Allowed }) {
            val decision = evaluate(case.policy, case.facts, case.spentToday)

            assertEquals(case.name, emptyList<PolicyReason>(), decision.reasons)
            assertTrue(case.name, decision.unverified.isEmpty())
            // And a request the phone couldn't read whole is never one of them.
            assertTrue(case.name, case.facts.fullyRead)
        }
    }

    @Test
    fun theTableCoversEveryReasonAVerdictCanCarry() {
        val used = POLICY_CASES.flatMap { it.reasons }.toSet()
        // Every reason except the one that comes from the stored document rather than from a
        // request: there is no policy to evaluate at all, so no case in this table produces it.
        val fromStorage = setOf(PolicyReason.PolicyUnreadable.code)
        val missing = PolicyReason.entries.map { it.code } - used - fromStorage

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun everyCaseIsNamedOnce() {
        val names = POLICY_CASES.map { it.name }

        assertEquals(names.distinct(), names)
        assertTrue(POLICY_CASES.size >= 15)
    }
}
