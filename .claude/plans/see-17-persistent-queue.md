# SEE-17 / SAW-010 — Implement the persistent sidecar queue and async MCP lifecycle

Linear: https://linear.app/seekeragentwallet/issue/SEE-17 · Branch: `develop` · One PR `develop` → `master` after SEE-21

## Checklist

- [x] Storage, `sidecar/src/storage/`:
  - [x] `database.ts`: opens `node:sqlite` with WAL and `synchronous = FULL`, runs migrations (`PRAGMA user_version`, each in a transaction, and a newer database is refused), and provides IMMEDIATE transactions
  - [x] `migrations.ts`: v1 with connections, requests, results, prepared transactions, and idempotency keys (STRICT tables)
  - [x] a frozen v1 fixture that the migration test opens
- [x] `requests/store.ts`, the `RequestStore`, on top of the SAW-009 rules:
  - [x] idempotent ack creation, with the pending limit and `NOT_PAIRED`
  - [x] get, and cancellation before approval
  - [x] keyset-paged `ListPending`
  - [x] idempotent `SubmitResult`, where a conflicting result gets `INVALID_STATE`
  - [x] expiry applied first, in every operation
- [x] `requests/mcp-tools.ts`: `vault_request_ack`, `vault_get_request`, `vault_cancel_request`, and the request view; the descriptions say that storing isn't approval
- [x] `requests/phone-service.ts`: `RequestService` with a `RequestErrorDetail` on every error
- [x] Wiring:
  - [x] config: `DATABASE_PATH`, `REQUEST_TTL_SECONDS`, `REQUEST_PENDING_LIMIT`
  - [x] server: open the database, the development connection, close the database
  - [x] a 64 KiB limit on `/mcp` bodies
  - [x] the stage guard changes on purpose: storage is allowed only in `src/storage/`
- [x] Test harnesses: a throwaway database per spawned sidecar (`testing/process.ts`, `restart.test.ts`, the Android transport test), and `:memory:` for in-process servers
- [x] Tests:
  - [x] restart persistence (SIGKILL right after a response)
  - [x] duplicate creation and conflicting keys
  - [x] repeated results and conflicting terminal results
  - [x] expiry and cancellation races
  - [x] pagination while states change
  - [x] the migration fixture and the migration runner
  - [x] the pending limit and the body limit
- [x] Docs:
  - [x] `docs/protocol.md`
  - [x] `docs/development/sidecar.md`: storage and lifecycle
  - [x] `.env.example`, `README.md`, `AGENTS.md`, `CODEBASE.md`, the changelog, and the decisions
  - [x] also `docs/testing/hello-world.md` and `docs/integrations/hermes.md`, which mention the tool list
- [x] Verify: `pnpm check`, `pnpm test:hello`, `pnpm check:generated`, `pnpm check:android`, `pnpm build`, and deliberate breaks
- [ ] Commit and push on `develop`
- [ ] Linear: tick the SEE-17 checklist, add a summary, and move the issue to In Review

## Design

- **The database is `node:sqlite`, built into Node 24.** It needs no native build and no new dependency, and Node 24.21 prints no warning for it. There's one file (`DATABASE_PATH`, default `sidecar/data/sidecar.db`, which `*.db` in `.gitignore` already covers). It runs in WAL mode with `synchronous = FULL`, so a commit is durable before the caller hears of it.
- **Every store operation is one synchronous IMMEDIATE transaction.** Node's single thread never interleaves two operations, and the write lock guards against a second process. Expiry runs first inside each transaction, so a request is PENDING strictly before `expires_at`, as the contract says.
- **The connection until pairing:** the store creates one connection when the database is new, and `PHONE_TOKEN` authenticates as it. The log prints its ID. SAW-011 replaces this with pairing.
- **Wallet kinds are refused with `WALLET_MISMATCH`,** because no wallet is connected before Stage 3. Only ack can be created.
- **`PrepareRequest`:** an ack gets `INVALID_PARAMETERS` ("nothing to prepare"), and a transaction answers `unimplemented` until Stage 4.
- **Repeated results are detected by bytes:** every accepted result is stored in `results`, and an identical one returns the request unchanged.

## Review

**What changed:**

- **Storage (`sidecar/src/storage/`):**
  - `node:sqlite` in WAL mode with `synchronous = FULL`
  - numbered migrations, tracked in `PRAGMA user_version`. v1 has connections, requests, idempotency keys, prepared transactions, and results, all STRICT tables.
  - a frozen v1 fixture
- **`RequestStore`:**
  - idempotent ack creation, with the pending limit, `NOT_PAIRED`, and `WALLET_MISMATCH` for wallet kinds
  - reads, cancellation by the agent, keyset pages, and idempotent results
  - one IMMEDIATE transaction per operation, with expiry applied first
- **Agent side:** `vault_request_ack`, `vault_get_request`, and `vault_cancel_request` answer at once with the request view. Their descriptions say that storing isn't approval.
- **Phone side:** `RequestService`, with a `RequestErrorDetail` on every error. Until pairing, `PHONE_TOKEN` authenticates as the one connection created with the database.
- **Wiring:**
  - three optional configuration variables
  - the server opens the database and closes it on shutdown
  - a 64 KiB limit on `/mcp` bodies
  - the Node stage guard allows storage only in `src/storage/`
  - test harnesses use throwaway databases
- **Docs:** the storage and lifecycle section in `sidecar.md`, and the status and limits in `protocol.md`. Also updated: README, AGENTS, CODEBASE, the changelog, the decisions, `hello-world.md`, `hermes.md`, and `.env.example`.

**How it was verified:** the verification record is in `docs/development/sidecar.md`.

- `pnpm check`: 189 sidecar tests (42 more than before) and 15 test-agent tests
- `pnpm test:hello`: 9/9
- `pnpm check:android`: 61/61
- `pnpm build`, a start of the built sidecar, and `check:generated` all pass
- Two SIGKILL restarts: one right after an answer to the agent, one right after an answer to the phone
- Eight deliberate breaks, all caught

**Caveats:**

- **The connection until pairing is a stand-in.** SAW-011 replaces it with pairing and revocation. For now, the startup log prints its ID.
- **`vault_request_ack` isn't gated yet.** SAW-014 limits it to development and demo use, and adds the test-agent commands and the Hermes example.
- **Durability through a power loss isn't tested.** The SIGKILL tests prove that the sidecar commits before it answers. `synchronous = FULL` is only asserted by the pragma test, not tested by pulling a plug.
- **Nothing enforces one sidecar per database file** beyond SQLite's write lock.
