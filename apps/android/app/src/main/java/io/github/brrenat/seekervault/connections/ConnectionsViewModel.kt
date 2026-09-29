package io.github.brrenat.seekervault.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.access.AccessResult
import io.github.brrenat.seekervault.access.FeedAccessManager
import io.github.brrenat.seekervault.access.storage.FeedAccessStore
import io.github.brrenat.seekervault.feeds.FeedStatusState
import io.github.brrenat.seekervault.feeds.ForegroundFeedsState
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedAccess
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.FeedReferenceProblem
import io.github.brrenat.seekervault.servers.FeedReferenceResult
import io.github.brrenat.seekervault.servers.FeedReferences
import io.github.brrenat.seekervault.servers.ManifestProblem
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.feedAccess
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.servers.serverSupport
import io.github.brrenat.seekervault.sync.ForegroundUpdatesState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Everything the connection screens show. */
data class ConnectionsUiState(
    val connections: List<Connection> = emptyList(),
    /** False until the stored connections have been read. */
    val loaded: Boolean = false,
    /**
     * False until the fetch that follows that first read has settled for every usable connection.
     *
     * [loaded] says the connections are known; this says what they are holding is known too. The
     * stored connections carry no pending requests with them — the inbox is filled by the fetch —
     * so anything that reads an appearing request as an arrival must wait for this and not for
     * [loaded], or a cold start replays the whole inbox as new (SEE-147).
     */
    val fetched: Boolean = false,
    /** Connections with a refresh in flight. */
    val refreshing: Set<String> = emptySet(),
    /** The code being typed. It stays in memory only, never in saved instance state. */
    val codeDraft: String = "",
    val adding: AddConnectionState = AddConnectionState.Idle,
    val disconnect: DisconnectState? = null,
    val message: ConnectionMessage? = null,
    /** Current transport liveness; last successful sync remains on each [Connection]. */
    val updates: ForegroundUpdatesState = ForegroundUpdatesState(),
    /** Current shared-feed transport liveness, keyed by gateway origin. */
    val feeds: ForegroundFeedsState = ForegroundFeedsState(),
    /**
     * Whether the publisher behind each feed is running, by connection ID (SEE-150).
     *
     * Deliberately a second state beside [feeds] rather than a field folded into it: that one is
     * whether this phone reaches the gateway, this one is whether the server behind a channel is
     * up, and a gateway that answers is no evidence at all for the second. A feed nothing has been
     * heard about is [io.github.brrenat.seekervault.feeds.FeedAvailability.Unknown], never online.
     */
    val feedStatus: FeedStatusState = FeedStatusState(),
    /**
     * Whether this build supports each connection's server, by connection ID (SEE-88). It is worked
     * out here from the manifest the connection caches and the plugins compiled into this build,
     * and never stored: a verdict on disk would outlive the build that reached it.
     */
    val support: Map<String, ServerSupport> = emptyMap(),
    /**
     * Where this phone's access to each restricted feed stands, by connection ID (SEE-156). A
     * public feed is absent from it, and so is a restricted one nothing has been asked of yet.
     */
    val access: Map<String, FeedAccessStore.Record> = emptyMap(),
    /** Connections with an access request, check or redemption in flight. */
    val accessWorking: Set<String> = emptySet(),
)

/** A valid code, with what the phone already knows about its server. */
data class Confirmation(
    val code: PairingCode,
    /** Live connections to the same server, which the sidecar revokes when this code pairs. */
    val sameServer: List<Connection>,
    /** Connections at the same URL but to another server. */
    val sameAddress: List<Connection>,
)

sealed interface AddConnectionState {
    data object Idle : AddConnectionState

    /** The entered or scanned text can't be used to pair. */
    data class PairingInvalid(val problem: PairingCodeProblem) : AddConnectionState

    /** The entered or scanned text is a feed reference, but this app can't use it. */
    data class FeedInvalid(val problem: FeedReferenceProblem) : AddConnectionState

    /** A legacy gateway invitation is recognized only to explain why it cannot run. */
    data object RetiredInvitation : AddConnectionState

