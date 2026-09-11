package io.github.brrenat.seekervault.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val pairing: PairingState = PairingState.Idle,
    val disconnect: DisconnectState? = null,
    val message: ConnectionMessage? = null,
)

/** A valid code, with what the phone already knows about its server. */
data class Confirmation(
    val code: PairingCode,
    /** Live connections to the same server, which the sidecar revokes when this code pairs. */
    val sameServer: List<Connection>,
    /** Connections at the same URL but to another server. */
    val sameAddress: List<Connection>,
)

sealed interface PairingState {
    data object Idle : PairingState

    /** The entered or scanned text can't be used to pair. */
    data class Invalid(val problem: PairingCodeProblem) : PairingState

    /** Waiting for the owner to confirm the server. */
    data class Confirm(val confirmation: Confirmation) : PairingState

    data class Pairing(val confirmation: Confirmation) : PairingState

    data class Failed(val confirmation: Confirmation, val failure: PairingFailure) : PairingState

    data class Paired(val connection: Connection) : PairingState
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
    private val cleartextPermitted: (host: String) -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionsUiState())
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    // Whether the app has left the foreground since it last fetched on opening.
    private var hidden = false

    init {
        viewModelScope.launch {
            repository.connections.collect { list -> _state.update { it.copy(connections = list) } }
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
            pairing =
                if (state.pairing is PairingState.Invalid) PairingState.Idle else state.pairing,
        )
    }

    /** A code the owner entered or scanned. Further scans are ignored once a code is valid. */
    fun onCode(text: String) {
        _state.update { state ->
            if (state.pairing !is PairingState.Idle && state.pairing !is PairingState.Invalid) {
                return@update state
            }
            val pairing =
                when (val result = PairingCodes.parse(text, cleartextPermitted)) {
                    is PairingCodeResult.Invalid -> PairingState.Invalid(result.problem)
                    is PairingCodeResult.Valid ->
                        PairingState.Confirm(confirmationFor(result.code, state.connections))
                }
            state.copy(pairing = pairing)
        }
    }

    /** Pairs with the confirmed code, or tries again after a failure that may pass. */
    fun confirmPairing() {
        val confirmation =
            when (val pairing = _state.value.pairing) {
                is PairingState.Confirm -> pairing.confirmation
                is PairingState.Failed -> pairing.confirmation.takeIf { canRetry(pairing.failure) }
                else -> null
            } ?: return
        val pairing = PairingState.Pairing(confirmation)
        _state.update { it.copy(pairing = pairing) }
        viewModelScope.launch {
            val next =
                try {
                    val connection = repository.pair(confirmation.code)
                    // The sidecar has one phone connection, so it revoked the old one: check now.
                    confirmation.sameServer.forEach { refresh(it.id) }
                    _state.update { it.copy(message = ConnectionMessage.Paired(connection.label)) }
                    PairingState.Paired(connection)
                } catch (e: GatewayException) {
                    PairingState.Failed(confirmation, failureOf(e.kind))
                } catch (e: StorageException) {
                    PairingState.Failed(confirmation, PairingFailure.Storage)
                }
            // If the owner left the screen meanwhile, only the message remains.
            _state.update { if (it.pairing == pairing) it.copy(pairing = next) else it }
        }
    }

    /** Forgets the code and its token, for example when the owner leaves the screen. */
    fun resetPairing() = _state.update { it.copy(codeDraft = "", pairing = PairingState.Idle) }

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

    private companion object {
        /** A retry can only help when the sidecar wasn't reached or the failure is unknown. */
        fun canRetry(failure: PairingFailure) =
            failure == PairingFailure.Unreachable || failure == PairingFailure.Other

        fun failureOf(kind: GatewayException.Kind): PairingFailure =
            when (kind) {
                GatewayException.Kind.Unauthenticated -> PairingFailure.CodeRefused
                GatewayException.Kind.Rejected -> PairingFailure.WrongAddress
                GatewayException.Kind.CertificateRejected -> PairingFailure.CertificateRejected
                GatewayException.Kind.CleartextBlocked -> PairingFailure.CleartextBlocked
                GatewayException.Kind.Unreachable -> PairingFailure.Unreachable
                GatewayException.Kind.NotFound,
                GatewayException.Kind.BadResponse -> PairingFailure.BadResponse
                GatewayException.Kind.InvalidState,
                GatewayException.Kind.Other -> PairingFailure.Other
            }
    }
}
