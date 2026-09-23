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
 */
class InAppNotificationQueue(
    private val scope: CoroutineScope,
    private val reviewOpen: (ReviewIdentity) -> Boolean = { false },
) {
    private val _notes = MutableStateFlow<List<InAppNote>>(emptyList())

    /** The whole queue. The head is the banner on screen. */
    val notes: StateFlow<List<InAppNote>> = _notes.asStateFlow()

    private var nextId = 0L
    private val timers = mutableMapOf<Long, Job>()

    /** The banner on screen, or null when nothing is showing. */
    val visible: InAppNote?
        get() = _notes.value.firstOrNull()

    fun notify(
        kind: InAppNotificationKind,
        target: InAppNotificationTarget,
        title: String,
        subtitle: String?,
        openActionLabel: String,
        dismissActionLabel: String,
    ) {
        if (target is InAppNotificationTarget.Review && reviewOpen(target.identity)) return
        _notes.update { queued ->
            queued +
                InAppNote(
                    id = ++nextId,
                    kind = kind,
                    target = target,
                    title = title,
                    subtitle = subtitle,
                    openActionLabel = openActionLabel,
                    dismissActionLabel = dismissActionLabel,
                )
        }
        armHead()
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
            armHead()
        }
    }

    /** Everything queued leaves at once, without motion. The app is no longer being looked at. */
    fun clear() {
        timers.values.forEach(Job::cancel)
        timers.clear()
        _notes.value = emptyList()
    }

    private fun armHead() {
        val head = _notes.value.firstOrNull() ?: return
        if (head.armed || head.leaving) return
        // A disconnected banner is never armed: it is the one thing here the owner has to answer.
        if (head.kind == InAppNotificationKind.Disconnected) return
        _notes.update { queued ->
            queued.map { if (it.id == head.id) it.copy(armed = true) else it }
        }
        timers[head.id] = scope.launch {
            delay(InAppNotificationMotion.LifetimeMs)
            dismiss(head.id)
        }
    }
}
