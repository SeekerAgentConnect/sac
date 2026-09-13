package io.github.brrenat.seekervault.sync

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import com.connectrpc.simpleTimeouts
import io.github.brrenat.seekervault.request.v1.PairingServiceClient
import io.github.brrenat.seekervault.request.v1.getConnectionCapabilitiesRequest
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import io.github.brrenat.seekervault.update.v1.UpdateServiceClient
import java.io.IOException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient

/** The production update calls, using Connect for discovery and genuine gRPC for UpdateService. */
class ConnectUpdateTransport(private val httpClient: OkHttpClient) : UpdateTransport {
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
        UpdateServiceClient(client(endpoint.grpcUrl, NetworkProtocol.GRPC))
            .sync(request, bearer(credential))
    }

    private fun client(host: String, protocol: NetworkProtocol) =
        ProtocolClient(
            httpClient = ConnectOkHttpClient(httpClient),
            config =
                ProtocolClientConfig(
                    host = host,
                    serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                    networkProtocol = protocol,
                    timeoutOracle = simpleTimeouts(unaryTimeout = TIMEOUT, streamTimeout = null),
                ),
        )

    private fun bearer(secret: String) = mapOf("Authorization" to listOf("Bearer $secret"))

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
