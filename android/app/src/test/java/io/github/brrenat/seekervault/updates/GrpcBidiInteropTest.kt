package io.github.brrenat.seekervault.updates

import com.connectrpc.ProtocolClientConfig
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import com.connectrpc.simpleTimeouts
import io.github.brrenat.seekervault.sync.ConnectUpdateTransport
import io.github.brrenat.seekervault.sync.UpdateEndpoint
import io.github.brrenat.seekervault.update.v1.ResumeDisposition
import io.github.brrenat.seekervault.update.v1.SubscribeResponse
import io.github.brrenat.seekervault.update.v1.UpdateServiceClient
import io.github.brrenat.seekervault.update.v1.clientHeartbeat
import io.github.brrenat.seekervault.update.v1.subscribe
import io.github.brrenat.seekervault.update.v1.subscribeRequest
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real Connect Kotlin 0.9.0/OkHttp 5.4.0 client against Connect Node 2.2.0 on Node's HTTP/2
 * server. The helper serves only this proof; SEE-68 adds the production update service.
 */
class GrpcBidiInteropTest {
    @Test
    fun productionTransportKeepsItsSendSideOpenForHeartbeats() = runBlocking {
        ProofServer().use { server ->
            withTimeout(30_000) {
                val transport = ConnectUpdateTransport(http())
                val subscription =
                    transport.subscribe(
                        UpdateEndpoint(1, server.url),
                        PHONE_TOKEN,
                        CONNECTION_ID,
                        resumeCursor = "",
                        serverInstanceId = "",
                    )
                assertEquals(
                    SubscribeResponse.EventCase.READY,
                    subscription.responses.receive().eventCase,
                )

                subscription.heartbeat(1, "", Instant.parse("2026-09-14T12:00:00Z"))
                assertEquals(
                    1L,
                    subscription.responses.receive().heartbeat.acknowledgedSequence,
                )
                subscription.close()
                assertEquals(
                    """{"outcome":"cancelled","httpVersion":"2.0","protocol":"grpc","streamsStarted":1,"messages":2,"heartbeats":1}""",
                    server.marker(),
                )
            }
        }
    }

    @Test
    fun clientAndServerMessagesInterleaveBeforeTheClientClosesItsSendSide() = runBlocking {
        ProofServer().use { server ->
            withTimeout(30_000) {
                val stream = client(server.url).subscribe(headers())
                assertTrue(stream.send(subscribeMessage()).isSuccess)

                val ready = stream.responseChannel().receive()
                assertEquals(SubscribeResponse.EventCase.READY, ready.eventCase)
                assertEquals(1, ready.ready.protocolVersion)
                assertEquals(
                    ResumeDisposition.RESUME_DISPOSITION_FULL_SYNC_REQUIRED,
                    ready.ready.resume,
                )
                assertEquals(30, ready.ready.heartbeatIntervalSeconds)
                assertFalse(stream.isSendClosed())

                assertTrue(stream.send(heartbeat(1)).isSuccess)
                val first = stream.responseChannel().receive()
                assertEquals(SubscribeResponse.EventCase.HEARTBEAT, first.eventCase)
                assertEquals(1L, first.heartbeat.acknowledgedSequence)
                assertFalse(stream.isSendClosed())

                assertTrue(stream.send(heartbeat(2)).isSuccess)
                val second = stream.responseChannel().receive()
                assertEquals(SubscribeResponse.EventCase.HEARTBEAT, second.eventCase)
                assertEquals(2L, second.heartbeat.acknowledgedSequence)
                assertFalse(stream.isSendClosed())

                stream.sendClose()
                assertTrue(stream.isSendClosed())
                val end = stream.responseChannel().receiveCatching()
                assertTrue(end.isClosed)
                stream.receiveClose()
                assertEquals(
                    """{"outcome":"complete","httpVersion":"2.0","protocol":"grpc","streamsStarted":1,"messages":3,"heartbeats":2}""",
                    server.marker(),
                )
            }
        }
    }

    @Test
    fun closingTheReceiveSideCancelsTheCallWithoutStartingAnotherStream() = runBlocking {
        ProofServer().use { server ->
            withTimeout(30_000) {
                val stream = client(server.url).subscribe(headers())
                assertTrue(stream.send(subscribeMessage()).isSuccess)
                assertEquals(
                    SubscribeResponse.EventCase.READY,
                    stream.responseChannel().receive().eventCase,
                )

                // This is the cleanup path a canceled foreground collector uses. It cancels the
                // OkHttp call rather than half-closing and reconnecting behind the caller's back.
                stream.receiveClose()
                assertTrue(stream.isReceiveClosed())
                assertTrue(stream.isSendClosed())
                assertEquals(
                    """{"outcome":"cancelled","httpVersion":"2.0","protocol":"grpc","streamsStarted":1,"messages":1,"heartbeats":0}""",
                    server.marker(),
                )
            }
        }
    }

    private fun http(): OkHttpClient =
        ConnectOkHttpClient.configureClient(
                OkHttpClient.Builder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            )
            .build()

    private fun client(url: String): UpdateServiceClient {
        val http = http()
        return UpdateServiceClient(
            ProtocolClient(
                ConnectOkHttpClient(http),
                ProtocolClientConfig(
                    host = url,
                    serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                    networkProtocol = NetworkProtocol.GRPC,
                    timeoutOracle = simpleTimeouts(unaryTimeout = 10.seconds, streamTimeout = null),
                ),
            )
        )
    }

    private fun headers() = mapOf("Authorization" to listOf("Bearer $PHONE_TOKEN"))

    private fun subscribeMessage() = subscribeRequest {
        connectionId = CONNECTION_ID
        subscribe = subscribe { protocolVersion = 1 }
    }

    private fun heartbeat(sequence: Long) = subscribeRequest {
        connectionId = CONNECTION_ID
        heartbeat = clientHeartbeat { this.sequence = sequence }
    }

    private class ProofServer : AutoCloseable {
        private val repoRoot =
            File(
                checkNotNull(System.getProperty("seekervault.repoRoot")) {
                    "run this test through Gradle"
                }
            )
        private val marker =
            Files.createTempFile("seeker-vault-bidi-proof", ".json").also {
                Files.delete(it)
            }
        private val process =
            ProcessBuilder("node", "src/testing/grpc-bidi-proof-server.ts")
                .directory(File(repoRoot, "sidecar"))
                .redirectErrorStream(true)
                .apply {
                    environment()
                        .putAll(
                            mapOf(
                                "PROOF_PHONE_TOKEN" to PHONE_TOKEN,
                                "PROOF_CONNECTION_ID" to CONNECTION_ID,
                                "PROOF_MARKER_PATH" to marker.toString(),
                            )
                        )
                }
                .start()
        private val output = process.inputStream.bufferedReader()
        val url: String =
            checkNotNull(output.readLine()) { "the Node proof server exited before listening" }
                .also { check(it.startsWith("http://127.0.0.1:")) { it } }

        suspend fun marker(): String {
            while (!Files.exists(marker)) {
                check(process.isAlive) {
                    "the Node proof server exited early: ${output.readText()}"
                }
                delay(20)
            }
            return Files.readString(marker)
        }

        override fun close() {
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
            Files.deleteIfExists(marker)
        }
    }

    private companion object {
        const val CONNECTION_ID = "11111111-1111-4111-8111-111111111111"
        val PHONE_TOKEN = "p".repeat(64)
    }
}
