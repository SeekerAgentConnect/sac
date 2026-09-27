# Prediction demo

An independent public-feed publisher that is not told what to publish: it
walks a prediction provider's listing, applies the filters its operator configured, and publishes
one feed request per market that matches — then keeps each of those in step with the source until it
closes. Every phone subscribed to its channel reads the same document, and each owner then chooses
their own side and their own stake on their own device and approves it there.

**It is one of two demonstrations, and it is independent.** It builds, tests, images and runs
without [`examples/demo-signals/`](../demo-signals), without the Direct Server SDK, and without the
MCP server. The things it needs that it does not contain are a reachable feed gateway and a
prediction provider. The two demos share a source library —
[`packages/publisher-support/`](../../packages/publisher-support), which has no command, no image and no deployment of its
own — and nothing else: not a database, not a credential, not a container, not a lifecycle.
Restarting or cancelling here does nothing to the other demo's source.

**Nothing comes back.** This server never learns who is subscribed, which side anyone took, whether
they went ahead, or what came of it. There is no table for any of that, no field in its API that
would accept it, and no endpoint that would answer about it. Devices subscribe to the gateway, never
to this process — and the gateway and the phone never learn which provider produced a market either.

**No opinion, either.** It publishes *which market*, never which way: no model, no probability of
its own, and no term a side could be published in. A market that stops matching a filter keeps its
proposal until the source itself ends it, because withdrawing a statement for a reason no subscriber
can see would be a publisher that argues with itself.

| File | What it is |
| --- | --- |
| [`cmd/prediction`](cmd/prediction) | The publisher: markets it discovered itself, served by the bundled `jupiter.prediction` plugin |
| [`cmd/prediction-admin`](cmd/prediction-admin) | Password-gated HTML UI. A client of `/v1`, not a second writer |
| [`cmd/publishctl`](cmd/publishctl) | The operator's tool: a three-line main over the shared client |
| [`internal/jupiter`](internal/jupiter) | The provider: two endpoints, paced, and the only file here that names its host |
| [`internal/discovery`](internal/discovery) | What a filter means, and what one cycle does |
| [`internal/config`](internal/config) | This demo's own half of a deployment: the provider, the filters, the deposit terms |
| [`internal/api`](internal/api) | How its discovery joins the shared API frame |
| [`internal/boundary`](internal/boundary) | What this demo is, as tests over its own source |
| [`Dockerfile`](Dockerfile) | This demo's image, and only this demo's |
| [`.env.example`](.env.example) | Every setting, with its default and what it means. Copy to `.env` here, which git ignores |

Everything durable — the market rows, the signals, their revisions, their idempotency keys and the
outbox that gets them to the gateway — lives in
[`packages/publisher-support/store`](../../packages/publisher-support/store); the client that reaches the gateway is
[`packages/publisher-support/gateway`](../../packages/publisher-support/gateway); the API frame both demos serve is
[`packages/publisher-support/api`](../../packages/publisher-support/api). This module supplies the provider, the filters,
the reconciler, its own configuration and its own deployment.

## 1. What it needs

| Requirement | Version | Why |
| --- | --- | --- |
| Go | 1.27.1, as [`go.mod`](go.mod) requires | Building from source and running the tests |
| Docker | any current release | Building and running the image |
| A reachable feed gateway | [`services/gateway/`](../../services/gateway) | Where publications go and where phones read |
| A publisher credential | issued by that gateway's operator | Authenticates this source to it |
| A prediction provider | Jupiter's prediction API | Where the markets come from — **keyless by default** |

No broker, no Redis, no Firebase credential, no database server. This demo reads a listing, submits
one document per market it decides to publish, and stops. Streaming and push delivery to phones are
the gateway's.

### The provider, keyless and keyed

`PREDICTION_PROVIDER_URL` empty means the keyless host `https://lite-api.jup.ag`, which serves the
two endpoints this demo reads without any credential. That is what a first deployment uses and what
the tests run against, so **no provider account is required to run this demo**.

`PREDICTION_API_KEY` raises the rate allowance, and the keyed host requires one. The key stays in
this deployment: it is never in a manifest, a document, a log line, or an answer this demo gives —
there is a test that drives a full cycle with a key configured and fails if it appears in the log.
It may be a file instead (`PREDICTION_API_KEY_FILE`).

