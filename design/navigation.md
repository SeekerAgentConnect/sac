# Navigation contract

This is the hand-written navigation reading of the exported user-flow page and its rendered runtime. The references under [`screens/`](./screens/) show each fixed state; the raw bundles are provenance, not an implementation input.

## Model

Home, Inbox, Discover, Wallet, and Activity are peer tabs. Add connection is a full screen. All review, connection, rule, editor, and wallet hand-off destinations are sheets. Pushing a sheet leaves the destination underneath it in place; closing the top sheet reveals that exact destination rather than routing to Home.

The export keeps a sheet stack. Each lower sheet remains visible as a dimmed, scaled, blurred edge offset by 12 CSS px. Tapping an exposed lower sheet removes every sheet above it. The export does not specify Android system Back, gesture dismissal, or outside-scrim behavior.

## Tabs and screen

| Destination | Type | Entry points | Exit behavior | Reference |
| --- | --- | --- | --- | --- |
| Home | Tab; launch destination | App launch; Home nav item | No app-bar Back. Opens child screens and sheets without answering a request. | [`home`](./screens/home.png) |
| Inbox | Tab with Pending / History local state | Inbox nav item; Home **N · see all**; Connection detail **Its inbox** with a source filter | App-bar Back in the export returns Home. Changing Pending / History does not create a destination. | [`requests`](./screens/requests.png) |
| Wallet | Tab | Wallet nav item; wallet banner on Home | App-bar Back in the export returns Home. | [`wallet`](./screens/wallet.png) |
| Discover | Tab (SEE-176); not in the Stage 7.2 export | Discover nav item | App-bar Back returns Home. Browsing and opening a card's details add nothing, open no wallet and send no request. | Hand-written: [`catalog-card`](./components/catalog-card/spec.md) |
| Activity | Tab | Activity nav item | App-bar Back in the export returns Home. Rows are read-only; the export defines no activity-detail destination. | [`activity`](./screens/activity.png) |
| Add connection | Full screen | Home Add connection FAB; a Discover card's **Connect** / **Request access** with its feed reference prefilled (SEE-176) | App-bar Back returns to the tab it was opened from (Home, or Discover). Continue pushes Connection detail over that tab. | [`add`](./screens/add.png) |

The flow-map arrows between Home, Inbox, Wallet, and Activity illustrate the nav bar; they do not impose a sequence.

**Discover (SEE-176) is a hand-written addition.** The Stage 7.2 export has four nav items and no
Discover screen; the fifth item, the catalog card and the catalog detail sheet are specified here
and in [`components/catalog-card/spec.md`](./components/catalog-card/spec.md) until the design is
re-exported (see [UPDATING.md](./UPDATING.md)). Until then the generated `screens/*.png` still show
the four-item bar.

## Sheets and stacks

