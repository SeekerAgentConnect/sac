package io.github.brrenat.seekervault.sync

import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse

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
