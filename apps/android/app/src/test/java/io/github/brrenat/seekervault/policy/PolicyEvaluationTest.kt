package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.policy.storage.StoredPolicy
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assessing one request against one connection's rules (docs/policy.md#evaluation-semantics): the
 * conjunction, the thresholds and their boundaries, and the two things it must never do — allow
 * what it couldn't read, and act.
 */
class PolicyEvaluationTest {
    private fun checkOf(decision: PolicyDecision, check: PolicyCheck): PolicyCheckResult =
        decision.checks.single { it.check == check }

    @Test
    fun everyConfiguredCheckHasToPass() {
        val policy =
            policy()
                .copy(
                    actions = Allowlist.of(PolicyAction.Transfer),
                    recipients = Allowlist.of(RECIPIENT),
                )

        assertTrue(evaluate(policy, solFacts()).allowed)
        // One failure is enough. The rules are a conjunction, and there is no weighing.
        assertFalse(evaluate(policy, solFacts(recipient = STRANGER)).allowed)
    }

    @Test
    fun theSameRulesAndFactsAlwaysReachTheSameVerdict() {
        val policy = policy().copy(assets = Allowlist.of(SOL), recipients = Allowlist.of(RECIPIENT))
        val facts = solFacts()

        val first = evaluate(policy, facts, spentToday(facts, confirmed = 5UL))
        val second = evaluate(policy, facts, spentToday(facts, confirmed = 5UL))

        assertEquals(first, second)
    }

