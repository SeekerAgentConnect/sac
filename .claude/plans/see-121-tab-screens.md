# SEE-121 — Rebuild the five tab screens from the design system

Ticket: https://linear.app/seekeragentwallet/issue/SEE-121/rebuild-the-tab-screens-from-the-library-home-inbox-wallet-activity

Source: read in full on 2026-09-19 through the Superset task fallback
(`superset tasks get SEE-121 --json`) because the configured Linear plugin is installed but not
connected. The synchronized body is identical to the full ticket text supplied by the owner. The
fallback exposes the current issue body/status but not comments, parent/sub-issues, or comment
creation; the in-app Linear page also reached a login wall.

Canonical screen inputs are `design/screens/{home,requests,wallet,activity,add}.{html,png}`. The
ticket's `design/reference/screens/` path does not exist in this checkout; `design/navigation.md`
identifies `design/screens/` as the generated references.

## Plan

- [x] Add the missing reusable visual contracts to `:designsystem`, grounded in the canonical
      screen HTML/PNG references and covered by previews: full-screen/app-bar/bottom-navigation
      chrome, inline-code instructions, Home global-rules entry, Wallet publication warning, and
      Activity summary row. Extend existing component APIs only where the screen references expose
      a missing state (wallet open/copy behavior, Inbox action kind/assessment state, text-field
      keyboard options, and empty states).
- [x] Build a stateless `HomeScreen(state, callbacks)` from `WalletBanner`, `SectionHeader`,
      `RequestCarousel`, the rules entry, `ServerRow`, the designed empty state, and `SeekerFab`.
      Add a thin `HomeRoute` that maps the existing connections/inbox/operations/wallet/policy
      state, preserves retry/message/copy/navigation behavior, and presents pending items newest
      first.
- [x] Build a stateless `InboxScreen(state, callbacks)` with hoisted Pending/History selection,
      `SeekerTabBar`, `InboxRow`, `HistoryRow`, the designed empty state, and exact footer copy.
      Keep refresh, review, result history, private/feed filtering, and all approval behavior in a
      thin route and the existing ViewModels.
- [x] Build a stateless `WalletScreen(state, callbacks)` with the expanded banner, per-server
      publication warning/retry, explanation, connect/error states, and disconnect action. Keep
      wallet sessions, publication, retries, and network selection in `WalletViewModel` and its
      thin route.
- [x] Build a stateless `ActivityScreen(state, callbacks)` from the new Activity row contract,
      exact footer, and designed empty state. Preserve newest-first records, unreadable-state
      handling, and the existing record-detail callback in a thin route.
- [x] Recompose the stateless `AddConnectionScreen(state, callbacks)` idle reference from the
      inline-code instruction, full-width scan FAB, design text field, Continue button, and caution
      copy. Keep camera permissions, scanning, parsing, confirmation/retry branches, reset rules,
      URI keyboard behavior, secret handling, and successful navigation in `AddConnectionRoute`.
- [x] Replace the app-local bottom bar/root clipping with the design-system navigation assembly and
      reference scroll-edge behavior: each root owns 104dp tokenized trailing scroll space while
      the 80dp navigation overlays the viewport.
- [x] Enable Roborazzi preview scanning for a dedicated `:app` screen-preview package; record exact
      390x844 dark fixtures as `screens/{home,requests,wallet,activity,add}.png`; merge app and
      design-system captures into `designCompare` without scanning the unrelated legacy preview.
- [x] Add/update focused Compose and mapper tests for exact fixture text, callbacks, ordering,
      tabs/history, retry/connect/disconnect behavior, pairing/camera behavior, empty states, and
      bottom-navigation scroll clearance. Preserve ViewModel/repository tests.
- [x] Iterate `recordRoborazziDebug` and `designCompare`, inspect every labelled pair, and commit a
      SEE-121 visual-review artifact whose final layout/spacing/scroll-edge difference list is
      empty.
- [x] Update the feature documentation, 2026-09-19 changelog, `CODEBASE.md` (new/moved files and
      screen architecture), and this plan's review section.
- [ ] Run formatting/lint/build verification and the relevant Android checks authorized by the
      ticket; re-read SEE-121; self-review the diff against `master`; commit and push only to
      `superset/feat/see-117`; verify PR #34 CI; move the synchronized task to In Review and attach
      PR #34 through the Superset fallback; comment only if Linear authentication becomes
      available; post the required finished webhook; stop before SEE-123.

## Acceptance criteria (verbatim)

- [x] Difference list against each screen reference is empty for layout, spacing between
      components, and scroll-edge behavior under the nav bar.
- [x] No literal colors, dp, or sp in `:app` UI code (check from
      [SEE-114](https://linear.app/seekeragentwallet/issue/SEE-114/create-the-designsystem-module-with-the-theme-from-extracted-tokens)
      passes).
- [x] Empty states (no pending requests, no servers, no activity) implemented as designed on the
      Components page.

## Done criteria (verbatim from the owner)

1. [ ] Commit + push to `superset/feat/see-117` (updates PR #34)
2. [ ] Move SEE-121 to In Review; comment if Linear auth works (else note in webhook)
3. [ ] BEFORE STOP, POST webhook Bearer `$SEE_SUPERSET_TOKEN`:
       `{"ticket":"SEE-121","repo":"SeekerAgentWallet","branch":"superset/feat/see-117","status":"finished","message":"<short summary>","pr":"https://github.com/BrRenat/SeekerAgentWallet/pull/34"}`
       Also `stuck|blocked|failed` if needed
4. [ ] Never switch to luna on rate limits — STOP and report
5. [ ] STOP. Do not start SEE-123.

## Review

- Implemented the five state/callback screens and thin route adapters, shared screen chrome and
  missing screen elements in `:designsystem`, designed empty states, and the app-level preview
  scanner. Existing wallet, inbox, activity, camera/pairing, and navigation ownership remains in
  the routes and ViewModels.
- Visual audit: all five 390×844 references are paired in `designCompare`; the committed labelled
  comparisons in `docs/reviews/see-121/` have an empty layout, component-spacing, and scroll-edge
  difference list. The 28 dp status region is reserved for system UI, and the fixed 80 dp navigation
  overlays scroll content with 104 dp trailing clearance.
- Verification passed: `git diff --check`; `pnpm run check:format`; `pnpm run check:lint`;
  `pnpm run build`; `pnpm run check:android`; `checkDesignSystemLiterals`; both Roborazzi record
  suites; and `designCompare --rerun-tasks` (129 paired paths, including all five screens).
