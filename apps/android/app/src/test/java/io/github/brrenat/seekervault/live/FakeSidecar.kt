package io.github.brrenat.seekervault.live

import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.live.v1.LiveCommand
import io.github.brrenat.seekervault.live.v1.liveCommand
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** A fake sidecar for the screen tests: each watch() opens a [FakeStream] the test drives. */
class FakeSidecar : LiveCommandTransportFactory {
    val streams = mutableListOf<FakeStream>()
    val acknowledgements = mutableListOf<String>()
    val connections = mutableListOf<Pair<String, String>>()

    /** Answers each acknowledgement after recording it; replace it to fail or to hold. */
    var onAcknowledge: suspend (String) -> Unit = {}

    /** The most recently opened stream. */
    val stream: FakeStream
        get() = streams.last()

    override fun create(serverUrl: String, phoneToken: String): LiveCommandTransport {
        connections += serverUrl to phoneToken
        return object : LiveCommandTransport {
            override fun watch(): Flow<WatchEvent> = flow {
                val stream = FakeStream().also { streams += it }
                try {
                    emitAll(stream.events)
                } finally {
                    stream.open = false
                }
            }

            override suspend fun acknowledge(id: String) {
                acknowledgements += id
                onAcknowledge(id)
            }
        }
    }
}

class FakeStream {
    val events = Channel<WatchEvent>(Channel.UNLIMITED)

    /** False once the app closed the stream or the stream ended. */
    var open = true

    fun ready() {
        events.trySend(WatchEvent.Ready)
    }

    fun send(command: LiveCommand) {
        events.trySend(WatchEvent.Command(command))
    }

    fun fail(kind: LiveTransportException.Kind) {
        events.close(LiveTransportException(kind, "fake $kind"))
    }

    fun end() {
        events.close()
    }
}

fun command(id: String = "c1", text: String = "Hello Seeker", expiresAt: Instant): LiveCommand =
    liveCommand {
        this.id = id
        this.text = text
        this.expiresAt = timestamp {
            seconds = expiresAt.epochSecond
            nanos = expiresAt.nano
        }
    }
