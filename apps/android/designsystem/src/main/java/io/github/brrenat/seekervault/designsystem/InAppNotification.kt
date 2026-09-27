package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerNotificationMetrics
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.launch

/** What the banner is about. A signal is a request with its own icon and its own words. */
enum class InAppNotificationKind {
    Request,
    Signal,
    Disconnected,
    /**
     * A service message: something the app did or learned — a connection added or renamed, a
     * publisher's decision, a save — on the theme's neutral surface rather than an accent.
     */
    Info,
}

/** Durations and easings for one banner's life on screen (SEE-147). */
object InAppNotificationMotion {
    const val EnterMs = 280
    const val ExitMs = 200
    const val FlyOutMs = 180
    const val SnapBackMs = 200

    /** A request or signal auto-dismisses this long after it becomes the visible banner. */
    const val LifetimeMs = 6_000L

    /** A dragged banner never fades past this, so it stays readable while it is being moved. */
    const val MinimumDragOpacity = 0.3f

    /** A banner released past the sideways threshold leaves by this much of its own width. */
    const val FlyOutWidthFraction = 1.2f

    val EnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** Test tag for the one banner on screen. */
const val InAppNotificationTag = "in-app-notification"

/**
 * The banner shown over the app while it is in the foreground: a request or signal that arrived, or
 * a paired server that ended the pairing.
 *
 * It is the Home wallet banner's shape — same radius, padding, gap, icon size and type — with a
 * shadow, because this one floats over the content rather than sitting in it.
 *
 * The composable owns only what one banner on screen owns: where the finger has moved it, and the
 * motion that brings it in and takes it out. Which banner is visible, how long it stays and where a
 * tap goes are the caller's (`notifications/InAppNotificationQueue` in `:app`).
 *
 * @param leaving the caller has decided this banner is going: play the exit. The caller removes it
 *   after [InAppNotificationMotion.ExitMs], which is the longer of the two exits.
 * @param onOpen the pointer went down and up again without travelling.
 * @param onDismiss the owner swiped it away. The banner has already begun its own fly-out; the
 *   caller is being told to mark it leaving and start the next one.
 */
@Composable
fun InAppNotification(
    kind: InAppNotificationKind,
    title: String,
    subtitle: String?,
    openActionLabel: String,
    dismissActionLabel: String,
    leaving: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val enter = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    // Set before onDismiss, so the exit the caller asks for is the one the gesture started.
    var flyOutDirection by remember { mutableStateOf(0f) }
    val leavingNow by rememberUpdatedState(leaving)

    LaunchedEffect(Unit) {
        enter.animateTo(
            targetValue = 1f,
            animationSpec =
                tween(
                    InAppNotificationMotion.EnterMs,
                    easing = InAppNotificationMotion.EnterEasing,
                ),
        )
    }
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        if (flyOutDirection == 0f) {
            exit.animateTo(
                targetValue = 1f,
                animationSpec =
                    tween(
                        InAppNotificationMotion.ExitMs,
                        easing = InAppNotificationMotion.ExitEasing,
                    ),
            )
        } else {
            fly.animateTo(
                targetValue = 1f,
                animationSpec =
                    tween(
                        InAppNotificationMotion.FlyOutMs,
                        easing = InAppNotificationMotion.ExitEasing,
                    ),
            )
        }
    }

    val container =
        when (kind) {
            InAppNotificationKind.Request,
            InAppNotificationKind.Signal -> SeekerTheme.colors.limeContainer
            InAppNotificationKind.Disconnected -> SeekerTheme.colors.orangeContainer
            InAppNotificationKind.Info -> SeekerTheme.colors.surface3
        }
    val ink =
        when (kind) {
            InAppNotificationKind.Request,
            InAppNotificationKind.Signal -> SeekerTheme.colors.onLimeContainer
            InAppNotificationKind.Disconnected -> SeekerTheme.colors.onOrangeContainer
            InAppNotificationKind.Info -> MaterialTheme.colorScheme.onSurface
        }
    val glyph =
        when (kind) {
            InAppNotificationKind.Request -> Icons.Outlined.NotificationsActive
            InAppNotificationKind.Signal -> Icons.Outlined.Sensors
            InAppNotificationKind.Disconnected -> Icons.Outlined.LinkOff
            InAppNotificationKind.Info -> Icons.Outlined.Info
        }
    val shape = RoundedCornerShape(SeekerTheme.radii.lg)
    val shadow = SeekerTheme.colors.overlayShadow

