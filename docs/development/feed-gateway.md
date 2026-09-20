# The feed gateway

The Go service in [`feed-gateway/`](../../feed-gateway): an authenticated publisher API and an anonymous
public-feed API. SEE-130 removed the former invitation/device API for private server connections.
[`docs/wiki/feed-gateway.md`](../wiki/feed-gateway.md) is why it is shaped the way it is;
this page is how to run it, what its settings do, and where its code and tests are.

It is not the reverse proxy in [`gateway/`](../../gateway), which is one owner's own deployment in
front of their own sidecar. Different service, different operator, different directory.

## Running it

The toolchain is Go alone — the version in [`feed-gateway/go.mod`](../../feed-gateway/go.mod), which is
also what CI reads ([`docs/development/toolchain.md`](toolchain.md)).

```sh
cd feed-gateway
go build ./...
go test ./...
```

From the repository root, `pnpm check:feed-gateway` runs the formatting check, `go vet` and the tests —
the same command CI runs. It is separate from `pnpm check` because that one must not need Go.

Natively, with a database in the current directory:

```sh
go run ./cmd/feed-gatewayctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "local"

BROADCAST_PUBLIC_URL=http://127.0.0.1:8090 \
BROADCAST_DATABASE_PATH=./broadcast.db \
go run ./cmd/feed-gateway
```

The credential is printed once. A publication then looks like this — the JSON codec is strict, so a
misspelled field is an error rather than something quietly dropped:

```sh
curl -sS http://127.0.0.1:8091/seekervault.gateway.v1.PublisherService/PublishManifest \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $CREDENTIAL" \
  -d '{"manifest":{"serverId":"3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","protocolVersion":1,
       "settingsRevision":"1","mode":"CONNECTION_MODE_GATEWAY_FEED",
       "environments":["SERVER_ENVIRONMENT_PRODUCTION"],
       "feed":{"gatewayUrl":"http://127.0.0.1:8090",
               "channel":"server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"}}}'
```

and a read needs no credential at all:

```sh
curl -sS http://127.0.0.1:8090/seekervault.gateway.v1.FeedService/ListRequests \
  -H 'Content-Type: application/json' \
  -d '{"channel":"server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"}'
```

`PublishRequest`/`CancelRequest` and `ListRequests`/`GetRequest` are the primary SEE-108
operations. The proposal operations remain compatibility adapters over the same rows and sequence.

In Docker, copy `deploy/feed/.env.example` to `deploy/feed/.env`, start
`deploy/feed/compose.yaml`, then register through the `gateway-ctl` operator profile. Public HTTPS
is the separate `deploy/ingress/feed` project, so plain HTTP cannot become the public default by
omission. The exact commands are in [`feed-gateway/README.md`](../../feed-gateway/README.md).

## Configuration

Every setting is one environment variable, a problem names the variable rather than guessing a
value, and every problem is reported at once — the sidecar's own rules
([`internal/config`](../../feed-gateway/internal/config)). Nothing here has a default that opens
something.

