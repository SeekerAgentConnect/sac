package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.designsystem.SourceColour

/**
 * The marker colour a connection carries everywhere it appears (SEE-83): the avatar on the paired
 * servers list and the label pill on its waiting-for-you tiles.
 *
 * There is no free picker on purpose: six colours are what the design ships, all readable against
 * their ink in both themes, and a fixed palette is what lets a colour mean "this server". The
 * declaration order is the assignment order and the order the connection sheet offers them in.
 *
 * The owner's choice is stored on the [Connection] record and never leaves the phone; a server
 * cannot influence it and never learns it.
 */
enum class ServerColour {
    Tangerine,
    Sky,
    Violet,
    Teal,
    Rose,
    Sand,
}

/**
 * The colour a new connection takes: the first palette entry no live connection holds. Once every
 * entry is taken, assignment continues through the palette in order, so a colour is reused only
 * because the palette is smaller than the list. Removing a connection returns its colour to the
 * pool; a disconnected server that is still listed keeps the colour the owner can still see.
 */
fun nextServerColour(existing: Collection<Connection>): ServerColour {
    val coloured = existing.mapNotNull { it.colour }
    val used = coloured.toSet()
    return ServerColour.entries.firstOrNull { it !in used }
        ?: ServerColour.entries[coloured.size % ServerColour.entries.size]
}

/** The same six colours, named as the design system shows them. */
fun ServerColour.sourceColour(): SourceColour =
    when (this) {
        ServerColour.Tangerine -> SourceColour.Tangerine
        ServerColour.Sky -> SourceColour.Sky
        ServerColour.Violet -> SourceColour.Violet
        ServerColour.Teal -> SourceColour.Teal
        ServerColour.Rose -> SourceColour.Rose
        ServerColour.Sand -> SourceColour.Sand
    }
