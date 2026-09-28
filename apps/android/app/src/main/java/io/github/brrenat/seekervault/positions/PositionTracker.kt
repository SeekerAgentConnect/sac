package io.github.brrenat.seekervault.positions

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.Submission
import io.github.brrenat.seekervault.confirmations.SubmissionTracking
import io.github.brrenat.seekervault.confirmations.TrackingOrigin
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.plugins.OrderRead
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PositionManagement
import io.github.brrenat.seekervault.plugins.PositionRead
import io.github.brrenat.seekervault.plugins.PreparedSale
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.ReadProblem
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.plugins.saleBlockOf
import io.github.brrenat.seekervault.positions.storage.PositionStore
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.WalletSession
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A purchase this phone made that names the position it went into. */
data class PurchaseCandidate(val held: HeldPosition, val purchase: PositionPurchase)

/**
 * The purchases worth tracking, out of the owner's own records (SEE-172).
 *
 * A production `prediction.buy` that reached the wallet and whose record names the position its
 * bytes placed the order into. The references come from the Activity record, where the inspection's
 * reading of the bytes was kept; the side, the wallet and the network come from the binding the
 * owner approved. A record without a position reference — one made before SEE-94 kept them, or one
 * whose History was cleared — is not guessed at: it stays readable and says tracking is
 * unavailable.
 */
fun purchasesOf(
    proposals: List<ProposalRecord>,
    activity: List<ActivityRecord>,
): List<PurchaseCandidate> {
    val references = activity.associate { it.key to it.operation?.references.orEmpty() }
    return proposals.mapNotNull { record ->
        val execution = record.execution ?: return@mapNotNull null
        val binding = execution.binding
        if (binding.action != PREDICTION_BUY_ACTION) return@mapNotNull null
        if (binding.environment != PluginEnvironment.Production) return@mapNotNull null
        val signature =
            when (val outcome = execution.outcome) {
                is ProposalOutcome.Submitted -> encodeBase58(outcome.signature.toByteArray())
                is ProposalOutcome.Unresolved -> null
                else -> return@mapNotNull null
            }
        val named = references[RequestKey(record.connectionId, record.key.proposalId)].orEmpty()
        val position = named.firstOrNull { it.key == POSITION_REFERENCE }?.text
        val market = named.firstOrNull { it.key == MARKET_REFERENCE }?.text
        if (position.isNullOrEmpty() || market != binding.instrument.id) return@mapNotNull null
        val side =
            (binding.choice[PredictionParameterNames.OUTCOME] as? ParameterValue.Selected)?.option
                ?: return@mapNotNull null
        PurchaseCandidate(
            held =
                HeldPosition(
                    provider = binding.provider,
                    owner = binding.wallet,
                    network = binding.network,
                    account = position,
                    marketId = market,
                    yes = side == PredictionOutcomes.YES,
                ),
            purchase =
                PositionPurchase(
                    connectionId = record.connectionId,
                    proposalId = record.key.proposalId,
                    orderAccount = named.firstOrNull { it.key == ORDER_REFERENCE }?.text,
                    signature = signature,
                    boughtAt = execution.startedAt,
                    depositBaseUnits =
                        (binding.choice[PredictionParameterNames.DEPOSIT] as? ParameterValue.Amount)
                            ?.baseUnits,
                ),
        )
    }
}

/** The reference keys a prediction purchase's record keeps. Stable: a record outlives a build. */
const val ORDER_REFERENCE: String = "order_account"

const val POSITION_REFERENCE: String = "position_account"

const val MARKET_REFERENCE: String = "market_id"

/** A sale built and read, waiting for the owner. Held in memory only, and only for its lifetime. */
data class SaleDraft(
    val held: HeldPosition,
    val prepared: PreparedSale,
    /** The wallet the sale was built for: the position's owner, on its network. */
    val wallet: SelectedWallet,
    val preparedAt: Instant,
) {
    fun expired(now: Instant): Boolean = now.epochSecond >= prepared.expiresAtEpochSeconds
}

