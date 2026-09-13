package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Effective rules are evaluated with source metadata and two independent daily results. */
class EffectivePolicyEvaluationTest {
    private val day = NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate()

    private fun totals(
        facts: RequestFacts,
        globalConfirmed: ULong = 0UL,
        localConfirmed: ULong = 0UL,
        globalUnresolved: ULong = 0UL,
        localUnresolved: ULong = 0UL,
        unreadable: Int = 0,
    ): DailyTotals {
        val local = scopeOf(facts)
        return DailyTotals(
            global =
                DailyTotal(
                    GlobalSpendScope(local.wallet, local.asset),
                    day,
                    globalConfirmed,
                    globalUnresolved,
                    if (globalConfirmed > 0UL) 1 else 0,
                    if (globalUnresolved > 0UL) 1 else 0,
                    unreadable,
                ),
            connection =
                DailyTotal(
                    local,
                    day,
                    localConfirmed,
                    localUnresolved,
                    if (localConfirmed > 0UL) 1 else 0,
                    if (localUnresolved > 0UL) 1 else 0,
                    unreadable,
                ),
        )
    }

    @Test
    fun globalAndConnectionSectionsAreOneConjunctionWithTheirSources() {
        val effective =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW)
                    .copy(
                        actions = Allowlist.of(PolicyAction.Transfer),
                        programs = Allowlist.of(SYSTEM_PROGRAM),
                    ),
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(recipients = RuleOverride.Replace(Allowlist.of(RECIPIENT))),
            )

        val decision = evaluate(effective, solFacts(programs = listOf(SYSTEM_PROGRAM)))

        assertTrue(decision.allowed)
        assertEquals(RuleSource.Global, decision.checks[0].source)
        assertEquals(RuleSource.ConnectionOverride, decision.checks[2].source)
        assertEquals(RuleSource.Global, decision.checks[3].source)
        assertEquals(PolicyCheck.entries.toList(), decision.checks.map { it.check })
    }

    @Test
    fun globalElevenOfTenWarnsWhileConnectionFiveOfEightPasses() {
        val facts = solFacts(amount = 5UL * ONE_SOL)
        val effective =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(SOL to AssetLimits(daily = 10UL * ONE_SOL))),
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to ConnectionAssetLimits(daily = 8UL * ONE_SOL))),
            )

        val decision = evaluate(effective, facts, totals(facts, globalConfirmed = 6UL * ONE_SOL))

        assertFalse(decision.allowed)
        assertEquals(
            listOf(DailyCheckScope.Global, DailyCheckScope.Connection),
            decision.dailyChecks.map { it.scope },
        )
        assertEquals(
            listOf(PolicyCheckStatus.Failed, PolicyCheckStatus.Passed),
            decision.dailyChecks.map { it.result.status },
        )
        assertEquals(11UL * ONE_SOL, decision.dailyChecks[0].projected)
        assertEquals(5UL * ONE_SOL, decision.dailyChecks[1].projected)
        assertEquals(listOf("over_daily_limit"), decision.reasonCodes)
    }

    @Test
    fun globalPassesWhileConnectionFourOfThreeWarns() {
        val facts = solFacts(amount = 4UL * ONE_SOL)
        val effective =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(SOL to AssetLimits(daily = 10UL * ONE_SOL))),
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to ConnectionAssetLimits(daily = 3UL * ONE_SOL))),
            )

        val decision = evaluate(effective, facts, totals(facts))

        assertFalse(decision.allowed)
        assertEquals(
            listOf(PolicyCheckStatus.Passed, PolicyCheckStatus.Failed),
            decision.dailyChecks.map { it.result.status },
        )
        assertEquals(listOf("over_daily_limit"), decision.reasonCodes)
    }

    @Test
    fun bothDailyFailuresAreRetainedInDeterministicScopeOrder() {
        val facts = solFacts(amount = 5UL * ONE_SOL)
        val effective =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(SOL to AssetLimits(daily = 4UL * ONE_SOL))),
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to ConnectionAssetLimits(daily = 3UL * ONE_SOL))),
            )

        val decision = evaluate(effective, facts, totals(facts))

        assertEquals(
            listOf(DailyCheckScope.Global, DailyCheckScope.Connection),
            decision.dailyChecks.map { it.scope },
        )
        assertEquals(
            listOf("over_daily_limit", "over_daily_limit"),
            decision.reasonCodes,
        )
        assertTrue(decision.warns)
    }

    @Test
    fun equalBoundariesPassAndOneBaseUnitAboveEitherScopeWarns() {
        val facts = solFacts(amount = ONE_SOL)
        val effective =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(SOL to AssetLimits(daily = 2UL * ONE_SOL))),
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to ConnectionAssetLimits(daily = 2UL * ONE_SOL))),
            )

        assertTrue(
            evaluate(
                    effective,
                    facts,
                    totals(
                        facts,
                        globalConfirmed = ONE_SOL,
                        localConfirmed = ONE_SOL,
                    ),
                )
                .allowed
        )
        val aboveGlobal =
            evaluate(
                effective,
                facts,
                totals(facts, globalConfirmed = ONE_SOL + 1UL, localConfirmed = ONE_SOL),
            )
        val aboveLocal =
            evaluate(
                effective,
                facts,
                totals(facts, globalConfirmed = ONE_SOL, localConfirmed = ONE_SOL + 1UL),
            )
        assertEquals(PolicyCheckStatus.Failed, aboveGlobal.dailyChecks[0].result.status)
        assertEquals(PolicyCheckStatus.Failed, aboveLocal.dailyChecks[1].result.status)
    }

    @Test
    fun globalOnlyWarningLocalOnlyWarningAndNoDailyThresholdsStayDistinct() {
        val facts = solFacts(amount = 2UL)
        val globalOnly =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW).copy(limits = mapOf(SOL to AssetLimits(daily = 1UL))),
                null,
            )
        val localOnly =
            resolveEffectivePolicy(
                CONNECTION,
                null,
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to ConnectionAssetLimits(daily = 1UL))),
            )
        val none = resolveEffectivePolicy(CONNECTION, null, null)

        val globalWarning = evaluate(globalOnly, facts, totals(facts))
        assertEquals(
            listOf(PolicyCheckStatus.Failed, PolicyCheckStatus.NotConfigured),
            globalWarning.dailyChecks.map { it.result.status },
        )
        assertTrue(globalWarning.warns)
        val localWarning = evaluate(localOnly, facts, totals(facts))
        assertEquals(
            listOf(PolicyCheckStatus.NotConfigured, PolicyCheckStatus.Failed),
            localWarning.dailyChecks.map { it.result.status },
        )
        assertTrue(localWarning.warns)
        val noThresholds = evaluate(none, facts, totals(facts))
        assertEquals(
            listOf(PolicyCheckStatus.NotConfigured, PolicyCheckStatus.NotConfigured),
            noThresholds.dailyChecks.map { it.result.status },
        )
        assertEquals(listOf("no_policy_configured"), noThresholds.reasonCodes)
        assertFalse(noThresholds.warns)
    }

    @Test
    fun aLocalThresholdAboveGlobalCannotSuppressTheGlobalWarning() {
        val facts = solFacts(amount = 6UL * ONE_SOL)
        val effective =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(SOL to AssetLimits(daily = 5UL * ONE_SOL))),
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to ConnectionAssetLimits(daily = 10UL * ONE_SOL))),
            )

        val decision = evaluate(effective, facts, totals(facts))

        assertFalse(decision.allowed)
        assertEquals(PolicyCheckStatus.Failed, decision.dailyChecks[0].result.status)
        assertEquals(PolicyCheckStatus.Passed, decision.dailyChecks[1].result.status)
    }

    @Test
    fun unreadHistoryMakesEachConfiguredDailyScopeUnverified() {
        val facts = solFacts()
        val effective =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(SOL to AssetLimits(daily = 10UL * ONE_SOL))),
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to ConnectionAssetLimits(daily = 10UL * ONE_SOL))),
            )

        val unavailable = evaluate(effective, facts, DailyTotals(null, null))
        val partial = evaluate(effective, facts, totals(facts, unreadable = 1))

        assertEquals(
            listOf(PolicyCheckStatus.Unverified, PolicyCheckStatus.Unverified),
            unavailable.dailyChecks.map { it.result.status },
        )
        assertEquals(
            listOf(PolicyCheckStatus.Unverified, PolicyCheckStatus.Unverified),
            partial.dailyChecks.map { it.result.status },
        )
        assertEquals(
            listOf("daily_total_unverified", "daily_total_unverified"),
            partial.reasonCodes,
        )
    }

    @Test
    fun aggregateOverflowCannotTurnEitherDailyCheckIntoAPass() {
        val facts = solFacts(amount = 1UL)
        val effective =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(SOL to AssetLimits(daily = ULong.MAX_VALUE))),
                ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to ConnectionAssetLimits(daily = ULong.MAX_VALUE))),
            )

        val decision =
            evaluate(
                effective,
                facts,
                totals(
                    facts,
                    globalConfirmed = ULong.MAX_VALUE,
                    localConfirmed = ULong.MAX_VALUE,
                ),
            )

        assertEquals(
            listOf(PolicyCheckStatus.Failed, PolicyCheckStatus.Failed),
            decision.dailyChecks.map { it.result.status },
        )
    }
}
