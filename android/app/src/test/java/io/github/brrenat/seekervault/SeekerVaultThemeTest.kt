package io.github.brrenat.seekervault

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekerVaultThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun darkSchemeMatchesTheV4TokensAndEveryTokenIsOpaque() {
        val values = capture(dark = true)
        assertEquals(Color(0xFF121212), values.scheme.surface)
        assertEquals(Color(0xFF1C1C1C), values.scheme.surfaceContainer)
        assertEquals(Color(0xFF232323), values.scheme.surfaceContainerHigh)
        assertEquals(Color(0xFF2E2E2E), values.scheme.surfaceContainerHighest)
        assertEquals(Color(0xFFE7FC6E), values.scheme.primary)
        assertEquals(Color(0xFFFF7A1A), values.scheme.tertiaryContainer)
        assertEquals(Color(0xFFF83959), values.scheme.error)
        assertEquals(Color(0xFFE7FC6E), values.extra.primaryText)
        assertOpaque(values)
    }

    @Test
    fun lightSchemeMatchesTheV4TokensAndTypeScale() {
        val values = capture(dark = false)
        assertEquals(Color(0xFFF7F7F7), values.scheme.surface)
        assertEquals(Color.White, values.scheme.surfaceContainer)
        assertEquals(Color(0xFFEEEEEE), values.scheme.surfaceContainerHigh)
        assertEquals(Color(0xFFE4E4E4), values.scheme.surfaceContainerHighest)
        assertEquals(Color(0xFFF1FFA0), values.scheme.primary)
        assertEquals(Color(0xFFE9FF7A), values.scheme.primaryContainer)
        assertEquals(Color(0xFF4F5C00), values.extra.primaryText)
        assertEquals(36.sp, values.typography.displaySmall.fontSize)
        assertEquals(28.sp, values.typography.headlineLarge.fontSize)
        assertEquals(22.sp, values.typography.titleLarge.fontSize)
        assertOpaque(values)
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
    )
}
