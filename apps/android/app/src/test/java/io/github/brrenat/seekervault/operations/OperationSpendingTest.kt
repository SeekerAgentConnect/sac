package io.github.brrenat.seekervault.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedSpending
import io.github.brrenat.seekervault.activity.ReviewedTransfer
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.jupiter.orderTransaction
import io.github.brrenat.seekervault.jupiter.predictionOrder
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.DailyCheckScope
import io.github.brrenat.seekervault.policy.GlobalPolicy
import io.github.brrenat.seekervault.policy.GlobalSpendScope
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A feed operation's spending, through the real approval path (SEE-181): what the inspected bytes
 * spend is pinned before the wallet is opened, it counts towards the day afterwards — across a
 * restart — and an approval that waited for the wallet lock is judged against what was spent while
 * it waited, not against what the owner read before.
 */
@RunWith(AndroidJUnit4::class)
class OperationSpendingTest {
    @get:Rule val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun resetMain() = Dispatchers.resetMain()

    private val clock = Instant.parse("2026-09-17T10:00:00Z")
    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val usdc = PolicyAsset(Network.NETWORK_MAINNET, USDC_MINT)
    private val root by lazy { File(folder.root, "phone") }

    private fun phone() = Phone(root) { clock }

    /** Connects a wallet, reads the feed, opens the market, and prepares a [stake] on Yes. */
    private fun prepared(phone: Phone, stake: ULong): OperationViewModel = runBlocking {
        phone.markets.answersOrder = { terms, choice, wallet ->
            val built =
                orderTransaction(
                    terms = terms,
                    yes = choice.yes,
                    deposit = choice.deposit,
                    owner = wallet,
                )
            phone.chain.tables = built.tables
            predictionOrder(built.transaction, yes = choice.yes, owner = wallet)
        }
        phone.adapter.answerConnected(owner, chains = listOf(WalletNetwork.Mainnet.chain))
        phone.connectWallet(WalletNetwork.Mainnet)
        phone.feed.answers = listOf(predictionProposal())
        val model = phone.viewModel()
        model.refresh(CONNECTION)
        model.open(CONNECTION, PREDICTION_PROPOSAL)
        model.choose(
            PredictionParameterNames.OUTCOME,
            ParameterValue.Selected(PredictionOutcomes.YES),
        )
        model.choose(PredictionParameterNames.DEPOSIT, ParameterValue.Amount(stake))
        model.prepare()
        assertTrue(checkNotNull(model.review.value).inspection?.approvable == true)
        model
    }

    private fun globalDaily(phone: Phone, limit: ULong) =
        phone.policies.putGlobal(
            GlobalPolicy.default(clock).copy(limits = mapOf(usdc to AssetLimits(daily = limit)))
        )

