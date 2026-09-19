# Design guide

This directory is the implementation contract for Android UI work. Its generated references come
from the Stage 7.2 Claude Design export; the hand-written component specs describe how those
references become Compose APIs. Read [UPDATING.md](UPDATING.md) before changing the guide itself.

## Dependency on SEE-111

SEE-111 owns `design/navigation.md`, `design/CHANGELOG.md`, and the hand-written `spec.md` in each
component directory. That work can land after SEE-116. While those files are absent, the intended
component-spec layout is the one in [`components/_template/spec.md`](components/_template/spec.md).
A component task whose `spec.md` is missing is blocked on SEE-111: report the missing spec instead
of inferring its API from the generated references alone.

## Read this for every UI task

For a component, read these sources in order:

1. `design/components/<component>/spec.md` — purpose, tier, Compose API, allowed child components,
   builder notes, and open questions.
2. The target `<variant>.html` — the exact rendered structure and numeric CSS-pixel measurements.
3. The matching `<variant>.png` — the visual reference and a check on the complete rendered bounds.
4. `design/tokens.json` — the only scales from which named design-system tokens are defined.

The HTML's CSS pixels map one-to-one to Android units: a layout `px` is a `dp`, and a font `px` is
an `sp`. Both generated PNGs and Compose captures use scale 3, so 48 CSS px / 48 dp is 144 image
pixels. Never derive Android dimensions from the PNG pixel count, and do not use the obsolete
2.625 scale.

Heights, paddings, radii, and fixed widths are exact targets. Widths of hug-content components
(buttons and chips) come from Chrome's text shaping and can differ from Compose by about a pixel;
treat those widths as approximate and keep the specified padding, type, and height exact.

## Compose component contract

- Components are stateless. Data goes in and event lambdas come out; repositories, ViewModels,
  navigation, and business effects stay outside `:designsystem`.
- Every public component accepts `modifier: Modifier = Modifier` and applies it to its outermost
  node. Do not use a modifier to hide a different component API.
- Model each axis named by `data-variant` as a typed enum named in the component spec. Do not pass
  variant names as strings or replace a multi-state design axis with unrelated booleans.
- Component APIs accept content and state, not styling escape hatches. Do not expose `Color`, `Dp`,
  or `TextStyle` parameters.
- A screen composes its visible UI only from `:designsystem`. If the guide has no required piece,
  update the design and this guide first, then implement the missing design-system component; do
  not invent a screen-local substitute.

### Tokens

The pass-through `dpN` properties in `Dimensions.kt` are deprecated and unusable for production
components in `:designsystem`. Components use named semantic tokens. Each named spacing, radius,
type, button, and icon token must correspond to a value in `design/tokens.json`, and the token
contract unit test must continue to compare the Kotlin declarations with that file.

If a component needs a value that `tokens.json` does not contain, report the missing token in the
issue or pull request and refresh the design guide first. Never invent another `dpN` property, a raw
literal, or a style parameter to get around the scale.

### Known Compose traps

- Disable platform font padding and carry the reference line height explicitly; otherwise text
  changes the measured component height.
- Material enforces a 48dp minimum touch target by default. Preserve the guide's explicit compact
  control size while providing an appropriate touch target at the call site where required.
- Material 3 supplies tonal elevation and default shapes implicitly. Set the design-system surface,
  elevation, and shape explicitly so those defaults cannot alter the reference.
- Variable-font weights require `FontVariation.Settings`; a numeric `FontWeight` alone does not
  select the intended bundled Roboto variation.

## Verification loop

1. Add or update a `:designsystem` preview for every implemented variant. Every `@Preview` has a
   matching `@DesignRef(component, variant)`.
2. From `android/`, run `./gradlew :designsystem:recordRoborazziDebug`.
3. Run `./gradlew designCompare`.
4. Read `android/build/design-compare/report.txt`, inspect every labelled `reference | actual`
   image, and write a difference list for padding, radius, font weight, line height, height, and
   color. Use the variant HTML to resolve each numeric difference.
5. Adjust the component and repeat record + compare until every intended variant is paired and the
   difference list is empty. Once a baseline is approved, run
   `compareRoborazziDebug` and `verifyRoborazziDebug` as regression checks.

`designCompare` is a review aid, not a pixel-equality gate. Roborazzi compare/verify checks approved
Android baselines; it does not compare Compose with the HTML export.

## Complete design-to-golden workflow

Use this complete path for an approved visual change. The export refresh remains a separate
design-only change as required by [UPDATING.md](UPDATING.md); the Compose and golden update follows
after that refreshed guide is approved.

