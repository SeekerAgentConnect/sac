package io.github.brrenat.seekervault.designsystem

import org.junit.Assert.assertEquals
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
}
