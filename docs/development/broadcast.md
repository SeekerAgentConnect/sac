# The broadcast gateway

The Go service in [`broadcast/`](../../broadcast) (SEE-90): a publisher API a developer's server
calls once per thing it wants to say, and a read-only client API every subscribed phone reads from.
[`docs/wiki/broadcast-gateway.md`](../wiki/broadcast-gateway.md) is why it is shaped the way it is;
this page is how to run it, what its settings do, and where its code and tests are.

It is not the reverse proxy in [`gateway/`](../../gateway), which is one owner's own deployment in
front of their own sidecar. Different service, different operator, different directory.

## Running it

The toolchain is Go alone — the version in [`broadcast/go.mod`](../../broadcast/go.mod), which is
also what CI reads ([`docs/development/toolchain.md`](toolchain.md)).

```sh
cd broadcast
go build ./...
go test ./...
```

From the repository root, `pnpm check:broadcast` runs the formatting check, `go vet` and the tests —
the same command CI runs. It is separate from `pnpm check` because that one must not need Go.

Natively, with a database in the current directory:

```sh
go run ./cmd/broadcastctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "local"

BROADCAST_PUBLIC_URL=http://127.0.0.1:8090 \
BROADCAST_DATABASE_PATH=./broadcast.db \
go run ./cmd/broadcast
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
curl -sS http://127.0.0.1:8090/seekervault.gateway.v1.FeedService/ListProposals \
  -H 'Content-Type: application/json' \
  -d '{"channel":"server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"}'
```

In Docker, from `broadcast/`: `cp .env.example .env && docker compose up -d --build`, then
`docker compose run --rm ctl register --server <uuid>`. The internet-facing overlay is a separate
command, so plain HTTP cannot become the public default by omission:
`docker compose -f compose.yaml -f compose.public.yaml up -d --build`.

## Configuration

Every setting is one environment variable, a problem names the variable rather than guessing a
value, and every problem is reported at once — the sidecar's own rules
([`internal/config`](../../broadcast/internal/config)). Nothing here has a default that opens
something.

| Variable | Default | What it is |
| --- | --- | --- |
| `BROADCAST_PUBLIC_URL` | none; required | This gateway's own origin, as a phone's feed reference spells it. Every published manifest must name exactly this |
| `BROADCAST_DATABASE_PATH` | none; required | The SQLite file. It is the authority for everything served |
| `BROADCAST_READ_ADDRESS` | `127.0.0.1:8090` | Where the client API listens |
| `BROADCAST_PUBLISHER_ADDRESS` | `127.0.0.1:8091` | Where the publisher API listens. It must differ from the read address |
| `BROADCAST_RETENTION_HOURS` | 168 | How long past its own expiry a proposal is still served |
| `BROADCAST_MAX_PROPOSALS` | 200 | The most proposals one channel may hold at once |
| `BROADCAST_READ_RATE`, `BROADCAST_READ_BURST` | 20, 60 | Reads per second per caller, and the burst |
| `BROADCAST_PUBLISH_RATE`, `BROADCAST_PUBLISH_BURST` | 2, 20 | Publications per second per publisher, and the burst |

**There is no credential in the configuration.** A publisher's credential is created by
`broadcastctl` and kept as a SHA-256, so there is nothing in the environment, a process list or a
compose file for one to leak from.

`BROADCAST_PUBLIC_URL` is the one setting with no error message on this side if it is wrong:
publications succeed, and every phone refuses the manifest because the origin is not the one it
added the feed from (SEE-88). It is canonicalized the way the phone canonicalizes one — HTTPS, or
plain HTTP on loopback for development; lowercase scheme and host; no default port; no path at all —
so `https://Feeds.Example.com:443/` and `https://feeds.example.com` are the same origin and a URL
with a path is refused at startup.

## The operator's tool

