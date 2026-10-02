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
import io.github.brrenat.seekervault.plugins.ActionOperation
import io.github.brrenat.seekervault.plugins.ExecutionProvider
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFact
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.ProviderAbout
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.ProviderResolution
import io.github.brrenat.seekervault.plugins.actionFacts
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.ActionPayloadResult
import io.github.brrenat.seekervault.plugins.actions.actionPayloadFrom
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.proposals.BindingProblem
import io.github.brrenat.seekervault.proposals.ExecutionBinding
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.namedProvider
import io.github.brrenat.seekervault.proposals.proposalProvider
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.WalletRepository
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val providers: ProviderRegistry,
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

    /**
     * What makes one review distinct from every other, including the same proposal opened twice.
     *
     * A proposal ID belongs to a source, not to the phone, so two feeds can hold the same one; and
     * the same proposal reopened, or moved to a new revision, is a different thing to be reading.
     * Every one of those mints a fresh number here, and anything that suspends carries the number
     * it started under — so an answer can only ever land on the review that asked for it (SEE-145).
     */
    private val reviews = AtomicLong()

    /** The provider read in flight, kept only so a superseded one can be stopped. */
    private var refining: Job? = null

    init {
        viewModelScope.launch {
            // The connections are read first, and waited for. A proposal is only ever held under
            // the feed it arrived on, so a read that could not see the feeds would conclude every
            // one of them was gone and drop what they hold (`ProposalRepository.load`).
            connectionsLoaded.first { it }
            proposals.load()
        }
        viewModelScope.launch {
            // `loaded` is the repository's own flag and not "this combine has emitted": the first
            // emission happens before `load()` has read the store, and an empty list there means
            // "not read yet". Anything that treats a new record as an arrival — the foreground
            // banners (SEE-147) — would otherwise announce the whole stored feed on a cold start.
            // The feeds' wallet bindings are part of it (SEE-174): a feed rebound under an open
            // review drops what was prepared for the wallet it had.
            combine(
                    proposals.proposals,
                    proposals.loaded,
                    connections,
                    wallet.readinessByConnection(),
                ) { held, loaded, live, _ ->
                    val feeds = live.filter { it.mode == ConnectionMode.GatewayFeed }
                    // A proposal is only ever held under the feed it arrived on, and a feed the
                    // owner removed takes its proposals with it — `ConnectionRepository.remove`
                    // deletes them, and `ProposalRepository.publish` reads only what a live feed
                    // owns. But the list in memory was read before the removal and is re-derived
                    // on the next publish, so the same rule is applied here, where the held
                    // proposals meet the connections as they are now: what a removed feed
                    // delivered stops counting as waiting for the owner and stops being listed
                    // under a source that is gone, rather than lingering until a restart
                    // (SEE-154, docs/wiki/shared-proposals.md#retention).
                    val present = feeds.mapTo(mutableSetOf(), Connection::id)
                    OperationsUiState(
                        loaded = loaded,
                        records = held.filter { it.connectionId in present },
                        feeds = feeds,
                    )
                }
                .collect { fresh ->
                    _state.value = fresh
                    // A proposal that moved under an open review — a higher revision, a
                    // cancellation, the feed removed — is reflected rather than remembered.
                    val before = _review.value?.generation
                    _review.update { open -> open?.let { refreshed(it, fresh) } }
                    val moved = _review.value?.takeIf { it.generation != before }
                    // Terms that moved are terms nothing has been asked about yet: the rules are
                    // read against the new ones, and the provider is asked about them afresh,
                    // exactly as opening the review would have done (SEE-145).
                    if (moved != null) {
                        assess()
                        refine(moved.connectionId, moved.proposalId, moved.generation)
                    }
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

    /**
     * The form a record's provider declares, from compiled code only, so a History record can put
     * the owner's recorded choice into the provider's own words (SEE-161). Nothing is resolved,
     * quoted or prepared.
     */
    fun parameterForm(record: ProposalRecord): ParameterForm {
        // The provider, promise and chain the choice was bound under — not today's settings.
        val binding = record.execution?.binding ?: return ParameterForm()
        val payload =
            (payloadOf(record) as? ActionPayloadResult.Valid)?.payload ?: return ParameterForm()
        val provider = providers.byId(binding.provider) ?: return ParameterForm()
        if (namedProvider(record.proposal, providers) == null) return ParameterForm()
        return provider.inputs(
            operationFor(record, payload)
                .copy(
                    provider = binding.provider,
                    environment = binding.environment,
                    network = binding.network,
                )
        )
    }

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
     * Opens one proposal for review: reads the publisher's terms as the action they claim to be,
     * asks the execution provider what has to be chosen, and starts the fields at whatever the
     * owner chose last time, or at what the provider suggests.
     *
     * The terms are read by core rather than by the provider (SEE-145), so a document that cannot
     * be read as its action says so before any provider has been reached — and says the same thing
     * whichever provider would have served it.
     */
    fun open(connectionId: String, proposalId: String) {
        val record = proposals.proposal(connectionId, proposalId) ?: return
        val terms = termsOf(record, referencesOf(connectionId, proposalId))
        val generation = reviews.incrementAndGet()
        _review.value =
            OperationReview(
                connectionId = connectionId,
                proposalId = proposalId,
                generation = generation,
                record = record,
                standing = proposals.standing(record),
                environment = environmentOf(connectionId),
                payload = terms.payload,
                form = terms.form,
                // Where the owner may carry on outside the app, if the action's provider has
                // anywhere truthful to send them (SEE-94). It comes from the terms alone, so it
                // survives a restart and needs no preparation — and no URL is ever stored.
                destinations = terms.destinations,
                about = terms.about,
                // A review already written for these terms is what the owner last chose about
                // them; anything else starts from the provider's own suggestion.
                choice =
                    record.review?.takeIf { it.revision == record.proposal.revision }?.choice
                        ?: initial(terms.form),
                served = terms.served,
                wallet = wallet.walletFor(connectionId),
            )
        // The rules are read when the review opens, so the owner is not shown a gap where the
        // assessment will be.
        assess()
        // And then the provider is asked what it currently says about this action, which is a read
        // and nothing else: no order, no quote, no wallet, nothing bound. A provider with nothing
        // live to add answers immediately and nothing on screen moves.
        refine(connectionId, proposalId, generation)
    }

    /** Everything in a review that is read off the proposal's terms, and nothing that is not. */
    private class Terms(
        val payload: ActionPayload?,
        val form: ParameterForm,
        val destinations: List<PluginDestination>,
        val served: Boolean,
        /** What the provider says about itself for this action (SEE-173). */
        val about: ProviderAbout? = null,
    )

    /**
     * Reads [record]'s terms as the action they claim to be, and asks the provider this build would
     * serve them with what has to be chosen.
     *
     * Opening a review and a revision arriving under an open one both come through here, which is
     * the point: the parsed payload, the input form, the provider resolution and the destinations
     * are derived together from one record, so a review can never hold one revision's terms beside
     * another revision's record (SEE-145).
     *
     * [references] are what this operation's provider named for it if it has already been submitted
     * ([referencesOf]). They only ever add a destination — a provider with somewhere to send an
     * owner about *their order* rather than about the market — and a review of something nobody has
     * ordered passes none (SEE-157).
     */
    private fun termsOf(record: ProposalRecord, references: List<PluginReference>): Terms {
        val read = payloadOf(record)
        val payload = (read as? ActionPayloadResult.Valid)?.payload
        val resolved = payload?.let { provider(record, it) }
        // Only for a provider that actually resolved: an operation names one, and a document whose
        // provider this build does not carry has none to name.
        val operation =
            if (resolved != null && payload != null) operationFor(record, payload) else null
        return Terms(
            payload = payload,
            form =
                when {
                    resolved != null && operation != null -> resolved.inputs(operation)
                    read is ActionPayloadResult.Invalid -> ParameterForm(problem = read.finding)
                    else -> ParameterForm()
                },
            destinations = destinationsOf(record, payload, references),
            served = resolved != null,
            about = if (resolved != null && operation != null) resolved.about(operation) else null,
        )
    }

    /**
     * What this operation's provider named for it, if it has already been submitted (SEE-157).
     *
     * The owner's own record is the source, because it is the one that outlives the process: an
     * order placed last week is still an order, and the review that placed it is long gone. The
     * record keeps public identifiers and never a URL, so a destination is still built here, now,
     * from compiled code — reading this back is reading *which* order, not where to send anybody.
     */
    private fun referencesOf(connectionId: String, proposalId: String): List<PluginReference> =
        history.records.value
            .firstOrNull { it.connectionId == connectionId && it.requestId == proposalId }
            ?.operation
            ?.references
            ?.map { PluginReference(it.key, it.text) }
            .orEmpty()

    /**
     * Where this record's provider would send the owner now, given what it has named for it.
     *
     * Re-derived rather than remembered, because an order placed while the review is open changes
     * the answer: before it there is a market to look at, and after it there is also the order
     * itself (SEE-157). Pure, and it reaches nothing.
     */
    private fun destinationsOf(
        record: ProposalRecord,
        payload: ActionPayload?,
        references: List<PluginReference>,
    ): List<PluginDestination> {
        val resolved = payload?.let { provider(record, it) } ?: return emptyList()
        return resolved.destinations(operationFor(record, payload), references)
    }

    /**
     * Asks the provider what it says about this action now ([ExecutionProvider.resolve]).
     *
     * Its answer refines the constraints and adds what the venue currently reports. A failure is
     * shown and changes nothing else: the declared constraints stay, because inventing tighter ones
     * from a read that did not happen would be worse than leaving them as the publisher stated
     * them.
     */
    private fun refine(connectionId: String, proposalId: String, generation: Long) {
        // A read for a review nobody is on any more answers nobody, and it must not be allowed to
        // answer the review that replaced it. It is superseded here, and its result is checked
        // against [generation] again on the far side of the wait.
        refining?.cancel()
        refining = viewModelScope.launch {
            val open = _review.value ?: return@launch
            if (!open.isStill(connectionId, proposalId, generation)) return@launch
            val payload = open.payload ?: return@launch
            val record = proposals.proposal(connectionId, proposalId) ?: return@launch
            val resolved = provider(record, payload) ?: return@launch
            val resolution =
                try {
                    resolved.resolve(operationFor(record, payload))
                } catch (e: PluginFailure) {
                    _review.update {
                        if (it?.isStill(connectionId, proposalId, generation) != true) it
                        else it.copy(failure = OperationFailure(e.code, e.explanation, e.detail))
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _review.update {
                        if (it?.isStill(connectionId, proposalId, generation) != true) it
                        else it.copy(failure = OperationFailure(UNEXPECTED, null, e.message))
                    }
                    return@launch
                }
            _review.update {
                // Only for the review that asked, and only while nothing has been prepared: a
                // form that moved under prepared bytes would be a screen describing something
                // other than what is on it.
                if (it?.isStill(connectionId, proposalId, generation) != true) it
                else if (it.prepared != null) it
                else
                    it.copy(
                        // A provider saying the action cannot be served as it stands — a
                        // market that has closed — lands where a document this phone could not
                        // read lands: shown, with nothing to prepare from. They are the same
                        // thing to the owner, so they are the same field.
                        form =
                            resolution.form.copy(
                                problem = resolution.problem ?: resolution.form.problem
                            ),
                        details = resolution.details,
                        choice =
                            it.choice.takeIf { chosen -> chosen.values.isNotEmpty() }
                                ?: initial(resolution.form),
                    )
            }
        }
    }

    /** Whether this is still the very review something suspended under (SEE-145). */
    private fun OperationReview.isStill(
        connectionId: String,
        proposalId: String,
        generation: Long,
    ): Boolean =
        this.connectionId == connectionId &&
            this.proposalId == proposalId &&
            this.generation == generation

    fun close() {
        refining?.cancel()
        refining = null
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
                // The terms on screen belong to one revision, and they are the ones being prepared
                // from. A record that moved while the tap was waiting for the lock is prepared
                // from by nobody: the refresh is about to replace the whole review with the new
                // terms, and combining the latest revision with the previous one's limits is
                // exactly the thing the binding exists to refuse (SEE-145).
                val record =
                    proposals.proposal(open.connectionId, open.proposalId)?.takeIf {
                        it.proposal.revision == open.record.proposal.revision
                    }
                        ?: run {
                            _review.update {
                                if (it?.generation != open.generation) it?.copy(preparing = false)
                                else
                                    it.copy(
                                        preparing = false,
                                        problem =
                                            OperationProblem.Binding(
                                                BindingProblem.ProposalChanged
                                            ),
                                    )
                            }
                            return@withLock
                        }
                val payload = open.payload
                val resolved = if (payload == null) null else provider(record, payload)
                if (payload == null || resolved == null) {
                    // Nothing here serves this, and the owner is told which of the six reasons it
                    // is rather than being left with a button that did nothing (SEE-145). It is a
                    // refusal before anything is prepared, and long before anything is signed.
                    val reason =
                        (providers.resolve(
                                provider = namedProvider(record.proposal, providers),
                                action = record.proposal.action,
                                schemaVersion = record.proposal.capabilityVersion,
                                network = networkOf(open.connectionId),
                                environment = environmentOf(open.connectionId),
                                payload = payload,
                            ) as? ProviderResolution.Unsupported)
                            ?.reason
                    _review.update {
                        if (it?.generation != open.generation) it?.copy(preparing = false)
                        else
                            it.copy(
                                preparing = false,
                                failure =
                                    reason?.let { why ->
                                        OperationFailure(why.code, unservedText(why))
                                    },
                            )
                    }
                    return@withLock
                }
                val operation = operationFor(record, payload)
                // What the owner chose is written down before anything is prepared, because it is
                // what the binding is checked against and what the record says they reviewed.
                proposals.review(open.connectionId, open.proposalId, open.choice)
                val outcome =
                    try {
                        val prepared = resolved.prepare(operation, open.choice)
                        val inspection = resolved.inspect(operation, open.choice, prepared)
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
                    // Bytes belong to the review they were prepared for. One that moved while the
                    // provider was working gets nothing from it, not even a failure (SEE-145).
                    if (it?.generation != open.generation) it?.copy(preparing = false)
                    else
                        it.copy(
                            preparing = false,
                            prepared = outcome.prepared,
                            inspection = outcome.inspection,
                            failure = outcome.failure,
                            acknowledged = false,
                            assessment = null,
                            // The wallet these bytes were built for: the feed's own profile at
                            // the moment of preparing, which a rebinding makes stale (SEE-174).
                            preparedFor = operation.wallet,
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
        prepared: PreparedOperation,
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
        // The feed's own wallet profile, read now, and never another connection's (SEE-174).
        val selected = wallet.walletFor(open.connectionId)
        if (
            selected == null ||
                reviewed == null ||
                selected != reviewed ||
                open.preparedFor != selected
        ) {
            stop(
                if (selected == null) OperationProblem.NoWallet else OperationProblem.WalletChanged
            )
            return
        }
        val record = proposals.proposal(open.connectionId, open.proposalId) ?: return
        // The bytes were prepared from one revision's terms, so they are bound against that
        // revision or against nothing. The gate below would refuse the mismatch too, but only
        // after the record said the owner reviewed something they did not (SEE-145).
        if (record.proposal.revision != open.record.proposal.revision) {
            stop(OperationProblem.Binding(BindingProblem.ProposalChanged))
            return
        }
        val payload = open.payload ?: return
        val resolved = provider(record, payload) ?: return
        val binding =
            ExecutionBinding(
                revision = record.proposal.revision,
                // Read here, from the connection, at the moment of acting — not taken from the
                // screen, which the owner may have been looking at since before they switched it.
                // The gate checks it again against the connection on the far side of the wait
                // (`bindingProblem`), so a switch that lands in between refuses rather than slips
                // through (SEE-97).
                environment = environmentOf(open.connectionId),
                choice = open.choice,
                wallet = selected.address,
                network = selected.network.network,
                // Who prepared it, what they prepared, at which schema, and about exactly which
                // market or pair — all four pinned, because any of them changing means the owner
                // would be signing something other than what they reviewed (SEE-145).
                provider = resolved.capabilities.id,
                action = record.proposal.action,
                schemaVersion = record.proposal.capabilityVersion,
                instrument = payload.instrument,
                contract = resolved.capabilities.contract,
                preparedVersion = prepared.version,
                contentHash = hash(prepared.transaction),
                expiresAtEpochSeconds = prepared.expiresAtEpochSeconds,
                // Who routed it and what service fee the bytes carry, as the owner reviewed them:
                // pinned with the binding so History keeps what was approved (SEE-173).
                receipt = open.inspection?.receipt.orEmpty(),
                // What the inspected bytes take out of the owner's balance, pinned before the
                // wallet so the day's counters keep the exposure whatever happens next (SEE-181).
                spending = fresh.facts.spending(),
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
        // A sandbox connection rehearses, and this is where it stops (SEE-97,
        // docs/wiki/environments.md).
        //
        // Everything above happened for real: the plugin read the provider and built the bytes,
        // this phone read them back, the owner's rules were applied, and the binding was bound.
        // What does not happen is the wallet — and it does not happen because there is no session
        // in scope here to ask, rather than because a flag was checked next to one. The gate is
        // the same gate: `beginExecution` applies every rule a production approval passes,
        // including this binding's own promise, so a rehearsal is refused by exactly the things
        // that would refuse the real operation.
        if (binding.environment != PluginEnvironment.Production) {
            when (
                val begun =
                    proposals.beginExecution(open.connectionId, open.proposalId, binding, selected)
            ) {
                is ExecutionOutcome.Refused -> stop(OperationProblem.Binding(begun.problem))
                is ExecutionOutcome.Gone -> stop(OperationProblem.Gone)
                is ExecutionOutcome.Begun ->
                    proposals.recordOutcome(
                        open.connectionId,
                        open.proposalId,
                        ProposalOutcome.Simulated,
                    )
            }
            return
        }
        // One wallet interaction at a time, for the whole process (SEE-84). Everything that has to
        // be true is checked inside the lock, because the wait for it is exactly where the world
        // changes underneath an approval.
        wallet.withWallet { session ->
            // Read again on this side of the wait: a feed rebound, or a profile removed or
            // reconnected, while another wallet interaction held the lock is not the wallet the
            // owner reviewed (SEE-174). The wallet call checks it once more.
            if (wallet.walletFor(open.connectionId) != selected) {
                stop(OperationProblem.WalletChanged)
                return@withWallet
            }
            // The rules and the day's counters are read again here too, from disk, after the wait
            // and before anything is committed (SEE-181). Another approval that held the lock may
            // have spent since the owner read this one, and a verdict — or a total — they did not
            // read is not one they agreed to: the review shows the new one and asks again.
            val current = reloaded() ?: return@withWallet
            if (current.consent != fresh.consent) {
                stop(OperationProblem.RulesChanged)
                return@withWallet
            }
            when (
                val begun =
                    proposals.beginExecution(
                        open.connectionId,
                        open.proposalId,
                        binding,
                        selected,
                        transaction = prepared.transaction.toByteArray(),
                    )
            ) {
                is ExecutionOutcome.Refused -> stop(OperationProblem.Binding(begun.problem))
                is ExecutionOutcome.Gone -> stop(OperationProblem.Gone)
                is ExecutionOutcome.Begun -> {
                    val answer =
                        try {
                            session.signAndSend(prepared.transaction, selected, open.connectionId)
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
     * The history is re-read from disk first, exactly as a direct request's review does it, so a
     * daily total is what is actually stored rather than what this process happened to see. A read
     * that fails leaves the day unknown, which shows up as unverified rather than as nothing spent.
     */
    private fun assess(): RequestAssessment? {
        val open = _review.value ?: return null
        val record = proposals.proposal(open.connectionId, open.proposalId) ?: return null
        val assessment = assessmentOf(record, open.payload, open.inspection)
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

    /**
     * What the owner's rules make of a held proposal nobody has opened yet, for its tile (SEE-158).
     *
     * The same evaluation the review runs when it opens, from the same facts — the action's
     * identity and what the provider would be asked — so a tile and the sheet it opens can never
     * disagree about the verdict. It is a read: nothing is stored, and no review is touched.
     */
    fun assessment(connectionId: String, proposalId: String): RequestAssessment? {
        _review.value
            ?.takeIf { it.connectionId == connectionId && it.proposalId == proposalId }
            ?.assessment
            ?.let {
                return it
            }
        val record = proposals.proposal(connectionId, proposalId) ?: return null
        val payload = (payloadOf(record) as? ActionPayloadResult.Valid)?.payload
        return assessmentOf(record, payload, inspection = null)
    }

    private fun assessmentOf(
        record: ProposalRecord,
        payload: ActionPayload?,
        inspection: ActionInspection?,
    ): RequestAssessment {
        val network = networkOf(record.connectionId)
        val facts =
            actionFacts(
                connectionId = record.connectionId,
                // A broadcast proposal carries no request, so the facts are built from what the
                // provider read and from the action's own identity.
                proposalId = record.proposal.key.proposalId,
                action = record.proposal.action,
                network = network,
                resolution =
                    providers.resolve(
                        provider = namedProvider(record.proposal, providers),
                        action = record.proposal.action,
                        schemaVersion = record.proposal.capabilityVersion,
                        network = network,
                        environment = environmentOf(record.connectionId),
                        payload = payload,
                    ),
                inspection = inspection,
            )
        val evaluated = policies.evaluateCurrent(facts)
        return RequestAssessment(
            decision = evaluated.decision,
            facts = facts,
            at = now(),
            applicablePolicy = evaluated.applicablePolicy,
        )
    }

    /** Re-reads the history from disk before an assessment, and never lets a failure look empty. */
    suspend fun reload() {
        reloaded()
    }

    /** [reload], answering the assessment it made; null when the review has gone. */
    private suspend fun reloaded(): RequestAssessment? {
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
        return assess()
    }

    private fun stop(problem: OperationProblem) {
        _review.update { it?.copy(problem = problem) }
    }

    /**
     * The execution provider this build would use for [record], or null when none would.
     *
     * The publisher names one and it is asked for by name, never used as a hint: a document cannot
     * choose code (SEE-89), and a build that does not carry the named provider serves nothing here
     * rather than handing the terms to whatever else it has for the action (SEE-145).
     */
    private fun provider(record: ProposalRecord, payload: ActionPayload): ExecutionProvider? {
        val environment = environmentOf(record.connectionId)
        val network = networkOf(record.connectionId)
        if (
            proposalProvider(record.proposal, providers, network, environment)
                !is io.github.brrenat.seekervault.proposals.ProposalProvider.Serving
        ) {
            return null
        }
        return (providers.resolve(
                provider = namedProvider(record.proposal, providers),
                action = record.proposal.action,
                schemaVersion = record.proposal.capabilityVersion,
                network = network,
                environment = environment,
                payload = payload,
            ) as? ProviderResolution.Supported)
            ?.provider
    }

    /** The publisher's terms, read as the action they claim to be ([actionPayloadFrom]). */
    private fun payloadOf(record: ProposalRecord): ActionPayloadResult =
        actionPayloadFrom(
            record.proposal.action,
            record.proposal.capabilityVersion,
            record.proposal.terms(),
        )

    /**
     * Which promise the feed this proposal arrived on keeps (SEE-97, docs/wiki/environments.md).
     *
     * It is the connection's own, read fresh: the owner sets it, one phone holds feeds in both
     * environments at once, and no manifest a publisher republishes can change it. A connection
     * that is gone answers sandbox, which is the direction a missing answer has to fall — and a
     * proposal whose feed is gone is not executable anyway.
     */
    private fun environmentOf(connectionId: String): PluginEnvironment =
        connections.value.firstOrNull { it.id == connectionId }?.environment
            ?: PluginEnvironment.Sandbox

    private fun operationFor(record: ProposalRecord, payload: ActionPayload) =
        ActionOperation(
            connectionId = record.connectionId,
            action = record.proposal.action,
            schemaVersion = record.proposal.capabilityVersion,
            // Null cannot reach here: a provider is only resolved when the document named one this
            // build carries, and this is only built for a resolved provider.
            provider = checkNotNull(namedProvider(record.proposal, providers)),
            environment = environmentOf(record.connectionId),
            network = networkOf(record.connectionId),
            payload = payload,
            request = null,
            wallet = wallet.walletFor(record.connectionId),
        )

    /**
     * The network [connectionId]'s own wallet profile is on (SEE-174), which is what a provider is
     * asked about and what the rules read. Unspecified when the feed has no ready wallet, which no
     * provider serves — so nothing is prepared on a network nobody chose.
     */
    private fun networkOf(connectionId: String): Network =
        wallet.walletFor(connectionId)?.network?.network ?: Network.NETWORK_UNSPECIFIED

    private fun refreshed(open: OperationReview, state: OperationsUiState): OperationReview? {
        val record =
            state.records.firstOrNull {
                it.connectionId == open.connectionId && it.key.proposalId == open.proposalId
            } ?: return null
        val environment = environmentOf(open.connectionId)
        // Two ways what is on screen stops being what the owner is looking at: the publisher moved
        // the terms, or the owner moved the promise. Either one makes the preparation the wrong
        // one to approve — new terms were never reviewed, and bytes prepared for a rehearsal are
        // not bytes anybody reviewed as a purchase (SEE-97) — so both drop it and both are named
        // here rather than left to the gate that would refuse it later.
        val bound = wallet.walletFor(open.connectionId)
        // A preparation made for another wallet — the feed was rebound, or its profile removed or
        // reconnected — is for an owner nobody is reviewing any more (SEE-174). It goes, with the
        // acknowledgement beside it, and a new number stops a read still in flight for the old
        // wallet from landing on the new review; the terms themselves are kept.
        if (open.preparing || open.prepared != null) {
            val preparedFor = if (open.preparing) open.wallet else open.preparedFor
            if (preparedFor != bound) {
                return open.copy(
                    generation = reviews.incrementAndGet(),
                    record = record,
                    standing = proposals.standing(record),
                    environment = environment,
                    wallet = bound,
                    preparing = false,
                    prepared = null,
                    inspection = null,
                    preparedFor = null,
                    acknowledged = false,
                    assessment = null,
                    problem = OperationProblem.WalletChanged,
                )
            }
        }
        val moved =
            record.proposal.revision != open.record.proposal.revision ||
                environment != open.environment
        if (!moved) {
            return open.copy(
                wallet = bound,
                record = record,
                standing = proposals.standing(record),
                environment = environment,
                // An execution recorded under an open review is exactly when a provider gains
                // somewhere to send the owner about the order itself, so this is asked again
                // rather than kept from before it existed (SEE-157).
                destinations =
                    destinationsOf(
                        record,
                        open.payload,
                        referencesOf(open.connectionId, open.proposalId).ifEmpty {
                            open.inspection?.references.orEmpty()
                        },
                    ),
            )
        }
        // A revision that moved is a different set of terms, and everything read off the old ones
        // goes with it: the parsed payload, the input form, which provider serves it and where it
        // may be continued. Keeping the old payload beside the new record is how the latest
        // revision comes to be prepared against the previous revision's limits (SEE-145).
        val terms = termsOf(record, referencesOf(open.connectionId, open.proposalId))
        return open.copy(
            // A new number, so a provider read still in flight for the old terms cannot land on
            // these, and so the caller knows to ask about them afresh.
            generation = reviews.incrementAndGet(),
            record = record,
            standing = proposals.standing(record),
            environment = environment,
            payload = terms.payload,
            form = terms.form,
            destinations = terms.destinations,
            about = terms.about,
            served = terms.served,
            // What the owner last chose about *these* terms, or the provider's suggestion — never
            // the previous revision's answer carried forward onto terms they never saw.
            choice =
                record.review?.takeIf { it.revision == record.proposal.revision }?.choice
                    ?: initial(terms.form),
            // Terms that moved are terms nobody reviewed. What was prepared was for the old ones,
            // and so was everything shown beside it.
            wallet = bound,
            preparedFor = null,
            prepared = null,
            inspection = null,
            acknowledged = false,
            details = emptyList(),
            failure = null,
            problem = null,
            assessment = null,
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
    val prepared: PreparedOperation? = null,
    val inspection: ActionInspection? = null,
    val failure: OperationFailure? = null,
)

/** Every proposal this phone holds, and the feeds behind them. */
data class OperationsUiState(
    /** False until the stored proposals have been read; before it, [records] is not an answer. */
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
    /**
     * Which review this is, counted by the ViewModel that minted it.
     *
     * Proposal IDs belong to the publisher, so two feeds may hold the same one, and the same
     * proposal reopened or moved to a new revision is a different thing to be looking at. Anything
     * that suspends carries the number it started under and compares it before it writes, so a slow
     * answer lands on the review that asked for it or on nothing at all (SEE-145).
     */
    val generation: Long,
    val record: ProposalRecord,
    val standing: ProposalStanding,
    /**
     * Which promise this feed keeps, which is what the Approve button is about to do (SEE-97).
     *
     * The screen shows it, because a rehearsal and a purchase must not look the same, and the
     * review is kept in step with it: if the owner switches the connection while this is open, what
     * was prepared for the other promise is dropped and they prepare again.
     */
    val environment: PluginEnvironment,
    /**
     * The publisher's terms, read as the action they claim to be; null when they cannot be
     * ([form]'s own problem then says which rule broke).
     */
    val payload: ActionPayload? = null,
    /** What the provider says has to be chosen, or why it cannot read this signal at all. */
    val form: ParameterForm,
    val choice: ParameterChoice,
    /** Whether this build has the plugin the publisher wrote this proposal for. */
    val served: Boolean,
    val preparing: Boolean = false,
    val prepared: PreparedOperation? = null,
    /** What this phone made of the prepared bytes, read independently of the provider. */
    val inspection: ActionInspection? = null,
    val failure: OperationFailure? = null,
    /** What the provider currently says about the action, for the owner to read (SEE-145). */
    val details: List<PluginFact> = emptyList(),
    /** Where the owner may continue outside the app, built fresh and never read off disk. */
    val destinations: List<PluginDestination> = emptyList(),
    /**
     * Who carries this out, as its provider describes itself: the integration's name, the
     * disclosures to read before committing and official links (SEE-173). Built fresh, never read
     * off disk.
     */
    val about: ProviderAbout? = null,
    val assessment: RequestAssessment? = null,
    val acknowledged: Boolean = false,
    val sending: Boolean = false,
    val problem: OperationProblem? = null,
    /**
     * The feed's own wallet profile as it stands now, or null when it has none ready (SEE-174). It
     * is what the review shows and what an approval is checked against; there is no global wallet.
     */
    val wallet: SelectedWallet? = null,
    /** The wallet [prepared] was built for. A preparation for another is dropped. */
    val preparedFor: SelectedWallet? = null,
)

/** Production executions continue in the external wallet; sandbox executions remain local. */
val OperationReview.requiresWalletHandoff: Boolean
    get() = environment == PluginEnvironment.Production

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
