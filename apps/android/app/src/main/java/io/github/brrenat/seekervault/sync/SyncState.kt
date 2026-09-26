package io.github.brrenat.seekervault.sync

import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.update.v1.RemovalReason
import java.time.Instant

/** Whether a paired sidecar can serve the Stage 5.2 update protocol. */
enum class UpdateAvailability {
    /** Capability discovery has not completed successfully yet. */
    Unknown,
    Available,
    /** A current sidecar is running without an update listener configured. */
    NotConfigured,
    /** The sidecar predates capability discovery and must be upgraded for live updates. */
    UpgradeRequired,
    /** The sidecar advertised an endpoint this app cannot safely or correctly use. */
    Incompatible,
}

/** A validated update origin. It contains no credential; the phone token stays in its vault. */
data class UpdateEndpoint(val protocolVersion: Int, val grpcUrl: String)

/** One revisioned server record in the phone's minimal update cache. */
data class ServerRequest(
    val key: RequestKey,
    val revision: Long,
    /** Null after the server explicitly removed the request. */
    val request: ActionRequest? = null,
    val removed: RemovalReason? = null,
) {
    init {
        require(revision > 0) { "a server revision must be positive" }
        require((request == null) != (removed == null)) { "request or removal required" }
        require(request == null || request.ref.connectionId == key.connectionId)
        require(request == null || request.ref.requestId == key.requestId)
    }

    val state: RequestState?
        get() = request?.state
}

/** Durable state for one connection, as a single atomic document. */
data class ConnectionSyncState(
    val connectionId: String,
    val availability: UpdateAvailability = UpdateAvailability.Unknown,
    val endpoint: UpdateEndpoint? = null,
    val serverInstanceId: String = "",
    val cursor: String = "",
    val lastSuccessfulSync: Instant? = null,
    /** Round-robin position for Activity histories larger than one protocol page. */
    val nextKnownIndex: Int = 0,
    val requests: Map<String, ServerRequest> = emptyMap(),
    /** Runtime-only attempt state. It is deliberately not written to disk. */
    val syncing: Boolean = false,
    val failure: CheckOutcome? = null,
    /** True after an ordering conflict; only a complete unary snapshot clears it. */
    val fullSyncRequired: Boolean = cursor.isEmpty(),
) {
    val pending: List<ActionRequest>
        get() =
            requests.values
                .mapNotNull(ServerRequest::request)
                .filter { it.state == RequestState.REQUEST_STATE_PENDING }
                .sortedWith(
                    compareBy(
                        { it.createdAt.seconds },
                        { it.createdAt.nanos },
                        { it.ref.requestId },
                    )
                )
}

/** The one application-scoped observable state used by every sync caller and later UI consumer. */
data class SynchronizationState(
    val loaded: Boolean = false,
    val connections: Map<String, ConnectionSyncState> = emptyMap(),
)

sealed interface SynchronizeOutcome {
    data class Updated(val state: ConnectionSyncState) : SynchronizeOutcome

    /** Manual Refresh can keep using RequestService for this connection. */
    data class Legacy(val availability: UpdateAvailability) : SynchronizeOutcome

    data class Failed(val failure: CheckOutcome) : SynchronizeOutcome

    data object Removed : SynchronizeOutcome
}

/** What applying a stream event did. SEE-70 supplies the lifecycle that calls these methods. */
enum class EventApplyOutcome {
    Applied,
    Ignored,
    Buffered,
    FullSyncRequired,
    Removed,
}
