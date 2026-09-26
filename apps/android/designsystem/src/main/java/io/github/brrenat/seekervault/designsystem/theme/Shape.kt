package io.github.brrenat.seekervault.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class SeekerRadii(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val sheet: Dp,
)

internal val SeekerRadiusTokens =
    SeekerRadii(
        xs = 4.dp,
        sm = 8.dp,
        md = 12.dp,
        lg = 16.dp,
        xl = 20.dp,
        xxl = 24.dp,
        sheet = 28.dp,
    )

internal val SeekerShapes =
    Shapes(
        extraSmall = RoundedCornerShape(SeekerRadiusTokens.xs),
        small = RoundedCornerShape(SeekerRadiusTokens.sm),
        medium = RoundedCornerShape(SeekerRadiusTokens.md),
        large = RoundedCornerShape(SeekerRadiusTokens.lg),
        extraLarge = RoundedCornerShape(SeekerRadiusTokens.sheet),
    )
