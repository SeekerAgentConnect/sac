package io.github.brrenat.seekervault.notifications

import io.github.brrenat.seekervault.ReviewIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which waiting items reached this phone as news, as opposed to catching up (SEE-175).
 *
 * The inbox is filled by two kinds of delivery and they look the same once they are in it: a whole
 * read of a connection — a feed snapshot, a direct server's synchronization, a legacy fetch, the
 * history a stream replays when it reopens — and a live event on a stream that is already open.
 * Only the second is something that *just happened*. Connecting a feed with fifty proposals, or
 * coming back to an app whose streams were closed, reads fifty documents that were there before the
 * owner looked, and none of them is worth a banner.
 *
 * The repositories know which kind of delivery they are applying, so they say it here, **before**
 * they publish what it brought: an item is marked [live] ahead of the list it appears in, so the
 * foreground banner can never see it arrive unmarked. The banner then announces what is new to it
 * *and* marked live, and nothing else ([InAppNotificationSource]).
 *
 * The rule the repositories follow is one sentence: a stream event is news, and a whole read is
 * news only when this connection has already been read whole in this foreground session
 * ([wasRead]). The first read of a connection is its backlog; the reads after it — a push-triggered
 * read, a feed whose gateway has no stream, a reconnect that could not be recovered — bring what
 * was published since the last one. A live item near the snapshot/live handoff arrives on the
 * stream, or in a later read, or in the snapshot just ahead of its own stream event — the handoff
 * below — and each way it is marked. Nothing here is a timer.
 *
 * A foreground session is the app's own: it starts at [onForeground] and ends at [onBackground],
 * and nothing outside one counts. The repositories are shared with the background workers, so a
 * read that completes while the app is away, or one that started in an earlier session, neither
 * makes the connection read nor marks anything ([session]). Otherwise a worker's read would turn
 * the next foreground catch-up into news.
 *
 * The one place the two kinds of delivery meet is the handoff. A feed's stream is open before its
 * snapshot is read, so something published in between is in the snapshot — stored as catching up —
 * and then arrives again on the stream. The first read marks it quiet ([markQuiet]); when the live
 * event for it follows, [handOver] moves it to [late], and the banner announces it as it would have
 * if the event had won the race. Only an item a catching-up read of this session stored is handed
 * over, and only once, so a replay or a duplicate of anything older is still nothing.
 *
 * Nothing is dropped by it either. An item that is not marked is still stored, listed, counted and
 * unread exactly as before; the ledger only decides whether a banner says so.
 */
class ArrivalLedger(private val capacity: Int = CAPACITY) {
    private val lock = Any()
    private val read = mutableSetOf<String>()
    private val quiet = LinkedHashSet<ReviewIdentity>()
    private val _live = MutableStateFlow<Set<ReviewIdentity>>(emptySet())
    private val _late = MutableStateFlow<Set<ReviewIdentity>>(emptySet())

    /** The current foreground session's number; null while the app is not in the foreground. */
    private var current: Long? = null
    private var sessions = 0L

    /**
     * The items delivered as news, newest last, at most [capacity] of them. A mark only has to
     * outlive the next look the banner takes, so the oldest are let go rather than kept forever.
     */
    val live: StateFlow<Set<ReviewIdentity>> = _live.asStateFlow()

    /**
     * Items a catching-up read stored first and a live event then delivered again ([handOver]).
     * They were news all along, so the banner announces them even though it has already seen them.
     */
    val late: StateFlow<Set<ReviewIdentity>> = _late.asStateFlow()

    /**
     * The foreground session this is, or null while the app is away. A read that spans some time
     * takes it when it starts and hands it back with what it marks, so what it finds is not
     * credited to a session it did not belong to.
     */
    fun session(): Long? = synchronized(lock) { current }

    /** Whether a whole read of [connectionId] has completed in this foreground session. */
    fun wasRead(connectionId: String): Boolean =
        synchronized(lock) { current != null && connectionId in read }

    /**
     * A whole read of [connectionId], started in [session], has completed; the next one brings
     * news. A read that completes outside the session it started in says nothing.
     */
    fun markRead(connectionId: String, session: Long? = session()) {
        synchronized(lock) { if (session != null && session == current) read += connectionId }
    }

    /** These items reached the phone as news. Call it before publishing them. */
    fun markLive(identities: Collection<ReviewIdentity>, session: Long? = session()) {
        if (identities.isEmpty()) return
        synchronized(lock) {
            if (session == null || session != current) return
            _live.value = bounded(_live.value, identities)
        }
    }

    /**
     * These items were stored by a read that was catching up, in [session]. Nothing is said about
     * them now; a live event for one of them later in the session is the handoff ([handOver]).
     */
    fun markQuiet(identities: Collection<ReviewIdentity>, session: Long? = session()) {
        if (identities.isEmpty()) return
        synchronized(lock) {
            if (session == null || session != current) return
            identities.forEach { identity ->
                quiet.remove(identity)
                quiet.add(identity)
            }
            while (quiet.size > capacity) quiet.remove(quiet.first())
        }
    }

    /**
     * A live event delivered [identity] again, identical to what is held. When a catching-up read
     * of this session stored it, the event was the item arriving while that read ran, and it is
     * marked [late] — once. Anything else is a duplicate and nothing happens.
     */
    fun handOver(identity: ReviewIdentity) {
        synchronized(lock) {
            if (current == null || !quiet.remove(identity)) return
            _late.value = bounded(_late.value, listOf(identity))
        }
    }

    /**
     * The app has come to the foreground. A new session starts with nothing read and nothing
     * marked, whatever the workers did while it was away. Coming back from a rotation is the same
     * session: it never left.
     */
    fun onForeground() {
        synchronized(lock) {
            if (current != null) return
            current = ++sessions
            forget()
        }
    }

    /**
     * The app has left the foreground. What is read on the way back is catching up again, and marks
     * from this session mean nothing to the next one, whose first look is its own baseline.
     */
    fun onBackground() {
        synchronized(lock) {
            current = null
            forget()
        }
    }

    private fun forget() {
        read.clear()
        quiet.clear()
        _live.value = emptySet()
        _late.value = emptySet()
    }

    private fun bounded(
        held: Set<ReviewIdentity>,
        identities: Collection<ReviewIdentity>,
    ): Set<ReviewIdentity> {
        val next = LinkedHashSet(held)
        identities.forEach { identity ->
            // Re-marking moves an item to the newest end, so it is the last to be let go.
            next.remove(identity)
            next.add(identity)
        }
        while (next.size > capacity) next.remove(next.first())
        return next
    }

    companion object {
        /** More than a banner could ever count, and small enough to hold for a whole session. */
        const val CAPACITY = 512
    }
}
