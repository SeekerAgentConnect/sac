# SEE-91 — Centrifugo and Redis: streaming delivery and reconnection recovery

Stage 7.1, the sixth fed child of SEE-85. SEE-90 built the gateway that holds the shared documents
and left two seams open: `dispatch.Dispatcher` on the Go side ("nothing is fanned out to yet") and
`FeedGateway`/`ProposalFeed` on the phone ("the Android client is SEE-91's"). This task closes both,
and the thing that closes them is a transport neither side has spoken before.

## What the ticket asks for

- Pin Centrifugo and Redis, and **prove unidirectional gRPC interoperability with the real Android
  client and generated schema before integrating any UI**.
- Publish committed proposal/settings events into server-scoped channels. A developer's server calls
  the gateway once and never holds a phone connection.
- **Hide Centrifugo's protocol types behind our transport adapter.** Feed content, settings and
  detail reads go only to the gateway's unary API.
- Bounded connection/subscription behaviour when the owner adds or removes a feed, reusing the
  existing foreground lifecycle machinery. **No permanent Android background stream.**
- Revision-aware reconciliation and snapshot/stream ordering, so a concurrent publication cannot
  fall into a gap during initial load or reconnect.
- **Verify what replay/recovery the pinned unidirectional transport actually supports.** Do not
  assume a bidirectional SDK's auto-recovery applies. Use explicit cursors where supported and an
  authoritative unary snapshot whenever continuity cannot be proven.
- Redis-backed cross-node fan-out, bounded buffers, slow-client disconnect, backoff with jitter,
  graceful drain. Each client still costs a connection, and we do not pretend otherwise.
- Reuse SEE-45's deployment/TLS tooling; add only the new services and configuration.

## What the transport actually is (probed, not assumed)

Pinned: **Centrifugo v6.9.6** (darwin/arm64 tarball, SHA-256 verified against the release checksums)
and **Redis 8.10.1** (built from the release tarball). Every line below was established by running
them, with a scratch gRPC client generated from the vendored schema; nothing here is recalled from
documentation alone.

1. **A unidirectional client cannot subscribe itself.** `ConnectRequest.subs` is *not* a subscribe
   request — it is a map of recovery positions, and a connect request naming channels there is
   answered with a connect reply carrying **no subscriptions at all**. Channels must arrive as
   **server-side subscriptions**, which means a connection JWT with a `channels` claim (or a connect
   proxy). This is the single fact that shapes the whole design: the phone cannot ask for a channel,
   so **the gateway must grant it one**.
2. **A token-granted channel bypasses the anonymous-subscribe permission.** With
   `allow_anonymous_connect_without_token: false` and `allow_subscribe_for_anonymous: false`, a
   token naming a channel still subscribes and receives. So both permissions stay off, no
   bidirectional transport is enabled, and **the gateway's ticket is the only way in**.
3. **`channel_regex` does not constrain a token-granted channel.** It guards client-initiated
   subscribes, which this transport does not have. So it buys nothing here and is not configured;
   the ticket's own validation is the real bound.
4. **A channel outside every configured namespace kills the connection** (`3004`), while an unknown
   channel *inside* the namespace simply yields nothing. The phone always prefixes `feed:`, so the
   fatal case is structurally unreachable.
5. **Recovery works, with `force_recovery` on the namespace**, and only at connect time:
   `recover + offset + epoch` in the request, `recovered`/`was_recovering`/`epoch`/`offset` plus the
   missed publications in the reply. `recovered=true` proves continuity. A wrong epoch, or an offset
   older than history, answers `recovered=false` **and the channel's current position** — which is
   exactly the "continuity cannot be proven" branch, and the position to stream from after a unary
   snapshot. `SubscribeResult.offset` echoes the *requested* offset on a successful recovery, so the
   new cursor is the last recovered publication's offset, not that field.
6. **Recovery is capped**: `client.recovery_max_publication_limit` (300 by default) and the
   namespace's `history_size`/`history_ttl`. Beyond either, recovery fails rather than truncating.
7. **There are no application-level pings on this transport.** `Connect.ping` reports 25 regardless
   of `client.ping_interval`, and an idle connection received nothing in 11 seconds with a 3-second
   interval configured. `DisconnectNoPong` is documented as bidirectional-only. Liveness is
   therefore HTTP/2's job: OkHttp's `pingInterval`, not a heartbeat we invent.
8. **Disconnect codes carry the retry policy, and the `reconnect` field does not.** A graceful
   shutdown answers `3001 shutdown` with `reconnect=false`, which plainly must be retried. The
   documented rule is the code range: `3500–3505` are terminal (invalid token, bad request, stale,
   force, connection limit, channel limit), everything below reconnects — `3005 connection expired`
   with a fresh token, `3014 state invalidated` with fresh state.
