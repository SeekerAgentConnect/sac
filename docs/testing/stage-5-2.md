# Stage 5.2 verification

Stage 5.2 replaces refresh-only discovery with a foreground bidirectional gRPC stream and a bounded unary sync path shared by foreground recovery, manual refresh, and WorkManager. It adds no push-notification transport, foreground service, automatic request decision, or automatic wallet action.

## SAW-048 — protocol and transport proof

SAW-048 defines the wire contract and proves the pinned Kotlin and Node libraries can sustain the required full-duplex call. It does not serve the production update endpoint, connect it to the durable queue, add a phone cache, or schedule background work; those belong to SAW-049 through SAW-053.

### Contract covered

- `UpdateService.Subscribe` is a true bidirectional RPC. Subscribe/ready establishes a mutation barrier; retained replay or a frozen `Sync` snapshot closes the initial-state race before buffered later events are applied.
- Opaque cursors are bound to a process instance, revisions make stale and duplicate delivery harmless, and invalid cursors, retention gaps, buffer overflow, restart, or incomplete pagination force a fresh snapshot without deleting local owner data.
- Update-service messages are limited to 65,536 bytes. Heartbeats are negotiated from 15 through 60 seconds, default to 30, and three unanswered intervals end the call. Page sizes are capped at 100 and snapshot tokens at 256 bytes with a two-minute inactivity lease renewed by each valid page.
- `Sync` returns every pending request plus the current server state of up to 100 named nonterminal Activity records. It may check at most four eligible transfers concurrently using the existing read-only confirmation budget; it never opens a wallet or creates, signs, sends, or retries a transaction.
- Pairing capability discovery is additive. The existing unary and MCP surfaces remain usable, while an old sidecar is reported as requiring an upgrade instead of being treated as an empty update feed.

### Full-duplex interoperability proof

`GrpcBidiInteropTest` launches a test-only Connect Node adapter on the real loopback h2c transport. The generated Connect-Kotlin 0.9.0 client uses OkHttp 5.4.0 with gRPC framing and explicit HTTP/2 prior knowledge, so HTTP/1 is not a fallback. The test sends subscribe, receives ready, then sends and receives two heartbeat pairs while the client send side is still open. The Node handler records HTTP version `2.0`, protocol `grpc`, one stream, three client messages, and two heartbeats. A second case closes the Kotlin receive side and requires the Node abort signal to fire on that one stream, with no reconnect. The production-listener tests separately cover TLS/ALPN HTTP/2 and preserved HTTP/1 calls on the secure origin.

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
| Deliberate transport break | PASS: forcing the proof client to HTTP/1.1 made `GrpcBidiInteropTest` fail; restoring HTTP/2 prior knowledge passed. |
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

## SAW-051 — foreground streams and reactive screens

SAW-051 attaches the real Android bidirectional client to application foreground state. It adds no worker or schedule: a true background transition closes every production stream, while a rotation or screen navigation retains the same application-owned stream. Every event and recovery snapshot enters the SAW-050 repository, so the existing observable Inbox and Activity state remains the only screen source.

### Automated coverage

- `ForegroundUpdateManagerTest` covers idempotent foreground/rotation/navigation signals, rapid background and foreground with a fresh reconciliation, pairing and removal while open, two independent servers with one initially unreachable, explicit authentication and version failures, a request event buffered behind a required barrier snapshot, reactive pending publication, client heartbeats, the three-interval liveness deadline, bounded retry, and recovery.
- `GrpcBidiInteropTest.productionTransportKeepsItsSendSideOpenForHeartbeats` uses `ConnectUpdateTransport`, the generated client, and explicit HTTP/2 prior knowledge against the loopback Node proof server. It proves the production wrapper sends Subscribe, leaves its send side open for a later cursor-bearing heartbeat, receives the acknowledgement, and cancels the same RPC on close. The production-listener suite separately verifies TLS/ALPN.
- `MainActivityTest` now holds the application foreground owner across recreation and marks a real non-configuration stop as background. Existing wallet lifecycle tests continue to prove a wallet hand-off has one result and foreground return reconciles it; `StageBoundaryTest` proves the sync package has no wallet, preparation, approval, signing, or sending entry point.
- `ConnectionsViewModelTest`, `ConnectionsScreenTest`, and `ConnectionDetailsScreenTest` prove liveness changes publish without refresh and render independently from the retained last-sync time.

The negotiated heartbeat interval is 15–60 seconds. Any response resets the quiet timer; after three unanswered intervals the phone closes the stream, so the detection bound is 45–180 seconds. A normal event is not batched behind that timer: expected delivery is one network transit, validation, and one atomic cache write before the existing flows recompose. Android scheduling and network conditions mean this is not a wall-clock guarantee.

