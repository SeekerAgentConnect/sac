# The publisher templates

The Go module in [`publisher/`](../../publisher) (SEE-95): a developer's or a trader's own server,
which publishes signals to the shared broadcast gateway and stops there.
[`docs/wiki/copytrading-template.md`](../wiki/copytrading-template.md) is why it is shaped the way
it is and [`docs/integrations/signal-api.md`](../integrations/signal-api.md) is its API; this page
is how to run it, what its settings do, and where its code and tests are.

It is neither of the other two services. [`broadcast/`](../../broadcast) is the shared gateway this
publishes *to*, run by whoever hosts the broadcast; [`sidecar/`](../../sidecar) is one owner's
private server for their own phone. Three servers, three operators.

## Running it

The toolchain is Go alone — the version in [`publisher/go.mod`](../../publisher/go.mod), which is
the same one `broadcast/go.mod` pins ([`docs/development/toolchain.md`](toolchain.md)).

```sh
cd publisher
go build ./...
go test ./...
```

From the repository root, `pnpm check:publisher` runs the formatting check, `go vet` and the tests —
the same command CI runs. It also builds the broadcast gateway into a temporary directory so the
opt-in test that runs the real thing is not skipped; a machine that cannot build it gets every other
test and a warning saying so.

Two things have to exist before a template can publish: a gateway, and a credential from whoever
operates it.

```sh
# in broadcast/, once per publisher
go run ./cmd/broadcastctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "copy trading"

BROADCAST_PUBLIC_URL=http://127.0.0.1:8090 BROADCAST_DATABASE_PATH=./broadcast.db \
go run ./cmd/broadcast
```

Then, natively, with a database in the current directory:

```sh
cd publisher
PUBLISHER_SERVER_ID=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 \
PUBLISHER_PUBLISH_URL=http://127.0.0.1:8091 \
PUBLISHER_ENVIRONMENT=sandbox \
PUBLISHER_DATABASE_PATH=./publisher.db \
BROADCAST_CREDENTIAL=<the credential broadcastctl printed> \
PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
go run ./cmd/copytrading
```

`PUBLISHER_PUBLISH_URL` is needed **only** in this shape, and it is the one setting worth
understanding before the first start. Run natively, the gateway has two loopback listeners — reads
on 8090, publications on 8091 — so the origin a phone reads from is not the address a publication
goes to. Run through the gateway's own compose stack, one proxy serves both on one origin and this
setting can be left empty. Get it wrong and the publication gets a 404 from a listener that has no
handler which could write anything; the template says so at startup, because nothing else would.

A publication then looks like this, and the template prints the feed reference on stdout at startup:

```sh
export PUBLISHER_API_TOKEN=…
go run ./cmd/publishctl create --in 2h --note "trimming SOL into USDC" \
  --term input_mint=So11111111111111111111111111111111111111112 \
  --term input_decimals=9 \
  --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term output_decimals=6 \
  --term max_slippage_bps=50
```

In Docker, from `publisher/`: `cp .env.example .env && docker compose up -d --build`, then
`docker compose run --rm ctl status`. The internet-facing overlay is a separate command, and worth
thinking about first — what it publishes is a write API, not the gateway's public read port:
`docker compose -f compose.yaml -f compose.public.yaml up -d --build`.

## Configuration

Every setting is one environment variable, a problem names the variable rather than guessing a
value, and every problem is reported at once — the sidecar's and the gateway's own rules
([`internal/config`](../../publisher/internal/config)). Nothing here has a default that opens
something.

| Variable | Default | What it is |
| --- | --- | --- |
| `PUBLISHER_SERVER_ID` | none; required | The lowercase UUID this publisher was registered with. Its channel is `server/<this>` |
| `PUBLISHER_GATEWAY_URL` | none; required | The gateway's own origin, as a phone's feed reference spells it. Every published manifest names exactly this |
| `PUBLISHER_ENVIRONMENT` | none; required | `production` or `sandbox`. One deployment serves one, and the database is stamped with it |
| `PUBLISHER_DATABASE_PATH` | none; required | The SQLite file: the signals, and what the gateway has confirmed about each |
| `PUBLISHER_API_TOKEN` | none; required | The token a caller of this template's API presents. At least 32 characters |
| `BROADCAST_CREDENTIAL` | none; required | The credential the gateway's operator issued this publisher |
| `PUBLISHER_PUBLISH_URL` | `PUBLISHER_GATEWAY_URL` | Where publications are *sent*, when that is not the origin phones read from |
| `PUBLISHER_API_ADDRESS` | `127.0.0.1:8092` | Where this template's API listens |
| `PUBLISHER_DISPLAY_NAME` | unset | The name this server calls itself, for a connection's default label. At most 64 bytes, never verified |
| `PUBLISHER_PUBLISH_TIMEOUT_SECONDS` | 10 | How long one publication may take before it is treated as unreachable and retried |

