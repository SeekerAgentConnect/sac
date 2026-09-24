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
)

/** Something that has just appeared and has not been announced in the app before. */
sealed interface InAppNotificationArrival {
    data class Waiting(val identity: ReviewIdentity) : InAppNotificationArrival

    data class Disconnected(val connectionId: String) : InAppNotificationArrival
}

/**
 * Successive snapshots into arrivals, by the same set difference the push path already uses to
 * decide what deserves a system notification (`sync/PushSynchronization`): what is in the new
 * snapshot and was not in the old one is new, and nothing else is.
 *
 * The first ready snapshot after the app becomes visible is a baseline and announces nothing. That
 * is what keeps the app from replaying, as a burst of banners, everything that arrived while it was
 * away and was already shown as a system notification. Waiting for readiness is what keeps an
 * ordinary cold start from doing the same with an inbox the owner has been carrying for days: the
 * opening fetch and the stored proposals belong to the baseline, not to it.
 */
class InAppNotificationSource {
    private var seeded = false
    private var waiting: Set<ReviewIdentity> = emptySet()
    private var disconnected: Set<String> = emptySet()

    fun accept(snapshot: InAppNotificationSnapshot): List<InAppNotificationArrival> {
        if (!snapshot.ready) return emptyList()
        if (!seeded) {
            remember(snapshot)
            seeded = true
            return emptyList()
        }
        // Set subtraction keeps the receiver's order, and the waiting list arrives in the
        // chronological order the inbox itself is sorted in, so a burst queues oldest first.
        val arrivals =
            (snapshot.waiting - waiting).map(InAppNotificationArrival::Waiting) +
                (snapshot.disconnected - disconnected).map(InAppNotificationArrival::Disconnected)
        remember(snapshot)
        return arrivals
    }

    /** The app has left the foreground. What happens while it is away belongs to the system. */
    fun reset() {
        seeded = false
        waiting = emptySet()
        disconnected = emptySet()
    }

    private fun remember(snapshot: InAppNotificationSnapshot) {
        waiting = snapshot.waiting
        disconnected = snapshot.disconnected
    }
}
