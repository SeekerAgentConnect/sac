# CopyTrading demo

An independent public-feed publisher: a developer's — or a trader's —
own server. A trader posts one signal to its API, it publishes one feed request to the
[shared feed gateway](../../services/gateway), every phone subscribed to its channel reads the same
document, and each owner then chooses their own amount on their own device and approves it there.

**It is one of two demonstrations, and it is independent.** It builds, tests, images and runs
without [`examples/demo-prediction/`](../demo-prediction), without the Direct Server SDK, and without the
MCP server. The one thing it needs that it does not contain is a reachable feed gateway. The two
demos share a source library — [`packages/publisher-support/`](../../packages/publisher-support), which has no command,
no image and no deployment of its own — and nothing else: not a database, not a credential, not a
container, not a lifecycle. Restarting or cancelling here does nothing to the other demo's source.

**Nothing comes back.** This server never learns who is subscribed, what anyone chose, whether they
went ahead, or what came of it. There is no table for any of that, no field in its API that would
accept it, and no endpoint that would answer about it. Devices subscribe to the gateway, never to
this process.

| File | What it is |
| --- | --- |
| [`cmd/copytrading`](cmd/copytrading) | The publisher: trader-authored spot-swap signals, served by the bundled `jupiter.swap` plugin |
| [`cmd/copytrading-admin`](cmd/copytrading-admin) | Password-gated HTML UI for the trader. A client of `/v1`, not a second writer |
| [`cmd/publishctl`](cmd/publishctl) | The operator's tool: a three-line main over the shared client, and a worked example of the API |
| [`internal/admin`](internal/admin) | That UI's implementation: sessions, passwords, pages |
| [`internal/boundary`](internal/boundary) | What this demo is, as tests over its own source |
| [`sdk`](sdk) | A small Go client of this demo's request API |
| [`Dockerfile`](Dockerfile) | This demo's image, and only this demo's |
| [`.env.example`](.env.example) | Every setting, with its default and what it means. Copy to `.env` here, which git ignores |

Everything durable — the signals, their revisions, their idempotency keys and the outbox that gets
them to the gateway — lives in [`packages/publisher-support/store`](../../packages/publisher-support/store); the client
that reaches the gateway is [`packages/publisher-support/gateway`](../../packages/publisher-support/gateway); the API
frame both demos serve is [`packages/publisher-support/api`](../../packages/publisher-support/api). This module supplies
the kind it registers, who writes its signals, its own configuration and its own deployment.

## 1. What it needs

| Requirement | Version | Why |
| --- | --- | --- |
| Go | 1.27.1, as [`go.mod`](go.mod) requires | Building from source and running the tests |
| Docker | any current release | Building and running the image |
| A reachable feed gateway | [`services/gateway/`](../../services/gateway) | Where publications go and where phones read |
| A publisher credential | issued by that gateway's operator | Authenticates this source to it |

Nothing else. No broker, no Redis, no Firebase credential, no database server, no provider account:
this demo submits one document per thing it has to say and stops. Streaming and push delivery to
phones are the gateway's.

## 2. Get a publisher identity and a credential

Registration is the gateway operator's act, not an RPC, and it happens once per publisher. Choose a
lowercase UUID for this source and ask whoever runs the gateway to register it — or run it yourself
if you run both:

```sh
# on the gateway's host, against the gateway's own database
feed-gatewayctl register \
  --database <gateway database> \
  --server <server-uuid> \
  --label "copy trading"
```

