package io.github.brrenat.seekervault.feeds

import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.FeedRefresh
import io.github.brrenat.seekervault.connections.ProposalRepository
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest

/**
 * What a listener needs from the phone's own state, and nothing more (SEE-91).
 *
 * It exists so that the session above it can be tested without a repository, a file or a socket —
 * the same reason `SynchronizationHost` exists for the direct path. Every method here is the app's
 * existing path for the same document: a streamed proposal goes through the idempotent,
 * revision-ordered apply that a snapshot's proposals go through (SEE-89), and streamed settings go
 * through the validator that a read manifest goes through (SEE-88). **A document is not trusted
 * more for having arrived quickly.**
 */
interface FeedHost {
    /** Applies one published proposal. */
    suspend fun applyProposal(connectionId: String, message: WireProposal)

    /** Applies a publisher's settings. */
    suspend fun applySettings(connectionId: String, message: WireManifest)

    /**
     * Reads the authoritative snapshot — settings, then the whole current feed — and returns the
     * sequence the walk was taken at, or null when it could not be read.
     *
     * This is what runs whenever continuity cannot be proven, which is the whole reason the
     * gateway's unary API exists next to the stream: a broker's history is a recovery cache, and
     * the documents are the gateway's (docs/wiki/broadcast-gateway.md).
     */
    suspend fun readFeed(connectionId: String, knownSequence: Long): Long?
}

/**
 * [FeedHost] over the repositories that already hold all of this.
 *
 * It is four lines of delegation on purpose. Anything cleverer here would be a second place where
 * the rules about applying a document live.
 */
class RepositoryFeedHost(
    private val connections: ConnectionRepository,
    private val proposals: ProposalRepository,
) : FeedHost {
    override suspend fun applyProposal(connectionId: String, message: WireProposal) {
        proposals.apply(connectionId, message)
    }

    override suspend fun applySettings(connectionId: String, message: WireManifest) {
        connections.applySettings(connectionId, message)
    }

    override suspend fun readFeed(connectionId: String, knownSequence: Long): Long? {
        // Settings first: a proposal names the plugin and contract its publisher's manifest
        // describes, so reading them in this order means the feed is judged against the settings it
        // was published under rather than against the ones from before.
        connections.refreshSettings(connectionId)
        return when (val refresh = proposals.refresh(connectionId, knownSequence)) {
            is FeedRefresh.Read -> refresh.sequence
            is FeedRefresh.Unchanged -> refresh.sequence
            else -> null
        }
    }
}
