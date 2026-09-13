# Stage 5.2 verification

Stage 5.2 replaces refresh-only discovery with a foreground bidirectional gRPC stream and a bounded unary sync path shared by foreground recovery, manual refresh, and WorkManager. It adds no push-notification transport, foreground service, automatic request decision, or automatic wallet action.

## SAW-048 — protocol and transport proof

SAW-048 defines the wire contract and proves the pinned Kotlin and Node libraries can sustain the required full-duplex call. It does not serve the production update endpoint, connect it to the durable queue, add a phone cache, or schedule background work; those belong to SAW-049 through SAW-053.

### Contract covered

- `UpdateService.Subscribe` is a true bidirectional RPC. Subscribe/ready establishes a mutation barrier; retained replay or a frozen `Sync` snapshot closes the initial-state race before buffered later events are applied.
- Opaque cursors are bound to a process instance, revisions make stale and duplicate delivery harmless, and invalid cursors, retention gaps, buffer overflow, restart, or incomplete pagination force a fresh snapshot without deleting local owner data.
- Messages are limited to 65,536 bytes. Heartbeats are negotiated from 15 through 60 seconds, default to 30, and three unanswered intervals end the call. Page sizes are capped at 100 and snapshot tokens at 256 bytes and two minutes.
- `Sync` returns every pending request plus the current server state of up to 100 named nonterminal Activity records. It may check at most four eligible transfers concurrently using the existing read-only confirmation budget; it never opens a wallet or creates, signs, sends, or retries a transaction.
- Pairing capability discovery is additive. The existing unary and MCP surfaces remain usable, while an old sidecar is reported as requiring an upgrade instead of being treated as an empty update feed.

### Full-duplex interoperability proof

`GrpcBidiInteropTest` launches a test-only Connect Node adapter on a real TLS listener. The generated Connect-Kotlin 0.9.0 client uses OkHttp 5.4.0 with gRPC framing and negotiated HTTP/2. The test sends subscribe, receives ready, then sends and receives two heartbeat pairs while the client send side is still open. The Node handler records HTTP version `2.0`, protocol `grpc`, one stream, three client messages, and two heartbeats. A second case closes the Kotlin receive side and requires the Node abort signal to fire on that one stream, with no reconnect.

The proof uses Connect Node 2.2.0, protobuf-es/protoc-gen-es 2.14.1, Buf 1.72.0, the remote Java/Kotlin generators v36.1, protobuf-kotlin-lite 4.36.1, Node 24.21.0, and pnpm 12.3.4. These were already pinned; no dependency changed.

### Verification record

Run on 2026-09-13 on macOS 26.5.2 (Apple silicon). The Android SDK came from the local `ANDROID_HOME`; no machine path was written to the repository.

| Check | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | PASS |
| `pnpm generate` | PASS: the update schema generated Java lite, Kotlin lite, Connect-Kotlin, protobuf-es JavaScript, and TypeScript declarations. |
| `pnpm check:generated` | PASS: a clean regeneration matches the committed output. |
| `pnpm check` | PASS: formatting, Buf format/lint, ESLint, TypeScript, 400/400 sidecar tests, and 29/29 test-agent tests. |
| `pnpm test:hello` | PASS: all 9 Stage 1 cases remain compatible. |
| `pnpm test:queue` | PASS: all 7 durable unary/MCP queue cases remain compatible. |
| `pnpm check:android` | PASS: Spotless, 746/746 JVM tests including the real full-duplex proof, lint, and debug and instrumentation APKs. |
| Deliberate transport break | PASS: requiring the HTTP/2 proof server to accept HTTP/1.1 made `GrpcBidiInteropTest` fail; the source was restored and the test passed. |
| Physical Seeker | **NOT RUN.** SAW-048 changes no production phone behavior. |

## SAW-049 — production sidecar stream and sync

SAW-049 attaches the contract to the same durable queue used by MCP and RequestService. It adds sidecar behavior only: the Android app does not consume the new endpoint until SAW-050 and SAW-051, and no worker is scheduled until SAW-052.

### Automated coverage

- `sidecar/src/updates/service.test.ts` connects a real Node gRPC client to the production secure listener over negotiated HTTP/2. On that same listener, HTTPS health, an authenticated MCP initialize, pairing capability discovery, and HTTP/1 RequestService remain usable. The suite also exercises the separate loopback h2c development listener.
- A subscription receives durable create, cancel, expiry, and revocation events with increasing request revisions; a client heartbeat can be sent while server events continue. Durable commits wake the stream directly: the delivery test sets the idle housekeeping interval to 60 seconds and still requires the new request within 500 milliseconds. A newer stream cancels the old generation, pairing replacement closes the revoked stream, and shutdown destroys retained HTTP/2 sessions.
- A retained cursor replays through `replay_complete`. More than the retained 512 mutations makes an old/slow cursor require a snapshot. Restart changes the process instance, invalidates its cursor, and recovers the still-pending request with Sync.
- Sync pages a disk-frozen pending set: a cancellation between pages does not rewrite the incomplete snapshot. A missing named Activity reference returns a revisioned removal; page tokens and every reference remain connection-scoped. The MCP token cannot call either update RPC, and an authenticated connection cannot name another one.
- `sidecar/src/updates/store.test.ts` checks the source transaction log directly: create, completion, agent cancellation, expiry, wallet-binding cancellation, and revocation each publish exactly once, while duplicate/idempotent operations do not. The revocation marker follows its request cancellations.
- `sidecar/src/updates/confirmation.test.ts` advances a controlled chain only after a transfer is SUBMITTED. Sync—not CheckStatus—settles it only after `ConfirmationTracker` fetches and matches the approved bytes, and the committed confirmation is then streamed. With five eligible transfers, exactly four are checked and the deferred record rotates into the next run. The fake chain exposes no send or simulation method.
- `sidecar/src/config.test.ts`, `storage/database.test.ts`, and `stage-boundary.test.ts` cover valid/invalid TLS and h2c configuration, schema migration 4, file/SQL boundaries, and confinement of HTTP/2/update-protocol code.

