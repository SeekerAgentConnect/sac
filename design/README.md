# Design guide

Generated references from the Stage 7.2 Claude Design export. How agents use this directory, and how to refresh it, is SEE-116.

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

Heights, paddings, radii, and fixed widths are exact targets. Widths of hug-content components (buttons, chips) come from Chrome's text shaping and will differ from Compose by a pixel or so; treat them as approximate.
