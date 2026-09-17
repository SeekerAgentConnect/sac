# broadcast

The shared broadcast gateway (SEE-90): the Go service a developer's publisher publishes to, and
every subscribed phone reads from. One publication, read by everyone; the publisher keeps no
connection to any phone, and no phone ever contacts the publisher.

**This is not [`gateway/`](../gateway).** That directory is one owner's private deployment — Caddy in
front of their own sidecar (SAW-035) — run by the owner, serving one paired phone. This is a service
of its own, run by whoever hosts the broadcast, serving everyone. The word "gateway" in the docs
means this one when it is next to "broadcast", "shared" or "feed", and that one when it is next to
"reverse proxy" or "deployment".

| File | What it is |
| --- | --- |
| [`cmd/broadcast`](cmd/broadcast) | The server: two listeners, the fan-out drainer, the retention sweep |
| [`cmd/broadcastctl`](cmd/broadcastctl) | The operator's tool: register a publisher, rotate, revoke, forget |
| [`internal/rules`](internal/rules) | What the gateway accepts, as pure functions — the phone's own rules, on this side |
| [`internal/store`](internal/store) | The only place that speaks SQL: publications, publisher configuration, the outbox |
| [`internal/gateway`](internal/gateway) | The two APIs, their interceptors, and the boundary tests |
| [`internal/dispatch`](internal/dispatch) | Persist first, fan out second: the outbox drainer and the seam SEE-91 fills |
| [`compose.yaml`](compose.yaml) | The stack. The gateway and the proxy start; `ctl` sits behind a profile and does not |
| [`compose.public.yaml`](compose.public.yaml) | The internet-facing overlay: HTTPS on your own domain, ports 80 and 443 |
| [`Caddyfile`](Caddyfile) / [`Caddyfile.public`](Caddyfile.public) | The proxy's local and public configurations |
| [`.env.example`](.env.example) | The deployment's settings. Copy to `.env` here, which git ignores |

## Running it

Locally:

```sh
cp .env.example .env
docker compose up -d --build
docker compose run --rm ctl register --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
```

On the internet, once `BROADCAST_DOMAIN` resolves to the host and `ACME_EMAIL` is set:

```sh
docker compose -f compose.yaml -f compose.public.yaml up -d --build
```

Without Docker — which is how the checks in this repository run it:

```sh
go build ./... && go test ./...
BROADCAST_PUBLIC_URL=http://127.0.0.1:8090 BROADCAST_DATABASE_PATH=./broadcast.db go run ./cmd/broadcast
```

`pnpm check:broadcast` from the repository root runs the formatting check, `go vet` and the tests.

## What it holds, and what it cannot

It holds shared publications — a publisher's manifest and the proposals it currently has open — and
the publisher configuration that says who may publish to which channel. That is all it has tables
for, and a boundary test reads the schema and fails if a column for an address, a chosen amount, a
decision or a result appears.

The reason is the stage's own: a proposal is common and a decision about it is not (SEE-89). Each
owner's parameters, their approval and their execution record stay on the device that made them, so
there is no user account here, no execution history, and nothing that could be turned into one.
[`docs/wiki/broadcast-gateway.md`](../docs/wiki/broadcast-gateway.md) is the architecture page and
[`docs/protocol.md`](../docs/protocol.md#the-broadcast-gateway-see-90) is the contract.
