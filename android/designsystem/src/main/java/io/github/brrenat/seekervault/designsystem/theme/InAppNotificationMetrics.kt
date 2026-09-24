package io.github.brrenat.seekervault.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * The distances the in-app notification banner's gesture is measured in (SEE-147).
 *
 * They are runtime behaviour rather than one of the named visual scales in `design/tokens.json`,
 * and they are here because this package is the one place a raw `dp` belongs
 * (`scripts/check-design-literals.mjs`).
 */
internal object SeekerNotificationMetrics {
    /** Under this much travel the pointer was a tap, not a drag. */
    val TapSlop = 6.dp

    /** Released past this much sideways travel, the banner flies out that way. */
    val DismissAside = 72.dp

    /** Released past this much upward travel, the banner plays the ordinary exit. */
    val DismissUp = 40.dp

    /** Full travel for the drag's fade: `max(0.3, 1 - |dx| / 300)`. */
    val FadeSpan = 300.dp

    /** How far past its own height the banner travels on entry and exit. */
    val ExitOvertravel = 40.dp

    /**
     * The specimen's `0 8 24 rgba(0,0,0,.45)`. Android's elevation shadow offsets and blurs
     * together, so one elevation stands for the pair; 12dp is the closest match to an 8dp drop
     * under a 24dp blur, and [SeekerColors.overlayShadow] carries the two themes' opacity.
     */
    val ShadowElevation = 12.dp
}
