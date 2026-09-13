package io.github.brrenat.seekervault.ui

import io.github.brrenat.seekervault.inbox.RequestKind
import io.github.brrenat.seekervault.inbox.stakeWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design system's own rules (SEE-57): the ones a screen could get wrong quietly.
 *
 * Colours, radii and spacings are values, and a test that restated them would only say the same
 * thing twice. What is worth holding here is what they *mean*: that the theme is the one ground the
 * app draws on, that an identifier is shortened where it can't mislead, and that a tile is lit by
 * how much the request could cost rather than by what it is called.
 */
class DesignSystemTest {
    @Test
    fun theThemeIsTheGroundAndTheInkAndNothingElse() {
        assertEquals(Nocturne.Bg, NocturneColors.background)
        assertEquals(Nocturne.Text, NocturneColors.onBackground)
        assertEquals(Nocturne.Accent, NocturneColors.primary)
    }

    @Test
    fun glassIsInkMixedIntoTransparencyRatherThanANewColour() {
        // Every fill, border and highlight in the design is a percentage of the ink. A surface
        // built from some other colour would drift away from the ground under it.
        for (alpha in listOf(0.04f, 0.09f, 0.15f)) {
            val fill = Nocturne.text(alpha)
            assertEquals(Nocturne.Text.red, fill.red, 0.005f)
            assertEquals(Nocturne.Text.green, fill.green, 0.005f)
            assertEquals(Nocturne.Text.blue, fill.blue, 0.005f)
            assertEquals(alpha, fill.alpha, 0.005f)
        }
    }

    @Test
    fun anIdentifierIsShortenedInTheMiddleAndNeverAtOneEnd() {
        val address = "FyfWsSZqhiLWMeEBhTsxreEJrGxUbAuPDoZ6pQMNSpEA"
        val short = truncateMiddle(address)
        assertEquals("FyfWsS…SpEA", short)
        // Both ends survive, so two addresses that share a prefix can still be told apart.
        assertTrue(short.startsWith(address.take(6)))
        assertTrue(short.endsWith(address.takeLast(4)))
        // Something already short enough is left exactly as it is.
        assertEquals("devnet", truncateMiddle("devnet"))
    }

    @Test
    fun anAvatarIsAtMostTwoInitialsAndNeverEmpty() {
        assertEquals("HM", initialsOf("Home Mac"))
        assertEquals("S", initialsOf("studio"))
        assertEquals("SM", initialsOf("studio-mac"))
        assertEquals("?", initialsOf("   "))
    }

    @Test
    fun aTileIsLitByWhatTheRequestCouldCost() {
        // The accent ring on a carousel tile tracks the stake, so the loudest tile on the screen is
        // always the one that could move funds.
        assertTrue(stakeWeight(RequestKind.Transfer) > stakeWeight(RequestKind.Signature))
        assertTrue(stakeWeight(RequestKind.Signature) > stakeWeight(RequestKind.Acknowledge))
        for (kind in RequestKind.entries) {
            assertTrue(kind.name, stakeWeight(kind) in 0f..1f)
        }
    }

    @Test
    fun theGesturesAgreeWithTheDesignsOwnThresholds() {
        // A swipe answers past the same distance its cue is fully revealed at, so a row that looks
        // answered is answered.
        assertEquals(Gesture.RowThreshold, Gesture.RowCueTravel)
        // A tap is shorter than the shortest drag that means anything.
        assertTrue(Gesture.TapSlop < Gesture.AxisLock)
        assertTrue(Gesture.AxisLock < Gesture.PanelThreshold)
        assertTrue(Gesture.PanelThreshold < Gesture.PanelDismiss)
    }
}