### Verification record

Run on 2026-09-14 on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, the pinned Buf CLI 1.72.0, and the existing Android SDK selected through `ANDROID_HOME`. No machine path was written to the repository.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, Buf format/lint, ESLint, TypeScript, 410/410 sidecar tests, and 29/29 test-agent tests. |
| `pnpm test:hello` | PASS: all 9 Stage 1 simulated-device cases remain compatible. |
| `pnpm test:queue` | PASS: all 7 Stage 2 durable queue cases remain compatible. |
| `pnpm check:android` | PASS: Spotless, 777/777 JVM tests, Android lint, and debug and instrumentation APKs. |
| `pnpm check:generated` | PASS: generated protocol code and fixtures are current. SAW-051 changes no schema. |
| Deliberate liveness break | PASS: changing the missed-heartbeat limit from three to four failed `ForegroundUpdateManagerTest.unansweredHeartbeatDeadlineClosesThenReconnectsWithBoundedBackoff`; restoring three passed the focused suites and the complete Android check. |
| Physical Seeker with Hermes | **NOT RUN.** Automatic appearance while Home/Inbox/Activity remain open, visible outage/recovery status, wallet return, and observed on-device delivery latency still require the Stage 5.2 device run. |

## SAW-052 — periodic WorkManager background synchronization

SAW-052 attaches WorkManager to the same headless unary repository used by manual and foreground recovery. It schedules no stream in the background and grants no new wallet authority.

### Automated coverage

- `BackgroundSynchronizationTest` uses WorkManager 2.11.2 test support and virtual time. It proves scheduling waits until the connection store is known, creates one unique periodic request, requires a connected network, uses the 15-minute minimum repeat and initial delay, configures exponential 30-second retry backoff, carries empty worker input, and is not expedited. A second scheduler instance adopts the same request ID, while revoking the last usable connection cancels it.
- The real `BackgroundSyncWorker` is constructed with `TestListenableWorkerBuilder` without an Activity or ViewModel. It reloads a manually seeded connection and encrypted credential, fetches a unary snapshot, and leaves the pending request, cursor, and last-success time in `SyncStore`. A transient unreachable result returns WorkManager retry; authentication failure revokes the connection, removes its credential, cancels periodic work, and returns without retry.
- `BackgroundSyncRunner` skips unary work when every usable foreground stream is live. If even one is unhealthy, it enters the shared path. Certificate/configuration failures and revocation do not cause a tight backoff loop; only transient unreachability does.
- `SynchronizationRepositoryTest` starts five blocked sidecars and proves only four enter concurrently, then releases them without serializing the set. A separate virtual-time case holds one server to the ten-minute overall bound while another completes; the held connection becomes retryable without a wall-clock sleep.
- `StageBoundaryTest` requires WorkManager on the classpath while continuing to confine its imports to `sync/`. Source still contains no foreground/direct service, `JobScheduler`, alarm, receiver, Firebase/push class, worker input secret, or worker access to preparation, approval, policy decisions, Mobile Wallet Adapter, signing, or sending.

WorkManager's own database preserves the unique request across ordinary process death and reboot. Fifteen minutes is only the configured minimum: network constraints, Doze, app standby, battery restrictions, and vendor policy can defer a run. Android Settings **Force stop** suppresses scheduled work until the owner reopens the app. No automated test claims an exact delivery time.

### Verification record

Run on 2026-09-14 on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, AndroidX WorkManager 2.11.2, the pinned Buf CLI 1.72.0, and the existing Android SDK selected through `ANDROID_HOME`. No machine path was written to the repository.

| Check | Result |
| --- | --- |
| Focused WorkManager and headless-bound tests | PASS: unique scheduling, constraints, retry, cancellation, worker-only initialization, foreground overlap, four-server concurrency, and virtual ten-minute timeout all ran without a wall-clock wait. |
| Deliberate schedule-replacement break | PASS: replacing `ExistingPeriodicWorkPolicy.KEEP` with `CANCEL_AND_REENQUEUE` failed `BackgroundSynchronizationTest.uniqueScheduleWaitsForLoadedStateKeepsItsClockAndCancelsWithoutAConnection`; restoring `KEEP` passed the focused suite. |
| `pnpm check` | PASS: Prettier, Buf format/lint, ESLint, TypeScript, 410/410 sidecar tests, and 29/29 test-agent tests. |
| `pnpm test:hello` | PASS: all 9 Stage 1 simulated-device cases remain compatible. |
| `pnpm test:queue` | PASS: all 7 Stage 2 durable unary/MCP queue cases remain compatible. |
| `pnpm check:android` | PASS: Spotless, 787/787 JVM tests, Android lint, and debug and instrumentation APKs. |
| `pnpm check:generated` | PASS: generated protocol code and fixtures are current. SAW-052 changes no schema. |
| Physical Seeker background, screen-off, process-death, and reboot checks | **NOT RUN.** No physical Seeker is attached; actual scheduling delays and the persisted result on reopen remain for the SAW-053 owner run. |