    /** Waiting for the owner to confirm the server. */
    data class ConfirmPairing(val confirmation: Confirmation) : AddConnectionState

    data class Pairing(val confirmation: Confirmation) : AddConnectionState

    data class PairingFailed(val confirmation: Confirmation, val failure: PairingFailure) :
        AddConnectionState

    data class Paired(val connection: Connection) : AddConnectionState

    /** Waiting for the owner to confirm a public feed. No network call or store write happened. */
    data class ConfirmFeed(val reference: FeedReference) : AddConnectionState

    data class AddingFeed(val reference: FeedReference) : AddConnectionState

    data class FeedFailed(val reference: FeedReference, val failure: FeedAddFailure) :
        AddConnectionState

    data class FeedAdded(val connection: Connection) : AddConnectionState

    /** This exact publisher was already stored, so adding it wrote nothing. */
    data class FeedAlready(val connection: Connection) : AddConnectionState
}

/** Why a valid, confirmed feed reference wasn't added. */
sealed interface FeedAddFailure {
    data class Refused(val problem: ManifestProblem) : FeedAddFailure

    data class Check(val outcome: CheckOutcome) : FeedAddFailure

    data object NoGateway : FeedAddFailure

    /** The manifest passed, but this phone could not persist the connection. */
    data object Storage : FeedAddFailure
}

enum class PairingFailure {
    /** The pairing token was unknown, expired, used, or replaced by a newer one. */
    CodeRefused,
    /** The code was issued for another URL. */
    WrongAddress,
    CertificateRejected,
    CleartextBlocked,
    Unreachable,
    BadResponse,
    Storage,
    Other,
}

sealed interface DisconnectState {
    val id: String

    data class Confirm(override val id: String) : DisconnectState

    data class Working(override val id: String) : DisconnectState

    /** The sidecar couldn't be told. The owner can still remove the connection from the phone. */
    data class NotReached(override val id: String, val outcome: CheckOutcome) : DisconnectState

    /** The sidecar no longer accepts the credential, so removing is local. */
    data class ConfirmRemove(override val id: String) : DisconnectState
}

sealed interface ConnectionMessage {
    val label: String

    data class Paired(override val label: String) : ConnectionMessage

    data class FeedAdded(override val label: String) : ConnectionMessage

    data class Disconnected(override val label: String) : ConnectionMessage

    data class Removed(override val label: String) : ConnectionMessage

    data class Renamed(override val label: String) : ConnectionMessage

    /**
     * Asking a restricted feed's publisher for access did not get as far as a decision (SEE-156).
     * Each of these is something the owner can act on, and none of them changed anything: the
     * wallet was not opened, or it was and nothing was sent.
     */
    data class AccessNotAsked(override val label: String, val reason: AccessProblem) :
        ConnectionMessage

    /** The publisher approved this device, and the feed is now readable (SEE-156). */
    data class AccessApproved(override val label: String) : ConnectionMessage

    /** The publisher rejected this device's request (SEE-156). */
    data class AccessRejected(override val label: String) : ConnectionMessage

    /** The publisher revoked this device's access (SEE-156). */
    data class AccessRevoked(override val label: String) : ConnectionMessage
}

/** Why asking for access stopped before the publisher had anything to decide (SEE-156). */
enum class AccessProblem {
    /** No wallet is connected on this phone, and access belongs to a wallet. */
    NoWallet,
    /** The owner declined in the wallet, or it could not sign. Nothing was sent. */
    NotSigned,
    /** The feed was bound to another wallet while asking. Nothing was sent (SEE-174). */
    WalletChanged,
    /** The publisher's challenge was not the text this phone would sign. Nothing was signed. */
    BadChallenge,
    /** The publisher could not be reached. Nothing changed, and it can be tried again. */
    Unreachable,
    /** The publisher answered no. */
    Refused,
}

/**
 * State and actions of the connection screens. [cleartextPermitted] is the platform's network
 * security policy: whether plain HTTP may reach a host.
 */
