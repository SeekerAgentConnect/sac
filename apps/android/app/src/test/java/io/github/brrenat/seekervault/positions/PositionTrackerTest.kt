package io.github.brrenat.seekervault.positions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.ChainState
import io.github.brrenat.seekervault.confirmations.Submission
import io.github.brrenat.seekervault.confirmations.SubmissionTracking
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.jupiter.FakeChain
import io.github.brrenat.seekervault.jupiter.FakePrediction
import io.github.brrenat.seekervault.jupiter.HELD_CONTRACTS
import io.github.brrenat.seekervault.jupiter.JupiterExecutionProvider
import io.github.brrenat.seekervault.jupiter.JupiterProvider
import io.github.brrenat.seekervault.jupiter.JupiterQuote
import io.github.brrenat.seekervault.jupiter.OWNER
import io.github.brrenat.seekervault.jupiter.OrderFill
import io.github.brrenat.seekervault.jupiter.PredictionException
import io.github.brrenat.seekervault.jupiter.PredictionOrderStatus
import io.github.brrenat.seekervault.jupiter.PredictionProblem
import io.github.brrenat.seekervault.jupiter.SALE_ORDER
import io.github.brrenat.seekervault.jupiter.held
import io.github.brrenat.seekervault.jupiter.predictionClose
import io.github.brrenat.seekervault.jupiter.predictionPosition
import io.github.brrenat.seekervault.jupiter.saleTransaction
import io.github.brrenat.seekervault.jupiter.wallet
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.positions.storage.PositionStore
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.WalletSession
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The position and sale lifecycle (SEE-172, acceptance criteria 3, 5, 7, 8 and 9).
 *
 * The provider is the real Jupiter one over a scripted API and a recorded chain, so a sale here is
 * built, resolved and read exactly as on a phone; only the wallet is a script. The invariant every
 * case checks in its own way: **the wallet is asked at most once per reviewed sale, and never by
 * anything but the owner's Sell.**
 */
@RunWith(AndroidJUnit4::class)
class PositionTrackerTest {
    @get:Rule val folder = TemporaryFolder()

    private var now: Instant = Instant.parse("2026-09-28T08:00:00Z")
    private val api = FakePrediction()
    private val sale = saleTransaction()
    private val registry =
        ProviderRegistry.of(JupiterExecutionProvider(NoSwaps, api, FakeChain(sale.tables)) { now })
    private val tracking = RecordingTracking()
    private var chainChecks: Map<RequestKey, ChainCheck> = emptyMap()
    private var selected: SelectedWallet? = wallet()
    private val session = ScriptedSession()
    private val directory get() = File(folder.root, "positions")

    private fun tracker(store: PositionStore = PositionStore(directory)) =
        PositionTracker(
            store = store,
            providers = { registry },
            tracking = tracking,
            chainChecks = { chainChecks },
            now = { now },
            io = Dispatchers.Unconfined,
            gate = ReadGate(Duration.ZERO, Duration.ZERO, { now }, {}),
        )

    private fun candidate(proposal: String = "p1", at: Instant = now.minusSeconds(60)) =
        PurchaseCandidate(
            held = held(),
            purchase =
                PositionPurchase(
                    connectionId = CONNECTION,
                    proposalId = proposal,
                    orderAccount = io.github.brrenat.seekervault.jupiter.ORDER_PUBKEY,
                    signature = "sig",
                    boughtAt = at,
                    depositBaseUnits = 5_000_000UL,
                ),
        )

    private val account = io.github.brrenat.seekervault.jupiter.POSITION_PUBKEY

    private fun ready(tracker: PositionTracker): SaleDraft {
        api.answersPosition = { predictionPosition() }
        api.answersClose = { _, _ -> predictionClose(sale) }
        val review = runBlocking { tracker.prepareSale(account, selected) }
        return (review as SaleReviewState.Ready).draft.also {
            assertTrue(it.prepared.inspection.findings.toString(), it.prepared.inspection.approvable)
        }
    }

    private fun PositionTracker.sellNow(draft: SaleDraft): SellOutcome = runBlocking {
        sell(draft, { selected }, { block -> block(session) })
    }

