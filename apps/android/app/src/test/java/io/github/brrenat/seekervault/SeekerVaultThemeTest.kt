package io.github.brrenat.seekervault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerColors
import io.github.brrenat.seekervault.designsystem.theme.SeekerDimensions
import io.github.brrenat.seekervault.designsystem.theme.SeekerExtraTypography
import io.github.brrenat.seekervault.designsystem.theme.SeekerRadii
import io.github.brrenat.seekervault.designsystem.theme.SeekerSpacing
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.ui.BottomDestination
import io.github.brrenat.seekervault.ui.SeekerBottomBar
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SolidDialog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTextApi::class)
class SeekerVaultThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun darkSchemeMatchesEveryApprovedV4RoleAndUsesOpaqueTokens() {
        val values = capture(dark = true)
        with(values.scheme) {
            assertEquals(Color(0xFFE7FC6E), primary)
            assertEquals(Color(0xFF1B1B1B), onPrimary)
            assertEquals(Color(0xFFC2E60F), primaryContainer)
            assertEquals(Color(0xFF1B1B1B), onPrimaryContainer)
            assertEquals(Color(0xFFFFB27A), tertiary)
            assertEquals(Color(0xFF2E1200), onTertiary)
            assertEquals(Color(0xFFFF7A1A), tertiaryContainer)
            assertEquals(Color(0xFF2E1200), onTertiaryContainer)
            assertEquals(Color(0xFFF83959), error)
            assertEquals(Color(0xFF2B0008), onError)
            assertEquals(Color(0xFF4D0011), errorContainer)
            assertEquals(Color(0xFFFFD9DE), onErrorContainer)
            assertEquals(Color(0xFF121212), background)
            assertEquals(Color.White, onBackground)
            assertEquals(Color(0xFF121212), surface)
            assertEquals(Color.White, onSurface)
            assertEquals(Color(0xFF232323), surfaceVariant)
            assertEquals(Color(0xFFCACACA), onSurfaceVariant)
            assertEquals(Color(0xFF6F6F6F), outline)
            assertEquals(Color(0xFF3A3A3A), outlineVariant)
            assertEquals(Color(0xFF121212), surfaceContainerLowest)
            assertEquals(Color(0xFF1C1C1C), surfaceContainerLow)
            assertEquals(Color(0xFF1C1C1C), surfaceContainer)
            assertEquals(Color(0xFF232323), surfaceContainerHigh)
            assertEquals(Color(0xFF2E2E2E), surfaceContainerHighest)
            assertEquals(Color(0xFF2E2E2E), surfaceBright)
            assertEquals(Color(0xFF0A0A0A), surfaceDim)
            assertEquals(Color(0xFFF7F7F7), inverseSurface)
            assertEquals(Color(0xFF1B1B1B), inverseOnSurface)
            assertEquals(Color(0xFF4F5C00), inversePrimary)
            assertEquals(Color(0xFF0A0A0A), scrim)
            assertEquals(Color.Transparent, surfaceTint)
        }
        assertEquals(Color(0xFFE7FC6E), values.extra.primaryText)
        assertEquals(Color(0xFF0A0A0A), values.extra.dim)
        assertEquals(Color(0xFFF83959), values.extra.errorText)
        assertEquals(Color(0xFF7EC8FF), values.extra.blueChip)
        assertEquals(Color(0xFF00243D), values.extra.onBlueChip)
        assertEquals(Color(0xFFFF8FA8), values.extra.pinkChip)
        assertEquals(Color(0xFF3D0014), values.extra.onPinkChip)
        assertOpaque(values)
    }

    @Test
    fun lightSchemeMatchesEveryApprovedV4RoleAndUsesReadableAccentText() {
        val values = capture(dark = false)
        with(values.scheme) {
            assertEquals(Color(0xFFF1FFA0), primary)
            assertEquals(Color(0xFF1B1B1B), onPrimary)
            assertEquals(Color(0xFFE9FF7A), primaryContainer)
            assertEquals(Color(0xFF2C3400), onPrimaryContainer)
            assertEquals(Color(0xFF8A3C00), tertiary)
            assertEquals(Color.White, onTertiary)
            assertEquals(Color(0xFFFFE0C2), tertiaryContainer)
            assertEquals(Color(0xFF4A2600), onTertiaryContainer)
            assertEquals(Color(0xFFF83959), error)
            assertEquals(Color(0xFF2B0008), onError)
            assertEquals(Color(0xFFFFE1E5), errorContainer)
            assertEquals(Color(0xFF5C0014), onErrorContainer)
            assertEquals(Color(0xFFF7F7F7), background)
            assertEquals(Color(0xFF1B1B1B), onBackground)
            assertEquals(Color(0xFFF7F7F7), surface)
            assertEquals(Color(0xFF1B1B1B), onSurface)
            assertEquals(Color(0xFFEEEEEE), surfaceVariant)
            assertEquals(Color(0xFF45464A), onSurfaceVariant)
            assertEquals(Color(0xFF76767F), outline)
            assertEquals(Color(0xFFC6C6C9), outlineVariant)
            assertEquals(Color.White, surfaceContainerLowest)
            assertEquals(Color.White, surfaceContainerLow)
            assertEquals(Color.White, surfaceContainer)
            assertEquals(Color(0xFFEEEEEE), surfaceContainerHigh)
            assertEquals(Color(0xFFE4E4E4), surfaceContainerHighest)
            assertEquals(Color.White, surfaceBright)
            assertEquals(Color(0xFFCFCFD2), surfaceDim)
            assertEquals(Color(0xFF1C1C1C), inverseSurface)
            assertEquals(Color.White, inverseOnSurface)
            assertEquals(Color(0xFFE7FC6E), inversePrimary)
            assertEquals(Color(0xFFCFCFD2), scrim)
            assertEquals(Color.Transparent, surfaceTint)
        }
        assertEquals(Color(0xFF4F5C00), values.extra.primaryText)
        assertEquals(Color(0xFFCFCFD2), values.extra.dim)
        assertEquals(Color(0xFFC4142F), values.extra.errorText)
        assertEquals(Color(0xFF7EC8FF), values.extra.blueChip)
        assertEquals(Color(0xFFFF8FA8), values.extra.pinkChip)
        assertOpaque(values)
    }

    @Test
    fun typographyShapesAndSpacingMatchTheExtractedScale() {
        val values = capture(dark = true)
        assertEquals(36.sp, values.typography.displaySmall.fontSize)
        assertEquals(43.2.sp, values.typography.displaySmall.lineHeight)
        assertEquals(28.sp, values.typography.headlineLarge.fontSize)
        assertEquals(33.6.sp, values.typography.headlineLarge.lineHeight)
        assertEquals(22.sp, values.typography.titleLarge.fontSize)
        assertEquals(26.4.sp, values.typography.titleLarge.lineHeight)
        assertEquals(13.sp, values.typography.bodySmall.fontSize)
        assertEquals(15.6.sp, values.typography.bodySmall.lineHeight)
        assertEquals(12.sp, values.typography.labelSmall.fontSize)
        assertEquals(14.4.sp, values.typography.labelSmall.lineHeight)
        assertEquals(15.sp, values.extraTypography.buttonLarge.fontSize)
        assertEquals(18.sp, values.extraTypography.amount.fontSize)
        assertEquals(20.sp, values.extraTypography.screenTitle.fontSize)

        val textStyles =
            listOf(
                values.typography.displaySmall,
                values.typography.headlineLarge,
                values.typography.titleLarge,
                values.typography.bodyLarge,
                values.typography.bodySmall,
                values.typography.labelSmall,
                values.extraTypography.buttonLarge,
                values.extraTypography.amount,
                values.extraTypography.screenTitle,
                values.extraTypography.identifier,
            )
        textStyles.forEach(::assertCssTextMetrics)
        assertTrue(values.typography.bodyLarge.fontFamily != FontFamily.Default)
        assertEquals(
            values.typography.bodyLarge.fontFamily,
            values.extraTypography.buttonLarge.fontFamily,
        )
        assertTrue(
            values.typography.bodyLarge.fontFamily != values.extraTypography.identifier.fontFamily
        )

        assertEquals(RoundedCornerShape(4.dp), values.shapes.extraSmall)
        assertEquals(RoundedCornerShape(8.dp), values.shapes.small)
        assertEquals(RoundedCornerShape(12.dp), values.shapes.medium)
        assertEquals(RoundedCornerShape(16.dp), values.shapes.large)
        assertEquals(RoundedCornerShape(28.dp), values.shapes.extraLarge)
        assertEquals(
            listOf(4.dp, 8.dp, 12.dp, 16.dp, 20.dp, 24.dp, 28.dp),
            with(values.radii) { listOf(xs, sm, md, lg, xl, xxl, sheet) },
        )
        assertEquals(
            listOf(
                2.dp,
                4.dp,
                6.dp,
                8.dp,
                10.dp,
                12.dp,
                14.dp,
                16.dp,
                20.dp,
                24.dp,
                28.dp,
                32.dp,
            ),
            with(values.spacing) {
                listOf(xxs, xs, sm, md, mdPlus, lg, lgPlus, xl, xxl, xxxl, huge, jumbo)
            },
        )
    }

    @Test
    fun materialDefaultsCannotTintElevateOrEnlargeCompactControls() {
        val values = capture(dark = true)
        assertEquals(Color.Transparent, values.scheme.surfaceTint)
        assertEquals(false, values.tonalElevationEnabled)
        assertEquals(Dp.Unspecified, values.minimumInteractiveSize)
        assertEquals(values.extra.primaryText, values.rippleColor)
    }

    @Test
    fun productionEntryPointUsesTheApprovedThemeAndRejectsParameterlessDefaults() {
        val root =
            File(
                checkNotNull(System.getProperty("seekervault.repoRoot")) {
                    "run this test through Gradle"
                }
            )
        val source =
            File(
                    root,
                    "apps/android/app/src/main/java/io/github/brrenat/seekervault/MainActivity.kt",
                )
                .readText()
        assertTrue(source.contains("setContent {\n            SeekerTheme {"))
        assertTrue(!Regex("""darkColorScheme\s*\(\s*\)""").containsMatchIn(source))
        assertTrue(!Regex("""lightColorScheme\s*\(\s*\)""").containsMatchIn(source))
    }

    @Test
    fun bottomBarExposesTheActiveDestinationAsSelected() {
        val selected = mutableStateOf("home")
        compose.setContent {
            SeekerTheme {
                SeekerBottomBar(
                    destinations =
                        listOf(
                            BottomDestination("home", Icons.Outlined.Public, "Home"),
                            BottomDestination("wallet", Icons.Outlined.Public, "Wallet"),
                        ),
                    selected = selected.value,
                    onSelect = { selected.value = it },
                )
            }
        }

        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Wallet").assertIsNotSelected().performClick()
        compose.onNodeWithText("Home").assertIsNotSelected()
        compose.onNodeWithText("Wallet").assertIsSelected()
    }

    @Test
    fun solidDialogBodyScrollsWithoutMovingItsActions() {
        compose.setContent {
            SeekerTheme {
                SolidDialog(
                    title = "Confirm",
                    body = {
                        Column {
                            repeat(20) { Text("Explanation line $it") }
                            Text("Last explanation", Modifier.testTag("dialog-last-line"))
                        }
                    },
                    actions = { Text("Keep this action visible") },
                )
            }
        }

        val lastLine = compose.onNodeWithTag("dialog-last-line")
        lastLine.assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep this action visible").assertIsDisplayed()
    }

    @Test
    fun criticalButtonLabelsWrapAtLargeText() {
        compose.setContent {
            SeekerTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 2f)
                ) {
                    SeekerButton(
                        text = "Approve and send\ndespite warnings",
                        onClick = {},
                        modifier = Modifier.width(180.dp).testTag("large-action"),
                    )
                }
            }
        }

        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val action =
            compose
                .onNodeWithText("Approve and send\ndespite warnings", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
        assertTrue(requireNotNull(action).invoke(layouts))
        val layout = layouts.single()
        assertTrue(
            "the complete action must wrap instead of ellipsizing: lines=${layout.lineCount}, size=${layout.size}, overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}",
            layout.lineCount > 1,
        )
    }

    private fun capture(dark: Boolean): Captured {
        var captured: Captured? = null
        compose.setContent {
            SeekerTheme(darkTheme = dark) {
                val value =
                    Captured(
                        MaterialTheme.colorScheme,
                        SeekerTheme.colors,
                        MaterialTheme.typography,
                        MaterialTheme.shapes,
                        SeekerTheme.spacing,
                        SeekerTheme.radii,
                        SeekerTheme.dimensions,
                        SeekerTheme.typography,
                        LocalTonalElevationEnabled.current,
                        LocalMinimumInteractiveComponentSize.current,
                        requireNotNull(LocalRippleConfiguration.current).color,
                    )
                SideEffect { captured = value }
            }
        }
        compose.waitForIdle()
        return requireNotNull(captured)
    }

    private fun assertOpaque(values: Captured) {
        val colors =
            listOf(
                values.scheme.primary,
                values.scheme.primaryContainer,
                values.scheme.tertiary,
                values.scheme.tertiaryContainer,
                values.scheme.error,
                values.scheme.errorContainer,
                values.scheme.surface,
                values.scheme.surfaceContainer,
                values.scheme.surfaceContainerHigh,
                values.scheme.surfaceContainerHighest,
                values.scheme.outline,
                values.scheme.outlineVariant,
                values.scheme.scrim,
                values.extra.primaryText,
                values.extra.dim,
                values.extra.errorText,
                values.extra.blueChip,
                values.extra.onBlueChip,
                values.extra.violetChip,
                values.extra.onVioletChip,
                values.extra.tealChip,
                values.extra.onTealChip,
                values.extra.pinkChip,
                values.extra.onPinkChip,
                values.extra.sandChip,
                values.extra.onSandChip,
            )
        assertEquals(colors, colors.filter { it.alpha == 1f })
    }

    private fun assertCssTextMetrics(style: TextStyle) {
        assertEquals(0.sp, style.letterSpacing)
        assertEquals(PlatformTextStyle(includeFontPadding = false), style.platformStyle)
        assertEquals(LineHeightStyle.Alignment.Center, style.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Trim.None, style.lineHeightStyle?.trim)
        assertEquals(LineHeightStyle.Mode.Fixed, style.lineHeightStyle?.mode)
    }

    private data class Captured(
        val scheme: ColorScheme,
        val extra: SeekerColors,
        val typography: Typography,
        val shapes: Shapes,
        val spacing: SeekerSpacing,
        val radii: SeekerRadii,
        val dimensions: SeekerDimensions,
        val extraTypography: SeekerExtraTypography,
        val tonalElevationEnabled: Boolean,
        val minimumInteractiveSize: Dp,
        val rippleColor: Color,
    )
}
