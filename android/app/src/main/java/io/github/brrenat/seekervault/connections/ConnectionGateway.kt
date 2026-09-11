package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.request.v1.ActionRequest

/**
 * The phone's calls to a sidecar's `PairingService` and `RequestService` (docs/protocol.md),
 * independent of the network stack. Every call names the URL it goes to, and its secret goes only
 * there.
 */
interface ConnectionGateway {
    /** Exchanges [code]'s pairing token, at [code]'s URL, for a new connection. */
    suspend fun pair(code: PairingCode, deviceName: String): PairedConnection

    /** The first page (up to 100) of the connection's PENDING requests. */
    suspend fun listPending(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): PendingRequests

    /** Ends the connection at its sidecar: the credential stops working there at once. */
    suspend fun revoke(serverUrl: String, credential: String, connectionId: String)
}

/** A `PairResponse`, before the repository has checked it. */
class PairedConnection(val connectionId: String, val credential: String, val serverId: String) {
    override fun toString() =
        "PairedConnection(connectionId=$connectionId, credential=<redacted>, serverId=$serverId)"
}

data class PendingRequests(val requests: List<ActionRequest>, val more: Boolean)

/** A failed call, classified by what the app tells the owner. */
class GatewayException(val kind: Kind, message: String?, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Kind {
        /**
         * `unauthenticated`: a pairing token that's unknown, expired, or used, or a credential the
         * sidecar no longer accepts.
         */
        Unauthenticated,
        /** `invalid_argument`: for `Pair`, the code was issued for another URL. */
        Rejected,
        /** `not_found`: for example, a connection the sidecar doesn't know. */
        NotFound,
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

/** What a failed refresh or revocation tells the owner about the connection. */
fun GatewayException.Kind.toOutcome(): CheckOutcome =
    when (this) {
        GatewayException.Kind.Unreachable -> CheckOutcome.Unreachable
        GatewayException.Kind.CertificateRejected -> CheckOutcome.CertificateRejected
        GatewayException.Kind.CleartextBlocked -> CheckOutcome.CleartextBlocked
        else -> CheckOutcome.Failed
    }
