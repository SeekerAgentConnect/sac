# Library-composed sheets

SEE-122 rebuilds the six remaining Android sheets from the shared `:designsystem` library:
Wallet hand-off, Connection detail, Rules for one connection, Global rules, Add/edit asset, and Add
address. Together with SEE-120's review template, every sheet in design-flow map 7–12 now has one
stateless presentation component.

## Presentation contract

Each public sheet takes an immutable UI state plus callbacks and imports no repository, ViewModel,
storage, transport, request, or wallet implementation type. Exact design copy lives in
`SheetCompositionFixtures`; production routes map their domain state into the same contracts.

`SheetScaffold` owns the drag handle, header, close action, scrolling body, and pinned footer or
header action. Every sheet sizes to its content, capped by the host in
[`design/motion.md`](../../design/motion.md). `StackedSheetUnderlay` applies the shared scale,
blur, and clipping treatment to the preserved parent sheet.

The application adapters retain all existing behavior:

- Wallet hand-off still delegates signing, decline, and leave-without-answering to the existing
  request lifecycle.
- Connection detail still renames, opens its inbox and rules, and confirms disconnect.
- Rules still edit the versioned global policy or the selected connection's overrides. Child asset
  and address sheets update the parent draft; persistence occurs only when the parent rules edit is
  saved.
- Asset input retains network and threshold validation. A connection asset shows the inherited
  global daily limit read-only and can route to its global editor.
- Address input is accepted only when it is valid base58 for a 32-byte Solana address.

Rules remain advisory. The new presentation does not approve a request, open a wallet, or weaken
the existing fresh-read and validation boundaries.

## Visual verification

The app preview scanner records six 390×844 dp dark fixtures as `screens/wallet-handoff.png`,
`screens/connection.png`, `screens/rules-connection.png`, `screens/rules-global.png`,
`screens/asset-edit.png`, and `screens/add-address.png`. `designCompare` pairs all six with the
checked-in export references. The committed [SEE-122 visual review](../reviews/see-122/README.md)
has an empty ticket-specific difference list.

Long rules content scrolls inside the body while its header remains pinned. The introductory rules
card expands without clipping, and actions stay outside the scrolling body. Physical Seeker checks
remain recorded separately in the Stage 7.2 acceptance record.
