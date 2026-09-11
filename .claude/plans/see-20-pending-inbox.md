# SEE-20 / SAW-013 — Implement the pending inbox and queued acknowledgement flow

Linear: https://linear.app/seekeragentwallet/issue/SEE-20 · Branch: `develop` · One PR `develop` → `master` after SEE-21

## Checklist

- [x] Gateway: `ListPending` with page tokens, `SubmitResult`, and `INVALID_STATE` read with the request from `RequestErrorDetail`
- [x] Storage (`connections/storage/ResultStore.kt`): one JSON file per answer, `filesDir/results/<connection ID>/<request ID>.json`, with the request as the owner saw it; identical request IDs on two servers stay apart
- [x] Repository:
  - [x] `refresh` sends waiting answers first, then fetches every page of PENDING requests into an in-memory inbox (only the connection's own)
  - [x] `answer` stores the answer before sending it; one answer per request; a lost or failed response keeps it waiting, and the next refresh sends it again (the sidecar recognizes a repeat)
  - [x] outcomes: accepted, superseded (cancelled or expired first, from `INVALID_STATE`), undeliverable (connection revoked)
  - [x] no concurrent sends of one answer; removing a connection deletes its answers; old settled answers are pruned
- [x] UI (stock Material 3): Pending requests (all, or one connection's) with source, action, age, expiry, and empty, offline, and error states; Request details with Acknowledge and Reject, progress while sending, and the stored outcome instead of buttons once answered; entries from Connections and Connection details
- [x] Fetch only when the app opens, a connection is selected, or the owner refreshes; nothing in the background, no push, nothing answered automatically
- [x] Test agent: `pnpm agent ack`, `get`, and `cancel` for the durable tools, for the owner-run Stage 2 check
- [x] Tests:
  - [x] a request created while the app is closed, fetched later, answered, and read back by the agent (real sidecar)
  - [x] lost responses, retries, restarts with a waiting answer, duplicate taps, concurrent sends, refresh, identical request IDs on two servers, cancelled-first and revoked connections
  - [x] no submissions when the inbox loads; no background, push, or wallet libraries (`StageBoundaryTest`)
  - [x] Compose: sections, empty/offline states, buttons disabled while sending, stored outcome on reopening
- [x] Docs: `docs/guides/pending-requests.md`, `docs/testing/stage-2.md`, `docs/development/android.md`, `docs/security.md` (stored answers), `test-agent/README.md`, README, AGENTS, CODEBASE, changelog, decisions
- [x] Verify: `pnpm check`, `pnpm test:hello`, `pnpm check:android`, `pnpm check:generated` (the deliberate breaks timed out: NOT RUN)
- [ ] Commit on `develop` (done); push and CI green after the push (not yet)
- [ ] Linear: tick the SEE-20 checklist, add a summary, and move the issue to In Review

## Design

- **The answer is written before it's sent.** `SubmitResult` is idempotent for an identical result, so resending after a lost response is safe; the sidecar's answer (the request as it is now) settles it.
- **The inbox is fetched, never pushed.** Pending lists live in memory; only the owner's answers are stored, because they must survive a crash or a dead network.
- **Answers stay in `connections/storage/`**, keyed by connection ID and request ID, and go with their connection when it's removed.

## Review

Everything above is done except the commit, push, and CI, and the Linear update, which follow this review.

- **What changed:**
  - The app shows **Pending requests** and **Request details**, reached from Connections and Connection details.
  - `ConnectionRepository` fetches every page of pending requests into an in-memory `Inbox`. It answers through a stored-first outbox (`ResultStore`, `LocalResult`), and settles each answer from the sidecar's reply.
  - The gateway gained `submitResult`, page tokens, and `INVALID_STATE` details.
  - The test agent gained `ack`, `get`, and `cancel`, with exit code 9.
- **Verified (PASS):**
  - `pnpm check`: 235 sidecar tests and 19 test-agent tests
  - `pnpm check:android`: 181/181 unit tests, lint clean, both APKs
  - `pnpm test:hello` 9/9, and `pnpm check:generated`
- **NOT RUN:**
  - the deliberate breaks, which timed out: the run hung during its third break, in `InboxTest`, and was stopped before it reported, and the file that break had changed was restored
  - the owner-run check on the physical Seeker (`docs/testing/stage-2.md`): no device was attached
  - CI: the commit isn't pushed yet
- **Caveats:**
  - A pending list isn't cached across restarts. After a restart, the inbox is empty until the app-open fetch finishes; a waiting answer shows at once, because it's stored.
  - Refresh reads at most 10 pages (1,000 requests) per connection. The sidecar's default pending limit is 100.
  - Retries happen only when the owner opens the app, opens a connection, refreshes, or taps **Send again**, never in the background.
