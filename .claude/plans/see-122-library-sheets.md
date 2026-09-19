# SEE-122 — Rebuild the remaining sheets from the design system

Ticket: https://linear.app/seekeragentwallet/issue/SEE-122/rebuild-the-sheets-from-the-library-wallet-hand-off-connection-detail

Source: read in full on 2026-09-19 through the Superset task fallback
(`superset tasks get SEE-122 --json`) because the configured Linear plugin is installed but not
connected. The parent SEE-110 and the separate SEE-120 review-sheet boundary were read through the
same fallback. The owner also supplied the full ticket text in this session.

## Plan

- [x] Read the sheet scaffold and every directly used design-system component contract, plus the
      canonical HTML/PNG references for design-flow sheets 7–12; inventory missing library pieces
      before touching app sheet code.
- [x] Add any genuinely missing reusable pieces to `:designsystem` first, with stateless APIs,
      exact-copy previews, `@DesignRef` coverage, and no screen-local styling substitutes.
- [x] Implement six stateless state/callback sheet compositions: Wallet hand-off, Connection
      detail, Rules for this connection, Global rules, Add/edit asset, and Add address.
- [x] Keep state, persistence, validation, navigation, and side effects in thin app adapters;
      preserve existing behavior while replacing presentation with `SheetScaffold` and library
      components only.
- [x] Add exact design-text fixtures and focused tests for content, callback wiring, base58
      validation state, rules inheritance/override states, scrolling bodies, and pinned actions.
- [x] Record the six app Roborazzi baselines, run `designCompare`, inspect each labelled
      reference/actual pair, and iterate until the SEE-122 difference list is empty.
- [x] Commit review evidence in `docs/reviews/see-122/`, update relevant feature/testing docs,
      changelog, `CODEBASE.md`, and this plan review section.
- [x] Run formatting, lint, build, Android, Roborazzi, and design comparison checks; re-read the
      synchronized SEE-122 task; self-review the diff against `master`.
- [x] Commit and push `superset/feat/see-122`; open one PR into `master` without merging; move
      SEE-122 to In Review and comment if Linear access supports it; post the required finished
      webhook before stopping.

## Acceptance criteria (verbatim)

- [x] Difference list against each sheet reference is empty, including drag handle, title row,
      close button, and the blurred backdrop for stacked sheets.
- [x] Intro text is not cut off at any sheet height; long content scrolls inside the sheet body with
      actions pinned.
- [x] SEE-74 can be closed against the new Global rules sheet.

## Done criteria (verbatim from the owner)

1. [x] Commit + push `superset/feat/see-122`
2. [x] Open ONE PR into **master**; do not merge
3. [x] Move SEE-122 to In Review; comment if Linear works
4. [x] BEFORE STOP, POST webhook Bearer `$SEE_SUPERSET_TOKEN`:
       `{"ticket":"SEE-122","repo":"SeekerAgentWallet","branch":"superset/feat/see-122","status":"finished","message":"<short summary>","pr":"<PR url>"}`
5. [x] Never switch to luna — STOP and report on rate limits
6. [x] STOP.

## Review

- Six UI-only state/callback sheet compositions now live in `:designsystem`; the app adapters keep
  repository access, validation, save confirmation, dirty-close handling, navigation, disconnect,
  and wallet outcomes outside the library.
- `SheetScaffold` gained optional header/action, full-height, close-tag, and body-spacing contracts;
  `StackedSheetUnderlay` is shared by reference fixtures and the production navigation backplate.
- Six 390×844 dp app fixtures and committed baselines pair with all six design-flow references.
  `designCompare` reports 135 pairs, including every SEE-122 path. The ticket-specific difference
  list is empty; its four missing-reference diagnostics and 57 missing-actual token/icon specimens
  are pre-existing non-SEE-122 inventory entries.
- `ANDROID_HOME=/Users/superset/Library/Android/sdk pnpm run check:android` passes the literal and
  formatting guards, both Roborazzi verifiers, all Android unit tests, both lint tasks, and both APK
  assemblies. `pnpm run build` passes for the sidecar and test agent. The initial Android command
  without `ANDROID_HOME` stopped before tasks ran because this worktree has no `local.properties`.
- Physical Seeker verification remains NOT RUN and is recorded separately in
  `docs/testing/stage-7-2.md`; no device result is inferred from JVM rendering or APK assembly.
- Commit `79d3991` is pushed on `superset/feat/see-122`; PR #37 targets `master` and remains open.
  Linear is In Review and has the implementation/verification comment. The required finished
  webhook is the final delivery action after this ledger update is pushed.
