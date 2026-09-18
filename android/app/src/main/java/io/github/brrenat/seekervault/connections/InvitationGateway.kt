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
import io.github.brrenat.seekervault.gateway.v1.DeviceResult
import io.github.brrenat.seekervault.gateway.v1.DeviceServiceClient
import io.github.brrenat.seekervault.gateway.v1.InvitationServiceClient
import io.github.brrenat.seekervault.gateway.v1.InvitationStatus
import io.github.brrenat.seekervault.gateway.v1.deviceServiceGetServerManifestRequest
import io.github.brrenat.seekervault.gateway.v1.deviceServiceListRequestsRequest
import io.github.brrenat.seekervault.gateway.v1.deviceServiceRevokeConnectionRequest
import io.github.brrenat.seekervault.gateway.v1.deviceServiceSubmitResultRequest
import io.github.brrenat.seekervault.gateway.v1.redeemInvitationRequest
import io.github.brrenat.seekervault.gateway.v1.resolveInvitationRequest
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.server.v1.ServerManifest
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient

enum class InvitationStanding {
    Pending,
    Connected,
    Expired,
}

data class ResolvedInvitation(
    val invitationId: String,
    val serverId: String,
    val displayName: String,
    val expiresAt: Instant,
    val standing: InvitationStanding,
    val manifest: ServerManifest,
)

class RedeemedInvitation(
    val connectionId: String,
    val deviceToken: String,
    val serverId: String,
    val manifest: ServerManifest,
) {
    override fun toString() =
        "RedeemedInvitation(connectionId=$connectionId, deviceToken=<redacted>, serverId=$serverId)"
}

data class GatewayPrivatePage(
    val requests: List<Request>,
    val nextPageToken: String,
    val sequence: Long,
    val unchanged: Boolean,
)

interface InvitationGateway {
    suspend fun resolve(reference: InvitationReference): ResolvedInvitation

    suspend fun redeem(reference: InvitationReference, deviceName: String): RedeemedInvitation

    suspend fun serverManifest(
        gatewayUrl: String,
        credential: String,
        connectionId: String,
        knownRevision: Long,
    ): ServerManifest?

    suspend fun listRequests(
        gatewayUrl: String,
        credential: String,
        connectionId: String,
        pageToken: String = "",
        knownSequence: Long = 0,
    ): GatewayPrivatePage

    suspend fun submitResult(
        gatewayUrl: String,
        credential: String,
        connectionId: String,
        result: DeviceResult,
    )

    suspend fun revoke(gatewayUrl: String, credential: String, connectionId: String)
}

class ConnectInvitationGateway(private val httpClient: OkHttpClient) : InvitationGateway {
    override suspend fun resolve(reference: InvitationReference): ResolvedInvitation {
        val response = call {
            InvitationServiceClient(protocolClient(reference.gatewayUrl))
                .resolveInvitation(resolveInvitationRequest { token = reference.token })
        }
        if (
            !response.hasManifest() ||
                response.invitationId.isEmpty() ||
                !isConnectionId(response.serverId)
        )
            throw GatewayException(GatewayException.Kind.BadResponse, "unusable invitation")
        return ResolvedInvitation(
            invitationId = response.invitationId,
            serverId = response.serverId,
            displayName = response.displayName,
            expiresAt =
                Instant.ofEpochSecond(
                    response.expiresAt.seconds,
                    response.expiresAt.nanos.toLong(),
                ),
            standing = standing(response.status),
            manifest = response.manifest,
        )
    }

    override suspend fun redeem(
        reference: InvitationReference,
        deviceName: String,
    ): RedeemedInvitation {
        val response = call {
            InvitationServiceClient(protocolClient(reference.gatewayUrl))
                .redeemInvitation(
                    redeemInvitationRequest {
                        token = reference.token
                        this.deviceName = deviceName
                    }
                )
        }
        return RedeemedInvitation(
            response.connectionId,
            response.deviceToken,
            response.serverId,
            response.manifest,
        )
    }

    override suspend fun serverManifest(
        gatewayUrl: String,
        credential: String,
        connectionId: String,
        knownRevision: Long,
    ): ServerManifest? {
        val response = call {
            DeviceServiceClient(protocolClient(gatewayUrl))
                .getServerManifest(
                    deviceServiceGetServerManifestRequest {
                        this.connectionId = connectionId
                        knownSettingsRevision = knownRevision
                    },
                    bearer(credential),
                )
        }
        return if (response.unchanged) null
        else
            response.manifest.takeIf { response.hasManifest() }
                ?: throw GatewayException(GatewayException.Kind.BadResponse, "manifest missing")
    }

    override suspend fun listRequests(
        gatewayUrl: String,
        credential: String,
        connectionId: String,
        pageToken: String,
        knownSequence: Long,
    ): GatewayPrivatePage {
        val response = call {
            DeviceServiceClient(protocolClient(gatewayUrl))
                .listRequests(
                    deviceServiceListRequestsRequest {
                        this.connectionId = connectionId
                        pageSize = 100
                        this.pageToken = pageToken
                        this.knownSequence = knownSequence
                    },
                    bearer(credential),
                )
        }
        return GatewayPrivatePage(
            response.requestsList,
            response.nextPageToken,
            response.sequence,
            response.unchanged,
        )
    }

    override suspend fun submitResult(
        gatewayUrl: String,
        credential: String,
        connectionId: String,
        result: DeviceResult,
    ) {
        call {
            DeviceServiceClient(protocolClient(gatewayUrl))
                .submitResult(
                    deviceServiceSubmitResultRequest {
                        this.connectionId = connectionId
                        this.result = result
                    },
                    bearer(credential),
                )
        }
    }

    override suspend fun revoke(gatewayUrl: String, credential: String, connectionId: String) {
        call {
            DeviceServiceClient(protocolClient(gatewayUrl))
                .revokeConnection(
                    deviceServiceRevokeConnectionRequest { this.connectionId = connectionId },
                    bearer(credential),
                )
        }
    }

    private fun protocolClient(gatewayUrl: String) =
        ProtocolClient(
            httpClient = ConnectOkHttpClient(httpClient),
            config =
                ProtocolClientConfig(
                    host = gatewayUrl,
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
                throw classifyInvitationCall(e)
            }
        return when (response) {
            is ResponseMessage.Success -> response.message
            is ResponseMessage.Failure -> throw classifyInvitationCall(response.cause)
        }
    }

    private fun standing(status: InvitationStatus): InvitationStanding =
        when (status) {
            InvitationStatus.INVITATION_STATUS_PENDING -> InvitationStanding.Pending
            InvitationStatus.INVITATION_STATUS_CONNECTED -> InvitationStanding.Connected
            InvitationStatus.INVITATION_STATUS_EXPIRED -> InvitationStanding.Expired
            else ->
                throw GatewayException(
                    GatewayException.Kind.BadResponse,
                    "invitation status missing",
                )
        }
}

private fun classifyInvitationCall(error: Throwable): GatewayException {
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    val code = causes.filterIsInstance<ConnectException>().firstOrNull()?.code
    val kind =
        when {
            code == Code.UNAUTHENTICATED -> GatewayException.Kind.Unauthenticated
            code == Code.INVALID_ARGUMENT -> GatewayException.Kind.Rejected
            code == Code.NOT_FOUND -> GatewayException.Kind.NotFound
            code == Code.FAILED_PRECONDITION -> GatewayException.Kind.InvalidState
            code == Code.UNAVAILABLE || causes.any { it is IOException } ->
                GatewayException.Kind.Unreachable
            else -> GatewayException.Kind.Other
        }
    return GatewayException(kind, error.message, error)
}
