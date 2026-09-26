package io.github.brrenat.seekervault.confirmations

import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.confirmations.storage.TrackingStore
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.skr.staking
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.messageBytes
import io.github.brrenat.seekervault.transactions.recentBlockhashOf
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the code that hands a transaction to the wallet tells the tracker (SEE-165). It is the whole
 * of the tracker's contact with the approval path: it is told, and it never asks the wallet for
 * anything, never signs, never builds, and never sends.
 */
interface SubmissionTracking {
    /**
     * The exact transaction about to be handed to the wallet. Called before the wallet is opened,
     * so the approved bytes are on disk whatever happens to the process while the wallet has them.
     * A second call for the same request changes nothing.
     */
    fun expect(submission: Submission)

    /** The wallet sent it and named [signature]. The first signature recorded stands. */
    fun submitted(key: RequestKey, signature: ByteArray)

    /** Nothing was sent — declined, refused, rehearsed — so there is nothing to look up. */
    fun abandoned(key: RequestKey)
}

/** What was captured before the wallet was opened. */
class Submission(
    val key: RequestKey,
    val origin: TrackingOrigin,
    val network: Network,
    val wallet: String,
    /** The unsigned wire transaction, exactly as the wallet is handed it. */
    val transaction: ByteArray,
)

/**
 * Follows the transactions this phone's wallet sent to the chain, from the phone, with nothing but
 * a read-only endpoint (SEE-165, docs/wiki/chain-confirmation.md).
 *
 * ## One coordinator
 *
 * The foreground loop, the background worker and the owner's own "Check status" all call into this
 * one object. Runs are serialized by [run]: an automatic run that finds another one going skips
 * rather than queues, and a manual check waits for it. Every write goes through [record], which
 * re-reads the tracking record under [lock] first, so an answer that arrives after History was
 * cleared, or after a record was removed, is dropped rather than written back.
 *
 * ## What counts as an answer
 *
 * The trust standard is the direct server's (server-sdk `ConfirmationTracker`): a status at
 * `confirmed` or `finalized` is looked at only together with the transaction under it, and only
 * when that transaction's message is byte for byte the one the owner approved does it settle
 * anything. `processed` is not a result. A missing status is not a result either, until the
 * approved message's blockhash is no longer valid on the finalized chain *and* a search of the
 * ledger has no record of the signature — then it can never land, and that is said.
 *
 * Everything an endpoint fails to answer — a timeout, a 429, a malformed body, the wrong cluster —
 * is not evidence about the transaction. The record keeps what it had, says what stopped the check,
 * and is tried again later, with backoff, a bounded number of times.
 */
