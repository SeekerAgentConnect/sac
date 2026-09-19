# App navigation

The Android app has one typed navigation state in `AppNavigation.kt`. It separates the current
full-height screen from the ordered sheet stack:

- `AppScreen.Home`, `Inbox`, `Wallet`, and `Activity` are peer tabs. Choosing a tab replaces the
  base destination and clears open sheets.
- `AppScreen.AddConnection` is a full-height screen reached from Home's FAB. Back and a successful
  pairing return to Home.
- `AppSheet` contains every review, connection, rules, asset, and address destination. A child
  sheet is pushed over its parent, so Back or Close removes only the top destination.

`AppNavigator` owns the allowed transitions. A transition from the wrong parent returns `false`,
which prevents a UI callback from creating a route that is absent from the approved flow. Its
saved-state encoding contains only destination tags and public route identifiers; editor drafts,
credentials, request bodies, and wallet data are never navigation arguments.

Request notifications select Home and open the exact typed `RequestReview` route. Approving a
wallet-backed production action pushes `WalletHandoff` over that review. Back, Close, and **Leave
without answering** pop only the hand-off, so the request remains pending. A refused hand-off also
returns to Review so its error remains visible; a successful approval or decline finishes both
sheets. Restored child destinations reload their policy draft, request preparation, or signal
review from their route IDs.

An asset route also carries whether it came from the allowlist or spending-limit section. Adding a
limit therefore cannot silently add the same asset to the allowlist.

The concise destination/entry/exit matrix is in [the design navigation table](../design/navigation.md).
`AppNavigationTest`, `InboxActivityTest`, and `PolicyActivityTest` cover graph validity, saved-state
round trips, review → hand-off → Back, and detail → connection rules → global rules.
