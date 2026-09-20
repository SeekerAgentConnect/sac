# Feed gateway

`feed-gateway/` is the independently buildable shared public-feed service. Authenticated publisher
backends publish public manifests and feed documents once; anonymous clients read those documents.
The gateway never routes a private request, holds a subscriber decision, or contacts a publisher.
Portable orchestration lives under [`deploy/feed/`](../deploy/feed); optional public routing lives
separately under [`deploy/ingress/feed/`](../deploy/ingress/feed).

The runtime is intentionally small:

- Go 1.27.1 with Connect 1.21.0 and protobuf 1.36.12;
- a local SQLite file through `modernc.org/sqlite` 1.59.0, containing publisher credentials,
  authoritative public documents, channel sequences, and the durable outbox;
- optional Centrifugo 6.9.6 for live delivery, with Redis 8.2 used by Centrifugo only as a bounded
  recovery cache; and
- optional Firebase Cloud Messaging for content-free wake-up hints.

No client SDK is required. The published contract is ordinary Connect JSON over HTTP, defined in
[`proto/seekervault/gateway/v1`](../proto/seekervault/gateway/v1). Generated code under
`internal/gen/` is a build artifact of this Go service, not a reusable client module.

## Layout and durable boundary

| Path | Responsibility |
| --- | --- |
| `cmd/feed-gateway` | Starts the read and publication listeners, outbox drainer, and retention sweep |
| `cmd/feed-gatewayctl` | Local-only publisher registration, rotation, revocation, listing, and removal |
| `internal/gateway` | Public read and authenticated publication business rules and Connect handlers |
| `internal/storage` | Focused durable contracts used by business and delivery code; contains no SQL |
| `internal/storage/sqlite` | The local SQLite implementation, schema, migrations, and transaction ownership |
| `internal/dispatch` | Durable outbox draining after a successful commit; delivery is at least once |
| `internal/stream` | Optional Centrifugo publication and listener-ticket minting |
| `internal/relay` | Optional content-free Firebase hints |

A publication is one storage transaction: validate against the held revision, advance the channel
sequence, store the document, and upsert its outbox notice. The API answers success and wakes the
drainer only after commit. A crash after commit can delay or duplicate delivery, but cannot produce
a success without durable state or a fan-out event without a committed document. Consumers must
therefore treat `(channel, document identity, revision)` as idempotent.

## Build and run from source

From the repository root:

```sh
cd feed-gateway
go build -o ./bin/feed-gateway ./cmd/feed-gateway
go build -o ./bin/feed-gatewayctl ./cmd/feed-gatewayctl

export BROADCAST_PUBLIC_URL=http://127.0.0.1:8090
export BROADCAST_DATABASE_PATH="$PWD/feed-gateway.db"
./bin/feed-gateway
```

The legacy `BROADCAST_*` variable names and default `/data/broadcast.db` filename are deliberately
retained so existing deployments can move to the renamed service without a silent configuration or
data split. With the source command above, the anonymous API is at `http://127.0.0.1:8090`, the
publisher API is at `http://127.0.0.1:8091`, and both expose `GET /healthz`:

```sh
curl --fail http://127.0.0.1:8090/healthz
# {"status":"ok"}
```

Startup fails before listening if required configuration is absent, the SQLite file cannot be
opened or migrated, stream settings are partial, or push settings are partial. Normal startup and
refusals are structured on stderr; credentials and document values are never logged.

## Build and run the image

The Dockerfile is self-contained and must be built with the repository root as its context:

```sh
docker build -f feed-gateway/Dockerfile -t seeker-feed-gateway/gateway:local .
docker volume create broadcast-data
docker run --rm --name feed-gateway \
  --read-only --user 10001:10001 \
  -v broadcast-data:/data \
  -e BROADCAST_PUBLIC_URL=http://127.0.0.1:8090 \
  -e BROADCAST_READ_ADDRESS=0.0.0.0:8090 \
  -e BROADCAST_PUBLISHER_ADDRESS=0.0.0.0:8091 \
  -p 127.0.0.1:8090:8090 \
  -p 127.0.0.1:8091:8091 \
  seeker-feed-gateway/gateway:local
```

This direct `docker run` is a loopback-only development example. For the portable reference stack,
copy the deployment environment, create the two Centrifugo secrets it requests, then run from the
repository root:

```sh
cp deploy/feed/.env.example deploy/feed/.env
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml up -d --build
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml ps
curl --fail "http://127.0.0.1:${BROADCAST_PORT:-8080}/healthz"
```