    @Test
    fun linkingIsIdempotentNeverRetargetsAndRespectsAClearedHistory() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate("p1"), candidate("p1"), candidate("p2")))
        val holding = checkNotNull(tracker.state.value.holdings[account])
        // Two purchases, one position: the wallet's aggregate holding, followed once.
        assertEquals(listOf("p1", "p2"), holding.purchases.map { it.proposalId })

        // Another owner naming the same account is ignored rather than merged.
        val stranger =
            candidate("p3").copy(held = held(owner = "7ToYommiXbxMdd7KaT8wgFYGzuWeHmGBvscGrgFTEWL2"))
        tracker.link(listOf(stranger))
        assertEquals(OWNER, tracker.state.value.holdings.getValue(account).held.owner)
        assertEquals(2, tracker.state.value.holdings.getValue(account).purchases.size)

        // Cleared History: a purchase from before the clear is one the owner deleted.
        tracker.clear()
        now = now.plusSeconds(1)
        tracker.link(listOf(candidate("p1", at = now.minusSeconds(3_600))))
        assertTrue(tracker.state.value.holdings.isEmpty())
        // And a restart does not bring it back either.
        val restarted = tracker().apply { load() }
        assertTrue(restarted.state.value.holdings.isEmpty())
    }

    @Test
    fun twoPurchasesOfOnePositionAreReadOnce() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate("p1"), candidate("p2")))
        api.answersPosition = { predictionPosition() }
        runBlocking { tracker.refresh(account, force = true) }
        assertEquals(1, api.asked.count { it.startsWith("position") })
        assertEquals(HELD_CONTRACTS, tracker.state.value.holdings.getValue(account).snapshot?.contractsMicro)
    }

    @Test
    fun concurrentScreensShareOneRead() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        api.answersPosition = {
            Thread.sleep(300)
            predictionPosition()
        }
        runBlocking(Dispatchers.Default) {
            (1..3).map { async { tracker.refresh(account, force = true) } }.awaitAll()
        }
        assertEquals(1, api.asked.count { it.startsWith("position") })
    }

    @Test
    fun aFailedReadKeepsTheLastSnapshotAndNotFoundIsNeverZero() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        api.answersPosition = { predictionPosition() }
        runBlocking { tracker.refresh(account, force = true) }
        val good = checkNotNull(tracker.state.value.holdings[account]).snapshot

        for (problem in listOf(PredictionProblem.RateLimited, PredictionProblem.Unreachable, PredictionProblem.Unusable, PredictionProblem.NotFound)) {
            now = now.plusSeconds(60)
            api.answersPosition = { throw PredictionException(problem) }
            runBlocking { tracker.refresh(account, force = true) }
            val holding = checkNotNull(tracker.state.value.holdings[account])
            // The last good read stands, marked by the problem and its age; nothing became zero.
            assertEquals(good, holding.snapshot)
            assertNotNull(holding.problem)
        }
        assertEquals(RefreshProblem.NotFound, tracker.state.value.holdings.getValue(account).problem)
    }

    @Test
    fun aReviewedSaleIsWrittenDownBeforeTheWalletAndSentOnce() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        val draft = ready(tracker)
        session.answer = {
            // At the moment the wallet is asked, the attempt is already on disk and tracked.
            val stored = PositionStore(directory).sales().single()
            assertEquals(SaleStage.Signing, stored.stage)
            assertTrue(tracking.calls.any { it.startsWith("expect") })
            SendResult.Sent(ByteString.copyFrom(ByteArray(64) { 9 }))
        }

        assertEquals(SellOutcome.Handed, tracker.sellNow(draft))

        assertEquals(listOf(draft.prepared.transaction), session.sent)
        val stored = tracker.state.value.sales.single()
        assertEquals(SaleStage.Submitted, stored.stage)
        assertNotNull(stored.signature)
        assertEquals(SALE_ORDER, stored.orderAccount)
        assertEquals(HELD_CONTRACTS, stored.contractsMicro)
        assertEquals(SaleResult.Pending, stored.result)
        assertEquals(PositionTracker.saleKey(stored.id), tracking.submittedKey)
        // The review is spent: approving again needs a new one.
        assertNull(tracker.state.value.reviews[account])
        assertEquals(SellOutcome.Stale, tracker.sellNow(draft))
        assertEquals(1, session.sent.size)
    }

    @Test
    fun aDeclineChangesNothingAndCanBeRetriedDeliberately() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        session.answer = { SendResult.Declined }
        assertEquals(SellOutcome.Handed, tracker.sellNow(ready(tracker)))
        val declined = tracker.state.value.sales.single()
        assertEquals(SaleStage.Declined, declined.stage)
        assertEquals(SaleResult.NotExecuted, declined.result)
        assertEquals(listOf("expect", "abandoned"), tracking.calls.map { it.substringBefore(' ') })

        // Not busy: the owner may prepare and review again, and a new attempt is its own record.
        session.answer = { SendResult.Sent(ByteString.copyFrom(ByteArray(64) { 3 })) }
        assertEquals(SellOutcome.Handed, tracker.sellNow(ready(tracker)))
        assertEquals(2, tracker.state.value.sales.size)
        assertEquals(2, session.sent.size)
    }

    @Test
    fun repeatedTapsHandTheSaleToTheWalletOnce() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        val draft = ready(tracker)
        val release = CompletableDeferred<Unit>()
        session.suspendUntil = release
        session.answer = { SendResult.Sent(ByteString.copyFrom(ByteArray(64) { 1 })) }
        val outcomes = runBlocking(Dispatchers.Default) {
            val first = async { tracker.sell(draft, { selected }, { block -> block(session) }) }
            while (session.sent.isEmpty()) Thread.sleep(10)
            val second = async { tracker.sell(draft, { selected }, { block -> block(session) }) }
            val secondOutcome = second.await()
            release.complete(Unit)
            listOf(first.await(), secondOutcome)
        }
        assertEquals(listOf(SellOutcome.Handed, SellOutcome.Busy), outcomes)
        assertEquals(1, session.sent.size)
        assertEquals(1, tracker.state.value.sales.size)
    }

    @Test
    fun aPositionThatChangedAfterReviewGoesBackToReview() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        val draft = ready(tracker)
        // Bought more elsewhere, or sold in Jupiter: the reviewed quantity is no longer the whole.
        api.answersPosition = { predictionPosition(contracts = HELD_CONTRACTS + 5_000_000UL) }
        assertEquals(SellOutcome.Changed, tracker.sellNow(draft))
        api.answersPosition = { throw PredictionException(PredictionProblem.NotFound) }
        assertEquals(SellOutcome.Stale, tracker.sellNow(draft))
        assertTrue(session.sent.isEmpty())
        assertTrue(tracker.state.value.sales.isEmpty())
    }

    @Test
    fun anExpiredReviewOrAnotherWalletIsNeverSigned() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        val draft = ready(tracker)
        selected = wallet("7ToYommiXbxMdd7KaT8wgFYGzuWeHmGBvscGrgFTEWL2")
        assertEquals(SellOutcome.WrongWallet, tracker.sellNow(draft))
        selected = wallet()
        now = now.plusSeconds(61)
        assertEquals(SellOutcome.Stale, tracker.sellNow(draft))
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun aSettledOrClosedPositionIsNotPrepared() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        for (position in
            listOf(
                predictionPosition(claimable = true),
                predictionPosition(result = "yes"),
                predictionPosition(status = "closed"),
                predictionPosition(contracts = 0UL),
                predictionPosition(openOrders = 1),
                predictionPosition(bid = null),
            )) {
            api.asked.clear()
            api.answersPosition = { position }
            val review = runBlocking { tracker.prepareSale(account, selected) }
            assertTrue("$position was prepared", review is SaleReviewState.Refused)
            assertFalse(api.asked.any { it.startsWith("close") })
        }
    }

    @Test
    fun anAnswerThatNeverCameIsReconciledByReadingNeverBySendingAgain() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        session.answer = { SendResult.Unknown("the wallet went away") }
        tracker.sellNow(ready(tracker))
        assertEquals(SaleStage.Unresolved, tracker.state.value.sales.single().stage)
        // Unknown keeps the chain capture: it may have been sent.
        assertFalse(tracking.calls.any { it.startsWith("abandoned") })

        // Nothing is known yet: still pending, and no second sale may be prepared.
        api.answersStatus = { throw PredictionException(PredictionProblem.NotFound) }
        runBlocking { tracker.reconcile() }
        assertEquals(SaleResult.Pending, tracker.state.value.sales.single().result)
        assertTrue(runBlocking { tracker.prepareSale(account, selected) } is SaleReviewState.Refused)

        // Then the provider reports the order filled, and the position is gone.
        api.answersStatus = { status(it, OrderFill.Filled, net = 21_000_000UL) }
        api.answersPosition = { throw PredictionException(PredictionProblem.NotFound) }
        runBlocking { tracker.reconcile() }
        val settled = tracker.state.value.sales.single()
        assertEquals(SaleResult.Closed, settled.result)
        assertEquals(21_000_000UL, settled.order?.reading?.netProceedsMicroUsd)
        assertEquals(1, session.sent.size)
    }

    @Test
    fun aPartialSaleLeavesAResidualPosition() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        session.answer = { SendResult.Sent(ByteString.copyFrom(ByteArray(64) { 2 })) }
        tracker.sellNow(ready(tracker))
        api.answersStatus = { status(it, OrderFill.PartiallyFilledClosed) }
        api.answersPosition = { predictionPosition(contracts = 10_000_000UL) }
        runBlocking { tracker.reconcile() }
        assertEquals(SaleResult.Residual, tracker.state.value.sales.single().result)
        assertEquals(10_000_000UL, tracker.state.value.holdings.getValue(account).snapshot?.contractsMicro)
    }

    @Test
    fun aSaleThatFailedOnChainSoldNothing() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        session.answer = { SendResult.Sent(ByteString.copyFrom(ByteArray(64) { 2 })) }
        tracker.sellNow(ready(tracker))
        val id = tracker.state.value.sales.single().id
        chainChecks = mapOf(PositionTracker.saleKey(id) to ChainCheck(ChainState.Failed))
        api.answersStatus = { throw PredictionException(PredictionProblem.NotFound) }
        runBlocking { tracker.reconcile() }
        assertEquals(SaleResult.NotExecuted, tracker.state.value.sales.single().result)
    }

    @Test
    fun processDeathMidHandoffIsUnresolvedAndNeverResent() {
        val store = PositionStore(directory)
        val first = tracker(store).apply { load() }
        first.link(listOf(candidate()))
        val draft = ready(first)
        // The wallet was open when the app died: the stored attempt says Signing.
        session.answer = { throw kotlinx.coroutines.CancellationException("process died") }
        runCatching { first.sellNow(draft) }
        assertEquals(SaleStage.Signing, store.sales().single().stage)

        val restarted = tracker(PositionStore(directory)).apply { load() }
        val recovered = restarted.state.value.sales.single()
        assertEquals(SaleStage.Unresolved, recovered.stage)
        assertTrue(restarted.unresolved())
        assertTrue(runBlocking { restarted.prepareSale(account, selected) } is SaleReviewState.Refused)

        // No order ever appears; once the transaction can no longer land, the attempt lapses.
        api.answersStatus = { throw PredictionException(PredictionProblem.NotFound) }
        api.answersPosition = { predictionPosition() }
        runBlocking { restarted.reconcile() }
        assertEquals(SaleResult.Pending, restarted.state.value.sales.single().result)
        now = now.plus(SALE_LAPSE).plusSeconds(1)
        runBlocking { restarted.reconcile() }
        assertEquals(SaleResult.Lapsed, restarted.state.value.sales.single().result)
        assertEquals(1, session.sent.size)
    }

    @Test
    fun clearingHistoryStopsTheSaleFromBeingWrittenOrSent() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        val draft = ready(tracker)
        tracker.clear()
        assertEquals(SellOutcome.Stale, tracker.sellNow(draft))
        assertTrue(session.sent.isEmpty())
        assertTrue(PositionStore(directory).sales().isEmpty())
    }

    private fun status(order: String, fill: OrderFill, net: ULong? = null) =
        PredictionOrderStatus(
            orderPubkey = order,
            fill = fill,
            rawStatus = fill.name.lowercase(),
            finished = fill != OrderFill.Pending && fill != OrderFill.PartiallyFilled,
            contractsMicro = HELD_CONTRACTS,
            filledContractsMicro = HELD_CONTRACTS,
            avgFillPriceUsd = 340_000UL,
            netProceedsUsd = net,
            feeUsd = 1_000_000UL,
            latestSignature = null,
        )

    private class RecordingTracking : SubmissionTracking {
        val calls = mutableListOf<String>()
        var submittedKey: RequestKey? = null

        override fun expect(submission: Submission) {
            calls += "expect ${submission.key}"
        }

        override fun submitted(key: RequestKey, signature: ByteArray) {
            calls += "submitted $key"
            submittedKey = key
        }

        override fun recovered(key: RequestKey, signature: ByteArray, at: Instant) {
            calls += "recovered $key"
        }

        override fun abandoned(key: RequestKey) {
            calls += "abandoned $key"
        }
    }

    private class ScriptedSession : WalletSession {
        val sent = mutableListOf<ByteString>()
        var answer: () -> SendResult = { SendResult.Declined }
        var suspendUntil: CompletableDeferred<Unit>? = null

        override suspend fun signAndSend(transaction: ByteString, reviewed: SelectedWallet): SendResult {
            sent += transaction
            suspendUntil?.await()
            return answer()
        }
    }

    private object NoSwaps : JupiterProvider {
        override suspend fun quote(terms: SwapPayload, amount: ULong, slippageBps: Int): JupiterQuote =
            throw AssertionError("a position asked for a swap")

        override suspend fun build(quote: JupiterQuote, wallet: String) =
            throw AssertionError("a position asked for a swap")
    }

    private companion object {
        const val CONNECTION = "5b1f4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b"
    }
}
