# Review sheets

SEE-120 makes Transfer, Swap, Prediction, Signature, and Acknowledge five states of one Android
design-system template. The visual structure is no longer copied between request and signal
screens.

## Design-system contract

`ReviewSheetState` is display-only. It contains the title, headline and subline; ordered origin,
environment, and network chips; optional sandbox and owner-input content; the policy verdict and
rule sources; optional daily-spend rows; informational blocks; facts; unverified note; expiry;
optional warning confirmation; two labelled actions; and the footer caption. It imports no app,
request, proposal, policy, wallet, storage, or transport type.

`ReviewSheet(state, onPrimary, onSecondary, onRules, onChoose, onClose)` is the only renderer. It
composes the atom, molecule, and organism APIs already in `:designsystem`. Its body is deliberately
natural-height and contains no internal scroll container, matching the five unrolled reference
sheets. A bounded runtime host may provide viewport scrolling without changing this template.

SEE-158 adds `ReviewSheetLayout.Pinned`, used by the app's prediction review: grabber and title
stay at the top, the footer at the bottom, and only the body scrolls. `Unrolled` (the default) is
still what previews capture. It also adds optional status blocks, a stale-quote card with a refresh
action, a terms card, copy-on-tap fact rows, a verdict heading override, caller-owned confirmation
state (`confirmationChecked` + `onConfirmedChange`), and the headline's 1.1 line height. See
[prediction-review.md](prediction-review.md).

Warnings always carry confirmation copy. The template owns that transient checkbox state and
keeps the primary button disabled until it is selected. A caller's own `primaryAction.enabled`
gate is applied as well, so an unchosen owner input, missing wallet, stale preparation, or other app
gate cannot be bypassed by checking the warning acknowledgement.

## App mapping boundary

`reviews/ReviewSheetMapper.kt` keeps domain adaptation in `:app`. Direct `ActionRequest` and signal
`OperationReview` overloads are thin adapters into the same private mapper over the SEE-108
`request.v2.Request` envelope. The mapper derives the variant title, headline, facts, chips,
actions, note, expiry, and request-versus-signal footer while runtime-established policy,
inspection, wallet, daily-spend, and owner-choice content stays explicit in
`ReviewSheetMappingContent`.

SEE-120 does not replace the current lifecycle screens. Their existing approval, rejection,
simulation, consent revalidation, and wallet hand-off callbacks remain unchanged; the shared state
and renderer are ready for the screen assembly work owned by SEE-121.

## Fixtures and verification

`ReviewSheetFixtures` contains exact-copy Transfer, Swap, Prediction, Signature, and Acknowledge
states. Five dark previews record under `screens/sheet-*.png` at 390dp wide and natural height.
`designCompare` includes only those five unrolled screen references in addition to the generated
component corpus.

`ReviewSheetTest` verifies that a warning-state primary action cannot
run before confirmation and becomes enabled after confirmation.
