# SEE-16 / SAW-009 — Define the durable request contract and lifecycle

Linear: https://linear.app/seekeragentwallet/issue/SEE-16 · Branch: `develop` · One PR `develop` → `master` after SEE-21

## Checklist

- [x] Proto `seekervault/request/v1`:
  - [x] `request.proto`: `RequestRef`, `ActionRequest`, `RequestState`, `Outcome`, `Approval`, `Action` (ack, sign_message, transfer, swap), `Asset`, `Network`, `PreparedTransaction`, `PolicyEvaluation`, `RequestError`, `RequestErrorDetail`
  - [x] `service.proto`: `PairingService` (`Pair`, `RevokeConnection`) and `RequestService` (`ListPending`, `GetRequest`, `PrepareRequest`, `SubmitResult`)
  - [x] `pnpm generate`, `buf format`, `buf lint`
- [x] Fixtures in `proto/fixtures/seekervault/request/v1/`: one request of each kind, u64 amounts, exact message text and bytes, a prepared transaction, submissions, a page, an error detail, the same request ID under two connections, and an empty request
- [x] Sidecar `src/requests/`, pure rules with no storage:
  - [x] `lifecycle.ts`: the transition table (from, to, actor, kinds), terminal and success states, which result moves which state, `decideResult` (approval binding), `isOverdue`
  - [x] `action.ts`: action validation, base-unit amounts, base58 addresses, exact message bytes, the agent's note
  - [x] `identity.ts`: the `RequestRef` scope check, the idempotency key format, the action fingerprint, `resolveIdempotency`
  - [x] Tests: transition tables, serialization fixtures, missing fields, large amounts, identical IDs under different connections, idempotency conflicts, and independence from Stage 1
- [x] Android: `RequestProtocolFixturesTest`, the Kotlin half of the fixture check
- [x] Stage 1 compatibility: `buf breaking` against the previous commit; the live package, its tool, and its tests stay untouched
- [x] Docs: `docs/protocol.md` (Stage 2 section), a new `docs/architecture.md`, `proto/README.md`, `README.md`, `AGENTS.md`, `RFC.md`, `CODEBASE.md`, the sidecar and Android docs, the changelog, the decisions, and the lessons
- [x] Verify: `pnpm check`, `pnpm test:hello`, `pnpm check:generated`, `pnpm check:android`, `pnpm build`, and deliberate breaks
- [ ] Commit and push on `develop`
- [ ] Linear: tick the SEE-16 checklist, add a summary, and move the issue to In Review

## Design

- **A connection is one phone paired with one sidecar.** The sidecar assigns `connection_id` at pairing, and each request is bound at creation to the active connection. `RequestRef` always carries both IDs, and a reference to another connection's request gets `NOT_FOUND`.
- **Idempotency keys belong to the agent's scope,** which is the whole sidecar, not a connection. That way a retry after re-pairing still finds the original request. The fingerprint is SHA-256 of the `Action`'s deterministic encoding. The agent's note and the requested lifetime are left out of it.
- **Approval is the commit point.** The phone sends `Approval` (PENDING → PROCESSING) before it invokes the wallet, and invokes the wallet only if the sidecar accepts it. That's how a cancellation and an approval race safely: exactly one of them wins.
- **No state moves backward.** UNKNOWN isn't terminal. A late report or a chain lookup settles it. SUBMITTED never becomes UNKNOWN, because the sidecar knows the signature.
- **Two expiries:** `ActionRequest.expires_at` bounds the user's decision and applies only to PENDING. `PreparedTransaction.last_valid_block_height` bounds whether a signed transaction can land.
- **Errors:** one `RequestError` enum. MCP uses `"<CODE>: <message>"`, like Stage 1. Connect errors carry a `RequestErrorDetail`; connect-kotlin 0.9.0 has `unpackedDetails` and connect-es has `findDetails`.
- **Scope boundary:** this task ships the contract, the pure rules, and the fixtures. SAW-010 adds storage and the MCP tools, SAW-011 pairing, Stage 3 message signing, Stage 4 transfers, and Stage 6 swaps.

## Review

**What changed:**

- **Contract:** `proto/seekervault/request/v1` has two files, with generated TypeScript and Kotlin:
  - `request.proto` holds the data model: the request and its reference, the four actions with their wallet and network binding, the ten `RequestState` values, `PreparedTransaction` (a version and content hash), `PolicyEvaluation`, and `RequestError` with a Connect error detail.
  - `service.proto` holds the phone's `PairingService` and `RequestService`, all unary.
- **Rules as pure code** in `sidecar/src/requests/`:
  - 22 transitions, each naming its actor and the action kinds it applies to
  - which result from the phone moves a request where, and the approval binding (version and hash)
  - expiry
  - every kind's required fields, u64 base-unit amounts, base58 addresses, and exact message bytes
  - connection scope, the idempotency key format, the action fingerprint, and the replay-or-conflict decision
- **Fixtures:** 15 cases, checked byte for byte by the sidecar and by `RequestProtocolFixturesTest`.
- **Docs:**
  - `docs/protocol.md` has a Stage 2 section: identity, actions, idempotency, the lifecycle, the two expiries, prepared transactions, the phone API, the MCP tools, errors, and compatibility with Stage 1.
  - `docs/architecture.md` is new.
  - README, AGENTS, RFC, CODEBASE, the sidecar and Android docs, `proto/README.md`, the changelog, the decisions, and the lessons are updated.

**How it was verified:** the verification record in `docs/protocol.md` has the details.

- `pnpm check`: 147 sidecar tests (74 new) and 15 test-agent tests
- `pnpm check:android`: 61 unit tests (16 new), no lint issues
- `pnpm check:generated`, `buf breaking` against the previous commit, `pnpm test:hello` (9/9), and `pnpm build` all pass
- Five deliberate breaks, one per rule and one fixture byte, were each caught. The fixture break was caught in both runtimes.

**Caveats:**

- **Nothing serves the workflow yet.** SAW-010 adds storage and the MCP tools, turning the documented JSON shapes into schemas. SAW-011 adds pairing and SAW-013 the inbox.
- **`decideResult` leaves three checks to its callers,** as its comment says:
  - detecting duplicate submissions (SAW-010)
  - verifying signatures (Stage 3)
  - checking blockhash expiry (Stage 4)
- **The pairing messages are minimal.** SAW-011 owns the pairing token, the QR code, and TLS, and may add fields; additions keep the package compatible.
- **`PolicyEvaluation` is defined, but nothing uses it until Stage 5.**
- **In this worktree, `pnpm check:android` needed `ANDROID_HOME`,** because there's no `local.properties`. The first background run's exit status was `tail`'s, not Gradle's. Both are recorded in `lessons.md`.
