package io.github.brrenat.seekervault.activity

import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.skr.staking
import io.github.brrenat.seekervault.transactions.mint
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner's history of what this phone did (docs/guides/transfers.md#the-activity-record). One
 * record per request, written every time the answer to it changes, and kept after the answer itself
 * is gone.
 *
 * It records, it doesn't decide: nothing here answers a request, reaches a wallet, or talks to a
 * server. The only judgement it makes is what a record says, and the one it is most careful about
 * is which kind of signature it holds.
 */
class ActivityLog(
    private val store: ActivityStore,
    private val now: () -> Instant = Instant::now,
) {
    private val _records = MutableStateFlow<List<ActivityRecord>>(emptyList())

    // What the owner was shown about their rules, per request, until the answer is written. It is
    // a note about a screen, not a verdict to act on: nothing reads it back out to decide anything.
    private val shown = ConcurrentHashMap<RequestKey, ReviewedPolicy>()

    // And what an operation's provider named for it, until the record is written (SEE-94).
    private val named = ConcurrentHashMap<RequestKey, List<ReviewedValue>>()

    private val _loaded = MutableStateFlow(false)

    private val _unreadableRecords = MutableStateFlow(0)

    /** Every record, newest first. */
    val records: StateFlow<List<ActivityRecord>> = _records.asStateFlow()

    /** Files present in Activity that did not decode during the last complete read. */
    val unreadableRecords: StateFlow<Int> = _unreadableRecords.asStateFlow()

    /**
     * Whether the history has been read off the disk. Until it has — and after a read that failed —
     * [records] is not the owner's history but what this process happens to have seen, and anything
     * that counts what the phone has done must treat it as unknown rather than as none (SAW-026).
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Reads what is stored. A store that can't be read leaves the list as it was, and throws. */
    fun load() {
        // A failed refresh makes the history unknown again; stale in-memory rows are not a
        // successful read of what is required for a daily assessment now.
        _loaded.value = false
        val snapshot = store.snapshot()
        _records.value = snapshot.records
        _unreadableRecords.value = snapshot.unreadableRecords
        _loaded.value = true
    }

    /**
     * Records where [result] stands now, under the request it belongs to. Called again for the same
     * request, it replaces that one record rather than adding another: an answer sent twice, a
     * status checked ten times, and a restart in between are all one thing that happened.
     *
     * An approved transfer the sidecar never accepted is not recorded at all. Nothing was approved
     * anywhere and no wallet was ever opened, so there is nothing to have a record of; the approval
     * is removed again, and the owner sees the request waiting for them as before (SAW-021).
     */
    fun record(result: LocalResult, source: Connection?) {
        if (result.uncommittedTransfer) return
        // The assessment the owner read when they answered, or the one already stored: a status
        // checked ten times later must not quietly drop what the review said at the time.
        val policy = shown[result.key] ?: store.get(result.connectionId, result.requestId)?.policy
        publish(store.put(recordOf(result, source, now(), policy)))
    }

    /**
     * Records one operation the owner executed from a publisher's shared proposal (SEE-89).
     *
     * The record is derived by the caller, which it has to be: an operation's terms come from a
     * proposal and from the plugin that prepared its bytes, and this package has never heard of
     * either — it keeps codes, and reads none of them back to decide anything
     * ([ReviewedOperation]). Everything else is as it is for an answer: the record replaces the one
     * under its own key, and one that is about something else under that key is refused rather than
     * merged (`ActivityStore.put`).
     *
     * The one thing this does add is the assessment the owner read when they went ahead, which
     * reaches it the same way an answer's does — through [reviewed], under the same key — because
     * the caller that binds an operation and the caller that shows the owner their rules are not
     * the same code, and the record is about what they read (SAW-028, SEE-93). An outcome recorded
     * later keeps it rather than dropping it.
     *
     * Nothing about it is delivered anywhere. A proposal owes no server an answer, so there is no
     * outbox here and nothing to retry: this is the owner's own record and its only reader.
     */
    fun record(record: ActivityRecord): ActivityRecord {
        val key = RequestKey(record.connectionId, record.requestId)
        val stored = store.get(record.connectionId, record.requestId)
        val policy = record.policy ?: shown[key] ?: stored?.policy
        val references =
            record.operation?.references?.takeIf { it.isNotEmpty() }
                ?: named[key]
                ?: stored?.operation?.references
        return publish(
            store.put(
                record.copy(
                    policy = policy,
                    operation = record.operation?.copy(references = references.orEmpty()),
                )
            )
        )
    }

    /**
     * Advances an existing Activity record from server state observed by Sync. It never creates an
     * Activity row: only an action the owner already took can do that. It also preserves every
     * locally authoritative field — reviewed terms, policy snapshot, signature, and answer time —
     * while using the same request-state interpretation as [record].
     */
    fun reconcile(request: ActionRequest): ActivityRecord? {
        if (!request.hasRef()) return null
        val existing = store.get(request.ref.connectionId, request.ref.requestId) ?: return null
        if (existing.kind != ActivityKind.Transfer || existing.signature == null) return existing
        if (
            existing.outcome == ActivityOutcome.Confirmed ||
                existing.outcome == ActivityOutcome.ChainFailed
        ) {
            return existing
        }
        val outcome =
            when (request.state) {
                RequestState.REQUEST_STATE_CONFIRMED -> ActivityOutcome.Confirmed
                RequestState.REQUEST_STATE_FAILED -> ActivityOutcome.ChainFailed
                RequestState.REQUEST_STATE_UNKNOWN -> ActivityOutcome.Unknown
                RequestState.REQUEST_STATE_SUBMITTED -> ActivityOutcome.Sent
                else -> return existing
            }
        val confirmation = request.outcome.confirmation.takeIf { request.outcome.hasConfirmation() }
        val updated =
            existing.copy(
                recordedAt = now(),
                outcome = outcome,
                detail = request.outcome.detail.takeIf(String::isNotEmpty) ?: existing.detail,
                checkedWith =
                    confirmation?.endpoint?.takeIf(String::isNotEmpty) ?: existing.checkedWith,
            )
        return publish(store.put(updated))
    }

    // Newest first, and one row per request: a record written again replaces the one it is about.
    private fun publish(stored: ActivityRecord): ActivityRecord {
        _records.value =
            (_records.value.filterNot { it.key == stored.key } + stored).sortedWith(
                compareByDescending<ActivityRecord> { it.answeredAt }.thenBy { it.requestId }
            )
        return stored
    }

    /**
     * What the review screen showed the owner about their rules for [key] (SAW-028). The next write
     * of that request's record keeps it, so the history says what the assessment said at the time —
     * including that the owner went ahead with a warning in front of them.
     *
     * It is recorded, not consulted: nothing here re-reads it to allow, refuse, or re-assess
     * anything. An assessment is made afresh every time one is needed (`PolicyEvaluator`).
     */
    fun reviewed(key: RequestKey, policy: ReviewedPolicy) {
        shown[key] = policy
    }

    /**
     * Keeps the identifiers an operation's provider named, for the record about to be written
     * (SEE-94).
     *
     * The same channel [reviewed] is, and for the same reason: the code that binds an operation and
     * the code that read its bytes are not the same code, and the record is about both. Public
     * identifiers only, and never a URL.
     */
    fun referenced(key: RequestKey, references: List<ReviewedValue>) {
        named[key] = references
    }

    /** Removes every record. The owner asked for it; nothing else calls it. */
    fun clear() {
        store.clear()
        shown.clear()
        named.clear()
        _records.value = emptyList()
        _unreadableRecords.value = 0
        // Cleared is read: the owner emptied it themselves, and an empty history is a known one.
        _loaded.value = true
    }

    private companion object {
        fun recordOf(
            result: LocalResult,
            source: Connection?,
            at: Instant,
            policy: ReviewedPolicy?,
        ): ActivityRecord {
            val request = result.request
            val transfer = request.transfer()
            return ActivityRecord(
                connectionId = result.connectionId,
                requestId = result.requestId,
                source = source?.label ?: result.connectionId,
                serverHost = source?.serverUrl?.let(PairingCodes::hostOf).orEmpty(),
                kind = kindOf(result),
                answeredAt = result.answeredAt,
                recordedAt = at,
                outcome = outcomeOf(result),
                transfer =
                    transfer?.let {
                        ReviewedTransfer(
                            wallet = it.wallet,
                            network = it.network,
                            recipient = it.recipient,
                            amount = it.amount.toULong().toString(),
                            mint = it.mint(),
                            preparedVersion = result.approvedTransaction?.version ?: 0,
                        )
                    },
                policy = policy,
                signature = signatureOf(result),
                detail = detailOf(result),
                checkedWith =
                    request.outcome.confirmation.endpoint.takeIf {
                        request.outcome.hasConfirmation() && it.isNotEmpty()
                    },
            )
        }

        fun kindOf(result: LocalResult): ActivityKind =
            when {
                result.request.transfer() != null -> ActivityKind.Transfer
                result.request.staking() != null -> ActivityKind.Staking
                result.request.signMessage() != null -> ActivityKind.MessageSignature
                result.request.action.kindCase == Action.KindCase.ACK ->
                    ActivityKind.Acknowledgement
                else -> ActivityKind.Other
            }

        /**
         * The signature the wallet made, in base58. A message's signature and a transaction's ID
         * are both 64 bytes and both belong here; which one it is comes from the record's kind, and
         * never from the signature itself.
         */
        fun signatureOf(result: LocalResult): String? =
            when (val outcome = result.signing) {
                is SigningOutcome.Signed -> encodeBase58(outcome.signature.toByteArray())
                is SigningOutcome.Sent -> encodeBase58(outcome.signature.toByteArray())
                else -> null
            }

        fun detailOf(result: LocalResult): String? {
            val outcome = result.signing
            val local =
                when (outcome) {
                    is SigningOutcome.Failed -> outcome.detail
                    is SigningOutcome.Unresolved -> outcome.detail
                    else -> null
                }
            // What the server said about how it ended comes second: the phone's own account of a
            // wallet that never answered is the more precise one.
            return local ?: result.request.outcome.detail.takeIf(String::isNotEmpty)
        }

        /**
         * Where the record stands. An answer that hasn't reached the server yet says so; one that
         * can't ever reach it says that instead, because it is the owner's action either way and
         * hiding it would lose it.
         */
        fun outcomeOf(result: LocalResult): ActivityOutcome {
            when (result.delivery) {
                Delivery.Undeliverable -> return ActivityOutcome.NotDelivered
                Delivery.Superseded -> return ActivityOutcome.Superseded
                else -> Unit
            }
            val waiting = result.delivery == Delivery.Waiting
            return when (result.answer) {
                Answer.Acknowledge ->
                    if (waiting) ActivityOutcome.Waiting else ActivityOutcome.Acknowledged
                Answer.Reject -> if (waiting) ActivityOutcome.Waiting else ActivityOutcome.Rejected
                Answer.Approve ->
                    when (result.signing) {
                        null -> ActivityOutcome.Waiting
                        is SigningOutcome.Signed -> ActivityOutcome.MessageSigned
                        is SigningOutcome.Sent -> sentOutcome(result)
                        SigningOutcome.Declined -> ActivityOutcome.DeclinedInWallet
                        is SigningOutcome.Failed -> ActivityOutcome.NotSigned
                        // For a message nothing was signed; for a transfer nobody knows, and this
                        // record says so rather than picking the comfortable answer.
                        is SigningOutcome.Unresolved ->
                            if (result.request.transfer() != null) ActivityOutcome.Unknown
                            else ActivityOutcome.NotSigned
                    }
            }
        }

        /**
         * What became of a transaction the wallet sent. Only the server's word from the chain makes
         * it confirmed or failed; until then it is sent, and sent is not paid.
         */
        fun sentOutcome(result: LocalResult): ActivityOutcome =
            when (result.request.state) {
                RequestState.REQUEST_STATE_CONFIRMED -> ActivityOutcome.Confirmed
                RequestState.REQUEST_STATE_FAILED -> ActivityOutcome.ChainFailed
                RequestState.REQUEST_STATE_UNKNOWN -> ActivityOutcome.Unknown
                else -> ActivityOutcome.Sent
            }
    }
}
