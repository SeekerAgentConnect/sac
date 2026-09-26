package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.sync.UpdateEndpoint
import io.github.brrenat.seekervault.sync.UpdateTransport
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse

/** Keeps existing UI tests on their intentional fake RequestService-only sidecar. */
class LegacyUpdateTransport : UpdateTransport {
    override suspend fun discover(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): UpdateEndpoint? = null

    override suspend fun sync(
        endpoint: UpdateEndpoint,
        credential: String,
        request: SyncRequest,
    ): SyncResponse = error("an unconfigured sidecar cannot be synchronized")
}
