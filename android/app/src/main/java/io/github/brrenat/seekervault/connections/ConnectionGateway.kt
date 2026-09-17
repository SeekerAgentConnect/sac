package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.server.v1.ServerManifest

/**
 * The phone's calls to a sidecar's `PairingService` and `RequestService` (docs/protocol.md),
 * independent of the network stack. Every call names the URL it goes to, and its secret goes only
 * there.
 */
interface ConnectionGateway {
    /** Exchanges [code]'s pairing token, at [code]'s URL, for a new connection. */
    suspend fun pair(code: PairingCode, deviceName: String): PairedConnection

    /**
     * What the connection's own server says about itself, unvalidated (SEE-88), or **null** when
     * the server doesn't know this call at all.
     *
     * Null is the legacy-direct path and not a failure: a sidecar from before Stage 7.1 answers
     * UNIMPLEMENTED, which means it publishes no manifest, and the phone keeps calling it exactly
     * as it always has (docs/wiki/server-manifests.md#legacy-direct). Everything the manifest says
     * is checked against what the phone already trusts about the connection before any of it is
     * stored.
     */
    suspend fun serverManifest(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): ServerManifest?

    /** One page, up to 100, of the connection's PENDING requests, from [pageToken] on. */
    suspend fun listPending(
        serverUrl: String,
        credential: String,
        connectionId: String,
        pageToken: String = "",
    ): PendingRequests

    /**
     * Asks the sidecar to build a fresh unsigned transaction for one of the connection's PENDING
     * transfers, and returns it. Each call is a new version, and the phone checks the bytes itself
     * before the owner sees anything (docs/security.md#inspecting-a-transfer).
     */
    suspend fun prepareRequest(
        serverUrl: String,
        credential: String,
        key: RequestKey,
    ): PreparedTransaction

    /**
     * Sends a result for one of the connection's requests, and returns the request as it is
     * afterwards. Repeating an accepted result changes nothing and returns the request again.
     */
    suspend fun submitResult(
        serverUrl: String,
        credential: String,
        submission: SubmitResultRequest,
    ): ActionRequest

    /**
     * Asks the sidecar what became of a transfer the wallet sent, and returns the request as it is
     * afterwards. The sidecar reads the chain; this phone signs nothing and sends nothing, and no
     * wallet is opened (docs/protocol.md#confirmation).
     */
    suspend fun checkStatus(
        serverUrl: String,
        credential: String,
        key: RequestKey,
    ): ActionRequest

    /**
     * Tells the connection's sidecar which wallet the owner selected, or, with a null [binding],
     * that none is connected. Returns the request IDs the sidecar cancelled because the new binding
     * no longer fits them.
     */
    suspend fun publishWallet(
        serverUrl: String,
        credential: String,
        connectionId: String,
        binding: WalletBinding?,
    ): List<String>

    /** Registers, rotates, or compare-clears this connection's private FCM direct-send target. */
    suspend fun setFcmToken(
        serverUrl: String,
        credential: String,
        connectionId: String,
        update: FcmTokenUpdate,
    )

    /** Ends the connection at its sidecar: the credential stops working there at once. */
    suspend fun revoke(serverUrl: String, credential: String, connectionId: String)
}

/** An FCM target update whose diagnostic representation can never disclose the opaque value. */
sealed class FcmTokenUpdate(val target: String) {
    class Register(target: String) : FcmTokenUpdate(target) {
        override fun toString() = "FcmTokenUpdate.Register(<redacted>)"
    }

    class ClearIfCurrent(target: String) : FcmTokenUpdate(target) {
        override fun toString() = "FcmTokenUpdate.ClearIfCurrent(<redacted>)"
    }
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
        /**
         * `failed_precondition` with STALE_PREPARATION: the approval doesn't name the request's
         * latest prepared transaction, or that transaction's blockhash has run out. Nothing was
         * approved. The phone prepares the request again and has the owner review the new version
         * (docs/architecture.md#approval-binding).
         */
        StalePreparation,
        /** TLS failed: the certificate isn't trusted, or it's for another host name. */
        CertificateRejected,
        /** Android's network security policy blocked plain HTTP to this host. */
        CleartextBlocked,
        /** The sidecar couldn't be reached (`unavailable`, network errors). */
        Unreachable,
        /**
         * `unimplemented`: the server doesn't know this call at all, which is how one from before
         * the call existed says so. For `GetServerManifest` that is the legacy-direct path (SEE-88)
         * rather than an error the owner needs to see.
         */
        Unimplemented,
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
