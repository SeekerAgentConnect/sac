package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.proposal.v1.Proposal
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.servers.FeedReference

/**
 * The one way a publisher's proposals reach this phone: through the shared gateway (SEE-89).
 *
 * The publisher itself is never contacted, and nothing about this phone goes the other way. A
 * subscription says which channel it is interested in and that is the whole of what the gateway
 * learns; the owner's wallet, the parameters they chose, whether they went ahead and what came of
 * it never leave the device (docs/wiki/shared-proposals.md).
 *
 * The implementation is `feeds.ConnectFeedGateway` (SEE-91); the live stream that pushes the same
 * documents is `feeds.ForegroundFeedManager`, and it applies them through the same path this does.
 * A build wired without a gateway says so rather than pretending a feed was read
 * ([ProposalRepository.refresh], [FeedRefresh.NoFeed]).
 *
 * The seam is a snapshot even though there is also a stream, because the snapshot is the
 * authoritative one: it is what a phone reads when it cannot prove it missed nothing. Both deliver
 * the same documents through the same idempotent apply path ([ProposalRepository.apply]) — a
 * replayed event, a duplicate push and a reloaded snapshot are all the same document arriving
 * again, so the path is what makes them harmless rather than the transport being careful.
 */
interface ProposalFeed {
    /**
     * The proposals the gateway currently holds for [reference]'s channel, unvalidated: what it
     * hands back is checked against the feed it came from ([proposalFrom]) before anything is
     * stored, so a gateway cannot put one publisher's proposal into another publisher's feed.
     *
     * [knownSequence] is the channel sequence this phone last completed a walk at, or zero. A
     * channel that has not moved since answers [FeedSnapshot.Unchanged] with no documents.
     *
     * The walk is paged behind this call and the pages are joined here, so what comes back is one
     * list and one boundary. That boundary is documented rather than transactional: the sequence is
     * the channel's count of accepted publications when the walk began, and a completed walk holds
     * every proposal that existed then and still exists at the end, plus any published during it.
     * It converges because every document carries its own revision and a lower one never wins
     * (SEE-89, docs/wiki/broadcast-gateway.md#the-snapshot-boundary).
     *
     * Throws [GatewayException] if the gateway refused, couldn't be reached, or answered with
     * something unusable.
     */
    suspend fun snapshot(reference: FeedReference, knownSequence: Long = 0L): FeedSnapshot
}

/** What a gateway said about a channel's current proposals. */
sealed interface FeedSnapshot {
    /** The proposals, and the sequence the walk began at. */
    data class Read(
        val sequence: Long,
        val proposals: List<Proposal> = emptyList(),
        val requests: List<Request> = emptyList(),
    ) : FeedSnapshot

    /** The sequence asked with is the current one, so no documents were sent. */
    data class Unchanged(val sequence: Long) : FeedSnapshot
}