    @Test
    fun whatTheOrderSpendsIsOnDiskBeforeTheWalletIsAskedAndCountsAfterARestart() = runBlocking {
        val phone = phone()
        globalDaily(phone, 10_000_000UL)
        val model = prepared(phone, stake = 6_000_000UL)
        var atHandoff: ActivityRecord? = null
        phone.adapter.beforeSending = {
            // The moment the wallet is handed the bytes: read the disk, not this process's memory.
            atHandoff =
                ActivityStore(File(root, "activity")).snapshot().records.single {
                    it.kind == ActivityKind.Operation
                }
        }
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 7 }))

        model.approve(phone.wallet.walletFor(CONNECTION))

        val expected =
            ReviewedSpending.Outgoing(owner, Network.NETWORK_MAINNET, USDC_MINT, 6_000_000UL)
        val pinned = checkNotNull(atHandoff)
        assertEquals(ActivityOutcome.Waiting, pinned.outcome)
        assertEquals(expected, pinned.operation?.spending)
        // The binding keeps it too, in the proposal's own record.
        assertEquals(
            expected,
            ProposalStore(File(root, "proposals"))
                .get(CONNECTION, PREDICTION_PROPOSAL)
                ?.execution
                ?.binding
                ?.spending,
        )

        // The next launch: a new store over the same files. Sent is exposure, not yet spending.
        val restarted =
            PolicyEvaluator(
                phone.policies,
                records = { ActivityStore(File(root, "activity")).snapshot().records },
                now = { clock },
            )
        val today = checkNotNull(restarted.spentToday(GlobalSpendScope(owner, usdc)))
        assertEquals(6_000_000UL, today.unresolved)
        assertEquals(0UL, today.confirmed)
        assertTrue(today.known)
    }

    @Test
    fun anApprovalThatWaitedForTheWalletSeesWhatWasSpentMeanwhileAndAsksAgain() = runBlocking {
        val phone = phone()
        globalDaily(phone, 10_000_000UL)
        val model = prepared(phone, stake = 6_000_000UL)
        // What the owner read: 6 of 10 today, nothing to warn about.
        val shown = checkNotNull(checkNotNull(model.review.value).assessment)
        assertFalse(shown.decision.warns)
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 7 }))

        // Another wallet interaction holds the lock — a transfer the owner is approving in the
        // wallet app — and this approval queues behind it.
        val release = CompletableDeferred<Unit>()
        val other = launch(Dispatchers.Unconfined) { phone.wallet.withWallet { release.await() } }
        model.approve(phone.wallet.walletFor(CONNECTION))
        assertTrue(checkNotNull(model.review.value).sending)

        // While it waits, that transfer is recorded: 5 USDC from the same wallet, on the same day.
        phone.history.record(transfer(amount = 5_000_000UL))
        release.complete(Unit)
        other.join()
        scheduler.advanceUntilIdle()

        // Nothing reached the wallet: the verdict moved, so the owner is shown the new one.
        assertTrue(phone.adapter.sendings.isEmpty())
        val review = checkNotNull(model.review.value)
        assertEquals(OperationProblem.RulesChanged, review.problem)
        assertNull(phone.proposals.proposal(CONNECTION, PREDICTION_PROPOSAL)?.execution)
        val fresh = checkNotNull(review.assessment)
        val global = fresh.decision.dailyChecks.single { it.scope == DailyCheckScope.Global }
        assertEquals(PolicyCheckStatus.Failed, global.result.status)
        assertEquals(PolicyReason.OverDailyLimit, global.result.reason)
        assertEquals(11_000_000UL, global.projected)
        assertTrue(fresh.decision.warns)
        assertFalse(review.acknowledged)

        // Going ahead now needs the owner's word for the new reason, and then the wallet is asked
        // exactly once.
        model.approve(phone.wallet.walletFor(CONNECTION))
        assertEquals(OperationProblem.NotAcknowledged, model.review.value?.problem)
        assertTrue(phone.adapter.sendings.isEmpty())
        model.acknowledge(true)
        model.approve(phone.wallet.walletFor(CONNECTION))
        assertEquals(1, phone.adapter.sendings.size)
        val record = phone.history.records.value.single { it.kind == ActivityKind.Operation }
        assertEquals(true, record.policy?.approvedAnyway)
    }

    /**
     * A direct transfer the same wallet sent, as the inbox records one. It is dated now because the
     * phone's evaluator reads the day off the real clock, as the app's does.
     */
    private fun transfer(amount: ULong, at: Instant = Instant.now()) =
        ActivityRecord(
            connectionId = OTHER_CONNECTION,
            requestId = UUID.nameUUIDFromBytes("transfer".toByteArray()).toString(),
            source = "Hermes",
            serverHost = "sidecar.example",
            kind = ActivityKind.Transfer,
            answeredAt = at,
            recordedAt = at,
            outcome = ActivityOutcome.Confirmed,
            transfer =
                ReviewedTransfer(
                    wallet = owner,
                    network = Network.NETWORK_MAINNET,
                    recipient = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt",
                    amount = amount.toString(),
                    mint = USDC_MINT,
                    preparedVersion = 1,
                ),
            signature = "transfer-signature",
        )

    private companion object {
        const val OTHER_CONNECTION = "9c1d7b3a-8e4f-4a52-b0c6-1d2e3f4a5b6c"
    }
}
