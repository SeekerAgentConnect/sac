# Feed gateway

`services/gateway/` is the independently buildable shared public-feed service. Authenticated publisher
backends publish public manifests and feed documents once; anonymous clients read those documents.
The gateway never routes a private request, holds a subscriber decision, or contacts a publisher.
Portable orchestration lives in the separate `do-deploy` repository under
[`compose/feed/`](https://github.com/SeekerAgentConnect/do-deploy/tree/main/compose/feed); optional public routing lives separately under
[`compose/ingress/feed/`](https://github.com/SeekerAgentConnect/do-deploy/tree/main/compose/ingress/feed).

Use the canonical numbered [deployment runbook](https://github.com/SeekerAgentConnect/do-deploy/blob/main/compose/README.md) in `do-deploy` for a clean-host
feeds-only or combined deployment, including the collision-free ports, public stream, publisher registration,
and restart checks.

The runtime is intentionally small:

- Go 1.27.1 with Connect 1.21.0 and protobuf 1.36.12;
- a local SQLite file through `modernc.org/sqlite` 1.59.0, containing publisher credentials,
  authoritative public documents, channel sequences, and the durable outbox;
- optional Centrifugo 6.9.6 for live delivery, with Redis 8.2 used by Centrifugo only as a bounded
  recovery cache; and
- optional Firebase Cloud Messaging for content-free wake-up hints.

No client SDK is required. The published contract is ordinary Connect JSON over HTTP, defined in
[`packages/protocol/proto/seekervault/gateway/v1`](../../packages/protocol/proto/seekervault/gateway/v1). Generated code under
`internal/gen/` is a build artifact of this Go service, not a reusable client module.

## Layout and durable boundary

| Path | Responsibility |
| --- | --- |
| `cmd/feed-gateway` | Starts the read and publication listeners, outbox drainer, and retention sweep |
| `cmd/feed-gatewayctl` | Local publisher registration, rotation, revocation, listing and removal, and the operator's password hash |
| `internal/gateway` | Public read and authenticated publication business rules and Connect handlers |
| `internal/admin` | The operator's password-protected browser administration, on its own listener; off unless a password is configured |
| `internal/credential` | The one place a publishing credential is minted, hashed and named, and the one place a password is stretched |
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
cd services/gateway
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
docker build -f services/gateway/Dockerfile -t seeker-feed-gateway/gateway:local .
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
root of a `do-deploy` checkout (the `compose/...` commands below all run from there):

```sh
cp compose/feed/.env.example compose/feed/.env
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml up -d
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml ps
curl --fail "http://127.0.0.1:${BROADCAST_PORT:-8090}/healthz"
```

The proxyless stack starts the gateway, Centrifugo, and colocated Redis—no MCP server or demo.
Gateway read and authenticated publication ports bind host loopback; the broker API/stream and
Redis have no host port. The preset pulls the published
`docker.io/brenat/seeker-agent-connect:gateway-<version>` image; set `BROADCAST_IMAGE` to run one
built from this directory instead. The independent
[`compose/ingress/feed/`](https://github.com/SeekerAgentConnect/do-deploy/tree/main/compose/ingress/feed) project in `do-deploy` adds a domain, certificates, and the
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
| `BROADCAST_PUSH_CREDENTIALS` | empty/off | Path of a Firebase service-account file, or the JSON document itself (App Platform has no file mount) |
| `BROADCAST_PUSH_ENDPOINT` | required with push | Push API origin |
| `BROADCAST_PUSH_ENVIRONMENT` | required with push | `production` or `sandbox`, included in topic scope |
| `BROADCAST_PUSH_RATE` / `BROADCAST_PUSH_BURST` | `0.1` / `5` | Content-free hints per topic per second and burst |
| `BROADCAST_ADMIN_PASSWORD_HASH` | empty/off | The operator's password, hashed by `feed-gatewayctl password`. Setting it is the only thing that brings the admin page into existence |
| `BROADCAST_ADMIN_ADDRESS` | `127.0.0.1:8092` | Admin listener; must differ from read and publisher |
| `BROADCAST_ADMIN_PATH` | `/admin` | Route prefix the admin page is served under |
| `BROADCAST_ADMIN_SESSION_MINUTES` | `60`, range 5–1440 | Absolute login lifetime |
| `BROADCAST_ADMIN_LOGIN_RATE` / `BROADCAST_ADMIN_LOGIN_BURST` | `0.1` / `5` | Login attempts per second and burst per caller |
| `BROADCAST_ADMIN_PUBLISHER_URL` | `BROADCAST_PUBLIC_URL` | The publisher API address the admin page hands a developer |

The portable Compose files additionally use the host bind/port and physical volume/network names,
`CENTRIFUGO_API_KEY`, `CENTRIFUGO_TOKEN_KEY`, `CENTRIFUGO_REDIS_URL`, its supported Redis TLS trust
and client-identity settings, `REDIS_MAX_MEMORY`, and the optional host-side push credential. The exact contract is
[`compose/feed/.env.example`](https://github.com/SeekerAgentConnect/do-deploy/blob/main/compose/feed/.env.example). Redis authentication belongs in its
`redis://`/`rediss://` URL; no Redis port is published.

## Register a publisher

There are two surfaces and one authority. Both write through the same SQLite file with the same
transactional semantics, each sees the other's work immediately, and neither is an API a publisher
or a phone can reach: there is still no RPC on either public listener that could register anything,
however a request is authenticated.

- **`feed-gatewayctl`**, below. It needs no listener, no password and no browser — only the file —
  so it is the recovery path and the right tool for a deployment that never exposes an
  administrative route.
- **The admin page**, further down. It is the same operations in a browser, for the routine case
  where the operator is not on the host that holds the database.

### With the operator tool

Run it against the same SQLite file:

```sh
./bin/feed-gatewayctl register \
  --database "$PWD/feed-gateway.db" \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
  --label "example publisher"
```

It prints one bearer credential once and stores only its SHA-256 hash. In the packaged stack, from a `do-deploy` checkout:

```sh
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml run --rm gateway-ctl register \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
  --label "example publisher"
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml run --rm gateway-ctl list
```

`register` refuses a server ID that is already registered, rather than quietly adding a credential
to it: a second registration of one identity is either a mistake or somebody claiming an identity
that is in use. Adding a credential to an existing publisher is `rotate`, which says so.

`--host <url>` records the developer's own base URL beside the registration. It is administrative
metadata: the gateway never fetches it, no phone is told to contact it, and knowing or claiming a
host grants nothing — the credential authenticates a publication and the server UUID identifies the
publisher.

Use `rotate --server …` before changing a publisher secret, then
`revoke --credential …`. `forget --server … --yes` removes that publisher and its public documents;
it is not a normal credential rotation.

### With the admin page

The page does not exist until an operator password is configured. Make the hash first — it needs no
database, so it works before one exists:

```sh
printf '%s' 'the password you chose' | ./bin/feed-gatewayctl password
# pbkdf2-sha256.600000.<salt>.<hash>
```

Set that line as `BROADCAST_ADMIN_PASSWORD_HASH`, set `BROADCAST_ADMIN_ADDRESS` to an address the
ingress can reach (`0.0.0.0:8092` in the packaged stack), and restart the gateway. The startup log
says which of the two it did:

```
"msg":"serving operator administration","address":"0.0.0.0:8092","path":"/admin"
"msg":"no operator password is configured: there is no administrative surface, …"
```

With no hash there is no third listener, no route and no setup page — not a disabled one, none. The
hash is deliberately deployment configuration rather than a row in the database: when the demo's
storage is replaced, the operator can still log in and register the publishers again without a
database console.

Then open `https://feeds.example.com/admin` and log in. The page lists every registered publisher
with the state the store can actually prove — `Publishing enabled` or `No active credentials`, and
whether a manifest and feed items have arrived — and offers:

| | What it does |
| --- | --- |
| **Add server** | Registers a publisher and issues its first credential in one transaction, or registers nothing. Takes the publisher's existing server UUID, or generates one the publisher must then use exactly. Shows the raw credential once, with the server ID, public gateway origin, publisher API URL and channel beside it |
| **Add credential** | The same additive rotation as `rotate`: the existing credential keeps working until it is revoked, so the publisher switches without downtime |
| **Revoke** | Ends one credential, or all of a publisher's. Enforced from the next publication. The manifest and feed items it already published stay on the feed |
| **Forget server** | The destructive one, behind typing the publisher's own server ID back. Removes the registration, its credentials, its manifest and every feed item this gateway holds. Phones that already read them keep their own copies |

None of that restarts the gateway or changes its environment. A publisher registered in the browser
can publish on its next request.

The responsive dark shell keeps **Servers**, **Add server**, the gateway address and **Log out** in
one sidebar. Adding a server opens a drawer; its credential is issued onto a separate page that can
be read once. Server detail is where capabilities are switched, credentials are added or revoked,
and a registration is forgotten. Revoke and forget confirmations are in-page dialogs rather than
browser pop-ups, and forgetting still stays disabled until the exact server ID is typed. The bundled
Roboto and Roboto Mono fonts, stylesheet and script all come from this binary; the page loads no CDN.

The page never claims a publisher is "connected" or "online", and counts no subscribers. A publisher
calls an HTTP API when it has something to say and holds no connection in between, and no row in
this database names a subscriber — so there is nothing to report and nothing to invent.

**What protects it.** One configured password, hashed with PBKDF2-HMAC-SHA256 (`crypto/pbkdf2`) and
compared in constant time; server-side sessions in an `HttpOnly`, `SameSite=Strict` cookie scoped to
the admin path, with an absolute lifetime and a logout that actually revokes; a per-session CSRF
token on every mutation plus the browser's own `Sec-Fetch-Site`; login rate limiting under the same
trusted-proxy policy as read limiting; and `Content-Security-Policy: default-src 'none'` with the
stylesheet, script and fonts served from the image. A publishing credential is never an administrator
credential, and an administrator session is never a publishing credential.

**What it does not survive.** Sessions live in the process, so a restart ends every one of them —
one more login, and nothing else. On the DigitalOcean App Platform demo the SQLite file itself does
not survive container replacement: the registrations and credential hashes go with it, and the
documented recovery is to log in and register the publishers again. Lost publisher secrets cannot be
recovered from hashes, and this page does not make storage durable.

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
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml ps
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml logs --tail=100 feed-gateway centrifugo redis
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml run --rm gateway-ctl list
curl --fail "http://127.0.0.1:${BROADCAST_PORT:-8090}/healthz"
# The admin page, when one is configured. Anonymous requests are sent to its login form.
curl -o /dev/null -w '%{http_code}\n' "http://127.0.0.1:${BROADCAST_ADMIN_PORT:-8092}/admin/"
```

Administrative actions are logged as `admin action` with the action, the publisher and the outcome.
No password, session, publishing credential or form body is ever written to the log.

An outbox entry remains pending when Centrifugo is unavailable and is retried after restart. Logs
report pending work and classified failures without document values or credentials. SQLite is the
authority even when stream or push delivery is degraded.

For a consistent backup, stop the writer, archive the existing named volume, and start it again:

```sh
mkdir -p backups
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml stop feed-gateway
docker run --rm \
  -v seeker-broadcast_broadcast-data:/from:ro \
  -v "$PWD/backups:/to" alpine:3.22 \
  tar -C /from -czf /to/feed-gateway-data.tgz .
docker compose --env-file compose/feed/.env -f compose/feed/compose.yaml start feed-gateway
```

The Compose project remains `seeker-broadcast` and defaults to the physical volume
`seeker-broadcast_broadcast-data`, so the current standalone lineage reuses its data. The combined
server lineage is selected explicitly with `BROADCAST_VOLUME_NAME`; see the
[deployment runbook](https://github.com/SeekerAgentConnect/do-deploy/blob/main/compose/README.md#7-back-up-replace-and-roll-back). On first open, the
gateway transactionally migrates the schema forward — v2 to v3 retires the removed private-routing
tables; v3 to v4 adds a publisher's optional host as a column on its existing registration — while
preserving all public manifests, feed items, publisher credentials, sequences, and pending notices.
Registrations made before v4 keep an empty host and are otherwise untouched. Take the backup before
upgrading.

To roll back across a schema change, stop the gateway, restore the pre-upgrade archive into the
same empty local volume, then start the previous image. Do not point an older binary at the newer
file. A future remote SQL service is not enabled by the storage interfaces: it requires a new
adapter, explicit transaction/consistency design, an operator data migration, and its own deployment
work.

**To hand a developer one link**, send them
[`docs/guides/server-development.md`](../../docs/guides/server-development.md): it is the canonical
third-party walkthrough — the onboarding conversation, what each value the operator gives them
means, publishing with nothing but an HTTP client, the lifecycle, every refusal the API can answer,
how to share the feed without the credential, rotation, and what to do after the demo loses its
data.

For the deeper invariants and protocol rationale, see
[`docs/wiki/feed-gateway.md`](../../docs/wiki/feed-gateway.md). Developer internals and verification
live in [`docs/development/feed-gateway.md`](../../docs/development/feed-gateway.md).