`PREDICTION_CALL_GAP_MS` (default 2100) is what keeps a keyless deployment inside the allowance of
one call every two seconds. Raising the cadence without raising the gap is how a deployment starts
collecting rate limits. Provider details and the rate limits themselves are
[`docs/integrations/jupiter.md`](../../docs/integrations/jupiter.md).

### What a provider outage does

Nothing is withdrawn. A cycle that cannot read the listing at all is recorded `failed`; one that read
part of it is `partial`, and a partial cycle still publishes what it did read. Proposals already
published stand, because a provider that is briefly unreachable has not ended any market. Only the
source ending a market — closed, cancelled, settled, or gone from a direct read — withdraws its
proposal. `publishctl discovery` shows the last cycle, its outcome, and the count
of each reason a considered market was not a candidate, which is the answer to "my filters match
nothing and I do not know which one did it".

## 2. Get a publisher identity and a credential

Registration is the gateway operator's act, not an RPC, and it happens once per publisher. This
source is registered **separately from the CopyTrading demo**, under its own UUID, with its own
credential:

```sh
# on the gateway's host, against the gateway's own database
feed-gatewayctl register \
  --database <gateway database> \
  --server <server-uuid> \
  --label "prediction"
```

That prints one bearer credential, once, and the gateway stores only its SHA-256 hash. See
[`services/gateway/README.md`](../../services/gateway/README.md#register-a-publisher) for rotation and
revocation.

## 3. Build and run from source

```sh
cd examples/demo-prediction
go build ./...
go test ./...

PUBLISHER_SERVER_ID=<server-uuid> \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 \
PUBLISHER_ENVIRONMENT=sandbox \
PUBLISHER_DATABASE_PATH=./prediction.db \
PUBLISHER_API_ADDRESS=127.0.0.1:8094 \
BROADCAST_CREDENTIAL=<the credential the gateway printed> \
PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
PREDICTION_CATEGORIES=crypto \
go run ./cmd/prediction
```

A repository checkout resolves the shared library through the `replace` line at the bottom of
[`go.mod`](go.mod). A copy taken out of the repository has to bring
[`packages/publisher-support/`](../../packages/publisher-support) with it, or replace that line with an explicit module
revision — see [§11](#11-copying-this-demo-out-of-the-repository).

It prints one line on stdout, and that line is the whole of what a subscriber needs:

```
seekervault://feed?v=1&gateway=http%3A%2F%2F127.0.0.1%3A8090&server=<server-uuid>
```

It carries no secret, so it can go in a README, a QR code or a public post.

## 4. Build and run the image

The build context is the repository root, because this module's `go.mod` replaces the shared library
with `../publisher-support` and the build needs that directory too. It needs nothing else:
`examples/demo-signals/` is never copied in, so no swap-writing API, no CopyTrading trader UI and no CopyTrading
binary exists anywhere in this image.

```sh
cd <repository root>
docker build -f examples/demo-prediction/Dockerfile -t demo-prediction:local .

# or, for a host of a different architecture
docker buildx build --platform linux/amd64 -f examples/demo-prediction/Dockerfile \
  -t demo-prediction:local --load .
```

Run it with the settings from [`.env.example`](.env.example) filled in. The container listens on
its network address and the port is published on the host's loopback address only:

```sh
docker volume create prediction-data
docker run -d --name prediction \
  --env-file examples/demo-prediction/.env \
  -e PUBLISHER_API_ADDRESS=0.0.0.0:8092 \
  -v prediction-data:/data \
  -p 127.0.0.1:8094:8092 \
  demo-prediction:local
```

The image also carries `/publishctl` and `/prediction-admin`; see [§8](#8-health-and-a-first-publication)
for the operator client.

## 5. Configuration

Application settings are documented in [`.env.example`](.env.example). The publisher's own half is read by exactly the same code as the other demo's; the `PREDICTION_*`
half is this demo's alone.

| Variable | Required | Default | What it is |
| --- | --- | --- | --- |
| `PUBLISHER_SERVER_ID` | yes | — | This source's lowercase UUID, as registered with the gateway |
| `PUBLISHER_GATEWAY_URL` | yes | — | The gateway's own public origin, character for character |
| `PUBLISHER_ENVIRONMENT` | yes | — | `production` or `sandbox`; stamped into the database on first open |
| `BROADCAST_CREDENTIAL` | yes | — | The credential the gateway issued this source |
| `PUBLISHER_API_TOKEN` | yes | — | The grant to call this demo's own API; at least 32 characters |
| `PUBLISHER_PUBLISH_URL` | no | the gateway URL | Where publications are *sent*, when that differs from where phones read |
| `PUBLISHER_DATABASE_PATH` | yes from source | image: `/data/prediction.db` | This demo's SQLite file |
| `PUBLISHER_API_ADDRESS` | no | `127.0.0.1:8092` | Where its API listens; in a container, set `0.0.0.0:8092` |
| `PUBLISHER_DISPLAY_NAME` | no | — | A default label for a connection; never verified |
| `PUBLISHER_PUBLISH_TIMEOUT_SECONDS` | no | `10` | How long one publication may take before it is retried |
| `PREDICTION_PROVIDER_URL` | no | `https://lite-api.jup.ag` | The provider; empty is the keyless host |
| `PREDICTION_API_KEY` | no | — | Raises the rate allowance; required by the keyed host |
| `PREDICTION_PROVIDER_TIMEOUT_SECONDS` | no | `15` | How long one call to the provider may take (1–120) |
| `PREDICTION_CALL_GAP_MS` | no | `2100` | Least time between two provider calls |
| `PREDICTION_SOURCE` | no | `polymarket` | The venue: `polymarket`, `kalshi` or `bisonfi` |
| `PREDICTION_CATEGORIES` | no | — | The provider's buckets, comma-separated; one listing walk each |
| `PREDICTION_FILTER` | no | — | The provider's own named filter: `new`, `live`, `trending`, `upcoming` |
| `PREDICTION_TAGS` | no | — | The event's own tags, whole and case-insensitive |
| `PREDICTION_KEYWORDS` | no | — | Case-insensitive substrings of title, bucket, subcategory, tags |
| `PREDICTION_STATE` | no | `open` | `any` also publishes closed markets — **sandbox only**, refused in production |
| `PREDICTION_LEAST_CLOSE_IN_MINUTES` | no | `60` | How soon a market may close and still be published |
| `PREDICTION_MOST_CLOSE_IN_MINUTES` | no | `43200` | How far ahead it may close |
| `PREDICTION_LIFETIME_HOURS` | no | `168` | Expiry for a market with no close time, from when it was first seen |
| `PREDICTION_POLL_SECONDS` | no | `300` | How often a cycle runs (30–86400) |
| `PREDICTION_PAGE_SIZE` / `PREDICTION_MOST_PAGES` | no | `25` / `4` | Events per call, calls per bucket |
| `PREDICTION_MOST_OPEN` | no | `25` | Proposals held open at once; the soonest to close win |
| `PREDICTION_MOST_CHECKS` | no | `20` | Tracked markets asked about directly per cycle |
| `PREDICTION_DEPOSIT_MINT` | no | USDC | The deposit token: USDC or JupUSD, and nothing else |
| `PREDICTION_LEAST_DEPOSIT` / `PREDICTION_MOST_DEPOSIT` | no | provider minimum / none | The bounds every signal carries, in base units |
| `PREDICTION_NOTE` | no | — | One line of the operator's prose, at most 400 bytes |

`BROADCAST_CREDENTIAL`, `PUBLISHER_API_TOKEN` and `PREDICTION_API_KEY` each accept a `…_FILE` form
instead, naming a file to read the secret from. Set one or the other, never both.

The examples here use port 8094 rather than 8092 so that a CopyTrading deployment on the same
machine does not collide with it.

## 6. Internal addresses versus what is advertised

- **`PUBLISHER_GATEWAY_URL`** is what phones read and what the manifest names. The gateway checks it
  against its own `BROADCAST_PUBLIC_URL` character for character.
- **`PUBLISHER_PUBLISH_URL`** is where a publication is *sent*. It can be an authenticated public
  feed ingress or a privately reachable publisher listener. Getting it wrong is the mistake with
  no error on the gateway's side: a read-only origin answers 404 to a publication.
- **`PUBLISHER_API_ADDRESS`** is this demo's own operator API. Keep it on the host's loopback
  address, or publish a container's port there only.

Phones never reach `PUBLISHER_API_ADDRESS`. They do not know this process exists.

## 7. Persistent data

One SQLite file, named by `PUBLISHER_DATABASE_PATH`, owned by this process alone: the markets it is
tracking, the signal published for each, their revisions, their idempotency keys, the manifest
revision, and the outbox. A market row and its signal are written in one transaction, because they
are one fact — a row without a signal would be a market nobody hears about, and a signal without a
row would be a proposal nothing maintains.

In the image it lives on the volume mounted at `/data`, owned by uid/gid 10001:10001. The rest of
the image is read-only. Never merge two non-empty SQLite files or point two processes at one. This
data is never shared with CopyTrading.

## 8. Health and a first publication

```sh
curl --fail http://127.0.0.1:8094/healthz
# {"status":"ok"}

cd examples/demo-prediction
export PUBLISHER_API_URL=http://127.0.0.1:8094
export PUBLISHER_API_TOKEN=…
go run ./cmd/publishctl status
go run ./cmd/publishctl reference     # the seekervault://feed line to pass on
```

In the image the client is `/publishctl`.

`/healthz` is the one route with no credential, and it therefore says nothing else.

The reproducible end-to-end run. **Nobody may write a signal here** — `create`, `update` and
`cancel` answer 403, and the refusal names the filters, because the way to change what this
publisher says is to change what it looks for:

```sh
# run a cycle now, rather than waiting for PREDICTION_POLL_SECONDS
go run ./cmd/publishctl poll

# what it is looking for, the last cycle, and every market it is tracking
go run ./cmd/publishctl discovery

# the signals it published for them, and what the gateway confirmed about each
go run ./cmd/publishctl list
go run ./cmd/publishctl show <proposal id>

# and the demonstration that this API takes no signal at all
go run ./cmd/publishctl create --in 2h   # 403 written_by_discovery
```

### The admin UI

`cmd/prediction-admin` is a password-gated HTML client of the same `/v1` API. It lists
signals already published to subscribers, searches the provider listing with typed filters, and
asks discovery to publish a selected market. It is a client, not a second writer: `POST /v1/requests`
stays 403. Side and stake stay on the phone. It is optional and runs only where you start it. It
uses the feed gateway's admin look, from its own copies of the
stylesheet, script and fonts in `internal/admin/assets/` (the templates are in
`internal/admin/templates/`), all embedded in the binary.

The same calls over plain HTTP, which is all the CLI does:

```sh
curl -sS -X POST http://127.0.0.1:8094/v1/discovery/poll \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN"
curl -sS http://127.0.0.1:8094/v1/discovery \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN"
```

Two cycles cannot overlap: a poll while one is running is answered 409 rather than queued. A second
poll that finds the same markets publishes nothing — the gateway answers `unchanged` to an identical
document, so a retry or a restart notifies nobody.

An update happens when the provider's own description of a market moves; a withdrawal happens when
the source ends it. Both are this demo's doing, not a caller's. `publishctl retry <id>` exists for
the one thing that *is* an operator's: a publication the gateway refused for a reason that has since
been fixed.

### Adding the feed in SAC, and verifying a restart

1. `go run ./cmd/publishctl reference` prints one `seekervault://feed?…` line.
2. In the phone app, add a public feed and paste that line (or scan it as a QR code). No credential
   is involved and the phone never contacts this process.
3. `go run ./cmd/publishctl poll`; the phone's feed shows the markets it published.
4. Restart the publisher, then run `go run ./cmd/publishctl discovery`. The identity,
   the tracked markets, the cycle number and anything pending are the same: they are in the file, not
   in the process. The cycle number is the store's to mint, so it does not start again from one.

What each filter means exactly, and the complete account of what one cycle does, is
[`docs/wiki/prediction-template.md`](../../docs/wiki/prediction-template.md).

## 9. Optional: HTTPS and provider credentials

There is no internet-facing overlay for this demo and it needs none: its signals are written by its
own discovery, so its API is something an operator reads rather than something a strategy engine
writes to. Keep it on loopback, or reach it over a VPN or an SSH tunnel. If a deployment genuinely
needs it published, put it behind an authenticated TLS ingress of your own, and remember that the
token on it is still a grant.

The only optional credential here is `PREDICTION_API_KEY`, described in [§1](#1-what-it-needs).
There is no FCM and no OAuth: push is the gateway's
([`docs/guides/firebase.md`](../../docs/guides/firebase.md)) and OAuth belongs to the direct server.

## 10. Logs, common errors, backup, upgrade and rollback

```sh
docker logs --tail=100 prediction     # when run from the image
go run ./cmd/publishctl discovery
curl --fail http://127.0.0.1:8094/healthz
```

Logs name what happened and never print the gateway credential, the API token or the provider key.

| Symptom | Usually |
| --- | --- |
| Refuses to start, listing variables | A missing or malformed setting; both halves are reported at once so a first start is fixed in one pass |
| Refuses `PREDICTION_STATE=any` | It is sandbox-only; run it with `PUBLISHER_ENVIRONMENT=sandbox` |
| Cycles run, nothing is published | The filters match nothing — `publishctl discovery` counts each reason |
| `other_gateway` on the manifest | `PUBLISHER_GATEWAY_URL` is not the gateway's own `BROADCAST_PUBLIC_URL`, character for character |
| 404 on every publication | `PUBLISHER_PUBLISH_URL` points at the *read* origin |
| Cycle outcome `failed` or `partial` | The provider was unreachable or rate-limited; raise `PREDICTION_CALL_GAP_MS`, or lower `PREDICTION_PAGE_SIZE`/`PREDICTION_MOST_PAGES` |
| 403 on `create` | Expected: this demo's signals are its own |

A consistent backup means stopping the writer, archiving the volume, and starting it again:

```sh
mkdir -p backups
docker stop prediction
docker run --rm \
  -v prediction-data:/from:ro \
  -v "$PWD/backups:/to" alpine:3.22 \
  tar -C /from -czf /to/prediction-data.tgz .
docker start prediction
```

To upgrade, take that backup, then replace the container with one from the new image on the same
volume. To roll back, stop it, restore the archive into the same empty volume, and start the
previous image. Never point an older binary at a newer database file.

Restarting, upgrading or rolling back this demo does nothing to the CopyTrading demo or to the
gateway: three processes, three lifecycles, three sets of durable state.

## 11. Copying this demo out of the repository

Take two directories, not one:

```sh
cp -R examples/demo-prediction ~/my-markets
cp -R packages/publisher-support ~/publisher-support
cd ~/my-markets
go mod edit -replace github.com/BrRenat/SeekerAgentWallet/publisher-support=../publisher-support
```

In the repository the `replace` line at the bottom of `go.mod` points at
`../../packages/publisher-support`; the `go mod edit` above points the copy at its sibling instead.
Alternatively, edit that line to an explicit, compatible module revision if you would rather depend
on a published one. Nothing else in the repository is needed: `go build ./...` and
`go test ./...` run from the copy, and the Docker build needs only those two directories as its
context.

## What it holds, and what it cannot

The *provider's* markets it is tracking, the signal published for each, and what the gateway has
confirmed about them. A boundary test reads the live schema and fails if a column for an address, a
chosen amount, a side, a decision or a result appears — and [`internal/boundary`](internal/boundary)
reads this module's source and fails if Firebase, a broker, a feed client, MCP, the Direct SDK or
the other demo turns up in it.

A prediction signal names the provider's market and event identifiers, so a phone can look the
market up for itself, plus the deposit token and the bounds this publisher will have its signals
acted on with. The provider's own page URL is kept here and never published: a URL a publisher chose,
arriving on somebody's phone, is the thing the manifest rules exist to prevent.

**The amount is not in it, and neither is the side.** Those are each owner's, they are chosen on the
phone that will sign, and they stay there.

Developer internals and the verification commands are
[`docs/development/demos.md`](../../docs/development/demos.md).
