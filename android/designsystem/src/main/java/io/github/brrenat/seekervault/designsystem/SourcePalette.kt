package io.github.brrenat.seekervault.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import java.util.Locale

/** The six source-identity colours supplied by the design guide. */
internal enum class SourcePaletteSlot {
    Pink,
    Sand,
    Violet,
    Tangerine,
    Teal,
    Blue,
}

@Immutable internal data class SourcePaletteColors(val container: Color, val content: Color)

/**
 * Keeps a source on the same colour everywhere without storing presentation state.
 *
 * Lowercase UTF-8 FNV-1a is stable across processes and platforms. Sources that collide share one
 * of the six design-token pairs; identity text, rather than colour alone, continues to distinguish
 * them.
 */
internal fun sourcePaletteSlot(sourceName: String): SourcePaletteSlot {
    var hash = FnvOffsetBasis
    for (byte in sourceName.lowercase(Locale.ROOT).encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toUInt()) * FnvPrime
    }
    return SourcePaletteSlot.entries[(hash % SourcePaletteSlot.entries.size.toUInt()).toInt()]
}

@Composable
internal fun sourcePaletteColors(sourceName: String): SourcePaletteColors {
    val colors = SeekerTheme.colors
    return when (sourcePaletteSlot(sourceName)) {
        SourcePaletteSlot.Pink -> SourcePaletteColors(colors.pinkChip, colors.onPinkChip)
        SourcePaletteSlot.Sand -> SourcePaletteColors(colors.sandChip, colors.onSandChip)
        SourcePaletteSlot.Violet -> SourcePaletteColors(colors.violetChip, colors.onVioletChip)
        SourcePaletteSlot.Tangerine ->
            SourcePaletteColors(colors.tangerineChip, colors.onTangerineChip)
        SourcePaletteSlot.Teal -> SourcePaletteColors(colors.tealChip, colors.onTealChip)
        SourcePaletteSlot.Blue -> SourcePaletteColors(colors.blueChip, colors.onBlueChip)
    }
}

private const val FnvOffsetBasis = 0x811C9DC5u
private const val FnvPrime = 0x01000193u
