# SEE-90 — The Go broadcast gateway: publishing, server configuration, and feed snapshots

Stage 7.1, the fifth fed child of SEE-85. SEE-88 made a connection's kind part of its record and
left `FeedGateway` as the seam a feed's manifest comes through; SEE-89 added the proposal a
publisher broadcasts and left `ProposalFeed` as the seam it arrives through. Both seams say the same
thing: **the gateway is SEE-90.** This task is that gateway.

It is the first Go in this repository, and the first component neither the owner nor their agent
runs: a developer publishes once, and every phone subscribed reads from here. The developer's own
server is never contacted by a phone, and nothing a phone decides ever arrives here.

## What the ticket asks for

- The common publication and read API for gateway-feed servers, in Go, against the agreed
  manifest/proposal contracts, with **publisher and client APIs kept separate**.
- Publishers registered with credentials **scoped to their own server and channel**, with rotation
  and revocation, input validation, bounded payloads and rate limits. A channel outside the grant is
  never accepted.
- Versioned manifest/settings publication, proposal creation/update/cancellation, **idempotent
  retries**, and refusal of conflicting revisions and invalid lifecycle transitions.
- Unary reads: manifest, a **paginated current feed snapshot**, and proposal details, with
  version-aware caching and explicit retention/cursor behaviour.
- **Persist first, fan out second.** A crash between the two must be replayable and idempotent, not
  silent loss and not a second logical proposal.
- A minimal durable store, documented, holding shared publications and publisher configuration —
  never a subscriber's wallet, amount, decision or result. Centrifugo/Redis history is a bounded
  recovery cache, never the source of truth.
- Streaming stays behind the gateway boundary (SEE-91 integrates it). No financial execution
  endpoint, and no proxying of the phone's Jupiter orders.
- Extend SEE-45's deployment/TLS assets, and **distinguish the new broadcast gateway from the
  existing deployment reverse proxy**.

## What this build does and does not do

- It serves the whole API the ticket asks for, and its own tests drive it over a real HTTP listener
  with real Connect clients and a real SQLite file.
- **It fans out to nothing yet.** Publication commits an outbox row in the same transaction, and a
  dispatcher drains it; Centrifugo and Redis are SEE-91, so this build carries the local dispatcher
  (a log line) and the replay machinery that makes a crash between commit and fan-out harmless.
- **No phone talks to it yet.** The Android client for `FeedGateway`/`ProposalFeed` is SEE-91's
  ("real Android integration proves server streaming plus independent unary reads"). What this task
  proves instead is that the documents it serves are the documents the phone's own validators
  accept: the cross-runtime fixtures are written from the running gateway and asserted in Kotlin.
- **Nothing was run in Docker.** No daemon is reachable here (`docs/testing/stage-7.md` records the
  same limit). The compose and Caddy assets are validated statically and the binary is run natively.

## Decisions to record

1. **`broadcast/`, not `gateway/`.** `gateway/` is the owner's deployment reverse proxy (SAW-035):
   Caddy in front of one private sidecar. This is a different thing with a different operator, so it
   gets its own directory, its own compose stack and its own README, and both READMEs say which is
   which.
2. **SQLite, one file, one process.** The store must commit a publication and its outbox row
   together, survive a crash, and be operable by one developer. Postgres would be a second thing to
   run for a workload of bounded documents; `modernc.org/sqlite` is cgo-free, so the image stays
   `FROM scratch`-simple and the tests need no service.
3. **The revision is the idempotency key.** The contract already carries one: a publisher's revision
   is its promise about the content. So a retry is the same revision with the same content and is
   accepted as unchanged, a revision that arrives with different content is a conflict, and there is
   no second key to invent, store or expire.
4. **The publisher API is a separate service on a separate listener.** Not one service with a
   credential check per method: two sockets, two handler sets, so a read port cannot serve a write
   however the paths are routed, and a deployment can publish one and not the other.
5. **The gateway never calls out.** It is called; it calls nobody. No publisher is ever contacted,
   and a Go boundary test fails if shipped code acquires an HTTP client.
6. **`CONNECTION_MODE_DIRECT` is refused on publication.** A manifest published here must be a feed
   naming this gateway's own origin and its own channel. A direct manifest would carry a URL, and
   relaying one would make the gateway able to point a phone at a server of a publisher's choosing.

## Plan

### The contract (`proto/seekervault/gateway/v1/`)

- [x] `problem.proto`: `GatewayProblem` (one code per rule) and `GatewayErrorDetail`, the Connect
      error detail on every gateway error, like `RequestErrorDetail` is on the sidecar's.
- [x] `feed.proto`: `FeedService` — `GetServerManifest`, `ListProposals`, `GetProposal` — the
      read-only client API. Version-aware requests (`known_settings_revision`,
      `known_snapshot_sequence`), bounded page sizes, an opaque cursor, and the snapshot sequence a
      page was read at.
