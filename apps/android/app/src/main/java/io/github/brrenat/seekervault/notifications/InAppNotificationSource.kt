package io.github.brrenat.seekervault.notifications

import io.github.brrenat.seekervault.ReviewIdentity

/**
 * One look at everything a foreground banner could be raised for: what is waiting for the owner,
 * and which paired servers have ended the pairing.
 *
 * [ready] is every one of those lists having been filled at least once — not merely the stored
 * connections having been read. Before it, an empty snapshot means "not read yet" rather than
 * "nothing waiting", and seeding a baseline from it would turn the opening fetch and the stored
 * proposals into a burst of arrivals.
 */
data class InAppNotificationSnapshot(
    val ready: Boolean,
    val waiting: Set<ReviewIdentity>,
    val disconnected: Set<String>,
    /**
     * The items delivered as news rather than as catching up ([ArrivalLedger.live]). Only these are
     * ever announced; everything else in [waiting] is kept, counted and listed without a banner.
     */
    val live: Set<ReviewIdentity> = emptySet(),
    /**
     * Items a catching-up read stored before their live event arrived ([ArrivalLedger.late]). They
     * are announced even though they are already known, once.
     */
    val late: Set<ReviewIdentity> = emptySet(),
)

/** Something that has just appeared and has not been announced in the app before. */
sealed interface InAppNotificationArrival {
    data class Waiting(val identity: ReviewIdentity) : InAppNotificationArrival

    data class Disconnected(val connectionId: String) : InAppNotificationArrival
}

/**
 * Successive snapshots into arrivals.
 *
 * Something waiting is announced once per foreground session, the first time it is seen, and only
 * when it reached the phone as news ([InAppNotificationSnapshot.live]). Everything else that
 * appears — a feed's first snapshot, the fetch after coming back, a replayed history — joins what
 * is known without a word, so it cannot be announced later either (SEE-175). What is known is kept
 * for the whole session rather than for one look, so an item that leaves the list and comes back —
 * a status update, a refresh that briefly lost it, a duplicate delivery — is not news the second
 * time.
 *
 * The one exception is the snapshot/live handoff ([InAppNotificationSnapshot.late]): an item the
 * feed's first read stored quietly and the stream then delivered as it was published. It is known
 * already, but it has never been announced, and it is news. What has been announced is kept apart
 * from what is known, so the handoff can reach an item that was seen but never an item that was
 * told.
 *
 * A disconnection is still the plain set difference the push path uses to decide what deserves a
 * system notification (`sync/PushSynchronization`): what is in the new snapshot and was not in the
 * old one is new, and nothing else is.
 *
 * The first ready snapshot after the app becomes visible is a baseline and announces nothing. That
 * is what keeps the app from replaying, as a burst of banners, everything that arrived while it was
 * away and was already shown as a system notification. Waiting for readiness is what keeps an
 * ordinary cold start from doing the same with an inbox the owner has been carrying for days: the
 * opening fetch and the stored proposals belong to the baseline, not to it.
 */
class InAppNotificationSource {
    private var seeded = false
    private val known = mutableSetOf<ReviewIdentity>()
    private val announced = mutableSetOf<ReviewIdentity>()
    private var disconnected: Set<String> = emptySet()

    fun accept(snapshot: InAppNotificationSnapshot): List<InAppNotificationArrival> {
        if (!snapshot.ready) return emptyList()
        if (!seeded) {
            known += snapshot.waiting
            // A handoff that completed before the baseline belongs to it, like any other mark.
            announced += snapshot.late
            disconnected = snapshot.disconnected
            seeded = true
            return emptyList()
        }
        // Filtering keeps the waiting list's order, which is the chronological order the inbox
        // itself is sorted in, so a burst is counted oldest first.
        val fresh = snapshot.waiting.filterNot(known::contains).toSet()
        known += fresh
        val news =
            snapshot.waiting.filter { identity ->
                identity !in announced &&
                    ((identity in fresh && identity in snapshot.live) || identity in snapshot.late)
            }
        announced += news
        val arrivals =
            news.map(InAppNotificationArrival::Waiting) +
                (snapshot.disconnected - disconnected).map(InAppNotificationArrival::Disconnected)
        disconnected = snapshot.disconnected
        return arrivals
    }

    /** The app has left the foreground. What happens while it is away belongs to the system. */
    fun reset() {
        seeded = false
        known.clear()
        announced.clear()
        disconnected = emptySet()
    }
}
