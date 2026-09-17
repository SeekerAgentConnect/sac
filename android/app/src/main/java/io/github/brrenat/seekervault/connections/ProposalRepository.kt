package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedOperation
import io.github.brrenat.seekervault.activity.ReviewedValue
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.proposals.BindingProblem
import io.github.brrenat.seekervault.proposals.ExecutionBinding
import io.github.brrenat.seekervault.proposals.ProposalDismissal
import io.github.brrenat.seekervault.proposals.ProposalExecution
import io.github.brrenat.seekervault.proposals.ProposalExpectation
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalPlugin
import io.github.brrenat.seekervault.proposals.ProposalProblem
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalResult
import io.github.brrenat.seekervault.proposals.ProposalReview
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.bindingProblem
import io.github.brrenat.seekervault.proposals.proposalFrom
import io.github.brrenat.seekervault.proposals.proposalPlugin
import io.github.brrenat.seekervault.proposals.proposalStanding
import io.github.brrenat.seekervault.proposals.settled
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.serverSupport
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What reading a feed came to (SEE-89). */
sealed interface FeedRefresh {
    /** How many documents were stored, and which rules the refused ones broke. */
    data class Read(val applied: Int, val refused: List<ProposalProblem>) : FeedRefresh

    /** The gateway couldn't be reached, or answered with something unusable. */
    data class Failed(val outcome: CheckOutcome) : FeedRefresh

    /**
     * This build has no gateway to read a feed through. It is said plainly because the alternative
     * is worse: a feed that looks read and has nothing in it (SEE-90 supplies the gateway, SEE-91
     * the live stream that pushes the same documents).
     */
    data object NoFeed : FeedRefresh

    /** There is no such feed connection on this phone. A direct connection is never read here. */
    data object NotAFeed : FeedRefresh
}

/** What applying one delivered document came to. */
sealed interface ProposalApplied {
    /** It was new, or a higher revision of something held, and it was written. */
    data class Stored(val record: ProposalRecord) : ProposalApplied

    /** The same revision with the same terms: the document was already held, and nothing moved. */
    data class Unchanged(val record: ProposalRecord) : ProposalApplied

    /** It broke a rule and was not applied. What the phone already held is untouched. */
    data class Refused(val problem: ProposalProblem) : ProposalApplied

    data object NotAFeed : ProposalApplied
}

/** What beginning one operation came to. */
sealed interface ExecutionOutcome {
    /**
     * It was bound and written down. The wallet may now be asked, with these bytes and no others,
     * and whatever it answers is recorded through [ProposalRepository.recordOutcome].
     */
    data class Begun(val record: ProposalRecord) : ExecutionOutcome

    /** One rule stopped it, and nothing was written. No wallet was opened. */
    data class Refused(val problem: BindingProblem) : ExecutionOutcome

    /** The proposal, or the feed it came from, is no longer on this phone. */
    data object Gone : ExecutionOutcome
}

/**
 * What this phone holds about publishers' proposals, and everything the owner does about one
 * (SEE-89, docs/wiki/shared-proposals.md).
 *
 * A proposal is broadcast: the publisher sends one document and everyone subscribed receives it
 * identically. So the two halves of a record belong to different people, and this class is where
 * that separation is kept.
 *
 * - The publisher's half is applied, never merged. A document is validated against the feed it
 *   arrived on, and a higher revision replaces what was held whole.
 * - This device's half — the dismissal, the review and its exact revision, the binding and what the
 *   wallet did — is written here and read nowhere else. **Nothing on this side is published.**
 *   There is no outbox, no `SubmitResult`, no sync upload, and no per-subscriber state on the
 *   publishing server for one owner's action to change: dismissing or executing a proposal here
 *   changes nothing about the same proposal on anyone else's phone.
 * - Applying is idempotent, because the transport is not trustworthy about repetition: a replayed
 *   event, a duplicate push and a reloaded snapshot are the same document arriving again ([apply]).
 * - One execution per proposal, ever, and it is written before the wallet is opened, so a second
 *   tap finds it and stops ([ProposalExecution]).
 *
 * Nothing here reaches a publisher or a gateway except [refresh], and nothing here opens a wallet:
 * the caller asks the wallet between [beginExecution] and [recordOutcome], exactly as it does
 * between an approval and a signing outcome for a private request (SAW-017).
 */