9. **Binary payloads are the protobuf transport's business only.** A publication sent as `b64data`
   arrives intact over gRPC, and the JSON server API cannot render it back (`/api/history` answers
   500: `json: error calling MarshalJSON for type apiproto.Raw`). Since protobuf-lite on Android
   cannot parse protojson at all, binary is the only sound choice — and the JSON history API is
   documented as unusable for these channels rather than quietly broken.
10. **Idempotent publication works, and across nodes.** The same `idempotency_key` published to node
    A and then to node B returned the same offset, with one entry in history, under the Redis engine.
    That turns the outbox's at-least-once retry into at-most-one publication per document revision.
11. **Cross-node fan-out works**: publish to node A, and clients attached to A and to B receive the
    same publication at the same offset in the same millisecond.
12. **A slow client is closed, and the bound is bigger than the number.** A client that stops
    reading is disconnected with `reason: "slow"` once its queue exceeds `client.queue_max_size` —
    observed at 16 KiB — while a healthy client on the same node keeps receiving every publication.
    At 256 KiB the same client survived more than 8 MB, because the transport's own flow-control
    window buffers it long before the queue grows. So the effective per-client bound is the window
    plus the queue, the shipped value is 64 KiB, and our own client keeps reading regardless of how
    slowly the app consumes — which makes the gateway's publish rate limit, not this queue, what
    bounds a phone that falls behind.
13. **`checkconfig` does not catch everything.** It accepted `ping_interval: 3s` with
    `pong_timeout: 8s`; the server then refused to start. The compose stack is validated by starting
    it, not only by checking it.

## Decisions to record

1. **The gateway mints a stream ticket.** Because the phone cannot subscribe itself, a new read-side
   RPC (`FeedService.GetStreamTicket`) takes the channels it wants and answers with a short-lived
   token granting exactly those it hosts. The ticket is the whole subscription: bounded in count,
   checked against registered publishers, rate limited like any read, and carrying **no identity** —
   `sub` is empty, there is no device id, no address, no `info` claim. A Go test pins the claim set.
2. **The stream lives on the gateway's own origin.** The ticket answers no URL. Caddy routes the one
   fixed gRPC path (`/centrifugal.centrifugo.unistream.CentrifugoUniStream/Consume`) to Centrifugo
   and everything else to the gateway, so the only address the phone ever learns is the one its feed
   reference already names. SEE-90 refused to relay a redirection; a ticket that named a host would
   reintroduce exactly that.