The TLS test identity is generated locally for the test and trusted only by that test client. Production continues to require a publicly trusted certificate. All chain advancement is deterministic on loopback; no cluster, wallet, or funds are used.

### Verification record

Run on 2026-09-13 on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, Buf 1.72.0, and the other pinned versions in [`docs/development/toolchain.md`](../development/toolchain.md). The Android SDK came from the machine's existing `ANDROID_HOME`; no machine path was written to the repository.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, Buf format/lint, ESLint, TypeScript, 410/410 sidecar tests, and 29/29 test-agent tests. |
| Production TLS/h2 and loopback h2c integration | PASS: all 5 cases, including the actual gRPC client, HTTP/1 health/MCP/Connect compatibility, replay, frozen Sync, isolation, replacement, revocation, retention gap, and restart recovery. |
| `pnpm test:hello` | PASS: all 9 Stage 1 simulated-device cases remain compatible. |
| `pnpm test:queue` | PASS: all 7 Stage 2 durable queue cases remain compatible. |
| `pnpm check:android` | PASS: Spotless, 746/746 JVM tests, lint, and the debug and instrumentation APKs. |
| `pnpm check:generated` | PASS: generated protocol code and fixtures are current. SEE-68 changes no schema. |
| `pnpm build` | PASS: the sidecar and test-agent production TypeScript builds compile. |
| Deliberate confirmation-bound break | PASS: changing the Sync confirmation limit from four to five failed `updates/confirmation.test.ts` because the required deferred record disappeared; restoring four passed both focused cases. |
| Physical Seeker | **NOT RUN.** SAW-049 changes only the sidecar; the phone transport arrives in SAW-050. |

## SAW-050 — shared Android synchronization and persistent state

SAW-050 gives every Android trigger one application-scoped convergence path without scheduling a trigger itself. Manual Refresh uses it now, and the later foreground stream owner and WorkManager caller can use the same `SynchronizationRepository` without an Activity or ViewModel. It reads and reconciles server state; it cannot prepare, approve, invoke a wallet, sign, send, simulate, or create an owner decision.

### Automated coverage

- `SynchronizationRepositoryTest` covers a worker-style process restart, complete multi-page replacement, expiry and absence, duplicate and stale events, revision gaps and rollback, revisioned removal, per-connection overlap coalescing, the stronger stream-barrier follow-up, more than 100 known Activity records across runs, isolated healthy and failing servers, buffered revocation, deletion during an in-flight call, and rejection of an invalid advertised origin.
- `SyncStoreTest` covers versioned atomic round trips, the absence of credentials from disk, an interrupted replacement retaining the preceding complete document, and corrupt or future-version state taking the empty-cursor full-sync recovery path.
- `ConnectionSynchronizationTest` proves manual Refresh uses the shared Sync path instead of `ListPending`, a stored owner result is retried through the existing idempotent result-delivery path, Activity advances from that existing record, and a process started only through `synchronizeAll()` restores connections, credentials, pending cache, and state.
- `ActivityLogTest` proves Sync can advance an existing sent transfer through the established server-confirmation interpretation, cannot regress a terminal record, preserves reviewed terms, signature, and policy assessment, and cannot fabricate Activity for an unknown request.
- `StageBoundaryTest` scans the entire sync package for wallet adapter access and preparation, approval, signing, or sending entry points. The application-scoped host surface contains only state reads, retry of an already-recorded result, monotonic reconciliation, failure recording, and revocation.
- Existing screen tests use an explicit legacy-only test transport. This both retains their previous RequestService assertions and proves capability fallback remains available to old or unconfigured sidecars.

### Verification record

Run on 2026-09-14 on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, and the pinned Buf CLI 1.72.0. The Android SDK came from the machine's existing `ANDROID_HOME`; no machine path was written to the repository.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, Buf format/lint, ESLint, TypeScript, 410/410 sidecar tests, and 29/29 test-agent tests. |
| `pnpm test:hello` | PASS: all 9 Stage 1 simulated-device cases remain compatible. |
| `pnpm test:queue` | PASS: all 7 Stage 2 durable queue cases remain compatible. |
| `pnpm check:android` | PASS: Spotless, 767/767 JVM tests, Android lint, and debug and instrumentation APKs. |
| `pnpm check:generated` | PASS: generated protocol code and fixtures are current. SAW-050 changes no schema. |
| Deliberate synchronization break | PASS: changing the contiguous event rule from `old revision + 1` to `old revision + 2` failed `SynchronizationRepositoryTest.duplicateAndStaleEventsAreIdempotentButGapConflictAndRollbackRequireSync`; restoring the rule passed the focused suite and the complete Android check. |
| Physical Seeker | **NOT RUN.** SAW-050 adds the phone's persistent Sync consumer, but no physical-device run was performed. |