| Destination | Type and stack | Entry points | Exit behavior | Reference |
| --- | --- | --- | --- | --- |
| Request review | Bottom sheet over the current base: `[review]` | Home request tile; pending Inbox row **Review** | Close removes Review. Reject/Dismiss, Acknowledge, and sandbox Simulate finish and close it. Production transfer/sign approval pushes Wallet hand-off. | [`review`](./screens/review.png) |
| Wallet hand-off | Stacked sheet: `[review, wallet]` | Approve a production transfer or signature | Approve or Decline closes both sheets. Close / **Leave without answering** removes Wallet hand-off only, records the outcome as unknown, and reveals Review with the request still present. | [`walletHandoff`](./screens/walletHandoff.png) |
| Owner input | Stacked sheet: `[review, params]`; not a numbered flow-map node | Choose/Edit amount for Swap; Choose/Edit side and stake for Prediction | Close removes Owner input only. **Use these** validates, updates the choice, clears warning acknowledgement, and returns to Review. | Review-state references below |
| Catalog detail | Bottom sheet over Discover: `[catalog]` (SEE-176) | A Discover card | Close removes it and reveals Discover at the same scroll position. **Connect** / **Request access** closes it and opens Add connection prefilled; **Open** replaces it with Connection detail. Nothing is added by opening it. | Hand-written |
| Connection detail | Bottom sheet: `[connection]` over Home or Discover | Paired-server row on Home; Add connection Continue; **Open** on a Discover card for a feed already added | Close removes it. **Its inbox** closes all sheets and opens Inbox/Pending filtered to that server. Rules pushes Connection rules. | [`connection`](./screens/connection.png) |
| Rules, this connection | Usually `[connection, rules]`; `[review, rules]` from a verdict | Rules row on Connection detail; Rules action on a review verdict | Close removes Rules only, revealing Connection detail or Review. Global pushes Global rules. Asset/address actions push their editors. | [`rulesConn`](./screens/rulesConn.png) |
| Global rules | `[global]` from Home, otherwise stacked over the current sheet | Global Rules row on Home; Global link in Connection rules; Edit global daily limit in Asset editor | Close removes Global rules only and reveals the exact destination underneath. | [`rulesGlobal`](./screens/rulesGlobal.png) |
| Add / edit asset | `[connection, rules, asset]` or `[global, asset]` | Asset row; Add asset | Close or successful Add/Save removes Asset editor and returns to its rules editor. Editing the global daily limit from a connection asset can push `[connection, rules, asset, global]`. | [`assetEdit`](./screens/assetEdit.png) |
| Add address | `[connection, rules, address]` or `[global, address]` | Add under Recipients or Programs | Close or successful Add removes Address editor and returns to its rules editor. The same destination serves wallet-recipient and program-address content. | [`addAddress`](./screens/addAddress.png) |

Request review is one destination driven by content/state, not five separate sheets:

| Review state | Phone reference | Unrolled sheet reference |
| --- | --- | --- |
| Transfer request | [`review`](./screens/review.png) | [`sheet-transfer`](./screens/sheet-transfer.png) |
| Swap signal | [`reviewSignal`](./screens/reviewSignal.png) | [`sheet-swap`](./screens/sheet-swap.png) |
| Prediction signal | [`reviewPrediction`](./screens/reviewPrediction.png) | [`sheet-prediction`](./screens/sheet-prediction.png) |
| Signature request | [`reviewSign`](./screens/reviewSign.png) | [`sheet-signature`](./screens/sheet-signature.png) |
| Acknowledge request | [`reviewAck`](./screens/reviewAck.png) | [`sheet-acknowledge`](./screens/sheet-acknowledge.png) |

The unrolled review references and [`rail-unrolled`](./screens/rail-unrolled.png) are comparison canvases, not destinations.

## Declared user-flow arrows

- Nav bar: Home ↔ Inbox ↔ Wallet ↔ Activity.
- Home → Add connection from the FAB.
- Home request tile or Inbox pending row → Request review.
- Request review → Wallet hand-off on production approval.
- Home paired-server row → Connection detail → Rules, this connection → Global rules.
- Rules, this connection → Add / edit asset.
- Rules, this connection → Add address.

## Open questions in the export

1. The drawn flow attaches Add connection after Activity while its label and runtime both say the FAB is on Home. This guide treats Home → Add connection as authoritative.
2. The legend says lower-row destinations stack over Home, but runtime preserves the actual base: Review opened from Inbox stays over Inbox, and Connection detail opened from Add connection stays over Add connection.
3. Global rules is drawn only after Connection rules, while runtime also opens it from Home and Asset editor. All observed entries are recorded above.
4. A Rules action from a signal review opens Connection rules even though feeds otherwise use global rules. Decide whether signal reviews should open Global rules.
5. Add connection → Connection detail exists in runtime but is absent from the drawn arrows.
6. Closing a rules sheet with unsaved edits has no specified prompt or draft-reset behavior.
7. Confirm that **Leave without answering** should reveal Review rather than close both layers; that is what the export renders.
8. There is no outside scrim. Android Back and a downward swipe both close the top sheet; timing, height, and swipe thresholds are in [`motion.md`](./motion.md).
