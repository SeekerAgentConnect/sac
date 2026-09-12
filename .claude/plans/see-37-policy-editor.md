# SEE-37 — SAW-027: the policy editor, in stock Material

Build the screen where the owner writes a connection's rules. SAW-025 defined the model and
SAW-026 made the phone apply it; nothing on the phone can create a policy yet, so every connection
is still assessed as having none. This closes that.

## Scope

| This task (SAW-027) | Not this task |
| --- | --- |
| A Rules screen under each connection | Showing an assessment on the review screen (SAW-028) |
| Actions, assets, recipients, programs, thresholds | Changing the policy model or the stored document (SAW-025) |
| Saving to `PolicyStore`, and removing rules | Anything that makes a verdict act |
| A plain-language summary of what the rules say | A custom design system, charts, sliders, canvas |

## Decisions

- **A switch per list, because absent is not empty.** Each list has a switch that turns the check
  on, and the list itself decides what passes. Switch off is no check at all; switch on with an
  empty list allows nothing. The two are never shown alike, and the summary says which it is.
- **One list of assets, with the thresholds on the asset rows.** A threshold belongs to an asset,
  so hanging it off the asset row makes `LimitForUnlistedAsset` structurally impossible rather
  than an error to report. The "only these assets may move" switch decides whether the same list
  also restricts.
- **SOL is typed in SOL; a token is typed in its base units.** The phone knows SOL has nine
  decimals. It does not know a mint's decimal count and must not invent one — a guess that is
  wrong by three places is a threshold wrong by a thousand. Every field shows the exact base-unit
  number that will be stored, under the field, as it is typed.
- **Saving with nothing configured removes the connection's rules.** A stored policy that
  configures nothing and no policy at all assess identically, so the file goes.
- **Unreadable rules are never silently replaced.** The editor refuses to open a blank form over
  them; it says what is stored can't be read and asks the owner to choose to start over.
- **The editor stays in the policy package, and the stage guard keeps it unable to act.** The
  pinned import list grows by three reads (`R`, `BackButton`, `formatInstant`) and by nothing that
  opens a wallet, a connection, or a socket.
- **A connection's rules go when the connection does.** Rules can be created for the first time
  here, so an orphan file is possible for the first time here.

## Implementation

- [x] `policy/PolicyDraft.kt`: the form as the owner is typing it, decimal ↔ base units, and the
      review that says whether it is fit to save
- [x] `policy/PolicyEditorViewModel.kt`: load, edit, save, remove, start over
- [x] `policy/PolicyEditorScreen.kt`: the screen, stock Material 3 only
- [x] `policy/PolicyText.kt`: test tags and the plain-language summary
- [x] `res/values/strings_policy.xml`
- [x] Wire it: a Rules button on Connection details, a route, `SeekerVaultApplication.policyStore`
- [x] Delete a connection's rules when the connection is removed
- [x] `StageBoundaryTest`: the three new reads, and the editor still can't act

## Tests and checks

- [x] `PolicyDraftTest`: decimals, precision, the largest amount, zero, absent vs empty, round trip
- [x] `PolicyEditorScreenTest`: create, edit, cancel, invalid amounts and addresses, empty lists
- [x] `PolicyEditorViewModelTest`: restart, switching connections, unreadable rules, save failure
- [x] `pnpm check`, `pnpm check:android`, `pnpm check:generated`, `pnpm test:hello`,
      `pnpm test:queue`, `pnpm test:transfer`
- [x] A deliberate break makes the relevant check fail

## Documentation

- [x] `docs/guides/policies.md` — the owner's walkthrough
- [x] `docs/policy.md`, `AGENTS.md`, `README.md`, `CODEBASE.md`,
      `docs/development/android.md`, `docs/changelog/2026-09-12.md`

## Review

### What was done

A **Rules** screen under each connection, and the four files behind it, all in the policy package:
`PolicyDraft.kt` (the form, the decimal-to-base-unit conversion, and `review`), `PolicyEditorViewModel.kt`,
`PolicyEditorScreen.kt`, and `PolicyText.kt`, plus `res/values/strings_policy.xml`. The route is in
`SeekerVaultApp.kt`, the store in `SeekerVaultApplication`, and the button on Connection details.
`ConnectionRepository.remove` now deletes a connection's rules with it.

### Judgement calls

- **A switch per list, with all three states said in words.** The absent/empty distinction is the one
  the model turns on, and leaving it to the shape of a blank control would have lost it on screen.
- **Thresholds on the asset rows.** `LimitForUnlistedAsset` becomes impossible to write rather than an
  error to report.
- **A token is typed in base units.** The alternative was asking the owner for a decimal count the
  phone can't verify, or guessing one. Both fail in the dangerous direction; this one fails strict.
  Flagged in the ticket, since a reader may expect decimals everywhere.
- **Chips rather than a dropdown for the chain.** The ticket lists dropdowns among the allowed stock
  components; `WalletScreen` already picks a network with `FilterChip`, and consistency and font
  scaling both favoured matching it. Still stock Material.
- **Saving nothing removes the rules.** A policy that configures nothing and no policy are assessed
  identically, so keeping a file would be keeping a document that says nothing.
- **The editor stays in the policy package**, and the stage guard grew by three reads, so the claim
  that the package can't act or speak now covers the screen the owner writes the rules on.
- **Deleting a removed connection's rules** is slightly outside the ticket's wording. It is in scope
  because rules can be created for the first time here, so an orphan file is possible for the first
  time here — and `PolicyStore`'s own comment already said the rules go when the connection does.

### What was run

`pnpm check` 0, `pnpm check:android` 0, `pnpm check:generated` 0, `pnpm test:hello` 0,
`pnpm test:queue` 0, `pnpm test:transfer` 0. The app's unit suite is 609 tests, 0 failures; 56 are new.
Five deliberate breaks, each failing exactly the intended check, each reverted and diffed clean.

**Device checks: NOT RUN.** The editor has not been opened on the Seeker, at any text size.
`docs/testing/stage-5.md` carries checks 56 to 64, including the larger-system-text one the ticket asks
for, all marked NOT RUN.

### What was deliberately left out

- Showing an assessment anywhere. That is SAW-028, and nothing here reads a verdict.
- Any change to the policy model or the stored document. Version 1 is written back unchanged.
- A decimal count for a token mint, stored or guessed. See above.
