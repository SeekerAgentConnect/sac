package io.github.brrenat.seekervault.notifications

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.designsystem.InAppNotification
import io.github.brrenat.seekervault.designsystem.InAppNotificationKind
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.PendingItem

/**
 * The foreground half of what the owner is told (SEE-147).
 *
 * The same two things a system notification is posted for — something newly waiting for the owner,
 * and a paired server that ended the pairing — become a banner while the app is being looked at,
 * and the same words are used for both: the copy comes from the very functions the notification
 * builder uses, so the two surfaces cannot drift apart.
 *
 * Nothing here decides *that* something arrived. It reads the lists the Home screen and the inbox
 * count are already drawn from, and announces what appeared in them since the last look
 * ([InAppNotificationSource]). That is what makes "the inbox count goes up at the moment the banner
 * appears" true by construction rather than by a second code path remembering to do it.
 *
 * Collection runs only while the lifecycle is at least STARTED, which is the app's own definition
 * of foreground (`MainActivity.onStart`/`onStop`). What arrives while the app is away is the system
 * notification's, and is not replayed as a burst of banners on return.
 *
 * @param ready whether every list a banner could be raised from has been filled at least once: the
 *   stored connections read, the fetch that follows them settled, and the stored proposals read.
 *   The first ready look is the baseline, so anything short of all three would make the rest of the
 *   opening arrive as banners.
 * @param waiting everything waiting for the owner, in the chronological order the inbox sorts it.
 * @param reviewOpen whether the review for that identity is already on screen; a banner for a
 *   request the owner is already reading is suppressed.
 * @param modifier where the banner sits in its parent. The safe area is the host's own business
 *   (SEE-150): the banner keeps clear of the status bar and any display cutout, with a visible gap
 *   below them, so a caller only aligns it.
 */
@Composable
fun InAppNotifications(
    ready: Boolean,
    connections: List<Connection>,
    waiting: List<PendingItem>,
    reviewOpen: (ReviewIdentity) -> Boolean,
    onOpen: (InAppNotificationTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentReviewOpen by rememberUpdatedState(reviewOpen)
    val queue = remember(scope) { InAppNotificationQueue(scope) { currentReviewOpen(it) } }

    val items =
        remember(waiting) {
            waiting
                .mapNotNull { item -> item.reviewIdentity()?.let { identity -> identity to item } }
                .toMap()
        }
    val disconnected =
        remember(connections) {
            connections.filter { it.revokedAt != null }.mapTo(LinkedHashSet(), Connection::id)
        }
    val labels = remember(connections) { connections.associate { it.id to it.label } }

    val snapshot =
        InAppNotificationSnapshot(
            ready = ready,
            waiting = items.keys,
            disconnected = disconnected,
        )
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentItems by rememberUpdatedState(items)
    val currentLabels by rememberUpdatedState(labels)

    val lifecycleOwner = LocalLifecycleOwner.current
    val source = remember { InAppNotificationSource() }
    LaunchedEffect(lifecycleOwner, queue) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            source.reset()
            try {
                snapshotFlow { currentSnapshot }
                    .collect { look ->
                        source.accept(look).forEach { arrival ->
                            queue.raise(context, arrival, currentItems, currentLabels)
                        }
                    }
            } finally {
                // Leaving the foreground takes the banner with it, timers and queue included.
                queue.clear()
            }
        }
    }

    // Plain collection: the queue is already emptied when the app leaves the foreground, so a
    // lifecycle-aware collector would only hold the last banner on a screen nobody is looking at.
    val notes by queue.notes.collectAsState()
    val visible = notes.firstOrNull() ?: return
    // Keyed so the next banner plays its own entry rather than inheriting the last one's motion.
    key(visible.id) {
        InAppNotification(
            kind = visible.kind,
            title = visible.title,
            subtitle = visible.subtitle,
            openActionLabel = visible.openActionLabel,
            dismissActionLabel = visible.dismissActionLabel,
            leaving = visible.leaving,
            onOpen = {
                queue.dismiss(visible.id)
                onOpen(visible.target)
            },
            onDismiss = { queue.dismiss(visible.id) },
            modifier = modifier.inAppNotificationSafeArea(),
        )
    }
}

/**
 * Below the status bar and any display cutout — both are in `safeDrawing`, and a cutout on the side
 * of a landscape phone is too — and then a gap, so the banner reads as floating over the content
 * rather than hanging from the top edge (SEE-150). The padding sits outside the banner's own
 * gesture area, so the strip above it stays the content's to touch.
 */
@Composable
private fun Modifier.inAppNotificationSafeArea(): Modifier =
    windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        )
        .padding(
            start = SeekerTheme.spacing.md,
            top = SeekerTheme.spacing.lg,
            end = SeekerTheme.spacing.md,
        )

/**
 * The words. A banner says what the system notification for the same event says, from the same two
 * functions, so the owner reads one thing whether the app was open or not.
 */
private fun InAppNotificationQueue.raise(
    context: Context,
    arrival: InAppNotificationArrival,
    items: Map<ReviewIdentity, PendingItem>,
    labels: Map<String, String>,
) {
    val dismiss = context.getString(R.string.in_app_notification_dismiss)
    when (arrival) {
        is InAppNotificationArrival.Waiting -> {
            val item = items[arrival.identity] ?: return
            val source = context.sourceName(labels[item.connectionId])
            when (item) {
                is PendingItem.Private -> {
                    val copy = requestNotificationCopy(context, item.request, source)
                    notify(
                        kind = InAppNotificationKind.Request,
                        target = InAppNotificationTarget.Review(arrival.identity),
                        title = copy.title,
                        subtitle =
                            context.getString(
                                R.string.in_app_notification_request_subtitle,
                                copy.source,
                                copy.summary,
                            ),
                        openActionLabel =
                            context.getString(
                                R.string.in_app_notification_open_request,
                                copy.title,
                            ),
                        dismissActionLabel = dismiss,
                    )
                }
                is PendingItem.Signal -> {
                    val copy = proposalNotificationCopy(context, item.record, source)
                    notify(
                        kind = InAppNotificationKind.Signal,
                        target = InAppNotificationTarget.Review(arrival.identity),
                        title = copy.title,
                        subtitle =
                            context.getString(
                                R.string.in_app_notification_signal_subtitle,
                                copy.source,
                                copy.summary,
                            ),
                        openActionLabel =
                            context.getString(R.string.in_app_notification_open_signal, copy.title),
                        dismissActionLabel = dismiss,
                    )
                }
            }
        }
        is InAppNotificationArrival.Disconnected -> {
            val title =
                context.getString(
                    R.string.in_app_notification_disconnected_title,
                    context.sourceName(labels[arrival.connectionId]),
                )
            notify(
                kind = InAppNotificationKind.Disconnected,
                target = InAppNotificationTarget.PairAgain(arrival.connectionId),
                title = title,
                subtitle = null,
                openActionLabel =
                    context.getString(R.string.in_app_notification_open_disconnected, title),
                dismissActionLabel = dismiss,
            )
        }
    }
}

private fun Context.sourceName(label: String?): String =
    label?.trim().orEmpty().ifEmpty { getString(R.string.notification_source_unknown) }

/** A blank identifier is not a route; such an item is carried but never announced. */
private fun PendingItem.reviewIdentity(): ReviewIdentity? {
    if (connectionId.isBlank() || requestId.isBlank()) return null
    return when (this) {
        is PendingItem.Private -> ReviewIdentity.Private(connectionId, requestId)
        is PendingItem.Signal -> ReviewIdentity.Signal(connectionId, requestId)
    }
}