| Variable | Default | What it is |
| --- | --- | --- |
| `BROADCAST_PUBLIC_URL` | none; required | This gateway's own origin, as a phone's feed reference spells it. Every published manifest must name exactly this |
| `BROADCAST_DATABASE_PATH` | none; required | The SQLite file. It is the authority for everything served |
| `BROADCAST_READ_ADDRESS` | `127.0.0.1:8090` | Where the anonymous feed API listens |
| `BROADCAST_PUBLISHER_ADDRESS` | `127.0.0.1:8091` | Where the publisher API listens. It must differ from the read address |
| `BROADCAST_RETENTION_HOURS` | 168 | How long past its own expiry a proposal is still served |
| `BROADCAST_MAX_PROPOSALS` | 200 | The most proposals one channel may hold at once |
| `BROADCAST_READ_RATE`, `BROADCAST_READ_BURST` | 20, 60 | Reads per second per caller, and the burst |
| `BROADCAST_PUBLISH_RATE`, `BROADCAST_PUBLISH_BURST` | 2, 20 | Publications per second per publisher, and the burst |
| `BROADCAST_STREAM_URL`, `BROADCAST_STREAM_API_KEY`, `BROADCAST_STREAM_TOKEN_KEY` | unset | The broker and its two keys (SEE-91). All three or none: without them the gateway answers every read and says once that there is no stream |
| `BROADCAST_TICKET_MINUTES`, `BROADCAST_MAX_CHANNELS` | 60, 32 | How long a listener's ticket lasts, and how many channels one grants |
| `BROADCAST_PUSH_CREDENTIALS`, `BROADCAST_PUSH_ENDPOINT`, `BROADCAST_PUSH_ENVIRONMENT` | unset | The push relay (SEE-92): the service account file, the push API, and `production` or `sandbox`. All three or none |
| `BROADCAST_PUSH_RATE`, `BROADCAST_PUSH_BURST` | 0.1, 5 | Hints per topic per second, and the burst. Above it a hint is dropped rather than queued |

**There is no credential in the configuration.** A publisher's credential is created by
`feed-gatewayctl` and kept as a SHA-256, so there is nothing in the environment, a process list or a
compose file for one to leak from. SEE-92's push credential is the one thing that has to be usable
rather than compared, and it is still not in the environment: what is configured is a **path**, the
file is mounted read-only into the gateway alone (`deploy/feed/compose.push.yaml`), and it is read once at
startup — a missing or malformed one stops the process with a message that names the field and no
part of its contents.

`BROADCAST_PUBLIC_URL` is the one setting with no error message on this side if it is wrong:
publications succeed, and every phone refuses the manifest because the origin is not the one it
added the feed from (SEE-88). It is canonicalized the way the phone canonicalizes one — HTTPS, or
plain HTTP on loopback for development; lowercase scheme and host; no default port; no path at all —
so `https://Feeds.Example.com:443/` and `https://feeds.example.com` are the same origin and a URL
with a path is refused at startup.

## The operator's tool

`feed-gatewayctl` is local, and registering a publisher is the one act that grants the ability to
publish. There is no administrative API to authenticate against.

| Command | What it does |
| --- | --- |
| `register --server <uuid> [--label]` | Registers a publisher and prints one credential, once |
| `rotate --server <uuid> [--label]` | Adds a second credential, so the first can be retired without an outage |
| `revoke --credential <id>` | Ends one credential, named by the handle `list` prints |
| `revoke --server <uuid> --all` | Ends every credential a publisher holds. Its documents stay |
| `list [--server <uuid>]` | What is registered, or one publisher's credentials |
| `forget --server <uuid> --yes` | Removes a publisher and everything it published |

`--database`, or `BROADCAST_DATABASE_PATH`, must be the file the gateway reads: pointing the two at
different files is the one mistake that looks like a credential that does not work. The tool can run
while the gateway is up.

## Code

| Path | What is in it |
| --- | --- |
| `cmd/feed-gateway` | The server: configuration, the store, two isolated listeners, the drainer, the sweep, and an orderly shutdown |
| `cmd/feed-gatewayctl` | The operator's tool, and its tests — which also pin that the tool and the gateway agree about what a credential is |
| `internal/config` | The environment, validated, and the canonical form of a gateway origin |
| `internal/rules` | What the gateway accepts, as pure functions: the document rules, the ordering rules, and what a withdrawal leaves behind. The phone's own rules, on this side |
| `internal/storage` | The durable contracts used by publication, reads, delivery, maintenance, and the local operator tool. Business code depends on these interfaces and contains no SQL or SQLite import |
| `internal/storage/sqlite` | The only place that speaks SQL: the six public publication/configuration/outbox tables, transaction ownership, and the one-way schema-v3 retirement migration |
| `internal/gateway` | Public feed and authenticated publisher handlers; credential interceptors, limiters, cursors, the strict JSON codec and boundary tests |
| `internal/dispatch` | The outbox drainer, its backoff, `Dispatcher`, and the event envelope every subscriber receives |
| `internal/stream` | The broker (SEE-91): publishing an event over its server API, and minting the ticket a listener connects with. One of the two packages that open a connection, and it takes the address from the operator |
| `internal/relay` | The push relay (SEE-92): one content-free hint per changed feed, the topic it goes to, the quota that bounds how often a feed's subscribers are woken, and the service-account grant it is sent with. The other package that opens a connection, and it takes both addresses from its operator — one from the environment, one from the credential document |
| `internal/gen` | Generated from `proto/`, committed, and never edited by hand |