- [x] `publish.proto`: `PublisherService` — `PublishManifest`, `PublishProposal`, `CancelProposal` —
      the publisher API, and `PublishStatus` (`STORED`/`UNCHANGED`) for a retry's answer.
- [x] Generation: `buf.gen.go.yaml` for Go (protocolbuffers/go + connectrpc/go, the three packages
      the gateway speaks), and `buf.gen.yaml` excludes `publish.proto` from the phone and the
      sidecar — **the phone is not a publisher, so no publisher client is generated for it.**
- [x] `scripts/generate.mjs` runs both templates and compares `broadcast/internal/gen` too.
- [x] Fixtures `proto/fixtures/seekervault/gateway/v1/…`, written from what the gateway actually
      serves and asserted in Go and in Kotlin.

### The service (`broadcast/`)

- [x] `go.mod`: Go 1.27.1, `connectrpc.com/connect`, `google.golang.org/protobuf`,
      `modernc.org/sqlite`. Nothing else.
- [x] `internal/config`: the environment, validated, with a token never in a message.
- [x] `internal/store`: the schema and its migrations; publishers and their token hashes; manifests;
      proposals with a per-channel sequence; the outbox; retention. One writer, WAL, and every
      publication committed with its outbox row in one transaction.
- [x] `internal/rules`: the pure rules — a manifest's, a proposal's, and the lifecycle's — each
      answering one `GatewayProblem`, in a fixed order. (Named `rules` rather than `publish`: the
      read side applies some of them too, and the handler that applies them belongs with the other
      handler, in `internal/gateway`.)
- [x] The read side — page cursors, the snapshot boundary, version-aware answers — and its handler,
      in `internal/gateway` beside the write one, because they share the store and the error
      vocabulary and differ only in what they are allowed to do.
- [x] The credential interceptor (`internal/gateway/auth.go`): a bearer credential hashed and
      matched against what the store keeps, resolving to the publisher it was issued to and nothing
      more.
- [x] The limiter (`internal/gateway/limit.go`): a token bucket with an injected clock, per
      publisher for writes and per caller for reads, bounded in how many callers it remembers.
- [x] `internal/dispatch`: the `Dispatcher` seam, the outbox drainer, and the local dispatcher this
      build ships. Replayable, idempotent, and at-least-once by design.
- [x] `cmd/broadcast`: the server — two listeners, bounded bodies, timeouts, graceful drain.
- [x] `cmd/broadcastctl`: register, rotate, revoke, list. The token is printed once and stored as a
      hash.

### Guards and tests (Go)

- [x] `boundary_test.go`: the proto field sets and forbidden words; no HTTP client in shipped code;
      SQL only in `internal/store`; the read listener serves only `FeedService`; the schema has no
      column for a subscriber.
- [x] Publisher tests: two publishers cannot touch each other's manifest, proposals or channel;
      revoked and rotated credentials; a direct manifest and a foreign gateway URL refused.
- [x] Revision tests: retry unchanged, conflicting content at the same revision, a lower revision,
      cancel then re-open, cancel twice.
- [x] Read tests: manifest caching, a paginated walk with publications landing mid-walk, details,
      unknown server and channel, a malformed cursor, expired and cancelled proposals.
- [x] Crash tests: a dispatcher that fails, a process that stops before draining, a duplicate
      publish; one notification per accepted revision after replay.
- [x] Privacy tests: unknown JSON fields refused; an unknown protobuf field never relayed; no token
      in any log line; no write path on the public listener.
- [x] `fixtures_test.go`: the committed fixtures are the documents the gateway serves.

### The phone's side of the contract

- [x] `GatewayProtocolFixturesTest` (Android): the gateway's own manifest and proposal documents
      pass `manifestFrom` and `proposalFrom` against a `FeedReference` — byte for byte, from the
      same files.

### Deployment (extending SEE-45)

- [x] `broadcast/Dockerfile`, `compose.yaml`, `compose.public.yaml`, `Caddyfile`, `Caddyfile.public`,
      `.env.example`, `README.md`: the same shape as `gateway/`'s, with the read API public and the
      publisher API routed to its own listener.
- [x] `gateway/README.md` says which gateway is which.

### Repository wiring

- [x] `package.json`: `check:broadcast` (formatting, vet, tests). `.github/workflows/ci.yml`: a Go
      job. `.gitignore`: the binary and any local database.
- [x] `docs/development/toolchain.md`: the Go pin and the two new generators.

### Documentation

- [x] `docs/wiki/feed-gateway.md`, and the sections in `docs/protocol.md`,
      `docs/architecture.md`, `docs/security.md`, and `docs/development/feed-gateway.md`.
- [x] `AGENTS.md`, `CODEBASE.md`, `README.md`, `RFC.md` (the layout line), `docs/changelog/`.

### Verification