## SAW-053 — cross-component acceptance and Seeker runbook

SAW-053 validates Stage 5.2 as one system without folding the Stage 1 Live diagnostic into its evidence. `pnpm test:updates` runs the sidecar's production update suites and the Android update/gRPC suites. `Stage52AcceptanceTest` starts real sidecar processes with their SQLite stores, pairs through the production API, creates and cancels requests through the MCP SDK client, and consumes those mutations through Android's production gRPC transport, synchronization repository, persistent cache, and foreground lifecycle owner over loopback HTTP/2.

### Repeatable automated acceptance

| Scenario | Automated evidence |
| --- | --- |
| Foreground updates | A real MCP request appears in the Android cache and pending count while the foreground stream is live, without Refresh; agent cancellation removes it. The sidecar records one stream across repeated foreground/navigation-style ownership calls. |
| Two-sidecar isolation | Two independently paired real processes stay live together. Stopping A leaves B live, and a request from B appears only under B. Restarting A catches up its own state without affecting B. |
| Lifecycle and persistent recovery | A sidecar restart and clock advance expire a request, a true background signal closes the stream, and a worker-only repository process catches up through unary HTTP/2 and commits a cache that another offline process can load. |
| Faults and backoff | A stopped sidecar leaves the owner's stored answer waiting while another connection proceeds. Foreground retry begins at one second, stays within the 30-second cap, and after restart delivers only that already-recorded answer. Separate protocol suites cover authentication, invalid cursors/pages/revisions, retained-history gaps, revocation, TLS, and h2c failures. |
| Confirmation without **Check status** | `sidecar/src/updates/confirmation.test.ts` uses the production secure HTTP/2 listener and a controlled read-only chain. Unary Sync settles a submitted transfer only after matching the approved bytes, publishes the result, checks at most four concurrently, and rotates the deferred record. It exposes no send or simulation operation and invokes no wallet. |
| Cleanup | Background closes each real stream; removal, revocation, replacement, shutdown, and bounded replay/snapshot cleanup are covered by the production sidecar suites. The worker schedule/constraint/Force-stop semantics remain covered by `BackgroundSynchronizationTest` and the physical checklist below. |

The automated agent is the repository's real MCP SDK client, not Hermes running on a physical workflow. Its evidence proves the same MCP boundary and real sidecar process but does not turn the Hermes-on-Seeker rows below into PASS. A deliberate change from h2c HTTP/2 prior knowledge to HTTP/1.1 made the joined foreground case fail before reaching `ready`; restoring HTTP/2 made it pass.

### Physical Seeker checklist (SAW-053)

Use the [MacBook → Seeker live/background runbook](../guides/live-background-updates.md). Record the physical device identity, revision, observed delivery times, and one of PASS, FAIL, or NOT RUN for every row. Emulator, Robolectric, a mock, a manual worker start, and successful APK installation are not physical-device passes.

No physical Seeker was attached on 2026-09-14, so checks 101–120 are honestly NOT RUN. They remain the owner's device acceptance record rather than being inferred from automated coverage.

