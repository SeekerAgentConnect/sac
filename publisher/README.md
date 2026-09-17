# publisher

The Go publisher templates (SEE-95): a developer's — or a trader's — own server. It publishes one
signal to the [shared broadcast gateway](../broadcast), every phone subscribed to its channel reads
the same document, and each owner then chooses their own amount on their own device and approves it
there.

**Nothing comes back.** This server never learns who is subscribed, what anyone chose, whether they
went ahead, or what came of it. There is no table for any of that, no field in its API that would
accept it, and no endpoint that would answer about it.

| File | What it is |
| --- | --- |
| [`cmd/copytrading`](cmd/copytrading) | The CopyTrading template: trader-authored spot-swap signals, served by the bundled `jupiter.swap` plugin |
| [`cmd/publishctl`](cmd/publishctl) | The operator's tool, and a worked example of the API: every command is one HTTP call |
| [`internal/signals`](internal/signals) | What a signal is, as pure data — and the one seam a template supplies: `Kind` |
| [`internal/manifest`](internal/manifest) | What this server says about itself, and the `seekervault://feed` reference a phone adds it from |
| [`internal/store`](internal/store) | The only place that speaks SQL: the signals, and what the gateway has confirmed about each |
| [`internal/publish`](internal/publish) | The one thing that reaches out of the process: the gateway client, the retry judgment, the drainer |
| [`internal/api`](internal/api) | The JSON API a person, a script or a strategy engine calls, and the boundary tests |
| [`compose.yaml`](compose.yaml) | The stack: the template and a proxy on loopback. `ctl` sits behind a profile and does not start |
| [`compose.public.yaml`](compose.public.yaml) | The internet-facing overlay — read the warning in it first |
| [`.env.example`](.env.example) | The deployment's settings. Copy to `.env` here, which git ignores |

## Running it

Two things have to exist first: a gateway to publish to, and a credential from whoever operates it.

```sh
# on the gateway's host, once per publisher
docker compose run --rm ctl register --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
  --label "copy trading"
```

That prints a credential once. Then, here:

```sh
cp .env.example .env     # PUBLISHER_SERVER_ID, PUBLISHER_GATEWAY_URL, PUBLISHER_ENVIRONMENT,
                         # BROADCAST_CREDENTIAL, and a PUBLISHER_API_TOKEN of your own
docker compose up -d --build
docker compose run --rm ctl status
```

Without Docker — which is how the checks in this repository run it:

```sh
go build ./... && go test ./...

PUBLISHER_SERVER_ID=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 \
PUBLISHER_ENVIRONMENT=sandbox \
PUBLISHER_DATABASE_PATH=./publisher.db \
BROADCAST_CREDENTIAL=<the credential the gateway printed> \
PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
go run ./cmd/copytrading
```

It prints one line on stdout, and that line is the whole of what a subscriber needs:

```
seekervault://feed?v=1&gateway=http%3A%2F%2F127.0.0.1%3A8090&server=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
```

It carries no secret — a feed is a broadcast, the phone subscribes through the gateway, and this
server is never contacted — so it can go in a README, a QR code or a public post, and holding one
grants nothing.

`pnpm check:publisher` from the repository root runs the formatting check, `go vet` and the tests.

## Publishing a signal

Through the CLI:

```sh
export PUBLISHER_API_TOKEN=…
publishctl create --in 2h --note "trimming SOL into USDC on the bounce" \
  --term input_mint=So11111111111111111111111111111111111111112 \
  --term input_decimals=9 \
  --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term output_decimals=6 \
  --term max_slippage_bps=50
```

or through the API, which is the same thing — the CLI has no privileged path of its own:

```sh
curl -sS http://127.0.0.1:8092/v1/signals \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: desk-1-sol-usdc-2026-09-17T19:00Z' \
  -d '{"expires_at":"2026-09-17T21:00:00Z","note":"trimming SOL into USDC",
       "terms":{"input_mint":"So11111111111111111111111111111111111111112",
                "input_decimals":"9",
                "output_mint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                "output_decimals":"6","max_slippage_bps":"50"}}'
```

[`docs/integrations/signal-api.md`](../docs/integrations/signal-api.md) is the API contract for a
strategy system, and [`docs/wiki/copytrading-template.md`](../docs/wiki/copytrading-template.md) is
why the template is shaped like this — including a complete worked example of where publication ends
and each owner's own execution begins.

## What a signal says, and what it cannot

A swap signal names the asset spent, the asset received, the decimals for display, and the most
slippage the publisher will have its signal acted on with. It may bound the amount and it may give
labels. It may carry a note, which is the publisher's own prose and is believed by nothing.

**The amount is not in it.** Neither is a wallet, a slippage somebody settled on, a decision or a
result: those are each owner's, they are chosen on the phone that will sign, and they stay there.
The API refuses a field it does not have rather than dropping it, so a caller that believes
otherwise is told, and `internal/api/boundary_test.go` tries every one of those words.

## Two things worth knowing about the API

- **`Idempotency-Key` is required on a create.** A retried create is the same signal; the same key
  with a different statement is a conflict rather than a second signal. Nothing else needs a key:
  an update carries the whole statement and moves the revision only if the content actually
  changed, and a withdrawal and a retry are idempotent by what they are.
- **The token is the whole grant.** There is one, it is checked in constant time, and it is
  required on everything but `/healthz`. That is why the API binds loopback by default and why the
  public overlay carries a warning: TLS keeps the token off the wire, and nothing makes holding one
  safer.

## What it holds, and what it cannot

Its own signals, and what the gateway has confirmed about each of them. A boundary test reads the
live schema and fails if a column for an address, a chosen amount, a decision or a result appears —
and another reads this module's source and fails if Firebase, a broker, a feed client or MCP turns
up in it, because delivery to phones is the gateway's and a publisher submits one document and
stops.
