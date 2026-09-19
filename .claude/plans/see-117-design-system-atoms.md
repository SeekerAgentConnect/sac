# SEE-117 — Build design-system atoms

Linear: https://linear.app/seekeragentwallet/issue/SEE-117/build-design-system-atoms-chips-button-icon-button-source-avatar

Ticket source: read through the Superset task fallback on 2026-09-19; the full ticket body was
also supplied by the owner in the session prompt. Parent SEE-110 was read through the same fallback.

## Plan

- [x] Read every target component `spec.md`, variant HTML, and PNG plus `design/tokens.json`.
- [x] Confirm whether `icon-button` has the required SEE-112 specimen and hand-written spec; skip
      and document it if the required input is missing.
- [x] Implement the stateless atom composables in `:designsystem` using named semantic tokens only.
- [x] Add one `@Preview` and matching `@DesignRef` per design variant, with the exact specimen text.
- [x] Add focused contract coverage for deterministic source colours and exact component sizes.
- [ ] Record Roborazzi captures, run `designCompare`, inspect every side-by-side image, and iterate
      until the written difference list is empty.
- [x] Update feature documentation, changelog, and this plan's review section.
- [x] Run formatting/lint and the repository build; review the final diff against `master`.
- [ ] Commit and push `superset/feat/see-117`, open one PR to `master`, and attach every
      side-by-side comparison image.
- [ ] Re-read SEE-117, move it to In Review, and comment with the summary and evidence.
- [ ] POST the required finished webhook with the PR URL, then stop.

## Acceptance criteria (verbatim)

- [ ] One composable per design component, one preview per variant; preview names map to the variant slugs.
- [x] No component exposes `Color`, `Dp`, or `TextStyle` parameters.
- [x] Chip heights (24, on-tile signal 22) and button heights match the design; minimum touch target inflation disabled where the design requires it, with touch area handled separately.
- [ ] Side-by-side images for every variant attached to the PR.

## Review

- **PASS — implementation:** twelve spec-backed design components have stateless public APIs. The
  ticket's `EnvChip`, `SeekerButton`, and `SeekerFab` names take precedence over the older proposed
  names in their specs. `SeekerButton` includes the two ticket-only verdict variants.
- **PASS — preview coverage:** 53 captured variants have one exact-text preview, one `@DesignRef`,
  a unique guide path, and a preview name equal to the generated variant slug.
- **PASS — component contracts:** public APIs expose no `Color`, `Dp`, or `TextStyle`; the shared
  FNV-1a source allocator is deterministic and matches all five captured source identities; row
  controls expose Switch, Checkbox, or RadioButton semantics.
- **PASS — exact geometry:** chip and button heights, fixed widths, radii, padding, type weight,
  line height, and every non-tertiary token-backed colour match. Hug widths differ by at most 3
  image pixels at 3×, inside the guide's explicit Chrome/Compose shaping allowance.
- **PASS — evidence:** all 53 labelled `reference | actual` images are committed under
  `docs/reviews/see-117/`; the PR body will link the complete index.
- **PASS — automated verification:** `:designsystem:recordRoborazziDebug`, `designCompare`,
  `spotlessCheck`, `checkDesignSystemLiterals`, `:designsystem:lintDebug`, `pnpm run check:format`,
  and `pnpm run build` pass. The record task runs the module's focused UI and palette tests.
- **NOT RUN — icon-button:** SEE-112 added HTML/PNG captures, but
  `design/components/icon-button/spec.md` is absent. `design/README.md` requires this task to stop
  short of inferring an API from generated references.
- **FAIL — empty difference list for tertiary button:** all three tertiary captures have the exact
  geometry and typography, but the reference background is `#8A3C00`. That colour has no semantic
  token in `design/tokens.json`; the implementation uses the nearest current named token
  (`orangeContainer`, `#FF7A1A`). The guide forbids a raw colour or a mixed design/Kotlin refresh,
  so this remains visibly recorded for a separate guide correction.
- **NOT RUN — verdict-tone previews:** `OnVerdictOk` and `OnVerdictWarn` are required by the ticket
  and implemented from the named inverse verdict token pairs, but the guide provides no specimens
  from which a `@DesignRef` could be created.
