package io.github.brrenat.seekervault.feeds

import io.github.brrenat.seekervault.gateway.v1.FeedEvent
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * Listening to a feed, independent of what is carrying it (SEE-91).
 *
 * This file is the whole of what the app knows about the stream. The broker's own protocol types
 * live behind [CentrifugoFeedStream] — the one file allowed to import them — so everything else
 * deals in the events below: a subscription opened, a document published, a channel dropped, the
 * stream closed. `FeedBoundaryTest` fails if a second file imports the broker's schema.
 *
 * Two facts about the transport shape everything here, and both were established by running the
 * pinned release rather than assumed:
 *
 * 1. **A listener cannot ask for anything.** The stream is unidirectional: after it opens, the
 *    phone sends nothing. It cannot subscribe to a channel, ask for history, or refresh its
 *    credential. So the channels come from the ticket the gateway minted ([FeedGrant]), and adding
 *    or removing a feed means opening a new stream rather than changing this one.
 * 2. **Recovery happens once, at the moment it opens.** The cursors a listener holds go in the
 *    request, and the answer says — per channel — whether the broker could replay what was missed.
 *    That answer is the only continuity this transport offers; there is no way to ask again later.
 *    When it says no, the authoritative snapshot over the gateway's unary API is what fills the gap
 *    (docs/wiki/feed-gateway.md#the-stream).
 */
interface FeedStream {
    /**
     * Opens a listener at [gatewayUrl] with [ticket], resuming each channel from the cursor held
     * for it, and emits what arrives.
     *
     * Cold: collecting opens the stream, and cancelling the collection closes it. A transport
     * failure is thrown as [FeedStreamException]; the broker ending the stream deliberately arrives
     * as [FeedStreamEvent.Closed] first, because its code is what says whether to come back.
     */
    fun listen(
        gatewayUrl: String,
        ticket: String,
        resume: Map<String, FeedCursor>,
    ): Flow<FeedStreamEvent>
}

/**
 * A position in a channel's stream: the broker's own, and never the protocol's.
 *
 * [offset] counts publications the broker holds and [epoch] identifies the history they are in — a
 * new epoch means the history was replaced, so an offset from the old one means nothing. It is not
 * a revision and not a sequence: it says where a listener stopped, and nothing about which document
 * is newer. That is the revision's job, on both sides (SEE-89).
 */
data class FeedCursor(val epoch: String, val offset: Long)

/** A channel this phone knows, and the name the same documents arrive under on the stream. */
data class GrantedChannel(val channel: String, val streamChannel: String)

/**
 * Permission to listen, as the gateway granted it.
 *
 * [channels] holds only the channels the gateway actually serves, so a phone that asked about a
 * feed this gateway no longer hosts finds it missing here and reads that one over unary calls
 * instead of losing the stream for the others.
 */
data class FeedGrant(
    val ticket: String,
    val channels: List<GrantedChannel>,
    val lifetime: Duration,
)

/** What a listener saw. */
sealed interface FeedStreamEvent {
    /**
     * The stream is open, with what the broker said about each channel it subscribed.
     *
     * Any documents it replayed arrive as [Published] events after this one, in order, so applying
     * what was missed and applying what happens next are the same code path.
     */
    data class Opened(val subscriptions: Map<String, FeedSubscription>) : FeedStreamEvent

    /**
     * A document, and the offset it sits at — the cursor to hold once it has been applied.
     *
     * [replayed] marks history the broker sent back when the stream opened, as opposed to something
     * published while it was open. It is applied the same way; it is only not news (SEE-175).
     */
    data class Published(
        val streamChannel: String,
        val offset: Long,
        val event: FeedEvent,
        val replayed: Boolean = false,
    ) : FeedStreamEvent

    /**
     * Something arrived on a channel that this version cannot read: an envelope from a later
     * protocol, or bytes that are not one at all.
     *
     * It is reported rather than skipped. A document this phone cannot parse still means the
     * channel moved, and the honest response is to read the snapshot rather than to carry on as if
     * nothing had happened.
     */
    data class Unreadable(val streamChannel: String, val offset: Long) : FeedStreamEvent

    /**
     * One channel's subscription ended while the stream stayed open — the broker's way of saying
     * its position could not be kept. The subscription cannot be re-opened on this transport, so
     * the listener reconnects and reads that channel's snapshot.
     */
    data class Dropped(val streamChannel: String, val code: Int, val reason: String) :
        FeedStreamEvent

    /**
     * The broker ended the stream, with the code that says whether to come back ([afterClose]). It
     * is not an error: a node shutting down and a ticket expiring both arrive this way, and both
     * are ordinary.
     */
    data class Closed(val code: Int, val reason: String) : FeedStreamEvent

    /**
     * The transport said something that carries no news.
     *
     * The pinned transport does not send periodic pings — a connection that is merely quiet sends
     * nothing at all, which is why liveness is HTTP/2's job here (see [CentrifugoFeedStream]).
     * Nothing depends on this event; it exists so that a frame the protocol may add later is not
     * read as a document.
     */
    data object Alive : FeedStreamEvent
}

/**
 * What the broker said about one channel when the stream opened.
 *
 * [recovered] is the whole question: it means the broker replayed everything this listener missed,
 * so nothing is needed from the snapshot. When it is false, [offset] is where the channel is *now*,
 * which is where to carry on from once the snapshot has been read.
 */
data class FeedSubscription(
    val epoch: String,
    val offset: Long,
    val recoverable: Boolean,
    val recovered: Boolean,
    val wasRecovering: Boolean,
)

/** Why listening failed, in terms that say what to do about it. */
class FeedStreamException(
    val kind: Kind,
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** The ticket was not accepted. A new one is worth trying; the same one is not. */
        Unauthenticated,
        /** There is no stream at this address — an older gateway, or one without a broker. */
        Unsupported,
        /** The gateway's certificate was refused. Never retried silently (SAW-013). */
        CertificateRejected,
        /** Plain HTTP outside loopback, which the app's network policy blocks. */
        CleartextBlocked,
        /** Not reachable now: no network, a broken connection, a node restarting. */
        Unreachable,
        /** Something arrived that is not this protocol. */
        BadResponse,
        Other,
    }
}