## Tests

`go test ./...` covers the service contract and its retirement boundary. Nothing in the gateway
tests is mocked that the binary does not also use.

Beside them, `pnpm test:integration` runs this **binary** against both publisher templates and two
subscribers, with a privacy sweep of everything the run wrote (SEE-98,
[`integration.md`](integration.md)). It is where the public and publisher listeners' separation, a
forgotten publisher, a revoked credential and a restart on the same database are checked as a
deployment rather than as a handler. `boundary_test.go` proves every removed private procedure and
hosted invitation route is absent from both surviving listeners.

| File | What it holds |
| --- | --- |
| `internal/rules/rules_test.go` | Every document rule with its own answer, what a revision means, that a document is rebuilt rather than relayed, and that the environments a server ID published cannot move while the order they were written in does not matter (SEE-97) |
| `internal/storage/sqlite/store_test.go` | The schema, credentials and rotation, a publication and its notice committing together, a notice surviving a stop, paging order, retention, forgetting a publisher, and the restart-safe schema-v2-to-v3 retirement that preserves every public table while dropping private routing rows |
| `internal/config/config_test.go` | The two settings with no default, the ranges, and what cannot be an origin |
| `internal/dispatch/dispatch_test.go` | Delivery, failure and retry, duplicate delivery after a sent-but-unacknowledged notice, a publication landing mid-flight, a document swept while its notice waited, and backoff |
| `internal/gateway/publish_test.go` | Two publishers that cannot reach each other, credentials and rotation, refusing a redirection, refusing a promotion to production (SEE-97) while what a subscriber reads stays as it was, retries and conflicts, withdrawal, the channel bound, rate limits, and a restart that still owes a fan-out |
| `internal/gateway/publisher_storage_test.go` | A fully evaluated publication whose storage commit fails answers failure, wakes no fan-out, and leaves neither document nor notice visible |
| `internal/gateway/http_publication_test.go` | A plain HTTP client, with no generated binding, publishes a manifest, creates and updates a feed item, withdraws it, and reads the authoritative result |
| `internal/gateway/read_test.go` | A phone reading a feed with no credential, a walk that stays stable while the feed moves, the caching answers, what a reader cannot ask for, and expiry and retention |
| `internal/gateway/privacy_test.go` | The three ways to try to submit something about a person, the read listener's lack of any write, that no credential reaches a log line, and that reading writes nothing down |
| `internal/gateway/boundary_test.go` | No HTTP client in shipped business code, no SQLite import outside composition/storage tests, SQL only in `internal/storage/sqlite`, pure rules, no provider named, the six live schema tables, the contract's fields and reservations, two listeners only, no listener serving another role's procedures, and 404s for every retired private RPC and invitation route |
| `internal/gateway/fixtures_test.go` | The committed cross-runtime fixtures are what the gateway actually answers |
| `internal/gateway/internal_test.go` | Page tokens, the limiter's arithmetic and bound, who a call is counted against, and that every problem has a code |
| `internal/gateway/ticket_test.go` | Which channels a listener is granted, which are left out, what is refused, and that asking to listen writes nothing down |
| `internal/gateway/topics_test.go` | Which channels are named a topic, which are left out, what is refused, that asking writes nothing down, and that a publisher cannot cause a hint about another feed |
| `internal/stream/stream_test.go` | What a publication carries, that a retry is one publication, and that every refusal is a failure to retry rather than a delivery |
| `internal/stream/ticket_test.go` | The claim set, exactly: an empty subject, an expiry, the channels — and a signature that verifies the way the broker verifies it |
| `internal/stream/broker_test.go` | The same publication against a **real** Centrifugo with the shipped configuration. Opt-in: `SEEKERVAULT_CENTRIFUGO=/path/to/centrifugo go test ./internal/stream/ -run TestBroker` |
| `internal/relay/relay_test.go` | What a hint carries — and, in bytes, what it does not — the topic's derivation, the quota, the one retry after a refused token, and that no failure is ever reported to the drainer |
| `internal/relay/token_test.go` | The assertion's claim set exactly, its signature verified with the public half of the key, the grant flow, the caching and its margin, and that no error quotes a credential |
| `internal/relay/firebase_test.go` | One **real** hint to a real project, which is the only way to know Google accepts this message. Opt-in: `SEEKERVAULT_FCM_CREDENTIALS=... SEEKERVAULT_FCM_SERVER=... go test ./internal/relay/ -run Firebase` |

