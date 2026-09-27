package io.github.brrenat.seekervault.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import kotlin.math.min

/** Durations and easings for graph-owned sheet enter, exit, and stack recess. */
object SheetMotion {
    const val EnterMs = 260
    const val ExitMs = 240
    const val StackMs = 300
    const val MaxScaleLevels = 3
    const val ScalePerLevel = 0.04f
    val EnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun scale(back: Int): Float = 1f - min(back, MaxScaleLevels) * ScalePerLevel
}

/** Depth from the front of the sheet stack. 0 is the frontmost sheet. */
val LocalSheetStackBack = compositionLocalOf { 0 }

/** True when the sheet is hosted in the app stack rather than an isolated preview. */
val LocalSheetStackHosted = compositionLocalOf { false }

@Composable
fun sheetStackSurfaceColor(): Color {
    val back = LocalSheetStackBack.current
    val color by
        animateColorAsState(
            if (back > 0) SeekerTheme.colors.dim else SeekerTheme.colors.surface2,
            tween(SheetMotion.StackMs, easing = Ease),
            label = "sheetStackSurface",
        )
    return color
}

@Composable
fun sheetStackContentColor(): Color {
    val back = LocalSheetStackBack.current
    val color by
        animateColorAsState(
            if (back > 0) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            tween(SheetMotion.StackMs, easing = Ease),
            label = "sheetStackInk",
        )
    return color
}

/**
 * Applies the stack-depth scale and blur. Depth properties animate on the layer being pushed back
 * (or restored), not on a newly incoming sheet.
 */
@Composable
fun StackedSheetUnderlay(
    modifier: Modifier = Modifier,
    back: Int = 1,
    content: @Composable () -> Unit,
) {
    val scale by
        animateFloatAsState(
            SheetMotion.scale(back),
            tween(SheetMotion.StackMs, easing = SheetMotion.EnterEasing),
            label = "sheetStackScale",
        )
    val blur by
        animateDpAsState(
            SeekerTheme.spacing.xxs * back.coerceIn(0, 1),
            tween(SheetMotion.StackMs, easing = SheetMotion.EnterEasing),
            label = "sheetStackBlur",
        )
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .blur(blur)
    ) {
        content()
    }
}
