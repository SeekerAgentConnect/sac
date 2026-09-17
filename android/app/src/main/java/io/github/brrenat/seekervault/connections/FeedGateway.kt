package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.server.v1.ServerManifest
import io.github.brrenat.seekervault.servers.FeedReference

/**
 * The one way a publisher's manifest is resolved: through the shared gateway (SEE-88).
 *
 * The publisher's own server is never contacted. That is the point of the mode rather than a detail
 * of it — a broadcast publisher must not be able to see which phones are interested in it, and the
 * phone must not depend on a developer's server being reachable to know what its feed needs. A
 * publisher registers its configuration with the gateway; the phone reads it from there.
 *
 * This build carries no implementation. The gateway that answers this is SEE-90, and until it
 * exists the app says so rather than pretending a feed resolved (`ConnectionRepository.addFeed`,
 * [FeedOutcome.NoGateway]).
 */
interface FeedGateway {
    /**
     * The manifest the gateway holds for [reference]'s server, unvalidated: what it says is checked
     * against the reference by [io.github.brrenat.seekervault.servers.manifestFrom] before anything
     * is stored, so a gateway cannot hand the phone a manifest for a different server or channel
     * than the one it asked about.
     *
     * Throws [GatewayException] if the gateway refused, couldn't be reached, or answered with
     * something unusable.
     */
    suspend fun resolve(reference: FeedReference): ServerManifest
}
