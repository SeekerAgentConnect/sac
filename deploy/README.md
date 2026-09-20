# Deploy Seeker Agent Connect

This is the canonical clean-host deployment guide for the repository. It covers three supported
shapes: the direct MCP server only, public feeds only, or all four independently managed
applications on one host. There is no all-in-one Compose project and no Tailscale dependency.

The repository builds local images. It does not publish npm packages or container images, so keep
this checkout on the deployment host or replace the local image names with artifacts you operate.

## 1. Choose a deployment

| Shape | Projects to run | Public address |
| --- | --- | --- |
| Direct only | `deploy/mcp` plus its native-TLS overlay | `https://direct.example.com:8443` |
| Feeds only | `deploy/feed` plus `deploy/ingress/feed`; demos optional | `https://feeds.example.com` |
| Everything | feed, feed ingress, MCP, CopyTrading, Prediction | the two origins above |

Each project owns its process, credentials, database, volume, restart, and rollback. Starting or
replacing one must not recreate another.

On a single public IP the feed Caddy project owns host ports 80 and 443. The direct application
therefore uses native TLS on 8443. To put both origins on 443, provide a second IP address or an
explicit HTTP/2-capable SNI/L4 router in front of them. Never bind two projects to the same port,
and never put the UpdateService behind an HTTP/1 reverse proxy.

## 2. Prepare the host

Install Git, OpenSSL, Docker Engine, and Docker Compose v2. Building and repository checks also use
Node.js 24.21.0, pnpm 12.3.4, and Go 1.27.1. Run every command below from the repository root.

DNS must resolve `direct.example.com` and `feeds.example.com` to the host before public startup.
Allow inbound TCP 80, 443, and 8443 for the combined example. The feed proxy uses 80/443 for ACME
and HTTPS; direct uses 8443. The following host ports are collision-free:

| Host port | Owner | Container port | Reachability |
| --- | --- | --- | --- |
| 80, 443 | feed Caddy | 80, 443 | public |
| 8443 | MCP native TLS | 8080 | public |
| 8090 | feed read API | 8090 | loopback |
| 8091 | feed publisher API | 8091 | loopback |
| 8092 | CopyTrading API | 8092 | loopback |
| 8094 | Prediction API | 8092 | loopback |
| 8096 | optional CopyTrading admin | 8096 | loopback |

Centrifugo ports 8000/11000 and Redis 6379 are private container-network ports and are not
published on the host. Use these addresses for each role; a container's `127.0.0.1` is never an
address for a different project:

| Role | Address to configure | Used by |
| --- | --- | --- |
| Agent MCP transport | `https://direct.example.com:8443/mcp` | Hermes/OpenClaw |
| Paired phone origin | `https://direct.example.com:8443` (`SIDECAR_PUBLIC_URL`) | SAC pairing, unary calls, UpdateService |
| Public feed origin | `https://feeds.example.com` (`BROADCAST_PUBLIC_URL`, each demo's `PUBLISHER_GATEWAY_URL`) | SAC snapshots and stream; advertised in feed references |
| Private combined publish target | `http://feed-gateway:8091` (injected by each combined overlay) | CopyTrading and Prediction containers only |
| Standalone publish target | a reachable authenticated publisher origin, normally `https://feeds.example.com` | A demo on another host/network |
| Feed-to-broker API | `http://centrifugo:8000` (`BROADCAST_STREAM_URL`) | Feed gateway only |
| Broker-to-Redis | `redis://redis:6379` (`CENTRIFUGO_REDIS_URL`) | Centrifugo only |
| CopyTrading operator API | `http://copytrading:8092` inside its project; host `127.0.0.1:8092` | `publishctl` or a local operator |
| Prediction operator API | `http://prediction:8092` inside its project; host `127.0.0.1:8094` | `publishctl` or a local operator |

Copy the configuration templates and restrict them before inserting secrets:

```sh
cp deploy/mcp/.env.example deploy/mcp/.env
cp deploy/feed/.env.example deploy/feed/.env
cp deploy/ingress/feed/.env.example deploy/ingress/feed/.env
cp deploy/copytrading/.env.example deploy/copytrading/.env
cp deploy/prediction/.env.example deploy/prediction/.env
chmod 600 deploy/mcp/.env deploy/feed/.env deploy/ingress/feed/.env \
  deploy/copytrading/.env deploy/prediction/.env
openssl rand -hex 32
```

Run the last command once for every credential. Do not reuse an MCP token, phone bootstrap token,
Centrifugo key, publisher credential, or demo operator token. The `.env` files, `deploy/**/tls`,
`deploy/**/secrets`, and `deploy/**/backups` are ignored by Git.

The containers run as UID/GID `10001:10001`. Named volumes are created with suitable ownership by
the images. A manually restored file must retain that ownership.

## 3. Direct MCP over native HTTPS and HTTP/2

The production direct path terminates TLS in the Node application. That preserves ALPN `h2` for
the bidirectional UpdateService stream while serving Streamable HTTP MCP, pairing, and unary phone
RPCs on the same origin. The secure listener intentionally omits the Stage 1 LiveCommandService and
allows `/healthz` only from loopback.

Obtain a normally trusted certificate for `direct.example.com` with the host's ACME client. Keep
issuance and renewal outside this repository and image. If feed Caddy already owns port 80, use
your DNS provider's ACME challenge or another operator-managed challenge that does not take that
port. Copy only the deployed identity into a dedicated directory with the exact names and
container UID/GID:

```sh
sudo install -d -o 10001 -g 10001 -m 0700 /srv/seeker-direct-tls
sudo install -o 10001 -g 10001 -m 0644 /path/from/acme/fullchain.pem \
  /srv/seeker-direct-tls/fullchain.pem
sudo install -o 10001 -g 10001 -m 0600 /path/from/acme/privkey.pem \
  /srv/seeker-direct-tls/privkey.pem
```

Run those two `install` commands and the MCP restart command below from the ACME client's
successful renewal/deploy hook. Do not mount the client's account/state directory into the
application container. Then set these values in `deploy/mcp/.env`:

```dotenv
MCP_TOKEN=<random agent token>
PHONE_TOKEN=<different random bootstrap token>
MCP_ALLOWED_HOSTS=direct.example.com
SIDECAR_PUBLIC_URL=https://direct.example.com:8443
MCP_TLS_DIR=/srv/seeker-direct-tls
MCP_SERVER_BIND=0.0.0.0
MCP_SERVER_PORT=8443
```

For a private test CA, also put its CA PEM in the mounted directory and set
`SIDECAR_HEALTH_CA_CERT_PATH=/run/tls/ca.pem`. Leave that setting empty for a public CA. The health
program always verifies the configured public hostname and trust chain; there is no insecure mode.

Start and inspect the server:

```sh
docker compose --env-file deploy/mcp/.env \
  -f deploy/mcp/compose.yaml -f deploy/mcp/compose.tls.yaml up -d --build
docker compose --env-file deploy/mcp/.env \
  -f deploy/mcp/compose.yaml -f deploy/mcp/compose.tls.yaml ps
docker inspect --format '{{json .State.Health}}' seeker-agent-connect-mcp-mcp-server-1
docker compose --env-file deploy/mcp/.env \
  -f deploy/mcp/compose.yaml -f deploy/mcp/compose.tls.yaml exec mcp-server \
  node mcp-server/dist/healthcheck.js
curl --http2 -sS -o /dev/null -w '%{http_code}\n' https://direct.example.com:8443/healthz
```

The `ps`/inspect output must say `healthy`, the internal command exits zero, and the public curl
prints `404`; a public health endpoint would leak deployment state. Confirm that TLS negotiated h2:

```sh
curl --http2 -sS -o /dev/null -w '%{http_version}\n' \
  https://direct.example.com:8443/seekervault.request.v1.UpdateService/Sync
```

It must print `2`. Pair the phone from the same container and add the resulting code in SAC:

```sh
docker compose --env-file deploy/mcp/.env \
  -f deploy/mcp/compose.yaml -f deploy/mcp/compose.tls.yaml exec mcp-server \
  node mcp-server/dist/cli.js pair
```

The phone should show the connection as Live. That is the deployed bidirectional UpdateService
check; an HTTP/1 downgrade can pass an ordinary HTTPS request but cannot produce this state.

For an agent, configure Hermes or OpenClaw with
`https://direct.example.com:8443/mcp` and the MCP bearer token. Follow the exact client steps in
[`docs/integrations/hermes.md`](../docs/integrations/hermes.md) or
[`docs/integrations/openclaw.md`](../docs/integrations/openclaw.md). Exercise the full durable path:

1. Discover the MCP tools and create a request.
2. Read it in SAC, then approve or dismiss it.
3. Read the terminal result from the agent.
4. Create a second request and cancel it from the agent.

For local development only, omit `compose.tls.yaml`, keep the loopback defaults, and check
`http://127.0.0.1:8080/healthz`. Cleartext is not a remote deployment path.

## 4. Public feed gateway

Set two distinct random values in `deploy/feed/.env`, then configure the public origin and stream:

```dotenv
BROADCAST_PUBLIC_URL=https://feeds.example.com
CENTRIFUGO_API_KEY=<random Centrifugo API key>
CENTRIFUGO_TOKEN_KEY=<different random ticket key>
BROADCAST_STREAM_URL=http://centrifugo:8000
BROADCAST_STREAM_API_KEY=<same value as CENTRIFUGO_API_KEY>
BROADCAST_STREAM_TOKEN_KEY=<same value as CENTRIFUGO_TOKEN_KEY>
BROADCAST_PORT=8090
BROADCAST_PUBLISH_PORT=8091
```

Set `FEED_DOMAIN=feeds.example.com` and a real `ACME_EMAIL` in
`deploy/ingress/feed/.env`. Start the application before the independently managed ingress:

```sh
docker compose --env-file deploy/feed/.env \
  -f deploy/feed/compose.yaml up -d --build
docker compose --env-file deploy/ingress/feed/.env \
  -f deploy/ingress/feed/compose.yaml up -d
curl --fail http://127.0.0.1:8090/healthz
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml ps
docker compose --env-file deploy/ingress/feed/.env -f deploy/ingress/feed/compose.yaml ps
```

The curl response is `{"status":"ok"}`; both Compose projects must report their services up and
the ingress container healthy.

Caddy exposes only the anonymous feed RPCs, the authenticated publisher RPCs, and the exact
Centrifugo unidirectional HTTP/2 stream. It exposes no health, operator, Centrifugo API/admin,
Redis, database, or demo control route. `BROADCAST_TRUSTED_PROXIES` must remain the exact feed
ingress subnet; publishers never join that trusted-proxy network.

The gateway starts with no publisher. Register each source once with a different lowercase UUID:

```sh
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml \
  --profile operator run --rm gateway-ctl register \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label 'copy trading'
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml \
  --profile operator run --rm gateway-ctl register \
  --server 7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d --label prediction
```

Each command prints its bearer credential once. Store it in only that demo's `.env`.

### Optional: administer publishers in a browser

The gateway can also serve a password-protected admin page to its operator, on a third listener.
It does not exist until a password is configured; nothing below is required, and a deployment that
skips it keeps `gateway-ctl` and loses nothing.

```sh
# The hash. It touches no database, so this works before the stack is up.
printf '%s' 'the password you chose' | docker compose --env-file deploy/feed/.env \
  -f deploy/feed/compose.yaml --profile operator run --rm -T gateway-ctl password
```

Put the printed line in `deploy/feed/.env`, with the address the ingress can reach:

```dotenv
BROADCAST_ADMIN_PASSWORD_HASH=pbkdf2-sha256.600000.<salt>.<hash>
BROADCAST_ADMIN_ADDRESS=0.0.0.0:8092
```

Recreate the gateway. Its startup log says which of the two states it is in — `serving operator
administration` with the address and path, or `no operator password is configured`. The page is
then at `https://feeds.example.com/admin` through the ingress project, whose Caddyfile carries an
`/admin` block the operator may delete to keep the page private; without it, reach
`127.0.0.1:8092` over SSH or a tunnel instead.

The page does the same operations as `gateway-ctl` — register, additive rotation, revoke one or
all, and the destructive `forget` behind typing the publisher's own ID back — through the same
SQLite file, so the two see each other's work. A registration takes effect immediately: the gateway
is not restarted and its environment does not change. A publishing credential is never accepted as
the operator password, and no publisher or phone API can reach any of it.

On the DigitalOcean App Platform app ([`deploy/seeker-gateway.yaml`](seeker-gateway.yaml)) the
`gateway` service already declares `BROADCAST_ADMIN_ADDRESS=0.0.0.0:8092`, opens that internal
port, and the `edge` Caddy already routes `/admin*` to it. `BROADCAST_ADMIN_PASSWORD_HASH` is
declared **empty** there on purpose — no credential is committed — so turning the page on is one
encrypted app-level secret and a redeploy.

Ephemeral storage is not fixed by any of this. If a container replacement discards the gateway's
SQLite file, the registrations and credential hashes go with it; the operator logs in to the
replacement — the password is deployment configuration, not a database row — and registers the
publishers again. Lost publisher secrets cannot be recovered from hashes.

## 5. Combined host: feed, direct server, and both demos

For the combined shape, start the feed with its publication-network overlay. The overlay adds a
private network for demo-to-gateway writes; it does not join publishers to the ingress network.

```sh
docker compose --env-file deploy/feed/.env \
  -f deploy/feed/compose.yaml -f deploy/feed/compose.combined.yaml up -d --build
docker compose --env-file deploy/ingress/feed/.env \
  -f deploy/ingress/feed/compose.yaml up -d
docker compose --env-file deploy/mcp/.env \
  -f deploy/mcp/compose.yaml -f deploy/mcp/compose.tls.yaml up -d --build
```

After registering both UUIDs in step 4, set the corresponding credential and operator token in
each demo file. Keep the advertised gateway public and the write path private:

```dotenv
PUBLISHER_GATEWAY_URL=https://feeds.example.com
PUBLISHER_PUBLISH_URL=https://feeds.example.com
PUBLISHER_ENVIRONMENT=sandbox
```

The combined Compose overlays replace the runtime publish URL with
`http://feed-gateway:8091`; the public value remains what the manifest and phone compare. Start the
demos independently:

```sh
docker compose --env-file deploy/copytrading/.env \
  -f deploy/copytrading/compose.yaml -f deploy/copytrading/compose.combined.yaml up -d --build
docker compose --env-file deploy/prediction/.env \
  -f deploy/prediction/compose.yaml -f deploy/prediction/compose.combined.yaml up -d --build
docker compose --env-file deploy/copytrading/.env \
  -f deploy/copytrading/compose.yaml -f deploy/copytrading/compose.combined.yaml \
  --profile operator run --rm ctl status
docker compose --env-file deploy/prediction/.env \
  -f deploy/prediction/compose.yaml -f deploy/prediction/compose.combined.yaml \
  --profile operator run --rm ctl status
```

Print each public feed reference, then paste or scan it in SAC's **Add connection** flow:

```sh
docker compose --env-file deploy/copytrading/.env \
  -f deploy/copytrading/compose.yaml -f deploy/copytrading/compose.combined.yaml \
  --profile operator run --rm ctl reference
docker compose --env-file deploy/prediction/.env \
  -f deploy/prediction/compose.yaml -f deploy/prediction/compose.combined.yaml \
  --profile operator run --rm ctl reference
```

Exercise CopyTrading create, update, and cancel through its operator profile:

```sh
docker compose --env-file deploy/copytrading/.env \
  -f deploy/copytrading/compose.yaml -f deploy/copytrading/compose.combined.yaml \
  --profile operator run --rm ctl create --in 2h --note 'deployment check' \
  --key deployment-check \
  --term input_mint=So11111111111111111111111111111111111111112 \
  --term input_decimals=9 \
  --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term output_decimals=6 --term max_slippage_bps=50
docker compose --env-file deploy/copytrading/.env \
  -f deploy/copytrading/compose.yaml -f deploy/copytrading/compose.combined.yaml \
  --profile operator run --rm ctl update <proposal-id> --in 4h --note 'updated check' \
  --term input_mint=So11111111111111111111111111111111111111112 \
  --term input_decimals=9 \
  --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term output_decimals=6 --term max_slippage_bps=50
docker compose --env-file deploy/copytrading/.env \
  -f deploy/copytrading/compose.yaml -f deploy/copytrading/compose.combined.yaml \
  --profile operator run --rm ctl cancel <proposal-id>
```

Verify each revision and withdrawal appears in SAC. For Prediction, configure filters in its `.env`
and run one discovery cycle:

```sh
docker compose --env-file deploy/prediction/.env \
  -f deploy/prediction/compose.yaml -f deploy/prediction/compose.combined.yaml \
  --profile operator run --rm ctl poll
docker compose --env-file deploy/prediction/.env \
  -f deploy/prediction/compose.yaml -f deploy/prediction/compose.combined.yaml \
  --profile operator run --rm ctl discovery
```

Provider, Firebase, OAuth, and alternate Redis configuration are optional boundaries, not clean-host
prerequisites. See the [prediction guide](../demo-prediction/README.md),
[Firebase guide](../docs/guides/firebase.md), [OAuth guide](../docs/integrations/claude.md), and
[feed gateway guide](../feed-gateway/README.md) when enabling them.

## 6. Restart and recovery acceptance

Record `pair status`, both demo `status` outputs, and the two feed references. Then restart one
project at a time:

```sh
docker compose --env-file deploy/mcp/.env \
  -f deploy/mcp/compose.yaml -f deploy/mcp/compose.tls.yaml restart mcp-server
docker compose --env-file deploy/feed/.env \
  -f deploy/feed/compose.yaml -f deploy/feed/compose.combined.yaml restart feed-gateway
docker compose --env-file deploy/copytrading/.env \
  -f deploy/copytrading/compose.yaml -f deploy/copytrading/compose.combined.yaml restart copytrading
docker compose --env-file deploy/prediction/.env \
  -f deploy/prediction/compose.yaml -f deploy/prediction/compose.combined.yaml restart prediction
```

The MCP server ID, pairing, requests, and results must survive. Feed publications must survive.
Each demo's ID, proposals, revisions, and outbox must survive independently. A restarted stream may
reconcile from the authoritative snapshot without duplicating a request.

MCP ownership is enforced by an operating-system SQLite lock in the same local volume as the main
database. Its persistent `*.mcp-server-owner.sqlite` file is normal and must not be deleted. After
`SIGKILL`, a host restart, or a container replacement, the kernel releases the lock and the one new
owner starts immediately. A second live owner fails closed. Use local Docker volumes; SQLite locks
on NFS/SMB or other network filesystems are unsupported.

To add a component later, copy only its `.env`, register only its identity if needed, and start only
its Compose project. Do not reset an existing volume or recreate the other projects.

## 7. Back up, replace, and roll back

Inventory before changing an existing deployment:

```sh
docker volume ls
docker volume inspect <exact-volume-name>
```

Stop the one writer and archive one explicit volume. Do not use a wildcard, broad prune,
`down -v`, or `--remove-orphans` to resolve a naming warning:

```sh
mkdir -p backups
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml stop mcp-server
docker run --rm -v seeker-agent-connect-mcp_mcp-data:/from:ro \
  -v "$PWD/backups:/backup" alpine:3.22 \
  sh -c 'cd /from && tar czf /backup/mcp-before-upgrade.tgz .'
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml start mcp-server
```

Use the actual selected volume if it differs. Restore only while the writer is stopped, retain
UID/GID `10001:10001`, run SQLite `PRAGMA integrity_check`, and verify the stable identity before
resuming traffic. Rollback means the old image plus its matching pre-upgrade archive; never point an
older binary at a schema a newer binary migrated.

| Owner | Existing physical volume | Canonical selection |
| --- | --- | --- |
| MCP, current | `seeker-agent-connect-mcp_mcp-data` | default |
| MCP, retired `gateway/` | `seeker-agent-wallet_sidecar-data` | `MCP_VOLUME_NAME=...`, `DATABASE_PATH=/data/sidecar.db` |
| MCP, retired combined | `seeker-agent-wallet-server_sidecar-data` | same variables |
| feed, current | `seeker-broadcast_broadcast-data` | default |
| feed, retired combined | `seeker-agent-wallet-server_broadcast-data` | `BROADCAST_VOLUME_NAME=...` |
| CopyTrading, current | `seeker-publisher_publisher-data` | default, `/data/publisher.db` |
| CopyTrading, retired combined | `seeker-agent-wallet-server_copytrading-data` | set volume and `/data/copytrading.db` |
| Prediction, current | `seeker-prediction_prediction-data` | default |
| Prediction, retired combined | `seeker-agent-wallet-server_prediction-data` | `PREDICTION_VOLUME_NAME=...` |

Feed Caddy's current certificate volumes are `seeker-broadcast_proxy-data` and
`seeker-broadcast_proxy-config`. The older combined lineage used
`seeker-agent-wallet-server_gateway-caddy-data` and
`seeker-agent-wallet-server_gateway-caddy-config`. The optional unary direct-ingress project uses
`seeker-agent-wallet_gateway-data` and `seeker-agent-wallet_gateway-config`. Select old certificate
volumes explicitly only after inspection; they are ACME identities, not application data.

If two non-empty candidates exist, they are two identities. Back up both and choose one; never
merge SQLite files. Before the first SEE-137 MCP start, stop every pre-SEE-137 server that could
open the database. The one-time compatibility guard then makes rollback binaries refuse instead of
silently becoming a second writer.

Replace only the intended service with `up -d --build --no-deps <service>`. Removing a container
with `docker compose down` leaves its explicitly named volume intact.

## 8. Troubleshooting and completion record

- `address already in use`: compare the port table with `docker compose config`; direct is 8443
  and feed read is 8090 in the combined layout.
- unhealthy MCP container: run `node mcp-server/dist/healthcheck.js` inside it. Check the public
  hostname, certificate chain, mounted key, and optional health CA path.
- MCP works but the phone never becomes Live: confirm native TLS negotiated h2 and that no HTTP/1
  proxy terminates the direct origin.
- demo publication gets 404: use the combined overlay, or point `PUBLISHER_PUBLISH_URL` at the
  authenticated publisher listener rather than a read-only address.
- feed snapshot works but Live does not: compare the three `BROADCAST_STREAM_*` values and inspect
  Centrifugo health/logs.
- `database is already owned`: find and stop the other live process/container. Do not delete the
  ownership database; a crash-stale PID file is no longer used.
- a volume warning appears: stop and inspect both exact volumes before choosing. Never delete one
  merely to silence Compose.

For every production deployment, record DNS and certificate validation, resolved Compose config,
container health, h2 negotiation, MCP create/result/cancel, feed create/update/cancel, Prediction
poll, SAC subscription, and the restart checks above. Automated and local evidence for SEE-137 is
kept in [`docs/testing/see-137.md`](../docs/testing/see-137.md); unavailable live checks remain
**NOT RUN** with their concrete blocker.
