package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest

/**
 * The phone's calls to a sidecar's `PairingService` and `RequestService` (docs/protocol.md),
 * independent of the network stack. Every call names the URL it goes to, and its secret goes only
 * there.
 */
interface ConnectionGateway {
    /** Exchanges [code]'s pairing token, at [code]'s URL, for a new connection. */
    suspend fun pair(code: PairingCode, deviceName: String): PairedConnection

    /** One page, up to 100, of the connection's PENDING requests, from [pageToken] on. */
    suspend fun listPending(
        serverUrl: String,
        credential: String,
        connectionId: String,
        pageToken: String = "",
    ): PendingRequests

    /**
     * Sends a result for one of the connection's requests, and returns the request as it is
     * afterwards. Repeating an accepted result changes nothing and returns the request again.
     */
    suspend fun submitResult(
        serverUrl: String,
        credential: String,
        submission: SubmitResultRequest,
    ): ActionRequest

    /** Ends the connection at its sidecar: the credential stops working there at once. */
    suspend fun revoke(serverUrl: String, credential: String, connectionId: String)
}

/** A `PairResponse`, before the repository has checked it. */
class PairedConnection(val connectionId: String, val credential: String, val serverId: String) {
    override fun toString() =
        "PairedConnection(connectionId=$connectionId, credential=<redacted>, serverId=$serverId)"
}

/** A page of PENDING requests. [nextPageToken] is empty on the last page. */
data class PendingRequests(val requests: List<ActionRequest>, val nextPageToken: String = "")

/** A failed call, classified by what the app tells the owner. */
class GatewayException(
    val kind: Kind,
    message: String?,
    cause: Throwable? = null,
    /** For [Kind.InvalidState]: the request as it is now, from the error's `RequestErrorDetail`. */
    val request: ActionRequest? = null,
) : Exception(message, cause) {
    enum class Kind {
        /**
         * `unauthenticated`: a pairing token that's unknown, expired, or used, or a credential the
         * sidecar no longer accepts.
         */
        Unauthenticated,
        /** `invalid_argument`: for `Pair`, the code was issued for another URL. */
        Rejected,
        /** `not_found`: for example, a connection or request the sidecar doesn't know. */
        NotFound,
        /** `failed_precondition`: the request has moved on, for example the agent cancelled it. */
        InvalidState,
        /** TLS failed: the certificate isn't trusted, or it's for another host name. */
        CertificateRejected,
        /** Android's network security policy blocked plain HTTP to this host. */
        CleartextBlocked,
        /** The sidecar couldn't be reached (`unavailable`, network errors). */
        Unreachable,
        /** The sidecar answered with something the app can't use. */
        BadResponse,
        Other,
    }
}

/** What a failed refresh, answer, or revocation tells the owner about the connection. */
fun GatewayException.Kind.toOutcome(): CheckOutcome =
    when (this) {
        GatewayException.Kind.Unreachable -> CheckOutcome.Unreachable
        GatewayException.Kind.CertificateRejected -> CheckOutcome.CertificateRejected
        GatewayException.Kind.CleartextBlocked -> CheckOutcome.CleartextBlocked
        else -> CheckOutcome.Failed
    }
