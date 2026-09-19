# SEE-118 — Build design-system molecules

Linear: https://linear.app/seekeragentwallet/issue/SEE-118/build-design-system-molecules-fact-row-daily-row-segmented-tab-bar-nav

Ticket source: read in full on 2026-09-19 through the Superset task fallback (`superset tasks get
SEE-118 --json`) because the configured Linear plugin is not connected. The fallback exposes the
issue body and current status but not comments, parent, sub-issues, or comment creation.

## Plan

- [x] Read `design/README.md`, every target `spec.md`, each captured variant HTML/PNG, and
  `design/tokens.json`; use no other visual input.
- [x] Implement the ten stateless component APIs in `:designsystem`, using only theme values and
  the child atoms each spec allows.
- [x] Add one exact-copy `@Preview` + `@DesignRef` for every captured variant, including long
  base58/two-line fact values and a four-item navigation-bar assembly.
- [x] Record Roborazzi output, run `designCompare`, inspect every labelled side-by-side image, and
  iterate until the written difference list is empty.
- [x] Run focused design-system verification, Android lint/build, repository formatting checks,
  and the repository build. Do not run additional test suites unless the user explicitly asks.
- [x] Add the design-system molecule documentation and changelog entry, update `CODEBASE.md`, and
  complete this plan's review section with PASS / FAIL / NOT RUN evidence.
- [x] Re-read SEE-118, commit and push to `superset/feat/see-117`, attach all side-by-side images to
  PR #34, move the ticket to In Review, and add a Linear summary/evidence comment if the available
  integration supports comments.
- [x] POST the required final session webhook for SEE-118 and stop without starting another ticket.

## Acceptance criteria (verbatim)

- [x] Long base58 address and two-line values wrap as in the reference.
- [x] `nav-item` previews assembled into a four-item bar match the nav bar on the screen references.
- [x] Side-by-side images for every variant attached to the PR.

## Review

- **PASS — implementation:** all ten ticket-named, stateless APIs compile in `:designsystem` and
  use only theme values, stock Compose primitives, and each spec's allowed SEE-117 atoms.
- **PASS — preview coverage:** all 22 captured variants have exact-copy previews, unique
  `@DesignRef` paths, and a paired reference/actual review image. The acceptance-only four-item
  navigation preview records separately because the component guide has no whole-bar specimen.
- **PASS — wrapping:** both the 44-character base58 address and the UUID render on two lines at the
  reference's 358dp width; the UUID label flexes onto two lines as well.
- **PASS — visual review:** fixed dimensions match exactly. Padding, radius, type weight, line
  height, layout, and token-backed colours have no material differences. Natural text crops differ
  by at most two image pixels at 3×. Compose and Chrome outline glyph paths differ slightly.
- **PASS — ticket precedence:** the ticket's Compose names, text-field placeholder, and small Clear
  button are implemented. The older filter HTML's medium button is kept visible as an intentional
  contract difference in the review evidence rather than hidden with local styling.
- **PASS — automated verification:** `recordRoborazziDebug`, `designCompare`, `spotlessCheck`,
  `checkDesignSystemLiterals`, `:designsystem:lintDebug`, `:designsystem:assembleDebug`,
  `pnpm run check:format`, and `pnpm run build` pass.
- **NOT RUN — Linear comment:** the configured Linear plugin is disconnected and the Superset task
  fallback exposes status/PR updates but no comment operation. Per the owner instruction, this is
  recorded in the final webhook rather than blocking the implementation.
- **PASS — delivery:** implementation commit `b548c91` is pushed to
  `superset/feat/see-117`; PR #34's body and comment link the complete review; the Superset Linear
  fallback records SEE-118 as In Review with PR #34. The required final webhook is sent after this
  review record is pushed.
