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
| [`internal/dispatch`](internal/dispatch) | Persist first, fan out second: the outbox drainer, and the event envelope every subscriber reads |
| [`internal/stream`](internal/stream) | The broker: publishing an event to it, and minting the ticket a listener connects with (SEE-91) |
| [`internal/relay`](internal/relay) | The push relay: one content-free hint per changed feed, and the grant it is sent with (SEE-92) |
| [`centrifugo.yaml`](centrifugo.yaml) | The broker's configuration — one transport, one namespace, and nothing a listener may do but listen |
| [`compose.yaml`](compose.yaml) | The stack: the gateway, the broker, its Redis and the proxy. `ctl` sits behind a profile and does not start |
| [`compose.public.yaml`](compose.public.yaml) | The internet-facing overlay: HTTPS on your own domain, ports 80 and 443 |
| [`compose.push.yaml`](compose.push.yaml) | The push overlay: the Firebase credential, mounted into the gateway and nothing else (SEE-92) |
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

`.env` needs two secrets before the first start — the key the gateway publishes to the broker with,
and the key a listener's ticket is signed with (`openssl rand -base64 32` for each). **Leave
`BROADCAST_STREAM_URL` empty to run without a stream**: the gateway then holds the documents and
answers every read, and a phone that asks to listen is told there is none.

### With push hints

The stream reaches a phone that is being looked at. A phone in a pocket is reached by one
content-free message to the feed's public Firebase topic, and the overlay that does it is separate
because turning it on means mounting a credential:

```sh
# in .env: BROADCAST_PUSH_CREDENTIALS_FILE=/etc/seeker/service-account.json
#          BROADCAST_PUSH_ENVIRONMENT=production
docker compose -f compose.yaml -f compose.push.yaml up -d --build
```

The credential is a Firebase service account for the project whose app the phones are running
([`docs/guides/firebase.md`](../docs/guides/firebase.md)). It is mounted read-only into the gateway
and into nothing else: the broker never sees it, `ctl` never sees it, and **a publisher never sees
it** — which is the whole point of relaying here rather than letting each publisher send its own.

A hint carries two constant fields and no document. Which feed changed is the topic it arrived on,
and the phone reads the feed from this gateway before it shows anything. Leave the overlay out and
the gateway relays nothing, answers every read, streams to whoever is listening, and tells a phone
that asks where hints arrive that none are sent here.

### More than one broker node

Redis is what makes two broker nodes one broker: a publication accepted by either reaches the
clients attached to both. Add a second service with the same configuration and give the proxy both
upstreams:

```
reverse_proxy h2c://centrifugo:11000 h2c://centrifugo-b:11000
```

Nothing else changes, and nothing about a phone does. `feeds/CentrifugoStreamIntegrationTest` drives
exactly this — two real nodes and a real Redis — on a machine where the binaries are available.

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
