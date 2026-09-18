# Design guide

The PNGs under `components/` are generated references from the Stage 7.2 Claude Design export. How
agents use this directory and how to refresh it is SEE-116. The Android `:designsystem` module
renders its Compose previews independently with Roborazzi and ComposablePreviewScanner, then
`designCompare` pairs files whose relative paths match.

## First run

From a clean checkout, install the capture tool once:

```bash
cd design/tools && npm install
```

That installs Playwright, sharp, and the pinned Chromium (the `postinstall` script runs `playwright install chromium`). Then from the repository root:

```bash
npm run design:capture
```

No other manual step is required. `--dsf` still overrides the default scale of 3.

Heights, paddings, radii, and fixed widths are exact targets. Widths of hug-content components
(buttons, chips) come from Chrome's text shaping and will differ from Compose by a pixel or so;
treat them as approximate.

## Capture contract

- Both sides use device scale factor 3. The Compose qualifier is
  `w390dp-h844dp-xxhdpi`: 390×844 dp at 480 dpi. Do not use 2.625.
- Android captures are dark-only and each preview is wrapped in `SeekerTheme(darkTheme = true)`.
- A component preview owns its complete canvas. The design surface adds no padding, so the PNG is
  the composable's own bounds. Width-dependent components declare `@Preview(widthDp = 358)`.
- Every `@Preview` in `:designsystem` must have `@DesignRef(component, variant)`. The output is
  `<component>/<variant-slug>.png`, where the slug is lowercase, `=` and whitespace become `-`,
  non-`[a-z0-9-]` characters are removed, repeated hyphens collapse, and edge hyphens are removed.
- Robolectric uses native graphics. Recording and comparison run entirely on the JVM with no
  emulator or device.

## Commands

Run these from `android/`:

```bash
./gradlew :designsystem:recordRoborazziDebug
./gradlew :designsystem:compareRoborazziDebug
./gradlew :designsystem:verifyRoborazziDebug
./gradlew designCompare
```

`recordRoborazziDebug` writes Android PNGs to
`designsystem/build/outputs/roborazzi/<component>/<variant-slug>.png`. The repository's
`pnpm check:android` runs that task too, so every design-system preview must render in CI without an
emulator.

`compareRoborazziDebug` and `verifyRoborazziDebug` compare against recorded Roborazzi baselines.
They are regression guards for components whose Android output has already been approved. They do
not compare Compose with the HTML design export and must never become an HTML pixel-match gate.
SEE-124 owns committing the approved baselines and enabling that regression guard in CI.

Run `designCompare` after recording. It reads the design references from `design/components`,
writes `android/build/design-compare/<component>/<variant-slug>.png` with
`reference | actual` and both pixel dimensions, and writes `report.txt` listing paired files,
missing references, and missing Android captures. A matching path is paired even when its pixel
dimensions differ, so the labels make a scale or crop mismatch visible instead of hiding it.

## Font and icon probes

`FontWeightProbe` shows bundled Roboto at 400/500/700, Roboto Mono, and the platform default. The
font entries in `Type.kt` already set `FontVariation.weight(...)` explicitly for every declared
weight. Native-renderer tests confirm the Roboto weights are distinct, bundled Roboto differs from
the platform fallback, and equal-length `iiii`/`MMMM` Mono samples have equal advance. The
variable fonts therefore remain in place; no static-TTF fallback was needed.

The icon probe renders `close`, `content_copy`, `toll`, `refresh`, and `delete` from the same
Material Icons Outlined set used by the app. It is a diagnostic capture, not permission to replace
the icon set when a glyph renders incorrectly.
