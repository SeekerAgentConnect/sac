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
