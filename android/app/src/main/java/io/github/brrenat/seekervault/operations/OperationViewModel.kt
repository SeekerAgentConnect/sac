package io.github.brrenat.seekervault.operations

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ReviewedValue
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ExecutionOutcome
import io.github.brrenat.seekervault.connections.FeedRefresh
import io.github.brrenat.seekervault.connections.ProposalRepository
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.inbox.RequestAssessment
import io.github.brrenat.seekervault.inbox.reviewedPolicy
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.ActionPlugin
import io.github.brrenat.seekervault.plugins.ActionSubject
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginPreparation
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.plugins.PluginResolution
import io.github.brrenat.seekervault.plugins.pluginFacts
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.proposals.BindingProblem
import io.github.brrenat.seekervault.proposals.ExecutionBinding
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.proposalPlugin
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.WalletRepository
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reviewing one of a publisher's proposals and acting on it (SEE-93).
 *
 * This is the path SEE-89 built the record for and SEE-86 built the boundary for, joined up: the
 * owner opens a signal a feed delivered, chooses their own parameters, has the plugin prepare the
 * exact bytes, reads what this phone made of those bytes, sees what their own rules make of the
 * facts, and approves — after which the wallet is asked once and whatever it answers is written
 * down (docs/wiki/shared-proposals.md, docs/wiki/client-plugins.md).
 *
 * ## What it does not do
 *
 * It sends nothing to the publisher and nothing to the gateway, because there is nowhere to send
 * it: a feed connection has no outbox, no result upload and no per-subscriber state
 * (`ProposalRepository`). It never prepares or signs by itself — every step here starts with the
 * owner touching something. And it never retries: an operation that failed, was declined, or whose
 * answer never arrived stays as it is.
 *
 * ## The order, which is the whole of the safety
 *
 * 1. Preparing writes down what the owner chose ([ProposalRepository.review]) and asks the plugin
 *    for bytes. Changing a parameter throws the preparation away, so what is on screen is always a
 *    reading of bytes that exist.
 * 2. Approving re-reads the owner's rules from disk and compares the verdict with the one they were
 *    shown. A verdict that moved while they were reading is not one they read.
 * 3. The wallet lock is taken, and only then is the operation bound and written down
 *    ([ProposalRepository.beginExecution]) — which is where expiry, the revision, the choice, the
 *    plugin and the wallet are all checked at once, on the far side of the wait, exactly as a
 *    transfer's window is (SAW-046).
 * 4. The wallet is asked once. The answer is recorded once; the first word stands.
 */
