# Portable deployments

`deploy/` is the only Compose entry point. Application orchestration, public ingress, and
operator-specific networking are separate layers:

| Preset | Starts | Does not start |
| --- | --- | --- |
| [`feed/compose.yaml`](feed/compose.yaml) | feed gateway, Centrifugo, Redis, local gateway operator CLI profile | MCP, demos, Caddy |
| [`mcp/compose.yaml`](mcp/compose.yaml) | one owner's direct MCP server | feed services, demos, Caddy |
| [`copytrading/compose.yaml`](copytrading/compose.yaml) | CopyTrading and its operator CLI profile | feed services, Prediction, MCP, Caddy |
| [`prediction/compose.yaml`](prediction/compose.yaml) | Prediction and its operator CLI profile | feed services, CopyTrading, MCP, Caddy |
| [`ingress/feed/compose.yaml`](ingress/feed/compose.yaml) | optional public feed HTTPS/HTTP2 Caddy | applications and dependencies |
| [`ingress/direct/compose.yaml`](ingress/direct/compose.yaml) | optional public MCP/unary-phone HTTPS Caddy | the direct server |
| [`operators/tailscale/`](operators/tailscale/README.md) | optional Funnel/certificate examples | portable defaults |

There is deliberately no second all-in-one application stack. Starting the four portable projects
is the full-demo convenience:

```sh
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml up -d --build
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml up -d --build
docker compose --env-file deploy/copytrading/.env -f deploy/copytrading/compose.yaml up -d --build
docker compose --env-file deploy/prediction/.env -f deploy/prediction/compose.yaml up -d --build
```

Each command is an independent Compose project. Stop, replace, or roll back one without recreating
the other three. A demo publishes to the authenticated URL in `PUBLISHER_PUBLISH_URL`; that may be
the optional public feed ingress or another privately reachable gateway. It never imports or starts
the gateway.

## Secrets and read-only mounts

The copied `.env` files are git-ignored but contain live tokens; set mode `0600`, do not bake them
into images, and give every service a distinct credential. The base examples pass their small
runtime secrets as environment variables. The application-level `*_FILE` alternatives remain
available for operator overlays that mount a secret directory read-only; they are not silently
mapped to a host path by the portable base.

The supplied mounts with host paths are deliberately opt-in: `deploy/feed/compose.push.yaml` mounts
one Firebase JSON into the gateway only, the CopyTrading `admin` profile mounts its password/token/
session directory into the admin only, and the Tailscale examples mount PEMs into the process that
terminates TLS. None of those directories is a shared data volume, and `deploy/**/secrets`,
`deploy/**/tls`, and `deploy/**/backups` are ignored by Git. Application data uses only the explicit
named volumes described below.

## Local reference feed

```sh
cp deploy/feed/.env.example deploy/feed/.env
# replace the two Centrifugo secrets
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml up -d --build
curl --fail http://127.0.0.1:8080/healthz
```

The gateway read listener is host-loopback `8080`; the authenticated publisher listener is
host-loopback `8091`. Centrifugo's API, its unidirectional gRPC listener, and Redis have no host
ports. The local stack has no proxy or domain. A production phone uses the optional feed ingress,
which is where the read API and the one allowed gRPC stream procedure share a trusted HTTPS origin.

The proxyless default is deliberately snapshot-only: leave `BROADCAST_STREAM_URL` empty because the
raw read and broker listeners do not share an origin. When the feed ingress below is running, set
`BROADCAST_STREAM_URL=http://centrifugo:8000`, copy `CENTRIFUGO_API_KEY` to
`BROADCAST_STREAM_API_KEY`, copy `CENTRIFUGO_TOKEN_KEY` to `BROADCAST_STREAM_TOKEN_KEY`, and set
`BROADCAST_PUBLIC_URL` to its HTTPS origin. Then restart only `feed-gateway`. It can now mint honest
stream tickets for the same origin.

SQLite is the authoritative local file on `broadcast-data`. Redis is a nonpersistent recovery
cache. `CENTRIFUGO_REDIS_URL` belongs to Centrifugo and accepts `redis://` credentials or `rediss://`
TLS as documented by Centrifugo; changing it relocates Redis without changing the gateway or its
database. Optional `CENTRIFUGO_REDIS_TLS_SERVER_CA_PEM` and
`CENTRIFUGO_REDIS_TLS_SERVER_NAME` configure certificate verification; optional
`CENTRIFUGO_REDIS_TLS_CERT_PEM`/`CENTRIFUGO_REDIS_TLS_KEY_PEM` configure a client identity. Each PEM
setting accepts Centrifugo v6's raw PEM, base64 PEM, or mounted file path forms. Never set an
insecure TLS skip flag.

## Public feed ingress

The application project creates the private named network that the independent ingress project
joins. Start the feed first, then:

```sh
cp deploy/ingress/feed/.env.example deploy/ingress/feed/.env
docker compose --env-file deploy/ingress/feed/.env \
  -f deploy/ingress/feed/compose.yaml up -d
```

