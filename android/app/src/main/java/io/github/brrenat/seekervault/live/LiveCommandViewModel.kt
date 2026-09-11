package io.github.brrenat.seekervault.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.live.v1.LiveCommand
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the live-test screen shows. */
data class LiveCommandUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val phoneToken: String = "",
    val connection: ConnectionState = ConnectionState.Disconnected(),
    val command: ReceivedCommand? = null,
) {
    companion object {
        /**
         * The Mac's sidecar through `adb reverse tcp:8080 tcp:8080` (docs/development/android.md).
         */
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:8080"
    }
}

sealed interface ConnectionState {
    data class Disconnected(val reason: DisconnectReason? = null) : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState
}

sealed interface DisconnectReason {
    data object InvalidUrl : DisconnectReason

    data object MissingToken : DisconnectReason

    /** The app left the foreground; the stream reopens when it returns. */
    data object Background : DisconnectReason

    data object Unauthenticated : DisconnectReason

    /** A newer WatchCommands stream replaced this one. */
    data object Replaced : DisconnectReason

    data object CleartextBlocked : DisconnectReason

    /** The first connection attempt could not reach the sidecar at [serverUrl]. */
    data class Unreachable(val serverUrl: String, val port: Int) : DisconnectReason

    /** The stream failed, or the sidecar ended it. */
    data class Lost(val detail: String?) : DisconnectReason
}

data class ReceivedCommand(val id: String, val text: String, val status: CommandStatus)

sealed interface CommandStatus {
    data object AwaitingOk : CommandStatus

    data object Sending : CommandStatus

    data object Acknowledged : CommandStatus

    data object TimedOut : CommandStatus

    data class Failed(val reason: AcknowledgeFailure) : CommandStatus
}

enum class AcknowledgeFailure {
    Cancelled,
    UnknownCommand,
    Unauthenticated,
    Unreachable,
    Other,
}

/**
 * State and actions of the live-test screen. It survives rotation, holds at most one WatchCommands
 * stream, and keeps that stream open only while the app is in the foreground.
 */
