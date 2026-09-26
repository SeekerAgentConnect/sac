package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The policy model: what a rule is, and what its absence is (docs/policy.md). */
class PolicyTest {
    @Test
    fun theDefaultPolicyConfiguresNothing() {
        val policy = ConnectionPolicy.default(CONNECTION, NOW)

        assertTrue(policy.configuresNothing)
        assertEquals(null, policy.actions)
        assertEquals(null, policy.assets)
        assertEquals(null, policy.recipients)
        assertEquals(null, policy.programs)
        assertEquals(emptyMap<PolicyAsset, AssetLimits>(), policy.limits)
        assertEquals(emptyList<PolicyProblem>(), policyProblems(policy))
    }

    @Test
    fun anEmptyListIsConfiguredAndAnAbsentListIsNot() {
        val none = ConnectionPolicy.default(CONNECTION, NOW).copy(recipients = Allowlist.nothing())

        // Configured to allow nothing: the check runs, and every recipient fails it.
        assertFalse(none.configuresNothing)
        assertTrue(checkNotNull(none.recipients).allowsNothing)
        assertFalse(RECIPIENT in checkNotNull(none.recipients))

        // No list at all: nothing to run, and nothing claimed about recipients.
        assertEquals(null, ConnectionPolicy.default(CONNECTION, NOW).recipients)
    }

    @Test
    fun aLimitOfNothingIsNotAnEmptyList() {
        val policy = ConnectionPolicy.default(CONNECTION, NOW)

        assertEquals(AssetLimits(), policy.limitsFor(PolicyAsset.sol(Network.NETWORK_MAINNET)))
        assertFalse(AssetLimits().configuresSomething)
        assertTrue(AssetLimits(perOperation = 1UL).configuresSomething)
        assertTrue(AssetLimits(daily = 1UL).configuresSomething)
    }

    @Test
    fun theSameMintOnTwoNetworksIsTwoAssets() {
        val mainnet = PolicyAsset.token(Network.NETWORK_MAINNET, MINT)
        val devnet = PolicyAsset.token(Network.NETWORK_DEVNET, MINT)

        assertFalse(mainnet == devnet)
        assertFalse(devnet in Allowlist.of(mainnet))
        assertFalse(mainnet.isNativeSol)
        assertTrue(PolicyAsset.sol(Network.NETWORK_MAINNET).isNativeSol)
    }

    @Test
    fun anAmountThresholdIsKeptInTheAssetsOwnBaseUnits() {
        // The biggest amount a transfer can carry, which a signed 64-bit type could not hold.
        val limits = AssetLimits(perOperation = ULong.MAX_VALUE, daily = ULong.MAX_VALUE)

        assertEquals("18446744073709551615", limits.perOperation.toString())
    }

    @Test
    fun anAddressThatIsNotAnAddressIsRefused() {
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(recipients = Allowlist.of(RECIPIENT, "not an address"))

        assertHas(policyProblems(policy), PolicyProblem.NotAnAddress)
        assertHas(
            policyProblems(
                ConnectionPolicy.default(CONNECTION, NOW).copy(programs = Allowlist.of("0OIl"))
            ),
            PolicyProblem.NotAnAddress,
        )
    }

    @Test
    fun anUnsupportedAssetIsRefused() {
        val badMint =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(assets = Allowlist.of(PolicyAsset.token(Network.NETWORK_MAINNET, "nope!")))
        assertHas(policyProblems(badMint), PolicyProblem.NotAMint)

        val noNetwork =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(assets = Allowlist.of(PolicyAsset.sol(Network.NETWORK_UNSPECIFIED)))
        assertHas(policyProblems(noNetwork), PolicyProblem.NoNetwork)

        // An asset named only in the limits is checked the same way.
        val badLimit =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(
                    limits =
                        mapOf(
                            PolicyAsset.token(Network.NETWORK_MAINNET, "nope!") to
                                AssetLimits(perOperation = 1UL)
                        )
                )
        assertHas(policyProblems(badLimit), PolicyProblem.NotAMint)
    }

    @Test
    fun aLimitOfZeroIsRefusedBecauseItIsAnEmptyListInDisguise() {
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(limits = mapOf(SOL to AssetLimits(perOperation = 0UL)))

        assertHas(policyProblems(policy), PolicyProblem.ZeroLimit)
    }

    @Test
    fun aDailyLimitBelowThePerOperationOneIsRefused() {
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(limits = mapOf(SOL to AssetLimits(perOperation = 10UL, daily = 5UL)))

        assertHas(policyProblems(policy), PolicyProblem.DailyBelowPerOperation)
        assertEquals(
            emptyList<PolicyProblem>(),
            policyProblems(
                ConnectionPolicy.default(CONNECTION, NOW)
                    .copy(limits = mapOf(SOL to AssetLimits(perOperation = 10UL, daily = 10UL)))
            ),
        )
    }

    @Test
    fun aLimitOnAnAssetTheListDoesNotAllowIsRefused() {
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(
                    assets = Allowlist.of(PolicyAsset.token(Network.NETWORK_MAINNET, MINT)),
                    limits = mapOf(SOL to AssetLimits(perOperation = 10UL)),
                )

        assertHas(policyProblems(policy), PolicyProblem.LimitForUnlistedAsset)
    }

    @Test
    fun aPolicyForSomethingOtherThanAConnectionIsRefused() {
        assertHas(
            policyProblems(ConnectionPolicy.default("../elsewhere", NOW)),
            PolicyProblem.NotAConnection,
        )
    }

    @Test
    fun everyActionKindHasItsOwnCode() {
        val codes = PolicyAction.entries.map { it.code }

        assertEquals(codes.distinct(), codes)
        assertEquals(PolicyAction.Transfer, PolicyAction.byCode("transfer"))
        assertEquals(null, PolicyAction.byCode("stake"))
    }

    private fun <T> assertHas(problems: List<T>, problem: T) {
        assertTrue("$problem is not in $problems", problems.contains(problem))
    }

    private companion object {
        const val CONNECTION = "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11"
        const val RECIPIENT = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt"
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val SOL = PolicyAsset.sol(Network.NETWORK_MAINNET)
        val NOW: Instant = Instant.parse("2026-09-12T10:00:00Z")
    }
}