`broadcastctl` is local, and registering a publisher is the one act that grants the ability to
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
| `cmd/broadcast` | The server: configuration, the store, two listeners, the drainer, the sweep, and an orderly shutdown |
| `cmd/broadcastctl` | The operator's tool, and its tests — which also pin that the tool and the gateway agree about what a credential is |
| `internal/config` | The environment, validated, and the canonical form of a gateway origin |
| `internal/rules` | What the gateway accepts, as pure functions: the document rules, the ordering rules, and what a withdrawal leaves behind. The phone's own rules, on this side |
| `internal/store` | The only place that speaks SQL: six tables, one writer, and a publication that commits with its notice |
| `internal/gateway` | The two handlers, the credential interceptor, the limiter, the page cursor, the strict JSON codec, and the boundary tests |
| `internal/dispatch` | The outbox drainer, its backoff, and `Dispatcher` — the seam SEE-91 fills |
| `internal/gen` | Generated from `proto/`, committed, and never edited by hand |

## Tests

`go test ./...`, and they are the whole acceptance for this task: there is no device behaviour in a
service, and nothing here is mocked that the binary does not also use.

| File | What it holds |
| --- | --- |
| `internal/rules/rules_test.go` | Every document rule with its own answer, what a revision means, and that a document is rebuilt rather than relayed |
| `internal/store/store_test.go` | The schema, credentials and rotation, a publication and its notice committing together, a notice surviving a stop, paging order, retention, and forgetting a publisher |
| `internal/config/config_test.go` | The two settings with no default, the ranges, and what cannot be an origin |
| `internal/dispatch/dispatch_test.go` | Delivery, failure and retry, a publication landing mid-flight, a document swept while its notice waited, and backoff |
| `internal/gateway/publish_test.go` | Two publishers that cannot reach each other, credentials and rotation, refusing a redirection, retries and conflicts, withdrawal, the channel bound, rate limits, and a restart that still owes a fan-out |
| `internal/gateway/read_test.go` | A phone reading a feed with no credential, a walk that stays stable while the feed moves, the caching answers, what a reader cannot ask for, and expiry and retention |
| `internal/gateway/privacy_test.go` | The three ways to try to submit something about a person, the read listener's lack of any write, that no credential reaches a log line, and that reading writes nothing down |
| `internal/gateway/boundary_test.go` | No HTTP client in shipped code, SQL only in the store, pure rules, no provider named, the schema's columns, the contract's fields, and neither listener serving the other's procedures |
| `internal/gateway/fixtures_test.go` | The committed cross-runtime fixtures are what the gateway actually answers |
| `internal/gateway/internal_test.go` | Page tokens, the limiter's arithmetic and bound, who a call is counted against, and that every problem has a code |

The phone's side of the same contract is `GatewayProtocolFixturesTest`, which reads the same fixture
files and requires the phone's validators to accept what is in them. Between them the loop is
closed without the two runtimes talking to each other — which is what SEE-91 is for.

## Deployment

The assets are the ones SAW-035 established, in this service's own directory: a `Dockerfile`, a
base `compose.yaml` with a `Caddyfile` on loopback, a `compose.public.yaml` overlay with a
`Caddyfile.public` that terminates TLS for a domain, and an `.env.example`.

Three things differ from `gateway/`'s, and each for a reason:

- **The image is `FROM scratch`**, because the binary is static and there is nothing else it needs.
  There are deliberately no CA certificates in it: the gateway is called and calls nobody, so
  adding one later is a visible line in the Dockerfile rather than something that was always there.
- **There is no healthcheck in the gateway's container**, because a scratch image has no shell to
  probe itself with. The proxy's healthcheck goes through the published listener and out the other
  side, which proves more than a self-probe would.
- **The proxy routes two upstreams**: `FeedService` to the read listener and `PublisherService` to
  the publisher one. Deleting the publisher route is how an operator keeps publishing off the
  internet; phones are unaffected, because they never call it.

`docker compose config` validates both files, and `caddy validate` both Caddyfiles, without a
daemon — which is how they were checked here (`docs/changelog/2026-09-17.md`).
