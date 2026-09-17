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
import io.github.brrenat.seekervault.request.v1.RequestError
import io.github.brrenat.seekervault.request.v1.RequestErrorDetail
import io.github.brrenat.seekervault.request.v1.RequestServiceClient
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.checkStatusRequest
import io.github.brrenat.seekervault.request.v1.getServerManifestRequest
import io.github.brrenat.seekervault.request.v1.listPendingRequest
import io.github.brrenat.seekervault.request.v1.pairRequest
import io.github.brrenat.seekervault.request.v1.prepareRequestRequest
import io.github.brrenat.seekervault.request.v1.publishWalletRequest
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.revokeConnectionRequest
import io.github.brrenat.seekervault.request.v1.setFcmTokenRequest
import io.github.brrenat.seekervault.server.v1.ServerManifest
import java.io.IOException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
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

    override suspend fun serverManifest(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): ServerManifest? {
        val request = getServerManifestRequest { this.connectionId = connectionId }
        val response =
            try {
                call {
                    PairingServiceClient(protocolClient(serverUrl))
                        .getServerManifest(request, bearer(credential))
                }
            } catch (e: GatewayException) {
                // A server that doesn't know the call publishes no manifest: that is the
                // legacy-direct path, and it is reported as an absence rather than a failure so
                // nothing upstream has to know which protocol detail said so.
                if (e.kind == GatewayException.Kind.Unimplemented) return null
                throw e
            }
        if (!response.hasManifest()) {
            // A server that answers this call has a manifest by definition, so an empty response
            // is a server this app can't read rather than one with nothing to say.
            throw GatewayException(
                GatewayException.Kind.BadResponse,
                "a GetServerManifestResponse with no manifest",
            )
        }
        return response.manifest
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
        // Preparing reads a chain: the mint, both token accounts, the blockhash, the fee. The
        // sidecar bounds the whole of that, and this deadline is set above its bound, so the
        // phone never reports a failure for work the sidecar is still doing (sidecar
        // solana/rpc.ts).
        return call {
            RequestServiceClient(protocolClient(serverUrl, CHAIN_TIMEOUT))
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

    override suspend fun checkStatus(
        serverUrl: String,
        credential: String,
        key: RequestKey,
    ): ActionRequest {
        val request = checkStatusRequest {
            ref = requestRef {
                connectionId = key.connectionId
                requestId = key.requestId
            }
        }
        // Checking reads a chain too, so it gets the same deadline as preparing.
        return call {
            RequestServiceClient(protocolClient(serverUrl, CHAIN_TIMEOUT))
                .checkStatus(request, bearer(credential))
        }
            .request
    }

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

    override suspend fun setFcmToken(
        serverUrl: String,
        credential: String,
        connectionId: String,
        update: FcmTokenUpdate,
    ) {
        val request = setFcmTokenRequest {
            this.connectionId = connectionId
            when (update) {
                is FcmTokenUpdate.Register -> token = update.target
                is FcmTokenUpdate.ClearIfCurrent -> clearIfToken = update.target
            }
        }
        call {
            PairingServiceClient(protocolClient(serverUrl)).setFcmToken(request, bearer(credential))
        }
    }

    private fun protocolClient(serverUrl: String, timeout: Duration = TIMEOUT) =
        ProtocolClient(
            httpClient = ConnectOkHttpClient(httpClient),
            config =
                ProtocolClientConfig(
                    host = serverUrl,
                    serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                    networkProtocol = NetworkProtocol.CONNECT,
                    timeoutOracle = simpleTimeouts(unaryTimeout = timeout, streamTimeout = null),
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

        /** What a call that only reads the sidecar's own database gets. */
        val TIMEOUT = 15.seconds

        /**
         * What a call the sidecar answers by reading a chain gets. The sidecar gives one such
         * operation 20 seconds however many endpoint calls it makes, so this leaves room for the
         * request and the response on top of that bound rather than racing it.
         */
        val CHAIN_TIMEOUT = 30.seconds

        fun classify(error: Throwable): GatewayException {
            val causes = causesOf(error)
            val code = causes.firstNotNullOfOrNull { (it as? ConnectException)?.code }
            // For INVALID_STATE and STALE_PREPARATION, the sidecar sends the request as it is now
            // (docs/protocol.md#request-errors). Both arrive as failed_precondition, and only the
            // detail tells them apart: one says the request moved on, the other says the owner
            // must review a fresh preparation.
            val detail =
                causes.filterIsInstance<ConnectException>().firstNotNullOfOrNull { exception ->
                    runCatching { exception.unpackedDetails(RequestErrorDetail::class) }
                        .getOrNull()
                        ?.firstOrNull()
                }
            val stale = detail?.error == RequestError.REQUEST_ERROR_STALE_PREPARATION
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
                    code == Code.UNIMPLEMENTED -> GatewayException.Kind.Unimplemented
                    code == Code.INVALID_ARGUMENT -> GatewayException.Kind.Rejected
                    code == Code.NOT_FOUND -> GatewayException.Kind.NotFound
                    code == Code.FAILED_PRECONDITION && stale ->
                        GatewayException.Kind.StalePreparation
                    code == Code.FAILED_PRECONDITION -> GatewayException.Kind.InvalidState
                    code == Code.UNAVAILABLE || causes.any { it is IOException } ->
                        GatewayException.Kind.Unreachable
                    else -> GatewayException.Kind.Other
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
