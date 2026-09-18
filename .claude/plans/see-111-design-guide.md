# SEE-111 — Prepare the in-repo design guide

Ticket: https://linear.app/seekeragentwallet/issue/SEE-111/prepare-the-in-repo-design-guide-design-from-the-claude-design-export

Source: the full Linear issue, relations, and comments were read through Linear MCP on 2026-09-19. The newest SEE-111 coordination comment limits this change to component specs, navigation, the token mismatch report, and the initial changelog; SEE-116 owns README, UPDATING, the template, token enforcement, and all capture-tool changes. The newest SEE-113 comment overrides its body by fixing the capture scale at 3.0; SEE-125 records that the exact-crop capture output is complete on master.

## Plan

- [x] Run the capture command from the committed SEE-113/SEE-125 tooling and confirm generated output is deterministic at device scale factor 3.
- [x] Inventory every ticket-listed `data-component`, its variants, rendered builder notes, and permitted component dependencies.
- [x] Add one `design/components/<name>/spec.md` per ticket-listed component with tier, purpose, Kotlin composable signature, enum-backed `data-variant` dimensions, allowed dependencies, builder notes, and open questions.
- [x] Keep `request-tile` as one component whose state covers rail and centred presentations.
- [x] Write `design/navigation.md` from the exported user-flow page, including destination type, entry points, stacking, and exit behavior.
- [x] Cross-check `design/tokens.json` against the existing Kotlin design-system tokens and record every mismatch without changing Kotlin.
- [x] Coordinate with parallel SEE-116 and avoid duplicating its `design/README.md`, `design/UPDATING.md`, template, and token-enforcement work.
- [x] Update `CODEBASE.md` and the dated changelog for the shipped guide.
- [x] Verify guide completeness, references, generated-file invariants, formatting, and the repository build; record every ticket item as PASS, FAIL, or NOT RUN.
- [ ] Re-read SEE-111 and relevant dependency comments immediately before handoff, self-review the diff, commit, push, open one PR to `master`, move SEE-111 to In Review, and add the evidence comment.

## Acceptance criteria (Linear, verbatim)

- [x] A component task can be completed using only `design/components/<name>/` plus `tokens.json`.
- [x] request-tile is one component with rail / centred state, not two.
- [ ] Guide reviewed by Renat before SEE-117 starts.

The third criterion requires Renat's review and will remain **NOT RUN** at PR handoff; this ticket prepares the guide for that review and does not claim the review happened.

## Review

- **PASS — component guide:** 34 ticket-listed contracts each contain a tier, purpose, standalone Kotlin API/content model, enum mapping, exhaustive dependency list, exact HTML/PNG links, the rendered builder note, and open questions. A local validator confirmed that every captured file in each component folder is linked exactly once.
- **PASS — request-tile:** one `RequestTile` contract maps all ten `kind × rail state` captures; `InRail` and `Centred` are values of one enum.
- **PASS — capture references:** the complete scale-3 capture and fresh byte comparison passed during guide generation (176 component specimens, tokens, 22 screen references). The committed generated references remain unchanged; the newest coordination comment assigns every capture-tool change to SEE-116.
- **PASS — repository checks:** no unresolved `var(--` or `{{` remains in rendered HTML; `pnpm run check:format`, `pnpm run check:lint`, and `pnpm run build` pass. Tests were **NOT RUN** because the repository instructions permit them only when explicitly requested.
- **PASS — coordination:** SEE-116 was still in progress at handoff. This change does not touch its README, UPDATING guide, template, CLAUDE wiring, token enforcement, capture tool, generated inventory, or generated references. The SEE-116 agent received the final ownership boundary through Superset orchestration.
- **NOT RUN — Renat review:** the PR is the review handoff. SEE-117 must not start until Renat reviews this guide.
- **Self-review:** no Kotlin changes; no raw export edits; `icon-button` stays captured but has no spec because it is absent from SEE-111's authoritative component list, and every consumer records that gap as an open question.