Either secret may be a file instead: `BROADCAST_CREDENTIAL_FILE` and `PUBLISHER_API_TOKEN_FILE` name
a path, which is what a deployment that mounts secrets wants. Setting both a value and a file for
the same secret is a configuration error, because then there would be two answers and no way to
tell which was used.

**Both credentials have to be usable rather than compared**, which is why they are configuration at
all — the gateway keeps a publisher's credential as a SHA-256 and has none in its own environment
(`broadcast/`). Neither is ever logged, no refusal quotes one, and a boundary test drives a series
of calls including refused ones and fails if either appears in a log line.

`PUBLISHER_GATEWAY_URL` is canonicalized exactly as the gateway canonicalizes its own origin and the
phone reads a feed reference — HTTPS, or plain HTTP on loopback for development; lowercase scheme
and host; no default port; no path at all — because the phone compares the manifest's gateway with
the reference it added the feed from character for character. `PUBLISHER_PUBLISH_URL` is a weaker
rule on purpose (any host, plain HTTP allowed, still no path): it is an address inside a deployment
that no phone ever sees and nothing compares, and what keeps the credential off the network is the
network it is on.

## The operator's tool

`publishctl` is a client of the API and nothing more, so every command is one HTTP call any program
could make.

| Command | What it does |
| --- | --- |
| `status` | What this publisher is, and whether anything is unpublished |
| `reference` | The `seekervault://feed` reference a phone adds this feed from, alone on stdout |
| `create --in 2h --term k=v …` | Publish a new signal. Mints and prints an idempotency key unless `--key` gives one |
| `update <id> --in 1h --term k=v …` | Replace a signal's whole statement |
| `cancel <id>` | Withdraw one |
| `retry <id>` | Try a refused publication again |
| `list`, `show <id>` | What this template holds, and what the gateway has confirmed |

`--url` (or `PUBLISHER_API_URL`) and `--token` (or `PUBLISHER_API_TOKEN`) say where and with what.
`--in <duration>` is a convenience: the tool works out the instant, because the API takes nothing
but an absolute one. The answer is JSON on stdout so it can be piped; what a person reads is on
stderr.

## Code

| Path | What is in it |
| --- | --- |
| `cmd/copytrading` | The CopyTrading template: configuration, the store, the swap kind, the manifest at startup, the API, the drainer, an orderly shutdown |
| `cmd/publishctl` | The operator's tool, and its tests — which pin what each command sends |
| `internal/config` | The environment, validated, and the two canonical forms of an address |
| `internal/ids` | A lowercase v4 UUID, which is the only identity shape this protocol has |
| `internal/signals` | What a signal is, as pure data; the `Kind` seam a template supplies; the swap kind and its terms; the document a signal becomes, and its fingerprint |
| `internal/manifest` | What this server says about itself, its fingerprint, and the feed reference |
| `internal/store` | The only place that speaks SQL: four tables, one writer, and two revisions per row that are the whole of the outbox |
| `internal/publish` | The one package that reaches out: the gateway client, the classification of every refusal, and the drainer |
| `internal/api` | The JSON API, its authorization, its strict decoding, and the boundary tests |
| `internal/gen` | Generated from `proto/` by `buf.gen.publisher.yaml`, committed, and never edited by hand |

**No feed client is generated for this module.** `buf.gen.publisher.yaml` takes `publish.proto`,
`problem.proto`, `proposal.proto` and `manifest.proto` and nothing else, so a template cannot read a
feed because no client for one exists here — the same argument that keeps `publish.proto` out of the
phone's generation. A boundary test checks both halves of that: what is absent, and what is present.

## Tests

`go test ./...`, and they are the whole acceptance for this task apart from the device run: there is
no phone behaviour in a publisher, and nothing here is mocked that the binary does not also use.

