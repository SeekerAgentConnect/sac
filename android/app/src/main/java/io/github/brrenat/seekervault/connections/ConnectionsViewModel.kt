package io.github.brrenat.seekervault.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.feeds.ForegroundFeedsState
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.FeedReferenceProblem
import io.github.brrenat.seekervault.servers.FeedReferenceResult
import io.github.brrenat.seekervault.servers.FeedReferences
import io.github.brrenat.seekervault.servers.ManifestProblem
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.serverSupport
import io.github.brrenat.seekervault.sync.ForegroundUpdatesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the connection screens show. */
data class ConnectionsUiState(
    val connections: List<Connection> = emptyList(),
    /** False until the stored connections have been read. */
    val loaded: Boolean = false,
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
     * Whether this build supports each connection's server, by connection ID (SEE-88). It is worked
     * out here from the manifest the connection caches and the plugins compiled into this build,
     * and never stored: a verdict on disk would outlive the build that reached it.
     */
    val support: Map<String, ServerSupport> = emptyMap(),
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
}

/**
 * State and actions of the connection screens. [cleartextPermitted] is the platform's network
 * security policy: whether plain HTTP may reach a host.
 */
class ConnectionsViewModel(
    private val repository: ConnectionRepository,
    private val foregroundUpdates: StateFlow<ForegroundUpdatesState>? = null,
    private val foregroundFeeds: StateFlow<ForegroundFeedsState>? = null,
    /** The bundled client plugins this build carries, which is what a manifest is matched to. */
    private val plugins: PluginRegistry = PluginRegistry.of(),
    /** A seam for the add flow's state tests; production always uses the repository method. */
    private val addFeed: suspend (FeedReference) -> FeedOutcome = repository::addFeed,
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
        viewModelScope.launch {
            repository.load()
            _state.update { it.copy(loaded = true) }
            // The phone fetches when the app opens (docs/protocol.md).
            refreshAll()
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

    fun refresh(id: String) {
        if (id in _state.value.refreshing) return
        _state.update { it.copy(refreshing = it.refreshing + id) }
        viewModelScope.launch {
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

    private fun refreshAll() =
        repository.connections.value.filter { it.usable }.forEach { refresh(it.id) }

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
                GatewayException.Kind.Other -> PairingFailure.Other
            }
    }
}
