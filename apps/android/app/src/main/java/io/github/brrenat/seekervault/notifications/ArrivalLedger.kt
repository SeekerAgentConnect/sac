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
 * was published since the last one. That is what keeps a live item near the snapshot/live handoff:
 * it arrives on the stream, or in a later read, and either way it is marked. Nothing here is a
 * timer.
 *
 * Nothing is dropped by it either. An item that is not marked is still stored, listed, counted and
 * unread exactly as before; the ledger only decides whether a banner says so.
 */
class ArrivalLedger(private val capacity: Int = CAPACITY) {
    private val lock = Any()
    private val read = mutableSetOf<String>()
    private val _live = MutableStateFlow<Set<ReviewIdentity>>(emptySet())

    /**
     * The items delivered as news, newest last, at most [capacity] of them. A mark only has to
     * outlive the next look the banner takes, so the oldest are let go rather than kept forever.
     */
    val live: StateFlow<Set<ReviewIdentity>> = _live.asStateFlow()

    /** Whether a whole read of [connectionId] has completed in this foreground session. */
    fun wasRead(connectionId: String): Boolean = synchronized(lock) { connectionId in read }

    /** A whole read of [connectionId] has completed; the next one brings news. */
    fun markRead(connectionId: String) {
        synchronized(lock) { read += connectionId }
    }

    /** These items reached the phone as news. Call it before publishing them. */
    fun markLive(identities: Collection<ReviewIdentity>) {
        if (identities.isEmpty()) return
        synchronized(lock) {
            val next = LinkedHashSet(_live.value)
            identities.forEach { identity ->
                // Re-marking moves an item to the newest end, so it is the last to be let go.
                next.remove(identity)
                next.add(identity)
            }
            while (next.size > capacity) next.remove(next.first())
            _live.value = next
        }
    }

    /**
     * The app has left the foreground. What is read on the way back is catching up again, and marks
     * from this session mean nothing to the next one, whose first look is its own baseline.
     */
    fun onBackground() {
        synchronized(lock) {
            read.clear()
            _live.value = emptySet()
        }
    }

    companion object {
        /** More than a banner could ever count, and small enough to hold for a whole session. */
        const val CAPACITY = 512
    }
}
