package io.github.brrenat.seekervault.positions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.ChainReader
import io.github.brrenat.seekervault.confirmations.ChainState
import io.github.brrenat.seekervault.confirmations.ChainTransaction
import io.github.brrenat.seekervault.confirmations.SignatureStatus
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
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.recentBlockhashOf
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
    private var ledger: Ledger? = null
    private val directory
        get() = File(folder.root, "positions")

    private fun tracker(store: PositionStore = PositionStore(directory)) =
        PositionTracker(
            store = store,
            providers = { registry },
            tracking = tracking,
            chainChecks = { chainChecks },
            now = { now },
            io = Dispatchers.Unconfined,
            gate = ReadGate(Duration.ZERO, Duration.ZERO, { now }, {}),
            chain = ledger?.let { reader -> { _ -> reader } },
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
            assertTrue(
                it.prepared.inspection.findings.toString(),
                it.prepared.inspection.approvable,
            )
        }
    }

    private fun PositionTracker.sellNow(draft: SaleDraft): SellOutcome = runBlocking {
        sell(draft, { selected }, { block -> block(session) })
    }

    /**
     * Sells [draft] with the wallet's lock held by something else until [meanwhile] has run: the
     * sale is past every check made before the lock and waiting for it.
     */
    private fun PositionTracker.sellAfterWaiting(
        draft: SaleDraft,
        meanwhile: () -> Unit,
    ): SellOutcome = runBlocking {
        val held = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val selling =
            async(Dispatchers.Unconfined) {
                sell(
                    draft,
                    { selected },
                    { block ->
                        waiting.complete(Unit)
                        held.await()
                        block(session)
                    },
                )
            }
        waiting.await()
        meanwhile()
        held.complete(Unit)
        selling.await()
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
            candidate("p3")
                .copy(held = held(owner = "7ToYommiXbxMdd7KaT8wgFYGzuWeHmGBvscGrgFTEWL2"))
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
        assertEquals(
            HELD_CONTRACTS,
            tracker.state.value.holdings.getValue(account).snapshot?.contractsMicro,
        )
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

        for (problem in
            listOf(
                PredictionProblem.RateLimited,
                PredictionProblem.Unreachable,
                PredictionProblem.Unusable,
                PredictionProblem.NotFound,
            )) {
            now = now.plusSeconds(60)
            api.answersPosition = { throw PredictionException(problem) }
            runBlocking { tracker.refresh(account, force = true) }
            val holding = checkNotNull(tracker.state.value.holdings[account])
            // The last good read stands, marked by the problem and its age; nothing became zero.
            assertEquals(good, holding.snapshot)
            assertNotNull(holding.problem)
        }
        assertEquals(
            RefreshProblem.NotFound,
            tracker.state.value.holdings.getValue(account).problem,
        )
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
        val outcomes =
            runBlocking(Dispatchers.Default) {
                val first = async { tracker.sell(draft, { selected }, { block -> block(session) }) }
                while (session.sent.isEmpty()) Thread.sleep(10)
                val second = async {
                    tracker.sell(draft, { selected }, { block -> block(session) })
                }
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
        assertTrue(
            runBlocking { tracker.prepareSale(account, selected) } is SaleReviewState.Refused
        )

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
        assertEquals(
            10_000_000UL,
            tracker.state.value.holdings.getValue(account).snapshot?.contractsMicro,
        )
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
        assertTrue(
            runBlocking { restarted.prepareSale(account, selected) } is SaleReviewState.Refused
        )

        // No order appears, however long: the provider's silence and the clock prove nothing, and
        // with no chain to ask it stays unresolved and blocks a second sale.
        api.answersStatus = { throw PredictionException(PredictionProblem.NotFound) }
        api.answersPosition = { predictionPosition() }
        now = now.plus(Duration.ofHours(2))
        runBlocking { restarted.reconcile() }
        assertEquals(SaleResult.Pending, restarted.state.value.sales.single().result)
        assertTrue(restarted.state.value.sales.single().inFlight)
        assertEquals(1, session.sent.size)
    }

    @Test
    fun anUnknownSaleLapsesOnlyOnTheChainsProofThatItNeverLanded() {
        val ledger = Ledger().also { this.ledger = it }
        val first = tracker().apply { load() }
        first.link(listOf(candidate()))
        session.answer = { SendResult.Unknown("the wallet went away") }
        first.sellNow(ready(first))
        val attempt = first.state.value.sales.single()
        // The approved bytes' blockhash is kept with the attempt, and survives a restart.
        assertEquals(recentBlockhashOf(sale.transaction.toByteArray()), attempt.blockhash)
        val restarted = tracker().apply { load() }
        assertEquals(attempt.blockhash, restarted.state.value.sales.single().blockhash)
        api.answersStatus = { throw PredictionException(PredictionProblem.NotFound) }
        now = now.plus(Duration.ofMinutes(10))

        fun settledAs(expected: SaleResult) {
            runBlocking { restarted.reconcile() }
            assertEquals(expected, restarted.state.value.sales.single().result)
        }

        // The blockhash still counts: it can still land.
        ledger.blockhashValid = true
        settledAs(SaleResult.Pending)
        // Expired, but the chain has a transaction naming the sale's order: it may have landed.
        ledger.blockhashValid = false
        ledger.signatures = listOf("5".repeat(88))
        settledAs(SaleResult.Pending)
        // Expired and nothing found, but the endpoint's ledger does not reach back far enough.
        ledger.signatures = emptyList()
        ledger.retainedSince = attempt.createdAt.plusSeconds(1)
        settledAs(SaleResult.Pending)
        ledger.retainedSince = null
        settledAs(SaleResult.Pending)
        // The endpoint could not be asked.
        ledger.retainedSince = attempt.createdAt.minus(Duration.ofDays(2))
        ledger.failing = true
        settledAs(SaleResult.Pending)
        // Expired, a ledger that covers it, and no transaction naming the order: proof.
        ledger.failing = false
        settledAs(SaleResult.Lapsed)
        assertEquals(SALE_ORDER, ledger.searched.last())
        assertFalse(restarted.state.value.sales.single().inFlight)
        assertEquals(1, session.sent.size)
    }

    @Test
    fun anOrderFirstSeenLongAfterTheWalletWentQuietStillReconciles() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        session.answer = { SendResult.Unknown("the wallet went away") }
        tracker.sellNow(ready(tracker))
        api.answersStatus = { throw PredictionException(PredictionProblem.NotFound) }
        api.answersPosition = { predictionPosition() }
        now = now.plus(Duration.ofMinutes(4))
        runBlocking { tracker.reconcile() }
        now = now.plus(Duration.ofMinutes(20))
        runBlocking { tracker.reconcile() }
        assertEquals(SaleResult.Pending, tracker.state.value.sales.single().result)

        // The provider indexes the order well past any fixed window: it is still this sale's.
        api.answersStatus = { status(it, OrderFill.Filled, net = 21_000_000UL) }
        api.answersPosition = { predictionPosition(contracts = 0UL) }
        runBlocking { tracker.reconcile() }
        val settled = tracker.state.value.sales.single()
        assertEquals(SaleResult.Closed, settled.result)
        assertEquals(21_000_000UL, settled.order?.reading?.netProceedsMicroUsd)
        assertEquals(1, session.sent.size)
    }

    @Test
    fun aFillSeenAfterAnOlderPositionReadIsMeasuredAgainstAFreshOne() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        session.answer = { SendResult.Sent(ByteString.copyFrom(ByteArray(64) { 2 })) }
        tracker.sellNow(ready(tracker))
        // The refresh reads the position while the sale is still open, then the order has filled:
        // the position is read again, after, and it is empty.
        val reads = ArrayDeque(listOf(HELD_CONTRACTS, 0UL))
        api.answersPosition = { predictionPosition(contracts = reads.removeFirstOrNull() ?: 0UL) }
        api.answersStatus = { status(it, OrderFill.Filled, net = 21_000_000UL) }
        runBlocking { tracker.reconcile() }
        assertEquals(SaleResult.Closed, tracker.state.value.sales.single().result)
        assertEquals(0UL, tracker.state.value.holdings.getValue(account).snapshot?.contractsMicro)
    }

    @Test
    fun aResidueFromAnIndexStillCatchingUpIsRevisedWhenThePositionEmpties() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        session.answer = { SendResult.Sent(ByteString.copyFrom(ByteArray(64) { 2 })) }
        tracker.sellNow(ready(tracker))
        // Even the read after the fill still shows the pre-sale balance: the index lags.
        api.answersPosition = { predictionPosition() }
        api.answersStatus = { status(it, OrderFill.Filled, net = 21_000_000UL) }
        runBlocking { tracker.reconcile() }
        val residual = tracker.state.value.sales.single()
        assertEquals(SaleResult.Residual, residual.result)
        assertTrue(residual.revisable)
        // It does not stand in the way of selling what is really left, if anything is.
        assertFalse(residual.inFlight)

        // A 404 is not an empty position; a read that finds it empty is.
        api.answersPosition = { throw PredictionException(PredictionProblem.NotFound) }
        runBlocking { tracker.refresh(account, force = true) }
        assertEquals(SaleResult.Residual, tracker.state.value.sales.single().result)
        api.answersPosition = { predictionPosition(contracts = 0UL) }
        runBlocking { tracker.refresh(account, force = true) }
        assertEquals(SaleResult.Closed, tracker.state.value.sales.single().result)
        // Nothing was sent again.
        assertEquals(1, session.sent.size)
    }

    @Test
    fun aSaleThatWaitedForTheWalletsLockIsCheckedAgainOnceItHasIt() {
        val tracker = tracker().apply { load() }
        tracker.link(listOf(candidate()))
        session.answer = { SendResult.Sent(ByteString.copyFrom(ByteArray(64) { 2 })) }

        // The review ran out while the lock was held elsewhere.
        var draft = ready(tracker)
        assertEquals(
            SellOutcome.Stale,
            tracker.sellAfterWaiting(draft) { now = now.plusSeconds(61) },
        )
        // The review was closed while the lock was held elsewhere.
        draft = ready(tracker)
        assertEquals(
            SellOutcome.Stale,
            tracker.sellAfterWaiting(draft) { tracker.discard(account) },
        )
        // The position changed while the lock was held elsewhere, long enough to matter.
        draft = ready(tracker)
        assertEquals(
            SellOutcome.Changed,
            tracker.sellAfterWaiting(draft) {
                now = now.plusSeconds(20)
                api.answersPosition = { predictionPosition(contracts = HELD_CONTRACTS / 2UL) }
            },
        )
        // Nothing was written down, and the wallet was never asked.
        assertTrue(session.sent.isEmpty())
        assertTrue(tracker.state.value.sales.isEmpty())
        assertTrue(PositionStore(directory).sales().isEmpty())

        // A short, harmless wait still sells, once.
        draft = ready(tracker)
        assertEquals(
            SellOutcome.Handed,
            tracker.sellAfterWaiting(draft) { now = now.plusSeconds(2) },
        )
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

        override suspend fun signAndSend(
            transaction: ByteString,
            reviewed: SelectedWallet,
        ): SendResult {
            sent += transaction
            suspendUntil?.await()
            return answer()
        }
    }

    /**
     * A chain endpoint that answers what a test sets, and remembers which addresses it searched.
     */
    private class Ledger : ChainReader {
        var blockhashValid = true
        var retainedSince: Instant? = Instant.EPOCH
        var signatures: List<String> = emptyList()
        var failing = false
        val searched = mutableListOf<String>()

        override val host = "rpc.example.com"

        private fun answer() {
            if (failing) throw SolanaException(SolanaProblem.Unreachable)
        }

        override suspend fun genesisHash(): String = "genesis"

        override suspend fun statuses(
            signatures: List<String>,
            searchHistory: Boolean,
        ): List<SignatureStatus?> = signatures.map { null }

        override suspend fun transaction(signature: String): ChainTransaction? = null

        override suspend fun blockhashValid(blockhash: String): Boolean = blockhashValid.also {
            answer()
        }

        override suspend fun retainedSince(): Instant? = retainedSince.also { answer() }

        override suspend fun signaturesFor(address: String, limit: Int): List<String> {
            answer()
            searched += address
            return signatures
        }
    }

    private object NoSwaps : JupiterProvider {
        override suspend fun quote(
            terms: SwapPayload,
            amount: ULong,
            slippageBps: Int,
        ): JupiterQuote = throw AssertionError("a position asked for a swap")

        override suspend fun build(quote: JupiterQuote, wallet: String) =
            throw AssertionError("a position asked for a swap")
    }

    private companion object {
        const val CONNECTION = "5b1f4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b"
    }
}
