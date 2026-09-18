# Design export capture

SEE-113 turns the three checked-in Claude Design offline bundles under `design/export/` into the generated references consumed by the design-guide and component tasks. Playwright is only a headless Chrome for those HTML exports; it never opens, drives, or compares the Android app.

## Set up the capture tool

The tool is an independent npm package so Android/component work can use the committed output without installing Node or a browser. From a clean checkout, install it once:

```bash
cd design/tools && npm install
```

`postinstall` downloads the pinned Chromium. Playwright and sharp are pinned in `design/tools/package-lock.json`. Roboto Mono is also pinned locally at `design/tools/fonts/RobotoMono-Latin-400-700.woff2`; the bundled exports already contain Roboto and Material Symbols Outlined.

See `design/README.md` for the First run commands.

## Capture and check

From the repository root, either command performs the complete capture:

```bash
npm --prefix design/tools run capture
npm run design:capture
```

The capture runs with networking disabled, forces the dark theme and reduced motion, waits for the unpacker/runtime and all three fonts, and defaults to device scale factor 3. Playwright's element screenshot is cropped in device pixels from the fractional bounding box so the PNG is `round(bbox × dsf)` rather than the enclosing integer CSS rect. Options are passed after `--`:

```bash
npm run design:capture -- --only components
npm run design:capture -- --only screens
npm run design:capture -- --only tokens
npm run design:capture -- --dsf 3
npm run design:capture -- --check
```

`--check` writes a fresh capture to a temporary directory and byte-compares it with the committed output. It compares PNGs as well as text and ignores only `manifest.json`'s `capturedAt`. A normal unchanged capture preserves the existing `capturedAt`, so a second run leaves the worktree clean.

## Generated contract

- `design/components/<component>/<variant>.png` and `.html` contain every tagged specimen. The HTML is a stable rendered DOM extract: CSS variables are resolved from the specimen, event/React bookkeeping attributes are removed, and inline declarations are one per line.
- `design/screens/` contains all 16 documented scenes, the complete unrolled request rail, and five unrolled review sheets. The 12 fixed flow scenes are selected by their exact captions. The four other review states are opened from the interactive `app.html` request rail.
- `design/tokens.json` is read from the rendered token and component specimens and self-checks the current type/button/icon geometry.
- `design/inventory.md` is the sorted 176-specimen index with original labels, slugs, CSS-pixel bounds, final PNG pixel size and links.
- `design/manifest.json` records every input hash/size, Chromium, viewport, density, injected font and capture-tool version.

The command fails on an unpacker error, a missing required font, an unstable or empty render, a component slug collision, a missing required component, an unexpected scene mapping, or stale committed output.