The proxyless stack starts the gateway, Centrifugo, and colocated Redis—no MCP server or demo.
Gateway read and authenticated publication ports bind host loopback; the broker API/stream and
Redis have no host port. There is no published remote image assumed by this repository:
`seeker-feed-gateway/gateway:local` is built locally. The independent
[`deploy/ingress/feed/`](../deploy/ingress/feed) project adds a domain, certificates, and the
same-origin HTTPS/HTTP2 stream without coupling proxy and application lifecycle.

The image is `FROM scratch`, runs as uid/gid `10001`, and writes only `/data`. A bind mount must be
created and owned by that identity before start:

```sh
sudo install -d -o 10001 -g 10001 -m 0700 /srv/seeker-feed-gateway
```

Do not place the SQLite file on NFS or another network filesystem. It is a local, single-process
authority. Redis is Centrifugo's disposable recovery cache and is never gateway storage.

## Addresses: public identity versus internal reachability

`BROADCAST_PUBLIC_URL` is the canonical origin placed in a feed reference and required in every
published manifest. It must be public HTTPS, except that loopback HTTP is accepted for development.
It has no path, query, fragment, or credentials.

A publisher may reach the same deployment by another address. `PUBLISHER_PUBLISH_URL` is the actual
authenticated endpoint it can reach, while manifests still contain the external
`https://feeds.example.com`. Never put `feed-gateway`, another Compose hostname, `localhost` from a
different container, or the private publication address in a manifest.

The packaged Caddy files route public read methods to the read listener and publication methods to
the separate publisher listener. Operators may delete the public publication route and expose
`BROADCAST_PUBLISHER_ADDRESS` only through a tunnel or private network; clients are unaffected.

## Configuration

The process reads these variables. Empty optional values use the stated default.

| Variable | Required/default | Meaning |
| --- | --- | --- |
| `BROADCAST_PUBLIC_URL` | required | Canonical public gateway origin used by manifests and feed references |
| `BROADCAST_DATABASE_PATH` | required | Local SQLite file; the image defaults it to `/data/broadcast.db` |
| `BROADCAST_READ_ADDRESS` | `127.0.0.1:8090` | Anonymous `FeedService` and `/healthz` listener |
| `BROADCAST_PUBLISHER_ADDRESS` | `127.0.0.1:8091` | Authenticated `PublisherService` and `/healthz` listener; must differ from read |
| `BROADCAST_RETENTION_HOURS` | `168`, range 1–8760 | Time an expired/withdrawn document remains readable |
| `BROADCAST_MAX_PROPOSALS` | `200`, range 1–10000 | Maximum held feed items per publisher channel |
| `BROADCAST_READ_RATE` / `BROADCAST_READ_BURST` | `20` / `60` | Read requests per second and burst per caller |
| `BROADCAST_PUBLISH_RATE` / `BROADCAST_PUBLISH_BURST` | `2` / `20` | Publications per second and burst per publisher |
| `BROADCAST_TRUSTED_PROXIES` | empty | Exact IP/CIDR allow-list whose forwarded client address may key read limiting; loopback is always trusted |
| `BROADCAST_STREAM_URL` | empty/off | Internal Centrifugo HTTP API origin |
| `BROADCAST_STREAM_API_KEY` | required with stream URL | Key used only to publish to Centrifugo |
| `BROADCAST_STREAM_TOKEN_KEY` | required with stream URL | HMAC key used to mint listener tickets |
| `BROADCAST_TICKET_MINUTES` | `60`, range 1–1440 | Listener-ticket lifetime |
| `BROADCAST_MAX_CHANNELS` | `32`, range 1–128 | Maximum channels granted by one ticket/topic request |
| `BROADCAST_PUSH_CREDENTIALS` | empty/off | Mounted Firebase service-account path inside the process |
| `BROADCAST_PUSH_ENDPOINT` | required with push | Push API origin |
| `BROADCAST_PUSH_ENVIRONMENT` | required with push | `production` or `sandbox`, included in topic scope |
| `BROADCAST_PUSH_RATE` / `BROADCAST_PUSH_BURST` | `0.1` / `5` | Content-free hints per topic per second and burst |

The portable Compose files additionally use the host bind/port and physical volume/network names,
`CENTRIFUGO_API_KEY`, `CENTRIFUGO_TOKEN_KEY`, `CENTRIFUGO_REDIS_URL`, its supported Redis TLS trust
and client-identity settings, `REDIS_MAX_MEMORY`, and the optional host-side push credential. The exact contract is
[`deploy/feed/.env.example`](../deploy/feed/.env.example). Redis authentication belongs in its
`redis://`/`rediss://` URL; no Redis port is published.