| # | Physical Seeker check | Status and evidence |
| --- | --- | --- |
| 101 | Record `git rev-parse HEAD`, Seeker brand/model, Android release, app build, and Mac tool versions. | **NOT RUN:** no physical Seeker attached. |
| 102 | Install the debug APK, reverse both the main and update ports, and verify the sidecar advertises the h2c HTTP/2 update origin. | **NOT RUN:** no physical Seeker attached. |
| 103 | Pair the Seeker and observe one foreground update stream show **Live updates connected.** with **Last synced** separate. | **NOT RUN:** no physical Seeker attached. |
| 104 | From real Hermes, create a request while Home/Requests is open; verify it and the pending count appear without Refresh and record latency. | **NOT RUN:** no physical Seeker attached or Hermes-on-device run. |
| 105 | Cancel and expire real requests; verify the row/count changes without Refresh and no duplicate remains. | **NOT RUN:** no physical Seeker attached. |
| 106 | Keep Home, Requests, Request details, and Activity open in turn; verify request and terminal outcome changes recompose in place. | **NOT RUN:** no physical Seeker attached. |
| 107 | Pair two real sidecars, stop A, and prove B remains live and isolated; restart A and prove catch-up without duplicate/rollback. | **NOT RUN:** no physical Seeker attached. |
| 108 | Rotate and navigate repeatedly; prove one logical stream stays open and no competing stream is created. | **NOT RUN:** no physical Seeker attached. |
| 109 | Press Home and return repeatedly; prove each real background closes the stream and each foreground reconciles before one replacement opens. | **NOT RUN:** no physical Seeker attached. |
| 110 | Remove and restore the network/adb mappings; verify actionable status and bounded recovery while another sidecar remains live. | **NOT RUN:** no physical Seeker attached. |
| 111 | Hand an approved request to the real wallet and return; prove one wallet interaction/result while stream lifecycle recovers independently. | **NOT RUN:** no physical Seeker attached and no wallet action was attempted. |
| 112 | With the screen off and process alive, wait for the OS-scheduled unary worker, record actual delay, and verify persisted catch-up on reopen. | **NOT RUN:** no physical Seeker attached. |
| 113 | After ordinary process death (not Force stop), wait for persisted scheduled work and verify its result survives the next process start. | **NOT RUN:** no physical Seeker attached. |
| 114 | Reboot, unlock, restore USB forwarding, and verify the persisted unique job eventually runs; record actual delay. | **NOT RUN:** no physical Seeker attached. |
| 115 | Force stop the app and verify no worker runs; reopen it and verify foreground catch-up and the unique schedule return. | **NOT RUN:** no physical Seeker attached. |
| 116 | Answer while its sidecar is offline, then restore it; verify only the saved result is retried and the answer is neither lost nor duplicated. | **NOT RUN:** no physical Seeker attached. |
| 117 | On a controlled non-mainnet chain, advance an already-submitted transfer; verify Activity confirms after Sync without **Check status**, another wallet call, or another send. | **NOT RUN:** no physical Seeker attached and no controlled device chain run. |
| 118 | Exercise expiry, revocation, sidecar restart, and an unsupported update version; verify each remains distinct and manual Refresh stays available where specified. | **NOT RUN:** no physical Seeker attached. |
| 119 | Remove a connection and verify its stream/cache/schedule ownership are cleaned up while other connections and Activity remain. | **NOT RUN:** no physical Seeker attached. |
| 120 | Capture `dumpsys jobscheduler`, device-idle/package stopped state, safe stream logs, and actual timings sufficient to diagnose any scheduling or connection failure. | **NOT RUN:** no physical Seeker attached. |

### SAW-053 verification record

Run on 2026-09-14 on macOS 26.5.2 (Apple silicon), from branch `superset/feat/see-66` based on revision `469af03`. The final immutable revision is recorded in the SEE-72 Linear completion comment. The commands used Node 24.21.0, pnpm 12.3.4, Buf 1.72.0, Gradle 9.7.1/Kotlin 2.4.0, a Java 19 launcher with the pinned Java 21 Gradle daemon, compile/target SDK 37, and the dependency versions in the pinned [toolchain](../development/toolchain.md). The Android SDK was selected through `ANDROID_HOME`; no machine path is written to the repository.

| Check | Result |
| --- | --- |
| `pnpm test:updates` | PASS: 8/8 production sidecar update cases and 37/37 focused Android sync/gRPC tests, including both `Stage52AcceptanceTest` joined cases. |
| Deliberate HTTP/2 break | PASS: forcing the joined h2c Android client to HTTP/1.1 failed `Stage52AcceptanceTest`; restoring HTTP/2 prior knowledge passed the focused test and `pnpm test:updates`. |
| `pnpm check` | PASS: Prettier, Buf format/lint, ESLint, TypeScript, 410/410 sidecar tests, and 29/29 test-agent tests. |
| `pnpm test:hello` | PASS: all 9 Stage 1 simulated-device cases; it remains a separate diagnostic and is not a Seeker pass. |
| `pnpm test:queue` | PASS: all 7 Stage 2 durable unary/MCP cases. |
| `pnpm check:android` | PASS: Spotless, 789/789 JVM tests, Android lint, and the debug and instrumentation APKs. |
| `pnpm check:generated` | PASS: generated protocol code and fixtures are current. SAW-053 changes no protocol schema. |
| Physical Seeker background/screen-off/process-death/reboot/force-stop and Hermes checks | **NOT RUN:** `adb devices -l` reported no attached device on 2026-09-14. |
