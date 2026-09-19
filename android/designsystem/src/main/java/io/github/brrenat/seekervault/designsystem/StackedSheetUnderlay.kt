package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/** The inert, softened sheet that remains visible behind a pushed sheet. */
@Composable
fun StackedSheetUnderlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = UnderlayScale
                    scaleY = UnderlayScale
                    transformOrigin = TransformOrigin.Center.copy(pivotFractionY = 0f)
                }
                .blur(SeekerTheme.spacing.xxs)
    ) {
        content()
    }
}

private const val UnderlayScale = 0.96f
