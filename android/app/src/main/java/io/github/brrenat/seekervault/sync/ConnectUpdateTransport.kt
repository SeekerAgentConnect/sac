package io.github.brrenat.seekervault.sync

import com.connectrpc.BidirectionalStreamInterface
import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import com.connectrpc.simpleTimeouts
import com.google.protobuf.Timestamp
import io.github.brrenat.seekervault.request.v1.PairingServiceClient
import io.github.brrenat.seekervault.request.v1.getConnectionCapabilitiesRequest
import io.github.brrenat.seekervault.update.v1.SubscribeRequest
import io.github.brrenat.seekervault.update.v1.SubscribeResponse
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import io.github.brrenat.seekervault.update.v1.UpdateServiceClient
import io.github.brrenat.seekervault.update.v1.clientHeartbeat
import io.github.brrenat.seekervault.update.v1.subscribe
import io.github.brrenat.seekervault.update.v1.subscribeRequest
import java.io.IOException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol

/** The production update calls, using Connect for discovery and genuine gRPC for UpdateService. */
class ConnectUpdateTransport(private val httpClient: OkHttpClient) : UpdateTransport {
    // OkHttp negotiates h2 over TLS, but cleartext HTTP/2 has no ALPN negotiation. The sidecar only
    // advertises cleartext updates on loopback, where the development listener expects prior
    // knowledge. Keeping this client separate preserves HTTP/1.1 capability discovery.
    private val cleartextGrpcHttpClient =
        httpClient.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()

    override suspend fun discover(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): UpdateEndpoint? {
        val request = getConnectionCapabilitiesRequest { this.connectionId = connectionId }
        val response = call {
            PairingServiceClient(client(serverUrl, NetworkProtocol.CONNECT))
                .getConnectionCapabilities(request, bearer(credential))
        }
        return response.updates
            .takeIf { response.hasUpdates() }
            ?.let {
                UpdateEndpoint(it.protocolVersion, it.grpcUrl)
            }
    }

    override suspend fun sync(
        endpoint: UpdateEndpoint,
        credential: String,
        request: SyncRequest,
    ): SyncResponse = call {
        UpdateServiceClient(client(endpoint.grpcUrl, NetworkProtocol.GRPC, grpcHttp(endpoint)))
            .sync(request, bearer(credential))
    }

    override suspend fun subscribe(
        endpoint: UpdateEndpoint,
        credential: String,
        connectionId: String,
        resumeCursor: String,
        serverInstanceId: String,
    ): UpdateSubscription {
        val stream =
            try {
                UpdateServiceClient(
                        client(endpoint.grpcUrl, NetworkProtocol.GRPC, grpcHttp(endpoint))
                    )
                    .subscribe(bearer(credential))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classify(e)
            }
        val first = subscribeRequest {
            this.connectionId = connectionId
            subscribe = subscribe {
                protocolVersion = PROTOCOL_VERSION
                this.resumeCursor = resumeCursor
                this.serverInstanceId = serverInstanceId
            }
        }
        stream.send(first).exceptionOrNull()?.let { error ->
            withContext(NonCancellable) { runCatching { stream.receiveClose() } }
            throw classify(error)
        }
        return ConnectUpdateSubscription(connectionId, stream)
    }

    private class ConnectUpdateSubscription(
        private val connectionId: String,
        private val stream: BidirectionalStreamInterface<SubscribeRequest, SubscribeResponse>,
    ) : UpdateSubscription {
        override val responses: ReceiveChannel<SubscribeResponse> = stream.responseChannel()

        override suspend fun heartbeat(sequence: Long, appliedCursor: String, sentAt: Instant) {
            val message = subscribeRequest {
                connectionId = this@ConnectUpdateSubscription.connectionId
                heartbeat = clientHeartbeat {
                    this.sequence = sequence
                    this.appliedCursor = appliedCursor
                    this.sentAt =
                        Timestamp.newBuilder()
                            .setSeconds(sentAt.epochSecond)
                            .setNanos(sentAt.nano)
                            .build()
                }
            }
            stream.send(message).exceptionOrNull()?.let { throw classify(it) }
        }

        override suspend fun close() {
            withContext(NonCancellable) { runCatching { stream.receiveClose() } }
        }
    }

    private fun client(
        host: String,
        protocol: NetworkProtocol,
        http: OkHttpClient = httpClient,
    ) =
        ProtocolClient(
            httpClient = ConnectOkHttpClient(http),
            config =
                ProtocolClientConfig(
                    host = host,
                    serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                    networkProtocol = protocol,
                    timeoutOracle = simpleTimeouts(unaryTimeout = TIMEOUT, streamTimeout = null),
                ),
        )

    private fun bearer(secret: String) = mapOf("Authorization" to listOf("Bearer $secret"))

    private fun grpcHttp(endpoint: UpdateEndpoint): OkHttpClient =
        if (endpoint.grpcUrl.startsWith("http://")) cleartextGrpcHttpClient else httpClient

    private suspend fun <T> call(block: suspend () -> ResponseMessage<T>): T {
        val response =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classify(e)
            }
        return when (response) {
            is ResponseMessage.Success -> response.message
            is ResponseMessage.Failure -> throw classify(response.cause)
        }
    }

    private companion object {
        // Sync can include the sidecar's existing bounded confirmation pass (four 20-second chain
        // reads in parallel), so it gets room beyond a normal database-only unary call.
        val TIMEOUT = 30.seconds
        const val PROTOCOL_VERSION = 1

        fun classify(error: Throwable): UpdateTransportException {
            val causes = causesOf(error)
            val code = causes.firstNotNullOfOrNull { (it as? ConnectException)?.code }
            val kind =
                when {
                    causes.any {
                        it is SSLHandshakeException ||
                            it is SSLPeerUnverifiedException ||
                            it is CertificateException ||
                            it is CertPathValidatorException
                    } -> UpdateTransportException.Kind.CertificateRejected
                    causes.any {
                        it is UnknownServiceException && "CLEARTEXT" in it.message.orEmpty()
                    } -> UpdateTransportException.Kind.CleartextBlocked
                    code == Code.UNAUTHENTICATED -> UpdateTransportException.Kind.Unauthenticated
                    code == Code.UNIMPLEMENTED -> UpdateTransportException.Kind.UpgradeRequired
                    code == Code.FAILED_PRECONDITION ->
                        UpdateTransportException.Kind.SnapshotInvalid
                    code == Code.INVALID_ARGUMENT || code == Code.NOT_FOUND ->
                        UpdateTransportException.Kind.BadResponse
                    code == Code.UNAVAILABLE || causes.any { it is IOException } ->
                        UpdateTransportException.Kind.Unreachable
                    else -> UpdateTransportException.Kind.Other
                }
            return UpdateTransportException(kind, error.message, error)
        }

        fun causesOf(error: Throwable): List<Throwable> {
            val found = mutableListOf<Throwable>()
            val pending = ArrayDeque(listOf(error))
            while (pending.isNotEmpty() && found.size < 32) {
                val next = pending.removeFirst()
                if (found.any { it === next }) continue
                found += next
                next.cause?.let(pending::addLast)
                next.suppressed.forEach(pending::addLast)
            }
            return found
        }
    }
}
