# Server colour

SEE-83 gives every connection a marker from a fixed palette of six. The phone chooses it. A server
never sends a colour and never learns the one stored here.

## Palette

| Name | Fill | Ink |
| --- | --- | --- |
| Tangerine | `#ff7a1a` | `#2e1200` |
| Sky | `#7ec8ff` | `#00243d` |
| Violet | `#c9a7ff` | `#241042` |
| Teal | `#4fdcc0` | `#00312a` |
| Rose | `#ff8fa8` | `#3d0014` |
| Sand | `#e3cf95` | `#322400` |

These are the design system's source-chip pairs. Ink on fill is at least 4.5:1 in both themes,
because the pairs do not change between light and dark. There is no free picker.

## Where it is kept

`ServerColour` on `Connection` is written in the connection file at store version 6. A file from
before that version, or a colour name this build does not know, is read as no colour. On startup
the repository assigns the first unused entry, oldest connection first, and writes it back.
Pairing and adding a feed do the same for the new record. Once every colour is in use, assignment
continues through the palette in order.

Removing a connection deletes the record, so the colour is free for the next one. A server that is
still listed keeps its colour in every state: connected, unreachable, or disconnected.

Changing the colour in the connection sheet writes the record and replaces the in-memory list. Home
reads that list for the paired-server avatar and for each waiting-for-you pill, so both update
with the sheet.

## Where it shows

- **Connection sheet.** A Colour card above the facts: a 40dp avatar, the line `<Name> · marks
  this server everywhere`, and six 40dp swatches. The selected swatch draws a check inside a 4dp
  colour border. A colour another connection already uses is drawn at 40% opacity with a link
  glyph and the accessible name `<Name> · used by <other server>`. It can still be chosen. The
  captured connection HTML draws that selection as a 2px surface gap plus a 4px colour shadow
  outside the 40px fill; the sheet keeps the 4dp border already in the recorded screen so the
  baseline does not move.
- **Paired servers.** The initials avatar uses that fill and ink.
- **Waiting for you.** The server-name pill under the kind row uses the same fill and ink. The
  selected tile stays on the lime container. The pill is at most 196dp wide inside the 246dp tile,
  ellipsises, and the kind label ellipsises on its own row.

The pill's radius is the source chip's 12dp (`radii.md`). The ticket text says 10px; the captured
request-tile HTML and the radius scale both use 12, and 10 is not a radius token. The tile itself
is the captured 246dp card. The pill's cap is under 204dp, which is the bound the acceptance
names.

Colour is not shown on request rows, Activity rows, or the review sheet.