class ProposalRepository(
    private val store: ProposalStore,
    /**
     * The connections as they are now. A proposal is only ever held under the feed it arrived on,
     * so this is what says which records still have a feed behind them.
     */
    private val connections: () -> List<Connection>,
    /** The plugins compiled into this build, for deciding what it supports (SEE-86, SEE-88). */
    private val plugins: PluginRegistry,
    /**
     * SEE-97 makes this the owner's choice; until then core asks for production, as it does
     * everywhere else a plugin is resolved.
     */
    private val environment: PluginEnvironment = PluginEnvironment.Production,
    /**
     * How a publisher's proposals are read, when this build has a gateway to read them through.
     * Optional because the gateway is SEE-90: without one, [refresh] says so and reads nothing.
     */
    private val feed: ProposalFeed? = null,
    /**
     * The owner's own history, which an execution is written to as well (SAW-023). Optional for the
     * same reason it is on [ConnectionRepository]: a phone without one still executes and records.
     */
    private val history: ActivityLog? = null,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val _proposals = MutableStateFlow<List<ProposalRecord>>(emptyList())

    /** Every proposal this phone holds, for every feed it reads, oldest first. */
    val proposals: StateFlow<List<ProposalRecord>> = _proposals.asStateFlow()

    /**
     * Reads what is stored, drops what no feed owns any more, and settles any operation the app
     * closed on.
     */
    suspend fun load() = locked {
        val ids = connections().map { it.id }.toSet()
        store.connectionIds().filter { it !in ids }.forEach(store::deleteConnection)
        publish()
        // The app closed while an operation was with the wallet. Whatever the wallet did, this
        // phone never learned it, so the execution is settled as unresolved rather than left open —
        // and never as a failure, because the transaction may well have been sent. The wallet is
        // not asked again either way (SAW-017).
        _proposals.value
            .filter { it.execution?.outcome == ProposalOutcome.Pending }
            .forEach { settle(it, ProposalOutcome.Unresolved(APP_CLOSED)) }
        publish()
    }

    /**
     * Reads [id]'s feed through the gateway and applies every document it holds.
     *
     * The publisher is not contacted, and nothing about this phone goes out: a subscription names a
     * channel, and that is the whole of what the gateway learns.
     */
    suspend fun refresh(id: String): FeedRefresh {
        val connection = feedConnection(id) ?: return FeedRefresh.NotAFeed
        val source = feed ?: return FeedRefresh.NoFeed
        val messages =
            try {
                source.proposals(FeedReference(connection.serverUrl, connection.serverId))
            } catch (e: GatewayException) {
                return FeedRefresh.Failed(e.kind.toOutcome())
            }
        var applied = 0
        val refused = mutableListOf<ProposalProblem>()
        for (message in messages) {
            when (val outcome = apply(id, message)) {
                is ProposalApplied.Stored -> applied++
                is ProposalApplied.Unchanged -> Unit
                is ProposalApplied.Refused -> refused += outcome.problem
                is ProposalApplied.NotAFeed -> return FeedRefresh.NotAFeed
            }
        }
        return FeedRefresh.Read(applied, refused.toList())
    }

    /**
     * Applies one document delivered on [connectionId]'s feed.
     *
     * This is the one path a proposal reaches the phone by, whether it came from a snapshot, a
     * stream event, or a push that arrived twice, and it is idempotent by construction:
     * - the same revision with the same terms writes nothing at all, so a replay cannot touch a
     *   dismissal, a review, or an execution;
     * - a lower revision is refused, so a replayed older document cannot restore terms the
     *   publisher has moved past;
     * - a higher revision replaces the publisher's half and leaves this device's half exactly where
     *   it was — which is what makes a review of the older terms detectably stale rather than
     *   silently applied to the new ones ([bindingProblem]);
     * - the same revision with *different* terms is a contradiction, because a revision is the
     *   publisher's promise about its content. The phone keeps the terms it validated and stops
     *   executing anything from the proposal until the publisher says something new
     *   ([ProposalProblem.ChangedWithoutRevision]).
     */
    suspend fun apply(connectionId: String, message: WireProposal): ProposalApplied = locked {
        val connection = feedConnection(connectionId) ?: return@locked ProposalApplied.NotAFeed
        val held = stored(connection, message.proposalId)
        val result =
            proposalFrom(
                message,
                ProposalExpectation(
                    serverId = connection.serverId,
                    heldRevision = held?.proposal?.revision,
                ),
            )
        val proposal =
            when (result) {
                is ProposalResult.Invalid -> return@locked ProposalApplied.Refused(result.problem)
                is ProposalResult.Valid -> result.proposal
            }
        val known = held?.proposal
        if (known != null && proposal.revision == known.revision) {
            if (proposal == known) return@locked ProposalApplied.Unchanged(held)
            val contradiction = ProposalProblem.ChangedWithoutRevision
            write(held.copy(refused = contradiction), connection)
            publish()
            return@locked ProposalApplied.Refused(contradiction)
        }
        // A refusal belongs to the revision it happened at: a higher revision is the publisher
        // saying something new, and it is judged on its own.
        val record =
            held?.copy(proposal = proposal, refused = null)
                ?: ProposalRecord(connectionId = connectionId, proposal = proposal)
        write(record, connection)
        publish()
        ProposalApplied.Stored(record)
    }

    /**
     * Hides a proposal on this device, at the revision the owner was looking at.
     *
     * It is final for the proposal's identity. A replayed delivery cannot bring it back, and
     * neither can a new revision: a publisher that could re-open a dismissal by changing a number
     * would have a way to keep putting the same proposal in front of someone who said no.
     */
    suspend fun dismiss(connectionId: String, proposalId: String): ProposalRecord? = locked {
        val connection = feedConnection(connectionId) ?: return@locked null
        val record = stored(connection, proposalId) ?: return@locked null
        record.dismissed?.let {
            return@locked record
        }
        record.copy(dismissed = ProposalDismissal(record.proposal.revision, now())).also {
            write(it, connection)
            publish()
        }
    }

    /**
     * Records what the owner reviewed and chose here, against the exact revision they read.
     *
     * The choice is theirs and stays on this phone. Once an operation has begun, what was bound
     * stands: a later review would contradict the record of what was actually executed, so nothing
     * is written and the record is returned as it is.
     */
    suspend fun review(
        connectionId: String,
        proposalId: String,
        choice: ParameterChoice,
    ): ProposalRecord? = locked {
        val connection = feedConnection(connectionId) ?: return@locked null
        val record = stored(connection, proposalId) ?: return@locked null
        if (record.execution != null) return@locked record
        record.copy(review = ProposalReview(record.proposal.revision, choice, now())).also {
            write(it, connection)
            publish()
        }
    }

    /**
     * Binds one operation and writes it down, before any wallet is opened.
     *
     * Everything that has to hold is checked in one place ([bindingProblem]): what this device has
     * already done, whether the proposal still stands, whether the owner reviewed *these* terms,
     * whether the plugin and its contract are the ones bound, whether the wallet selected now is
     * the one bound, and whether what was prepared can still be included.
     *
     * It is written under the same lock that reads it, which is what makes a double tap harmless:
     * the second call sees the first one's record and answers [BindingProblem.AlreadyExecuted].
     * Nothing is ever retried automatically — not here, and not anywhere else: the owner starts an
     * operation, or it does not happen.
     */
    suspend fun beginExecution(
        connectionId: String,
        proposalId: String,
        binding: ExecutionBinding,
        wallet: SelectedWallet?,
    ): ExecutionOutcome = locked {
        val connection = feedConnection(connectionId) ?: return@locked ExecutionOutcome.Gone
        val record = stored(connection, proposalId) ?: return@locked ExecutionOutcome.Gone
        bindingProblem(record, binding, wallet, support(connection), now())?.let {
            return@locked ExecutionOutcome.Refused(it)
        }
        record
            .copy(execution = ProposalExecution(binding = binding, startedAt = now()))
            .also {
                write(it, connection)
                publish()
            }
            .let(ExecutionOutcome::Begun)
    }

    /**
     * Records what the wallet did, once. The first word stands: an outcome already settled is not
     * replaced, so a late second answer about one wallet interaction cannot turn a sent transaction
     * into a failed one, and nothing infers a failure from a response that never arrived.
     */
    suspend fun recordOutcome(
        connectionId: String,
        proposalId: String,
        outcome: ProposalOutcome,
    ): ProposalRecord? = locked {
        val connection = feedConnection(connectionId) ?: return@locked null
        val record = stored(connection, proposalId) ?: return@locked null
        val execution = record.execution ?: return@locked null
        if (execution.outcome.settled) return@locked record
        settle(record, outcome).also { publish() }
    }

    /** Where a proposal stands for this owner, derived afresh ([proposalStanding]). */
    fun standing(record: ProposalRecord): ProposalStanding =
        proposalStanding(record, supportOf(record.connectionId), now())

    /** Which plugin this build would use for a proposal, or why none would ([proposalPlugin]). */
    fun plugin(record: ProposalRecord): ProposalPlugin =
        proposalPlugin(record.proposal, plugins, environment)

    /** Everything held for one feed, oldest first. */
    fun proposalsFor(connectionId: String): List<ProposalRecord> =
        _proposals.value.filter { it.connectionId == connectionId }

    fun proposal(connectionId: String, proposalId: String): ProposalRecord? =
        _proposals.value.firstOrNull {
            it.connectionId == connectionId && it.key.proposalId == proposalId
        }

    // Settles an execution and writes the record. Call it under the lock.
    private fun settle(record: ProposalRecord, outcome: ProposalOutcome): ProposalRecord {
        val execution = checkNotNull(record.execution)
        return record.copy(execution = execution.copy(outcome = outcome, settledAt = now())).also {
            write(it, connections().firstOrNull { c -> c.id == it.connectionId })
        }
    }

    /**
     * Writes a record, and writes the owner's own history at the same time when there is an
     * operation to record (SAW-023). Every write goes through here, so a record can never be
     * forgotten at one call site and written at another.
     *
     * A dismissal or a review writes no history: nothing was done, and the owner's history is of
     * what this phone did.
     */
    private fun write(record: ProposalRecord, connection: Connection?) {
        store.put(record)
        if (record.execution != null) history?.record(operationRecord(record, connection, now()))
    }

    private fun publish() {
        _proposals.value =
            connections()
                .filter { it.mode == ConnectionMode.GatewayFeed }
                .flatMap { connection ->
                    // A record is read only for the publisher whose feed it is held under. A file
                    // that says otherwise is not this feed's proposal, whatever directory it is in.
                    store.listFor(connection.id).filter {
                        it.key.serverId == connection.serverId
                    }
                }
    }

    private fun feedConnection(id: String): Connection? =
        connections().firstOrNull { it.id == id && it.mode == ConnectionMode.GatewayFeed }

    private fun stored(connection: Connection, proposalId: String): ProposalRecord? =
        store.get(connection.id, proposalId)?.takeIf { it.key.serverId == connection.serverId }

    private fun support(connection: Connection): ServerSupport =
        serverSupport(connection.server, plugins, environment)

    private fun supportOf(connectionId: String): ServerSupport =
        feedConnection(connectionId)?.let(::support) ?: ServerSupport.Unknown

    private suspend fun <T> locked(block: () -> T): T = lock.withLock {
        withContext(io) { block() }
    }

    private companion object {
        const val APP_CLOSED =
            "The app closed while the operation was with the wallet, so this phone never learned " +
                "whether it was sent."

        /**
         * The owner's record of one operation: the binding, written down, with the outcome as it
         * stands. The terms are codes and public values — the operation, the plugin, the proposal
         * revision, the address that paid and the parameters this owner chose — and nothing about
         * any of it is delivered anywhere.
         */
        fun operationRecord(
            record: ProposalRecord,
            connection: Connection?,
            at: Instant,
        ): ActivityRecord {
            val execution = checkNotNull(record.execution)
            val binding = execution.binding
            return ActivityRecord(
                connectionId = record.connectionId,
                // The publisher's own ID for the proposal. A record is one per proposal, so an
                // outcome written again replaces it rather than adding a second row.
                requestId = record.key.proposalId,
                source = connection?.label ?: record.connectionId,
                // For a feed this is the gateway's host: the publisher's own address is not
                // something this phone has, because it never contacted it.
                serverHost = connection?.serverUrl?.let(PairingCodes::hostOf).orEmpty(),
                kind = ActivityKind.Operation,
                answeredAt = execution.startedAt,
                recordedAt = at,
                outcome = activityOutcome(execution.outcome),
                operation =
                    ReviewedOperation(
                        operation = record.proposal.operation.value,
                        plugin = binding.plugin.value,
                        contract = binding.contract,
                        revision = binding.revision,
                        wallet = binding.wallet,
                        network = binding.network,
                        preparedVersion = binding.preparedVersion,
                        values =
                            binding.choice.values
                                .map { (key, value) -> ReviewedValue(key.value, valueText(value)) }
                                .sortedBy { it.key },
                    ),
                signature =
                    (execution.outcome as? ProposalOutcome.Submitted)?.let {
                        encodeBase58(it.signature.toByteArray())
                    },
                detail =
                    when (val outcome = execution.outcome) {
                        is ProposalOutcome.Failed -> outcome.detail
                        is ProposalOutcome.Unresolved -> outcome.detail
                        else -> null
                    },
            )
        }

        /**
         * Where the record stands. The mapping is the transfer path's own, for the same reasons:
         * sent is not paid, and an outcome nobody knows is [ActivityOutcome.Unknown] rather than a
         * failure, because the wallet may have sent the transaction before this phone lost it.
         */
        fun activityOutcome(outcome: ProposalOutcome): ActivityOutcome =
            when (outcome) {
                is ProposalOutcome.Pending -> ActivityOutcome.Waiting
                is ProposalOutcome.Submitted -> ActivityOutcome.Sent
                is ProposalOutcome.Declined -> ActivityOutcome.DeclinedInWallet
                is ProposalOutcome.Failed -> ActivityOutcome.NotSigned
                is ProposalOutcome.Unresolved -> ActivityOutcome.Unknown
            }

        /** One chosen parameter as text, in the units it was chosen in. Nothing is rounded. */
        fun valueText(value: ParameterValue): String =
            when (value) {
                is ParameterValue.Amount -> value.baseUnits.toString()
                is ParameterValue.Selected -> value.option.value
                is ParameterValue.Count -> value.value.toString()
            }
    }
}
