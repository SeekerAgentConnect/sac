# Design-system organisms

SEE-119 adds the third reusable component tier to Android's visual-only `:designsystem` module.
The twelve stateless APIs compose the theme plus SEE-117 atoms and SEE-118 molecules; app models,
navigation, persistence, transports, and wallet SDK behavior stay with callers.

## Components

| Design component | Compose API | State or content |
| --- | --- | --- |
| `request-tile` | `RequestTile` | five request kinds in rail or centred colour states; SEE-183 header/title/footer rebuild with a status badge ([request tile](request-tile.md)) |
| `request-carousel` | `RequestCarousel` | 358dp lazy row with SEE-81 endpoint snapping and centred-state selection |
| `inbox-row` | `InboxRow` | merged request/signal summary, context chips, expiry, and one Review action |
| `history-row` | `HistoryRow` | sent, simulated, dismissed, cancelled, expired, and unknown outcomes; optional kind and status icons, and a chevron when tappable (SEE-161) |
| `history-detail` | `HistoryDetailScreen` | the read-only History record page and its atoms, from a typed model (SEE-161, [history details](history-details.md)) |
| `server-row` | `ServerRow` | source avatar, connection status, and optional row action |
| `rule-row` | `RuleRow` | action, asset, program, and recipient rule entries, including read-only state |
| `verdict-card` | `VerdictCard` | OK or one/many warnings with scope chips and Rules action |
| `owner-input-card` | `OwnerInputCard` | chosen or unchosen owner-entered amount / side and stake |
| `terms-card` | `TermsCard` | daily-limit molecule container plus the captured quoted-swap summary |
| `wallet-banner` | `WalletBanner` | compact Home and expanded Wallet forms |
| `wallet-handoff` | `WalletHandoffCard` | Seed Vault handoff summary and three explicit outcomes |
| `sheet-scaffold` | `SheetScaffold` | handle, fixed header, scrolling body, pinned actions, and optional blurred stack |

`RequestCarousel` owns only presentation state. It measures the viewport against the same snap
target used by its custom fling behavior: the first item snaps to the start, interior items to the
centre, and the last item to the end. The selected item is passed to `RequestTile` as centred, so
rail geometry never changes when its colours do.

`SheetScaffold` keeps the handle and title/close row above a bounded vertical scroll container and
places the action slot outside that container. This makes the introductory content reachable while
the sheet chrome and primary actions remain visible, addressing the SEE-74 cut-off mode without
coupling the scaffold to a particular rules screen.

## Ticket and guide resolutions

The SEE-119 ticket calls `terms-card` a container for `DailyRow` molecules and supplies the title
“Daily spend, if you approve”. The generated component directory instead contains one quoted-swap
facts specimen. `TermsCard` supports both contracts: its daily-row overload is the ticket-facing
organism, while the typed swap overload renders the available design variant exactly. No
uncaptured daily-row arrangement was added to the visual evidence.

The generated WalletBanner directory contains compact and expanded wallet specimens but no
“couldn't tell server” warning card. That missing variant is recorded rather than invented. The
guide likewise has no unchosen prediction OwnerInput variant, so only its three available variants
are captured.

Several organisms reference an `icon-button`, but the guide still lacks that atom's hand-written
contract. A private token-backed icon action supplies close, retry, copy, disclosure, and delete
controls inside the organisms. It is intentionally not exported as a public design-system atom.

The public handoff name follows the ticket (`WalletHandoffCard`); a delegating `WalletHandoff`
alias preserves the older generated-spec name without duplicating implementation.

## Visual verification

All 43 variants present in the target design directories have exact-copy dark `@Preview` and
`@DesignRef` coverage at 3×.

The carousel is the one intentional ticket-over-reference rendering: the old generated specimen is
422px wide and leaves both tiles in the rail state, while SEE-119 requires a 358dp content viewport
with the centred tile highlighted. The implementation and evidence follow the ticket and retain the
SEE-81 snapping policy.
