package io.github.brrenat.seekervault.live

import io.github.brrenat.seekervault.live.v1.LiveCommand
import kotlinx.coroutines.flow.Flow

/** The phone's side of LiveCommandService (docs/protocol.md), independent of the network stack. */
interface LiveCommandTransport {
    /**
     * Opens WatchCommands. The flow emits [WatchEvent.Ready] first and then each command. It throws
     * [LiveTransportException] when the stream fails and completes when the sidecar ends it;
     * cancelling the collection closes the stream.
     */
    fun watch(): Flow<WatchEvent>

    /** Sends the user's OK for command [id]; throws [LiveTransportException] if it is refused. */
    suspend fun acknowledge(id: String)
}

fun interface LiveCommandTransportFactory {
    fun create(serverUrl: String, phoneToken: String): LiveCommandTransport
}

sealed interface WatchEvent {
    /** The sidecar registered this stream as its only watcher. */
    data object Ready : WatchEvent

    data class Command(val command: LiveCommand) : WatchEvent
}

/** A failed stream or acknowledgement, classified by what the screen tells the user. */
class LiveTransportException(val kind: Kind, message: String?, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Kind {
        /** `unauthenticated`: the sidecar rejected the phone token. */
        Unauthenticated,
        /** `deadline_exceeded`: the command reached its deadline. */
        TimedOut,
        /** `canceled`: the command was cancelled, or a newer stream replaced this one. */
        Cancelled,
        /** `not_found`: the sidecar does not track the command. */
        UnknownCommand,
        /** The sidecar could not be reached or went away (`unavailable`, network errors). */
        Unreachable,
        /** Android's network security policy blocked plain HTTP to this host. */
        CleartextBlocked,
        Other,
    }
}
