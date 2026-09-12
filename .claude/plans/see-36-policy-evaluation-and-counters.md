# SEE-36 / SAW-026 — Deterministic policy evaluation and daily counters

Depends on SAW-025 (`policy/Policy.kt`, `policy/PolicyDecision.kt`, `policy/storage/PolicyStore.kt`).

## Scope

| This task (SAW-026) | Not this task |
| --- | --- |
| Evaluate a request's **parsed facts** against a stored policy | The editor that writes a policy (SAW-027) |
| Daily counters from the app's own activity records | The review screen that shows the verdict (SAW-028) |
| Confirmed vs unresolved exposure, kept apart | Anything that approves, rejects, or opens a wallet |
| The evaluator that re-reads store and records on every call | Sending an assessment to a sidecar |

## Decisions

- **Facts, never prose.** The evaluator takes a `RequestFacts` built from what the phone read out
  of the transaction's own bytes (`TransferInspection`), plus the request's action kind and the
  selected wallet's network. The sidecar's description of what it built is not an input.
- **Unread bytes are never ALLOWED.** A transaction with an instruction the phone couldn't read
  withholds the verdict (`request_unverified`), whatever rules matched the part it did read.
- **An action that moves nothing passes the value checks vacuously**, with a detail saying so, and
  `notChecked` still reports what nobody configured.
- **A day is a local day**, in the phone's own zone, computed at read time from `answeredAt` — the
  moment the owner answered, which doesn't move when a status is checked later.
- **Confirmed and unresolved are separate numbers.** The projected total is their sum plus the
  request in hand; it warns, and it never claims unresolved money is spent.
- **A counter counts once per request, and once per signature.** Re-preparing, re-sending, and
  re-checking are one thing that happened.
- **A rejection is not a transfer**, and neither is a transaction the chain ran and failed.

## Items

- [x] `policy/DailySpending.kt` — `SpendScope`, `SpendStatus`, `Spend`, `DailyTotal`, `spendsOf`
- [x] `policy/RequestFacts.kt` — the facts, and the adapter from a request and its inspection
- [x] `policy/PolicyEvaluation.kt` — `evaluate`, the six checks, and `PolicyEvaluator`
- [x] `TransferFacts` carries the programs the transaction calls
- [x] `PolicyReason.RequestUnverified`
- [x] Test fixtures: `policy/PolicyFixtures.kt`, run by `PolicyFixturesTest`
- [x] Tests: boundaries, decimals, midnight, zone change, restart, state transitions, duplicates
- [x] Tests: two connections one wallet; two wallets one mint
- [x] Tests: unknown coverage never ALLOWED; no wallet action
- [x] `StageBoundaryTest` updated for the package's new reads
- [x] `docs/policy.md`: counters, the day boundary, known limits, the fixtures
- [x] `AGENTS.md`, `README.md`, `CODEBASE.md`, `docs/architecture.md`, changelog
- [x] `pnpm check`, `pnpm check:android`, deliberate-break verification
- [x] Commit on `develop`; Linear to In Review

## Review

### What was done

Three new files in `policy/`, all of them reads:

- **`RequestFacts.kt`** — what the phone established about one request, and `policyFacts`, which reads it off the request and its `TransferInspection`. The action kind comes from the structured request; the asset, amount, recipient and programs come out of the transaction's own bytes; the chain comes from the wallet the owner connected, not from the request the inspection already checked it against. A fact that couldn't be established is null, and null never passes.
- **`PolicyEvaluation.kt`** — `evaluate` for a `ConnectionPolicy` or a `StoredPolicy`, the six checks, and `PolicyEvaluator`, which caches nothing.
- **`DailySpending.kt`** — `SpendScope` (connection, wallet, asset, chain), `SpendStatus`, `Spend`, `DailyTotal`, `spendsOf` over the Activity records, and `dailyTotal` over a local day.

`TransferFacts` gained `programs`, the programs a transaction calls, and `PolicyReason` gained `RequestUnverified`.

### Judgement calls

- **The withheld verdict applies only to a match.** A request that is under restrictions already keeps the reasons it has; `request_unverified` exists to stop an `ALLOWED`, not to pile a second reason onto an existing refusal.
- **A threshold is compared by the room left, not by the sum.** `projected` saturates for display, and a saturated sum reads as under a threshold set to the maximum. The comparison is `today.projected <= limit && amount <= limit - today.projected`, which is exact. A deliberate break proved the difference.
- **Compute budget is a program like any other.** A policy that lists the programs a transaction may call lists that one too. A carve-out would be a hole the owner can't see.
- **`Waiting` counts as exposure.** A transfer record only exists once the sidecar accepted the approval, so the wallet has the transaction; telling the owner they have room they may not have is the one wrong answer.
- **`ChainFailed` counts nothing.** The chain ran it and it failed; the fee moved and the transfer didn't.
- **A movement is dated by `answeredAt`.** `recordedAt` changes on every status check, which would walk yesterday's payment into today. A deliberate break proved it.
- **Nothing was wired into the app.** `PolicyEvaluator` has no caller: SAW-028 puts the assessment on the review screen, and an evaluator constructed in `SeekerVaultApplication` with nothing reading it would be dead code. This matches how SAW-025 left `PolicyStore`.

### What was run

- `pnpm check` (0), `pnpm check:android` (0), `pnpm check:generated` (0), `pnpm test:hello` (0), `pnpm test:queue` (0), `pnpm test:transfer` (0).
- 66 new tests; the whole Android suite passes.
- Five deliberate breaks, each failing exactly the intended test and restored: the withheld verdict removed, a rejection counted as spending, a movement dated by when it was last written, a threshold compared with a saturated sum, and a wallet import added to the policy package.
- Device checks: none. Nothing here is reachable from a screen.

### What was deliberately left out

No editor (SAW-027), no review-screen integration (SAW-028), nothing wired into the application graph, and no swap evaluation — a swap comes back as unread, so it can never be `ALLOWED`.