## Register a publisher

Registration is deliberately not an RPC. Run the operator tool against the same SQLite file:

```sh
./bin/feed-gatewayctl register \
  --database "$PWD/feed-gateway.db" \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
  --label "example publisher"
```

It prints one bearer credential once and stores only its SHA-256 hash. In the packaged stack:

```sh
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml run --rm gateway-ctl register \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
  --label "example publisher"
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml run --rm gateway-ctl list
```

Use `rotate --server …` before changing a publisher secret, then
`revoke --credential …`. `forget --server … --yes` removes that publisher and its public documents;
it is not a normal credential rotation.

## Publish with plain HTTP and Connect JSON

These calls need only `curl`; no generated client is involved. Set the externally advertised
origin separately from the address this publisher can reach:

```sh
export PUBLIC_GATEWAY=http://127.0.0.1:8090
export PUBLISH_URL=http://127.0.0.1:8091
export PUBLISHER_ID=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
export CHANNEL=server/$PUBLISHER_ID
export PROPOSAL_ID=7c9e6679-7425-40de-944b-e07fc1f90ae7
export PUBLISHER_CREDENTIAL='<the value printed by register>'
```

Publish the manifest first:

```sh
curl --fail-with-body "$PUBLISH_URL/seekervault.gateway.v1.PublisherService/PublishManifest" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $PUBLISHER_CREDENTIAL" \
  --data "{\"manifest\":{\"serverId\":\"$PUBLISHER_ID\",\"protocolVersion\":1,\"settingsRevision\":\"1\",\"mode\":\"CONNECTION_MODE_GATEWAY_FEED\",\"environments\":[\"SERVER_ENVIRONMENT_PRODUCTION\"],\"displayName\":\"Example feed\",\"feed\":{\"gatewayUrl\":\"$PUBLIC_GATEWAY\",\"channel\":\"$CHANNEL\"}}}"
# {"status":"PUBLISH_STATUS_STORED","settingsRevision":"1"}
```

Create revision 1 of a feed item:

```sh
curl --fail-with-body "$PUBLISH_URL/seekervault.gateway.v1.PublisherService/PublishProposal" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $PUBLISHER_CREDENTIAL" \
  --data "{\"proposal\":{\"serverId\":\"$PUBLISHER_ID\",\"channel\":\"$CHANNEL\",\"proposalId\":\"$PROPOSAL_ID\",\"revision\":\"1\",\"operation\":\"swap\",\"pluginId\":\"jupiter.swap\",\"status\":\"PROPOSAL_STATUS_OPEN\",\"createdAt\":\"2030-01-02T09:00:00Z\",\"updatedAt\":\"2030-01-02T09:00:00Z\",\"expiresAt\":\"2030-01-03T09:00:00Z\",\"publisherNote\":\"HTTP example\",\"values\":[{\"key\":\"published_price\",\"text\":\"139420000\"}]}}"
# {"status":"PUBLISH_STATUS_STORED","revision":"1","snapshotSequence":"2"}
```

Update it by sending the complete document with a higher revision. `createdAt` and the identity stay
fixed; any other content change must advance `revision`:

```sh
curl --fail-with-body "$PUBLISH_URL/seekervault.gateway.v1.PublisherService/PublishProposal" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $PUBLISHER_CREDENTIAL" \
  --data "{\"proposal\":{\"serverId\":\"$PUBLISHER_ID\",\"channel\":\"$CHANNEL\",\"proposalId\":\"$PROPOSAL_ID\",\"revision\":\"2\",\"operation\":\"swap\",\"pluginId\":\"jupiter.swap\",\"status\":\"PROPOSAL_STATUS_OPEN\",\"createdAt\":\"2030-01-02T09:00:00Z\",\"updatedAt\":\"2030-01-02T10:00:00Z\",\"expiresAt\":\"2030-01-03T09:00:00Z\",\"publisherNote\":\"Updated HTTP example\",\"values\":[{\"key\":\"published_price\",\"text\":\"141000000\"}]}}"
```

Withdraw it with the next revision. Withdrawal is final for that proposal identity:

```sh
curl --fail-with-body "$PUBLISH_URL/seekervault.gateway.v1.PublisherService/CancelProposal" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $PUBLISHER_CREDENTIAL" \
  --data "{\"proposalId\":\"$PROPOSAL_ID\",\"revision\":\"3\"}"
```

Read the authoritative result anonymously from the public/read address:

```sh
curl --fail-with-body "$PUBLIC_GATEWAY/seekervault.gateway.v1.FeedService/GetProposal" \
  -H 'Content-Type: application/json' \
  --data "{\"channel\":\"$CHANNEL\",\"proposalId\":\"$PROPOSAL_ID\"}"
```

The newer common contract uses `PublishRequest`, `CancelRequest`, `ListRequests`, and `GetRequest`
at the same Connect JSON paths. `PublishProposal` remains a compatibility adapter over the same row,
revision gate, sequence, and outbox; it is used above because its compact shape makes the lifecycle
clear.

### Revisions, errors, and retries

- Repeating byte-equivalent content at the same revision returns `PUBLISH_STATUS_UNCHANGED` and
  creates no sequence or fan-out event. It is safe after a timeout or lost response.
- Lower revisions are rejected as `stale_revision`; different content at the held revision is
  `revision_conflict`; a withdrawn identity is `cancelled`; cross-publisher server/channel claims
  are rejected.
- Connect errors are JSON with an HTTP status, a stable Connect `code`, a short `message`, and a
  `GatewayErrorDetail` carrying `problem`, `field`, and when relevant `heldRevision`. Refused values
  are not echoed.
- Retry transport failures and Connect `unavailable`/`internal` with bounded exponential backoff.
  Fix authentication, permission, invalid-argument, not-found, or failed-precondition responses
  before retrying. A 429/resource-exhausted response should respect backoff.
- A publisher must retry the same revision and content until it learns the outcome; it must never
  invent a higher revision merely because an HTTP response was lost.

## Streaming and push are optional

Leaving `BROADCAST_STREAM_URL` empty runs a complete snapshot-only gateway. When it is set, the URL,
API key, and token key are all required. Centrifugo is external to the gateway process; Redis is
wired to Centrifugo, not imported or contacted by the gateway. The broker contains only evictable
recovery history. A listener that cannot recover reads the SQLite-backed snapshot.

The proxyless preset intentionally leaves the stream URL empty: its raw read and broker ports are
not one origin. Enable tickets only after the optional feed ingress is running, then use the
internal `http://centrifugo:8000` API URL while `BROADCAST_PUBLIC_URL` remains the external HTTPS
feed/stream origin. Copy the Centrifugo API and token secrets into the gateway's corresponding
`BROADCAST_STREAM_*` settings at that point. This avoids issuing a ticket for an unrouted stream.

Push is similarly off until all of `BROADCAST_PUSH_CREDENTIALS`, `BROADCAST_PUSH_ENDPOINT`, and
`BROADCAST_PUSH_ENVIRONMENT` are configured. The credential is mounted read-only into the gateway
only. Hints contain no document or subscriber identity; topic membership remains Firebase's.

## Operations, backup, upgrade, and rollback

Useful checks:

```sh
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml ps
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml logs --tail=100 feed-gateway centrifugo redis
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml run --rm gateway-ctl list
curl --fail "http://127.0.0.1:${BROADCAST_PORT:-8080}/healthz"
```

An outbox entry remains pending when Centrifugo is unavailable and is retried after restart. Logs
report pending work and classified failures without document values or credentials. SQLite is the
authority even when stream or push delivery is degraded.

For a consistent backup, stop the writer, archive the existing named volume, and start it again:

```sh
mkdir -p backups
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml stop feed-gateway
docker run --rm \
  -v seeker-broadcast_broadcast-data:/from:ro \
  -v "$PWD/backups:/to" alpine:3.22 \
  tar -C /from -czf /to/feed-gateway-data.tgz .
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml start feed-gateway
```

The Compose project remains `seeker-broadcast` and defaults to the physical volume
`seeker-broadcast_broadcast-data`, so the current standalone lineage reuses its data. The combined
server lineage is selected explicitly with `BROADCAST_VOLUME_NAME`; see
[`deploy/README.md`](../deploy/README.md#persistent-identities-and-upgrades). On first open, the
gateway transactionally migrates schema v2 to v3, preserving all public manifests, feed items,
publisher credentials, sequences, and pending notices while retiring the removed private-routing
tables. Take the backup before upgrading.

To roll back across a schema change, stop the gateway, restore the pre-upgrade archive into the
same empty local volume, then start the previous image. Do not point an older binary at the newer
file. A future remote SQL service is not enabled by the storage interfaces: it requires a new
adapter, explicit transaction/consistency design, an operator data migration, and its own deployment
work.

For the deeper invariants and protocol rationale, see
[`docs/wiki/feed-gateway.md`](../docs/wiki/feed-gateway.md). Developer internals and verification
live in [`docs/development/feed-gateway.md`](../docs/development/feed-gateway.md).
