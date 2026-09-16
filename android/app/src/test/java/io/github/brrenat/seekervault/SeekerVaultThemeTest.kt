package io.github.brrenat.seekervault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        }
        assertEquals(Color(0xFFE7FC6E), values.extra.primaryText)
        assertEquals(Color(0xFF0A0A0A), values.extra.dim)
        assertEquals(Color(0xFFF83959), values.extra.errorText)
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
        }
        assertEquals(Color(0xFF4F5C00), values.extra.primaryText)
        assertEquals(Color(0xFFCFCFD2), values.extra.dim)
        assertEquals(Color(0xFFC4142F), values.extra.errorText)
        assertOpaque(values)
    }

    @Test
    fun typographyAndShapesMatchTheApprovedV4Scale() {
        val values = capture(dark = true)
        assertEquals(36.sp, values.typography.displaySmall.fontSize)
        assertEquals(42.sp, values.typography.displaySmall.lineHeight)
        assertEquals(28.sp, values.typography.headlineLarge.fontSize)
        assertEquals(34.sp, values.typography.headlineLarge.lineHeight)
        assertEquals(22.sp, values.typography.titleLarge.fontSize)
        assertEquals(28.sp, values.typography.titleLarge.lineHeight)
        assertEquals(13.sp, values.typography.bodySmall.fontSize)
        assertEquals(18.sp, values.typography.bodySmall.lineHeight)
        assertEquals(12.sp, values.typography.labelSmall.fontSize)
        assertEquals(16.sp, values.typography.labelSmall.lineHeight)
        assertEquals(RoundedCornerShape(8.dp), values.shapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), values.shapes.small)
        assertEquals(RoundedCornerShape(16.dp), values.shapes.medium)
        assertEquals(RoundedCornerShape(24.dp), values.shapes.large)
        assertEquals(RoundedCornerShape(28.dp), values.shapes.extraLarge)
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
                    "android/app/src/main/java/io/github/brrenat/seekervault/MainActivity.kt",
                )
                .readText()
        assertTrue(source.contains("setContent {\n            SeekerVaultTheme {"))
        assertTrue(!Regex("""darkColorScheme\s*\(\s*\)""").containsMatchIn(source))
        assertTrue(!Regex("""lightColorScheme\s*\(\s*\)""").containsMatchIn(source))
    }

    @Test
    fun bottomBarExposesTheActiveDestinationAsSelected() {
        val selected = mutableStateOf("home")
        compose.setContent {
            SeekerVaultTheme {
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
            SeekerVaultTheme {
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
            SeekerVaultTheme {
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
            SeekerVaultTheme(darkTheme = dark) {
                val value =
                    Captured(
                        MaterialTheme.colorScheme,
                        SeekerTheme.colors,
                        MaterialTheme.typography,
                        MaterialTheme.shapes,
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
            )
        assertEquals(colors, colors.filter { it.alpha == 1f })
    }

    private data class Captured(
        val scheme: ColorScheme,
        val extra: SeekerExtraColors,
        val typography: Typography,
        val shapes: Shapes,
    )
}
