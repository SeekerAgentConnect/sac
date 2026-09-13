# SEE-35 — SAW-025: the policy model and explicit evaluation semantics

Stage 5 opens. This task defines *what a policy is* and *how a verdict is reached*. It does not
evaluate a real request (SAW-026), does not build the editor (SAW-027), and does not put anything
on the review screen (SAW-028).

## Scope boundary

| This task | A later task |
| --- | --- |
| The policy document, its validation, and its per-connection storage | The editor that writes one (SAW-027) |
| The verdict types, the reason codes, and the conjunction that combines checks | Computing each check from a request and its parsed transaction, and the daily counters (SAW-026) |
| `docs/policy.md`: schema, defaults, precedence, advisory behaviour | `docs/guides/policies.md`, the owner's walkthrough (SAW-027) |

## Items

- [x] `policy/Policy.kt`: `ConnectionPolicy`, `Allowlist`, `PolicyAsset`, `PolicyAction`,
      `AssetLimits`, and validation (`PolicyProblem`). Absent (`null`) is not empty.
- [x] `policy/PolicyDecision.kt`: `PolicyAssessment` (no BLOCKED), `PolicyCheck`,
      `PolicyCheckStatus`, `PolicyReason` with stable codes, `PolicyDecision`, and `assess()` —
      the conjunction, plus the decisions for no policy and an unreadable one.
- [x] `policy/storage/PolicyStore.kt`: one atomic JSON file per connection, a document version with
      the migration rule, and `StoredPolicy` (`None`, `Policy`, `Unreadable`).
- [x] `wallet/Base58.kt`: `isSolanaAddress`, the address rule the sidecar already applies.
- [x] Tests: `policy/PolicyTest`, `policy/PolicyDecisionTest`, `policy/storage/PolicyStoreTest`.
- [x] `StageBoundaryTest`: `policy/storage/` joins the storage packages; no automatic approval and
      no BLOCKED branch anywhere in the policy package.
- [x] `docs/policy.md`, and the pointers to it: `AGENTS.md`, `README.md`, `docs/architecture.md`,
      `docs/security.md`, `CODEBASE.md`, `docs/changelog/`.
- [x] `pnpm check:android`, `pnpm check`.

## Decisions

- **Absent versus empty.** `null` is "not configured": the check doesn't run and is reported as a
  coverage gap. An empty `Allowlist` is configured and matches nothing.
- **No policy is never ALLOWED.** A connection with no policy, or one whose every check is
  unconfigured, assesses as UNDER_RESTRICTIONS with `no_policy_configured`.
- **Unreadable is its own answer.** A damaged document, or one from a newer version, is neither a
  policy nor the absence of one. Reading it as "no policy" would quietly drop the owner's rules,
  and letting an older build edit a document it can only partly represent would delete them.
- **Coverage is separate from the verdict.** A check the phone couldn't verify fails the
  conjunction with its own reason; it never passes silently.
- **Advisory.** `PolicyDecision` carries no executable branch: nothing in it can approve, and
  nothing in it can block. The owner decides either way.

## Review

**Done.** Stage 5 is open, at the model and nothing more. `policy/Policy.kt`, `policy/PolicyDecision.kt`, and `policy/storage/PolicyStore.kt`, with 38 tests across `PolicyTest`, `PolicyDecisionTest`, and `PolicyStoreTest`, plus a new `StageBoundaryTest` case. `docs/policy.md` is the schema, the defaults, the semantics, the precedence, and the advisory rule; `AGENTS.md`, `README.md`, `CODEBASE.md`, `docs/architecture.md`, `docs/security.md`, `docs/development/android.md`, and the changelog point at it.

**Ran:** `pnpm check`, `pnpm check:android`, `pnpm test:hello`, and `pnpm test:queue`, all green. Three deliberate breaks — `assess` allowing an unconfigured policy, `policyProblems` dropping the zero-limit check, and `PolicyStore` reading a rule it doesn't know as a shorter list — each failed exactly one test and no others. A fourth, an import of `connections.Connection` into the policy package, failed the new boundary case.

**One thing worth saying plainly:** this branch was three commits behind `master`, which held all of Stage 4 as rebased copies. `develop`'s tip tree was byte-identical to master's copy of SEE-26, so the merge here takes master's tree verbatim; SAW-025 is built on Stage 4, as its dependency requires.

**Deliberately not done, and left to the tasks that own them:** nothing evaluates a request against a policy (SAW-026), nothing writes one (SAW-027), and nothing shows an assessment (SAW-028). `PolicyStore.delete` exists but isn't wired into `ConnectionRepository.remove`: no policy can be created yet, so there is nothing to orphan, and the lifecycle belongs with the editor.

**A judgement call in the model:** a stored rule this build has no name for makes the whole document `Unreadable` rather than a shorter allowlist. Dropping an allowlist entry is strictly safer on its own — it can only make the policy stricter — but a policy shown to the owner with a rule missing is a policy they will save back, and the rule is then gone. Refusing to read it is the answer that can't quietly delete anything.