class ConfirmationTracker(
    private val store: TrackingStore,
    private val history: ActivityLog,
    private val endpoints: ChainEndpoints,
    private val now: () -> Instant = Instant::now,
) : SubmissionTracking {
    private val lock = Any()
    private val run = Mutex()
    private val _checks = MutableStateFlow<Map<RequestKey, ChainCheck>>(emptyMap())

    /** What each tracked transaction's last check found, by request. */
    val checks: StateFlow<Map<RequestKey, ChainCheck>> = _checks.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Something became due that wasn't before. The scheduler listens, so a new submission is
     * checked promptly rather than at the next wake-up.
     */
    @Volatile var onDue: () -> Unit = {}

    /**
     * Reads what is stored. Anything that stopped for want of an endpoint is armed again when this
     * build has one for its cluster, and nothing else is changed.
     */
    fun load() =
        synchronized(lock) {
            store.list().forEach { tracking ->
                val check = tracking.check
                if (
                    tracking.signature != null &&
                        check.state == ChainState.Checking &&
                        check.nextCheckAt == null &&
                        endpoints.configured(tracking.network)
                ) {
                    store.put(tracking.copy(check = check.copy(nextCheckAt = now())))
                }
            }
            publishAll()
            _loaded.value = true
        }

    override fun expect(submission: Submission) {
        synchronized(lock) {
            if (store.get(submission.key) != null) return
            // Bytes this build can't take apart can't be compared later either. Nothing is
            // tracked, and the record says there is no verification context, which is true.
            val message = messageBytes(submission.transaction) ?: return
            store.put(
                ChainTracking(
                    key = submission.key,
                    origin = submission.origin,
                    network = submission.network,
                    wallet = submission.wallet,
                    message = message,
                    blockhash = recentBlockhashOf(submission.transaction),
                    capturedAt = now(),
                )
            )
            publishAll()
        }
    }

    override fun submitted(key: RequestKey, signature: ByteArray) {
        val check =
            synchronized(lock) {
                val tracking = store.get(key) ?: return
                if (tracking.signature != null) return
                val at = now()
                val check =
                    ChainCheck(
                        state = ChainState.Checking,
                        nextCheckAt = at.plus(FIRST_CHECK),
                    )
                store.put(
                    tracking.copy(
                        signature = encodeBase58(signature),
                        submittedAt = at,
                        check = check,
                    )
                )
                history.confirm(key, check)
                publishAll()
                check
            }
        if (check.nextCheckAt != null) onDue()
    }

    override fun abandoned(key: RequestKey) {
        synchronized(lock) {
            val tracking = store.get(key) ?: return
            if (tracking.signature != null) return
            store.delete(key)
            publishAll()
        }
    }

    /** Forgets everything. History was cleared, and a check in flight must not bring any back. */
    fun clear() {
        synchronized(lock) {
            store.clear()
            publishAll()
        }
    }

    /**
     * Brings what was sent before this build into tracking, where the phone still holds enough to
     * check it honestly (SEE-165's backfill).
     *
     * A direct request whose stored answer still carries the approved transaction and the wallet's
     * signature is tracked from that. A History record with a transaction signature and nothing to
     * compare it against is marked as missing that context rather than checked: its status alone
     * would say something *landed* under the signature, not that it was what the owner approved.
     */
    fun backfill(results: List<LocalResult>, records: List<ActivityRecord>) {
        val due =
            synchronized(lock) {
                var armed = false
                results.forEach { result ->
                    val sent = result.signing as? SigningOutcome.Sent ?: return@forEach
                    if (store.get(result.key) != null) return@forEach
                    if (result.request.state in SERVER_SETTLED) return@forEach
                    val submission = directSubmission(result) ?: return@forEach
                    val message = messageBytes(submission.transaction) ?: return@forEach
                    val check = ChainCheck(state = ChainState.Checking, nextCheckAt = now())
                    store.put(
                        ChainTracking(
                            key = result.key,
                            origin = TrackingOrigin.Direct,
                            network = submission.network,
                            wallet = submission.wallet,
                            message = message,
                            blockhash = recentBlockhashOf(submission.transaction),
                            capturedAt = result.answeredAt,
                            signature = encodeBase58(sent.signature.toByteArray()),
                            submittedAt = result.answeredAt,
                            check = check,
                        )
                    )
                    history.confirm(result.key, check)
                    armed = true
                }
                records
                    .filter {
                        it.signatureIsTransaction &&
                            it.chain == null &&
                            it.outcome in UNSETTLED &&
                            store.get(it.key) == null
                    }
                    .forEach {
                        history.confirm(
                            it.key,
                            ChainCheck(
                                state = ChainState.Unresolved,
                                reason = ChainReason.MissingContext,
                            ),
                        )
                    }
                publishAll()
                armed
            }
        if (due) onDue()
    }

    /** When the next automatic check is due, or null when nothing is waiting. */
    fun nextDue(): Instant? =
        synchronized(lock) {
            store.list().filter { it.unfinished }.mapNotNull { it.check.nextCheckAt }.minOrNull()
        }

    /**
     * Checks everything that is due. An automatic caller that finds a run already going returns
     * without waiting: that run is doing the same work. Returns when the next check is due.
     */
    suspend fun checkDue(): Instant? {
        if (!run.tryLock()) return nextDue()
        try {
            val at = now()
            val due =
                synchronized(lock) {
                    store.list().filter { tracking ->
                        tracking.unfinished && tracking.check.nextCheckAt?.let { it <= at } == true
                    }
                }
            check(due, manual = false)
        } finally {
            run.unlock()
        }
        return nextDue()
    }

    /**
     * The owner asked about one transaction. It waits for a run that is already going, and then
     * asks the chain whatever the schedule says. Returns the check as it stands afterwards.
     */
    suspend fun check(key: RequestKey): ChainCheck? {
        run.withLock {
            val tracking = synchronized(lock) { store.get(key) } ?: return null
            if (tracking.signature != null && tracking.check.checkable) {
                check(listOf(tracking), manual = true)
            }
        }
        return synchronized(lock) { store.get(key)?.check }
    }

    private suspend fun check(due: List<ChainTracking>, manual: Boolean) {
        due.groupBy { it.network }
            .forEach { (network, trackings) ->
                val reader =
                    try {
                        endpoints.readerFor(network)
                    } catch (e: SolanaException) {
                        trackings.forEach {
                            record(it, inconclusive(it, reasonOf(e), null, manual))
                        }
                        return@forEach
                    }
                trackings.chunked(MOST_SIGNATURES).forEach { chunk ->
                    val statuses =
                        try {
                            reader.statuses(chunk.map { checkNotNull(it.signature) }, false)
                        } catch (e: SolanaException) {
                            chunk.forEach {
                                record(it, inconclusive(it, reasonOf(e), reader.host, manual))
                            }
                            return@forEach
                        }
                    chunk.zip(statuses).forEach { (tracking, status) ->
                        val next =
                            try {
                                evaluate(reader, tracking, status, manual)
                            } catch (e: SolanaException) {
                                inconclusive(tracking, reasonOf(e), reader.host, manual)
                            }
                        record(tracking, next)
                    }
                }
            }
    }

    /** What one status means for one tracked transaction. */
    private suspend fun evaluate(
        reader: ChainReader,
        tracking: ChainTracking,
        status: SignatureStatus?,
        manual: Boolean,
    ): ChainTracking {
        // Already settled against the approved message: only the level can still move.
        if (tracking.check.state.verified) {
            return if (status?.level == ChainLevel.Finalized)
                tracking.copy(
                    check =
                        tracking.check.copy(
                            level = ChainLevel.Finalized,
                            slot = status.slot,
                            checkedAt = now(),
                            checks = tracking.check.checks + 1,
                            host = reader.host,
                            reason = null,
                            nextCheckAt = null,
                        ),
                    attempts = 0,
                )
            else inconclusive(tracking, ChainReason.AwaitingFinality, reader.host, manual)
        }
        return when {
            status == null -> absent(reader, tracking, manual)
            status.level == ChainLevel.Processed ->
                inconclusive(
                    tracking,
                    ChainReason.ProcessedOnly,
                    reader.host,
                    manual,
                    level = ChainLevel.Processed,
                    slot = status.slot,
                )
            else -> seen(reader, tracking, status, manual)
        }
    }

    /**
     * The endpoint has no status. Proven expiry needs both halves: the approved blockhash can no
     * longer be used on the finalized chain, and the ledger itself has no record of the signature.
     * A record with no blockhash, or one sent too recently for the finalized chain to have caught
     * up, is only "not visible yet".
     */
    private suspend fun absent(
        reader: ChainReader,
        tracking: ChainTracking,
        manual: Boolean,
    ): ChainTracking {
        val blockhash = tracking.blockhash
        val submittedAt = tracking.submittedAt ?: tracking.capturedAt
        if (blockhash == null || now() < submittedAt.plus(EXPIRY_GRACE)) {
            return inconclusive(tracking, ChainReason.NotYetVisible, reader.host, manual)
        }
        if (reader.blockhashValid(blockhash)) {
            return inconclusive(tracking, ChainReason.NotYetVisible, reader.host, manual)
        }
        val searched = reader.statuses(listOf(checkNotNull(tracking.signature)), true).firstOrNull()
        if (searched != null) {
            return if (searched.level == ChainLevel.Processed)
                inconclusive(
                    tracking,
                    ChainReason.ProcessedOnly,
                    reader.host,
                    manual,
                    level = ChainLevel.Processed,
                    slot = searched.slot,
                )
            else seen(reader, tracking, searched, manual)
        }
        return settled(
            tracking,
            ChainState.Expired,
            level = null,
            slot = null,
            error = null,
            reader,
        )
    }

    /** A confirmed or finalized status: settle it only against the approved message. */
    private suspend fun seen(
        reader: ChainReader,
        tracking: ChainTracking,
        status: SignatureStatus,
        manual: Boolean,
    ): ChainTracking {
        val onChain =
            reader.transaction(checkNotNull(tracking.signature))
                ?: return inconclusive(
                    tracking,
                    ChainReason.BodyNotServed,
                    reader.host,
                    manual,
                    level = status.level,
                    slot = status.slot,
                )
        val message = messageBytes(onChain.transaction)
        if (message == null || !message.contentEquals(tracking.message)) {
            // The signature names something else: not evidence that the approved transaction ran,
            // nor that it didn't. Nothing automatic will change that.
            return tracking.copy(
                check =
                    ChainCheck(
                        state = ChainState.Unresolved,
                        level = status.level,
                        slot = onChain.slot,
                        chainError = onChain.chainError ?: status.chainError,
                        checkedAt = now(),
                        checks = tracking.check.checks + 1,
                        host = reader.host,
                        reason = ChainReason.Mismatch,
                        nextCheckAt = null,
                    ),
                attempts = 0,
            )
        }
        val error = onChain.chainError ?: status.chainError
        return settled(
            tracking,
            if (error != null) ChainState.Failed else ChainState.Confirmed,
            level = status.level,
            slot = onChain.slot,
            error = error,
            reader,
        )
    }

    private fun settled(
        tracking: ChainTracking,
        state: ChainState,
        level: ChainLevel?,
        slot: Long?,
        error: String?,
        reader: ChainReader,
    ): ChainTracking {
        val at = now()
        val final = state == ChainState.Expired || level == ChainLevel.Finalized
        return tracking.copy(
            check =
                ChainCheck(
                    state = state,
                    level = level,
                    slot = slot,
                    chainError = error,
                    checkedAt = at,
                    checks = tracking.check.checks + 1,
                    host = reader.host,
                    reason = if (final) null else ChainReason.AwaitingFinality,
                    nextCheckAt = if (final) null else at.plus(FINALITY[0]),
                ),
            attempts = 0,
        )
    }

    /**
     * A check that settled nothing. It keeps what the record already knew, says why, and sets the
     * next attempt — or stops automatic attempts once they have run out, which says so too. A
     * manual check doesn't use up the automatic budget.
     */
    private fun inconclusive(
        tracking: ChainTracking,
        reason: ChainReason,
        host: String?,
        manual: Boolean,
        level: ChainLevel? = null,
        slot: Long? = null,
    ): ChainTracking {
        val at = now()
        val attempts = if (manual) tracking.attempts else tracking.attempts + 1
        val known = tracking.check
        if (known.state.verified) {
            // Settled already; only finality is outstanding, and it is followed for a short while.
            val more = attempts < FINALITY.size
            return tracking.copy(
                check =
                    known.copy(
                        checkedAt = at,
                        checks = known.checks + 1,
                        host = host ?: known.host,
                        reason = if (more) ChainReason.AwaitingFinality else null,
                        nextCheckAt = if (more) at.plus(FINALITY[attempts]) else null,
                    ),
                attempts = attempts,
            )
        }
        if (reason == ChainReason.NoEndpoint) {
            // Nothing to ask until a build configures one; `load` arms it again then.
            return tracking.copy(
                check =
                    known.copy(
                        state = ChainState.Checking,
                        checkedAt = at,
                        checks = known.checks + 1,
                        reason = reason,
                        nextCheckAt = null,
                    ),
                attempts = attempts,
            )
        }
        val submittedAt = tracking.submittedAt ?: tracking.capturedAt
        val exhausted =
            !manual && (attempts >= MAX_ATTEMPTS || at.isAfter(submittedAt.plus(MAX_AGE)))
        return tracking.copy(
            check =
                ChainCheck(
                    state = if (exhausted) ChainState.Unresolved else ChainState.Checking,
                    level = level ?: known.level,
                    slot = slot ?: known.slot,
                    chainError = known.chainError,
                    checkedAt = at,
                    checks = known.checks + 1,
                    host = host ?: known.host,
                    reason = if (exhausted) ChainReason.GaveUp else reason,
                    nextCheckAt =
                        if (exhausted) null
                        else at.plus(BACKOFF[minOf(attempts, BACKOFF.size - 1)]),
                ),
            attempts = attempts,
        )
    }

    /**
     * Writes what a check found, if the record is still there and still about the same signature. A
     * settled answer is never replaced by an unsettled one.
     */
    private fun record(before: ChainTracking, after: ChainTracking) {
        synchronized(lock) {
            val current = store.get(before.key) ?: return
            if (current.signature != before.signature) return
            val merged =
                if (current.check.state.verified && !after.check.state.verified) {
                    current.copy(
                        check =
                            current.check.copy(
                                checkedAt = after.check.checkedAt,
                                checks = after.check.checks,
                                host = after.check.host ?: current.check.host,
                                nextCheckAt = after.check.nextCheckAt,
                            ),
                        attempts = after.attempts,
                    )
                } else after
            try {
                store.put(merged)
            } catch (_: IOException) {
                return
            }
            history.confirm(merged.key, merged.check)
            publishAll()
        }
    }

    // Call under the lock.
    private fun publishAll() {
        _checks.value = store.list().associate { it.key to it.check }
    }

    companion object {
        /** How soon the first check runs after the wallet names a signature. */
        val FIRST_CHECK: Duration = Duration.ofSeconds(2)

        /** The wait after each inconclusive check, by how many came before it. */
        val BACKOFF: List<Duration> =
            listOf(2L, 4L, 8L, 15L, 30L, 60L, 120L, 300L, 600L, 1200L, 1800L, 3600L, 7200L, 21600L)
                .map(Duration::ofSeconds)

        /** The waits while a settled result is followed to the finalized level. */
        val FINALITY: List<Duration> =
            listOf(8L, 15L, 30L, 60L, 120L, 300L).map(Duration::ofSeconds)

        /** After this many inconclusive automatic checks, automatic checks stop. */
        const val MAX_ATTEMPTS = 40

        /** And after this long since submission, whatever the count. */
        val MAX_AGE: Duration = Duration.ofDays(7)

        /**
         * No expiry is concluded sooner than this after submission. A blockhash fetched at the
         * confirmed level can be a few seconds ahead of the finalized chain, where it would read as
         * not valid *yet*; this is well past both that lag and the blockhash's own window.
         */
        val EXPIRY_GRACE: Duration = Duration.ofMinutes(3)

        const val MOST_SIGNATURES = 256

        /** Server states that already settled the chain question; nothing more to check. */
        private val SERVER_SETTLED =
            setOf(
                RequestState.REQUEST_STATE_CONFIRMED,
                RequestState.REQUEST_STATE_FAILED,
                RequestState.REQUEST_STATE_COMPLETED,
            )

        private val UNSETTLED = setOf(ActivityOutcome.Sent, ActivityOutcome.Unknown)

        fun reasonOf(e: SolanaException): ChainReason =
            when (e.problem) {
                SolanaProblem.NoEndpoint -> ChainReason.NoEndpoint
                SolanaProblem.Unreachable -> ChainReason.Unreachable
                SolanaProblem.RateLimited -> ChainReason.RateLimited
                SolanaProblem.Refused ->
                    if (e.detail == ChainEndpoints.WRONG_CLUSTER) ChainReason.WrongCluster
                    else ChainReason.Refused
                SolanaProblem.Unusable -> ChainReason.Unusable
            }

        /**
         * What a direct request's stored answer holds for tracking: the approved transaction and
         * the cluster and wallet the request is bound to. Null for anything that isn't a transfer
         * or a staking action with an approved transaction.
         */
        fun directSubmission(result: LocalResult): Submission? {
            if (result.answer != Answer.Approve) return null
            val approved = result.approvedTransaction ?: return null
            val request = result.request
            val (network, wallet) =
                request.transfer()?.let { it.network to it.wallet }
                    ?: request.staking()?.let { it.network to it.wallet }
                    ?: return null
            return Submission(
                key = result.key,
                origin = TrackingOrigin.Direct,
                network = network,
                wallet = wallet,
                transaction = approved.transaction.toByteArray(),
            )
        }
    }
}
