package io.github.brrenat.seekervault.sync

import io.github.brrenat.seekervault.update.v1.SubscribeResponse
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import java.time.Instant
import kotlinx.coroutines.channels.ReceiveChannel

/** One open HTTP/2 update subscription. Closing it cancels both sides of the RPC. */
interface UpdateSubscription {
    val responses: ReceiveChannel<SubscribeResponse>

    /** Keeps the client send side open after Subscribe; this must never half-close the stream. */
    suspend fun heartbeat(sequence: Long, appliedCursor: String, sentAt: Instant)

    suspend fun close()
}

/** Network calls needed by synchronization, independent of Activities, ViewModels, and workers. */
interface UpdateTransport {
    /**
     * Discovers an existing connection's endpoint; null means a current but unconfigured sidecar.
     */
    suspend fun discover(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): UpdateEndpoint?

    /** Reads one frozen snapshot page over gRPC/HTTP2. */
    suspend fun sync(
        endpoint: UpdateEndpoint,
        credential: String,
        request: SyncRequest,
    ): SyncResponse

    /** Opens a genuine bidirectional gRPC stream and sends its first Subscribe message. */
    suspend fun subscribe(
        endpoint: UpdateEndpoint,
        credential: String,
        connectionId: String,
        resumeCursor: String,
        serverInstanceId: String,
    ): UpdateSubscription =
        throw UpdateTransportException(
            UpdateTransportException.Kind.UpgradeRequired,
            "update subscriptions are unavailable",
        )
}

class UpdateTransportException(
    val kind: Kind,
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        Unauthenticated,
        UpgradeRequired,
        SnapshotInvalid,
        CertificateRejected,
        CleartextBlocked,
        Unreachable,
        BadResponse,
        Other,
    }
}