/** Where preparing a sale for one position stands, for the review sheet. */
sealed interface SaleReviewState {
    data object Preparing : SaleReviewState

    data class Ready(val draft: SaleDraft) : SaleReviewState

    /** Nothing could be prepared, and why. */
    data class Refused(val failure: PluginFailure) : SaleReviewState

    /** The wallet is open for this sale. */
    data object Signing : SaleReviewState
}

/** What approving a sale came to. */
enum class SellOutcome {
    /** Handed to the wallet; the record says what the wallet answered. */
    Handed,
    /** The review was for a sale that is no longer current. Prepare and review again. */
    Stale,
    /** The position changed since it was reviewed. Prepare and review again. */
    Changed,
    /** Another sale of the position is still unresolved. */
    Busy,
    /** The selected wallet is not the position's owner on its network. */
    WrongWallet,
    /** The review found something that forbids approving it. */
    NotApprovable,
}

/** Everything the screens read. */
data class PositionsState(
    val loaded: Boolean = false,
    val holdings: Map<String, HoldingRecord> = emptyMap(),
    val sales: List<SaleRecord> = emptyList(),
    val refreshing: Set<String> = emptySet(),
    val reviews: Map<String, SaleReviewState> = emptyMap(),
    /** Whether the phone could not read its stored positions. */
    val unreadable: Boolean = false,
) {
    fun salesOf(account: String): List<SaleRecord> =
        sales.filter { it.held.account == account }.sortedByDescending { it.createdAt }
}

/**
 * The one coordinator for positions and sales (SEE-172).
 *
 * ## Reading
 *
 * One read of a position at a time, however many screens ask: a second caller waits for the first
 * read rather than making another. Every call to the provider passes one [ReadGate], so all
 * positions together stay inside the provider's keyless allowance; a rate-limit answer pauses them
 * all. A failed read never replaces the last good snapshot.
 *
 * ## Selling
 *
 * [prepareSale] reads the position again, has the provider build the sale and read its bytes, and
 * holds the result for the review. [sell] checks that the review is still current, re-reads the
 * position to catch a change made elsewhere since, writes the attempt down, and only then asks the
 * wallet — under the wallet's own lock — to sign and send exactly the reviewed bytes. It is the
 * only path to the wallet here, and nothing it does is ever repeated automatically: an attempt
 * whose outcome is unknown is reconciled by reading ([reconcile]), never by sending again.
 */
