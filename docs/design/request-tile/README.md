# Request tile — design brief (SEE-183)

The design source of truth for the rebuilt Home request tile, as attached to Linear SEE-183. It is
committed unchanged so the implementation and its review can be checked against the same
references.

- [`request-tile.html`](request-tile.html): the reference markup and CSS. Its classes and tokens
  are the spec (open it in a browser).
- `png/tile-states.png`: the whole tile, centred and in the rail, in rules / 1 warning / 3 warnings.
- `png/tile-title-sizes.png`: the three title sizes (22, 17 and 15px by title length).
- `png/atom-status.png`, `png/atom-server-chip.png`, `png/atom-rows.png`: the status badge, the
  server chip, the header and footer rows and the kind icons.
- `png/carousel-item.png`: one centred tile as it sits in the Home rail.

These references are not part of the generated guide under `design/`: the Stage 7.2 export still
draws the old tile. When the export is refreshed, it goes through `design/UPDATING.md` as its own
design-only change, and `design/components/request-tile/` is regenerated from it.

The implementation is described in [`docs/wiki/request-tile.md`](../../wiki/request-tile.md).
