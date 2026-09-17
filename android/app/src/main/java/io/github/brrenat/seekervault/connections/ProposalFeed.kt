package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.proposal.v1.Proposal
import io.github.brrenat.seekervault.servers.FeedReference

/**
 * The one way a publisher's proposals reach this phone: through the shared gateway (SEE-89).
 *
 * The publisher itself is never contacted, and nothing about this phone goes the other way. A
 * subscription says which channel it is interested in and that is the whole of what the gateway
 * learns; the owner's wallet, the parameters they chose, whether they went ahead and what came of
 * it never leave the device (docs/wiki/shared-proposals.md).
 *
 * This build carries no implementation. The gateway that answers this is SEE-90 and the live stream
 * that pushes the same documents is SEE-91, and until they exist the app says so rather than
 * pretending a feed was read ([ProposalRepository.refresh], [FeedRefresh.NoFeed]).
 *
 * The seam is a snapshot on purpose, even though the production transport will be a stream. Both
 * deliver the same documents, and both go through the same idempotent apply path
 * ([ProposalRepository.apply]): a replayed event, a duplicate push and a reloaded snapshot are all
 * the same document arriving again, so the path is what makes them harmless rather than the
 * transport being careful.
 */
interface ProposalFeed {
    /**
     * The proposals the gateway currently holds for [reference]'s channel, unvalidated: what it
     * hands back is checked against the feed it came from ([proposalFrom]) before anything is
     * stored, so a gateway cannot put one publisher's proposal into another publisher's feed.
     *
     * Throws [GatewayException] if the gateway refused, couldn't be reached, or answered with
     * something unusable.
     */
    suspend fun proposals(reference: FeedReference): List<Proposal>
}