| File | What it holds |
| --- | --- |
| `internal/config/config_test.go` | The six settings with no default, every problem at once, the two canonical address forms, a secret as a file, and that a credential must be one word an HTTP header can carry |
| `internal/signals/signals_test.go` | The expiry rules, the note's bounds, the terms' bounds, that the document lists its terms in key order, and what the fingerprint is and is not |
| `internal/signals/swap_test.go` | Every way a swap's terms can be wrong with the code a caller is told, that direction is the pair and there is no side field, canonical numbers, absent-is-absent, a label counted the way the phone counts it, and base58 |
| `internal/signals/contract_test.go` | The bounds are the **gateway's own**, read out of its source rather than trusted |
| `internal/manifest/manifest_test.go` | A manifest is always a gateway feed, names one environment, carries the kind's plugin requirement, and a reference that carries no secret |
| `internal/store/store_test.go` | The live schema has no column for a subscriber; the file remembers whose it is and which environment; revisions, idempotency, a withdrawal being final, a late answer confirming nothing, deferral and refusal, and everything surviving a restart |
| `internal/publish/publish_test.go` | The credential goes in a header and nowhere else, the same document again is unchanged, a withdrawal of nothing is not an error, **every refusal classified** with the reason, and that a publication is never redirected |
| `internal/publish/drain_test.go` | The manifest first, a restart republishing identical bytes, a gateway that is down, a refusal that stops, a withdrawal before anything was published, and the drainer running on a wake-up |
| `internal/publish/gateway_test.go` | The **real gateway**, as a separate process. Opt-in: `SEEKERVAULT_BROADCAST=/path/to/broadcast go test ./internal/publish/ -run Gateway` |
| `internal/api/api_test.go` | A signal published end to end, a retried create, a reused key, a create with no key, the token on every route, every malformed statement, a gateway that is down, a refusal and its retry, an update that changes nothing, a withdrawal being final, 404s, 405s, and strict decoding |
| `internal/api/boundary_test.go` | No feed client compiled, one package calling the gateway, no Firebase or broker or MCP in the source, no service address compiled in, no credential in a log line, nothing about a subscriber accepted in a body or a term, and the CLI importing nothing privileged |
| `cmd/publishctl/main_test.go` | What each command sends, the key it mints and prints, terms from a file, what it refuses to send at all, and how it reports a refusal and a pending publication |

### Against the real gateway

The one test that needs a binary this module does not build. It is what proves the two services
agree rather than assuming it — the manifest's shape, the channel, the origin, and what a
republication is answered with — and it is also the automated half of "two phones see the same
proposal":

```sh
cd broadcast && go build -o /tmp/broadcast ./cmd/broadcast \
             && go build -o /tmp/broadcastctl ./cmd/broadcastctl
cd ../publisher && SEEKERVAULT_BROADCAST=/tmp/broadcast go test ./internal/publish/ -run Gateway -v
```

It registers a publisher with the real `broadcastctl`, starts the real gateway on two loopback
ports, publishes a manifest and a signal, **reads the feed back twice with two independent clients
and compares the bytes**, republishes the identical document and requires `UNCHANGED`, then
withdraws and reads the withdrawal. `pnpm check:publisher` does all of that for you.

What is left after it is the device run: two real phones on one feed, each choosing its own amount
([`docs/testing/stage-7-1.md`](../testing/stage-7-1.md)).

## Deployment

The assets are the ones SAW-035 established and `broadcast/` follows, in this service's own
directory: a `Dockerfile`, a base `compose.yaml` with a `Caddyfile` on loopback, a
`compose.public.yaml` overlay with a `Caddyfile.public` that terminates TLS for a domain, and an
`.env.example`.

Two things differ from the gateway's, and each for a reason:

- **The image carries a CA bundle from the start.** This service calls out in the ordinary case —
  the gateway is somebody else's HTTPS endpoint — so a publisher that could not verify it would be
  publishing to whatever answered. The gateway's own image shipped none until it gained an outbound
  call of its own.
- **The public overlay carries a warning rather than a recommendation.** What it puts on the
  internet is a write API whose token is the whole grant to publish as this server, which is a
  different thing from the gateway's public read port, where everything served is a document
  somebody published for everyone. A template whose signals are written locally should stay on the
  base file and be reached over a tunnel.

`docker compose config` validates both files and `caddy validate` both Caddyfiles without a daemon —
which is how they were checked here ([`docs/changelog/2026-09-17.md`](../changelog/2026-09-17.md)).
