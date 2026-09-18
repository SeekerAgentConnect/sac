package io.github.brrenat.seekervault.designsystem.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

private val LocalSeekerColors = staticCompositionLocalOf { DarkSeekerColors }
private val LocalSeekerSpacing = staticCompositionLocalOf { SeekerSpacingTokens }
private val LocalSeekerDimensions = staticCompositionLocalOf { SeekerDimensionTokens }
private val LocalSeekerRadii = staticCompositionLocalOf { SeekerRadiusTokens }
private val LocalSeekerTypography = staticCompositionLocalOf { SeekerExtraTypographyTokens }

object SeekerTheme {
    val colors: SeekerColors
        @Composable get() = LocalSeekerColors.current

    val spacing: SeekerSpacing
        @Composable get() = LocalSeekerSpacing.current

    val dimensions: SeekerDimensions
        @Composable get() = LocalSeekerDimensions.current

    val radii: SeekerRadii
        @Composable get() = LocalSeekerRadii.current

    val typography: SeekerExtraTypography
        @Composable get() = LocalSeekerTypography.current

    val shapes: Shapes
        @Composable get() = MaterialTheme.shapes

    /** Installs every visual token and disables Material defaults that are not in the export. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    operator fun invoke(
        darkTheme: Boolean = isSystemInDarkTheme(),
        content: @Composable () -> Unit,
    ) {
        val colors = if (darkTheme) DarkSeekerColors else LightSeekerColors
        val scheme = if (darkTheme) DarkColorScheme else LightColorScheme
        CompositionLocalProvider(
            LocalSeekerColors provides colors,
            LocalSeekerSpacing provides SeekerSpacingTokens,
            LocalSeekerDimensions provides SeekerDimensionTokens,
            LocalSeekerRadii provides SeekerRadiusTokens,
            LocalSeekerTypography provides SeekerExtraTypographyTokens,
        ) {
            MaterialTheme(
                colorScheme = scheme,
                typography = SeekerTypography,
                shapes = SeekerShapes,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides scheme.onSurface,
                    LocalTonalElevationEnabled provides false,
                    LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                    LocalRippleConfiguration provides
                        RippleConfiguration(color = colors.primaryText),
                    LocalIndication provides ripple(color = colors.primaryText),
                    content = content,
                )
            }
        }
    }
}
