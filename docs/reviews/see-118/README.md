# SEE-118 visual review

Each component image is the design reference on the left and the Roborazzi capture on the right,
both at 3×. The 22 images below are the complete generated-variant evidence set for SEE-118. The
last image is the additional 390dp four-item navigation assembly required by the ticket; the
component guide has no generated whole-bar reference to place beside it.

## Final difference list

- Padding, radius, font weight, line height, fixed dimensions, and token-backed colours: no
  material differences after the final pass. Natural text-height captures differ by at most two
  image pixels at 3× because Chrome and Android crop different font rasterization bounds.
- Long values: both the base58 address and UUID wrap across two lines; the UUID's label also wraps
  at its space, matching the flex-shrink reference.
- Icons: geometry, size, tone, and placement match. Chrome's Material Symbols outlines and
  Compose's bundled Material Icons Outlined paths have small glyph-shape differences.
- Ticket precedence: the filter reference predates SEE-118 and shows the medium Clear atom.
  SEE-118 explicitly requires `size = Sm`, so the captured Android button is intentionally smaller.
  No component-local styling was added to conceal that contract difference.

## fact-row

### value-full-address

![fact-row value-full-address](fact-row/value-full-address.png)

### value-longest-wraps

![fact-row value-longest-wraps](fact-row/value-longest-wraps.png)

### value-mono

![fact-row value-mono](fact-row/value-mono.png)

### value-short

![fact-row value-short](fact-row/value-short.png)

## daily-row

### state-nolimit-scope-global

![daily-row state-nolimit-scope-global](daily-row/state-nolimit-scope-global.png)

### state-over-scope-connection

![daily-row state-over-scope-connection](daily-row/state-over-scope-connection.png)

### state-within-scope-global

![daily-row state-within-scope-global](daily-row/state-within-scope-global.png)

## segmented

### count-2-mode

![segmented count-2-mode](segmented/count-2-mode.png)

### count-2-selected-0

![segmented count-2-selected-0](segmented/count-2-selected-0.png)

### count-3-selected-1

![segmented count-3-selected-1](segmented/count-3-selected-1.png)

## tab-bar

### selected-pending

![tab-bar selected-pending](tab-bar/selected-pending.png)

## nav-item

### state-rest

![nav-item state-rest](nav-item/state-rest.png)

### state-selected

![nav-item state-selected](nav-item/state-selected.png)

## section-header

### trailing-button

![section-header trailing-button](section-header/trailing-button.png)

### trailing-none

![section-header trailing-none](section-header/trailing-none.png)

## text-field

### state-error

![text-field state-error](text-field/state-error.png)

### state-rest

![text-field state-rest](text-field/state-rest.png)

## notice-card

### sandbox-card

![notice-card sandbox-card](notice-card/sandbox-card.png)

### stale-card

![notice-card stale-card](notice-card/stale-card.png)

## empty-state

### screen-inbox

![empty-state screen-inbox](empty-state/screen-inbox.png)

### screen-rules

![empty-state screen-rules](empty-state/screen-rules.png)

## filter-bar

### src-studio-mac

![filter-bar src-studio-mac](filter-bar/src-studio-mac.png)

## Assembled navigation bar

![Home selected in a four-item Home, Inbox, Wallet, Activity bar](nav-bar/state-home-selected.png)