class OperationViewModel(
    private val proposals: ProposalRepository,
    /**
     * The connections as they are now: a feed's label, and which held proposals still have a feed
     * behind them.
     *
     * The list and whether it has been read, rather than the repository that holds them, because
     * that is the whole of what this needs. The server-facing half of a connection — pairing, the
     * credential, requests, results, the outbox — has nothing to do with a broadcast, and a feed
     * has no part of it (SEE-89).
     */
    private val connections: StateFlow<List<Connection>>,
    private val connectionsLoaded: StateFlow<Boolean>,
    private val wallet: WalletRepository,
    private val policies: PolicyEvaluator,
    private val history: ActivityLog,
    private val plugins: PluginRegistry,
    /** SEE-97 makes this the owner's choice; until then core asks for production, as always. */
    private val environment: PluginEnvironment = PluginEnvironment.Production,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(OperationsUiState())
    val state: StateFlow<OperationsUiState> = _state.asStateFlow()

    // One review at a time, which is what the screen is: a single destination for a single
    // proposal. Anything about a proposal nobody is looking at is in the list and nowhere else.
    private val _review = MutableStateFlow<OperationReview?>(null)
    val review: StateFlow<OperationReview?> = _review.asStateFlow()

    // Preparing and approving are serialized against each other, so a second tap on either finds
    // the first one's work instead of starting beside it.
    private val busy = Mutex()

    init {
        viewModelScope.launch {
            // The connections are read first, and waited for. A proposal is only ever held under
            // the feed it arrived on, so a read that could not see the feeds would conclude every
            // one of them was gone and drop what they hold (`ProposalRepository.load`).
            connectionsLoaded.first { it }
            proposals.load()
        }
        viewModelScope.launch {
            combine(proposals.proposals, connections) { held, live ->
                    OperationsUiState(
                        loaded = true,
                        records = held,
                        feeds = live.filter { it.mode == ConnectionMode.GatewayFeed },
                    )
                }
                .collect { fresh ->
                    _state.value = fresh
                    // A proposal that moved under an open review — a higher revision, a
                    // cancellation, the feed removed — is reflected rather than remembered.
                    _review.update { open -> open?.let { refreshed(it, fresh) } }
                }
        }
    }

    /**
     * Where one proposal stands for this owner, derived afresh on every read ([ProposalStanding]).
     *
     * It is asked for rather than stored, and it is the same answer the gate between a review and
     * the wallet asks for, so what a list shows and what an approval allows cannot drift apart
     * (SEE-89).
     */
    fun standing(record: ProposalRecord): ProposalStanding = proposals.standing(record)

    /** Reads the feed this proposal belongs to, through the gateway. It publishes nothing. */
    fun refresh(connectionId: String) {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = it.refreshing + connectionId) }
            val outcome =
                try {
                    proposals.refresh(connectionId)
                } finally {
                    _state.update { it.copy(refreshing = it.refreshing - connectionId) }
                }
            _state.update { it.copy(lastRefresh = outcome) }
        }
    }

    /**
     * Opens one proposal for review: asks the plugin what has to be chosen, and starts the fields
     * at whatever the owner chose last time, or at what the plugin suggests.
     */
    fun open(connectionId: String, proposalId: String) {
        val record = proposals.proposal(connectionId, proposalId) ?: return
        val resolved = plugin(record)
        val form = resolved?.parameters(subjectFor(record)) ?: ParameterForm()
        _review.value =
            OperationReview(
                connectionId = connectionId,
                proposalId = proposalId,
                record = record,
                standing = proposals.standing(record),
                form = form,
                // Where the owner may carry on outside the app, if the operation's provider has
                // anywhere truthful to send them (SEE-94). It comes from the terms alone, so it
                // survives a restart and needs no preparation — and no URL is ever stored.
                destinations = resolved?.destinations(subjectFor(record)).orEmpty(),
                // A review already written for these terms is what the owner last chose about
                // them; anything else starts from the plugin's own suggestion.
                choice =
                    record.review?.takeIf { it.revision == record.proposal.revision }?.choice
                        ?: initial(form),
                served = resolved != null,
            )
        // The rules are read when the review opens, so the owner is not shown a gap where the
        // assessment will be.
        assess()
    }

    fun close() {
        _review.value = null
    }

    /** What the owner chose, which stays on this phone. Nothing is prepared until they ask. */
    fun choose(key: ParameterKey, value: ParameterValue) {
        _review.update { open ->
            open?.copy(
                choice = ParameterChoice(open.choice.values + (key to value)),
                // The bytes were for the old numbers. Keeping them on screen beside new ones is
                // how somebody comes to approve a transaction they are not looking at.
                prepared = null,
                inspection = null,
                failure = null,
                problem = null,
                assessment = null,
                acknowledged = false,
            )
        }
        assess()
    }

    /** Asks the plugin for the exact bytes, with the parameters as they stand. */
    fun prepare() {
        val open = _review.value ?: return
        if (open.preparing) return
        _review.update { it?.copy(preparing = true, failure = null, problem = null) }
        viewModelScope.launch {
            busy.withLock {
                val record = proposals.proposal(open.connectionId, open.proposalId)
                val resolved = record?.let(::plugin)
                if (record == null || resolved == null) {
                    _review.update { it?.copy(preparing = false) }
                    return@withLock
                }
                // What the owner chose is written down before anything is prepared, because it is
                // what the binding is checked against and what the record says they reviewed.
                proposals.review(open.connectionId, open.proposalId, open.choice)
                val outcome =
                    try {
                        val prepared = resolved.prepare(subjectFor(record), open.choice)
                        val inspection = resolved.inspect(subjectFor(record), open.choice, prepared)
                        Prepared(prepared, inspection)
                    } catch (e: PluginFailure) {
                        Prepared(failure = OperationFailure(e.code, e.explanation, e.detail))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A plugin that threw something else is a plugin that prepared nothing,
                        // and this says so rather than letting the app die for it.
                        Prepared(failure = OperationFailure(UNEXPECTED, null, e.message))
                    }
                _review.update {
                    it?.copy(
                        preparing = false,
                        prepared = outcome.prepared,
                        inspection = outcome.inspection,
                        failure = outcome.failure,
                        acknowledged = false,
                        assessment = null,
                    )
                }
            }
            assess()
        }
    }

    /** The owner says they mean to go ahead past the warnings they read. */
    fun acknowledge(acknowledged: Boolean) {
        _review.update { it?.copy(acknowledged = acknowledged) }
    }

    /** Hides a proposal here, for good, for its identity rather than for this revision. */
    fun dismiss(connectionId: String, proposalId: String) {
        viewModelScope.launch {
            proposals.dismiss(connectionId, proposalId)
            if (_review.value?.proposalId == proposalId) close()
        }
    }

    /**
     * Goes ahead: re-reads the rules, binds what was reviewed, and asks the wallet once.
     *
     * [reviewed] is the wallet the screen showed the owner. A wallet that changed while they were
     * reading stops this rather than signing with one they did not see.
     */
    fun approve(reviewed: SelectedWallet?) {
        val open = _review.value ?: return
        if (open.sending) return
        val prepared = open.prepared ?: return
        val inspection = open.inspection ?: return
        // Input validation, and never a rule to overrule: bytes this phone could not account for
        // whole are not put to a wallet (docs/security.md#verification-versus-advisory-rules).
        if (!inspection.approvable) return
        _review.update { it?.copy(sending = true, problem = null, failure = null) }
        viewModelScope.launch {
            try {
                go(open, prepared, reviewed)
            } finally {
                _review.update { it?.copy(sending = false) }
            }
        }
    }

    private suspend fun go(
        open: OperationReview,
        prepared: PluginPreparation,
        reviewed: SelectedWallet?,
    ) {
        val shown = open.assessment?.consent
        val fresh = assess() ?: return
        // A verdict that moved while they were reading is not the verdict they read.
        if (shown != null && shown != fresh.consent) {
            stop(OperationProblem.RulesChanged)
            return
        }
        if (fresh.decision.warns && _review.value?.acknowledged != true) {
            stop(OperationProblem.NotAcknowledged)
            return
        }
        val selected = wallet.wallet.value
        if (
            selected == null ||
                reviewed == null ||
                selected.address != reviewed.address ||
                selected.network != reviewed.network
        ) {
            stop(
                if (selected == null) OperationProblem.NoWallet else OperationProblem.WalletChanged
            )
            return
        }
        val record = proposals.proposal(open.connectionId, open.proposalId) ?: return
        val resolved = plugin(record) ?: return
        val binding =
            ExecutionBinding(
                revision = record.proposal.revision,
                choice = open.choice,
                wallet = selected.address,
                network = selected.network.network,
                plugin = resolved.descriptor.id,
                contract = resolved.descriptor.contract,
                preparedVersion = prepared.version,
                contentHash = hash(prepared.transaction),
                expiresAtEpochSeconds = prepared.expiresAtEpochSeconds,
            )
        // The assessment the owner read, and the identifiers the provider named, both kept for the
        // record that is about to be written (SAW-028, SEE-94).
        val key = RequestKey(open.connectionId, open.proposalId)
        history.reviewed(key, reviewedPolicy(fresh.decision, fresh.at, wentAhead = true))
        open.inspection
            ?.references
            ?.takeIf { it.isNotEmpty() }
            ?.let { references ->
                history.referenced(key, references.map { ReviewedValue(it.key, it.value) })
            }
        // One wallet interaction at a time, for the whole process (SEE-84). Everything that has to
        // be true is checked inside the lock, because the wait for it is exactly where the world
        // changes underneath an approval.
        wallet.withWallet { session ->
            when (
                val begun =
                    proposals.beginExecution(open.connectionId, open.proposalId, binding, selected)
            ) {
                is ExecutionOutcome.Refused -> stop(OperationProblem.Binding(begun.problem))
                is ExecutionOutcome.Gone -> stop(OperationProblem.Gone)
                is ExecutionOutcome.Begun -> {
                    val answer =
                        try {
                            session.signAndSend(prepared.transaction, selected)
                        } catch (e: CancellationException) {
                            // The app is going away with the operation still at the wallet. It is
                            // settled as unresolved on the next load, never as a failure, and the
                            // wallet is not asked again.
                            throw e
                        } catch (e: Exception) {
                            SendResult.Unknown(e.message)
                        }
                    proposals.recordOutcome(
                        open.connectionId,
                        open.proposalId,
                        outcomeOf(answer),
                    )
                }
            }
        }
    }

    /**
     * Reads the owner's rules against what the plugin established, right now.
     *
     * The history is re-read from disk first, exactly as a private request's review does it, so a
     * daily total is what is actually stored rather than what this process happened to see. A read
     * that fails leaves the day unknown, which shows up as unverified rather than as nothing spent.
     */
    private fun assess(): RequestAssessment? {
        val open = _review.value ?: return null
        val record = proposals.proposal(open.connectionId, open.proposalId) ?: return null
        val facts =
            pluginFacts(
                connectionId = open.connectionId,
                // A broadcast proposal carries no request, so the facts are built from what the
                // plugin read and from the operation's own identity.
                proposalId = open.proposalId,
                operation = record.proposal.operation,
                network = wallet.wallet.value?.network?.network ?: Network.NETWORK_UNSPECIFIED,
                resolution = plugins.resolve(record.proposal.operation, environment),
                inspection = open.inspection,
            )
        val evaluated = policies.evaluateCurrent(facts)
        val assessment =
            RequestAssessment(
                decision = evaluated.decision,
                facts = facts,
                at = now(),
                applicablePolicy = evaluated.applicablePolicy,
            )
        _review.update {
            if (it?.proposalId != open.proposalId) it
            else
                it.copy(
                    assessment = assessment,
                    // Consent was given for particular reasons. Different reasons, different
                    // thing to have consented to.
                    acknowledged = it.acknowledged && it.assessment?.consent == assessment.consent,
                )
        }
        return assessment
    }

    /** Re-reads the history from disk before an assessment, and never lets a failure look empty. */
    suspend fun reload() {
        withContext(io) {
            try {
                history.load()
            } catch (_: IOException) {
                // ActivityLog has already marked this read unknown, so a configured daily
                // threshold becomes unverified instead of seeing an empty day.
            } catch (_: SecurityException) {
                // Denied storage is treated exactly like any other incomplete history read.
            }
        }
        assess()
    }

    private fun stop(problem: OperationProblem) {
        _review.update { it?.copy(problem = problem) }
    }

    private fun plugin(record: ProposalRecord): ActionPlugin? {
        // The publisher's plugin name is checked against what this build resolves, and never used
        // to select anything: a document cannot choose code (SEE-89).
        proposalPlugin(record.proposal, plugins, environment).let {
            if (it !is io.github.brrenat.seekervault.proposals.ProposalPlugin.Serving) return null
        }
        return (plugins.resolve(record.proposal.operation, environment)
                as? PluginResolution.Supported)
            ?.plugin
    }

    private fun subjectFor(record: ProposalRecord) =
        ActionSubject(
            connectionId = record.connectionId,
            operation = record.proposal.operation,
            environment = environment,
            request = null,
            wallet = wallet.wallet.value,
            terms = record.proposal.values.associate { it.key to it.text },
        )

    private fun refreshed(open: OperationReview, state: OperationsUiState): OperationReview? {
        val record =
            state.records.firstOrNull {
                it.connectionId == open.connectionId && it.key.proposalId == open.proposalId
            } ?: return null
        val moved = record.proposal.revision != open.record.proposal.revision
        return open.copy(
            record = record,
            standing = proposals.standing(record),
            // Terms that moved are terms nobody reviewed. What was prepared was for the old ones.
            prepared = if (moved) null else open.prepared,
            inspection = if (moved) null else open.inspection,
            acknowledged = if (moved) false else open.acknowledged,
        )
    }

    private companion object {
        const val UNEXPECTED = "plugin_failed"

        fun hash(bytes: ByteString): ByteString =
            ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))

        /**
         * What the wallet did, in the words a proposal's record keeps.
         *
         * The mapping the transfer path uses, for its reasons: declining is the owner's own answer,
         * an answer that never arrived is [ProposalOutcome.Unresolved] and never a failure, because
         * a transaction the wallet may have sent is not one that did not happen.
         */
        fun outcomeOf(result: SendResult): ProposalOutcome =
            when (result) {
                is SendResult.Sent -> ProposalOutcome.Submitted(result.signature)
                is SendResult.Declined -> ProposalOutcome.Declined
                is SendResult.Unknown ->
                    ProposalOutcome.Unresolved(result.message ?: "The wallet gave no answer.")
                is SendResult.NoWallet -> ProposalOutcome.Failed("No wallet app answered.")
                is SendResult.NotConnected -> ProposalOutcome.Failed("No wallet is connected.")
                is SendResult.AuthorizationExpired ->
                    ProposalOutcome.Failed("The wallet's authorization no longer works.")
                is SendResult.Changed ->
                    ProposalOutcome.Failed("The wallet's account is not the one reviewed.")
                is SendResult.Failed ->
                    ProposalOutcome.Failed(result.message ?: "The wallet refused.")
            }

        /** Where each field starts before the owner touches it ([ParameterKind]). */
        fun initial(form: ParameterForm): ParameterChoice =
            ParameterChoice(
                form.fields
                    .mapNotNull { field ->
                        when (val kind = field.kind) {
                            // An amount is never suggested: how much of their own money to spend
                            // is the one thing nothing here has an opinion about.
                            is ParameterKind.Amount -> null
                            is ParameterKind.Count ->
                                field.key to ParameterValue.Count(kind.initial)
                            is ParameterKind.Choice -> null
                        }
                    }
                    .toMap()
            )
    }
}

