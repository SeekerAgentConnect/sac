package io.github.brrenat.seekervault.notifications

import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.designsystem.InAppNotificationKind
import io.github.brrenat.seekervault.designsystem.InAppNotificationMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a tapped banner goes. */
sealed interface InAppNotificationTarget {
    /** A request or a signal: the review it is about. */
    data class Review(val identity: ReviewIdentity) : InAppNotificationTarget

    /** A server that ended the pairing: Add connection, because pairing again is the only way. */
    data class PairAgain(val connectionId: String) : InAppNotificationTarget

    /** A service message: there is nowhere to go, so a tap only puts it away. */
    data object Dismiss : InAppNotificationTarget

    /** Several requests or signals at once: the Inbox, where every one of them is (SEE-175). */
    data object Inbox : InAppNotificationTarget
}

/** What one banner says and where it goes, before it has a place in the queue. */
data class InAppNoteCopy(
    val kind: InAppNotificationKind,
    val target: InAppNotificationTarget,
    val title: String,
    val subtitle: String?,
    val openActionLabel: String,
    val dismissActionLabel: String,
)

/**
 * The unique waiting items one incoming banner is about, oldest first (SEE-175).
 *
 * [items] holds at most [IncomingNotificationPolicy.MOST_COUNTED] identities; [overflow] says more
 * arrived than that, so the banner says "99+" rather than a number it cannot vouch for.
 */
data class IncomingBurst(val items: List<ReviewIdentity>, val overflow: Boolean = false) {
    /** The one item this banner is about, when it is about exactly one. */
    val single: ReviewIdentity?
        get() = items.singleOrNull()?.takeUnless { overflow }
}

/**
 * The limits on incoming-request banners (SEE-175), in one place so the tests and the documentation
 * (docs/wiki/in-app-notifications.md) name the same numbers.
 */
object IncomingNotificationPolicy {
    /**
     * How long an incoming banner stays once it is visible. It is the same six seconds every banner
     * gets, and it is **fixed**: merging more arrivals into the banner changes its words and never
     * its timer, so a stream that never stops cannot keep one on screen forever.
     */
    const val LIFETIME_MS: Long = InAppNotificationMotion.LifetimeMs

    /**
     * How long after an incoming banner leaves — timed out, swiped or tapped — before the next one
     * may appear. Whatever arrives meanwhile is gathered into a single banner shown when it ends,
     * so at most one incoming banner is presented per `LIFETIME_MS + exit + COOLDOWN_MS` (about 36
     * seconds), and dismissing one never brings the same burst straight back.
     */
    const val COOLDOWN_MS: Long = 30_000L

    /**
     * The most items one banner counts. It is also the bound on what is held during a cooldown: the
     * inbox has every item, and the banner only has to say that there are many.
     */
    const val MOST_COUNTED: Int = 99
}

/**
 * One banner, queued or on screen. [armed] means its six seconds have started, which happens when
 * it reaches the front and not when it arrives; [leaving] means the exit is playing.
 */
data class InAppNote(
    val id: Long,
    val kind: InAppNotificationKind,
    val target: InAppNotificationTarget,
    val title: String,
    val subtitle: String?,
    val openActionLabel: String,
    val dismissActionLabel: String,
    val armed: Boolean = false,
    val leaving: Boolean = false,
)

/**
 * One banner on screen at a time; later events wait their turn.
 *
 * The rules are the specification's, and they are here rather than in the composable because they
 * are about which banner is shown rather than about how one looks: a request or signal gets its
 * full six seconds from the moment it becomes visible, a disconnected banner has no timer at all
 * and holds the queue until the owner deals with it, and a request whose review is already open
 * never joins the queue — the owner is looking at the thing the banner would announce.
 *
 * Requests and signals arriving are coordinated rather than queued one by one (SEE-175, [arrive]),
 * whichever connection or producer they came from:
 * - There is at most one incoming banner, visible or waiting. Whatever arrives while it exists is
 *   merged into it, counted by unique identity, and it becomes "5 new requests" opening the Inbox;
 *   one isolated item keeps its own words and opens its own review.
 * - Its lifetime is fixed when it becomes visible, and merging never extends it.
 * - When it leaves, a cooldown starts ([IncomingNotificationPolicy.COOLDOWN_MS]). What arrives
 *   during it is held — unique, bounded — and shown as one banner when it ends, never replayed one
 *   by one.
 * - A service message or a disconnection is never held back by that: it is queued ahead of an
 *   incoming banner that is not yet visible, and nothing here throttles it.
 */