class ConnectionsViewModel(
    private val repository: ConnectionRepository,
    private val foregroundUpdates: StateFlow<ForegroundUpdatesState>? = null,
    private val foregroundFeeds: StateFlow<ForegroundFeedsState>? = null,
    private val foregroundFeedStatus: StateFlow<FeedStatusState>? = null,
    /**
     * Asks for a fresh presence read, outside the periodic one (SEE-155).
     *
     * Production passes [io.github.brrenat.seekervault.feeds.FeedStatusManager.refresh]. It is a
     * function rather than the manager because this holds the manager's *state* as a flow and has
     * no other use for the object — and because a test of the refresh action should be able to
     * count the asks without standing up a poll.
     */
    private val refreshFeedStatus: () -> Unit = {},
    /** The bundled client plugins this build carries, which is what a manifest is matched to. */
    private val plugins: ProviderRegistry = ProviderRegistry.of(),
    /** A seam for the add flow's state tests; production always uses the repository method. */
    private val addFeed: suspend (FeedReference) -> FeedOutcome = repository::addFeed,
    /**
     * Restricted-feed access (SEE-156), or null in a build or a test that has none. It is the one
     * thing on these screens that opens the wallet, and it does so once per feed.
     */
    private val feedAccess: FeedAccessManager? = null,
    private val cleartextPermitted: (host: String) -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionsUiState())
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    // Whether the app has left the foreground since it last fetched on opening.
    private var hidden = false

    init {
        viewModelScope.launch {
            repository.connections.collect { list ->
                // Each connection against its own promise (SEE-97): one phone holds a sandbox
                // feed and a production one at the same time, and support is a question about one
                // connection rather than about the app.
                val support = list.associate { connection ->
                    connection.id to
                        serverSupport(connection.server, plugins, connection.environment)
                }
                _state.update { it.copy(connections = list, support = support) }
            }
        }
        foregroundUpdates?.let { updates ->
            viewModelScope.launch {
                updates.collect { current -> _state.update { it.copy(updates = current) } }
            }
        }
        foregroundFeeds?.let { feeds ->
            viewModelScope.launch {
                feeds.collect { current -> _state.update { it.copy(feeds = current) } }
            }
        }
        foregroundFeedStatus?.let { availability ->
            viewModelScope.launch {
                availability.collect { current -> _state.update { it.copy(feedStatus = current) } }
            }
        }
        feedAccess?.let { access ->
            viewModelScope.launch {
                access.states.collect { current ->
                    _state.update {
                        it.copy(
                            access = current,
                            message =
                                accessChange(it.access, current, it.connections) ?: it.message,
                        )
                    }
                }
            }
            // A decision reaches this phone only when it asks (SEE-156), so while the app is open
            // a request that is waiting is asked about now and then, signed with the device key and
            // never with the wallet. What it learns is said by the collector above.
            viewModelScope.launch {
                while (true) {
                    delay(ACCESS_CHECK_INTERVAL)
                    if (!hidden) checkWaitingAccess()
                }
            }
        }
        viewModelScope.launch {
            repository.load()
            _state.update { it.copy(loaded = true) }
            // The phone fetches when the app opens (docs/protocol.md). What that fetch brings back
            // is the inbox as it stood when the app opened, not a stream of new arrivals, so the
            // wait for it is part of opening.
            refreshAll().joinAll()
            _state.update { it.copy(fetched = true) }
        }
    }

    /**
     * Asks the publisher of a restricted feed for access, with the wallet selected on this phone
     * (SEE-156). It opens the wallet once, to sign text that says it is not a transaction.
     *
     * Asking again for a feed that already has a live request is a check rather than a second
     * request: the publisher would answer the first one anyway, and the owner should not be sent to
     * the wallet for nothing.
     */
    fun requestAccess(id: String) = working(id) { checkNotNull(feedAccess).requestAccess(id) }

    /**
     * Invitations a restricted feed's reference carried, held until the feed has a wallet to ask
     * for access with (SEE-174). In memory only: a link's invitation is single-use and a process
     * that dies before the wallet is chosen asks again without it.
     */
    private val invitations = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * A restricted feed was bound to a wallet: access is asked for with it, and an invitation its
     * link carried is redeemed after the request it belongs to (SEE-156, SEE-174). Access proven
     * with another address stays with that address and is not carried over.
     */
    fun afterWalletBound(id: String) {
        viewModelScope.launch {
            requestAccess(id)?.join()
            invitations.remove(id)?.let { redeemAccess(id, it)?.join() }
        }
    }

    /**
     * Asks where this phone's request stands, and redeems an invitation if one is waiting. Signed
     * with the device key; the wallet is not opened again.
     */
    fun checkAccess(id: String) = working(id) { checkNotNull(feedAccess).check(id) }

    /**
     * Asks about every request still waiting for the publisher, quietly: a check the owner did not
     * ask for does not report that the publisher could not be reached.
     */
    private fun checkWaitingAccess() {
        _state.value.access.values
            .filter {
                it.state == FeedAccessStore.State.Pending ||
                    it.state == FeedAccessStore.State.Approved
            }
            .forEach { record ->
                working(record.connectionId, quiet = true) {
                    checkNotNull(feedAccess).check(record.connectionId)
                }
            }
    }

    /**
     * What to tell the owner about a decision that just reached this phone, or null. Only a change
     * to a request this phone already held counts: loading what is stored, or a new request, is not
     * news.
     */
    private fun accessChange(
        before: Map<String, FeedAccessStore.Record>,
        after: Map<String, FeedAccessStore.Record>,
        connections: List<Connection>,
    ): ConnectionMessage? =
        after.values.firstNotNullOfOrNull { record ->
            val previous = before[record.connectionId]
            if (
                previous == null ||
                    previous.requestId != record.requestId ||
                    previous.state == record.state
            ) {
                return@firstNotNullOfOrNull null
            }
            val label =
                connections.firstOrNull { it.id == record.connectionId }?.label
                    ?: return@firstNotNullOfOrNull null
            when (record.state) {
                FeedAccessStore.State.Connected -> ConnectionMessage.AccessApproved(label)
                FeedAccessStore.State.Rejected -> ConnectionMessage.AccessRejected(label)
                FeedAccessStore.State.Revoked -> ConnectionMessage.AccessRevoked(label)
                FeedAccessStore.State.Pending,
                FeedAccessStore.State.Approved,
                FeedAccessStore.State.Expired -> null
            }
        }

    /** Redeems an invitation that arrived as a link or a code rather than through a check. */
    fun redeemAccess(id: String, invitation: String) =
        working(id) { checkNotNull(feedAccess).redeem(id, invitation) }

    /** What to tell the owner about an access attempt, or null when the state says it already. */
    private fun accessMessage(result: AccessResult, label: String): ConnectionMessage? {
        val reason =
            when (result) {
                is AccessResult.Done,
                AccessResult.NotRestricted,
                AccessResult.NotRequested -> return null
                AccessResult.NoWallet -> AccessProblem.NoWallet
                is AccessResult.WalletDidNotSign -> AccessProblem.NotSigned
                AccessResult.WalletChanged -> AccessProblem.WalletChanged
                AccessResult.BadChallenge -> AccessProblem.BadChallenge
                AccessResult.Unreachable -> AccessProblem.Unreachable
                is AccessResult.Refused -> AccessProblem.Refused
            }
        return ConnectionMessage.AccessNotAsked(label, reason)
    }

    private fun working(
        id: String,
        quiet: Boolean = false,
        block: suspend () -> AccessResult,
    ): Job? {
        if (feedAccess == null) return null
        if (id in _state.value.accessWorking) return null
        _state.update { it.copy(accessWorking = it.accessWorking + id) }
        return viewModelScope.launch {
            val result = block()
            _state.update {
                it.copy(
                    accessWorking = it.accessWorking - id,
                    message =
                        if (quiet) it.message
                        else
                            accessMessage(
                                result,
                                it.connections.firstOrNull { one -> one.id == id }?.label.orEmpty(),
                            ) ?: it.message,
                )
            }
        }
    }

    /** The app left the foreground. A rotation doesn't count. */
    fun onAppHidden() {
        hidden = true
    }

    /**
     * The app is back in the foreground, which is opening it again: every connection is fetched, as
     * on the first start (docs/guides/pending-requests.md). The first start fetches once the stored
     * connections are read, and a rotation fetches nothing.
     */
    fun onAppVisible() {
        if (!hidden) return
        hidden = false
        refreshAll()
        checkWaitingAccess()
    }

    fun onCodeDraftChange(text: String) = _state.update { state ->
        state.copy(
            codeDraft = text,
            adding =
                when (state.adding) {
                    is AddConnectionState.PairingInvalid,
                    is AddConnectionState.FeedInvalid,
                    AddConnectionState.RetiredInvitation -> AddConnectionState.Idle
                    else -> state.adding
                },
        )
    }

    /** A pairing code or feed reference. Further scans are ignored once one is valid. */
    fun onCode(text: String) {
        _state.update { state ->
            if (
                state.adding !is AddConnectionState.Idle &&
                    state.adding !is AddConnectionState.PairingInvalid &&
                    state.adding !is AddConnectionState.FeedInvalid &&
                    state.adding != AddConnectionState.RetiredInvitation
            ) {
                return@update state
            }
            val adding =
                when (val result = PairingCodes.parse(text, cleartextPermitted)) {
                    is PairingCodeResult.Invalid -> routeAfterPairing(result.problem, text)
                    is PairingCodeResult.Valid ->
                        AddConnectionState.ConfirmPairing(
                            confirmationFor(result.code, state.connections)
                        )
                }
            state.copy(adding = adding)
        }
    }

    /** Pairs with the confirmed code, or tries again after a failure that may pass. */
    fun confirmPairing() {
        val confirmation =
            when (val adding = _state.value.adding) {
                is AddConnectionState.ConfirmPairing -> adding.confirmation
                is AddConnectionState.PairingFailed ->
                    adding.confirmation.takeIf { canRetry(adding.failure) }
                else -> null
            } ?: return
        val pairing = AddConnectionState.Pairing(confirmation)
        _state.update { it.copy(adding = pairing) }
        viewModelScope.launch {
            val next =
                try {
                    val connection = repository.pair(confirmation.code)
                    // The sidecar has one phone connection, so it revoked the old one: check now.
                    confirmation.sameServer.forEach { refresh(it.id) }
                    _state.update { it.copy(message = ConnectionMessage.Paired(connection.label)) }
                    AddConnectionState.Paired(connection)
                } catch (e: GatewayException) {
                    AddConnectionState.PairingFailed(confirmation, failureOf(e.kind))
                } catch (e: StorageException) {
                    AddConnectionState.PairingFailed(confirmation, PairingFailure.Storage)
                }
            // If the owner left the screen meanwhile, only the message remains.
            _state.update { if (it.adding == pairing) it.copy(adding = next) else it }
        }
    }

    /** Adds the confirmed feed, or tries a transient gateway failure again. */
    fun confirmFeed() {
        val reference =
            when (val adding = _state.value.adding) {
                is AddConnectionState.ConfirmFeed -> adding.reference
                is AddConnectionState.FeedFailed ->
                    adding.reference.takeIf { canRetry(adding.failure) }
                else -> null
            } ?: return
        val working = AddConnectionState.AddingFeed(reference)
        _state.update { it.copy(adding = working) }
        viewModelScope.launch {
            val next =
                try {
                    when (val outcome = addFeed(reference)) {
                        is FeedOutcome.Added -> {
                            _state.update {
                                it.copy(
                                    message = ConnectionMessage.FeedAdded(outcome.connection.label)
                                )
                            }
                            // A restricted feed is added and then asked for (SEE-156), with the
                            // wallet the owner binds it to next (SEE-174): nothing is signed
                            // before a wallet is chosen for this feed, so the request — and an
                            // invitation the reference carried, which is redeemed straight after
                            // it — waits for [afterWalletBound].
                            if (
                                outcome.connection.server.manifest?.feedAccess
                                    is FeedAccess.Restricted
                            ) {
                                reference.invitation?.let {
                                    invitations[outcome.connection.id] = it
                                }
                            }
                            AddConnectionState.FeedAdded(outcome.connection)
                        }
                        is FeedOutcome.Already -> AddConnectionState.FeedAlready(outcome.connection)
                        is FeedOutcome.Refused ->
                            AddConnectionState.FeedFailed(
                                reference,
                                FeedAddFailure.Refused(outcome.problem),
                            )
                        is FeedOutcome.Failed ->
                            AddConnectionState.FeedFailed(
                                reference,
                                FeedAddFailure.Check(outcome.outcome),
                            )
                        FeedOutcome.NoGateway ->
                            AddConnectionState.FeedFailed(reference, FeedAddFailure.NoGateway)
                    }
                } catch (e: StorageException) {
                    AddConnectionState.FeedFailed(reference, FeedAddFailure.Storage)
                }
            _state.update { if (it.adding == working) it.copy(adding = next) else it }
        }
    }

    /** Forgets the entered reference and any pairing token when the owner cancels or leaves. */
    fun resetAdding() = _state.update { it.copy(codeDraft = "", adding = AddConnectionState.Idle) }

    /**
     * Returns the fetch, or null when one for [id] is already running.
     *
     * A feed's refresh also asks its gateway whether the publisher is running (SEE-155). The two
     * are separate questions asked of the same gateway, and only the first of them used to be asked
     * here — so an owner whose publisher had just come back could refresh, watch the fetch succeed,
     * and still be told "Feed offline" until the next periodic read half a minute later. The
     * presence read is not awaited: it is one small call about every feed on every gateway, not
     * about this connection, and the row it corrects repaints from the manager's own state.
     */
    fun refresh(id: String): Job? {
        if (repository.connection(id)?.mode == ConnectionMode.GatewayFeed) refreshFeedStatus()
        return fetch(id)
    }

    /** The fetch alone, for the callers that are not an owner asking for one. */
    private fun fetch(id: String): Job? {
        if (id in _state.value.refreshing) return null
        _state.update { it.copy(refreshing = it.refreshing + id) }
        return viewModelScope.launch {
            try {
                repository.refresh(id)
            } finally {
                _state.update { it.copy(refreshing = it.refreshing - id) }
            }
        }
    }

    /** Renames the connection, or returns why [label] isn't accepted. */
    fun rename(id: String, label: String): LabelProblem? {
        labelProblem(label)?.let {
            return it
        }
        viewModelScope.launch {
            repository.rename(id, label)
            _state.update { it.copy(message = ConnectionMessage.Renamed(label.trim())) }
        }
        return null
    }

    /**
     * Moves a gateway connection between the environments its server serves (SEE-97,
     * docs/wiki/environments.md).
     *
     * The answers that say no cannot be reached from the screen — it offers only what the publisher
     * serves, and only for a feed — so there is nothing to report here: what the owner sees is the
     * card itself, showing the promise that is now being kept.
     */
    fun setEnvironment(id: String, environment: PluginEnvironment) {
        viewModelScope.launch { repository.setEnvironment(id, environment) }
    }

    /** Stores the marker colour. The connection list flow repaints every open screen. */
    fun setColour(id: String, colour: ServerColour) {
        viewModelScope.launch { repository.setColour(id, colour) }
    }

    fun askToDisconnect(id: String) {
        val connection = repository.connection(id) ?: return
        val dialog =
            if (connection.usable) DisconnectState.Confirm(id)
            else DisconnectState.ConfirmRemove(id)
        _state.update { it.copy(disconnect = dialog) }
    }

    /** Revokes the connection at its sidecar, then removes it from the phone. */
    fun confirmDisconnect() {
        val confirm = _state.value.disconnect as? DisconnectState.Confirm ?: return
        val label = repository.connection(confirm.id)?.label ?: return closeDialog()
        _state.update { it.copy(disconnect = DisconnectState.Working(confirm.id)) }
        viewModelScope.launch {
            try {
                repository.disconnect(confirm.id)
                _state.update {
                    it.copy(disconnect = null, message = ConnectionMessage.Disconnected(label))
                }
            } catch (e: GatewayException) {
                _state.update {
                    it.copy(disconnect = DisconnectState.NotReached(confirm.id, e.kind.toOutcome()))
                }
            }
        }
    }

    /** Removes the connection from the phone only. */
    fun confirmRemove() {
        val dialog = _state.value.disconnect
        if (dialog !is DisconnectState.NotReached && dialog !is DisconnectState.ConfirmRemove)
            return
        val label = repository.connection(dialog.id)?.label ?: return closeDialog()
        _state.update { it.copy(disconnect = DisconnectState.Working(dialog.id)) }
        viewModelScope.launch {
            repository.remove(dialog.id)
            _state.update { it.copy(disconnect = null, message = ConnectionMessage.Removed(label)) }
        }
    }

    fun dismissDisconnect() {
        if (_state.value.disconnect !is DisconnectState.Working) closeDialog()
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun closeDialog() = _state.update { it.copy(disconnect = null) }

    /**
     * Every connection fetched, on opening and on returning to the foreground.
     *
     * It fetches rather than refreshes: presence is asked for once per foregrounding by the status
     * manager itself, and asking once per feed here would send the same read as many times as the
     * owner has feeds (SEE-155).
     */
    private fun refreshAll(): List<Job> =
        repository.connections.value.filter { it.usable }.mapNotNull { fetch(it.id) }

    private fun confirmationFor(code: PairingCode, connections: List<Connection>) =
        Confirmation(
            code = code,
            sameServer = connections.filter { it.serverId == code.serverId && it.usable },
            sameAddress =
                connections.filter {
                    it.serverUrl == code.serverUrl && it.serverId != code.serverId
                },
        )

    /**
     * A malformed pairing URI remains a pairing problem. Only text that is not a pairing URI is
     * offered to the feed parser, so neither route can accidentally call the other's action.
     */
    private fun routeAfterPairing(problem: PairingCodeProblem, text: String): AddConnectionState =
        when (problem) {
            PairingCodeProblem.NotACode,
            PairingCodeProblem.NotSeekerVault ->
                if (isRetiredGatewayInvitation(text)) AddConnectionState.RetiredInvitation
                else
                    when (val feed = FeedReferences.parse(text, cleartextPermitted)) {
                        is FeedReferenceResult.Valid ->
                            AddConnectionState.ConfirmFeed(feed.reference)
                        is FeedReferenceResult.Invalid ->
                            AddConnectionState.FeedInvalid(feed.problem)
                    }
            else -> AddConnectionState.PairingInvalid(problem)
        }

    private companion object {
        /** How often a waiting access request is asked about while the app is open (SEE-156). */
        const val ACCESS_CHECK_INTERVAL = 20_000L

        /** A retry can only help when the sidecar wasn't reached or the failure is unknown. */
        fun canRetry(failure: PairingFailure) =
            failure == PairingFailure.Unreachable || failure == PairingFailure.Other

        fun canRetry(failure: FeedAddFailure) =
            failure is FeedAddFailure.Check &&
                (failure.outcome == CheckOutcome.Unreachable ||
                    failure.outcome == CheckOutcome.Failed)

        fun failureOf(kind: GatewayException.Kind): PairingFailure =
            when (kind) {
                GatewayException.Kind.Unauthenticated -> PairingFailure.CodeRefused
                GatewayException.Kind.Rejected -> PairingFailure.WrongAddress
                GatewayException.Kind.CertificateRejected -> PairingFailure.CertificateRejected
                GatewayException.Kind.CleartextBlocked -> PairingFailure.CleartextBlocked
                GatewayException.Kind.Unreachable -> PairingFailure.Unreachable
                GatewayException.Kind.NotFound,
                // A host that doesn't know Pair is not a sidecar, whatever else it is.
                GatewayException.Kind.Unimplemented,
                GatewayException.Kind.BadResponse -> PairingFailure.BadResponse
                // Neither can come of pairing, which has no request and nothing prepared.
                GatewayException.Kind.InvalidState,
                GatewayException.Kind.StalePreparation,
                // Nor can a feed's access refusals: pairing is a direct server's, and a feed is
                // never paired with (SEE-156).
                GatewayException.Kind.AccessRequired,
                GatewayException.Kind.AccessRevoked,
                GatewayException.Kind.AccessExpired,
                GatewayException.Kind.Other -> PairingFailure.Other
            }
    }
}
