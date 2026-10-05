# Home request tile

Each pending request and signal in the Home rail ("Waiting for you") is one `RequestTile`
(SEE-183). The tile says three things: what kind of request it is and whether it is within your
rules, what is being asked, and which server asked and when. Everything else — the side and stake,
the recipient, the provider and the risks — is on the review it opens.

## Structure

A fixed 214×200dp card in three rows, with the title centred in what the other two leave:

1. **Header** — the kind icon and label (Acknowledge, Prediction, Swap, Signature, Transfer) and the
   status badge.
2. **Title** — the request's question, or its amount and unit when it has none ("5 SOL",
   "74 bytes"). Always shown whole: 22sp up to 34 characters, 17sp up to 60, 15sp beyond.
3. **Footer** — the server chip in the connection's colour (SEE-83), and the time the request
   arrived. A long server name ellipsizes; the time never does.

The centred tile is lime with dark ink; tiles in the rail are `surface1` with white text and a grey
time.

## Status badge

| Rules | Badge | Spoken label |
| --- | --- | --- |
| Configured checks passed | green check | "In rules" |
| One warning | orange `!` | "1 warning" |
| Two or more | orange `!` and the count | "N warnings" |
| Nothing checked (no rules, not yet assessed, or a signal with no order prepared) | none | — |

The warning count is the one the review's verdict card shows (`ReviewVerdict`, SEE-158/SEE-180);
"nothing checked" is the SEE-181 rule that a tile never claims "In rules" for a request no rule
looked at. The badge uses the same colours on both tile backgrounds.

## What was removed

The SEE-119 tile's "Signal" label, the large outcome number with its unit row, the caption under
the title, the separate question caption, the "In rules / N warnings" text pill and the server
chip in the header are gone. The server is named once, in the footer.

## Where it lives

- `apps/android/designsystem/.../RequestTile.kt` — the component, `RequestTileTitleSize`,
  `RequestTileStatus` and `statusLabel()`; `RequestTilePreviews.kt` — the captured variants, also
  shown on the debug component gallery.
- `apps/android/app/.../connections/ConnectionsScreen.kt` (`toHomeCarouselItem`) — maps a pending
  item to the tile: title, connection label and colour, `formatTime(at)`, warnings and `unchecked`.
- Spec: [`design/components/request-tile/spec.md`](../../design/components/request-tile/spec.md);
  ticket references: [`docs/design/request-tile/`](../design/request-tile/README.md).

## Tokens the export does not have yet

`statusOk` (`#25984D`, the reference's `oklch(0.6 0.15 150)`) and the 17sp `tileTitle` role were
specified by the ticket before the Stage 7.2 export had them; see
[`design/token-mismatches.md`](../../design/token-mismatches.md#see-183-tokens-pending-the-export).
