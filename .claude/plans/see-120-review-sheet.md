# SEE-120 — One review-sheet template driven by state

Ticket: https://linear.app/seekeragentwallet/issue/SEE-120/implement-one-review-sheet-template-driven-by-state-with-five-design

Source: read in full on 2026-09-19 through the Superset task fallback
(`superset tasks get SEE-120 --json`) because the configured Linear plugin is installed but not
connected and direct page access returned no content. The fallback exposes the synced issue body
and current status but not comments, parent, sub-issues, or comment creation. The returned body is
identical to the full ticket text the owner supplied in the session prompt.

## Plan

- [x] Read the five review-sheet HTML/PNG references, their component specs, current design-system
      APIs, and the existing request/signal approval paths.
- [x] Add the UI-only `ReviewSheetState` and one stateless `ReviewSheet(state, onPrimary,
      onSecondary, onRules, onChoose, onClose)` composed only from `:designsystem` components.
- [x] Add five exact-text, full-height, non-scrolling-content fixtures/previews for Transfer, Swap,
      Prediction, Signature, and Acknowledge.
- [x] Add `:app` mappers from the existing request/signal models through the SEE-108 common
      envelope. Preserve current approval/rejection/wallet behavior by leaving the lifecycle
      screens and callbacks intact for SEE-121's screen assembly.
- [x] Enforce warning acknowledgement so the primary action is disabled until the checkbox is
      checked.
- [x] Record the five Roborazzi images and iterate against the design screen references until the
      design comparison has an empty difference list.
- [x] Update the feature docs, changelog, codebase map, and this plan's review section.
- [x] Run formatting/lint/build verification and the relevant existing Android checks. The ticket's
      explicit Roborazzi and behavior acceptance authorizes the relevant test runs.
- [ ] Re-read the ticket source, self-review the diff, commit, push the existing branch, verify PR
      #34 CI, move SEE-120 to In Review/comment if Linear becomes available, and post the required
      finished webhook.

## Acceptance criteria (verbatim)

- [x] Five recorded images vs the canonical unrolled `design/screens/sheet-*.png` references (the
      navigation-contract mapping for the ticket's `review_*.png` wording), with an empty final
      difference list
- [x] Signal differs from request only by data, not separate composables
- [x] Existing approval, rejection, and wallet hand-off behavior unchanged

## Done criteria (verbatim)

1. [ ] Commit + push to `superset/feat/see-117` (updates PR #34)
2. [ ] Move SEE-120 to In Review; comment if Linear auth works (else note in webhook)
3. [ ] BEFORE STOP, POST webhook Bearer `$SEE_SUPERSET_TOKEN`:
       `{"ticket":"SEE-120","repo":"SeekerAgentWallet","branch":"superset/feat/see-117","status":"finished","message":"<short summary>","pr":"https://github.com/BrRenat/SeekerAgentWallet/pull/34"}`
       Also `stuck|blocked|failed` if needed
4. [ ] Never switch to luna on rate limits — STOP and report
5. [ ] STOP. Do not start SEE-121.

## Review

- Added one design-system `ReviewSheetState` and `ReviewSheet` with no domain types or internal
  scrolling. Existing atoms/molecules/organisms render chips, notices, owner input, policy verdict,
  facts, confirmation, and actions; review-specific header/info/note/daily containers remain
  private to the template.
- Added five exact-copy fixtures/previews and extended the preview-name and `designCompare`
  contracts with only the five canonical unrolled screen references. The scanner's measurement
  ceiling is 1500dp so tall previews retain natural height without changing wrap-content component
  dimensions.
- Added thin `ActionRequest` and `OperationReview` adapters over the SEE-108 common envelope in
  `:app`. They accept runtime-established presentation facts and do not change the current request
  or signal lifecycle screens.
- Added a Compose behavior test proving warning confirmation gates the primary callback. The first
  full Android run exposed the plugin-ownership boundary because the mapper imported
  `PluginEnvironment`; the mapper now accepts the UI environment value instead, the focused guard
  passes, and the full Android pipeline passes.
- Verification: `git diff --check`; `pnpm run check:format`; `pnpm run check:lint`; `pnpm run build`;
  `pnpm run check:android`; `:designsystem:recordRoborazziDebug`; `designCompare` (five review
  screen pairs present); `ReviewSheetTest`; `DesignPreviewNamingTest`.
