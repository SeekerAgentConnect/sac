# request-tile

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `RequestTile`
- **Allowed dependencies:** `source-chip`

One pending item in the Home rail. Rail and centred presentations are states of this single component.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

**SEE-183 rebuild.** The tile now follows the ticket's reference
([`docs/design/request-tile/`](../../../docs/design/request-tile/README.md)), not the generated
`kind=… state=…` captures below. Those captures still show the old tile (Signal label, outcome
number, caption, text verdict pill, server chip in the header) until the export is refreshed through
[UPDATING.md](../../UPDATING.md); `designCompare` pairs will differ until then.

## Kotlin API

```kotlin
enum class RequestTileKind {
    Acknowledgement, PredictionSignal, SwapSignal, SignatureRequest, Transfer
}
enum class RequestTileRailState { InRail, Centred }
enum class RequestTileTitleSize { Large, Medium, Small }   // of(title): ≤34 / ≤60 / longer
enum class RequestTileStatus { Ok, Warning }               // of(model), null when unchecked

data class RequestTileModel(
    val title: String,          // the question, or amount + unit ("5 SOL", "74 bytes")
    val sourceName: String,     // the footer chip; the only place the tile names its server
    val time: String,           // already formatted ("9:37 PM")
    val warningCount: Int,
    val sourceColour: SourceColour? = null,
    val unchecked: Boolean = false,
)

fun RequestTileModel.statusLabel(): String?   // "In rules", "1 warning", "N warnings", or null

@Composable
fun RequestTile(
    model: RequestTileModel,
    kind: RequestTileKind,
    railState: RequestTileRailState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`kind` maps `ack/pred/sig/sign/tx` to the unambiguous enum values above; `state` maps to `RequestTileRailState`. There is one component and one source helper for both states.

## Layout (SEE-183)

- Fixed 214×200dp including 16dp padding, radius `xl` (20), a column: header, title, footer, with
  at least `mdPlus` (10) between them and the title centred in the space left (CSS
  `justify-content: space-between; gap: 10px`).
- **Header:** kind icon 20dp, `md` gap, kind label `bodyMedium` (14/400, one line, ellipsis,
  weighted), status badge.
- **Status badge:** 28dp high, at least 28dp wide, fully rounded, 18dp icon. Within rules: `check`
  on `statusOk` (`oklch(0.6 0.15 150)` = `#25984D`) in white. Warnings: `priority_high` on
  `orangeContainer` (`#FF7A1A`) in `onOrangeContainer` (`#2E1200`); from two warnings the count
  follows the icon in `badgeCount` (13/600) with 4dp/8dp start/end padding. The same colours on
  both tile backgrounds. Its accessible label is the `statusLabel()`. `unchecked` with no
  warnings shows no badge (SEE-181): nothing checked is neither a pass nor a warning.
- **Title:** the whole text, never clamped. 22sp (`titleLarge`) up to 34 characters, 17sp
  (`tileTitle`) up to 60, 15sp (`buttonLarge` at 400) beyond; line height 1.2, weight 400,
  `LineBreak.Paragraph` for `text-wrap: pretty`.
- **Footer:** the server chip (`SourceChipSize.Small`: 22dp high, 8dp padding, radius `sm`,
  `labelMedium` 12/500, ellipsis) in a weighted slot, then the time (12/400) kept whole. The chip
  truncates before the time does.
- **Colours:** in the rail `surface1`, white text, time `onSurfaceVariant` (`#CACACA`); centred
  `limeContainer` (`#C2E60F`) with `onLimeContainer` (`#1B1B1B`) text and time.

## Captured variants

Generated from the Stage 7.2 export (pre-SEE-183, stale until the next refresh):

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `kind=ack state=centred` | [HTML](./kind-ack-state-centred.html) | [PNG](./kind-ack-state-centred.png) |
| `kind=ack state=in-rail` | [HTML](./kind-ack-state-in-rail.html) | [PNG](./kind-ack-state-in-rail.png) |
| `kind=pred state=centred` | [HTML](./kind-pred-state-centred.html) | [PNG](./kind-pred-state-centred.png) |
| `kind=pred state=in-rail` | [HTML](./kind-pred-state-in-rail.html) | [PNG](./kind-pred-state-in-rail.png) |
| `kind=sig state=centred` | [HTML](./kind-sig-state-centred.html) | [PNG](./kind-sig-state-centred.png) |
| `kind=sig state=in-rail` | [HTML](./kind-sig-state-in-rail.html) | [PNG](./kind-sig-state-in-rail.png) |
| `kind=sign state=centred` | [HTML](./kind-sign-state-centred.html) | [PNG](./kind-sign-state-centred.png) |
| `kind=sign state=in-rail` | [HTML](./kind-sign-state-in-rail.html) | [PNG](./kind-sign-state-in-rail.png) |
| `kind=tx state=centred` | [HTML](./kind-tx-state-centred.html) | [PNG](./kind-tx-state-centred.png) |
| `kind=tx state=in-rail` | [HTML](./kind-tx-state-in-rail.html) | [PNG](./kind-tx-state-in-rail.png) |

SEE-183 adds these previews with no generated reference yet (they follow
`docs/design/request-tile/request-tile.html`): `state=centred|in-rail status=ok|warning|warnings`,
`title=l`, `title=m`, `title=s`, and `title=longest` (a 98-character question).

## Builder note

> mkCard(key, centred). One tile for every kind of pending item; a signal differs by the Signal label and the feed-coloured source chip. 214×200 of content plus 16px of padding on each side, so the box measures 246×232. Only ever inside the Home rail.

SEE-183 supersedes this note: no Signal label, the server chip is in the footer, and the box itself
is 214×200 with the padding inside it.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The raw key `sig` means swap signal while `sign` means signature request; never carry those ambiguous abbreviations into Kotlin.
