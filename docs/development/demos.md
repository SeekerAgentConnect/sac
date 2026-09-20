# The public-feed demos

Three Go modules at the root of this repository (SEE-95, SEE-96, SEE-134): two independent
demonstrations of a developer's or a trader's own server, which publishes signals to the shared feed
gateway and stops there, and the source library they are both built on.
[`docs/wiki/copytrading-template.md`](../wiki/copytrading-template.md) and
[`docs/wiki/prediction-template.md`](../wiki/prediction-template.md) are why they are shaped the way
they are and [`docs/integrations/signal-api.md`](../integrations/signal-api.md) is their API; this
page is how to run them, what their settings do, and where the code and the tests live now. For a
first deployment, [`docs/guides/server-development.md`](../guides/server-development.md) walks the
whole path in order — a gateway, a credential, a copied demo, a published signal and a woken phone.

| Module | What it is |
| --- | --- |
| [`publisher-support/`](../../publisher-support) | The shared source library. No `main` package, no Dockerfile, no compose file, no listener, no deployment of its own |
| [`demo-copytrading/`](../../demo-copytrading) | An independent deployable: a trader's own signals, its own image, its own stack, its own database and credential ([README](../../demo-copytrading/README.md)) |
| [`demo-prediction/`](../../demo-prediction) | An independent deployable: markets it discovered itself, its own image, its own stack, its own database and credential ([README](../../demo-prediction/README.md)) |

**Three modules and two images.** Each demo's `go.mod` ends with
`replace github.com/BrRenat/SeekerAgentWallet/publisher-support => ../publisher-support`, which is
how a repository checkout resolves the library; a copy taken out of the repository takes the two
directories as siblings, or replaces that line with an explicit module revision. Nothing else joins
them. `demo-copytrading` builds, tests, images, runs, backs up, upgrades and rolls back without
`demo-prediction` ever being on the machine, and the reverse is equally true — which is the whole
point of the split, and the thing each demo's own `internal/boundary` tests fail over.

## What the library is, and what it is not

`publisher-support/` exists so that the two demos share one durable publication engine rather than
two subtly different copies of it. A signal is stored before it is published, at a settled revision,
so a retry after a crash sends identical bytes; the gateway answers `UNCHANGED` and nobody is
notified twice. That behaviour is worth getting right once, and a second implementation of it in the
second demo would be a second opinion about when a publisher has already said something.

**It is not deployable.** There is no `cmd/` in it, no Dockerfile, no compose file, no Caddyfile and
no `.env.example`, and there is nothing for those to describe: it has no listener of its own and
nothing to start. Each demo builds the binaries — including its own `cmd/publishctl`, whose `main`
is three lines over `publisher-support/publisherctl` — because a binary belongs to the thing that
ships it.

