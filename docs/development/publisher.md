# The publisher templates

The Go module in [`publisher/`](../../publisher) (SEE-95, SEE-96): a developer's or a trader's own
server, which publishes signals to the shared broadcast gateway and stops there.
[`docs/wiki/copytrading-template.md`](../wiki/copytrading-template.md) is why it is shaped the way
it is and [`docs/integrations/signal-api.md`](../integrations/signal-api.md) is its API; this page
is how to run it, what its settings do, and where its code and tests are. For a first deployment,
[`docs/guides/server-development.md`](../guides/server-development.md) walks the whole path in order
— a gateway, a credential, a copied template, a published signal and a woken phone.

**One module, two templates**, and the difference is who writes the signals:

| | `cmd/copytrading` (SEE-95) | `cmd/prediction` (SEE-96) |
| --- | --- | --- |
| Publishes | a trader's own spot-swap signals | Jupiter Prediction markets it discovered |
| Kind | `signals.Swap` → `jupiter.swap` | `signals.Prediction` → `jupiter.prediction` |
| Written by | its callers, through the API | itself, from the provider's listing |
| Its API | create, update, cancel, read | read only; the three writing endpoints answer 403 |
| Why it is shaped so | [copytrading-template.md](../wiki/copytrading-template.md) | [prediction-template.md](../wiki/prediction-template.md) |

Everything else — the configuration, the store, the outbox, the drainer, the manifest and the API —
is shared, so most of this page is about both.

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

### The Prediction template

The same six settings, a different binary, and no signal to write by hand: it finds its own. The
provider defaults to the keyless host, so nothing else is required to see it work — though a first
run with no filters at all will find a great many markets, which is what `PREDICTION_MOST_OPEN`
is for.

```sh
cd publisher
PUBLISHER_SERVER_ID=0e1f2a3b-4c5d-4e6f-8a7b-8c9d0e1f2a3b PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 PUBLISHER_PUBLISH_URL=http://127.0.0.1:8091 PUBLISHER_ENVIRONMENT=sandbox PUBLISHER_DATABASE_PATH=./prediction.db PUBLISHER_API_ADDRESS=127.0.0.1:8094 BROADCAST_CREDENTIAL=<the credential broadcastctl printed for *this* server> PUBLISHER_API_TOKEN=$(openssl rand -base64 32) PREDICTION_CATEGORIES=economics PREDICTION_KEYWORDS=fed PREDICTION_MOST_OPEN=5 go run ./cmd/prediction
```

It is a **second publisher**, so it needs its own server ID, its own credential and its own
database. Sharing a database is refused at startup, which is the point of the stamp in it.

Then, to see what it is doing rather than waiting five minutes for the timer:

```sh
export PUBLISHER_API_URL=http://127.0.0.1:8094 PUBLISHER_API_TOKEN=…
go run ./cmd/publishctl poll | jq '.cycle'
go run ./cmd/publishctl discovery | jq '{filters, markets: [.markets[].market_id]}'
```

In Docker it is its own stack, for the same reason it is its own everything else:

```sh
cp .env.prediction.example .env.prediction
docker compose --env-file .env.prediction -f compose.prediction.yaml up -d --build
docker compose --env-file .env.prediction -f compose.prediction.yaml run --rm ctl discovery
```

## Configuration

Every setting is one environment variable, a problem names the variable rather than guessing a
value, and every problem is reported at once — the sidecar's and the gateway's own rules
([`internal/config`](../../publisher/internal/config)). Nothing here has a default that opens
something.

| Variable | Default | What it is |
| --- | --- | --- |
| `PUBLISHER_SERVER_ID` | none; required | The lowercase UUID this publisher was registered with. Its channel is `server/<this>` |
| `PUBLISHER_GATEWAY_URL` | none; required | The gateway's own origin, as a phone's feed reference spells it. Every published manifest names exactly this |
| `PUBLISHER_ENVIRONMENT` | none; required | `production` or `sandbox`. One deployment serves one, the database is stamped with it, and the gateway refuses a manifest that changes the set a server ID already published (SEE-97, [environments.md](../wiki/environments.md)). Both `.env` examples ship as `sandbox` |
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

