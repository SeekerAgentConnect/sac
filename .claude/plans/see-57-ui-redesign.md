# SEE-57 — Seeker Agent Connect UI/UX redesign

A restyle of screens that already work. No new capability, no change to request
decoding, rule storage, rule evaluation, pairing, or the wallet hand-off.

The two product rules that survive it: nothing is ever auto-approved, and rules
are advisory — they classify during review and approve, block, delay and filter
nothing.

## Plan

- [x] 1. `ui/` design-system package: Nocturne tokens, dark theme, lit ground,
      glass surfaces, floating header, floating tab bar, pills, chips, the
      switch/checkbox/radio, monospace identifiers, motion specs, icon set.
- [x] 2. App shell: tabs (Home, Requests, Wallet, Activity) over the existing
      route stack; every screen on the new chrome.
- [x] 3. Home: wallet card, "Waiting for you", the browse-only request
      carousel, paired servers, Add connection.
- [x] 4. Requests: swipe-to-answer rows with revealed cues, the rule pill, and
      the answered list.
- [x] 5. Request review: the layered panel — grab handle, pager, headline,
      body cards, action row, horizontal/vertical drag — and the wallet
      hand-off sheet that stacks in front of it.
- [x] 6. Activity, Connection details, Add connection / Scan.
- [x] 7. Rules: intent card, "What these rules say", four section cards, the
      new copy, and the Add an asset dialog with thresholds.
- [x] 8. Tests for every behaviour that changed, then `pnpm check`,
      `pnpm check:android`.
- [x] 9. Docs: the design system page, the guides the copy touches, AGENTS.md's
      UI rule, CODEBASE.md, and a changelog entry.

## Decisions

- **AGENTS.md's "stock Material 3 only" rule is replaced, not broken.** The
  ticket supersedes it; the rule becomes "one design system, in `ui/`, and no
  screen invents its own values".
- **The accent is one token.** `Accent` in `Tokens.kt`; the palette study
  changes that line and nothing else.
- **Blur degrades, layering does not.** `Modifier.blur` where the platform has
  it, a flat translucent fill of the same value where it doesn't.
- **The wallet sheet announces the hand-off; it never imitates the wallet.**
  The prototype's sheet has Sign / Decline buttons because it is a prototype
  with no wallet behind it. Here the real wallet app answers, so the sheet
  carries the hand-off copy and the state, and no control that looks like the
  wallet's own. Faking those would be the one thing this screen exists to
  prevent.
- **Inter is not bundled.** The type scale, weights and tracking are
  reproduced on the platform sans; no font binary is committed.

## Review

All nine items landed. The checks that ran, and their results:

| Check | Result |
| --- | --- |
| `pnpm check` | Pass |
| `pnpm check:android` | Pass — 681 unit tests, lint clean, debug and instrumentation APKs built |
| `pnpm check:generated` | Pass — nothing generated changed |
| `pnpm test:hello` | Pass |
| `pnpm test:queue` | Pass |
| Device checks on the Seeker | **NOT RUN** — no physical device in this environment |

### Where the prototype and the shipped app disagreed

Four places, each called out in the changelog and the PR:

1. **A right swipe on a transfer or a signature opens the review instead of going to the wallet.**
   There is no transaction to hand a wallet until the review asks for one, and a message's bytes
   are not on the row. Left still rejects anything; right still acknowledges an acknowledgement
   outright.
2. **The review stays open after the wallet answers**, because the outcome — the signature, whether
   it was sent, and Check status — appears there at exactly that moment.
3. **Every paired server opens**, including a disconnected one: its details are where the owner
   removes it.
4. **The rule card's closing line keeps the wallet in it**, because that line is the app's standing
   promise that the wallet asks again, and a test holds it to saying so.

### What is not in this change

- **Inter is not bundled.** The scale, weights and tracking are reproduced on the platform sans.
- **No backdrop blur.** Compose has none that works across the versions this app supports, so the
  glass fills are flat translucent at the same values — the fallback the handoff calls for. The
  layering is intact, and each surface still carries the radius the design asks for.
- **The accent is still `#9184d9`.** The palette study lands as one token.
