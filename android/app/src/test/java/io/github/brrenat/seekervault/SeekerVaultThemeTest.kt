package io.github.brrenat.seekervault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
