# SEE-38 / SAW-028 — the assessment on the request-review screen

Stage 5's last task. SAW-025 gave the model, SAW-026 made the phone apply it to a real request and
count the day, SAW-027 gave the owner the editor. A verdict has been computed since SAW-026 and
shown nowhere. This puts it on screen, and makes going ahead past a warning something the owner
does on purpose.

## Scope

| This task (SAW-028) | Not this task |
| --- | --- |
| The assessment on Request details, for every kind of request | Any change to the model, the evaluation, or the counters |
| Each check named, with what it read, in words | A verdict that decides anything — there is still no BLOCKED |
| An explicit "despite warnings" step before an affirmative answer | Softening input validation: a malformed preparation stays non-executable |
| Re-checking when the rules or the preparation change | Uploading anything to the sidecar — the rules never leave the phone |
| The displayed snapshot kept with the history record | Swaps (Stage 6), which stay unread and so never ALLOWED |

## Decisions

- **The verdict is read, never kept to act on.** `PolicyEvaluator` re-reads the rules and the
  records on every call. The screen's assessment is a snapshot of a reading, and the approval path
  reads again and refuses if the answer changed.
- **No rules at all is not a warning.** Every request on a phone with no rules is
  UNDER_RESTRICTIONS for want of any; gating those would make the tick a ritual. `PolicyDecision.warns`
  is UNDER_RESTRICTIONS for any other reason — including rules this build can't read.
- **Consent is to the reasons, not to the request.** The tick is bound to the exact
  `PolicyDecision` it was given for. A different assessment drops it.
- **Input validation comes first and is never relabelled.** A preparation this phone couldn't
  account for has no Approve button at all, whatever the policy made of it, and the policy block
  never claims to be the reason.
- **The record keeps codes, never rules.** A stored snapshot is the verdict's code, the reason
  codes, the uncovered checks, and whether the owner went ahead anyway. No threshold and no
  allowlist is written into the history, and nothing policy-derived reaches the gateway.
- **The record's document version stays 1.** The field is additive and optional; bumping it would
  make an older build drop the whole record, and a dropped record is a spend the day's counters
  never see.

## Items

- [x] `PolicyDecision.warns`: UNDER_RESTRICTIONS with something to warn about
- [x] `ReviewedPolicy` on `ActivityRecord`, written and read by `ActivityStore` at version 1
- [x] `ActivityLog.reviewed(key, snapshot)`, carried forward when a record is written again
- [x] `RequestAssessment` and the inbox state: assessments, acknowledgements
- [x] `InboxViewModel.review(key)`, run on open, after every preparation, and on returning
- [x] The acknowledgement dropped whenever the assessment changes
- [x] `answer`/`approve`/`approveTransfer` re-read the rules and refuse a stale or unticked review
- [x] `SigningProblem.RulesChanged` and `SigningProblem.NotAcknowledged`
- [x] `PolicyReview` on Request details: verdict, every check, what couldn't be checked, the line
      that never changes
- [x] The programs a transaction calls, on screen
- [x] The tick and the "despite warnings" buttons, next to what they approve
- [x] The stored snapshot on Activity details
- [x] Wiring: the evaluator in the application, the log in the inbox ViewModel
- [x] Tests: allowed, threshold warning, recipient warning, unverified content, malformed
- [x] Tests: no wallet invocation on open, refresh, evaluation, or a rule match
- [x] Tests: rules changed under an open review; nothing policy-shaped reaches the gateway
- [x] Docs: `docs/guides/policies.md`, `docs/security.md`, `docs/policy.md`, `docs/testing/stage-5.md`,
      changelog, `AGENTS.md`, `CODEBASE.md`, `README.md`

## Review

### What was done

The assessment is on Request details, under everything the phone established for itself. `PolicyReview`
shows the verdict in words, then all six checks with what each read and what became of it, then the
checks nothing covered, then the line that never changes. Nothing is said by colour alone.

An affirmative answer to a request the assessment warns about waits for `ApproveAnyway` — a toggleable
row, so the words and the box are one thing to a screen reader — beside a button that says what it
would be doing. Reject never waits. The tick is bound to the `PolicyDecision` it was given for, so a
new preparation, an edited rule, or a moved counter clears it.

`InboxViewModel.cleared` reads the rules and the records again at the moment the owner answers and
compares them with what was on screen. Different means the answer stops, the review is replaced, and
`SigningProblem.RulesChanged` says why. The advisory check runs after the inspection check, so a
malformed preparation still reports `NotVerified` and is never relabelled.

`ReviewedPolicy` keeps the codes of what was read with the Activity record, carried through every later
write of it, and `ActivityDetailsScreen` reads it back.

### Judgement calls

- **Having no rules is not a warning.** Gating every request on a phone with no rules would make the
  tick a ritual and teach the owner to tick without reading. `PolicyDecision.warns` excludes only
  `NoPolicyConfigured`; unreadable rules still warn, because there something was written.
- **The tick gates every affirmative answer, not just the ones that reach a wallet.** Acknowledging is
  still answering an agent, and the action rule exists precisely so an unexpected kind of request gets
  pointed out. One rule with no exceptions to explain.
- **The snapshot is codes, not rules.** It keeps the record small, keeps the wording free to improve,
  and means the history has no second copy of a threshold or an address to keep in step or to leak.
- **The record's document version stays 1.** The field is additive and optional. A bump would make an
  older build drop the whole record, and `ActivityStore.listFor` drops an unreadable file silently —
  which would quietly under-count the day's spending.
- **The gate lives in the ViewModel as well as the screen.** A disabled button is a UI state; the
  re-read and the refusal are the guarantee, and they are what the tests hold.
- **Renamed `InboxTags.TRANSFER_POLICY` to `TRANSFER_NOT_APPROVABLE`.** It tags the input-validation
  message, and with a real policy block on the same screen the old name would actively mislead.

### What was run

`pnpm check` 0, `pnpm check:android` 0, `pnpm check:generated` 0, `pnpm test:hello` 0, `pnpm test:queue`
0, `pnpm test:transfer` 0. The app's unit suite is 642 tests, 0 failures — 34 new, 1 removed (the
"Not evaluated" assertion, whose subject no longer exists).

Seven deliberate breaks, each failing exactly the intended check and each reverted and diffed clean:
`warns` true for no rules (38 cases), the acknowledgement check skipped, the changed-assessment check
skipped, a later record write dropping the snapshot, Approve enabled with a warning unticked, a
matching policy restoring the Approve button, and a sidecar-facing file importing a policy.

### What was deliberately left out

- **Device checks: NOT RUN.** Checks 65 to 72 are written down and none has been on the Seeker.
- No change to the model, the evaluation, or the counters. SAW-025 and SAW-026 stand as they were.
- No `PolicyEvaluation` on the wire. The protocol has the message and nothing sends one; that is not
  this task's to change, and the ticket says the rules must not be uploaded.
- Swaps are still unread, so they are still never `ALLOWED`. Stage 6 reads them.
