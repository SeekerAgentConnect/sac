# SEE-123 — Align navigation with the user-flow map

Linear: https://linear.app/seekeragentwallet/issue/SEE-123/align-navigation-with-the-user-flow-map

Ticket source: `superset tasks get SEE-123 --json` returned the Linear-backed issue in full and matched the owner-quoted body in the 2026-09-19 implementation request. The configured Linear plugin is enabled in `.claude/settings.json`, but its account is not connected in this runner, so the repository's Superset ticket fallback was used.

## Plan

- [x] Read the design navigation contract, relevant component specs/references, existing navigation code, notification routing, and prior Stage 7.2 changes.
- [x] Define one typed navigation graph for the Home, Inbox, Wallet, and Activity tabs and all sheet destinations.
- [x] Wire the exact user-flow arrows and remove paths that answer requests from Home.
- [x] Preserve nested-sheet back/close behavior, including leaving wallet hand-off with the request pending.
- [x] Preserve SEE-80 notification routing into the correct review sheet.
- [x] Add Compose navigation coverage for review → hand-off → back and detail → rules → global rules.
- [x] Add `docs/design/navigation.md`, the relevant feature documentation, and the changelog entry required for a shipped feature.
- [x] Update `CODEBASE.md` if files/architecture move or newly explored files are not represented there.
- [x] Run formatting/lint/build checks and the user-requested navigation tests, then review the diff against `master` and the design flow.
- [x] Commit and push the existing `superset/feat/see-117` branch so PR #34 updates.
- [x] Re-read SEE-123, move it to In Review, link PR #34, and add a result comment when possible.
- [x] Post the required finished webhook before stopping.

## Acceptance criteria (verbatim)

- [x] Every arrow on the flow page is represented by the typed graph and no undesigned visible UI edge remains
- [x] Compose navigation tests: review → hand-off → back; detail → rules → global rules

## Required flow (verbatim)

- [x] Nav bar: Home ↔ Inbox ↔ Wallet ↔ Activity
- [x] Home → Add connection (FAB)
- [x] Home carousel tile tap, or Inbox row Review → Request review sheet (6)
- [x] Request review → Approve → Wallet hand-off sheet (7), stacked over blurred review
- [x] Home paired-connection row → Connection detail sheet (8) → Rules row → Rules for this connection (9) → Global / Edit link → Global rules (10)
- [x] Home Rules row → Global rules (10)
- [x] Rules (9) → asset row or Add asset → Add / edit asset (11); Add under Recipients or Programs → Add address (12)

## Scope checks (verbatim)

- [x] One navigation graph with typed routes; sheets as sheet destinations, not ad-hoc booleans
- [x] Back/close: returns to sheet underneath, not Home; "Leave without answering" on hand-off returns to review with request still pending
- [x] Carousel only browses; tile tap opens review; nothing answered from Home
- [x] Notification tap-to-open (SEE-80) still lands on correct review sheet
- [x] Short `docs/design/navigation.md` table: destination, type (tab/screen/sheet/stacked sheet), entry points, exit behavior

## Review

- Replaced the saved string list with `AppNavigator`, typed `AppScreen`/`AppSheet` routes, guarded
  transition edges, and an ID-only saved-state codec.
- Made wallet hand-off, asset editing, and address entry real stacked destinations. Back/Close pop
  exactly one sheet; hand-off Back and **Leave without answering** perform no request action.
- Restored child sheets reload their backing draft, preparation, or signal review. Refused wallet
  approvals reveal Review with the request still pending, and signature approvals use the same
  typed hand-off stack as transfer and production-operation approvals.
- Kept allowlist and spending-limit asset entry points distinct in the route, so adding a limit
  cannot implicitly allow that asset.
- Removed the visible Activity-detail, connection-filtered-inbox, and review-to-rules edges absent
  from the approved flow. Pairing now returns to Home.
- Verified notification tap-to-review and the two requested Compose stacks in the activity tests.
- Verification: `pnpm run check:android` passed (the complete JVM/Compose suite, Spotless, design
  literals, Roborazzi, Android lint, debug APK, and debug-test APK). `pnpm run check:format` and the
  focused route restoration test also passed. No Android device was attached for a manual walk.
- Delivery: implementation commit `b3eb1b2` was pushed to PR #34 and the Linear-backed task was
  moved to In Review with that PR linked. The configured Linear plugin is disconnected, the task
  fallback has no comment command, and the browser session reached Linear's login wall, so a
  Linear comment was not possible.
