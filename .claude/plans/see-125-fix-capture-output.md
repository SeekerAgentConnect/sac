# SEE-125 — exact-size crops, scale 3, clean-checkout setup

Ticket: https://linear.app/seekeragentwallet/issue/SEE-125/fix-capture-output-exact-size-crops-scale-3-by-default-clean-checkout

Read through Linear MCP `get_issue` + `list_comments` on 2026-09-19. SEE-125 comments: none. SEE-113 newest comment (wins on scale): default `deviceScaleFactor` is 3.0, not 2.625.

Workspace HEAD `c2484f7` equals `origin/master`. SEE-113 is already on master via PR #28 (`55dfe76`). No cherry-pick required.

## Acceptance (copied from the ticket)

- [x] `button/variant-filled-size-lg.png` is exactly 144 px high and 505 px wide (168.484 × 3, rounded).
- [x] For every specimen: PNG size = round(bounding box × 3). Checked by the script itself, non-zero exit on mismatch.
- [x] Phone-frame screen captures are exactly 1170 × 2532 px.
- [x] `grep -rl --include='*.html' 'var(--\|{{' design/components design/screens` prints nothing.
- [x] Running capture twice leaves `git status --short design/` empty.
- [x] Clean checkout → `npm install` in `design/tools` → capture succeeds with no extra manual step.

## Work

- [x] Exact crop with sharp after each element screenshot; assert uncropped size = enclosing integer rect × dsf.
- [x] Deterministic PNG (fixed sharp options, no metadata, no timestamps).
- [x] `inventory.md` includes final PNG pixel size next to the CSS bounding box.
- [x] Default `deviceScaleFactor` = 3; `--dsf` stays; `manifest.json` records 3.
- [x] `design/tools/package.json`: `postinstall` already installs Chromium; add `sharp`; commit lockfile.
- [x] `design/README.md` First run section.
- [x] Regenerate and commit `design/components`, `design/screens`, `tokens.json`, `inventory.md`, `manifest.json` at scale 3.
- [x] Re-read Linear comments before the PR.

## Review

Linear `list_comments` on SEE-125 was empty before implementation and again before the PR. SEE-113's newest comment (scale 3.0) stands.

SHA `3ed9cf4`. Evidence in `docs/testing/see-125.md`.

## Out of scope

SEE-116's full agent README, except the First run section this ticket names and the hug-content note it puts in that file.