DNS must already resolve and ports 80/443 must reach the host. Caddy exposes only the anonymous
feed RPCs, the authenticated publisher RPCs, and
`/centrifugal.centrifugo.unistream.CentrifugoUniStream/Consume`. It exposes no gateway operator
command, Centrifugo HTTP API/health/admin route, Redis, database, demo control API, or `/healthz`.
Restarting either Compose project leaves the other running.

`BROADCAST_TRUSTED_PROXIES` is the exact CIDR of the private ingress network, not a global
forwarded-header switch. The gateway trusts `X-Forwarded-For` only from loopback or that explicit
CIDR, so Caddy preserves per-reader rate limits without letting arbitrary peers choose a bucket.
Change it together with `FEED_INGRESS_SUBNET` if the default subnet conflicts with the host.

Before enabling tickets, update `deploy/feed/.env`:

```dotenv
BROADCAST_PUBLIC_URL=https://feeds.example.com
BROADCAST_STREAM_URL=http://centrifugo:8000
BROADCAST_STREAM_API_KEY=<same value as CENTRIFUGO_API_KEY>
BROADCAST_STREAM_TOKEN_KEY=<same value as CENTRIFUGO_TOKEN_KEY>
```

Then replace only `feed-gateway`; Centrifugo, Redis, and Caddy stay up.

## Persistent identities and upgrades

Every durable volume has an explicit physical `volume.name`. The clean defaults preserve the
current standalone projects. An existing combined or older direct deployment is a different real
deployment, not something to auto-merge. Inventory every candidate before choosing one:

```sh
docker volume ls
docker volume inspect <exact-volume-name>
```

Stop its writer and make a cold archive before changing an image or path. Substitute one exact
volume name after inspecting it; do not use a command substitution, wildcard, `down -v`,
`--remove-orphans`, or a broad prune:

```sh
mkdir -p backups
docker run --rm -v <exact-volume-name>:/from:ro -v "$PWD/backups":/backup \
  alpine:3.22 sh -c 'cd /from && tar czf /backup/volume-before-see-135.tgz .'
```

Select a zero-copy lineage by putting both the physical volume and, where shown, the database file
in that preset's `.env`:

| Owner | Existing physical volume | Canonical variables |
| --- | --- | --- |
| direct, old `gateway/` | `seeker-agent-wallet_sidecar-data` | `MCP_VOLUME_NAME=seeker-agent-wallet_sidecar-data`, `DATABASE_PATH=/data/sidecar.db` |
| direct, standalone MCP | `seeker-agent-connect-mcp_mcp-data` | defaults |
| direct, old combined server | `seeker-agent-wallet-server_sidecar-data` | `MCP_VOLUME_NAME=seeker-agent-wallet-server_sidecar-data`, `DATABASE_PATH=/data/sidecar.db` |
| feed, standalone | `seeker-broadcast_broadcast-data` | defaults |
| feed, old combined server | `seeker-agent-wallet-server_broadcast-data` | `BROADCAST_VOLUME_NAME=seeker-agent-wallet-server_broadcast-data` |
| CopyTrading, standalone | `seeker-publisher_publisher-data`, `/data/publisher.db` | defaults |
| CopyTrading, old combined server | `seeker-agent-wallet-server_copytrading-data`, `/data/copytrading.db` | set `COPYTRADING_VOLUME_NAME` **and** `PUBLISHER_DATABASE_PATH=/data/copytrading.db` |
| Prediction, standalone | `seeker-prediction_prediction-data` | defaults |
| Prediction, old combined server | `seeker-agent-wallet-server_prediction-data` | `PREDICTION_VOLUME_NAME=seeker-agent-wallet-server_prediction-data` |

If two non-empty candidates exist, stop: they are separate identities. Back up both and choose the
one the operator intends; never combine SQLite files or let Compose guess. The containers run as
UID/GID `10001:10001`. A copied restore must retain that ownership. Restore only while its service
is stopped, verify `PRAGMA integrity_check`, the schema version, and the stable server/publisher ID,
then start exactly one writer. Rollback means the old image plus its matching pre-upgrade archive;
never point an older binary at a schema a newer binary migrated.

Caddy certificate volumes are parameterized too. The feed ingress defaults to
`seeker-broadcast_proxy-data`/`seeker-broadcast_proxy-config`; direct ingress defaults to
`seeker-agent-wallet_gateway-data`/`seeker-agent-wallet_gateway-config`. Old combined feed ingress
used `seeker-agent-wallet-server_gateway-caddy-data`/
`seeker-agent-wallet-server_gateway-caddy-config`. Select those names explicitly when keeping that
ACME identity.

## Backup and component replacement

SQLite stays on the local named volume, one writer only. For a cold backup:

```sh
docker compose --env-file <preset>/.env -f <preset>/compose.yaml stop <service>
# archive the one exact volume as above
docker compose --env-file <preset>/.env -f <preset>/compose.yaml start <service>
```

For an update, build or pull the replacement and target only its service:

```sh
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml up -d --no-deps feed-gateway
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml up -d --no-deps mcp-server
```

A gateway replacement keeps SQLite publications and pending outbox rows. A Centrifugo or Redis
replacement may cost listeners their recovery cache; phones then read the authoritative snapshot.
Replacing MCP keeps pairings and requests. Replacing one demo keeps only that demo's identity,
revision and outbox state. None of these operations restarts an ingress project.