/** What was prepared, or why nothing was. */
private class Prepared(
    val prepared: PluginPreparation? = null,
    val inspection: ActionInspection? = null,
    val failure: OperationFailure? = null,
)

/** Every proposal this phone holds, and the feeds behind them. */
data class OperationsUiState(
    val loaded: Boolean = false,
    val records: List<ProposalRecord> = emptyList(),
    val feeds: List<Connection> = emptyList(),
    val refreshing: Set<String> = emptySet(),
    val lastRefresh: FeedRefresh? = null,
)

/** One proposal, open for review. */
data class OperationReview(
    val connectionId: String,
    val proposalId: String,
    val record: ProposalRecord,
    val standing: ProposalStanding,
    /** What the plugin says has to be chosen, or why it cannot read this signal at all. */
    val form: ParameterForm,
    val choice: ParameterChoice,
    /** Whether this build has the plugin the publisher wrote this proposal for. */
    val served: Boolean,
    val preparing: Boolean = false,
    val prepared: PluginPreparation? = null,
    /** What this phone made of the prepared bytes, read independently of the provider. */
    val inspection: ActionInspection? = null,
    val failure: OperationFailure? = null,
    /** Where the owner may continue outside the app, built fresh and never read off disk. */
    val destinations: List<PluginDestination> = emptyList(),
    val assessment: RequestAssessment? = null,
    val acknowledged: Boolean = false,
    val sending: Boolean = false,
    val problem: OperationProblem? = null,
)

/** Why nothing could be prepared: a plugin's own code and its own words. */
data class OperationFailure(
    val code: String,
    @StringRes val explanation: Int?,
    /** A provider's own words, when it gave any. For display, and parsed by nobody. */
    val detail: String? = null,
)

/** Why an approval did not reach the wallet, or did not get past the gate once it had. */
sealed interface OperationProblem {
    /** The rules changed while the owner was reading them, so the screen says so and stops. */
    data object RulesChanged : OperationProblem

    /** They have not said they mean to go ahead past the warnings. */
    data object NotAcknowledged : OperationProblem

    data object NoWallet : OperationProblem

    /** The selected wallet is not the one the screen showed them. */
    data object WalletChanged : OperationProblem

    /** The proposal, or its feed, is no longer on this phone. */
    data object Gone : OperationProblem

    /** One of the rules that stand between a review and the wallet (SEE-89). */
    data class Binding(val problem: BindingProblem) : OperationProblem
}
