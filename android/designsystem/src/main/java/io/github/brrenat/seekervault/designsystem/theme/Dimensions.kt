package io.github.brrenat.seekervault.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class SeekerSpacing(
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val mdPlus: Dp,
    val lg: Dp,
    val lgPlus: Dp,
    val xl: Dp,
    val xxl: Dp,
    val xxxl: Dp,
    val huge: Dp,
    val jumbo: Dp,
)

internal val SeekerSpacingTokens =
    SeekerSpacing(
        xxs = 2.dp,
        xs = 4.dp,
        sm = 6.dp,
        md = 8.dp,
        mdPlus = 10.dp,
        lg = 12.dp,
        lgPlus = 14.dp,
        xl = 16.dp,
        xxl = 20.dp,
        xxxl = 24.dp,
        huge = 28.dp,
        jumbo = 32.dp,
    )

/** Legacy values retained while existing `:app` screens migrate to named design tokens. */
@Deprecated("Legacy screen values, not design tokens. Do not use in :designsystem.")
@Immutable
data class SeekerDimensions(
    val dp0: Dp,
    val dp1: Dp,
    val dp2: Dp,
    val dp3: Dp,
    val dp4: Dp,
    val dp5: Dp,
    val dp6: Dp,
    val dp7: Dp,
    val dp8: Dp,
    val dp9: Dp,
    val dp10: Dp,
    val dp12: Dp,
    val dp13: Dp,
    val dp14: Dp,
    val dp16: Dp,
    val dp18: Dp,
    val dp20: Dp,
    val dp22: Dp,
    val dp24: Dp,
    val dp28: Dp,
    val dp30: Dp,
    val dp32: Dp,
    val dp40: Dp,
    val dp48: Dp,
    val dp52: Dp,
    val dp56: Dp,
    val dp64: Dp,
    val dp68: Dp,
    val dp80: Dp,
    val dp86: Dp,
    val dp100: Dp,
    val dp150: Dp,
    val dp192: Dp,
    val dp204: Dp,
    val dp240: Dp,
    val dp420: Dp,
)

@Deprecated("Legacy screen values, not design tokens. Do not use in :designsystem.")
@Suppress("DEPRECATION")
internal val SeekerDimensionTokens =
    SeekerDimensions(
        dp0 = 0.dp,
        dp1 = 1.dp,
        dp2 = 2.dp,
        dp3 = 3.dp,
        dp4 = 4.dp,
        dp5 = 5.dp,
        dp6 = 6.dp,
        dp7 = 7.dp,
        dp8 = 8.dp,
        dp9 = 9.dp,
        dp10 = 10.dp,
        dp12 = 12.dp,
        dp13 = 13.dp,
        dp14 = 14.dp,
        dp16 = 16.dp,
        dp18 = 18.dp,
        dp20 = 20.dp,
        dp22 = 22.dp,
        dp24 = 24.dp,
        dp28 = 28.dp,
        dp30 = 30.dp,
        dp32 = 32.dp,
        dp40 = 40.dp,
        dp48 = 48.dp,
        dp52 = 52.dp,
        dp56 = 56.dp,
        dp64 = 64.dp,
        dp68 = 68.dp,
        dp80 = 80.dp,
        dp86 = 86.dp,
        dp100 = 100.dp,
        dp150 = 150.dp,
        dp192 = 192.dp,
        dp204 = 204.dp,
        dp240 = 240.dp,
        dp420 = 420.dp,
    )

@Immutable data class SeekerIconSizes(val standard: Dp)

@Immutable
data class SeekerButtonSize(
    val height: Dp,
    val radius: Dp,
    val fontSize: TextUnit,
    val horizontalPadding: Dp,
)

@Immutable
data class SeekerButtonSizes(
    val small: SeekerButtonSize,
    val medium: SeekerButtonSize,
    val large: SeekerButtonSize,
)

@Immutable
data class SeekerIconButtonSize(
    val box: Dp,
    val glyph: Dp,
)

@Immutable
data class SeekerIconButtonSizes(
    val medium: SeekerIconButtonSize,
    val large: SeekerIconButtonSize,
)

@Immutable
data class SeekerSizes(
    val icon: SeekerIconSizes,
    val button: SeekerButtonSizes,
    val iconButton: SeekerIconButtonSizes,
)

internal val SeekerSizeTokens =
    SeekerSizes(
        icon = SeekerIconSizes(standard = 28.dp),
        button =
            SeekerButtonSizes(
                small =
                    SeekerButtonSize(
                        height = 32.dp,
                        radius = 16.dp,
                        fontSize = 13.sp,
                        horizontalPadding = 12.dp,
                    ),
                medium =
                    SeekerButtonSize(
                        height = 40.dp,
                        radius = 20.dp,
                        fontSize = 14.sp,
                        horizontalPadding = 24.dp,
                    ),
                large =
                    SeekerButtonSize(
                        height = 48.dp,
                        radius = 24.dp,
                        fontSize = 15.sp,
                        horizontalPadding = 24.dp,
                    ),
            ),
        iconButton =
            SeekerIconButtonSizes(
                medium = SeekerIconButtonSize(box = 40.dp, glyph = 20.dp),
                large = SeekerIconButtonSize(box = 48.dp, glyph = 24.dp),
            ),
    )
