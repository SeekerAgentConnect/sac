package io.github.brrenat.seekervault.connections

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import com.connectrpc.simpleTimeouts
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.PairingServiceClient
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.RequestErrorDetail
import io.github.brrenat.seekervault.request.v1.RequestServiceClient
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.listPendingRequest
import io.github.brrenat.seekervault.request.v1.pairRequest
import io.github.brrenat.seekervault.request.v1.prepareRequestRequest
import io.github.brrenat.seekervault.request.v1.publishWalletRequest
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.revokeConnectionRequest
import java.io.IOException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient

/**
 * [ConnectionGateway] over the Connect protocol with OkHttp. The client keeps OkHttp's normal TLS
 * checks: the platform's trusted certificates and host name verification. Nothing here relaxes
 * them.
 */
class ConnectConnectionGateway(private val httpClient: OkHttpClient) : ConnectionGateway {
    override suspend fun pair(code: PairingCode, deviceName: String): PairedConnection {
        val request = pairRequest {
            serverUrl = code.serverUrl
            this.deviceName = deviceName
        }
        val response = call {
            PairingServiceClient(protocolClient(code.serverUrl)).pair(request, bearer(code.token))
        }
        return PairedConnection(response.connectionId, response.phoneToken, response.serverId)
    }

    override suspend fun listPending(
        serverUrl: String,
        credential: String,
        connectionId: String,
        pageToken: String,
    ): PendingRequests {
        val request = listPendingRequest {
            this.connectionId = connectionId
            pageSize = PAGE_SIZE
            this.pageToken = pageToken
        }
        val response = call {
            RequestServiceClient(protocolClient(serverUrl)).listPending(request, bearer(credential))
        }
        return PendingRequests(response.requestsList, response.nextPageToken)
    }

    override suspend fun prepareRequest(
        serverUrl: String,
        credential: String,
        key: RequestKey,
    ): PreparedTransaction {
        val request = prepareRequestRequest {
            ref = requestRef {
                connectionId = key.connectionId
                requestId = key.requestId
            }
        }
        return call {
            RequestServiceClient(protocolClient(serverUrl))
                .prepareRequest(request, bearer(credential))
        }
            .prepared
    }

    override suspend fun submitResult(
        serverUrl: String,
        credential: String,
        submission: SubmitResultRequest,
    ): ActionRequest = call {
        RequestServiceClient(protocolClient(serverUrl)).submitResult(submission, bearer(credential))
    }
        .request

    override suspend fun publishWallet(
        serverUrl: String,
        credential: String,
        connectionId: String,
        binding: WalletBinding?,
    ): List<String> {
        val request = publishWalletRequest {
            this.connectionId = connectionId
            // An absent binding is what "no wallet is connected" means on the wire.
            if (binding != null) this.binding = binding
        }
        val response = call {
            RequestServiceClient(protocolClient(serverUrl))
                .publishWallet(request, bearer(credential))
        }
        return response.cancelledList.map { it.requestId }
    }

    override suspend fun revoke(serverUrl: String, credential: String, connectionId: String) {
        val request = revokeConnectionRequest { this.connectionId = connectionId }
        call {
            PairingServiceClient(protocolClient(serverUrl))
                .revokeConnection(request, bearer(credential))
        }
    }

    private fun protocolClient(serverUrl: String) =
        ProtocolClient(
            httpClient = ConnectOkHttpClient(httpClient),
            config =
                ProtocolClientConfig(
                    host = serverUrl,
                    serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                    networkProtocol = NetworkProtocol.CONNECT,
                    timeoutOracle = simpleTimeouts(unaryTimeout = 15.seconds, streamTimeout = null),
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
        const val PAGE_SIZE = 100

        fun classify(error: Throwable): GatewayException {
            val causes = causesOf(error)
            val code = causes.firstNotNullOfOrNull { (it as? ConnectException)?.code }
            val kind =
                when {
                    // Before the generic IOException: a TLS failure is one too.
                    causes.any {
                        it is SSLHandshakeException ||
                            it is SSLPeerUnverifiedException ||
                            it is CertificateException ||
                            it is CertPathValidatorException
                    } -> GatewayException.Kind.CertificateRejected
                    causes.any {
                        it is UnknownServiceException && "CLEARTEXT" in it.message.orEmpty()
                    } -> GatewayException.Kind.CleartextBlocked
                    code == Code.UNAUTHENTICATED -> GatewayException.Kind.Unauthenticated
                    code == Code.INVALID_ARGUMENT -> GatewayException.Kind.Rejected
                    code == Code.NOT_FOUND -> GatewayException.Kind.NotFound
                    code == Code.FAILED_PRECONDITION -> GatewayException.Kind.InvalidState
                    code == Code.UNAVAILABLE || causes.any { it is IOException } ->
                        GatewayException.Kind.Unreachable
                    else -> GatewayException.Kind.Other
                }
            // For INVALID_STATE, the sidecar sends the request as it is now
            // (docs/protocol.md#request-errors).
            val detail =
                causes.filterIsInstance<ConnectException>().firstNotNullOfOrNull { exception ->
                    runCatching { exception.unpackedDetails(RequestErrorDetail::class) }
                        .getOrNull()
                        ?.firstOrNull()
                }
            return GatewayException(
                kind,
                error.message,
                error,
                request = detail?.takeIf { it.hasRequest() }?.request,
            )
        }

        /**
         * [error], its causes, and the exceptions suppressed along the way. When a host has several
         * addresses, OkHttp throws the first route's failure and suppresses the others'. A refused
         * IPv6 connection must not hide the certificate failure on IPv4.
         */
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