### The Prediction template's own settings

Everything above, plus these. All of them have a default, so a deployment can start with none of
them — and what each of them *means* is [the filters
table](../wiki/prediction-template.md#the-filters-and-what-they-mean), because a filter's exact
semantics are a promise to an operator rather than an implementation detail.

| Variable | Default | What it is |
| --- | --- | --- |
| `PREDICTION_PROVIDER_URL` | the keyless host | The provider's origin. Any host over HTTP or HTTPS, no path |
| `PREDICTION_API_KEY` | unset | The provider's key, for the keyed host. One `x-api-key` header, never anything else. `…_FILE` works too |
| `PREDICTION_SOURCE` | `polymarket` | The venue whose markets the provider aggregates: also `kalshi`, `bisonfi` |
| `PREDICTION_CATEGORIES` | unset | The provider's buckets, comma-separated: one listing walk each |
| `PREDICTION_FILTER` | unset | Its own named filter: `new`, `live`, `trending`, `upcoming` |
| `PREDICTION_TAGS` | unset | Tags, matched whole and case-insensitively against the event's own |
| `PREDICTION_KEYWORDS` | unset | Substrings, case-insensitively, of the titles, the bucket and the tags |
| `PREDICTION_STATE` | `open` | `any` also publishes closed and settled markets — **sandbox only** |
| `PREDICTION_LEAST_CLOSE_IN_MINUTES` | 60 | How soon a market may close and still be published |
| `PREDICTION_MOST_CLOSE_IN_MINUTES` | 43200 | How far ahead it may close. Must be above the floor |
| `PREDICTION_LIFETIME_HOURS` | 168 | The expiry for a market with no close time, counted from when it was first seen |
| `PREDICTION_POLL_SECONDS` | 300 | How often a cycle runs (30 to 86400) |
| `PREDICTION_PAGE_SIZE` | 25 | Events per listing call (1 to 100, the provider's own ceiling) |
| `PREDICTION_MOST_PAGES` | 4 | Listing calls per bucket, per cycle |
| `PREDICTION_MOST_OPEN` | 25 | How many proposals to hold open at once |
| `PREDICTION_MOST_CHECKS` | 20 | Tracked markets asked about directly per cycle, oldest first |
| `PREDICTION_CALL_GAP_MS` | 2100 | The least time between two calls to the provider |
| `PREDICTION_PROVIDER_TIMEOUT_SECONDS` | 15 | How long one of them may take |
| `PREDICTION_DEPOSIT_MINT` | USDC | The deposit token: USDC or JupUSD, and nothing else. Its decimals and label are derived |
| `PREDICTION_LEAST_DEPOSIT` | the provider's own floor | What this publisher will have its signals acted on with, in base units |
| `PREDICTION_MOST_DEPOSIT` | none | Its ceiling, or none at all |
| `PREDICTION_NOTE` | unset | One line of the operator's own prose, above the line the template writes |

Two of them refuse things rather than accept anything: `PREDICTION_STATE=any` is refused when
`PUBLISHER_ENVIRONMENT=production`, and `PREDICTION_DEPOSIT_MINT` is refused for anything but the
two tokens the provider takes — including a real mint that is neither, because that is a signal
every phone would refuse.

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
| `discovery` | The Prediction template only: the filters in force, the last cycle, and every market tracked |
| `poll` | The Prediction template only: run a cycle now rather than at the next interval |

`--url` (or `PUBLISHER_API_URL`) and `--token` (or `PUBLISHER_API_TOKEN`) say where and with what.
`--in <duration>` is a convenience: the tool works out the instant, because the API takes nothing
but an absolute one. The answer is JSON on stdout so it can be piped; what a person reads is on
stderr.

## Code

| Path | What is in it |
| --- | --- |
| `cmd/copytrading` | The CopyTrading template: configuration, the store, the swap kind, the manifest at startup, the API, the drainer, an orderly shutdown |
| `cmd/prediction` | The Prediction template: the same, plus the provider client and the reconciler's own goroutine, and an API nobody may write a signal through |
| `cmd/publishctl` | The operator's tool, and its tests — which pin what each command sends |
| `internal/config` | The environment, validated, and the two canonical forms of an address. `prediction.go` is the second template's half of it |
| `internal/ids` | A lowercase v4 UUID, which is the only identity shape this protocol has |
| `internal/signals` | What a signal is, as pure data; the `Kind` seam a template supplies; the two kinds and their terms; the document a signal becomes, and its fingerprint |
| `internal/jupiter` | The prediction provider: two endpoints, its pagination, its error codes, a paced client, and **the only file in the module that names its host** |
| `internal/discovery` | What a filter means, what a cycle does, and the two row types the store keeps for it. No SQL and no HTTP: it is written against two interfaces |
| `internal/manifest` | What this server says about itself, its fingerprint, and the feed reference |
| `internal/environment` | Which promise a deployment keeps (SEE-97): one type, one pair of words, shared by both templates and by everything that validates, stamps, publishes or answers with it |
| `internal/store` | The only place that speaks SQL: six tables, one writer, two revisions per row that are the whole of the outbox, and a schema that is brought forward rather than refused |
| `internal/publish` | The one package that reaches the gateway: its client, the classification of every refusal, and the drainer |
| `internal/api` | The JSON API, its authorization, its strict decoding, who may write a signal, and the boundary tests |
| `internal/gen` | Generated from `proto/` by `buf.gen.publisher.yaml`, committed, and never edited by hand |

**No feed client is generated for this module.** `buf.gen.publisher.yaml` takes `publish.proto`,
`problem.proto`, `proposal.proto` and `manifest.proto` and nothing else, so a template cannot read a
feed because no client for one exists here — the same argument that keeps `publish.proto` out of the
phone's generation. A boundary test checks both halves of that: what is absent, and what is present.

## Tests

`go test ./...`, and they are the whole acceptance for this task apart from the device run: there is
no phone behaviour in a publisher, and nothing here is mocked that the binary does not also use.

Beside them, `pnpm test:integration` runs both **binaries** — with `PREDICTION_PROVIDER_URL` aimed
at this module's own captured answers, served back over loopback — against the real gateway, two
subscribers and a privacy sweep (SEE-98, [`integration.md`](integration.md)). That is where a
deployment's settings, its API, its CLI and its database stamp are exercised as a process rather
than as a package.

| File | What it holds |
| --- | --- |
| `internal/config/config_test.go` | The six settings with no default, every problem at once, the two canonical address forms, a secret as a file, and that a credential must be one word an HTTP header can carry |
| `internal/signals/signals_test.go` | The expiry rules, the note's bounds, the terms' bounds, that the document lists its terms in key order, and what the fingerprint is and is not |
| `internal/signals/swap_test.go` | Every way a swap's terms can be wrong with the code a caller is told, that direction is the pair and there is no side field, canonical numbers, absent-is-absent, a label counted the way the phone counts it, and base58 |
| `internal/signals/prediction_test.go` | Every way a market's terms can be wrong, that no term can carry a side, the provider's floor being published rather than assumed, both deposit mints with their own decimals, and the identifier rule |
| `internal/signals/contract_test.go` | The bounds are the **gateway's own** and the prediction rules are the **phone's own**, both read out of their source rather than trusted |
| `internal/manifest/manifest_test.go` | A manifest is always a gateway feed, names one environment, carries the kind's plugin requirement, and a reference that carries no secret. An environment that came from anywhere but the configuration is published as unspecified rather than as production (SEE-97) |
| `internal/environment/environment_test.go` | Only the two words are an environment, including the ways an operator nearly gets it right; one from nowhere has no wire value at all; and the two words are **the phone's own**, read out of `ActionPlugin.kt` rather than trusted |
| `internal/store/store_test.go` | The live schema has no column for a subscriber; the file remembers whose it is and which environment; revisions, idempotency, a withdrawal being final, a late answer confirming nothing, deferral and refusal, and everything surviving a restart |
| `internal/store/markets_test.go` | A market and its signal as one write, the same market twice being one proposal, the `UNIQUE` proposal, a re-opened market at the next generation, the cycle's number surviving a restart, and **a version-1 database brought forward with its signals intact** |
| `internal/jupiter/jupiter_test.go` | A real listing decoded as the provider sent it, a walk that ends on an empty page, a market read directly, what makes a market tradeable, **every provider failure classified** with whether it is temporary, the pacing, and that the key is in one header and no message. Its fixtures are seven real answers |
| `internal/discovery/discovery_test.go` | Every reason a market is not a candidate, the expiry being the market's own close time, what the note says and that a provider's text cannot spoil it, the derived key, and the publishing order |
| `internal/discovery/reconcile_test.go` | The reconciler over the **real store**: a real listing becoming proposals, a second cycle publishing nothing, a restart publishing nothing, a postponed market moving one revision, **absence not being closure**, every way the source ends a market, an outage ending none, the ceiling, the round robin, and two cycles not running at once |
| `internal/publish/publish_test.go` | The credential goes in a header and nowhere else, the same document again is unchanged, a withdrawal of nothing is not an error, **every refusal classified** with the reason, and that a publication is never redirected |
| `internal/publish/drain_test.go` | The manifest first, a restart republishing identical bytes, a gateway that is down, a refusal that stops, a withdrawal before anything was published, and the drainer running on a wake-up |
| `internal/publish/gateway_test.go` | The **real gateway**, as a separate process. Opt-in: `SEEKERVAULT_BROADCAST=/path/to/broadcast go test ./internal/publish/ -run Gateway` |
| `internal/api/api_test.go` | A signal published end to end, a retried create, a reused key, a create with no key, the token on every route, every malformed statement, a gateway that is down, a refusal and its retry, an update that changes nothing, a withdrawal being final, 404s, 405s, and strict decoding |
| `internal/api/discovery_test.go` | Nobody may write a prediction signal, a poll running a cycle and publishing it, what `/v1/discovery` says, that the status says it is not writable, a poll refused while one runs, and **the provider's key in no answer and no log line** |
| `internal/api/boundary_test.go` | No feed client compiled, one package calling the gateway, no Firebase or broker or MCP in the source, no service address compiled in **except the provider's, in one file**, no credential in a log line, nothing about a subscriber accepted in a body or a term, each template saying who writes its signals, and the CLI importing nothing privileged |
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

There are two of those tests now, one per template, and the second is not the first with a word
changed: a prediction manifest requires the other plugin, and its proposal is built the way a cycle
builds one — through the store's market rows — so what it proves is that a *discovered* market
becomes a document the real gateway accepts, and that ending the market leaves a cancelled proposal
a phone can still read.

What is left after them is the device run: two real phones on one feed, each choosing its own
([`docs/testing/stage-7-1.md`](../testing/stage-7-1.md)).

### Against the real provider

The other opt-in test, and the one that can notice the provider changing under this template — a
beta API by its own documentation:

```sh
cd publisher && SEEKERVAULT_JUPITER=1 go test ./internal/jupiter/ -run Live -v
```

It reads one real page of the listing and then one real market from it, and checks that the fields
discovery depends on are all still there — an identifier, a category, a title, the markets inside
the event, a status, a close time — rather than asserting anything about a particular market. It is
not in `pnpm check:publisher`, because a check that needs the internet is not a check.

Everything else about the provider runs against seven answers it really gave, committed under
`internal/jupiter/testdata` with the request that produced each. To re-capture them:

```sh
node scripts/capture-jupiter.mjs --events
```

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

The Prediction template is a **separate stack**, `compose.prediction.yaml` with
`Caddyfile.prediction` and `.env.prediction.example`, rather than a service added to the base file.
The two templates are two deployments — two server IDs, two credentials, two databases — and a
publisher running both is running two publishers. What they share is the image: it carries both
binaries, and the prediction stack names the other one in its entrypoint. Its front door forwards
`GET /v1/*`, the poll and the retry, and nothing else: the three endpoints that would write a signal
are refused by the template *and* never reach it.

`docker compose config` validates all three files and `caddy validate` every Caddyfile without a
daemon — which is how they were checked here
([`docs/changelog/2026-09-17.md`](../changelog/2026-09-17.md)).