That prints one bearer credential, once, and the gateway stores only its SHA-256 hash. It is this
source's own: the Prediction demo is registered separately, under its own UUID, with its own
credential. See [`services/gateway/README.md`](../../services/gateway/README.md#register-a-publisher) for
rotation and revocation.

## 3. Build and run from source

```sh
cd examples/demo-signals
go build ./...
go test ./...

PUBLISHER_SERVER_ID=<server-uuid> \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 \
PUBLISHER_ENVIRONMENT=sandbox \
PUBLISHER_DATABASE_PATH=./copytrading.db \
BROADCAST_CREDENTIAL=<the credential the gateway printed> \
PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
go run ./cmd/copytrading
```

A repository checkout resolves the shared library through the `replace` line at the bottom of
[`go.mod`](go.mod). A copy taken out of the repository has to bring
[`packages/publisher-support/`](../../packages/publisher-support) with it, or replace that line with an explicit module
revision — see [§11](#11-copying-this-demo-out-of-the-repository).

It prints one line on stdout, and that line is the whole of what a subscriber needs:

```
seekervault://feed?v=1&gateway=http%3A%2F%2F127.0.0.1%3A8090&server=<server-uuid>
```

It carries no secret — a feed is a broadcast, the phone subscribes through the gateway, and this
server is never contacted — so it can go in a README, a QR code or a public post, and holding one
grants nothing.

## 4. Build and run the image

The build context is the repository root, because this module's `go.mod` replaces the shared library
with `../publisher-support` and the build needs that directory too. It needs nothing else:
`examples/demo-prediction/` is never copied in, so no provider client, no discovery and no prediction binary
exists anywhere in this image.

```sh
cd <repository root>
docker build -f examples/demo-signals/Dockerfile -t demo-copytrading:local .

# or, for a host of a different architecture
docker buildx build --platform linux/amd64 -f examples/demo-signals/Dockerfile \
  -t demo-copytrading:local --load .
```

Run it with the settings from [`.env.example`](.env.example) filled in. The container listens on
its network address and the port is published on the host's loopback address only:

```sh
docker volume create copytrading-data
docker run -d --name copytrading \
  --env-file examples/demo-signals/.env \
  -e PUBLISHER_API_ADDRESS=0.0.0.0:8092 \
  -v copytrading-data:/data \
  -p 127.0.0.1:8092:8092 \
  demo-copytrading:local
```

The image also carries `/publishctl` and `/copytrading-admin`; see [§8](#8-health-and-a-first-request)
for the operator client.

## 5. Configuration

Application settings are documented in [`.env.example`](.env.example). In summary:

| Variable | Required | Default | What it is |
| --- | --- | --- | --- |
| `PUBLISHER_SERVER_ID` | yes | — | This source's lowercase UUID, as registered with the gateway |
| `PUBLISHER_GATEWAY_URL` | yes | — | The gateway's own public origin, character for character |
| `PUBLISHER_ENVIRONMENT` | yes | — | `production` or `sandbox`; stamped into the database on first open |
| `BROADCAST_CREDENTIAL` | yes | — | The credential the gateway issued this source |
| `PUBLISHER_API_TOKEN` | yes | — | The grant to call this demo's own API; at least 32 characters |
| `PUBLISHER_PUBLISH_URL` | no | the gateway URL | Where publications are *sent*, when that differs from where phones read |
| `PUBLISHER_DATABASE_PATH` | yes from source | image: `/data/publisher.db` | This demo's SQLite file |
| `PUBLISHER_API_ADDRESS` | no | `127.0.0.1:8092` | Where its API listens; in a container, set `0.0.0.0:8092` |
| `PUBLISHER_DISPLAY_NAME` | no | — | A default label for a connection; never verified |
| `PUBLISHER_PUBLISH_TIMEOUT_SECONDS` | no | `10` | How long one publication may take before it is retried |
| `PUBLISHER_CREATE_LIMIT` | no | unlimited | New signals accepted per rolling hour |

`BROADCAST_CREDENTIAL` and `PUBLISHER_API_TOKEN` each accept a `…_FILE` form instead, naming a file
to read the secret from. Set one or the other, never both.

## 6. Internal addresses versus what is advertised

Three addresses are easy to confuse, and two of them are not interchangeable:

- **`PUBLISHER_GATEWAY_URL`** is what phones read and what the manifest names. The gateway checks it
  against its own `BROADCAST_PUBLIC_URL` character for character, and the phone compares it with the
  feed reference the feed was added from.
- **`PUBLISHER_PUBLISH_URL`** is where a publication is *sent*. Leave it empty when the gateway's
  public feed ingress serves both APIs on one origin. Point it at a private address — a tunnel, a
  VPN, or the gateway's publisher port — when an operator keeps publishing off the internet. Getting
  this one wrong is the mistake with no error on the gateway's side: a read-only origin answers 404
  to a publication, because that listener has no handler that could write.
- **`PUBLISHER_API_ADDRESS`** is this demo's *own* business API, which is nobody's but its operator's
  and whoever writes its signals. Keep it on `127.0.0.1:8092`, or publish a container's port on
  the host's loopback address only.

Phones never reach `PUBLISHER_API_ADDRESS`. They do not know this process exists.

## 7. Persistent data

One SQLite file, named by `PUBLISHER_DATABASE_PATH`, owned by this process alone: the signals, their
revisions, their idempotency keys, the manifest revision, and the outbox that says what has and has
not reached the gateway. A signal is stored before it is published, so this file is what makes a
retry after a crash send the same document rather than a second one.

In the image it lives on the volume mounted at `/data`, owned by uid/gid 10001:10001. The rest of
the image is read-only. Never merge two non-empty SQLite files or point two processes at one. This
data is never shared with Prediction.

## 8. Health and a first request

```sh
curl --fail http://127.0.0.1:8092/healthz
# {"status":"ok"}

cd examples/demo-signals
export PUBLISHER_API_TOKEN=…
go run ./cmd/publishctl status
go run ./cmd/publishctl reference     # the seekervault://feed line to pass on
```

`publishctl` reaches `http://127.0.0.1:8092` unless `--url` or `PUBLISHER_API_URL` says otherwise.
In the image it is `/publishctl`.

`/healthz` is the one route with no credential, and it therefore says nothing else: this demo's
settings, its pending count and its environment are all behind the token.

Publish, update and withdraw one request — the reproducible end-to-end run:

```sh
# create
go run ./cmd/publishctl create --in 2h --note "trimming SOL into USDC on the bounce" \
  --key desk-1-sol-usdc-2026-09-17T19:00Z \
  --term input_mint=So11111111111111111111111111111111111111112 \
  --term input_decimals=9 \
  --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term output_decimals=6 \
  --term max_slippage_bps=50

# update: the whole statement, not the parts that changed
go run ./cmd/publishctl update <proposal id> --in 4h --note "wider window" \
  --term input_mint=So11111111111111111111111111111111111111112 \
  --term input_decimals=9 \
  --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term output_decimals=6 \
  --term max_slippage_bps=50

# withdraw
go run ./cmd/publishctl cancel <proposal id>
go run ./cmd/publishctl show <proposal id>
```

The same calls over plain HTTP, which is all the CLI does — it has no privileged path of its own:

```sh
curl -sS http://127.0.0.1:8092/v1/requests \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: desk-1-sol-usdc-2026-09-17T19:00Z' \
  -d '{"expires_at":"2026-09-17T21:00:00Z","note":"trimming SOL into USDC",
       "terms":{"input_mint":"So11111111111111111111111111111111111111112",
                "input_decimals":"9",
                "output_mint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                "output_decimals":"6","max_slippage_bps":"50"}}'
```

**`Idempotency-Key` is required on a create.** A retried create with the same key is the same
signal; the same key with a different statement is a conflict rather than a second signal. Nothing
else needs a key: an update carries the whole statement and moves the revision only if the content
actually changed, and a withdrawal and a retry are idempotent by what they are.

The old `/v1/signals` routes remain compatibility aliases over the same store and the same
publisher. New code uses `/v1/requests`.

### Adding the feed in SAC, and verifying a restart

1. `go run ./cmd/publishctl reference` prints one `seekervault://feed?…` line.
2. In the phone app, add a public feed and paste that line (or scan it as a QR code). No credential
   is involved and the phone never contacts this process.
3. Publish a request as above; the phone's feed shows it.
4. Restart the publisher, then run `go run ./cmd/publishctl status`. The identity, the
   revisions and anything pending are the same: they are in the file, not in the process. Publishing
   the identical document again is answered `unchanged` by the gateway, so a restart notifies nobody.

The complete request/answer/status-code contract for a strategy system is
[`docs/integrations/signal-api.md`](../../docs/integrations/signal-api.md);
[`docs/wiki/copytrading-template.md`](../../docs/wiki/copytrading-template.md) is why this demo is
shaped like this, including a worked example of where publication ends and each owner's own
execution begins.

### The trader UI

`cmd/copytrading-admin` is a password-gated HTML client of the same `/v1` API. It is a
client, not a second writer: it holds the API token and calls the same endpoints, so validation, the
identity, the revision and the publication cannot be gone around through it. It is optional, it is
started only where you run it, and it has its own passwords file and session secret.
It uses the feed gateway's admin look, from its own copies of the stylesheet, script and fonts in
`internal/admin/assets/` (the templates are in `internal/admin/templates/`), all embedded in the
binary.

## 9. Optional operator access

The API is plain HTTP on the host's loopback address, which is right for a demo whose signals
are written on the same machine — by a person, a cron job or a strategy process beside it — and for
a server reached over a VPN or an SSH tunnel.

There is no bundled public ingress for the demo. Reach it over an SSH tunnel or private network, or
operate a separate authenticated TLS ingress when remote strategy software genuinely needs it. The
token is the whole grant to publish as this source, so exposing the API is a deliberate operator
decision rather than a portable default. Keep the optional admin UI on loopback too; it never
belongs on the public feed ingress.

There is no FCM, OAuth or provider configuration here. Push is the gateway's
([`docs/guides/firebase.md`](../../docs/guides/firebase.md)), OAuth belongs to the direct server, and
this demo has no provider at all.

## 10. Logs, common errors, backup, upgrade and rollback

```sh
docker logs --tail=100 copytrading     # when run from the image
go run ./cmd/publishctl list
curl --fail http://127.0.0.1:8092/healthz
```

Logs name what happened and never print a credential or a token — there is a test that makes a
series of calls, including refused ones, against a publisher whose log is a buffer, and fails if
either secret appears in it.

| Symptom | Usually |
| --- | --- |
| Refuses to start, listing variables | A missing or malformed setting; every problem is reported at once so a first start is fixed in one pass |
| `other_gateway` on the manifest | `PUBLISHER_GATEWAY_URL` is not the gateway's own `BROADCAST_PUBLIC_URL`, character for character |
| 404 on every publication | `PUBLISHER_PUBLISH_URL` points at the *read* origin; point it at the publisher API |
| 401 from the gateway | The credential was revoked or rotated, or belongs to a different `PUBLISHER_SERVER_ID` |
| 403 from this demo's API | A wrong `PUBLISHER_API_TOKEN`, or no `Authorization` header |
| Signals stay `pending` | The gateway is unreachable; they stay in the file and drain when it returns |

A consistent backup means stopping the writer, archiving the volume, and starting it again:

```sh
mkdir -p backups
docker stop copytrading
docker run --rm \
  -v copytrading-data:/from:ro \
  -v "$PWD/backups:/to" alpine:3.22 \
  tar -C /from -czf /to/copytrading-data.tgz .
docker start copytrading
```

To upgrade, take that backup, then replace the container with one from the new image on the same
volume. To roll back, stop it, restore the archive into the same empty volume, and start the
previous image. Never point an older binary at a newer database file.

Restarting, upgrading or rolling back this demo does nothing to the Prediction demo or to the
gateway: three processes, three lifecycles, three sets of durable state.

## 11. Copying this demo out of the repository

Take two directories, not one:

```sh
cp -R examples/demo-signals ~/my-signals
cp -R packages/publisher-support ~/publisher-support
cd ~/my-signals
go mod edit -replace github.com/BrRenat/SeekerAgentWallet/publisher-support=../publisher-support
```

In the repository the `replace` line at the bottom of `go.mod` points at
`../../packages/publisher-support`; the `go mod edit` above points the copy at its sibling instead.
Alternatively, edit that line to an explicit, compatible module revision if you would rather depend
on a published one. Nothing else in the repository is needed: `go build ./...` and
`go test ./...` run from the copy, and the Docker build needs only those two directories as its
context.

## What it holds, and what it cannot

Its own signals and what the gateway has confirmed about each of them. A boundary test reads the
live schema and fails if a column for an address, a chosen amount, a decision or a result appears —
and [`internal/boundary`](internal/boundary) reads this module's source and fails if Firebase, a
broker, a feed client, MCP, the Direct SDK or the other demo turns up in it.

A swap signal names the asset spent, the asset received, the decimals for display, and the most
slippage the publisher will have its signal acted on with. It may bound the amount and it may give
labels. It may carry a note, which is the publisher's own prose and is believed by nothing.

**The amount is not in it.** Neither is a wallet, a slippage somebody settled on, a decision or a
result: those are each owner's, they are chosen on the phone that will sign, and they stay there.
The API refuses a field it does not have rather than dropping it, so a caller that believes
otherwise is told.

For a request addressed to one phone, use the owner's own direct server and its ordinary
`seekervault://pair` link or QR code. The shared gateway has no invitation, device-binding,
request-result or completion-polling API.

Developer internals and the verification commands are
[`docs/development/demos.md`](../../docs/development/demos.md).
