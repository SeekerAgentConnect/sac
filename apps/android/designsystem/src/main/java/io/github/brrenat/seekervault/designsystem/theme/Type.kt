package io.github.brrenat.seekervault.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.brrenat.seekervault.designsystem.R

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resource: Int, weight: FontWeight): Font =
    Font(
        resId = resource,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

@OptIn(ExperimentalTextApi::class)
private val Roboto =
    FontFamily(
        variableFont(R.font.roboto_variable, FontWeight.Normal),
        variableFont(R.font.roboto_variable, FontWeight.Medium),
        variableFont(R.font.roboto_variable, FontWeight.SemiBold),
        variableFont(R.font.roboto_variable, FontWeight.Bold),
    )

@OptIn(ExperimentalTextApi::class)
private val RobotoMono =
    FontFamily(
        variableFont(R.font.roboto_mono_variable, FontWeight.Normal),
        variableFont(R.font.roboto_mono_variable, FontWeight.Medium),
    )

private val CssLineHeight =
    LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
        mode = LineHeightStyle.Mode.Fixed,
    )

private fun style(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = Roboto,
): TextStyle =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = CssLineHeight,
    )

internal val SeekerTypography =
    Typography(
        displayLarge = style(36.sp, 43.2.sp),
        displayMedium = style(36.sp, 43.2.sp),
        displaySmall = style(36.sp, 43.2.sp),
        headlineLarge = style(28.sp, 33.6.sp),
        headlineMedium = style(24.sp, 28.8.sp),
        headlineSmall = style(22.sp, 26.4.sp),
        titleLarge = style(22.sp, 26.4.sp),
        titleMedium = style(16.sp, 19.2.sp, FontWeight.Medium),
        titleSmall = style(14.sp, 16.8.sp, FontWeight.Medium),
        bodyLarge = style(16.sp, 19.2.sp),
        bodyMedium = style(14.sp, 16.8.sp),
        bodySmall = style(13.sp, 15.6.sp),
        labelLarge = style(14.sp, 16.8.sp, FontWeight.Medium),
        labelMedium = style(12.sp, 14.4.sp, FontWeight.Medium),
        labelSmall = style(12.sp, 14.4.sp, FontWeight.Medium),
    )

@Immutable
data class SeekerExtraTypography(
    val buttonLarge: TextStyle,
    val amount: TextStyle,
    val screenTitle: TextStyle,
    val identifier: TextStyle,
    val walletAddress: TextStyle,
    /**
     * The request tile's middle title size (SEE-183), between `buttonLarge` and `titleLarge`. The
     * Stage 7.2 export has no 17sp step; it joins `tokens.json` at the next re-export.
     */
    val tileTitle: TextStyle,
    /** The count beside a warning badge's `!` (SEE-183): 13sp at 600. */
    val badgeCount: TextStyle,
)

internal val SeekerExtraTypographyTokens =
    SeekerExtraTypography(
        buttonLarge = style(15.sp, 18.sp, FontWeight.Medium),
        amount = style(18.sp, 21.6.sp),
        screenTitle = style(20.sp, 24.sp),
        identifier = style(13.sp, 15.6.sp, family = RobotoMono),
        walletAddress = style(12.sp, 18.sp, family = RobotoMono),
        tileTitle = style(17.sp, 20.4.sp),
        badgeCount = style(13.sp, 15.6.sp, FontWeight.SemiBold),
    )
