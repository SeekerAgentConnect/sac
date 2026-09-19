# SEE-124 — Debug component gallery and Stage 7.2 acceptance

Linear: https://linear.app/seekeragentwallet/issue/SEE-124/add-a-debug-component-gallery-and-run-the-stage-72-acceptance-pass-on

Ticket source: `superset tasks get SEE-124 --json` returned the Linear-backed issue in full and
matched the owner-quoted body in the 2026-09-19 implementation request. The configured Linear
plugin is enabled in `.claude/settings.json`, but its account is not connected in this runner, so
the repository's Superset ticket fallback was used. SEE-110, SEE-74, and SEE-51 were also read
through the same fallback.

## Plan

- [x] Read every target component spec and preview fixture plus the app navigation, build-variant,
      Roborazzi, CI, and Stage 7.2 acceptance surfaces.
- [x] Add a debug-source-only gallery that renders every public `:designsystem` component variant
      from the shared preview fixtures and is reachable only in debug builds.
- [x] Add automated coverage proving gallery completeness/reachability and release exclusion.
- [x] Record and commit the approved `:designsystem` and app-screen Roborazzi golden images.
- [x] Change CI to run `verifyRoborazziDebug` so an unrecorded rendering change fails.
- [x] Run the Seeker-device gallery, screen, sheet, font-scale, navigation-inset, and keyboard pass
      when hardware is attached; otherwise record each hardware item as **NOT RUN** with evidence.
- [x] Update `design/README.md` with the complete design-to-golden workflow requested by the
      ticket, plus Stage 7.2 acceptance docs, product docs, changelog, and `CODEBASE.md`.
- [x] Reconcile SEE-74 and any remaining UI mismatch bugs against this branch; update their status
      when the Linear fallback supports it and list any unresolved/NOT RUN gaps.
- [x] Run formatting, Android verification, build, and golden guard checks; review the diff against
      `master` and demonstrate that the guard detects an unrecorded rendering change.
- [ ] Commit and push the existing `superset/feat/see-117` branch to update PR #34, then watch its
      CI to completion.
- [ ] Re-read SEE-124, move it to In Review and add the result comment when the available Linear
      transport permits it.
- [ ] Post the required finished webhook before stopping.

## Scope (verbatim)

- [x] Debug-only gallery screen listing every `:designsystem` component in every variant (reuse the
      preview fixtures). Reachable from a debug menu or a long-press; excluded from release builds.
- [ ] Device pass on Seeker: walk the gallery and every screen and sheet next to the design export
      open on a laptop; record issues as sub-tasks or fix inline.
- [ ] Check system font scale at 1.0 and 1.3, gesture and 3-button navigation insets, and keyboard
      overlap on the pairing-code, address, and threshold fields.
- [x] After approval, commit the recorded Roborazzi images as golden files and enable
      `verifyRoborazziDebug` in CI as a regression guard.
- [x] Update `design/README.md` with the full workflow: change the design → re-export →
      `npm run references` → update components → record → review → commit goldens.
- [x] Close or update SEE-74 and any remaining UI mismatch bugs. SEE-74 remains In Review because
      its physical comparison and the SEE-122 Global rules rebuild are not complete.

## Acceptance (verbatim)

- [ ] All Stage 7.2 acceptance criteria in SEE-110 are met.
- [x] CI fails when a component's rendering changes without its golden image being updated.
- [ ] Stage 8 (SEE-51) can start with the corrected UI.

## SEE-110 acceptance (verbatim)

- [ ] Every component on the Components page has a composable, previews for all variants, and a
      recorded Roborazzi image reviewed against its reference.
- [ ] Home, Inbox, Wallet, Activity, Add connection, and all sheets (request review, wallet hand-off,
      connection detail, rules, global rules, add/edit asset, add address) are composed only from
      `:designsystem`.
- [x] One review-sheet template renders Transfer, Swap, Prediction, Signature, and Acknowledge from
      fixtures.
- [ ] Checked on a real Seeker device via the debug gallery screen.

## Review

- Added a debug-source-only activity reached from the app-icon long-press shortcut. Its catalog
  directly invokes all 128 scanner fixtures; the naming test requires exact set equality with the
  `@DesignRef` scan, while the gallery Compose test proves that an indexed fixture opens live.
- Recorded 128 design-system and five app-screen Roborazzi PNGs under module-owned
  `src/test/snapshots/images/` paths. Both CI-facing verify tasks read those committed files and
  write only disposable comparison output under `build/`.
- Proved the regression guard by changing the checked-row fixture text: design-system verification
  failed on that preview, then passed after the deterministic change was reverted. The committed
  source and golden were not modified by the proof.
- Verified the exact reference workflow with `npm run references -- --check`: 176 component
  specimens and 22 screens matched their committed captures. `designCompare` paired 129 paths and
  retained the prior approved review boards.
- Verification passed: `git diff --check`; `pnpm run check:format`; `pnpm run build`;
  `pnpm run check:android`; both standalone Roborazzi verify tasks; `:app:assembleRelease`; release
  APK inspection; and the design reference check above.
- Physical Seeker acceptance is **NOT RUN** because `adb devices -l` returned no attached devices.
  All font-scale, navigation-inset, keyboard, gallery, screen, and sheet checks are enumerated in
  `docs/testing/stage-7-2.md` rather than inferred from JVM rendering.
- Stage 7.2 is not fully accepted: SEE-122 remains Todo and its six app sheet compositions are not
  on this branch; the exported icon-button still lacks a component spec; and SEE-74 therefore
  remains In Review. The automated baseline is ready for Stage 8, but those gaps must not be
  reported as physical or full-stage acceptance.