class PositionTracker(
    private val store: PositionStore,
    private val providers: () -> ProviderRegistry,
    private val tracking: SubmissionTracking? = null,
    private val chainChecks: () -> Map<RequestKey, ChainCheck> = { emptyMap() },
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val gate: ReadGate = ReadGate(now = now),
) {
    private val _state = MutableStateFlow(PositionsState())
    val state: StateFlow<PositionsState> = _state.asStateFlow()

    private val writes = Any()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val selling = mutableMapOf<String, Mutex>()

    /**
     * Reads what is stored. An attempt left at [SaleStage.Signing] by a stopped app is unresolved.
     */
    fun load() {
        synchronized(writes) {
            val (holdings, sales) =
                try {
                    store.holdings() to store.sales()
                } catch (_: IOException) {
                    _state.update { it.copy(loaded = true, unreadable = true) }
                    return
                }
            val recovered = sales.map { sale ->
                if (sale.stage != SaleStage.Signing) return@map sale
                // The app stopped with the wallet open. It may have signed and sent; nothing
                // here knows, so the attempt is unresolved and is reconciled by reading.
                sale
                    .copy(
                        stage = SaleStage.Unresolved,
                        settledAt = now(),
                        detail = "The app stopped before the wallet answered.",
                    )
                    .also { store.put(it) }
            }
            recovered.forEach { sale ->
                val signature = sale.signature ?: return@forEach
                decodeSignature(signature)?.let {
                    tracking?.recovered(saleKey(sale.id), it, sale.settledAt ?: sale.createdAt)
                }
            }
            _state.update {
                it.copy(
                    loaded = true,
                    unreadable = false,
                    holdings = holdings.associateBy { holding -> holding.held.account },
                    sales = recovered,
                )
            }
        }
    }

    /**
     * Adds the purchases [candidates] name to the positions they went into. Idempotent.
     *
     * A position is never retargeted: a candidate naming a known account with another owner,
     * network, market or side is ignored rather than merged. A purchase made before the owner last
     * cleared History is one they deleted, and is not brought back.
     */
    fun link(candidates: List<PurchaseCandidate>) {
        synchronized(writes) {
            val cleared = store.clearedAt()
            var holdings = _state.value.holdings
            for (candidate in candidates) {
                if (cleared != null && !candidate.purchase.boughtAt.isAfter(cleared)) continue
                val account = candidate.held.account
                val existing = holdings[account]
                if (existing != null && existing.held != candidate.held) continue
                val known =
                    existing?.purchases?.any {
                        it.connectionId == candidate.purchase.connectionId &&
                            it.proposalId == candidate.purchase.proposalId
                    } == true
                if (known) continue
                val updated =
                    existing?.copy(
                        purchases =
                            (existing.purchases + candidate.purchase).sortedBy { it.boughtAt }
                    )
                        ?: HoldingRecord(
                            held = candidate.held,
                            purchases = listOf(candidate.purchase),
                            linkedAt = now(),
                        )
                if (!persist(updated)) continue
                holdings = holdings + (account to updated)
            }
            _state.update { it.copy(holdings = holdings) }
        }
    }

    /**
     * Reads [account]'s position, and the fills of every order on it still open, once.
     *
     * Skipped when it was read within [FRESH] unless [force]. A caller that arrives while a read is
     * running waits for it rather than starting another.
     */
    suspend fun refresh(account: String, force: Boolean = false) {
        val holding = _state.value.holdings[account] ?: return
        val last = holding.attemptedAt
        if (!force && last != null && Duration.between(last, now()) < FRESH) return
        val (mine, deferred) =
            synchronized(inFlight) {
                inFlight[account]?.let { false to it }
                    ?: (true to CompletableDeferred<Unit>().also { inFlight[account] = it })
            }
        if (!mine) {
            deferred.await()
            return
        }
        _state.update { it.copy(refreshing = it.refreshing + account) }
        try {
            read(holding)
        } finally {
            synchronized(inFlight) { inFlight.remove(account) }
            deferred.complete(Unit)
            _state.update { it.copy(refreshing = it.refreshing - account) }
        }
    }

    private suspend fun read(holding: HoldingRecord) {
        val manager = managerOf(holding.held)
        if (manager == null) {
            record(holding.held.account) {
                it.copy(attemptedAt = now(), problem = RefreshProblem.Unsupported)
            }
            return
        }
        val position = gate.read { manager.position(holding.held) }
        record(holding.held.account) { stored ->
            when (position) {
                is PositionRead.Found ->
                    stored.copy(
                        snapshot = position.position,
                        observedAt = now(),
                        attemptedAt = now(),
                        problem = null,
                    )
                PositionRead.NotFound ->
                    stored.copy(attemptedAt = now(), problem = RefreshProblem.NotFound)
                is PositionRead.Failed ->
                    stored.copy(attemptedAt = now(), problem = position.problem.refresh)
            }
        }
        // Each purchase's order, until the provider has finished with it.
        for (purchase in holding.purchases) {
            val order = purchase.orderAccount ?: continue
            if (holding.orders[order]?.reading?.fill?.finished == true) continue
            val read = gate.read { manager.order(holding.held, order) }
            if (read is OrderRead.Found) {
                record(holding.held.account) {
                    it.copy(orders = it.orders + (order to OrderSnapshot(read.order, now())))
                }
            }
        }
        // And any sale of it still unresolved, against what was just read.
        for (sale in _state.value.salesOf(holding.held.account).filter { it.inFlight }) {
            settle(sale, manager, position)
        }
    }

    /**
     * Brings every unresolved sale and open buy order up to date by reading. Returns whether
     * anything is still unresolved. Never prepares, signs or sends.
     */
    suspend fun reconcile(): Boolean {
        val pending =
            _state.value.holdings.values.filter { holding ->
                watching(holding) || _state.value.salesOf(holding.held.account).any { it.inFlight }
            }
        for (holding in pending) refresh(holding.held.account, force = true)
        return unresolved()
    }

    /**
     * Whether any sale or recent buy order is still waiting on evidence. An order is watched for
     * [ORDER_WATCH] after its purchase; one the provider never reports on is then left to the
     * owner's Refresh rather than polled for ever.
     */
    fun unresolved(): Boolean =
        _state.value.sales.any { it.inFlight && it.stage != SaleStage.Signing } ||
            _state.value.holdings.values.any(::watching)

    private fun watching(holding: HoldingRecord): Boolean {
        val since = now().minus(ORDER_WATCH)
        return holding.purchases.any { purchase ->
            val order = purchase.orderAccount ?: return@any false
            purchase.boughtAt.isAfter(since) &&
                holding.orders[order]?.reading?.fill?.finished != true
        }
    }

    private suspend fun settle(
        sale: SaleRecord,
        manager: PositionManagement,
        position: PositionRead,
    ) {
        val order = gate.read { manager.order(sale.held, sale.orderAccount) }
        val chain = chainChecks()[saleKey(sale.id)]
        synchronized(writes) {
            val stored = _state.value.sales.firstOrNull { it.id == sale.id } ?: return
            val settled = settleSale(stored, chain, order, position, now())
            if (settled != stored) putSale(settled)
        }
    }

    /**
     * Builds and reads a sale of the whole of [account] for [wallet], and holds it for review.
     *
     * Refused, with the reason, while another sale of it is unresolved or one is being prepared.
     */
    suspend fun prepareSale(account: String, wallet: SelectedWallet?): SaleReviewState {
        val holding = _state.value.holdings[account] ?: return refuse(account, NO_POSITION_FAILURE)
        if (_state.value.salesOf(account).any { it.inFlight }) {
            return refuse(account, BUSY_FAILURE)
        }
        val manager = managerOf(holding.held) ?: return refuse(account, UNSUPPORTED_FAILURE)
        synchronized(writes) {
            if (_state.value.reviews[account] == SaleReviewState.Preparing) {
                return SaleReviewState.Preparing
            }
            review(account, SaleReviewState.Preparing)
        }
        val result =
            try {
                val prepared = gate.read { manager.prepareSale(holding.held, wallet) }
                // What the provider read to build it is the freshest snapshot there is.
                record(account) {
                    it.copy(
                        snapshot = prepared.position,
                        observedAt = now(),
                        attemptedAt = now(),
                        problem = null,
                    )
                }
                SaleReviewState.Ready(
                    SaleDraft(holding.held, prepared, checkNotNull(wallet), now())
                )
            } catch (e: CancellationException) {
                discard(account)
                throw e
            } catch (e: PluginFailure) {
                SaleReviewState.Refused(e)
            }
        synchronized(writes) { review(account, result) }
        return result
    }

    /** Forgets a sale nobody approved. */
    fun discard(account: String) {
        synchronized(writes) {
            if (_state.value.reviews[account] == SaleReviewState.Signing) return
            _state.update { it.copy(reviews = it.reviews - account) }
        }
    }

    /**
     * Hands [draft] to the wallet, once, if it is still exactly what the owner reviewed.
     *
     * [selected] is the wallet selected now, and [withWallet] runs the hand-off under the wallet's
     * own lock, the one every other signing path in the app takes.
     */
    suspend fun sell(
        draft: SaleDraft,
        selected: () -> SelectedWallet?,
        withWallet: suspend (suspend (WalletSession) -> Unit) -> Unit,
    ): SellOutcome {
        val account = draft.held.account
        val lock = synchronized(selling) { selling.getOrPut(account) { Mutex() } }
        if (!lock.tryLock()) return SellOutcome.Busy
        try {
            val current = (_state.value.reviews[account] as? SaleReviewState.Ready)?.draft
            if (current != draft || draft.expired(now())) return stale(account)
            if (!draft.prepared.inspection.approvable) return SellOutcome.NotApprovable
            if (_state.value.salesOf(account).any { it.inFlight }) return SellOutcome.Busy
            if (!sameWallet(selected(), draft)) return SellOutcome.WrongWallet
            // The race between reviewing and signing: the position is read once more, and a sale
            // of anything but exactly the reviewed contracts goes back to review.
            val manager = managerOf(draft.held) ?: return SellOutcome.NotApprovable
            when (val fresh = gate.read { manager.position(draft.held) }) {
                is PositionRead.Found -> {
                    record(account) {
                        it.copy(
                            snapshot = fresh.position,
                            observedAt = now(),
                            attemptedAt = now(),
                            problem = null,
                        )
                    }
                    if (
                        fresh.position.contractsMicro != draft.prepared.terms.contractsMicro ||
                            saleBlockOf(fresh.position) != null
                    ) {
                        return changed(account)
                    }
                }
                else -> return changed(account)
            }
            if (draft.expired(now())) return stale(account)
            var outcome = SellOutcome.Handed
            withWallet { session ->
                val wallet = selected()
                if (!sameWallet(wallet, draft) || wallet == null) {
                    outcome = SellOutcome.WrongWallet
                    return@withWallet
                }
                val sale =
                    begin(draft)
                        ?: run {
                            outcome = SellOutcome.Stale
                            return@withWallet
                        }
                val answer =
                    try {
                        session.signAndSend(draft.prepared.transaction, wallet)
                    } catch (e: CancellationException) {
                        // The record says Signing; the next load makes it Unresolved, and it is
                        // reconciled by reading.
                        throw e
                    } catch (e: Exception) {
                        SendResult.Unknown(e.message)
                    }
                finish(sale, answer)
            }
            if (outcome != SellOutcome.Handed) return outcome
            synchronized(writes) { _state.update { it.copy(reviews = it.reviews - account) } }
            return outcome
        } finally {
            lock.unlock()
        }
    }

    // Writes the attempt down before the wallet is opened, and arms the chain tracking for its
    // exact bytes. Null when History was cleared since the review began.
    private fun begin(draft: SaleDraft): SaleRecord? =
        synchronized(writes) {
            val cleared = store.clearedAt()
            if (cleared != null && !draft.preparedAt.isAfter(cleared)) return null
            if (_state.value.holdings[draft.held.account] == null) return null
            val terms = draft.prepared.terms
            val sale =
                SaleRecord(
                    id = UUID.randomUUID().toString(),
                    held = draft.held,
                    createdAt = now(),
                    contractsMicro = terms.contractsMicro,
                    floorPriceMicroUsd = terms.floorPriceMicroUsd,
                    leastGrossMicroUsd = terms.leastGrossMicroUsd,
                    estimatedGrossMicroUsd = terms.estimatedGrossMicroUsd,
                    estimatedFeeMicroUsd = terms.estimatedFeeMicroUsd,
                    proceedsSymbol = terms.proceedsSymbol,
                    proceedsDecimals = terms.proceedsDecimals,
                    proceedsAccount = terms.proceedsAccount,
                    orderAccount = terms.orderAccount,
                    contentHash = hash(draft.prepared.transaction),
                    stage = SaleStage.Signing,
                )
            try {
                store.put(sale)
            } catch (_: IOException) {
                // Not written down means not sent: the wallet is never opened for an attempt the
                // phone could lose.
                return null
            }
            _state.update {
                it.copy(
                    sales = it.sales + sale,
                    reviews = it.reviews + (draft.held.account to SaleReviewState.Signing),
                )
            }
            tracking?.expect(
                Submission(
                    key = saleKey(sale.id),
                    origin = TrackingOrigin.Operation,
                    network = draft.held.network,
                    wallet = draft.held.owner,
                    transaction = draft.prepared.transaction.toByteArray(),
                )
            )
            sale
        }

    private fun finish(sale: SaleRecord, answer: SendResult) {
        synchronized(writes) {
            val stored = _state.value.sales.firstOrNull { it.id == sale.id } ?: return
            if (stored.stage != SaleStage.Signing) return
            val at = now()
            val settled =
                when (answer) {
                    is SendResult.Sent ->
                        stored.copy(
                            stage = SaleStage.Submitted,
                            signature = encodeBase58(answer.signature.toByteArray()),
                            settledAt = at,
                        )
                    SendResult.Declined ->
                        stored.copy(
                            stage = SaleStage.Declined,
                            settledAt = at,
                            result = SaleResult.NotExecuted,
                            resolvedAt = at,
                            detail = "You declined in your wallet. Nothing was sent.",
                        )
                    is SendResult.Unknown ->
                        stored.copy(
                            stage = SaleStage.Unresolved,
                            settledAt = at,
                            detail =
                                answer.message?.take(MOST_DETAIL) ?: "The wallet gave no answer.",
                        )
                    else ->
                        stored.copy(
                            stage = SaleStage.Failed,
                            settledAt = at,
                            result = SaleResult.NotExecuted,
                            resolvedAt = at,
                            detail = failureText(answer),
                        )
                }
            putSale(settled)
            when (answer) {
                is SendResult.Sent ->
                    tracking?.submitted(saleKey(sale.id), answer.signature.toByteArray())
                // Unknown keeps the capture: it may have been sent, and the chain may yet say so.
                is SendResult.Unknown -> Unit
                else -> tracking?.abandoned(saleKey(sale.id))
            }
        }
    }

    /** Forgets everything. History was cleared, and nothing in flight may bring any of it back. */
    fun clear() {
        synchronized(writes) {
            try {
                store.clear(now())
            } catch (_: IOException) {
                // What is on screen is cleared regardless; the next load reads whatever remains.
            }
            _state.value = PositionsState(loaded = true)
        }
    }

    private fun record(account: String, change: (HoldingRecord) -> HoldingRecord) {
        synchronized(writes) {
            val stored = _state.value.holdings[account] ?: return
            val updated = change(stored)
            if (updated == stored || !persist(updated)) return
            _state.update { it.copy(holdings = it.holdings + (account to updated)) }
        }
    }

    // A write after History was cleared is refused rather than resurrecting the record.
    private fun persist(record: HoldingRecord): Boolean {
        val cleared = store.clearedAt()
        if (cleared != null && !record.linkedAt.isAfter(cleared)) return false
        return try {
            store.put(record)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun putSale(sale: SaleRecord) {
        val cleared = store.clearedAt()
        if (cleared != null && !sale.createdAt.isAfter(cleared)) return
        try {
            store.put(sale)
        } catch (_: IOException) {
            return
        }
        _state.update { state ->
            state.copy(sales = state.sales.map { if (it.id == sale.id) sale else it })
        }
    }

    private fun review(account: String, review: SaleReviewState) {
        _state.update { it.copy(reviews = it.reviews + (account to review)) }
    }

    private fun refuse(account: String, failure: PluginFailure): SaleReviewState =
        SaleReviewState.Refused(failure).also { synchronized(writes) { review(account, it) } }

    private fun stale(account: String): SellOutcome {
        discard(account)
        return SellOutcome.Stale
    }

    private fun changed(account: String): SellOutcome {
        discard(account)
        return SellOutcome.Changed
    }

    private fun sameWallet(wallet: SelectedWallet?, draft: SaleDraft): Boolean =
        wallet != null &&
            wallet.address == draft.held.owner &&
            wallet.address == draft.wallet.address &&
            wallet.network == draft.wallet.network &&
            wallet.network.network == draft.held.network

    private fun managerOf(held: HeldPosition): PositionManagement? =
        providers().byId(held.provider)?.positions

    private suspend fun <T> ReadGate.read(block: suspend () -> T): T =
        withContext(io) { pass(block) }

    companion object {
        /** How long after a purchase its order's fill is followed without being asked. */
        val ORDER_WATCH: Duration = Duration.ofHours(24)

        /** A position read this recently is not read again unless asked. */
        val FRESH: Duration = Duration.ofSeconds(15)

        /**
         * The namespace a sale's chain-tracking key lives under. Sales belong to no connection, and
         * a fixed identifier keeps them apart from every request and proposal.
         */
        const val SALES_NAMESPACE: String = "00000000-0000-4000-8172-000000000000"

        fun saleKey(id: String): RequestKey = RequestKey(SALES_NAMESPACE, id)

        private const val MOST_DETAIL = 200

        internal val NO_POSITION_FAILURE =
            PluginFailure("no_position", R.string.position_refused_unknown)
        internal val BUSY_FAILURE =
            PluginFailure("sale_in_progress", R.string.position_refused_busy)
        internal val UNSUPPORTED_FAILURE =
            PluginFailure("unsupported", R.string.position_refused_unsupported)

        fun hash(bytes: ByteString): String =
            MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") {
                "%02x".format(it)
            }

        private fun decodeSignature(text: String): ByteArray? =
            io.github.brrenat.seekervault.wallet.decodeBase58(text)?.takeIf { it.size == 64 }

        private fun failureText(answer: SendResult): String =
            when (answer) {
                SendResult.NoWallet -> "No wallet app answered."
                SendResult.NotConnected -> "No wallet is connected."
                SendResult.AuthorizationExpired -> "The wallet's authorization no longer works."
                SendResult.Changed -> "The wallet's account is not the one reviewed."
                is SendResult.Failed -> answer.message?.take(MOST_DETAIL) ?: "The wallet refused."
                else -> "The wallet refused."
            }
    }
}

private val ReadProblem.refresh: RefreshProblem
    get() =
        when (this) {
            ReadProblem.Unreachable -> RefreshProblem.Unreachable
            ReadProblem.RateLimited -> RefreshProblem.RateLimited
            ReadProblem.Refused -> RefreshProblem.Refused
            ReadProblem.Unusable -> RefreshProblem.Unusable
        }

/**
 * One queue for every call to the provider (SEE-172): at most one call per [spacing], and a pause
 * of [backoff] after the provider says it is rate-limited. Jupiter's keyless allowance is about one
 * request every two seconds, shared by every position this phone follows.
 */
class ReadGate(
    private val spacing: Duration = Duration.ofMillis(2_100),
    private val backoff: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = Instant::now,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var next: Instant = Instant.EPOCH

    suspend fun <T> pass(block: suspend () -> T): T = lock.withLock {
        val pause = Duration.between(now(), next).toMillis()
        if (pause > 0) wait(pause)
        val result =
            try {
                block()
            } finally {
                next = now().plus(spacing)
            }
        if (result.limited()) next = now().plus(backoff)
        result
    }

    private fun Any?.limited(): Boolean =
        (this is PositionRead.Failed && problem == ReadProblem.RateLimited) ||
            (this is OrderRead.Failed && problem == ReadProblem.RateLimited)
}
