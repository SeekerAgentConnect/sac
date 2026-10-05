# Tab screens

The Home, Inbox, Wallet, Activity, and Add connection destinations are composed from the shared
Android `:designsystem` library. Each destination separates display state from application work:

- `HomeScreen`, `InboxScreen`, `WalletScreen`, `ActivityScreen`, and `AddConnectionScreen` are
  stateless composables. They receive UI-only state and callbacks and do not collect a ViewModel.
- Their thin route adapters preserve the existing repositories, refreshes, wallet publishing,
  pairing, camera permission, scanner, history, and review navigation.
- `ScreenScaffold` owns the app bar, the overlaid navigation bar, and the scroll body whose
  trailing space lets the last item clear that bar. The bar is the same five items on every tab —
  Home, Inbox, Discover, Wallet, Activity — with no per-screen opt-out; only the selected item
  differs. Add connection keeps the navigation visible with no selected tab. `DetailScreenScaffold` is the same app bar with no navigation bar, for a
  page reached from a tab such as a History record ([history details](history-details.md)).
- Empty Home server, Home pending, Inbox pending/history, and Activity states use the shared
  design-system empty-state component.

## Design verification

Private previews in `io.github.brrenat.seekervault.designpreviews` provide deterministic fixtures
for the five references under `design/screens/`. The app module's Roborazzi preview scanner records
them as `screens/home.png`, `screens/requests.png`, `screens/wallet.png`, `screens/activity.png`, and
`screens/add.png`. From the repository root:

```sh
pnpm run check:android
cd apps/android && ./gradlew designCompare
```

`designCompare` pairs both design-system component captures and app screen captures with the local
HTML export PNGs. Its report must pair all five `screens/*` paths with no missing screen reference
or actual; the repository-wide inventory separately lists intentionally unpaired probes and token
specimens.
