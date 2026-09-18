# SEE-116 — design-guide use and refresh workflow

Linear: https://linear.app/seekeragentwallet/issue/SEE-116/write-designreadmemd-how-agents-use-the-guide-and-designupdatingmd-how

Parent: SEE-110

Branch: `superset/feat/see-116` from `origin/master` at `a2d7b72a01ac5fbf6631c7a210649611d9a1c91f`

## Authoritative inputs

- [x] Read SEE-116 and SEE-110 in full through the configured Linear MCP, including relations.
- [x] Read SEE-111, SEE-113, SEE-114, and SEE-125 plus their comments before implementation.
- [x] The initial `list_comments` sweep found no SEE-116/SEE-111 comments. The final sweep found no
  SEE-116 changes and confirmed the newest SEE-111 comment assigns the capture preservation guard
  wholly to SEE-116. SEE-114 adds no scope; SEE-113/125 confirm exact scale-3.0 output.
- [x] Confirm HEAD, merge-base, and fetched `origin/master` are the same commit.
- [x] Confirm SEE-113/125 capture output and SEE-114/115 Android infrastructure are on master.
- [x] Confirm SEE-111's component specs/navigation/changelog are not on master. This ticket documents
  their intended layout and dependency without creating SEE-111-owned content.
- [x] Record the SEE-111/SEE-116 ownership boundary in a Linear comment on SEE-111.

## Implementation checklist

- [x] Expand `design/README.md` with the fixed component read order, px/dp/sp and scale rules,
  component API boundaries, `:designsystem`-only screen composition, the Roborazzi +
  `designCompare` review loop, Compose traps, generated/hand-written ownership, and missing-token
  escalation.
- [x] Add `design/UPDATING.md` with the ticket's refresh steps 1–9, including export replacement,
  generated diffs, hand-written updates, `design/CHANGELOG.md`, a guide-only PR, follow-up work,
  and deterministic/spec sanity checks.
- [x] Add `design/components/_template/spec.md` with the SEE-111 brief fields while leaving every
  per-component `spec.md` and `design/navigation.md` to SEE-111.
- [x] Make capture regeneration preserve hand-written `spec.md`/`_template` files and make
  `--check` compare only generated component HTML/PNG files.
- [x] Deprecate the legacy numeric `SeekerDimensions` pass-through for new design-system code while
  leaving legacy `:app` screens compiling; make deprecation warnings errors in `:designsystem`.
- [x] Add named Kotlin component-size tokens for `iconSize`, `buttonSize`, and `iconButtonSize`, and
  a unit test that loads canonical `design/tokens.json` and checks spacing, radius, type, and size
  parity in both directions.
- [x] Preserve the existing token-colour diagnostic's 1dp legacy exception and report that 1px is
  absent from `tokens.json`; do not invent a token or another `dpN` value.
- [x] Reference both guides from `CLAUDE.md`; update the design developer docs, root status text,
  `CODEBASE.md`, the 2026-09-19 changelog, and a SEE-116 verification record.
- [x] Re-read SEE-116 and required related comments immediately before the PR; newest comment wins.

## Acceptance criteria (verbatim)

- [ ] One component issue is completed by an agent using only README + its component folder, with
  no extra prompting.
- [x] The `dpN` block is deprecated and unusable from `:designsystem`; the tokens-vs-`tokens.json`
  test exists and passes.
- [ ] A trial refresh (change one button padding in Claude Design, re-export) produces a one-file
  html diff, a failing tokens test if a token changed, and a correct changelog entry.

## Required verification

- [x] Focused `DesignTokensTest` passes.
- [x] Deliberate token mismatch makes `DesignTokensTest` fail, then the clean test passes again.
- [x] A deliberate new legacy-dimension use in `:designsystem` fails compilation, then the clean
  compile passes again.
- [x] `pnpm run design:capture --check` passes with `_template/spec.md` present and unchanged.
- [x] Two capture checks/runs are deterministic and preserve hand-written component files.
- [x] `:designsystem:recordRoborazziDebug` then `designCompare` exercise the documented loop.
- [x] `pnpm run check:android` passes.
- [x] `pnpm run build` passes.
- [x] Documentation formatting and lint checks pass; final `git diff --check` follows the review edit.
- [x] Re-read SEE-116, SEE-111, and SEE-114 comments before hand-off and PR.
- [x] Review every ticket item below as PASS, FAIL, or NOT RUN.

## Review

- **PASS:** README, UPDATING, the spec template, and CLAUDE loading rules contain the complete
  ticket contract without taking ownership of SEE-111's per-component specs/navigation/changelog.
- **PASS:** capture regeneration preserves hand-written component files and compares only generated
  HTML/PNG; a full check and two normal captures left generated output unchanged.
- **PASS:** named Kotlin spacing, radius, type and size tokens exactly match `tokens.json`; a
  deliberate drift failed and the restored contract passed.
- **PASS:** new `dpN` use in `:designsystem` is a compile error while legacy `:app` screens still
  compile. The one pre-existing token-colour diagnostic uses a local, documented `dp1` exception
  because the design has no 1px token; no token was invented.
- **PASS:** record + designCompare, Android verification, formatting, lint, and build completed.
- **NOT RUN:** a real component implementation is blocked on SEE-111's missing component specs.
- **NOT RUN:** an external Claude Design button-padding refresh was not available; deterministic
  capture and the failing token-drift control exercise the repository-owned parts of that path.
