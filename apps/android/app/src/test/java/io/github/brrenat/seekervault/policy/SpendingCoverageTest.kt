package io.github.brrenat.seekervault.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedOperation
import io.github.brrenat.seekervault.activity.ReviewedSpending
import io.github.brrenat.seekervault.activity.ReviewedStaking
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.activity.storage.decodeSpending
import io.github.brrenat.seekervault.activity.storage.encodeSpending
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.skr.SKR_MINT
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Daily spending coverage beyond direct transfers (SEE-181,
 * docs/policy.md#what-counts-as-spending): feed swaps and prediction buys, SKR staking per action,
 * and records written before any of them were counted. Each test goes through persisted Activity —
 * a real store on disk, read back by a fresh store as the next launch would — and the evaluator the
 * review screens call, not only the counting helper.
 */
@RunWith(AndroidJUnit4::class)
class SpendingCoverageTest {
    @get:Rule val folder = TemporaryFolder()

    private val policies by lazy { PolicyStore(File(folder.root, "policies").apply { mkdirs() }) }
    private val activityDir by lazy { File(folder.root, "activity").apply { mkdirs() } }
    private val today = NOW.atZone(ZoneOffset.UTC).toLocalDate()

    /** A new store over the same directory every time: what is counted is what is on disk. */
    private fun evaluator() =
        PolicyEvaluator(
            policies,
            records = { ActivityStore(activityDir).snapshot().records },
            now = { NOW },
            zone = { ZoneOffset.UTC },
        )

    private fun store(vararg records: ActivityRecord) = records.forEach {
        ActivityStore(activityDir).put(it)
    }

    private fun globalDaily(asset: PolicyAsset, limit: ULong) =
        policies.putGlobal(
            GlobalPolicy.default(NOW).copy(limits = mapOf(asset to AssetLimits(daily = limit)))
        )

    private fun daily(decision: PolicyDecision, scope: DailyCheckScope): DailyPolicyCheck =
        decision.dailyChecks.single { it.scope == scope }

    @Test
    fun confirmedPredictionBuysAccumulateUntilTheNextOneGoesOver() {
        // The ticket's own sequence: 20 USDC buys under a 100 USDC global daily threshold.
        globalDaily(USDC, 100UL * USDC_UNIT)
        val projected = mutableListOf<ULong?>()
        for (index in 1..6) {
            val decision = evaluator().evaluate(predictionFacts("p$index"))
            val global = daily(decision, DailyCheckScope.Global)
            projected += global.projected
            if (index <= 5) {
                // Equal to the threshold is still within it.
                assertEquals("buy $index", PolicyCheckStatus.Passed, global.result.status)
                assertTrue("buy $index", decision.allowed)
            } else {
                assertEquals(PolicyCheckStatus.Failed, global.result.status)
                assertEquals(PolicyReason.OverDailyLimit, global.result.reason)
                assertTrue(decision.warns)
            }
            store(operation("p$index", signature = "sig-$index"))
        }
        assertEquals(listOf(20, 40, 60, 80, 100, 120).map { it.toULong() * USDC_UNIT }, projected)
    }

    @Test
    fun aSwapCountsItsInspectedInputAndNothingItReceives() {
        // 1 SOL in, USDC out. The SOL leaves; the USDC that arrives is not spending of anything.
        store(
            operation(
                "swap",
                spending =
                    ReviewedSpending.Outgoing(WALLET, Network.NETWORK_MAINNET, null, ONE_SOL),
            )
        )
        val evaluator = evaluator()

        val sol = checkNotNull(evaluator.spentToday(GlobalSpendScope(WALLET, SOL)))
        val usdc = checkNotNull(evaluator.spentToday(GlobalSpendScope(WALLET, USDC)))

        assertEquals(ONE_SOL, sol.confirmed)
        assertEquals(1, sol.confirmedCount)
        assertEquals(DailyTotal.none(GlobalSpendScope(WALLET, USDC), today), usdc)
    }

    @Test
    fun anOperationThatSpendsNothingIsNotCounted() {
        // A prediction sale: contracts leave, dollars arrive. Nothing of the owner's is spent.
        store(operation("sale", spending = ReviewedSpending.None))

        val total = checkNotNull(evaluator().spentToday(GlobalSpendScope(WALLET, USDC)))

        assertEquals(DailyTotal.none(GlobalSpendScope(WALLET, USDC), today), total)
    }

    @Test
    fun onlyAStakeSpendsSkrAndTheSamePrincipalIsNeverCountedAgain() {
        // Stake 500, unstake it, cancel, unstake again, withdraw: one movement out of the wallet.
        store(
            staking("stake", StakingOperation.STAKING_OPERATION_STAKE, 500UL),
            staking("unstake", StakingOperation.STAKING_OPERATION_UNSTAKE, 500UL),
            staking("cancel", StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE, 500UL),
            staking("unstake-again", StakingOperation.STAKING_OPERATION_UNSTAKE, 500UL),
            staking("withdraw", StakingOperation.STAKING_OPERATION_WITHDRAW, 500UL),
        )

        val total = checkNotNull(evaluator().spentToday(GlobalSpendScope(WALLET, SKR)))

        assertEquals(500UL, total.confirmed)
        assertEquals(1, total.confirmedCount)
        assertTrue(total.known)
    }

    @Test
    fun aWithdrawalAddsNothingToTheDayAndTakesNothingOffIt() {
        globalDaily(SKR, 500UL)
        store(staking("stake", StakingOperation.STAKING_OPERATION_STAKE, 600UL))

        val withdrawal = evaluator().evaluate(skrFacts("withdraw", 600UL, incoming = true))
        val stake = evaluator().evaluate(skrFacts("stake-more", 1UL, incoming = false))

        // Already over the threshold today, and SKR coming back changes neither fact: the
        // withdrawal is not warned about, and the next stake still is.
        val withdrawn = daily(withdrawal, DailyCheckScope.Global)
        assertEquals(PolicyCheckStatus.Passed, withdrawn.result.status)
        assertEquals(600UL, withdrawn.projected)
        assertEquals(
            PolicyReason.OverDailyLimit,
            daily(stake, DailyCheckScope.Global).result.reason,
        )
    }

    @Test
    fun globalTotalsSpanConnectionsWhileEachConnectionKeepsItsOwn() {
        store(
            operation("here", amount = 30UL * USDC_UNIT),
            operation("there", connectionId = OTHER_CONNECTION, amount = 40UL * USDC_UNIT),
            // Isolated: another wallet, another chain, another asset.
            operation("other-wallet", wallet = OTHER_WALLET, amount = 1_000UL * USDC_UNIT),
            operation("devnet", network = Network.NETWORK_DEVNET, amount = 1_000UL * USDC_UNIT),
            operation("other-mint", mint = OTHER_MINT, amount = 1_000UL * USDC_UNIT),
        )
        val evaluator = evaluator()

        assertEquals(
            70UL * USDC_UNIT,
            checkNotNull(evaluator.spentToday(GlobalSpendScope(WALLET, USDC))).confirmed,
        )
        assertEquals(
            30UL * USDC_UNIT,
            checkNotNull(evaluator.spentToday(SpendScope(CONNECTION, WALLET, USDC))).confirmed,
        )
        assertEquals(
            40UL * USDC_UNIT,
            checkNotNull(evaluator.spentToday(SpendScope(OTHER_CONNECTION, WALLET, USDC)))
                .confirmed,
        )
    }

    @Test
    fun anUnresolvedOperationMovesToConfirmedWithoutBeingCountedTwice() {
        val evaluator = evaluator()
        // Pinned before the wallet: the process can die here and the exposure survives.
        store(operation("buy", outcome = ActivityOutcome.Waiting))
        val waiting = checkNotNull(evaluator.spentToday(GlobalSpendScope(WALLET, USDC)))
        assertEquals(20UL * USDC_UNIT, waiting.unresolved)
        assertEquals(0UL, waiting.confirmed)

        // The wallet sent it, then the chain confirmed it, and the status was checked repeatedly.
        store(operation("buy", outcome = ActivityOutcome.Sent, signature = "sig"))
        repeat(3) {
            store(operation("buy", outcome = ActivityOutcome.Confirmed, signature = "sig"))
        }
        val confirmed = checkNotNull(evaluator.spentToday(GlobalSpendScope(WALLET, USDC)))

        assertEquals(20UL * USDC_UNIT, confirmed.confirmed)
        assertEquals(0UL, confirmed.unresolved)
        assertEquals(20UL * USDC_UNIT, confirmed.projected)
    }

    @Test
    fun oneSignatureIsOneMovementAcrossRecordsAndConnections() {
        store(
            operation("a", outcome = ActivityOutcome.Sent, signature = "same"),
            operation(
                "b",
                connectionId = OTHER_CONNECTION,
                outcome = ActivityOutcome.Confirmed,
                signature = "same",
            ),
        )

        val total = checkNotNull(evaluator().spentToday(GlobalSpendScope(WALLET, USDC)))

        assertEquals(20UL * USDC_UNIT, total.projected)
        assertEquals(1, total.confirmedCount)
    }

    @Test
    fun theOperationUnderReviewIsNotCountedAgainstItself() {
        globalDaily(USDC, 20UL * USDC_UNIT)
        // A record for the very proposal being assessed again, e.g. a review reopened after an
        // earlier attempt: it is projected once, as "now", not also as part of the day.
        store(operation("p1", outcome = ActivityOutcome.Waiting))

        val decision = evaluator().evaluate(predictionFacts("p1"))

        assertEquals(20UL * USDC_UNIT, daily(decision, DailyCheckScope.Global).projected)
        assertTrue(decision.allowed)
    }

    @Test
    fun aRehearsalADeclineOrAFailureSpendsNothing() {
        store(
            operation("simulated", outcome = ActivityOutcome.Simulated),
            operation("declined", outcome = ActivityOutcome.DeclinedInWallet),
            operation("failed", outcome = ActivityOutcome.NotSigned),
            operation("chain-failed", outcome = ActivityOutcome.ChainFailed, signature = "s"),
        )

        val total = checkNotNull(evaluator().spentToday(GlobalSpendScope(WALLET, USDC)))

        assertEquals(DailyTotal.none(GlobalSpendScope(WALLET, USDC), today), total)
    }

    @Test
    fun anOperationRecordedBeforeSpendingWasKeptMakesTheDayUnknownNotZero() {
        globalDaily(USDC, 100UL * USDC_UNIT)
        store(operation("legacy", spending = null))

        val total = checkNotNull(evaluator().spentToday(GlobalSpendScope(WALLET, USDC)))
        val decision = evaluator().evaluate(predictionFacts("p1"))

        assertFalse(total.known)
        assertEquals(1, total.uncounted)
        assertEquals(0, total.unreadable)
        val global = daily(decision, DailyCheckScope.Global)
        assertEquals(PolicyCheckStatus.Unverified, global.result.status)
        assertEquals(PolicyReason.DailyTotalUnverified, global.result.reason)
        assertEquals(
            "1 of today's operations were recorded without what they spent",
            global.result.detail,
        )
        assertTrue(decision.warns)
    }

    @Test
    fun aLegacyOperationOnlyCloudsItsOwnWalletAndChainAndOnlyWhileItCouldHaveSpent() {
        store(
            operation("legacy-other-wallet", wallet = OTHER_WALLET, spending = null),
            operation("legacy-devnet", network = Network.NETWORK_DEVNET, spending = null),
            operation("legacy-rehearsal", outcome = ActivityOutcome.Simulated, spending = null),
            operation(
                "legacy-yesterday",
                answeredAt = NOW.minusSeconds(24 * 60 * 60),
                spending = null,
            ),
        )

        val total = checkNotNull(evaluator().spentToday(GlobalSpendScope(WALLET, USDC)))

        assertTrue(total.known)
        assertEquals(DailyTotal.none(GlobalSpendScope(WALLET, USDC), today), total)
    }

    @Test
    fun aStakeRecordWithNoTermsIsUnknownSpendingAnywhere() {
        store(
            ActivityRecord(
                connectionId = CONNECTION,
                requestId = id("old-stake"),
                source = "Hermes",
                serverHost = "sidecar.example",
                kind = ActivityKind.Staking,
                answeredAt = NOW,
                recordedAt = NOW,
                outcome = ActivityOutcome.Confirmed,
                signature = "sig",
            )
        )

        val total = checkNotNull(evaluator().spentToday(GlobalSpendScope(WALLET, USDC)))

        assertEquals(1, total.uncounted)
        assertFalse(total.known)
    }

    @Test
    fun theInspectedSpendingIsWhatTheFactsSay() {
        assertEquals(
            ReviewedSpending.Outgoing(WALLET, Network.NETWORK_MAINNET, MINT, 20UL * USDC_UNIT),
            predictionFacts("p1").spending(),
        )
        assertEquals(ReviewedSpending.None, skrFacts("w", 5UL, incoming = true).spending())
        assertEquals(
            ReviewedSpending.None,
            RequestFacts.movesNothing(CONNECTION, PolicyAction.Prediction).spending(),
        )
        // What the bytes don't establish is not recorded as nothing.
        assertNull(RequestFacts.unread(CONNECTION, PolicyAction.Swap).spending())
        assertNull(predictionFacts("p1").copy(amount = null).spending())
    }

    @Test
    fun aSpendingThisBuildCannotReadIsUnknownNotNothing() {
        val largest =
            ReviewedSpending.Outgoing(WALLET, Network.NETWORK_MAINNET, MINT, ULong.MAX_VALUE)
        assertEquals(largest, decodeSpending(encodeSpending(largest)))
        assertEquals(ReviewedSpending.None, decodeSpending(encodeSpending(ReviewedSpending.None)))
        // A kind a later build named, or an amount that is not a whole number of base units, is
        // "the record does not say" — which the counters treat as unknown, never as zero.
        assertNull(decodeSpending(JSONObject().put("kind", "refund")))
        assertNull(
            decodeSpending(JSONObject(encodeSpending(largest).toString()).put("amount", "-1"))
        )
        assertNull(decodeSpending(null))
    }

    private fun predictionFacts(proposalId: String, amount: ULong = 20UL * USDC_UNIT) =
        RequestFacts(
            connectionId = CONNECTION,
            requestId = id(proposalId),
            wallet = WALLET,
            action = PolicyAction.Prediction,
            movesValue = true,
            asset = USDC,
            recipient = STRANGER,
            programs = TOKEN_PROGRAMS,
            amount = amount,
            decimals = TOKEN_DECIMALS,
            fullyRead = true,
            preparedVersion = 1,
        )

    private fun skrFacts(requestId: String, amount: ULong, incoming: Boolean) =
        RequestFacts(
            connectionId = CONNECTION,
            requestId = id(requestId),
            wallet = WALLET,
            action = PolicyAction.Staking,
            movesValue = true,
            asset = SKR,
            recipient = WALLET,
            programs = TOKEN_PROGRAMS,
            amount = amount,
            decimals = TOKEN_DECIMALS,
            fullyRead = true,
            preparedVersion = 1,
            incoming = incoming,
        )

    private fun operation(
        proposalId: String,
        connectionId: String = CONNECTION,
        wallet: String = WALLET,
        network: Network = Network.NETWORK_MAINNET,
        mint: String? = MINT,
        amount: ULong = 20UL * USDC_UNIT,
        outcome: ActivityOutcome = ActivityOutcome.Confirmed,
        signature: String? = null,
        answeredAt: Instant = NOW,
        spending: ReviewedSpending? = ReviewedSpending.Outgoing(wallet, network, mint, amount),
    ) =
        ActivityRecord(
            connectionId = connectionId,
            requestId = id(proposalId),
            source = "CopyTrading",
            serverHost = "gateway.example",
            kind = ActivityKind.Operation,
            answeredAt = answeredAt,
            recordedAt = answeredAt,
            outcome = outcome,
            operation =
                ReviewedOperation(
                    operation = "prediction",
                    plugin = "jupiter.prediction",
                    contract = 1,
                    revision = 1,
                    wallet = wallet,
                    network = network,
                    environment = PluginEnvironment.Production,
                    preparedVersion = 1,
                    spending = spending,
                ),
            signature = signature,
        )

    private fun staking(requestId: String, operation: StakingOperation, amount: ULong) =
        ActivityRecord(
            connectionId = CONNECTION,
            requestId = id(requestId),
            source = "Hermes",
            serverHost = "sidecar.example",
            kind = ActivityKind.Staking,
            answeredAt = NOW,
            recordedAt = NOW,
            outcome = ActivityOutcome.Confirmed,
            staking =
                ReviewedStaking(
                    wallet = WALLET,
                    network = Network.NETWORK_MAINNET,
                    operation = operation.name,
                    amount = amount.toString(),
                ),
            signature = "sig-$requestId",
        )

    private companion object {
        const val USDC_UNIT = 1_000_000UL

        /** Activity files are named by request ID, so every ID is a UUID; this one is stable. */
        fun id(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray()).toString()

        val SKR = PolicyAsset.token(Network.NETWORK_MAINNET, SKR_MINT)
    }
}
