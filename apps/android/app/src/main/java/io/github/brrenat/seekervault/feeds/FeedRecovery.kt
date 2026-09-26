package io.github.brrenat.seekervault.feeds

/**
 * The decisions a listener makes, as functions of what it was told (SEE-91).
 *
 * They are here, without a socket or a clock anywhere near them, because they are the part that is
 * easy to get quietly wrong: whether a reconnect is worth making, whether the same ticket is worth
 * reusing, and — the one that decides whether a phone can miss a publication — whether the broker
 * proved it replayed everything.
 */

/** What to do when a stream ends. */
sealed interface AfterClose {
    /**
     * Open another one. [reticket] says whether the ticket has to be replaced first: the broker
     * distinguishes "come back" from "come back with a fresh grant", and reusing an expired one
     * would just be refused.
     */
    data class Reconnect(val reticket: Boolean) : AfterClose

    /**
     * Do not come back as we are. Something about this listener is wrong rather than late — too
     * many channels for the broker's limit, a protocol it refused — and retrying it in a loop would
     * be a phone talking to itself.
     */
    data class Stop(val code: Int) : AfterClose
}

/**
 * What the broker's disconnect code means, by the documented ranges rather than by the `reconnect`
 * field beside it.
 *
 * That field is not usable: a graceful shutdown (`3001`) arrives with `reconnect: false`, and a
 * node restarting is the most ordinary reason to come straight back. The codes are what carry the
 * advice — `3500` and above are terminal, everything below is transient — and two of the transient
 * ones say the grant or the state behind it is stale, so they need a new ticket rather than another
 * try with the old one.
 */
fun afterClose(code: Int): AfterClose =
    when (code) {
        // The connection's token expired, or the broker invalidated what it knew about this
        // connection. Both mean: come back, with a grant minted now.
        CONNECTION_EXPIRED,
        STATE_INVALIDATED -> AfterClose.Reconnect(reticket = true)
        // A grant the broker would not read at all. It is terminal for this ticket, and a fresh
        // one is the only thing worth trying — once, which the caller's attempt count bounds.
        INVALID_TOKEN -> AfterClose.Reconnect(reticket = true)
        in TERMINAL_CODES -> AfterClose.Stop(code)
        else -> AfterClose.Reconnect(reticket = false)
    }

/** Codes the broker documents as terminal: a client receiving one does not reconnect. */
private val TERMINAL_CODES = 3500..3505

private const val INVALID_TOKEN = 3500
private const val CONNECTION_EXPIRED = 3005
private const val STATE_INVALIDATED = 3014

/** Whether a channel's continuity was proven, or has to be read from the authoritative snapshot. */
sealed interface Continuity {
    /**
     * The broker replayed everything this listener missed on that channel. Nothing is needed from
     * the gateway: the events that came with the connect answer are the gap, and they were applied
     * like any others.
     */
    data object Recovered : Continuity

    /** Continuity could not be proven, for this reason. Read the snapshot. */
    data class Snapshot(val why: Why) : Continuity

    enum class Why {
        /** Nothing was held for the channel: a feed just added, or a phone that forgot. */
        NothingHeld,
        /** The broker's history was replaced, so a position in the old one means nothing. */
        EpochChanged,
        /** Further behind than the broker keeps, or than it will replay in one go. */
        TooFarBehind,
        /** The channel is not recoverable at all — a broker configured without history. */
        NotRecoverable,
        /** The stream opened without this channel, so nothing will arrive on it. */
        NotSubscribed,
    }
}

/**
 * What the connect answer means for one channel.
 *
 * The order matters: a missing subscription first, because a channel that was not subscribed will
 * never deliver anything; then a changed epoch, which invalidates the cursor itself; then the
 * broker's own verdict.
 *
 * `offset` is deliberately not compared. The broker echoes the *requested* offset in a successful
 * recovery, so a listener reading its new position from that field would go backwards; the position
 * to keep is the offset of the last document actually applied, and until one arrives the answer's
 * own offset stands (which is why [FeedSubscription.offset] is used only when nothing was
 * recovered).
 */
fun continuity(held: FeedCursor?, subscription: FeedSubscription?): Continuity =
    when {
        subscription == null -> Continuity.Snapshot(Continuity.Why.NotSubscribed)
        held == null -> Continuity.Snapshot(Continuity.Why.NothingHeld)
        !subscription.recoverable -> Continuity.Snapshot(Continuity.Why.NotRecoverable)
        subscription.epoch != held.epoch -> Continuity.Snapshot(Continuity.Why.EpochChanged)
        subscription.recovered -> Continuity.Recovered
        else -> Continuity.Snapshot(Continuity.Why.TooFarBehind)
    }

/**
 * How long to wait before opening another stream, doubling to a ceiling.
 *
 * The same shape as the direct path's ([ForegroundUpdateManager]), and for the same reason: a
 * gateway that is down is down for every phone subscribed to it, so they must not come back in
 * step. [jitter] is injected so a test can make it the identity and the production one can spread
 * the herd.
 */
fun backoff(attempt: Int, jitter: (Long) -> Long): Long {
    val exponent = (attempt - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
    val delay = (BASE_BACKOFF_MILLIS shl exponent).coerceAtMost(MAX_BACKOFF_MILLIS)
    return jitter(delay).coerceIn(0, MAX_BACKOFF_MILLIS)
}

const val BASE_BACKOFF_MILLIS = 1_000L
const val MAX_BACKOFF_MILLIS = 30_000L
const val MAX_BACKOFF_EXPONENT = 5
