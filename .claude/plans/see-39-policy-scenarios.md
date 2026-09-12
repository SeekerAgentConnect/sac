# SEE-39 — SAW-029: run policy scenarios and document the limits honestly

QA and documentation. Nothing in the policy model, the evaluation, the editor or the review
changes: this ticket demonstrates what SAW-025 … SAW-028 built, end to end, from real bytes, and
writes down what it does and does not do.

## Scope

| This task | Not this task |
| --- | --- |
| Deterministic scenarios from real transaction bytes to a verdict and its reason codes | Any change to `evaluate`, the checks, or the model |
| A shared fixture for prose that disagrees with the bytes | New checks, new reasons, new verdicts |
| Two connections, and a restart, through the same path | The editor (SAW-027) or the review screen (SAW-028) |
| The owner's worked examples, and the honest limits | Enforcing anything: a counter is still a floor, never a ceiling |

## Decisions

1. **A scenario is the whole path, not another unit test.** `PolicyFixtures.kt` already holds the
   table of verdicts against invented facts. A scenario starts at bytes the sidecar really built,
   inspects them the way the review screen does, reads the rules off a real store on disk, counts
   the day from real Activity records, and ends at the verdict with its exact reason codes. That is
   what makes it demonstrable rather than merely tested.
2. **Every scenario asserts both halves.** The classification and the exact reason codes, in order,
   and the checks the assessment doesn't cover. A verdict with the wrong reasons is a wrong verdict.
3. **A scenario also records what does not happen.** Whether the transfer was approvable at all,
   and whether the review would ask the owner to tick — so a scenario that stops being a warning
   fails here rather than quietly changing what the owner is shown.
4. **The tampered fixtures are metadata, not malformed bytes.** Prose that disagrees with the
   amount, a familiar ticker on an unrelated mint, and an allowed program carrying a different
   operation. Two already exist; the third is added to the shared builder and rebuilt.
5. **"Safe" is not a word this app uses about a verdict.** A test holds the policy strings to it, so
   the wording can't drift back in a later change.

## Items

- [x] Add `note_disagrees_with_the_amount` to `sidecar/src/testing/transaction-fixtures.ts` and rebuild `fixtures/transactions/cases.json`
- [x] New `policy/PolicyScenarioTest.kt`: the five scenarios, each asserting verdict, reason codes, uncovered checks, approvability, and whether it warns
- [x] The three tampered-metadata scenarios in the same suite
- [x] Two connections, each assessed against its own rules and its own day, through the real store
- [x] A restart: the same scenario reaching the same verdict off a new store over the same directory
- [x] A counter that cannot see a wallet's own activity, asserted where the owner would read it
- [x] Assessing a scenario writes nothing to disk
- [x] A wording test: no policy string calls a verdict safe, secure, or automatically approved
- [x] `docs/guides/policies.md`: worked examples — setting the rules, reading each label, going ahead anyway, rejecting
- [x] `docs/policy.md`: the scenarios in Test fixtures, and the file table
- [x] `docs/testing/stage-5.md`: the SAW-029 table, the deliberate breaks, and the device checks
- [x] `docs/testing/transaction-fixtures.md`: the new case
- [x] `AGENTS.md`, `README.md`, `CODEBASE.md`, and the changelog
- [x] `pnpm check`, `pnpm check:android`, `pnpm check:generated`, `pnpm test:hello`, `pnpm test:queue`, `pnpm test:transfer`
- [x] Deliberate breaks, each run and reverted
- [x] Commit on `develop`, and set SEE-39 to In Review

## Review

### What was done

No production file changed. Two test suites, one shared fixture, and the documentation.

- **`policy/PolicyScenarioTest.kt`** — six scenarios, each the whole path: a transaction the sidecar
  really built, inspected the way the review screen inspects it, against rules read off a real
  `PolicyStore` on disk, with the day counted from real `ActivityRecord`s. Each asserts the
  classification, the exact reason codes in order, the checks the assessment doesn't cover, whether
  input validation left the transfer approvable, and whether the review asks for a tick. Around them:
  every scenario read again under a second connection with no rules, every scenario read again off a
  new store over the same directory, two connections whose different thresholds read the same
  transfer differently, a day this app never saw, an assessment that leaves the file byte for byte
  as it was, and codes that carry nothing the owner wrote down.
- **`policy/PolicyWordingTest.kt`** — every policy-facing string against *safe*, *secure*,
  *automatic*, *blocked*, *denied*, *guaranteed*, *protects*, *prevents*; and both verdicts having to
  name the rules and say who approves.
- **`note_disagrees_with_the_amount`** in `sidecar/src/testing/transaction-fixtures.ts`, rebuilt into
  `fixtures/transactions/cases.json`. The same bytes as `sol_transfer`, with a note saying a tenth of
  what the instruction carries. The other two tampered-metadata fixtures the ticket asks for already
  existed: `fake_ticker_in_the_note` and `token_delegate_alongside_the_transfer`.
- **Docs** — the owner's worked examples and the counter's honest limits in
  `docs/guides/policies.md`, the scenarios in `docs/policy.md`, the SAW-029 tables, six more
  deliberate breaks, device checks 73–78 and a verification record in `docs/testing/stage-5.md`, the
  new fixture in `docs/testing/transaction-fixtures.md`, and `AGENTS.md`, `README.md`, `CODEBASE.md`
  and the changelog.

### Judgement calls

- **A scenario starts at bytes, not at facts.** `PolicyFixtures.kt` already holds the table of
  verdicts against invented facts, and repeating it would have proved nothing new. What was missing
  was the join: that the bytes the sidecar produces, read by this phone, against rules a real editor
  could write, reach the verdict the guide says they do.
- **The pair is the demonstration.** Scenario 2 (a warning, still approvable) and scenario 8 (every
  rule matched, no Approve button) say the whole precedence rule between them. The suite asserts
  both exist, so neither can be deleted quietly.
- **The wording test is scoped by string name, not by file.** `policy_*`, `activity_policy_*`,
  `approve_*`. Three strings elsewhere legitimately say *blocked*, *insecure* and *securely* — about
  cleartext HTTP and a Keystore failure — and they are not about a verdict.
- **The worked examples quote the screen, not a paraphrase of it.** Where the check-level reason
  sentences are not on the review screen — only the check's status and what it read — the guide says
  so, and says the sentences are what **Activity** keeps.
- **The two device checks the ticket asks for became six.** ALLOWED plus manual approval and
  UNDER_RESTRICTIONS plus rejection are 74 and 75; walking the guide's own examples (73), two
  connections (76), a force-stop (77), and reading the screens for a word that claims too much (78)
  are the rest.

### What was run

`pnpm check` 0 · `pnpm check:android` 0 (654 tests, 12 new) · `pnpm check:generated` 0 ·
`pnpm test:hello` 0 · `pnpm test:queue` 0 · `pnpm test:transfer` 0. Six deliberate breaks, each
applied, run, confirmed to fail the recorded tests and nothing else, and reverted byte for byte.

**Device checks: NOT RUN.** Checks 56–78 have not been done on the Seeker.

### What was deliberately left out

- No change to the model, the evaluation, the counters, the editor, or the review. SAW-029 is QA and
  documentation, and a scenario that needed a production change would have been a bug report, not a
  scenario.
- No screenshots. The repository has none, and adding image files to document a screen a test already
  drives at twice the system text size would be a second copy to keep in step.
- No new reason codes, checks, or verdicts.
