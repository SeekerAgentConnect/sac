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
 * The implementation is `feeds.ConnectFeedGateway` (SEE-91), which reads the gateway SEE-90 built.
 * A build wired without one says so rather than pretending a feed resolved
 * (`ConnectionRepository.addFeed`, [FeedOutcome.NoGateway]).
 */
interface FeedGateway {
    /**
     * The manifest the gateway holds for [reference]'s server, unvalidated: what it says is checked
     * against the reference by [io.github.brrenat.seekervault.servers.manifestFrom] before anything
     * is stored, so a gateway cannot hand the phone a manifest for a different server or channel
     * than the one it asked about.
     *
     * [knownRevision] is the settings revision this phone already holds, or zero when it holds
     * none. A gateway that has nothing newer answers [FeedManifest.Unchanged] and sends no
     * document, which is one small round trip instead of a manifest on someone's mobile data. The
     * revision travels in the request rather than in a caching header on purpose: it is the
     * contract's own number, and nothing between the phone and the gateway gets to decide what the
     * phone believes about it (SEE-88).
     *
     * Throws [GatewayException] if the gateway refused, couldn't be reached, or answered with
     * something unusable.
     */
    suspend fun resolve(reference: FeedReference, knownRevision: Long = 0L): FeedManifest
}

/** What a gateway said about a publisher's settings. */
sealed interface FeedManifest {
    /** The manifest, as the gateway holds it and before this phone has checked any of it. */
    data class Held(val manifest: ServerManifest) : FeedManifest

    /** The revision asked with is the current one, so nothing was sent and what is held stands. */
    data class Unchanged(val settingsRevision: Long) : FeedManifest
}