3. **`feed:` is a transport namespace, not the protocol's channel.** The document's channel stays
   `server/<id>` (SEE-88's rule, which the phone validates); Centrifugo sees `feed:server/<id>`.
   That is what scopes history, recovery and permissions to these channels alone and leaves every
   other channel name default-denied. The mapping is one function per runtime, pinned by a test on
   each side.
4. **The envelope is ours.** A publication carries a `seekervault.gateway.v1.FeedEvent`: the channel
   sequence and a `oneof` of the manifest or the proposal, rebuilt by the gateway. The phone parses
   one type, runs SEE-88/89's validators on the document inside, and ignores an event kind it does
   not know (then refreshes by snapshot, because an event it cannot read may still mean something
   changed). Centrifugo's `tags` are not used: metadata that matters belongs in the contract.
5. **Two counters, two jobs, never compared.** Centrifugo's `offset`/`epoch` is a transport cursor
   for recovery. The gateway's `sequence` is the document join. The revision decides what wins. The
   phone stores all three and mixes none of them.
6. **Order: stream first, snapshot second, buffer between.** The session opens the stream (with any
   cursor it holds), then walks the unary snapshot for every channel whose continuity was not
   proven, buffering live events meanwhile and applying them after. Applying a buffered event twice
   is free — `ProposalRepository.apply` is revision-ordered and idempotent (SEE-89) — so the join
   needs no transaction, only a bound: 512 events per channel, and past that the buffer is dropped
   and the channel converges by snapshot instead.
7. **The adapter is one file.** Only `feeds/CentrifugoFeedStream.kt` imports the vendored schema;
   the rest of the app sees `FeedStream`/`FeedStreamSession` and our own event types. An Android
   boundary test pins that, alongside the existing `StageBoundaryTest` rule that forbids the vendor's
   name in `connections/`, `sync/`, `live/`, `push/`, `policy/`, `transactions/` and `activity/`.
8. **A third buf template.** The vendored `unistream.proto` generates Kotlin only
   (`buf.gen.centrifugo.yaml`, its own output directory), so the sidecar and the gateway are never
   compiled against a client protocol neither of them speaks. Same argument as SEE-90's exclusion of
   `publish.proto`: a boundary that needs no test to hold.
9. **Foreground only, and no new WorkManager.** The feed session runs between `onStart` and
   `onStop`, like `ForegroundUpdateManager`. The direct path's background workers stay exactly as
   they are; a shared feed has nothing to deliver to a phone that is not being looked at, and the
   existing boundary test keeps `androidx.work` inside `sync/`.
10. **No streaming configured is a first-class answer.** A deployment without Centrifugo answers the
    ticket with one refusal code and the phone reads unary only. The gateway keeps the log
    dispatcher SEE-90 shipped; wiring Centrifugo is configuration, not a rebuild.

## Plan

### Transport and contract

- [x] Vendor `unistream.proto` from Centrifugo v6.9.6 with provenance and licence in a header.
- [x] `buf.gen.centrifugo.yaml`: Kotlin (javalite + Kotlin DSL + connect-kotlin) into its own
      generated directory; register it in `scripts/generate.mjs` and the Gradle source sets.
- [x] `proto/seekervault/gateway/v1/event.proto`: `FeedEvent { sequence, oneof { manifest, proposal } }`.
- [x] `feed.proto`: `GetStreamTicket`, with the channel list, the granted channels and an expiry.
- [x] `problem.proto`: `NO_STREAM`, `TOO_MANY_CHANNELS`, `NO_SUCH_CHANNEL` (appended, never renumbered).
- [x] `pnpm generate`, and check the generated Go/Kotlin/TS in.

### Go: fan-out and tickets

- [x] `internal/stream`: the Centrifugo publish client over the HTTP server API (`b64data`, an
      idempotency key per document revision, `X-API-Key`), implementing `dispatch.Dispatcher`.
- [x] `internal/stream`: HS256 ticket minting on the standard library alone, with the empty subject.
- [x] `internal/dispatch`: build the `FeedEvent` envelope; keep at-least-once, coalescing and the
      revision-conditional clear exactly as they are.
- [x] `internal/gateway`: the `GetStreamTicket` handler — bounded channels, known publishers only,
      the read limiter, one refusal when streaming is not configured.
- [x] `internal/config`: stream URL, API key, token key, lifetime, channel bound; unset ⇒ log
      dispatcher, as now.
- [x] Go tests: the publish contract against a fake Centrifugo, error and retry mapping, the ticket's
      claims and refusals, the envelope, and a boundary test that the gateway never learns what a JWT is.

### Phone: the feed session

- [x] `feeds/FeedStream.kt` — the seam: session, events, cursor, disconnect classification.
- [x] `feeds/CentrifugoFeedStream.kt` — the adapter, and the only file that imports the vendor.
- [x] `feeds/ConnectFeedGateway.kt` — `FeedGateway` + `ProposalFeed` over the unary API, version
      aware (`known_settings_revision`, `known_snapshot_sequence`), paged, plus the ticket call.
- [x] `feeds/FeedRecovery.kt` — pure decisions: recover or snapshot, retry or stop, backoff.
- [x] `feeds/ForegroundFeedManager.kt` — one session per gateway origin, the snapshot/stream join,
      bounded buffers, debounced re-open when the feed set changes, close on background.
- [x] `feeds/storage/FeedCursorStore.kt` — `AtomicFile` JSON per channel: epoch, offset, sequence,
      settings revision. Versioned, and a newer document is refused rather than guessed at.
- [x] `connections/`: sequence-aware seams, and a feed manifest refresh that reuses `validated`.
- [x] Wire it in `SeekerVaultApplication` (`feeds`, `feed`, the manager) and `MainActivity`
      (`onStart`/`onStop`), with the existing test seams kept.

### Proving it

- [x] Kotlin interop proof, always run: the real generated client and adapter against MockWebServer
      over HTTP/2 + TLS, with hand-built gRPC frames — connect, recovered subscription, publication,
      ping, unsubscribe, disconnect codes, malformed frame.
- [x] Kotlin integration, opt-in: two real Centrifugo nodes and real Redis, the real adapter —
      cross-node delivery, disconnect and reconnect, recovery, an epoch change, a history gap, a
      duplicate and an out-of-order event, the snapshot boundary, removing a feed.
- [x] Android unit tests: the session's reconciliation and lifecycle against a fake stream; the
      cursor store; the boundary tests; fixtures for `FeedEvent`.
- [x] Deliberate breaks for every new guard, each time-limited, restored, `cmp`-verified.

### Deployment and documentation

- [x] `broadcast/`: Centrifugo config, Redis service, the two-node overlay, Caddy's one gRPC route,
      `.env.example`, and a `compose config` + live `checkconfig`-by-starting validation.
- [x] `docs/wiki/feed-gateway.md` (streaming), `docs/protocol.md`, `docs/architecture.md`,
      `docs/security.md`, `docs/development/{broadcast,toolchain}.md`, `AGENTS.md`, `CODEBASE.md`,
      the changelog, and the device-run record for the owner's TLS/HTTP2 check.

## Caveats to state plainly at the end

- Docker: no daemon here. Compose and Caddy are validated statically; Centrifugo, Redis and the
  gateway are run natively instead, including two nodes.
- The "real Android integration" acceptance is proven as far as this machine can: the phone's own
  generated client and adapter, on the JVM, against real Centrifugo over real HTTP/2. The device run
  over TLS belongs to the owner, and gets a written procedure.
- Each subscriber still costs a connection on a node. The bounds are documented, not waved away.

## Review

Done, and verified with the real services rather than with fakes wherever a fake would have been
the easy answer.

### What changed from the plan

1. **The ticket, and why it exists at all.** The plan expected to *pin* a transport; what probing it
   found was that the transport cannot subscribe itself, which turned a client detail into a
   protocol change: `FeedService.GetStreamTicket`, two new problem codes, and a `StreamChannel`
   mapping so the phone never learns the broker's naming. Everything else followed from that.
2. **No buffer between the snapshot and the stream.** The plan said "buffer 512 events per channel,
   drop past that". Writing it made the buffer obviously unnecessary: both paths apply through
   SEE-89's revision-ordered idempotent apply, which refuses a document that is not newer, so the
   join needs no ordering and no bound. That removed a mechanism, an overflow case and its tests.
3. **`queue_max_size` is 64 KiB because of a measurement.** At 256 KiB a client that stops reading
   its socket survived more than 8 MB, because the transport's own window buffers it long before the
   broker's queue grows. The number moved, and the documentation now says what the bound does and
   does not protect — including that our own client keeps reading, so a slow phone is bounded by the
   gateway's publish rate limit rather than by the broker.
4. **The `FeedCursorStore` lost a field.** It held the settings revision as well; the connection
   record already holds it, and a second copy could disagree with the manifest the phone actually
   validated.
5. **A `Reconnecting` state stopped hiding the reason.** After a failure the listener publishes what
   went wrong, not "reconnecting" — a phone that says "reconnecting" when a certificate was refused
   is telling the owner the wrong thing to wait for.

### The breaks that prove the guards

| Break | What failed |
| --- | --- |
| an `http.Client` in `internal/gateway` | the call-out guard's allow-list |
| a hard-coded broker address in `internal/stream` | the same guard's second half |
| a `subscriber_wallet` field in `event.proto` | the contract's pinned field set |
| an `info` claim in a listener's ticket | the claim set, exactly |
| a moved field number in the vendored schema | `pnpm generate`'s digest check, and then the Android field pin |
| a second file importing the broker's schema | both halves of `FeedBoundaryTest` |
| `continuity` calling a changed epoch recovered | the recovery cases |
| the listener never reading the snapshot | the session's own test |
| a cursor document from a newer version accepted | the store's version refusal |

Each was time-limited, restored from a copy taken from a verified-clean state, and `cmp`-verified.
(The first attempt at this left a `var sneaky` behind in `feed.go`, because the restore compared
against a backup taken mid-break. Caught by `git diff`, and the reason every later backup was taken
once, up front, from a clean tree.)

### Verified with services

- **Two real broker nodes and a real Redis**, driven by the phone's own adapter: cross-node
  delivery, recovery from a cursor, a gap longer than the history, a history replaced under it, a
  duplicate arriving once, a node shutting down while the other serves, and a slow listener not
  holding up the others.
- **Real gRPC framing over TLS and HTTP/2** for the client itself, always run, with no broker
  needed.
- **The whole loop natively**: the gateway binary with the broker configured, `broadcastctl`
  registering a publisher, a ticket minted by the gateway (and refused for a server it does not
  host), a manifest and a proposal published over the publisher API, both arriving at a listener —
  and then the broker killed, a publication committing anyway, the gateway retrying, and the event
  delivered when the broker came back. No silent loss, end to end.

### Caveats

- **Docker: NOT RUN.** No daemon here. `docker compose config`, `caddy validate`/`fmt` and
  `centrifugo checkconfig` all pass, and every binary was run natively instead — including two
  broker nodes.
- **The device run is the owner's**, and `docs/testing/stage-7-1.md` is the written procedure.
- **No screen lists a feed yet.** The listener applies through the repositories; the plugins that
  read a proposal's terms are SEE-93 and SEE-94.
- **Each listener costs a connection** on a broker node. Redis makes nodes interchangeable, not
  free, and the docs say so.