**And it is not a published "feed publisher client" product.** Nothing in it is a protocol nobody
else can speak. `publisher-support/gateway` is an ordinary authenticated HTTP/Connect call to the
gateway's documented publication API — a bearer credential in one header and a protobuf message in
the body — which any HTTP client in any language can make, and which
[`docs/development/feed-gateway.md`](feed-gateway.md) shows being made with `curl`. The library is
here because both demos happen to be written in Go, not because publishing requires it. Anyone
writing a publisher in Python, TypeScript or Rust reads
[`docs/protocol.md`](../protocol.md#publisherservice) and calls the API; nothing in this directory is
a dependency of that.

## The two demos, and the one thing that differs

| | `demo-copytrading` (SEE-95) | `demo-prediction` (SEE-96) |
| --- | --- | --- |
| Publishes | a trader's own spot-swap signals | Jupiter Prediction markets it discovered |
| Kind | `signals.Swap` → `jupiter.swap` | `signals.Prediction` → `jupiter.prediction` |
| Written by | its callers, through the API | itself, from the provider's listing |
| Its API | create, update, cancel, read | read only; the three writing endpoints answer 403 |
| Its binaries | `copytrading`, `copytrading-admin`, `publishctl` | `prediction`, `publishctl` |
| Its stack | `compose.yaml`, plus a `compose.public.yaml` overlay | `compose.yaml`, and no public overlay |
| Why it is shaped so | [copytrading-template.md](../wiki/copytrading-template.md) | [prediction-template.md](../wiki/prediction-template.md) |

That single line — who writes the signals — is `api.Authorship` in the shared frame, and it is the
only branch in the API either demo takes. Everything else about serving the API is one
implementation: the token compared in constant time, the strict decoding, the idempotency key on a
create, the revision that moves only when the content actually changed, the router's own 404 and 405
rewritten into this API's shape.

These are neither of the other two services. [`feed-gateway/`](../../feed-gateway) is the shared
gateway they publish *to*, run by whoever hosts the broadcast; [`mcp-server/`](../../mcp-server) is
one owner's private server for their own phone. Three servers, three operators.

## Running them

The toolchain is Go alone — the version in
[`publisher-support/go.mod`](../../publisher-support/go.mod), which is the same one both demos and
`feed-gateway/go.mod` pin ([`docs/development/toolchain.md`](toolchain.md)).

Each module is checked on its own, from inside its own directory, because that is what proves each
one stands on its own:

```sh
# from the repository root; each parenthesis is one module, checked on its own
(cd publisher-support && gofmt -l . && go vet ./... && go build ./... && go test ./...)
(cd demo-copytrading  && gofmt -l . && go vet ./... && go build ./... && go test ./...)
(cd demo-prediction   && gofmt -l . && go vet ./... && go build ./... && go test ./...)
```

From the repository root, `pnpm check:publisher-support`, `pnpm check:copytrading` and
`pnpm check:prediction` each run exactly that for one module, and `pnpm check:demos` runs all three
in that order — the same commands CI runs, as separate jobs. They also build the feed gateway into a
temporary directory so the opt-in tests that run the real thing are not skipped; a machine that
cannot build it gets every other test and a warning saying so. What none of them does is check the
modules *together*: the command that proves a demo is independent has to name one module.

Two things have to exist before either demo can publish: a gateway, and a credential from whoever
operates it. Each demo is registered separately, under its own UUID, with its own credential.

```sh
# in feed-gateway/, once per publisher
go run ./cmd/feed-gatewayctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "copy trading"

BROADCAST_PUBLIC_URL=http://127.0.0.1:8090 BROADCAST_DATABASE_PATH=./broadcast.db \
go run ./cmd/feed-gateway
```

Then, natively, with a database in the demo's own directory:

```sh
cd demo-copytrading
PUBLISHER_SERVER_ID=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 \
PUBLISHER_PUBLISH_URL=http://127.0.0.1:8091 \
PUBLISHER_ENVIRONMENT=sandbox \
PUBLISHER_DATABASE_PATH=./copytrading.db \
BROADCAST_CREDENTIAL=<the credential feed-gatewayctl printed> \
PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
go run ./cmd/copytrading
```

`PUBLISHER_PUBLISH_URL` is needed **only** in this shape, and it is the one setting worth
understanding before the first start. Run natively, the gateway has separate loopback listeners —
feeds on 8090 and publications on 8091 — so the origin a phone reads from is not the address a
publication goes to. Run through the gateway's own compose stack, one proxy serves them on one
origin and this setting can be left empty. Get it wrong and the publication gets a 404 from a
listener that has no handler which could write anything; the demo says so at startup, because
nothing else would.

A publication then looks like this, and the demo prints the feed reference on stdout at startup:

```sh
export PUBLISHER_API_TOKEN=…
go run ./cmd/publishctl create --in 2h --note "trimming SOL into USDC" \
  --term input_mint=So11111111111111111111111111111111111111112 \
  --term input_decimals=9 \
  --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term output_decimals=6 \
  --term max_slippage_bps=50
```

In Docker, from `demo-copytrading/`: `cp .env.example .env && docker compose up -d --build`, then
`docker compose run --rm ctl status`. The build context is the repository root, because the `replace`
line needs `../publisher-support`; the compose file already says so, and
[`demo-copytrading/README.md`](../../demo-copytrading/README.md#4-build-and-run-the-image) has the
plain `docker build` form. The internet-facing overlay is a separate command, and worth thinking
about first — what it publishes is a write API, not the gateway's public read port:
`docker compose -f compose.yaml -f compose.public.yaml up -d --build`.

### The Prediction demo

The same publisher settings, a different module, a different binary, and no signal to write by hand:
it finds its own. The provider defaults to the keyless host, so nothing else is required to see it
work — though a first run with no filters at all will find a great many markets, which is what
`PREDICTION_MOST_OPEN` is for.

```sh
cd demo-prediction
PUBLISHER_SERVER_ID=7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 \
PUBLISHER_PUBLISH_URL=http://127.0.0.1:8091 \
PUBLISHER_ENVIRONMENT=sandbox \
PUBLISHER_DATABASE_PATH=./prediction.db \
PUBLISHER_API_ADDRESS=127.0.0.1:8094 \
BROADCAST_CREDENTIAL=<the credential feed-gatewayctl printed for *this* server> \
PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
PREDICTION_CATEGORIES=economics PREDICTION_KEYWORDS=fed PREDICTION_MOST_OPEN=5 \
go run ./cmd/prediction
```

It is a **second publisher**, so it needs its own server ID, its own credential and its own database.
Sharing a database is refused at startup, which is the point of the stamp in it. `PUBLISHER_API_ADDRESS`
is set explicitly above because its default, `127.0.0.1:8092`, is the one the CopyTrading demo would
take as well; the packaged stack publishes its proxy on `127.0.0.1:${PREDICTION_PORT:-8094}` for the
same reason.

Then, to see what it is doing rather than waiting five minutes for the timer:

```sh
export PUBLISHER_API_URL=http://127.0.0.1:8094 PUBLISHER_API_TOKEN=…
go run ./cmd/publishctl poll | jq '.cycle'
go run ./cmd/publishctl discovery | jq '{filters, markets: [.markets[].market_id]}'
```

In Docker it is its own stack in its own directory, for the same reason it is its own everything
else:

```sh
cd demo-prediction
cp .env.example .env
docker compose up -d --build
docker compose run --rm ctl discovery
```

## Configuration

Every setting is one environment variable, a problem names the variable rather than guessing a
value, and every problem is reported at once — the sidecar's and the gateway's own rules. Nothing
here has a default that opens something: the API binds loopback unless it is told otherwise, and the
settings a deployment cannot get wrong have no default at all.

**Each demo's own `.env.example` is the authority for its settings**, with a paragraph on each one
beside the line that sets it, and each README's §5 is the same list as a table:

- [`demo-copytrading/.env.example`](../../demo-copytrading/.env.example) and
  [`demo-copytrading/README.md#5-configuration`](../../demo-copytrading/README.md#5-configuration)
- [`demo-prediction/.env.example`](../../demo-prediction/.env.example) and
  [`demo-prediction/README.md#5-configuration`](../../demo-prediction/README.md#5-configuration)

They are not repeated here, because a settings table in a developer page and a settings table in the
file an operator actually copies are two tables that drift.

What is worth saying here is the code behind them, because it is shared and its shape is deliberate
([`publisher-support/config`](../../publisher-support/config)). `Config` and `Load` read the settings
every publisher has — the identity, the gateway, the environment, the database, the API and its
token — and both demos call exactly the same `Load`, so a missing variable or a bad number is
reported identically wherever it is set. Beside it the package exports `Reader`: `NewReader`, `Note`,
`Text`, `Secret`, `Whole`, `List` and `Problems`, which is the same collecting reader the library
uses for its own half. [`demo-prediction/internal/config`](../../demo-prediction/internal/config)
reads its twenty-odd `PREDICTION_*` settings through it, so a Prediction deployment's two halves are
validated in one pass and reported in one message — rather than an operator fixing the publisher's
half, restarting, and only then being told about the provider's.

**Both credentials have to be usable rather than compared**, which is why they are configuration at
all — the gateway keeps a publisher's credential as a SHA-256 and has none in its own environment
(`feed-gateway/`). Neither is ever logged, no refusal quotes one, and a boundary test drives a series
of calls including refused ones and fails if either appears in a log line. Either may be a file
instead: `BROADCAST_CREDENTIAL_FILE` and `PUBLISHER_API_TOKEN_FILE` name a path, which is what a
deployment that mounts secrets wants. Setting both a value and a file for the same secret is a
configuration error, because then there would be two answers and no way to tell which was used.

`config.Origin` and `config.Reachable` are the two canonical address forms, and the difference
between them is the reason there are two. `PUBLISHER_GATEWAY_URL` goes through `Origin`, which
canonicalizes it exactly as the gateway canonicalizes its own origin and the phone reads a feed
reference — HTTPS, or plain HTTP on loopback for development; lowercase scheme and host; no default
port; no path at all — because the phone compares the manifest's gateway with the reference it added
the feed from character for character. `PUBLISHER_PUBLISH_URL` goes through `Reachable`, which is a
weaker rule on purpose (any host, plain HTTP allowed, still no path): it is an address inside a
deployment that no phone ever sees and nothing compares, and what keeps the credential off the
network is the network it is on. `HeaderSafe` is the third of them, and it is what refuses a
credential that could not survive being put in an HTTP header.

### The Prediction template's own settings

Everything above, plus the `PREDICTION_*` half, which is
[`demo-prediction`](../../demo-prediction/internal/config)'s alone and unknown to the other demo. All
of them have a default, so a deployment can start with none of them. Their ranges and defaults are
[`demo-prediction/.env.example`](../../demo-prediction/.env.example) and
[the README's table](../../demo-prediction/README.md#5-configuration); what each of them *means* is
[the filters table](../wiki/prediction-template.md#the-filters-and-what-they-mean), because a
filter's exact semantics are a promise to an operator rather than an implementation detail.

They divide in two, and the division is worth knowing before tuning any of them.
`PREDICTION_SOURCE`, `PREDICTION_CATEGORIES` and `PREDICTION_FILTER` are the **provider's own query
parameters** and decide which events come back at all; everything else — the tags, the keywords, the
state, the two close-time edges, the lifetime, and the ceilings `PREDICTION_MOST_OPEN` and
`PREDICTION_MOST_CHECKS` — is applied here, to the records that arrived.

Two of them refuse things rather than accept anything: `PREDICTION_STATE=any` is refused when
`PUBLISHER_ENVIRONMENT=production`, and `PREDICTION_DEPOSIT_MINT` is refused for anything but the two
tokens the provider takes — including a real mint that is neither, because that is a signal every
phone would refuse.

## The operator's tool

`publishctl` is a client of a demo's own API and nothing more, so every command is one HTTP call any
program could make. The implementation is
[`publisher-support/publisherctl`](../../publisher-support/publisherctl) and each demo's
`cmd/publishctl` is a three-line `main` over it — because both demos answer the same API, neither may
import the other, and two copies of one CLI would be two CLIs.

| Command | What it does |
| --- | --- |
| `status` | What this publisher is, and whether anything is unpublished |
| `reference` | The `seekervault://feed` reference a phone adds this feed from, alone on stdout |
| `create --in 2h --term k=v …` | Publish a new signal. Mints and prints an idempotency key unless `--key` gives one |
| `update <id> --in 1h --term k=v …` | Replace a signal's whole statement |
| `cancel <id>` | Withdraw one |
| `retry <id>` | Try a refused publication again |
| `list`, `show <id>` | What this demo holds, and what the gateway has confirmed |
| `discovery` | The Prediction demo only: the filters in force, the last cycle, and every market tracked |
| `poll` | The Prediction demo only: run a cycle now rather than at the next interval |

`--url` (or `PUBLISHER_API_URL`) and `--token` (or `PUBLISHER_API_TOKEN`) say where and with what.
`--in <duration>` is a convenience: the tool works out the instant, because the API takes nothing but
an absolute one. The answer is JSON on stdout so it can be piped; what a person reads is on stderr.

## Code

### The shared library

| Path | What is in it |
| --- | --- |
| `gen/` | Generated from `proto/` by `buf.gen.publisher-support.yaml`, committed, and never edited by hand |
| `signals/` | What a signal is, as pure data: `signals.go` with the `Kind` seam a demo supplies, `swap.go` and `prediction.go` with the two kinds and their terms, the document a signal becomes, and its fingerprint |
| `manifest/` | What a publisher says about itself, its fingerprint, and the `seekervault://feed` reference — which carries no secret, because a feed is a broadcast |
| `environment/` | Which promise a deployment keeps (SEE-97): one type, one pair of words, shared by everything that validates, stamps, publishes or answers with it |
| `ids/` | A lowercase v4 UUID from `crypto/rand`, which is the only identity shape this protocol has, and no dependency to get it |
| `limit/` | The in-memory sliding window behind the optional rolling-hour create cap and the trader UI's own limits (SEE-126) |
| `config/` | The base deployment: `Config` and `Load`, the exported `Reader` (`NewReader`/`Note`/`Text`/`Secret`/`Whole`/`List`/`Problems`), and the address rules `Origin`, `Reachable` and `HeaderSafe` |
| `markets/` | The market records the store persists: `Market`, `Tracked`, `Cycle`, `ErrBusy`, and the `OK`/`Partial`/`Failed` outcomes. It knows no provider |
| `store/` | The only place that speaks SQL: six tables, one writer, two revisions per row that are the whole of the outbox, the market rows, and a schema brought forward rather than refused |
| `gateway/` | The authenticated HTTP/Connect `PublisherService` client, and the classification of every refusal into retry or refuse |
| `publish/` | The durable drainer: it publishes what the store says is pending, manifest first, because a phone holds no feed without one |
| `api/` | The shared business-API frame both demos serve: its authorization, its strict decoding, `Authorship`, and the stage boundary in `boundary_test.go` |
| `publisherctl/` | The operator CLI as a library; each demo ships the binary |
| `publishertest/` | Exported test support: the fake gateway, the shared environment fixture, and the real-gateway process harness (`RunGateway`, `ReadFeed`, `CredentialFrom`, `FreePort`, `WaitFor`) |
| `demotest/` | Exported test support: the whole-demo driver — a real store, the real drainer, the real API and a gateway of our own at the end of it |

### demo-copytrading

| Path | What is in it |
| --- | --- |
| `cmd/copytrading` | The demo: configuration, the store, the swap kind, the manifest at startup, the API, the drainer, an orderly shutdown |
| `cmd/copytrading-admin` | The password-gated HTML UI for the trader (SEE-126), and a client of `/v1` rather than a second writer |
| `cmd/publishctl` | Three lines over `publisher-support/publisherctl` |
| `internal/admin` | That UI's implementation: named bcrypt file, sessions, CSRF, pages, its own rate limits |
| `internal/boundary` | What this module is, as tests over its own source |
| `sdk/` | A small Go client of this demo's request API |
| `Dockerfile`, `compose.yaml`, `compose.public.yaml`, `Caddyfile`, `Caddyfile.public`, `.env.example` | This demo's image and stacks, and only this demo's |

### demo-prediction

| Path | What is in it |
| --- | --- |
| `cmd/prediction` | The demo: the same core, plus the provider client and the reconciler's own goroutine, and an API nobody may write a signal through |
| `cmd/publishctl` | Three lines over `publisher-support/publisherctl` |
| `internal/jupiter` | The prediction provider: two endpoints, its pagination, its error codes, a paced client, and **the only file in this module that names its host**. Seven captured answers under `testdata/` |
| `internal/discovery` | What a filter means, what a cycle does, and the reconciler. No SQL and no HTTP: it is written against interfaces |
| `internal/config` | This demo's own half of a deployment, read through the library's `Reader` so both halves are reported at once |
| `internal/api` | How its discovery joins the shared frame: `Authorship` is `ByDiscovery`, and it adds two endpoints of its own |
| `internal/boundary` | What this module is, as tests over its own source |
| `Dockerfile`, `compose.yaml`, `Caddyfile`, `.env.example` | This demo's image and stack, and only this demo's |

### Three decisions worth knowing about

**The market rows live in `publisher-support/store`, not in the Prediction demo.** It is the one
place where the split could plausibly have gone the other way, and it did not, because a market row
and the signal published for it are written in **one transaction by the same durable engine**. A row
without a signal would be a market nobody hears about; a signal without a row would be a proposal
nothing maintains — and the second is the dangerous one, because nothing would ever withdraw it.
Splitting that transaction across two modules would mean two writers of one file, or a second
schema, or an interface wide enough that it was the store again with a longer name. So the *rows* are
shared and the *meaning* is not: `publisher-support/markets` knows no provider, and which markets are
worth publishing, how they are found and who they are found from is
`demo-prediction/internal/discovery` alone.

**The API frame is shared; the listener, the token and the authorship are each demo's.** Both demos
serve the same endpoints with the same authorization, the same strict decoding, the same refusal
shape and the same idempotency, because a second copy would be a second set of rules about what a
publisher will say. What each demo supplies is its own `config`, its own address and token, the kind
it registers, and one `api.Authorship` value. The CopyTrading demo routes create, update and cancel;
the Prediction demo refuses all three with 403 and adds `GET /v1/discovery` and
`POST /v1/discovery/poll`. That is the entire difference, and each demo's boundary test reads its own
`main` and fails if it stops saying which it is.

**No feed client is generated for any of the three.**
[`buf.gen.publisher-support.yaml`](../../buf.gen.publisher-support.yaml) takes `publish.proto`,
`problem.proto`, `proposal.proto`, `request.proto` and `manifest.proto` and nothing else, into one
output directory shared by both demos — one contract, not two generated copies of one. So a demo
cannot read a feed because no client for one exists anywhere in its module graph: the same argument
that keeps `publish.proto` out of the phone's generation. `publisher-support/api/boundary_test.go`
checks both halves of that — what is absent, and what is present.

## Tests

Per module, from inside it, and they are the whole acceptance for this task apart from the device
run: there is no phone behaviour in a publisher, and nothing here is mocked that the binary does not
also use.

```sh
(cd publisher-support && go test ./...)
(cd demo-copytrading  && go test ./...)
(cd demo-prediction   && go test ./...)
```

Beside them, `pnpm test:integration` runs both **binaries** — with `PREDICTION_PROVIDER_URL` aimed at
this repository's own captured answers, served back over loopback — against the real gateway, two
subscribers and a privacy sweep (SEE-98, [`integration.md`](integration.md)). That is where a
deployment's settings, its API, its CLI and its database stamp are exercised as processes rather than
as packages, and where the two demos are exercised as what they are: two independent sources
publishing to one gateway.

### The shared library's tests

| File | What it holds |
| --- | --- |
| `config/config_test.go` | The settings with no default, every problem at once, the two canonical address forms, a secret as a file, and that a credential must be one word an HTTP header can carry |
| `signals/signals_test.go` | The expiry rules, the note's bounds, the terms' bounds, that the document lists its terms in key order, and what the fingerprint is and is not |
| `signals/swap_test.go` | Every way a swap's terms can be wrong with the code a caller is told, that direction is the pair and there is no side field, canonical numbers, absent-is-absent, a label counted the way the phone counts it, and base58 |
| `signals/prediction_test.go` | Every way a market's terms can be wrong, that no term can carry a side, the provider's floor being published rather than assumed, both deposit mints with their own decimals, and the identifier rule |
| `signals/contract_test.go` | The bounds are the **gateway's own** and the prediction rules are the **phone's own**, both read out of their source rather than trusted |
| `manifest/manifest_test.go` | A manifest is always a gateway feed, names one environment, carries the kind's plugin requirement, and a reference that carries no secret. An environment that came from anywhere but the configuration is published as unspecified rather than as production (SEE-97) |
| `environment/environment_test.go` | Only the two words are an environment, including the ways an operator nearly gets it right; one from nowhere has no wire value at all; and the two words are **the phone's own**, read out of `ActionPlugin.kt` rather than trusted |
| `ids/ids_test.go` | The shape, the version and variant bits, and that two are not the same |
| `limit/limit_test.go` | Sliding window, unlimited zero, independent keys, undo freeing a slot |
| `store/store_test.go` | The live schema has no column for a subscriber; the file remembers whose it is and which environment; revisions, idempotency, a withdrawal being final, a late answer confirming nothing, deferral and refusal, and everything surviving a restart |
| `store/markets_test.go` | A market and its signal as one write, the same market twice being one proposal, the `UNIQUE` proposal, a re-opened market at the next generation, the cycle's number surviving a restart, and **a version-1 database brought forward with its signals intact** |
| `gateway/gateway_test.go` | The credential goes in a header and nowhere else, the same document again is unchanged, a withdrawal of nothing is not an error, **every refusal classified** with the reason, and that a publication is never redirected |
| `publish/drain_test.go` | The manifest first, a restart republishing identical bytes, a gateway that is down, a refusal that stops, a withdrawal before anything was published, and the drainer running on a wake-up |
| `publish/feedgateway_test.go` | The **real gateway**, as a separate process. Opt-in; see below |
| `api/api_test.go` | A signal published end to end, a retried create, a reused key, a create with no key, the token on every route, every malformed statement, a gateway that is down, a refusal and its retry, an update that changes nothing, a withdrawal being final, 404s, 405s, strict decoding, and the optional create cap (SEE-126) |
| `api/boundary_test.go` | The stage boundary over the library: no feed client compiled, one package calling the gateway, no Firebase or broker or MCP, no service address compiled in, no credential in a log line, nothing about a subscriber accepted in a body or a term, no command importing the test support, and a caller unable to name an operation or a plugin |
| `publisherctl/publisherctl_test.go` | What each command sends, the key it mints and prints, terms from a file, what it refuses to send at all, and how it reports a refusal and a pending publication |

### The CopyTrading demo's tests

| File | What it holds |
| --- | --- |
| `internal/admin/*_test.go` | Named bcrypt file, mtime revoke, session cookie flags, CSRF origin, `/v1` client, token never in HTML, login and create rate limits |
| `cmd/copytrading-admin/main_test.go` | `hash` prints a `name:bcrypt` line |
| `sdk/client_test.go` | That the small Go client posts to the one request endpoint, with the idempotency key and the bearer token in the headers the API reads them from |
| `internal/boundary/boundary_test.go` | What *this module* is: it does not import the other demo, needs no direct server, compiles in no provider and no discovery, delivers nothing itself, names no service address, its CLI command is only a `main`, its own `main` says its signals are its callers', and no subscriber is known here |

### The Prediction demo's tests

| File | What it holds |
| --- | --- |
| `internal/jupiter/jupiter_test.go` | A real listing decoded as the provider sent it, a walk that ends on an empty page, a market read directly, what makes a market tradeable, **every provider failure classified** with whether it is temporary, the pacing, and that the key is in one header and no message. Its fixtures are seven real answers |
| `internal/discovery/discovery_test.go` | Every reason a market is not a candidate, the expiry being the market's own close time, what the note says and that a provider's text cannot spoil it, the derived key, and the publishing order |
| `internal/discovery/reconcile_test.go` | The reconciler over the **real store**: a real listing becoming proposals, a second cycle publishing nothing, a restart publishing nothing, a postponed market moving one revision, **absence not being closure**, every way the source ends a market, an outage ending none, the ceiling, the round robin, and two cycles not running at once |
| `internal/discovery/feedgateway_test.go` | The **real gateway**, for a *discovered* market. Opt-in; see below |
| `internal/config/config_test.go` | A deployment that starts on its defaults, every filter read, every way one is refused, **both halves reported at once**, the provider's key as a file, and no refusal quoting it |
| `internal/api/discovery_test.go` | Nobody may write a prediction signal, a poll running a cycle and publishing it, what `/v1/discovery` says, that the status says it is not writable, a poll refused while one runs, and **the provider's key in no answer and no log line** |
| `internal/boundary/boundary_test.go` | What *this module* is: it does not import the other demo, needs no direct server, builds the provider only in its own `main`, delivers nothing itself, names no service address **except the provider's, in one file**, its own `main` says its signals are its own discovery's, and no subscriber is known here |

Note that the boundary tests are now three, not one, and that is deliberate. The rules every
public-feed publisher obeys are stated once over the library, in
[`publisher-support/api/boundary_test.go`](../../publisher-support/api/boundary_test.go). The rules
about *being an independent demonstration* are stated per module, in
[`demo-copytrading/internal/boundary`](../../demo-copytrading/internal/boundary) and
[`demo-prediction/internal/boundary`](../../demo-prediction/internal/boundary), each walking its own
module's shipped source — because "this builds and runs without the other" is a claim about one
module, and a test that walked both at once could not make it.

### Against the real gateway

The tests that need a binary these modules do not build. They are what prove that a publisher and the
gateway agree rather than assuming it — the manifest's shape, the channel, the origin, and what a
republication is answered with — and they are also the automated half of "two phones see the same
proposal":

```sh
(cd feed-gateway && go build -o /tmp/feed-gateway ./cmd/feed-gateway \
                 && go build -o /tmp/feed-gatewayctl ./cmd/feed-gatewayctl)

(cd publisher-support && SEEKERVAULT_FEED_GATEWAY=/tmp/feed-gateway go test ./publish/ -run Gateway -v)
(cd demo-prediction   && SEEKERVAULT_FEED_GATEWAY=/tmp/feed-gateway go test ./internal/discovery/ -run Gateway -v)
```

Each registers a publisher with the real `feed-gatewayctl`, starts the real gateway on isolated
loopback ports, publishes a manifest and a signal, **reads the feed back twice with two independent
clients and compares the bytes**, republishes the identical document and requires `UNCHANGED`, then
withdraws and reads the withdrawal. The process harness they share is
[`publisher-support/publishertest`](../../publisher-support/publishertest), exported for exactly this
reason: both modules need the same gateway, and a difference between two tests' gateways would be a
difference nobody meant. `pnpm check:publisher-support` and `pnpm check:prediction` build the gateway
and set the variable for you.

There are two of these, one per demo, and the second is not the first with a word changed: a
prediction manifest requires the other plugin, and its proposal is built the way a cycle builds one —
through the store's market rows — so what it proves is that a *discovered* market becomes a document
the real gateway accepts, and that ending the market leaves a cancelled proposal a phone can still
read.

What is left after them is the device run: two real phones on one feed, each choosing its own
([`docs/testing/stage-7-1.md`](../testing/stage-7-1.md)).

### Against the real provider

The other opt-in test, and the one that can notice the provider changing under the Prediction demo —
a beta API by its own documentation:

```sh
cd demo-prediction && SEEKERVAULT_JUPITER=1 go test ./internal/jupiter/ -run Live -v
```

It reads one real page of the listing and then one real market from it, and checks that the fields
discovery depends on are all still there — an identifier, a category, a title, the markets inside the
event, a status, a close time — rather than asserting anything about a particular market. It is not
in `pnpm check:prediction`, because a check that needs the internet is not a check.

Everything else about the provider runs against seven answers it really gave, committed under
`demo-prediction/internal/jupiter/testdata` with the request that produced each. To re-capture them:

```sh
node scripts/capture-jupiter.mjs --events
```

## Deployment

Each demo carries its own deployment assets in its own directory — the ones SAW-035 established and
`feed-gateway/` follows — and each demo's README is the deployment guide for it, end to end:
identity and credential, the image, the stack, every setting, the persistent volume, health, the
first publication, logs, common errors, backup, upgrade, rollback, and copying the demo out of the
repository.

- **[`demo-copytrading/README.md`](../../demo-copytrading/README.md)** — a `Dockerfile`, a base
  `compose.yaml` with a `Caddyfile` on loopback, a `compose.public.yaml` overlay with a
  `Caddyfile.public` that terminates TLS for a domain, and an `.env.example`. Compose project
  `seeker-publisher`, volume `publisher-data`.
- **[`demo-prediction/README.md`](../../demo-prediction/README.md)** — a `Dockerfile`, a
  `compose.yaml` with a `Caddyfile` on loopback, and an `.env.example`. No public overlay, because
  its signals are written by its own discovery and its API is something an operator reads. Compose
  project `seeker-prediction`, volume `prediction-data`.

**Two images, not one.** Each demo's Dockerfile builds only that demo's binaries, so no provider
client, no discovery and no prediction binary exists anywhere in the CopyTrading image, and no
signal-writing API, no trader UI and no CopyTrading binary exists anywhere in the Prediction one.
Both builds take the repository root as their context, because each module's `go.mod` replaces the
shared library with `../publisher-support` and the build needs that directory too; neither copies the
other demo in. The project names, the volume names and the database file names are unchanged from the
single combined stack these were split out of, so an existing installation upgrades into the same
data rather than a fresh identity.

Two things differ from the gateway's assets, and each for a reason:

- **The images carry a CA bundle from the start.** These services call out in the ordinary case — the
  gateway is somebody else's HTTPS endpoint, and so is the prediction provider — so a publisher that
  could not verify them would be publishing to, and reading from, whatever answered. The gateway's
  own image shipped none until it gained an outbound call of its own.
- **The CopyTrading public overlay carries a warning rather than a recommendation.** What it puts on
  the internet is a write API whose token is the whole grant to publish as this server, which is a
  different thing from the gateway's public read port, where everything served is a document somebody
  published for everyone. A deployment whose signals are written locally should stay on the base file
  and be reached over a tunnel.

`docker compose config` validates every compose file and `caddy validate` every Caddyfile without a
daemon — which is how they were checked here
([`docs/changelog/2026-09-17.md`](../changelog/2026-09-17.md)).
