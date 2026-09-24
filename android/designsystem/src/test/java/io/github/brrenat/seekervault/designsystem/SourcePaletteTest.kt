package io.github.brrenat.seekervault.designsystem

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePaletteTest {
    @Test
    fun `captured sources map to their design palette slots`() {
        assertEquals(SourcePaletteSlot.Teal, sourcePaletteSlot("CopyTrading demo"))
        assertEquals(SourcePaletteSlot.Pink, sourcePaletteSlot("Jupiter Prediction demo"))
        assertEquals(SourcePaletteSlot.Blue, sourcePaletteSlot("hermes-box"))
        assertEquals(SourcePaletteSlot.Violet, sourcePaletteSlot("runner-node"))
        assertEquals(SourcePaletteSlot.Tangerine, sourcePaletteSlot("studio-mac"))
    }

    @Test
    fun `source palette assignment ignores casing and remains stable`() {
        assertEquals(sourcePaletteSlot("Hermes-Box"), sourcePaletteSlot("HERMES-BOX"))
        assertEquals(SourcePaletteSlot.Blue, sourcePaletteSlot("hermes-box"))
    }

    @Test
    fun `an explicit colour overrides the name hash`() {
        assertEquals(SourcePaletteSlot.Tangerine, sourcePaletteSlot("studio-mac"))
        assertEquals(SourcePaletteSlot.Blue, SourceColour.Sky.toSlot())
        assertNotEquals(sourcePaletteSlot("studio-mac"), SourceColour.Sky.toSlot())
    }

    @Test
    fun `every marker pair meets the text contrast minimum`() {
        val pairs =
            listOf(
                0xFF7A1A to 0x2E1200,
                0x7EC8FF to 0x00243D,
                0xC9A7FF to 0x241042,
                0x4FDCC0 to 0x00312A,
                0xFF8FA8 to 0x3D0014,
                0xE3CF95 to 0x322400,
            )
        pairs.forEach { (fill, ink) ->
            assertTrue(
                "contrast ${contrast(fill, ink)} for ${fill.toString(16)}",
                contrast(fill, ink) >= 4.5,
            )
        }
    }
}

private fun contrast(fill: Int, ink: Int): Double {
    val lighter = maxOf(luminance(fill), luminance(ink))
    val darker = minOf(luminance(fill), luminance(ink))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun luminance(rgb: Int): Double {
    fun channel(value: Int): Double {
        val srgb = value / 255.0
        return if (srgb <= 0.03928) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((rgb shr 16) and 0xFF) +
        0.7152 * channel((rgb shr 8) and 0xFF) +
        0.0722 * channel(rgb and 0xFF)
}
