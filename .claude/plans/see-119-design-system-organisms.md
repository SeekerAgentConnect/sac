# SEE-119 — Build design-system organisms

Ticket: https://linear.app/seekeragentwallet/issue/SEE-119/build-design-system-organisms-request-tile-request-carousel-inbox-row

Ticket source: read in full on 2026-09-19 through the Superset task fallback
(`superset tasks get SEE-119 --json`) because the configured Linear plugin had no connected
account and the signed-in browser fallback reached Linear's login wall. The fallback exposes the
synced issue body and current status but not comments, parent, sub-issues, or comment creation.

## Plan

- [x] Read `design/README.md`, every named organism `spec.md`, the token source, and the SEE-117 / SEE-118 implementation patterns.
- [x] Inventory the available design specimens; record missing variants (including any SEE-112-dependent WalletBanner variants) without inventing them.
- [x] Implement the organism tier in `:designsystem`, composed only from SEE-117 atoms and SEE-118 molecules:
  - [x] `RequestTile`: one component; rail and centred colour states; Acknowledge, Prediction, Transfer, Swap, and Signature kinds; optional signal label; source chip; amount/unit headline with coin badge; effect line; centred on-tile verdict pill.
  - [x] `RequestCarousel`: snapping row at 358 content width with the centred tile highlighted, preserving SEE-81 alignment and snapping.
  - [x] `InboxRow`: merged request/signal row with chips, summary, time, expiry, and one Review button.
  - [x] `HistoryRow`: answered-history Activity row.
  - [x] `ServerRow`: source avatar, name, and status line.
  - [x] `RuleRow`: Global rules entry and per-section rows.
  - [x] `VerdictCard`: ok and warning variants, scope chips, and an onVerdict Rules button.
  - [x] `OwnerInputCard`: “Your part · amount / side and stake”, “Not chosen yet”, and Choose button.
  - [x] `TermsCard`: daily-row container (“Daily spend, if you approve”).
  - [x] `WalletBanner`: available compact Home / expanded Wallet variants plus “couldn't tell server” warning card; skip and record missing specimens.
  - [x] `WalletHandoffCard`: Seed Vault Wallet header, title, monospace summary, Sign and send / Decline / Leave without answering.
  - [x] `SheetScaffold`: drag handle, title row, close icon-button, scrollable body, pinned actions, and plain / stacked-over-blurred variants; fix SEE-74 intro cut-off and missing chrome.
- [x] Add one exact-design-text `@Preview` per available data variant, captured through `@DesignRef`.
- [x] Run the focused design-system build and Roborazzi capture/compare workflow; produce a difference list and iterate until it is empty.
- [x] Generate side-by-side evidence for every implemented variant and attach it to PR #34.
- [x] Update `CODEBASE.md`, the relevant feature documentation, the changelog, and this plan's review section.
- [x] Re-read SEE-119 through any available ticket source, run the required build/lint checks, review the final diff against `master`, commit, and push to `superset/feat/see-117`.
- [x] Update SEE-119 to In Review and comment with summary/evidence if Linear authentication becomes available; otherwise record the auth failure in the webhook report.
- [ ] POST the required finished webhook with PR #34, then stop without starting SEE-120.

## Acceptance criteria (verbatim)

- [x] RequestTile previews reproduce the design's rail sheet (five kinds × two states) and match references
- [x] SheetScaffold fixes SEE-74 intro cut-off and missing chrome
- [x] Side-by-side images for every variant attached to PR #34

## Done criteria (verbatim)

1. [x] Commit + push to `superset/feat/see-117` (updates PR #34)
2. [x] Move SEE-119 to In Review on Linear; comment with summary + evidence (if Linear auth fails, note in webhook and continue)
3. [ ] BEFORE STOP, POST webhook with Authorization Bearer `$SEE_SUPERSET_TOKEN`:
   `{"ticket":"SEE-119","repo":"SeekerAgentWallet","branch":"superset/feat/see-117","status":"finished","message":"<short summary>","pr":"https://github.com/BrRenat/SeekerAgentWallet/pull/34"}`
   Also POST stuck|blocked|failed if you cannot finish
4. [x] Never switch to luna on rate limits — STOP and report
5. [x] STOP. Do not start SEE-120.

## Review

- Implemented twelve stateless organism APIs and 43 exact-text `@DesignRef` previews.
- Captured and manually reviewed all 43 design/Roborazzi pairs. Corrected the warning icon family,
  TermsCard flex sizing, and compact wallet-address truncation; no material unresolved differences
  remain. Renderer-level font cropping and Material glyph-path differences are documented with the
  intentional 358dp/centred carousel ticket precedence.
- The generated guide has no WalletBanner warning specimen or unchosen prediction OwnerInput
  specimen; both gaps are recorded without invented previews. TermsCard supports the ticket's
  DailyRow container and the only generated quoted-swap specimen.
- Verification passed: `pnpm run check:format`, `pnpm run check:lint`, `spotlessCheck`,
  `checkDesignSystemLiterals`, `:designsystem:lintDebug`, `:designsystem:assembleDebug`,
  `:designsystem:recordRoborazziDebug`, `:designsystem:compareRoborazziDebug`, `designCompare`, and
  `pnpm run build`.
- Linear MCP remained unavailable and the browser fallback reached the Linear login wall. Ticket
  content was re-read through `superset tasks get SEE-119 --json`; the Superset fallback synced the
  In Review status and PR URL to Linear. It exposes no comment API, so summary/evidence was posted
  to PR #34 and the Linear comment limitation is carried into the required webhook.
- Commit `02096c2` is on `superset/feat/see-117`; PR #34 was updated in place and no second PR was
  opened. GitHub Actions did not start any steps because account payments failed or the spending
  limit needs to be increased; the equivalent local checks above pass.