1. Change the design in Claude Design, preserving the component and variant annotations.
2. Re-export all three offline pages into `design/export/` and follow the refresh procedure in
   [UPDATING.md](UPDATING.md).
3. Regenerate the references with `cd design/tools && npm run references`. This is the
   ticket-named alias for the existing offline capture (`npm run capture`); from the repository
   root, `pnpm run design:capture` is equivalent.
4. Update the named tokens, stateless components, exact-copy fixtures, screens, and sheets that the
   generated diff requires. Do not infer uncaptured variants.
5. Record the candidate Android baselines from `android/`:

   ```bash
   ./gradlew :designsystem:recordRoborazziDebug :app:recordRoborazziDebug
   ```

6. Review every affected `reference | actual` pair from `./gradlew designCompare`, using the HTML
   for exact measurements. Record the difference list; repeat the component and record steps until
   every intended difference is resolved.
7. Run the regression guard before committing:

   ```bash
   ./gradlew :designsystem:verifyRoborazziDebug :app:verifyRoborazziDebug
   ```

8. Commit the implementation and all approved PNGs under
   `android/designsystem/src/test/snapshots/images/` and
   `android/app/src/test/snapshots/images/`. Never commit files from the comparison-output
   directories. CI runs the same verify tasks and fails if rendering changes without a matching
   reviewed golden update.

## Capture contract

- The design capture defaults to device scale factor 3. Compose uses
  `w390dp-h844dp-xxhdpi`: 390×844 dp at 480 dpi.
- Android captures are dark-only and each preview is wrapped in `SeekerTheme(darkTheme = true)`.
- A component preview owns its complete canvas. The design surface adds no padding, so the PNG is
  the composable's own bounds. Width-dependent components declare `@Preview(widthDp = 358)`.
- `@DesignRef` output is `<component>/<variant-slug>.png`, where the slug is lowercase, `=` and
  whitespace become `-`, non-`[a-z0-9-]` characters are removed, repeated hyphens collapse, and
  edge hyphens are removed.
- Robolectric uses native graphics. Recording and comparison run entirely on the JVM with no
  emulator or device.

The following files are generated and must never be hand-edited:

- `components/**/*.html` and `components/**/*.png`
- `screens/**/*.html` and `screens/**/*.png`
- `tokens.json`, `inventory.md`, and `manifest.json`

The three `export/*.html` files are inputs replaced by a complete re-export, never edited in place.
The hand-maintained, non-capture-generated files are component `spec.md` files,
`components/_template/spec.md`, `navigation.md`, `motion.md`, this `README.md`, `UPDATING.md`,
`CHANGELOG.md`, and `tools/` (with its package lock changed through npm, not by hand). Refresh the
exports and generated files only with [the fixed procedure](UPDATING.md).

## Capture tool first run

From a clean checkout, install the isolated capture tool once:

```bash
cd design/tools && npm install
```

That installs Playwright, sharp, and the pinned Chromium. Then run the repository command from the
root:

```bash
pnpm run design:capture
```

No other manual setup is required. `--dsf` still overrides the default scale of 3.

## Capture and comparison commands

From the repository root:

```bash
pnpm run design:capture
pnpm run design:capture --check
```

From `android/`:

```bash
./gradlew :designsystem:recordRoborazziDebug
./gradlew :designsystem:compareRoborazziDebug
./gradlew :designsystem:verifyRoborazziDebug
./gradlew designCompare
```

`recordRoborazziDebug` writes approved Android PNGs to each module's
`src/test/snapshots/images/<component>/<variant-slug>.png`. The repository's
`pnpm check:android` verifies those committed baselines in CI without an emulator; it never records
over them.

`designCompare` reads `design/components`, writes labelled pairs to
`android/build/design-compare/<component>/<variant-slug>.png`, and writes `report.txt` with paired
files and missing files in either direction. A matching path is paired even when its pixel
dimensions differ, which keeps scale and crop mistakes visible.

## Font and icon probes

`FontWeightProbe` shows bundled Roboto at 400/500/700, Roboto Mono, and the platform default. The
font entries in `Type.kt` set `FontVariation.weight(...)` explicitly for every declared weight.
Native-renderer tests confirm that the Roboto weights are distinct, bundled Roboto differs from the
platform fallback, and equal-length `iiii`/`MMMM` Mono samples have equal advance.

The icon probe renders `close`, `content_copy`, `toll`, `refresh`, and `delete` from the same
Material Icons Outlined set used by the app. It is diagnostic only and is not permission to replace
the icon set when a glyph renders incorrectly.