- [x] `pnpm check`, `pnpm check:android`, `pnpm check:generated`, `pnpm check:broadcast`,
      `pnpm test:hello`, `pnpm test:queue`, `pnpm test:transfer`, `pnpm test:updates`,
      `pnpm test:push`.
- [x] A native run of the binary: register a publisher, publish, read the feed back, restart.
- [x] Deliberate breaks for each new guard, each restored and `cmp`-verified.

## Acceptance

- [x] Two publisher identities publish to their own channels and cannot mutate each other's settings
      or proposals.
- [x] A phone can fetch manifest, full current feed and details without reaching either publisher
      server.
- [x] Restart, interrupted dispatch, duplicate publish, conflicting revision and expired/cancelled
      proposals are tested.
- [x] Snapshot pagination has a documented consistency boundary, and a reconnecting client converges
      without a live stream.
- [x] Public endpoint tests reject attempts to submit wallet/amount/result data as personal
      execution records.
- [x] Deployment, authentication, retention and caching behaviour are documented with focused Go
      tests.

## Review

Written after the work landed.

### The decisions, and where they ended up

The six recorded before starting all held. Four more came out of writing it:

**7. The rules are one package, and the handlers are another.** The plan had `internal/publish` and
`internal/feed`, split by API. What actually divides this service is purity: the document rules and
the ordering rules are the same on both sides and belong nowhere near a request, and the two
handlers share a store, an error vocabulary and a set of interceptors. So `internal/rules` is
pure and `internal/gateway` holds both handlers — and the boundary test can then say something
worth saying about each: the rules reach for an exact list of nine things, and nothing in the
service calls out.

**8. The JSON codec is strict, and that is the answer to the ticket's privacy acceptance.** Connect's
default ignores a field the contract does not have, so the first version of the test that publishes
a wallet and an amount alongside a proposal got **200 OK** and the fields silently dropped. Dropping
them is correct; answering 200 is not — a client that believed the gateway kept execution records
would go on believing it. A strict codec (30 lines) makes the JSON path refuse outright, and the
binary path keeps the other guarantee: every document is rebuilt from the fields that were
validated, so an unknown field is never stored or relayed. Both are tested, in both directions.

**9. A notice is cleared only at the revision it was sent at.** The outbox holds one row per
document, not per revision, so two publications that have not gone out collapse into the later one —
which is what a subscriber wants and what the push invalidations already do (SAW-056). The bug that
shape invites is a publication landing while a notice is in flight: deleting the row afterwards
would drop that fan-out silently. The delete matches the revision, so a row that moved stays
pending. This was written deliberately and then broken deliberately, and the two tests that caught
it are the reason to keep the conditional.

**10. The fixtures are written from the running service, not for it.** The gateway's own test runs
the scenario the committed fixtures describe and requires each file to be exactly what it answered;
the phone's `GatewayProtocolFixturesTest` then requires its validators to accept the same files. The
withdrawn proposal in them turned out to be byte-identical to the `Proposal/cancelled` fixture
SEE-89 already had — which is the strongest thing this task can say without SEE-91's transport: the
document the gateway produces on a withdrawal is the document the phone was already tested against.

### The breaks that prove the guards

| Break | What failed |
| --- | --- |
| `internal/rules` importing the store | the import allow-list |
| a `wallet` field in `feed.proto` | the contract's field set and word list |
| an `http.Get` in the fan-out | the no-outbound-call check |
| a `last_reader_wallet` column in the schema | the store's pinned columns |
| the publisher handler mounted on the read listener | both listeners' procedure surfaces, and the public-listener test |
| accepting the same revision with different content | the rules' revision cases, and the publisher suite |
| clearing a notice whatever revision it was sent at | the collapsing case and the mid-flight case |
| a permissive JSON codec | the subscriber-data refusal |
| one flipped byte in a gateway fixture | `GatewayProtocolFixturesTest`; on the JSON side instead, the Go fixtures test and `pnpm check:generated` |

Each was time-limited, restored from a copy, and `cmp`-verified.

### Caveats

- **Nothing is fanned out to.** Centrifugo and Redis are SEE-91; `dispatch.Dispatcher` is the seam,
  and this build's dispatcher writes a log line. The replay machinery is exercised either way.
- **No phone calls the gateway.** The Android client for `FeedGateway` and `ProposalFeed` is
  SEE-91's, which is also where a real stream and real unary reads over TLS get proven. The
  fixtures are what closes the loop here.
- **Docker: NOT RUN.** No daemon is reachable. `docker compose config` and `caddy validate` accept
  every file, and the binaries were run natively instead — including a `SIGTERM` and a restart.
- **Physical-device checks: NOT RUN, and there are none.** No app behaviour changed.
- **The read rate limit is a backstop.** Behind the shipped Caddy the gateway counts the forwarded
  address, trusted only from loopback; a serious per-client limit belongs in the proxy or the
  network, and the documentation says so rather than implying the gateway is the place for it.
