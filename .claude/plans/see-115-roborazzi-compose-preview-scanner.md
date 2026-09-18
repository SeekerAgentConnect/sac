# SEE-115 — Set up Roborazzi with the Compose preview scanner

Linear: https://linear.app/seekeragentwallet/issue/SEE-115/set-up-roborazzi-with-the-compose-preview-scanner

Parent: SEE-110

Branch: `superset/feat/see-115` from `origin/master` at `c2484f7`

## Authoritative inputs

- [x] Read SEE-115 in full through the configured Linear MCP, including relations.
- [x] Read every SEE-115 comment before implementation: none as of 2026-09-18 22:21 UTC.
- [x] Read SEE-110 and its complete child inventory/boundaries.
- [x] Read linked issue comments. The newest decision clarification is SEE-113's 2026-09-18 comment: rendering scale is 3.0, not 2.625; expected phone size is 1170×2532 px, 48dp is 144 px, and 358dp is 1074 px.
- [x] Confirmed no Linear documents are attached to SEE-115 or SEE-110.

## Implementation checklist

- [x] Add Roborazzi 1.74.0, Robolectric, and ComposablePreviewScanner 0.9.3 to `:designsystem`; generate Robolectric preview tests with `w390dp-h844dp-xxhdpi`, native graphics, dark-only previews, and a reusable module convention that SEE-121 can enable for `:app`.
- [x] Capture component previews at their own bounds on the design surface with no added padding; use `@Preview(widthDp = 358)` for width-dependent components.
- [x] Map capture paths exactly to `design/components/<component>/<variant-slug>.png`, using the SEE-113 slug rule: lowercase; `=` and spaces become `-`; strip everything outside `[a-z0-9-]`.
- [x] Add dark `SeekerTheme` probes for the theme swatch, a 48dp height sample, `FontWeightProbe`, and the requested icon set (`close`, `content_copy`, `toll`, `refresh`, `delete`) without changing icon sets.
- [x] Keep variable Roboto/Roboto Mono entries explicit at 400/500/700 and 400/500 respectively; validate the native Robolectric rendering and use static TTFs only if the one-hour fallback condition is reached.
- [x] Add and document `recordRoborazziDebug`, `compareRoborazziDebug`, `verifyRoborazziDebug`, and `designCompare`.
- [x] Make `designCompare` create `build/design-compare/<component>/<variant>.png` as `reference | actual`, print both pixel sizes, and report missing pairs in both directions.
- [x] Document the font outcome and comparison/regression semantics in `design/README.md`.
- [x] Add focused automated checks for slug/path mapping, 48dp → 144px, font/icon probe presence/behavior, and comparison pairing.

## Acceptance criteria (verbatim)

- [x] A theme-swatch preview records locally and in CI.
- [x] A 48dp-high sample box records at exactly 144 px high.
- [x] `FontWeightProbe` passes the checks above.
- [x] Side-by-side output works for at least one component once SEE-113 references exist, and both halves have the same scale.
- [x] Verify mode is used only as a regression guard for already-approved components, never as a pixel-match gate against the HTML references.

## Required verification

- [x] `./gradlew :designsystem:recordRoborazziDebug`
- [x] Inspect recorded dimensions and font/icon probes.
- [x] `./gradlew designCompare`
- [x] `./gradlew :designsystem:compareRoborazziDebug`
- [x] `./gradlew :designsystem:verifyRoborazziDebug`
- [x] `./gradlew :designsystem:check`
- [x] `./gradlew check`
- [x] `pnpm check:android`
- [x] Re-read SEE-115 with `get_issue` and `list_comments` before PR creation.
- [x] Review every ticket item as PASS, FAIL, or NOT RUN below.

## Review

- **PASS:** all requested dependencies, scanner wiring, device configuration, dark theme, capture
  bounds, exact naming, probes, helper output, documentation, and tests are present.
- **PASS:** the swatch reference and Android output pair at 198×150; the 48dp probe is 144px high;
  the 358dp probe is 1074px wide.
- **PASS:** generated preview tests and the focused probe tests use Robolectric native graphics and
  run without an emulator.
- **PASS:** `:app` carries disabled equivalent wiring; enabling and adding its full-screen previews
  remains SEE-121.
- **PASS:** no Android golden PNGs are committed and no HTML pixel gate was added; SEE-124 owns
  approved baselines and CI verification.
- **PASS:** the final Linear sweep found no SEE-115 comments and no new decision on SEE-121 or
  SEE-124. SEE-113's 3× clarification still governs. SEE-125's new implementation comment confirms
  its separate exact-crop/default-scale work and changes no SEE-115 requirement.
