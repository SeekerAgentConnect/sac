package io.github.brrenat.seekervault.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class RequestTileTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun titleSizeFollowsTheTitleLength() {
        assertEquals(RequestTileTitleSize.Large, RequestTileTitleSize.of("x".repeat(34)))
        assertEquals(RequestTileTitleSize.Medium, RequestTileTitleSize.of("x".repeat(35)))
        assertEquals(RequestTileTitleSize.Medium, RequestTileTitleSize.of("x".repeat(60)))
        assertEquals(RequestTileTitleSize.Small, RequestTileTitleSize.of("x".repeat(61)))
    }

    @Test
    fun statusIsACheckWithinRulesAWarningOtherwiseAndNothingWhenUnchecked() {
        assertEquals("In rules", model(warnings = 0).statusLabel())
        assertEquals("1 warning", model(warnings = 1).statusLabel())
        assertEquals("3 warnings", model(warnings = 3).statusLabel())
        assertNull(model(warnings = 0, unchecked = true).statusLabel())
        // Warnings found are shown even when the rest could not be checked.
        assertEquals("2 warnings", model(warnings = 2, unchecked = true).statusLabel())
    }

    @Test
    fun theCountAppearsBesideTheBadgeOnlyFromTwoWarnings() {
        show(model(warnings = 1))
        val one = compose.onNodeWithContentDescription("1 warning", useUnmergedTree = true)
        assertEquals(one.bounds().width, one.bounds().height, 0.5f)

        show(model(warnings = 3))
        val badge = compose.onNodeWithContentDescription("3 warnings", useUnmergedTree = true)
        badge.assertExists()
        assertTrue(badge.bounds().width > badge.bounds().height)

        show(model(warnings = 0))
        val ok = compose.onNodeWithContentDescription("In rules", useUnmergedTree = true)
        assertEquals(ok.bounds().width, ok.bounds().height, 0.5f)
    }

    @Test
    fun theTileIsFixedAndNeverTruncatesItsTitleOrTime() {
        listOf(
                "Baltimore Orioles",
                "What price will Bitcoin hit on September 25?",
                "Bitcoin Up or Down - September 25, 4:00PM-8:00PM ET · Polymarket hourly",
            )
            .forEach { title ->
                show(model(warnings = 0, title = title, source = "Very Long Server Name MCP"))
                val tile = compose.onNodeWithTag(TILE)
                tile.assertWidthIsEqualTo(214.dp).assertHeightIsEqualTo(200.dp)
                val bounds = tile.bounds()

                val titleNode = compose.onNodeWithText(title, useUnmergedTree = true)
                titleNode.assertWholeText(title)
                assertTrue(title, bounds.contains(titleNode.bounds()))

                val time = compose.onNodeWithText("9:37 PM", useUnmergedTree = true)
                time.assertWholeText("9:37 PM")
                assertTrue(bounds.contains(time.bounds()))
                // The long server name gives way, not the time beside it.
                val chip =
                    compose.onNodeWithText("Very Long Server Name MCP", useUnmergedTree = true)
                assertTrue(chip.textLayout().isLineEllipsized(0))
                assertTrue(chip.bounds().right <= time.bounds().left)
            }
    }

    private var shown by mutableStateOf<RequestTileModel?>(null)

    private fun show(model: RequestTileModel) {
        val first = shown == null
        shown = model
        if (!first) {
            compose.waitForIdle()
            return
        }
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                RequestTile(
                    model = checkNotNull(shown),
                    kind = RequestTileKind.PredictionSignal,
                    railState = RequestTileRailState.Centred,
                    onClick = {},
                    modifier = Modifier.testTag(TILE),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun model(
        warnings: Int,
        unchecked: Boolean = false,
        title: String = "5 SOL",
        source: String = "Polymarket",
    ) =
        RequestTileModel(
            title = title,
            sourceName = source,
            time = "9:37 PM",
            warningCount = warnings,
            unchecked = unchecked,
        )

    /** Every character laid out, nothing ellipsized, nothing below the box. */
    private fun SemanticsNodeInteraction.assertWholeText(text: String) {
        val layout = textLayout()
        val last = layout.lineCount - 1
        assertEquals(text, text.length, layout.getLineEnd(last))
        assertFalse(text, layout.isLineEllipsized(last))
        assertFalse(text, layout.didOverflowHeight)
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.single()
    }

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left - 0.5f &&
            other.top >= top - 0.5f &&
            other.right <= right + 0.5f &&
            other.bottom <= bottom + 0.5f

    private companion object {
        const val TILE = "tile"
    }
}
