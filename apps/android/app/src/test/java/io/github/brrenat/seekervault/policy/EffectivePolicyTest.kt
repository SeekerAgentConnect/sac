package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Global defaults and connection overrides resolve by section, with their sources intact. */
class EffectivePolicyTest {
    @Test
    fun differentAllowlistSectionsCombineAndNameTheirSources() {
        val global =
            GlobalPolicy.default(NOW)
                .copy(
                    actions = Allowlist.of(PolicyAction.Transfer),
                    programs = Allowlist.of(SYSTEM),
                )
        val local =
            ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER)
                .copy(recipients = RuleOverride.Replace(Allowlist.of(RECIPIENT)))

        val effective = resolveEffectivePolicy(CONNECTION, global, local)

        assertEquals(Allowlist.of(PolicyAction.Transfer), effective.actions.value)
        assertEquals(RuleSource.Global, effective.actions.source)
        assertEquals(Allowlist.of(SYSTEM), effective.programs.value)
        assertEquals(RuleSource.Global, effective.programs.source)
        assertEquals(Allowlist.of(RECIPIENT), effective.recipients.value)
        assertEquals(RuleSource.ConnectionOverride, effective.recipients.source)
        assertEquals(EffectiveRule.notConfigured<Allowlist<PolicyAsset>>(), effective.assets)
    }

    @Test
    fun aConnectionAllowlistReplacesTheWholeGlobalSectionRatherThanUnioningIt() {
        val global = GlobalPolicy.default(NOW).copy(programs = Allowlist.of(SYSTEM))
        val local =
            ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER)
                .copy(programs = RuleOverride.Replace(Allowlist.of(TOKEN_PROGRAM)))

        val programs = resolveEffectivePolicy(CONNECTION, global, local).programs

        assertEquals(RuleSource.ConnectionOverride, programs.source)
        assertEquals(setOf(TOKEN_PROGRAM), checkNotNull(programs.value).values)
        assertFalse(SYSTEM in checkNotNull(programs.value))
    }

    @Test
    fun inheritNoCheckAndEmptyListStayThreeDifferentStates() {
        data class Case(
            val name: String,
            val global: Allowlist<PolicyAction>?,
            val local: RuleOverride<Allowlist<PolicyAction>>,
            val expected: EffectiveRule<Allowlist<PolicyAction>>,
        )

        val transfer = Allowlist.of(PolicyAction.Transfer)
        val cases =
            listOf(
                Case(
                    "inherit",
                    transfer,
                    RuleOverride.Inherit,
                    EffectiveRule(transfer, RuleSource.Global),
                ),
                Case(
                    "explicit no check",
                    transfer,
                    RuleOverride.NoCheck,
                    EffectiveRule(null, RuleSource.ConnectionOverride),
                ),
                Case(
                    "explicit empty list",
                    transfer,
                    RuleOverride.Replace(Allowlist.nothing()),
                    EffectiveRule(Allowlist.nothing(), RuleSource.ConnectionOverride),
                ),
                Case(
                    "inherit absent global",
                    null,
                    RuleOverride.Inherit,
                    EffectiveRule.notConfigured(),
                ),
            )

        for (case in cases) {
            val global = GlobalPolicy.default(NOW).copy(actions = case.global)
            val local =
                ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER).copy(actions = case.local)

            assertEquals(
                case.name,
                case.expected,
                resolveEffectivePolicy(CONNECTION, global, local).actions,
            )
        }
    }

    @Test
    fun perRequestThresholdsResolvePerAssetAndDailyThresholdsStaySeparate() {
        val global =
            GlobalPolicy.default(NOW)
                .copy(
                    limits =
                        linkedMapOf(
                            SOL to AssetLimits(perOperation = 10UL, daily = 100UL),
                            USDC to AssetLimits(perOperation = 20UL, daily = 200UL),
                        )
                )
        val local =
            ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER)
                .copy(
                    limits =
                        linkedMapOf(
                            SOL to
                                ConnectionAssetLimits(
                                    perOperation = RuleOverride.Replace(15UL),
                                    daily = 30UL,
                                ),
                            USDC to ConnectionAssetLimits(perOperation = RuleOverride.NoCheck),
                            DEVNET_SOL to
                                ConnectionAssetLimits(
                                    perOperation = RuleOverride.Replace(5UL),
                                    daily = 9UL,
                                ),
                        )
                )

        val effective = resolveEffectivePolicy(CONNECTION, global, local)

        assertEquals(
            EffectiveAssetLimits(
                perOperation = EffectiveRule(15UL, RuleSource.ConnectionOverride),
                globalDaily = EffectiveRule(100UL, RuleSource.Global),
                connectionDaily = EffectiveRule(30UL, RuleSource.ConnectionOverride),
            ),
            effective.limitsFor(SOL),
        )
        assertEquals(
            EffectiveAssetLimits(
                perOperation = EffectiveRule(null, RuleSource.ConnectionOverride),
                globalDaily = EffectiveRule(200UL, RuleSource.Global),
            ),
            effective.limitsFor(USDC),
        )
        assertEquals(
            EffectiveAssetLimits(
                perOperation = EffectiveRule(5UL, RuleSource.ConnectionOverride),
                connectionDaily = EffectiveRule(9UL, RuleSource.ConnectionOverride),
            ),
            effective.limitsFor(DEVNET_SOL),
        )
        assertEquals(listOf(SOL, USDC, DEVNET_SOL), effective.limits.keys.toList())
        assertEquals(effective, resolveEffectivePolicy(CONNECTION, global, local))
    }

    @Test
    fun assetReplacementNoCheckAndResetResolveThresholdsIndependently() {
        val global =
            GlobalPolicy.default(NOW)
                .copy(limits = mapOf(SOL to AssetLimits(perOperation = 7UL, daily = 10UL)))
        val inheritAll = ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER)

        val withAssetReplacement =
            resolveEffectivePolicy(
                    CONNECTION,
                    global,
                    inheritAll.copy(assets = RuleOverride.Replace(Allowlist.nothing())),
                )
                .limitsFor(SOL)
        assertEquals(EffectiveRule(7UL, RuleSource.Global), withAssetReplacement.perOperation)
        assertEquals(EffectiveRule(10UL, RuleSource.Global), withAssetReplacement.globalDaily)

        val withPerRequestNoCheck =
            resolveEffectivePolicy(
                    CONNECTION,
                    global,
                    inheritAll.copy(
                        limits =
                            mapOf(SOL to ConnectionAssetLimits(perOperation = RuleOverride.NoCheck))
                    ),
                )
                .limitsFor(SOL)
        assertEquals(
            EffectiveRule<ULong>(null, RuleSource.ConnectionOverride),
            withPerRequestNoCheck.perOperation,
        )
        assertEquals(EffectiveRule(10UL, RuleSource.Global), withPerRequestNoCheck.globalDaily)

        for (local in listOf(inheritAll, null)) {
            val limits = resolveEffectivePolicy(CONNECTION, global, local).limitsFor(SOL)
            assertEquals(EffectiveRule(7UL, RuleSource.Global), limits.perOperation)
            assertEquals(EffectiveRule(10UL, RuleSource.Global), limits.globalDaily)
        }
    }

    @Test
    fun aGlobalDailyBelowALocalPerRequestThresholdIsValidAcrossScopes() {
        val global = GlobalPolicy.default(NOW).copy(limits = mapOf(SOL to AssetLimits(daily = 5UL)))
        val local =
            ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER)
                .copy(
                    limits =
                        mapOf(
                            SOL to ConnectionAssetLimits(perOperation = RuleOverride.Replace(10UL))
                        )
                )

        assertEquals(emptyList<PolicyProblem>(), policyProblems(global))
        assertEquals(emptyList<PolicyProblem>(), policyProblems(local))
        val limits = resolveEffectivePolicy(CONNECTION, global, local).limitsFor(SOL)
        assertEquals(10UL, limits.perOperation.value)
        assertEquals(5UL, limits.globalDaily.value)

        val invalidWithinOneScope =
            local.copy(
                limits = mapOf(SOL to ConnectionAssetLimits(RuleOverride.Replace(10UL), 5UL))
            )
        assertTrue(PolicyProblem.DailyBelowPerOperation in policyProblems(invalidWithinOneScope))
    }

    @Test
    fun aNewConnectionWithNoDocumentInheritsGlobalRules() {
        val global = GlobalPolicy.default(NOW).copy(recipients = Allowlist.of(RECIPIENT))

        val effective = resolveEffectivePolicy(CONNECTION, global, null)

        assertEquals(EffectiveRule(global.recipients, RuleSource.Global), effective.recipients)
    }

    private companion object {
        const val CONNECTION = "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11"
        const val RECIPIENT = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt"
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val SYSTEM = "11111111111111111111111111111111"
        const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        val SOL = PolicyAsset.sol(Network.NETWORK_MAINNET)
        val DEVNET_SOL = PolicyAsset.sol(Network.NETWORK_DEVNET)
        val USDC = PolicyAsset.token(Network.NETWORK_MAINNET, MINT)
        val NOW: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val LATER: Instant = Instant.parse("2026-09-13T11:00:00Z")
    }
}