    Row(
        modifier =
            modifier
                .testTag(InAppNotificationTag)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    val slop = SeekerNotificationMetrics.TapSlop.toPx()
                    val aside = SeekerNotificationMetrics.DismissAside.toPx()
                    val up = SeekerNotificationMetrics.DismissUp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // A banner on its way out is not a target. Its exit carries it off the
                        // top of the screen while its bounds stay put, so a press here would
                        // otherwise open a review the owner cannot see.
                        if (leavingNow) return@awaitEachGesture
                        // Consumed so a press never reaches the content or the sheet underneath.
                        down.consume()
                        var total = Offset.Zero
                        var travelled = false
                        var lifted = false
                        try {
                            // Reads this pointer wherever it goes until it is lifted: the drag
                            // continues outside the banner's own bounds.
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    change.consume()
                                    lifted = true
                                    break
                                }
                                total += change.positionChange()
                                if (!travelled && total.getDistance() > slop) travelled = true
                                if (travelled) {
                                    change.consume()
                                    // Downward travel is held at the top edge.
                                    scope.launch {
                                        drag.snapTo(Offset(total.x, min(total.y, 0f)))
                                    }
                                }
                            }
                        } finally {
                            // The accumulated travel, not the animated value: what follows the
                            // finger is launched and may not have caught up at release. A
                            // cancelled pointer arrives here too, and snaps back like a short one.
                            val moved = Offset(total.x, min(total.y, 0f))
                            val sideways = abs(moved.x) >= abs(moved.y)
                            when {
                                !lifted -> snapBack(scope, drag)
                                !travelled -> onOpen()
                                sideways && abs(moved.x) > aside -> {
                                    flyOutDirection = sign(moved.x)
                                    onDismiss()
                                }
                                !sideways && -moved.y > up -> onDismiss()
                                else -> snapBack(scope, drag)
                            }
                        }
                    }
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = openActionLabel
                    liveRegion = LiveRegionMode.Polite
                    onClick {
                        onOpen()
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(dismissActionLabel) {
                                onDismiss()
                                true
                            }
                        )
                }
                .graphicsLayer {
                    val moved = drag.value
                    // Whichever axis has travelled further is the one the banner follows.
                    val sideways = abs(moved.x) >= abs(moved.y)
                    val span = SeekerNotificationMetrics.FadeSpan.toPx()
                    val fade =
                        if (sideways) {
                            max(
                                InAppNotificationMotion.MinimumDragOpacity,
                                1f - abs(moved.x) / span,
                            )
                        } else {
                            max(
                                InAppNotificationMotion.MinimumDragOpacity,
                                1f - 2f * abs(moved.y) / span,
                            )
                        }
                    val travel = size.height + SeekerNotificationMetrics.ExitOvertravel.toPx()
                    translationX =
                        (if (sideways) moved.x else 0f) +
                            flyOutDirection *
                                size.width *
                                InAppNotificationMotion.FlyOutWidthFraction *
                                fly.value
                    translationY =
                        (if (sideways) 0f else moved.y) - travel * ((1f - enter.value) + exit.value)
                    alpha = fade * (1f - exit.value) * (1f - fly.value)
                }
                .shadow(
                    elevation = SeekerNotificationMetrics.ShadowElevation,
                    shape = shape,
                    ambientColor = shadow,
                    spotColor = shadow,
                )
                .clip(shape)
                .background(container)
                .padding(SeekerTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxxl),
            tint = ink,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            // A service message is a sentence, not a headline, so it may wrap; the others keep the
            // one line that holds every request banner at the same height.
            Text(
                text = title,
                color = ink,
                maxLines = if (kind == InAppNotificationKind.Info) InfoTitleLines else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            // Only a request has one, and it is one line: the banner's height is the same for
            // every request no matter how much the server had to say about it.
            subtitle?.let { line ->
                Text(
                    text = line,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun snapBack(
    scope: kotlinx.coroutines.CoroutineScope,
    drag: Animatable<Offset, *>,
) {
    scope.launch {
        drag.animateTo(
            targetValue = Offset.Zero,
            animationSpec =
                tween(
                    InAppNotificationMotion.SnapBackMs,
                    easing = InAppNotificationMotion.EnterEasing,
                ),
        )
    }
}

/** How many lines a service message may take before it is cut. */
private const val InfoTitleLines = 3

private const val InAppNotificationPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES
private const val InAppNotificationPreviewWidth = 374

@Composable
private fun InAppNotificationPreview(
    kind: InAppNotificationKind,
    title: String,
    subtitle: String?,
    openActionLabel: String,
) {
    SeekerTheme(darkTheme = true) {
        InAppNotification(
            kind = kind,
            title = title,
            subtitle = subtitle,
            openActionLabel = openActionLabel,
            dismissActionLabel = "Dismiss",
            leaving = false,
            onOpen = {},
            onDismiss = {},
        )
    }
}

@DesignRef(component = "notification", variant = "kind=request")
@Preview(
    name = "notification/kind-request",
    widthDp = InAppNotificationPreviewWidth,
    uiMode = InAppNotificationPreviewDarkMode,
)
@Composable
internal fun InAppNotificationRequestPreview() =
    InAppNotificationPreview(
        kind = InAppNotificationKind.Request,
        title = "Send 5 SOL",
        subtitle = "New request · studio-mac · funds move",
        openActionLabel = "Send 5 SOL, open request",
    )

@DesignRef(component = "notification", variant = "kind=signal")
@Preview(
    name = "notification/kind-signal",
    widthDp = InAppNotificationPreviewWidth,
    uiMode = InAppNotificationPreviewDarkMode,
)
@Composable
internal fun InAppNotificationSignalPreview() =
    InAppNotificationPreview(
        kind = InAppNotificationKind.Signal,
        title = "Bitcoin under \$68,000",
        subtitle = "New signal · Jupiter Prediction demo · you pick the side and the amount",
        openActionLabel = "Bitcoin under \$68,000, open signal",
    )

@DesignRef(component = "notification", variant = "kind=disconnected")
@Preview(
    name = "notification/kind-disconnected",
    widthDp = InAppNotificationPreviewWidth,
    uiMode = InAppNotificationPreviewDarkMode,
)
@Composable
internal fun InAppNotificationDisconnectedPreview() =
    InAppNotificationPreview(
        kind = InAppNotificationKind.Disconnected,
        title = "runner-node disconnected",
        subtitle = null,
        openActionLabel = "runner-node disconnected, open",
    )

@DesignRef(component = "notification", variant = "kind=info")
@Preview(
    name = "notification/kind-info",
    widthDp = InAppNotificationPreviewWidth,
    uiMode = InAppNotificationPreviewDarkMode,
)
@Composable
internal fun InAppNotificationInfoPreview() =
    InAppNotificationPreview(
        kind = InAppNotificationKind.Info,
        title = "CopyTrading approved this device. Its signals will arrive here.",
        subtitle = null,
        openActionLabel = "CopyTrading approved this device, dismiss",
    )