class InAppNotificationQueue(
    private val scope: CoroutineScope,
    /**
     * The words for an incoming banner, resolved when it is raised and each time more is merged
     * into it. Null when none of its items can be described any more; then nothing is shown.
     */
    private val describe: (IncomingBurst) -> InAppNoteCopy? = { null },
    private val reviewOpen: (ReviewIdentity) -> Boolean = { false },
) {
    private val _notes = MutableStateFlow<List<InAppNote>>(emptyList())

    /** The whole queue. The head is the banner on screen. */
    val notes: StateFlow<List<InAppNote>> = _notes.asStateFlow()

    private var nextId = 0L
    private val timers = mutableMapOf<Long, Job>()

    /** The one incoming banner, visible or waiting, and the items it is about. */
    private var incoming: Incoming? = null

    /** What arrived during a cooldown, to be told as one banner when it ends. */
    private var held = Gathered()
    private var cooldown: Job? = null

    /** The banner on screen, or null when nothing is showing. */
    val visible: InAppNote?
        get() = _notes.value.firstOrNull()

    /** Whether incoming banners are paused after the last one left. */
    val coolingDown: Boolean
        get() = cooldown?.isActive == true

    fun notify(
        kind: InAppNotificationKind,
        target: InAppNotificationTarget,
        title: String,
        subtitle: String?,
        openActionLabel: String,
        dismissActionLabel: String,
    ) {
        if (target is InAppNotificationTarget.Review && reviewOpen(target.identity)) return
        val note =
            InAppNote(
                id = ++nextId,
                kind = kind,
                target = target,
                title = title,
                subtitle = subtitle,
                openActionLabel = openActionLabel,
                dismissActionLabel = dismissActionLabel,
            )
        _notes.update { queued ->
            // An incoming banner nobody has seen yet waits behind this one: an error or the result
            // of something the owner just did is not held back by informational news.
            val waiting = incoming?.let { current ->
                queued.indexOfFirst { it.id == current.noteId }
            }
            if (waiting != null && waiting > 0) {
                queued.subList(0, waiting) + note + queued.subList(waiting, queued.size)
            } else {
                queued + note
            }
        }
        armHead()
    }

    /**
     * Requests or signals that have just arrived, in the order they are waiting in (SEE-175).
     *
     * They join the incoming banner if there is one, are held if incoming banners are cooling down,
     * and otherwise raise one banner for all of them.
     */
    fun arrive(identities: List<ReviewIdentity>) {
        val arrived = identities.distinct().filterNot(reviewOpen)
        if (arrived.isEmpty()) return
        val current = incoming
        val note = current?.let { noteOf(it.noteId) }
        when {
            current != null && note != null && !note.leaving -> {
                val merged = current.items.plus(arrived)
                if (merged == current.items) return
                incoming = current.copy(items = merged)
                describe(merged.burst())?.let { copy -> rewrite(current.noteId, copy) }
            }
            // Leaving counts as gone: the cooldown starts the moment its exit ends.
            current != null || coolingDown -> held = held.plus(arrived)
            else -> raise(Gathered().plus(arrived))
        }
    }

    /**
     * What is waiting now. Anything held for later, or in an incoming banner that has not been seen
     * yet, which is no longer waiting — answered, opened, withdrawn — is not told afterwards. The
     * banner on screen is left as it is: its words do not change under the owner's finger.
     */
    fun retainWaiting(waiting: Set<ReviewIdentity>) {
        held = held.retain(waiting)
        val current = incoming ?: return
        val note = noteOf(current.noteId) ?: return
        if (note.armed || note.leaving || visible?.id == note.id) return
        val kept = current.items.retain(waiting)
        if (kept == current.items) return
        if (kept.items.isEmpty()) {
            incoming = null
            _notes.update { queued -> queued.filterNot { it.id == note.id } }
            return
        }
        incoming = current.copy(items = kept)
        describe(kept.burst())?.let { copy -> rewrite(current.noteId, copy) }
    }

    /**
     * Starts one banner's exit. The note stays in the queue, marked [InAppNote.leaving], for as
     * long as the exit lasts, so the composable can play it before the view is taken away.
     */
    fun dismiss(id: Long) {
        val note = _notes.value.firstOrNull { it.id == id } ?: return
        if (note.leaving) return
        timers.remove(id)?.cancel()
        _notes.update { queued ->
            queued.map { if (it.id == id) it.copy(leaving = true) else it }
        }
        scope.launch {
            delay(InAppNotificationMotion.ExitMs.toLong())
            _notes.update { queued -> queued.filterNot { it.id == id } }
            if (incoming?.noteId == id) {
                incoming = null
                coolDown()
            }
            armHead()
        }
    }

    /** Everything queued leaves at once, without motion. The app is no longer being looked at. */
    fun clear() {
        timers.values.forEach(Job::cancel)
        timers.clear()
        cooldown?.cancel()
        cooldown = null
        incoming = null
        held = Gathered()
        _notes.value = emptyList()
    }

    private fun raise(items: Gathered) {
        val copy = describe(items.burst()) ?: return
        val id = ++nextId
        incoming = Incoming(id, items)
        _notes.update { queued ->
            queued +
                InAppNote(
                    id = id,
                    kind = copy.kind,
                    target = copy.target,
                    title = copy.title,
                    subtitle = copy.subtitle,
                    openActionLabel = copy.openActionLabel,
                    dismissActionLabel = copy.dismissActionLabel,
                )
        }
        armHead()
    }

    private fun coolDown() {
        cooldown?.cancel()
        cooldown = scope.launch {
            delay(IncomingNotificationPolicy.COOLDOWN_MS)
            cooldown = null
            val gathered = held
            held = Gathered()
            if (gathered.items.isNotEmpty()) raise(gathered)
        }
    }

    /** New words for a note, in place: same id, same timer, same position. */
    private fun rewrite(id: Long, copy: InAppNoteCopy) {
        _notes.update { queued ->
            queued.map {
                if (it.id != id) {
                    it
                } else {
                    it.copy(
                        kind = copy.kind,
                        target = copy.target,
                        title = copy.title,
                        subtitle = copy.subtitle,
                        openActionLabel = copy.openActionLabel,
                        dismissActionLabel = copy.dismissActionLabel,
                    )
                }
            }
        }
    }

    private fun noteOf(id: Long): InAppNote? = _notes.value.firstOrNull { it.id == id }

    private fun armHead() {
        val head = _notes.value.firstOrNull() ?: return
        if (head.armed || head.leaving) return
        // A disconnected banner is never armed: it is the one thing here the owner has to answer.
        if (head.kind == InAppNotificationKind.Disconnected) return
        _notes.update { queued ->
            queued.map { if (it.id == head.id) it.copy(armed = true) else it }
        }
        timers[head.id] = scope.launch {
            delay(IncomingNotificationPolicy.LIFETIME_MS)
            dismiss(head.id)
        }
    }

    private data class Incoming(val noteId: Long, val items: Gathered)

    /**
     * Unique identities in arrival order, never more than
     * [IncomingNotificationPolicy.MOST_COUNTED].
     */
    private data class Gathered(
        val items: List<ReviewIdentity> = emptyList(),
        val overflow: Boolean = false,
    ) {
        fun plus(more: List<ReviewIdentity>): Gathered {
            val all = LinkedHashSet(items)
            var over = overflow
            for (identity in more) {
                if (identity in all) continue
                if (all.size == IncomingNotificationPolicy.MOST_COUNTED) {
                    over = true
                    break
                }
                all += identity
            }
            return Gathered(all.toList(), over)
        }

        fun retain(waiting: Set<ReviewIdentity>): Gathered =
            Gathered(items.filter(waiting::contains), overflow)

        fun burst() = IncomingBurst(items, overflow)
    }
}