    @Test
    fun anAmountExactlyOnAThresholdMatchesAndOneBaseUnitMoreDoesNot() {
        val policy =
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(perOperation = ONE_SOL)),
                )

        assertTrue(evaluate(policy, solFacts(amount = ONE_SOL - 1UL)).allowed)
        assertTrue(evaluate(policy, solFacts(amount = ONE_SOL)).allowed)
        assertFalse(evaluate(policy, solFacts(amount = ONE_SOL + 1UL)).allowed)
    }

    @Test
    fun aDailyThresholdIsMeasuredAgainstTodayPlusThisRequest() {
        val limit = 10UL * ONE_SOL
        val policy =
            policy()
                .copy(assets = Allowlist.of(SOL), limits = mapOf(SOL to AssetLimits(daily = limit)))
        val facts = solFacts(amount = ONE_SOL)

        // Nine spent and one more is exactly the day's threshold, and exactly is a match.
        assertTrue(evaluate(policy, facts, spentToday(facts, confirmed = 9UL * ONE_SOL)).allowed)
        assertFalse(
            evaluate(policy, facts, spentToday(facts, confirmed = 9UL * ONE_SOL + 1UL)).allowed
        )
    }

    @Test
    fun unresolvedExposureWarnsWithoutBeingCalledSpending() {
        val policy =
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = 10UL * ONE_SOL)),
                )
        val facts = solFacts(amount = ONE_SOL)
        val today = spentToday(facts, confirmed = 4UL * ONE_SOL, unresolved = 6UL * ONE_SOL)

        val decision = evaluate(policy, facts, today)
        val daily = checkOf(decision, PolicyCheck.DailyLimit)

        assertEquals(PolicyCheckStatus.Failed, daily.status)
        assertEquals(PolicyReason.OverDailyLimit, daily.reason)
        // The warning is made of both numbers, and it says which is which.
        val detail = checkNotNull(daily.detail)
        assertTrue(detail, detail.contains("4 confirmed"))
        assertTrue(detail, detail.contains("6 not yet settled"))
        assertTrue(detail, detail.contains("1 now"))
        // And the day's own record still keeps them apart.
        assertEquals(4UL * ONE_SOL, today.confirmed)
        assertEquals(6UL * ONE_SOL, today.unresolved)
    }

    @Test
    fun aThresholdThatWouldOverflowIsStillOver() {
        val policy =
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = ULong.MAX_VALUE)),
                )
        val facts = solFacts(amount = ULong.MAX_VALUE)

        assertFalse(evaluate(policy, facts, spentToday(facts, confirmed = 1UL)).allowed)
    }

    @Test
    fun aThresholdIsReadForTheAssetThatMoves() {
        val policy =
            policy()
                .copy(
                    assets = Allowlist.of(SOL, USDC),
                    limits =
                        mapOf(
                            SOL to AssetLimits(perOperation = ONE_SOL),
                            USDC to AssetLimits(perOperation = 1_000_000UL),
                        ),
                )

        // Two million base units is over the token's threshold and far under the SOL one.
        assertFalse(evaluate(policy, tokenFacts(amount = 2_000_000UL)).allowed)
        assertTrue(evaluate(policy, solFacts(amount = 2_000_000UL)).allowed)
    }

    @Test
    fun anAssetWithNoThresholdOfItsOwnHasNoneAppliedToIt() {
        val policy =
            policy()
                .copy(
                    assets = Allowlist.of(SOL, USDC),
                    limits = mapOf(SOL to AssetLimits(perOperation = 1UL)),
                )

        val decision = evaluate(policy, tokenFacts(amount = ULong.MAX_VALUE))

        assertEquals(
            PolicyCheckStatus.NotConfigured,
            checkOf(decision, PolicyCheck.PerOperationLimit).status,
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun anAmountIsComparedInBaseUnitsAndShownWithItsDecimalPoint() {
        val policy =
            policy()
                .copy(
                    assets = Allowlist.of(USDC),
                    limits = mapOf(USDC to AssetLimits(perOperation = 1_500_000UL)),
                )

        val decision = evaluate(policy, tokenFacts(amount = 1_500_000UL))

        assertTrue(decision.allowed)
        // Six decimals: 1500000 base units is 1.5 of the token, exactly, and never 1.4999999.
        assertEquals("1.5 of 1.5", checkOf(decision, PolicyCheck.PerOperationLimit).detail)
    }

    @Test
    fun aTransactionTheAppCouldNotReadWholeIsNeverAllowed() {
        // The one rule the owner set matches. The transaction still contains an instruction nobody
        // read, and no rule was written about what is in it.
        val policy = policy().copy(actions = Allowlist.of(PolicyAction.Transfer))

        val decision = evaluate(policy, solFacts(fullyRead = false))

        assertFalse(decision.allowed)
        assertEquals(listOf("request_unverified"), decision.reasonCodes)
        // The check that did pass still reads as passed: the verdict is withheld, not rewritten.
        assertEquals(PolicyCheckStatus.Passed, checkOf(decision, PolicyCheck.Action).status)
    }

    @Test
    fun noArrangementOfRulesMakesAnUnreadTransactionAllowed() {
        val facts = solFacts(fullyRead = false)
        val policies =
            listOf(
                policy(),
                policy().copy(actions = Allowlist.of(PolicyAction.Transfer)),
                policy().copy(assets = Allowlist.of(SOL)),
                policy().copy(recipients = Allowlist.of(RECIPIENT)),
                policy().copy(programs = Allowlist(SOL_PROGRAMS.toSet())),
                policy()
                    .copy(
                        actions = Allowlist.of(PolicyAction.Transfer),
                        assets = Allowlist.of(SOL),
                        recipients = Allowlist.of(RECIPIENT),
                        programs = Allowlist(SOL_PROGRAMS.toSet()),
                        limits = mapOf(SOL to AssetLimits(perOperation = ULong.MAX_VALUE)),
                    ),
            )

        for (policy in policies) {
            assertFalse("$policy", evaluate(policy, facts, spentToday(facts)).allowed)
        }
    }

    @Test
    fun anUnverifiedCheckNeverPasses() {
        val policy =
            policy()
                .copy(
                    recipients = Allowlist.of(RECIPIENT),
                    programs = Allowlist(SOL_PROGRAMS.toSet()),
                )

        val decision = evaluate(policy, solFacts(recipient = null))

        assertFalse(decision.allowed)
        assertEquals(listOf(PolicyCheck.Recipient), decision.unverified)
        assertEquals(listOf("recipient_unverified"), decision.reasonCodes)
    }

    @Test
    fun aRequestThatMovesNothingSatisfiesTheRulesAboutWhatMoves() {
        val policy =
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    recipients = Allowlist.of(RECIPIENT),
                    programs = Allowlist.of(SYSTEM_PROGRAM),
                    limits = mapOf(SOL to AssetLimits(perOperation = 1UL)),
                )

        val decision =
            evaluate(policy, RequestFacts.movesNothing(CONNECTION, PolicyAction.MessageSignature))

        assertTrue(decision.allowed)
        // And it says why, rather than showing a check that looks as if it examined something.
        assertEquals("nothing moves", checkOf(decision, PolicyCheck.Asset).detail)
        assertEquals("nothing moves", checkOf(decision, PolicyCheck.PerOperationLimit).detail)
    }

    @Test
    fun nothingConfiguredIsNeverAMatch() {
        val decision = evaluate(policy(), solFacts())

        assertFalse(decision.allowed)
        assertEquals(listOf("no_policy_configured"), decision.reasonCodes)
        assertEquals(PolicyCheck.entries.toList(), decision.notChecked)
    }

    @Test
    fun rulesThatCouldNotBeReadAreNotTheAbsenceOfRules() {
        val none = evaluate(StoredPolicy.None, solFacts())
        val unreadable =
            evaluate(StoredPolicy.Unreadable(UnreadableReason.NewerVersion), solFacts())

        assertEquals(listOf("no_policy_configured"), none.reasonCodes)
        assertEquals(listOf("policy_unreadable"), unreadable.reasonCodes)
        assertFalse(none.allowed)
        assertFalse(unreadable.allowed)
    }

    @Test
    fun storedRulesAreAppliedAsTheyStand() {
        val stored =
            StoredPolicy.Policy(policy().copy(actions = Allowlist.of(PolicyAction.Transfer)))

        assertTrue(evaluate(stored, solFacts()).allowed)
    }

    @Test
    fun oneConnectionsRulesAreNeverAppliedToAnothersRequest() {
        val theirs = policy().copy(actions = Allowlist.of(PolicyAction.Transfer))

        assertThrows(IllegalArgumentException::class.java) {
            evaluate(theirs, solFacts(connectionId = OTHER_CONNECTION))
        }
    }

    @Test
    fun anotherScopesCountersAreNeverReadAsThisOnes() {
        val facts = solFacts()
        val elsewhere = spentToday(facts).copy(scope = SpendScope(OTHER_CONNECTION, WALLET, SOL))

        assertThrows(IllegalArgumentException::class.java) {
            evaluate(policy().copy(assets = Allowlist.of(SOL)), facts, elsewhere)
        }
    }

    @Test
    fun thereAreTwoVerdictsAndNeitherOfThemIsABlock() {
        // Every case in the table, and every case there could be, comes to one of two things. A
        // request outside the rules is shown with its reasons and left to the owner.
        val verdicts =
            POLICY_CASES.map { evaluate(it.policy, it.facts, it.spentToday).assessment }.toSet()

        assertEquals(
            setOf(PolicyAssessment.Allowed, PolicyAssessment.UnderRestrictions),
            verdicts,
        )
        assertEquals(2, PolicyAssessment.entries.size)
    }
}