class LiveCommandViewModel(
    private val transports: LiveCommandTransportFactory,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveCommandUiState())
    val state: StateFlow<LiveCommandUiState> = _state.asStateFlow()

    private var session: Job? = null
    private var transport: LiveCommandTransport? = null
    private var deadline: Job? = null
    private var reconnectWhenVisible = false

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value) }

    fun onPhoneTokenChange(value: String) = _state.update { it.copy(phoneToken = value) }

    fun connect() {
        if (session?.isActive == true) return
        val serverUrl = _state.value.serverUrl.trim()
        val phoneToken = _state.value.phoneToken.trim()
        val invalid =
            when {
                !isHttpUrl(serverUrl) -> DisconnectReason.InvalidUrl
                phoneToken.isEmpty() -> DisconnectReason.MissingToken
                else -> null
            }
        if (invalid != null) {
            _state.update { it.copy(connection = ConnectionState.Disconnected(invalid)) }
            return
        }
        val transport = transports.create(serverUrl, phoneToken)
        this.transport = transport
        _state.update { it.copy(connection = ConnectionState.Connecting, command = null) }
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val reason =
                    try {
                        transport.watch().collect { event ->
                            when (event) {
                                WatchEvent.Ready ->
                                    _state.update {
                                        it.copy(connection = ConnectionState.Connected)
                                    }
                                is WatchEvent.Command -> show(event.command)
                            }
                        }
                        DisconnectReason.Lost(detail = null)
                    } catch (e: LiveTransportException) {
                        reasonFor(e)
                    }
                stop(reason)
            }
        session = job
        job.start()
    }

    fun disconnect() {
        reconnectWhenVisible = false
        stop(reason = null)
    }

    /** The app left the foreground: close the stream, and reopen it on return. */
    fun onAppHidden() {
        if (session?.isActive != true) return
        reconnectWhenVisible = true
        stop(DisconnectReason.Background)
    }

    fun onAppVisible() {
        if (!reconnectWhenVisible) return
        reconnectWhenVisible = false
        connect()
    }

    /** The user tapped OK. Only the first tap on a command sends an acknowledgement. */
    fun acknowledge() {
        val command = _state.value.command ?: return
        val transport = transport ?: return
        if (command.status != CommandStatus.AwaitingOk) return
        setStatus(command.id, CommandStatus.Sending)
        viewModelScope.launch {
            val status =
                try {
                    transport.acknowledge(command.id)
                    CommandStatus.Acknowledged
                } catch (e: LiveTransportException) {
                    statusFor(e)
                }
            setStatus(command.id, status)
        }
    }

    private fun show(command: LiveCommand) {
        deadline?.cancel()
        val expired = command.isExpiredAt(now())
        val status = if (expired) CommandStatus.TimedOut else CommandStatus.AwaitingOk
        _state.update { it.copy(command = ReceivedCommand(command.id, command.text, status)) }
        if (expired) return
        deadline = viewModelScope.launch {
            while (!command.isExpiredAt(now())) {
                val remaining =
                    Duration.between(now(), command.expiresAtInstant() ?: now()).toMillis()
                delay(remaining.coerceAtLeast(1))
            }
            // The sidecar has told the agent TIMEOUT by now; an OK would be refused.
            _state.update { state ->
                val current = state.command
                if (current?.id == command.id && current.status == CommandStatus.AwaitingOk) {
                    state.copy(command = current.copy(status = CommandStatus.TimedOut))
                } else {
                    state
                }
            }
        }
    }

    private fun setStatus(id: String, status: CommandStatus) {
        _state.update { state ->
            val current = state.command
            if (current?.id == id) state.copy(command = current.copy(status = status)) else state
        }
    }

    private fun stop(reason: DisconnectReason?) {
        session?.cancel()
        session = null
        transport = null
        deadline?.cancel()
        deadline = null
        // The sidecar cancels a waiting command when the phone disconnects, so clear it.
        _state.update { it.copy(connection = ConnectionState.Disconnected(reason), command = null) }
    }

    private fun reasonFor(error: LiveTransportException): DisconnectReason =
        when {
            error.kind == LiveTransportException.Kind.Unauthenticated ->
                DisconnectReason.Unauthenticated
            error.kind == LiveTransportException.Kind.Cancelled -> DisconnectReason.Replaced
            error.kind == LiveTransportException.Kind.CleartextBlocked ->
                DisconnectReason.CleartextBlocked
            // Never connected: usually the sidecar isn't running or `adb reverse` is missing.
            error.kind == LiveTransportException.Kind.Unreachable &&
                _state.value.connection == ConnectionState.Connecting -> unreachable()
            else -> DisconnectReason.Lost(error.message)
        }

    private fun unreachable(): DisconnectReason.Unreachable {
        val serverUrl = _state.value.serverUrl.trim()
        val uri = URI(serverUrl)
        val port =
            when {
                uri.port != -1 -> uri.port
                uri.scheme == "https" -> 443
                else -> 80
            }
        return DisconnectReason.Unreachable(serverUrl, port)
    }

    private fun statusFor(error: LiveTransportException): CommandStatus =
        when (error.kind) {
            LiveTransportException.Kind.TimedOut -> CommandStatus.TimedOut
            LiveTransportException.Kind.Cancelled ->
                CommandStatus.Failed(AcknowledgeFailure.Cancelled)
            LiveTransportException.Kind.UnknownCommand ->
                CommandStatus.Failed(AcknowledgeFailure.UnknownCommand)
            LiveTransportException.Kind.Unauthenticated ->
                CommandStatus.Failed(AcknowledgeFailure.Unauthenticated)
            LiveTransportException.Kind.Unreachable,
            LiveTransportException.Kind.CleartextBlocked ->
                CommandStatus.Failed(AcknowledgeFailure.Unreachable)
            LiveTransportException.Kind.Other -> CommandStatus.Failed(AcknowledgeFailure.Other)
        }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    }
}
