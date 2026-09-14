package io.github.brrenat.seekervault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
    fun darkThemeUsesStockMaterial3Defaults() {
        val values = capture(dark = true)
        assertStockDefaults(values, darkColorScheme())
    }

    @Test
    fun lightThemeUsesStockMaterial3Defaults() {
        val values = capture(dark = false)
        assertStockDefaults(values, lightColorScheme())
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
                        MaterialTheme.typography,
                        MaterialTheme.shapes,
                    )
                SideEffect { captured = value }
            }
        }
        compose.waitForIdle()
        return requireNotNull(captured)
    }

    private fun assertStockDefaults(values: Captured, expectedScheme: ColorScheme) {
        assertEquals(expectedScheme.primary, values.scheme.primary)
        assertEquals(expectedScheme.tertiary, values.scheme.tertiary)
        assertEquals(expectedScheme.error, values.scheme.error)
        assertEquals(expectedScheme.surface, values.scheme.surface)
        assertEquals(expectedScheme.surfaceContainer, values.scheme.surfaceContainer)
        assertEquals(expectedScheme.onSurface, values.scheme.onSurface)
        val expectedTypography = Typography()
        assertEquals(expectedTypography.displaySmall, values.typography.displaySmall)
        assertEquals(expectedTypography.titleLarge, values.typography.titleLarge)
        assertEquals(expectedTypography.bodyMedium, values.typography.bodyMedium)
        val expectedShapes = Shapes()
        assertEquals(expectedShapes.extraSmall, values.shapes.extraSmall)
        assertEquals(expectedShapes.medium, values.shapes.medium)
        assertEquals(expectedShapes.extraLarge, values.shapes.extraLarge)
    }

    private data class Captured(
        val scheme: ColorScheme,
        val typography: Typography,
        val shapes: Shapes,
    )
}