The phone's side of the same contract is `GatewayProtocolFixturesTest`, which reads the same fixture
files — including the three `FeedEvent` ones taken from the gateway's own outbox — and requires the
phone's validators to accept what is in them.

### The stream, end to end

Two tests on the phone's side finish the loop, and one of them needs services:

| Where | What it needs | What it proves |
| --- | --- | --- |
| `feeds/UniStreamInteropTest` | nothing (always runs) | the phone's generated client and adapter against real gRPC framing over TLS and HTTP/2: the request it sends, the pushes it decodes, the codes it classifies |
| `feeds/CentrifugoStreamIntegrationTest` | a Centrifugo and a Redis binary | two real nodes sharing Redis: cross-node delivery, recovery from a cursor, a history gap, an epoch change, a duplicate, a node shutting down, and a listener that cannot keep up |
| `feeds/ConnectFeedTopicsTest` | nothing (always runs) | the phone asking where hints arrive, over real Connect bodies: the channels it sends, and that a topic for a channel nobody asked about is refused |
| `push/FeedTopicManagerTest`, `push/SeekerVaultMessagingServiceTest`, `sync/FeedSynchronizationTest`, `notifications/FeedNotificationsTest` | nothing (always run) | the phone's whole hint path (SEE-92): subscribing and unsubscribing, the two kinds of invalidation, the bounded read and what it skips, and the alert with its read-only route |
| `push/FeedHintContractTest` | nothing (always runs) | that the phone and the relay still agree on what a hint is — it reads the relay's own Go source, because a drift here would be silence rather than an error |

```sh
android/gradlew -p android :app:testDebugUnitTest \
  --tests 'io.github.brrenat.seekervault.feeds.CentrifugoStreamIntegrationTest' \
  -Dseekervault.centrifugo=/path/to/centrifugo \
  -Dseekervault.redis=/path/to/redis-server
```

Both skip cleanly when the binaries are not named, which is why CI runs everything else. What is
left after them is the device run: a real phone, a real certificate, one origin
(`docs/testing/stage-7-1.md`).

## Deployment

The application image remains in this module. Canonical orchestration is in `deploy/feed`; optional
public HTTPS/HTTP2 is a separately operated `deploy/ingress/feed` project.

Three details matter:

- **The runtime image is minimal.** The binary is static; the CA bundle added for SEE-92's optional
  push connection is the only runtime support file.
- **There is no healthcheck in the gateway's container**, because a scratch image has no shell to
  probe itself with. The optional ingress checks the private read listener.
- **The optional ingress routes two upstreams**: `FeedService` to the public read listener and
  `PublisherService` to the backend listener. An operator may keep publisher RPCs private while
  exposing public feed reads.

`pnpm check:deployments` resolves every canonical Compose preset without a Docker daemon. A Caddy
runtime can additionally validate the ingress configuration without starting applications.
