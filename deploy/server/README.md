# One-server Stage 7.1 deployment

This directory is the complete single-Linux-server entry point. It has two independently operable
parts:

1. **App infrastructure:** the shared broadcast gateway, Centrifugo, Redis, Firebase relay inside
   the gateway, public HTTPS/HTTP2 entry point, and local operator CLI.
2. **Demo servers:** the existing CopyTrading and Jupiter Prediction publisher templates.

The optional owner-operated sidecar remains a third, independent service. It still owns its direct
pairing, credential, request database, MCP adapter and TLS/HTTP2 update listener. An independent
server may instead use SEE-109's gateway-private invitation/device path; that does not alter or
replace the direct sidecar.

For the one layout of a small server that pulls published images and is reached through Tailscale
Funnel, [`GUIDE.md`](GUIDE.md) is the same material as a start-to-finish runbook, with removal.

All commands below run from a repository checkout's `deploy/server` directory. The detailed
[server-development guide](../../docs/guides/server-development.md) remains the contract reference;
it is not required to assemble this deployment.

## Files and combinations

| Command files | What runs |
| --- | --- |
| `-f compose.yaml` | Part 1 only: gateway, HTTPS/HTTP2 proxy, Centrifugo and Redis. |
| `-f compose.yaml -f compose.demos.yaml` | Part 1 plus both Part 2 publishers and the CopyTrading trader UI. Start/stop only the five demo runtime services with `--no-deps`. |
| `-f compose.yaml -f compose.tailscale.yaml` | Part 1 on a Tailscale MagicDNS name behind Funnel in TCP mode, for a server with no DNS name of its own ([section 7](#7-part-1-behind-tailscale-funnel)). Goes between `compose.yaml` and `compose.demos.yaml` in every command when the demos run. |
| `-f compose.direct.yaml` | Optional direct sidecar only. It can run beside either combination and keeps the original Compose project and volume name. |

`gateway-ctl`, `copytrading-ctl` and `prediction-ctl` are one-shot local tools under the `operator`
profile; ordinary `up` does not start them. Never run `docker compose down -v`: `-v` deletes the
SQLite volumes that hold publisher identities, publications, requests and pairing state.

The address layout is deliberate:

| Address | Audience and protocol |
| --- | --- |
| `https://feeds.example.com:443` | Phones: public feed reads, temporary invitation pages, invitation/device Connect RPCs and the one Centrifugo unidirectional gRPC stream over HTTP/2. |
| `https://feeds.example.com/trader` | Hackathon judges: password-gated CopyTrading trader HTML (SEE-126). Not `/v1`. Behind Funnel, the same path on the MagicDNS origin, including the Funnel port when it is not 443. |
| `http://broadcast:8082` | Demo containers only: authenticated `PublisherService` on the private `publisher-ingress` Docker network. It never appears in a manifest. |
| `127.0.0.1:8092`, `127.0.0.1:8094` | Host operator only: CopyTrading and Prediction control APIs. Both require their own API token. The trader UI holds that token and calls loopback; the browser never sees it. |
| `127.0.0.1:8443` / public TCP `:10000` | Optional direct sidecar: TLS terminates in the sidecar; a raw TCP forward preserves its private update stream. Do not put the trader UI on `:10000`. |

Redis, the Centrifugo API, the gateway's three implementation listeners, Caddy administration and
the gateway operator CLI have no host port. The public Caddy route does not expose
`PublisherService`, health or administration.

# Part 1: Deploy the app infrastructure

## 1. Prepare the host and checkout

Use one Linux host with:

- Docker Engine 24 or newer and Docker Compose v2;
- Git, `curl`, `openssl`, `jq`, `uuidgen` and at least 2 GB free memory while building;
- TCP 80 and 443 reachable from the internet;
- a DNS A/AAAA record, such as `feeds.example.com`, already pointing at the host — or, with
  neither a name nor open ports, Tailscale Funnel: read
  [section 7](#7-part-1-behind-tailscale-funnel) before step 2;
- enough durable disk for Docker volumes and backups.

Clone the tracked Stage 7.1 branch or deploy the reviewed commit you intend to run:

```sh
sudo mkdir -p /opt/seeker-agent-wallet
sudo chown "$USER" /opt/seeker-agent-wallet
git clone https://github.com/BrRenat/SeekerAgentWallet.git /opt/seeker-agent-wallet/repo
cd /opt/seeker-agent-wallet/repo
git checkout superset/feat/see-85
git pull --ff-only
cd deploy/server
```

With a checkout, Compose builds the unchanged `broadcast/Dockerfile` and `publisher/Dockerfile`.
The pinned `broadcast/centrifugo.yaml` and the two publisher Caddy allow-lists are mounted through
symlinks in this directory (`centrifugo.yaml`, `Caddyfile.copytrading`, `Caddyfile.prediction`), so
there is one copy of each.

### Without a checkout: published images and this folder alone

A small server need not clone or build anything. Build both images for the server's architecture
on any machine with Docker — the compiler runs natively and cross-compiles, so an Apple silicon Mac
produces `linux/amd64` without emulation — and push them as tags of one repository:

```sh
# from the repository root
docker buildx build --platform linux/amd64 -f broadcast/Dockerfile \
  -t YOUR-USER/seeker-agent-connect:broadcast-v1 --load .
docker buildx build --platform linux/amd64 -f publisher/Dockerfile \
  -t YOUR-USER/seeker-agent-connect:publisher-v1 --load .
docker push YOUR-USER/seeker-agent-connect:broadcast-v1
docker push YOUR-USER/seeker-agent-connect:publisher-v1
```

Copy this directory with `-L`, which turns the three symlinks into real files; `--exclude` keeps a
later re-sync from touching the server's own state:

```sh
rsync -avL --exclude '.env*' --exclude tls --exclude secrets --exclude backups \
  deploy/server/ SERVER:seeker-agent-connect/
```

On the server, `docker login` if the repository is private, set the two tags in `.env`, and use
`pull` and `--no-build` wherever this guide says `build` or `--build`:

```dotenv
BROADCAST_IMAGE=docker.io/YOUR-USER/seeker-agent-connect:broadcast-v1
PUBLISHER_IMAGE=docker.io/YOUR-USER/seeker-agent-connect:publisher-v1
```

```sh
docker compose -f compose.yaml pull
docker compose -f compose.yaml up -d --no-build
```

A plain `rsync -a` or a copy that keeps symlinks leaves three dangling links, and Compose fails on
the mount; `ls -l centrifugo.yaml` on the server must show a regular file. The `grpcurl` stream
check in Part 2 reads protos from `third_party/`, so run it from a machine that has the checkout.

No public broadcast or publisher image is assumed. The default tags are local and are built from
the checked-out source:

```sh
docker compose build broadcast gateway-ctl
docker compose pull gateway-proxy centrifugo redis
```

For an offline server, build as in the section above, then `docker save`, copy the archive and
`docker load` instead of pushing. Caddy, Centrifugo and Redis remain pinned in `compose.yaml` and
must also be present.

## 2. Configure the gateway

```sh
cd /opt/seeker-agent-wallet/repo/deploy/server
cp .env.template .env
chmod 0600 .env
mkdir -p secrets/broadcast backups
chmod 0700 secrets secrets/broadcast backups
sudo chown 10001:10001 secrets/broadcast
openssl rand -hex 32
openssl rand -hex 32
```

A mounted secrets directory belongs to uid 10001, the account every image here runs as: a `0700`
directory of the operator's own is one the container cannot enter, whatever the file inside allows.

Put the two different generated values in `CENTRIFUGO_API_KEY` and `CENTRIFUGO_TOKEN_KEY`. Set
`BROADCAST_DOMAIN` and `ACME_EMAIL`. Do not add a scheme to `BROADCAST_DOMAIN`; Compose derives the
only public origin as `https://<domain>`.

Validate the complete base model before starting it:

```sh
docker compose -f compose.yaml config --quiet
docker compose -f compose.yaml config --services
```

The services are `broadcast`, `gateway-proxy`, `centrifugo` and `redis`; `gateway-ctl` is listed
only as an operator-profile service. This command requires no demo environment file, publisher
credential, sidecar token or sidecar certificate.

## 3. Choose Firebase or the supported no-Firebase mode

The default empty `BROADCAST_PUSH_CREDENTIALS` keeps push off. Unary reads and foreground streaming
still work; a closed app simply receives no immediate feed invalidation hint.

To enable feed hints, use the Firebase project built into the Android app, enable FCM HTTP v1,
create a service account with only the Firebase Cloud Messaging API Admin role, and install its JSON
key:

```sh
sudo install -o 10001 -g 10001 -m 0400 /secure/source/service-account.json \
  secrets/broadcast/fcm-service-account.json
```

Then set:

```dotenv
BROADCAST_PUSH_CREDENTIALS=/run/secrets/fcm-service-account.json
BROADCAST_PUSH_ENVIRONMENT=sandbox
```

Use `production` only for a production gateway. Compose mounts `secrets/broadcast` read-only into
`broadcast` and nowhere else. Centrifugo, Redis, Caddy, both demos and the optional sidecar cannot
read this key. The sidecar, if enabled, has its own `secrets/sidecar` directory and credential.

## 4. Start and inspect Part 1

```sh
docker compose -f compose.yaml up -d --build
docker compose -f compose.yaml ps
docker compose -f compose.yaml logs --tail=100 broadcast gateway-proxy centrifugo redis
docker compose -f compose.yaml exec gateway-proxy \
  wget -qO- http://127.0.0.1:8081/healthz
```

Expected: all four services are running, Redis and Centrifugo become healthy, the proxy healthcheck
becomes healthy, and the private health request prints `{"status":"ok"}`. Caddy obtains and stores
the public certificate in `gateway-caddy-data`; certificate issuance fails until DNS and both public
ports are correct.

From a different internet connection, verify the certificate, ALPN and a real unary request:

```sh
DOMAIN=feeds.example.com
openssl s_client -connect "$DOMAIN:443" -servername "$DOMAIN" -alpn h2 </dev/null 2>/dev/null \
  | grep 'ALPN protocol'
curl --http2 -sS -o /tmp/gateway-answer.json \
  -w 'unary HTTP/%{http_version} status %{http_code}\n' \
  "https://$DOMAIN/seekervault.gateway.v1.FeedService/GetServerManifest" \
  -H 'Content-Type: application/json' \
  -d '{"serverId":"00000000-0000-4000-8000-000000000000"}'
cat /tmp/gateway-answer.json
```

Expected: `ALPN protocol: h2`, `HTTP/2`, and a structured `no_such_server` error for the deliberately
unknown ID. That proves public TLS plus unary routing, not delivery. A real stream needs a published
manifest and ticket; the end-to-end `grpcurl` publication check is in Part 2 and is mandatory before
calling streaming verified. A unary success or ALPN line alone is not a stream test.

## 5. Operate, update and back up Part 1

Useful commands:

```sh
docker compose -f compose.yaml ps
docker compose -f compose.yaml logs -f broadcast gateway-proxy centrifugo redis
docker compose -f compose.yaml run --rm gateway-ctl list
docker compose -f compose.yaml restart gateway-proxy
```

For an update, first record the commit, build or load the matching image, validate, and replace only
the gateway and proxy. The broker and Redis stay up; open streams reconnect and recover or read a
snapshot:

```sh
git pull --ff-only
docker compose -f compose.yaml build broadcast gateway-ctl
docker compose -f compose.yaml config --quiet
docker compose -f compose.yaml up -d --no-deps broadcast gateway-proxy
```

The authoritative state is `broadcast-data`. Caddy's ACME account and certificates are in
`gateway-caddy-data`. Redis is intentionally not persistent: losing it costs listeners one
authoritative snapshot, not a document. Take a cold, consistent backup without deleting volumes:

```sh
mkdir -p backups
docker compose -f compose.yaml stop gateway-proxy broadcast
docker run --rm \
  -v seeker-agent-wallet-server_broadcast-data:/from:ro \
  -v "$PWD/backups:/to" alpine:3.22 \
  tar -C /from -czf /to/broadcast-data.tgz .
docker run --rm \
  -v seeker-agent-wallet-server_gateway-caddy-data:/from:ro \
  -v "$PWD/backups:/to" alpine:3.22 \
  tar -C /from -czf /to/gateway-caddy-data.tgz .
docker compose -f compose.yaml up -d --no-deps broadcast gateway-proxy
```

Restore only while those services are stopped, into the same named volumes, from a backup whose
commit and environment you recorded. Preserve file ownership. Test the unary and stream paths again
afterwards. The same stop-and-tar pattern applies independently to `copytrading-data`,
`prediction-data` and `sidecar-data`.

To stop public infrastructure without deleting data:

```sh
docker compose -f compose.yaml stop
```

## 6. Optional direct sidecar and non-destructive upgrade

The sidecar is not a gateway service. It retains the original Linux host-network layout: it binds
`127.0.0.1:8443`, terminates its own TLS, and Tailscale Funnel or another TCP-level forward exposes
it. This preserves `UpdateService.Subscribe` over HTTP/2, pairing, stored private requests,
`PrepareRequest`/`SubmitResult`, optional MCP and direct FCM registration.

The default sidecar image is the existing published `linux/amd64` image. On another architecture,
or to deploy this checkout exactly, build the existing Dockerfile from the repository root and set
`SIDECAR_IMAGE` in the infrastructure `.env`:

```sh
cd /opt/seeker-agent-wallet/repo
docker build -f sidecar/Dockerfile -t seeker-agent-wallet/sidecar:local .
cd deploy/server
```

For a new optional sidecar:

```sh
cp direct.env.template .env.direct
chmod 0600 .env.direct
mkdir -p tls secrets/sidecar
chmod 0755 tls
chmod 0700 secrets/sidecar
sudo chown 10001:10001 secrets/sidecar
```

`tls` stays enterable: the key's own mode protects it, and its readers are not the directory's owner.

Edit `.env.direct`. Keep `SERVER_DOMAIN`, `SIDECAR_PUBLIC_URL` and `MCP_ALLOWED_HOSTS` consistent;
make `MCP_TOKEN` and `PHONE_TOKEN` different random values. `MCP_ENABLED=false` keeps the private
phone workflow and makes MCP plus both OAuth metadata routes return 404. It does not route MCP
through the broadcast gateway.

Obtain a certificate and expose the raw TLS socket:

```sh
sudo ./tls-from-tailscale.sh
sudo tailscale funnel --bg --tcp=10000 tcp://localhost:8443
tailscale funnel status
docker compose -f compose.direct.yaml up -d sidecar
docker compose -f compose.direct.yaml logs -f sidecar
```

Use the `PUBLIC_PORT` and `SIDECAR_PORT` values from `.env.direct` if they differ. The log must say
the production updates are served as gRPC over HTTP/2 at the public sidecar origin. Verify it from
outside with `openssl ... -alpn h2` and `curl https://<sidecar-domain>:10000/healthz`, then pair:

```sh
docker compose -f compose.direct.yaml exec sidecar node sidecar/dist/pairing/cli.js
docker compose -f compose.direct.yaml exec sidecar node sidecar/dist/pairing/cli.js status
```

An agent uses `https://<sidecar-domain>:10000/mcp` with `Authorization: Bearer <MCP_TOKEN>`; it does
not use the feed domain. Without a token that path answers 401 when MCP is enabled. With
`MCP_ENABLED=false`, `/mcp` and both OAuth metadata paths answer 404 while pairing, phone APIs,
updates, push and stored requests continue unchanged.

For direct-request push, install its service account as
`secrets/sidecar/fcm-service-account.json`, owned by uid 10001 and mode 0400, set `FCM_PROJECT_ID`
in `.env.direct`, and recreate only `sidecar`.

An existing `deploy/server` installation already uses Compose project
`seeker-agent-wallet-server` and volume `sidecar-data`. Upgrade it without moving or copying that
volume:

```sh
docker compose stop sidecar
cp .env .env.direct
# Add SIDECAR_PUBLIC_URL=https://SERVER_DOMAIN:PUBLIC_PORT and
# MCP_ALLOWED_HOSTS=SERVER_DOMAIN to .env.direct, using their actual values.
cp .env.template .env
# Configure Part 1 in the new .env, or leave Part 1 stopped.
docker volume inspect seeker-agent-wallet-server_sidecar-data
docker compose -f compose.direct.yaml up -d sidecar
```

The recreated container opens the same database and migrates it forward. Do not rename the Compose
project, declare a new external volume, run `down -v`, or copy a live SQLite file. Renew the
certificate weekly with `tls-from-tailscale.sh`; it restarts only this sidecar when the certificate
changes.

## 7. Part 1 behind Tailscale Funnel

For a server whose only public name is its Tailscale MagicDNS name, `<node>.<tailnet>.ts.net`.
`compose.yaml` alone cannot serve it: Caddy's ACME challenges need a DNS name of the operator's own
and public 80/443, and Funnel's ordinary HTTPS mode puts an HTTP/1.1 hop in front of the gRPC
stream. `compose.tailscale.yaml` keeps every route and changes only how TLS gets there:

| | |
| --- | --- |
| Public origin | `https://<node>.<tailnet>.ts.net`, Funnel port 443. The direct sidecar, when it runs, keeps `:10000` on the same name. |
| In front | `tailscale funnel --tcp=443 tcp://localhost:9443`, a raw TCP pipe. TLS and HTTP/2 end in Caddy, so unary reads and the Centrifugo stream behave as they do behind a public 443. |
| Certificate | `tailscale cert` through `tls-from-tailscale.sh`, into `./tls` — the same pair the sidecar reads, because a node has one name. Caddy here is root with no capability to override file permissions, so when `.env`'s `BROADCAST_DOMAIN` is the certificate's name the script leaves the key `0640`, uid 10001, group root. |
| Host ports | Loopback only: `.env` moves Caddy's bindings off the public interface. Nothing listens on the server's public address. |

The tailnet needs MagicDNS, HTTPS Certificates, and the `funnel` node attribute for this node, and
Funnel's port 443 on it must be free (`tailscale funnel status`, `tailscale serve status`).

In step 2's `.env`:

```dotenv
BROADCAST_DOMAIN=<node>.<tailnet>.ts.net
# Required by compose.yaml and unused here: no ACME account is created.
ACME_EMAIL=unused@example.com
GATEWAY_HTTP_BIND=127.0.0.1:9080
GATEWAY_HTTPS_BIND=127.0.0.1:9443
```

If `tailscale funnel` answers `cannot serve TCP; already serving web on 443`, the node's 443 belongs
to an HTTPS `serve`. Either remove that (`tailscale serve status` names it; `tailscale funnel
--https=443 off`), or keep it and add `GATEWAY_PUBLIC_PORT=8443` to `.env`, use `--tcp=8443` in the
Funnel command below, and write the origin as `https://<node>.<tailnet>.ts.net:8443` everywhere it
appears, `PUBLISHER_GATEWAY_URL` included.

`9443` is any free loopback port that is not the sidecar's `8443`; `9080` answers nothing and is
bound only because the base file publishes the pair. Then the certificate, the forward, and the
stack, with the extra file in every Part 1 command of this guide:

```sh
mkdir -p tls && chmod 0755 tls
sudo ./tls-from-tailscale.sh
ls -ln tls                     # privkey.pem: 10001 0, -rw-r-----
docker compose -f compose.yaml -f compose.tailscale.yaml config --quiet
docker compose -f compose.yaml -f compose.tailscale.yaml up -d --build
sudo tailscale funnel --bg --tcp=443 tcp://localhost:9443
tailscale funnel status
```

With an existing sidecar on the same node, run the script once more after `.env` names the domain:
the certificate is unchanged, nothing restarts, and the key gains its group bit. The weekly cron
entry then renews for both and restarts `gateway-proxy` as well as the sidecar when the certificate
changed (Caddy's administration endpoint is off, so a reload is a restart; streams reconnect).

Step 4's outside checks apply unchanged with `DOMAIN=<node>.<tailnet>.ts.net`. For the demos, the
file order is `-f compose.yaml -f compose.tailscale.yaml -f compose.demos.yaml`, and
`PUBLISHER_GATEWAY_URL` is `https://<node>.<tailnet>.ts.net`. In step 6 of Part 2, `--add-host`
still reaches Caddy only if the name resolves to where it listens: replace `host-gateway` and `:443`
by running `grpcurl` from outside the server against the public name, which is the stronger test.

What this layout gives up: Funnel hands Caddy every connection from one local address, so
`BROADCAST_READ_RATE`/`BROADCAST_READ_BURST` become one bucket shared by all readers rather than one
per caller. Raise them for more than a handful of phones, or use a DNS name. Funnel also caps
bandwidth; it is a demonstration entry point, not a production one.

To remove the public exposure and leave the stack running on loopback:

```sh
sudo tailscale funnel --tcp=443 tcp://localhost:9443 off
```

# Part 2: Deploy the demo servers

Part 2 assumes Part 1 is healthy. It starts the existing two binaries from `publisher/Dockerfile`;
it adds no trading logic. Both examples are explicitly `sandbox`: proposals and live Prediction
market discovery are real data, but execution on the phone is simulated. Sandbox is not Jupiter
devnet, and none of these commands signs a transaction or spends funds.

## 1. Create two identities and the CopyTrading secrets

```sh
cd /opt/seeker-agent-wallet/repo/deploy/server
cp copytrading.env.template .env.copytrading
cp prediction.env.template .env.prediction
chmod 0600 .env.copytrading .env.prediction
mkdir -p secrets/copytrading secrets/prediction
chmod 0700 secrets/copytrading secrets/prediction
sudo chown 10001:10001 secrets/copytrading secrets/prediction

COPYTRADING_ID=$(uuidgen | tr 'A-Z' 'a-z')
PREDICTION_ID=$(uuidgen | tr 'A-Z' 'a-z')
sed -i "s/^PUBLISHER_SERVER_ID=.*/PUBLISHER_SERVER_ID=$COPYTRADING_ID/" .env.copytrading
sed -i "s/^PUBLISHER_SERVER_ID=.*/PUBLISHER_SERVER_ID=$PREDICTION_ID/" .env.prediction
sed -i 's#^PUBLISHER_GATEWAY_URL=.*#PUBLISHER_GATEWAY_URL=https://feeds.example.com#' \
  .env.copytrading .env.prediction
```

Register each ID locally. The gateway prints each credential once; these commands capture it
without putting it in shell history or the demo environment files:

```sh
COPY_REG=$(mktemp)
PREDICTION_REG=$(mktemp)
trap 'rm -f "$COPY_REG" "$PREDICTION_REG"' EXIT

docker compose -f compose.yaml run --rm gateway-ctl register \
  --server "$COPYTRADING_ID" --label "single-host CopyTrading demo" >"$COPY_REG"
docker compose -f compose.yaml run --rm gateway-ctl register \
  --server "$PREDICTION_ID" --label "single-host Prediction demo" >"$PREDICTION_REG"

sed -n '1,3p' "$COPY_REG"
sed -n '1,3p' "$PREDICTION_REG"
awk 'length($0)==43 && $0 ~ /^[A-Za-z0-9_-]+$/ {print; exit}' "$COPY_REG" \
  | sudo install -o 10001 -g 10001 -m 0400 /dev/stdin \
  secrets/copytrading/broadcast-credential
awk 'length($0)==43 && $0 ~ /^[A-Za-z0-9_-]+$/ {print; exit}' "$PREDICTION_REG" \
  | sudo install -o 10001 -g 10001 -m 0400 /dev/stdin \
  secrets/prediction/broadcast-credential
rm -f "$COPY_REG" "$PREDICTION_REG"
trap - EXIT

openssl rand -base64 32 | sudo install -o 10001 -g 10001 -m 0400 /dev/stdin \
  secrets/copytrading/api-token
openssl rand -base64 32 | sudo install -o 10001 -g 10001 -m 0400 /dev/stdin \
  secrets/prediction/api-token
openssl rand -base64 32 | sudo install -o 10001 -g 10001 -m 0400 /dev/stdin \
  secrets/copytrading/admin-session-secret
sudo install -o 10001 -g 10001 -m 0400 /dev/null \
  secrets/copytrading/admin-passwords
```

Mint one named bcrypt line per judge. The helper never stores the password in the clear; append
its stdout to the file. Deleting a line revokes that name on the next request, including an
already-open session:

```sh
printf '%s\n' "$JUDGE_PASSWORD" | docker compose -f compose.yaml -f compose.demos.yaml \
  run --rm --no-deps copytrading-pass hash judge1 \
  | sudo tee -a secrets/copytrading/admin-passwords >/dev/null
sudo chown 10001:10001 secrets/copytrading/admin-passwords
sudo chmod 0400 secrets/copytrading/admin-passwords
```

`copytrading-pass` is an operator-profile tool, like `copytrading-ctl`. Recreate the trader UI after
changing the password file if it was already running; mtime is rechecked on every request, so a
deleted line takes effect without a recreate.

The gateway stores only hashes of its grants. Each template reads its gateway grant and API token
from its own read-only directory. Neither directory is mounted into the other demo, and neither
demo receives the Firebase service account.

Validate the combined model. This is the first command that needs the two demo env files:

```sh
docker compose -f compose.yaml -f compose.demos.yaml config --quiet
```

## 2. Configure Prediction discovery

The template starts with a bounded, keyless example: open Polymarket crypto markets closing from
one hour to thirty days away, at most four listing pages and three open proposals. Edit
`.env.prediction` to narrow `PREDICTION_CATEGORIES`, `PREDICTION_TAGS`,
`PREDICTION_KEYWORDS`, close-time bounds or `PREDICTION_MOST_OPEN` before a public demo.

An empty `PREDICTION_API_KEY_FILE` uses Jupiter's keyless endpoint. For a keyed endpoint, install
the key as `secrets/prediction/prediction-api-key`, mode 0400/uid 10001, and set
`PREDICTION_API_KEY_FILE=/run/secrets/prediction-api-key`. A provider key never belongs in the
gateway or CopyTrading container.

`PREDICTION_STATE=any` is sandbox-only. Production refuses it. Changing
`PUBLISHER_ENVIRONMENT=production` is a deliberate new deployment with a separately stamped
database, not a way to turn this demo into devnet trading.

## 3. Start both demos without recreating infrastructure

```sh
docker compose -f compose.yaml -f compose.demos.yaml build \
  copytrading copytrading-ctl copytrading-admin copytrading-pass \
  prediction prediction-ctl
docker compose -f compose.yaml -f compose.demos.yaml up -d --no-deps \
  copytrading copytrading-proxy copytrading-admin \
  prediction prediction-proxy
docker compose -f compose.yaml -f compose.demos.yaml ps
docker compose -f compose.yaml -f compose.demos.yaml logs --tail=100 \
  copytrading copytrading-proxy copytrading-admin prediction prediction-proxy
```

Expected from each publisher: `publishing as this server`, a
`seekervault://feed?...gateway=https%3A%2F%2Ffeeds.example.com...` line, `the manifest is published`,
and its API listening. The URL in the reference must be the external gateway origin. The logs may
name `http://broadcast:8082` only as `publishing_to`; that is the private container route.

Check each API and the Prediction cycle through the shipped CLI. The token is placed in the
one-shot container environment and is not an argument or committed file:

```sh
COPYTRADING_API_TOKEN=$(sudo cat secrets/copytrading/api-token) \
  docker compose -f compose.yaml -f compose.demos.yaml run --rm --no-deps \
  copytrading-ctl status
PREDICTION_API_TOKEN=$(sudo cat secrets/prediction/api-token) \
  docker compose -f compose.yaml -f compose.demos.yaml run --rm --no-deps \
  prediction-ctl poll
PREDICTION_API_TOKEN=$(sudo cat secrets/prediction/api-token) \
  docker compose -f compose.yaml -f compose.demos.yaml run --rm --no-deps \
  prediction-ctl discovery
```

`discovery` reports the filters, cycle outcome, pages/events considered, matched/skipped counts and
tracked markets. Provider failure is a failed or partial cycle and closes no proposal.

The trader UI is HTML on the **existing** gateway origin, path `/trader`. Recreate the gateway
proxy after this checkout so Caddy has that route, then open:

```
https://$DOMAIN/trader
```

Behind Tailscale Funnel, include the Funnel port when it is not 443
(`https://<node>.<tailnet>.ts.net:8443/trader`). Judges do not install Tailscale. The session cookie
is `HttpOnly`, `Secure`, `SameSite=Strict`, path `/trader`, so it is not sent to feed RPCs. Stopping
`copytrading-admin` leaves the feed and the loopback API intact. Do not use `compose.public.yaml`
and do not publish `/v1` on this origin.

## 4. Obtain feed references and verify manifests externally

```sh
COPY_REFERENCE=$(COPYTRADING_API_TOKEN=$(sudo cat secrets/copytrading/api-token) \
  docker compose -f compose.yaml -f compose.demos.yaml run --rm --no-deps \
  copytrading-ctl reference)
PREDICTION_REFERENCE=$(PREDICTION_API_TOKEN=$(sudo cat secrets/prediction/api-token) \
  docker compose -f compose.yaml -f compose.demos.yaml run --rm --no-deps \
  prediction-ctl reference)
printf '%s\n%s\n' "$COPY_REFERENCE" "$PREDICTION_REFERENCE"
```

Both references are public and contain no credential. From another machine, read the manifest that
the phone will read:

```sh
DOMAIN=feeds.example.com
curl --http2 -sS "https://$DOMAIN/seekervault.gateway.v1.FeedService/GetServerManifest" \
  -H 'Content-Type: application/json' \
  -d "{\"serverId\":\"$COPYTRADING_ID\"}" | jq .
curl --http2 -sS "https://$DOMAIN/seekervault.gateway.v1.FeedService/GetServerManifest" \
  -H 'Content-Type: application/json' \
  -d "{\"serverId\":\"$PREDICTION_ID\"}" | jq .
```

Expected: mode `SERVER_MODE_GATEWAY_FEED`, channel `server/<same ID>`, environment sandbox, the
matching Jupiter plugin, and `gatewayUrl: https://<public domain>`. A Docker hostname or demo API
URL in either answer is a failed deployment.

## 5. Create, read and cancel a CopyTrading signal

The CLI is an HTTP client for the same authenticated API a strategy uses. This safe example names
a pair and a slippage ceiling; it contains no owner amount and triggers no execution:

```sh
COPY_JSON=$(COPYTRADING_API_TOKEN=$(sudo cat secrets/copytrading/api-token) \
  docker compose -f compose.yaml -f compose.demos.yaml run --rm --no-deps \
  copytrading-ctl create --in 2h --note "Single-host deployment verification" \
  --term input_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term input_decimals=6 --term input_symbol=USDC \
  --term output_mint=So11111111111111111111111111111111111111112 \
  --term output_decimals=9 --term output_symbol=SOL \
  --term max_slippage_bps=50)
printf '%s\n' "$COPY_JSON" | jq .
PROPOSAL_ID=$(printf '%s\n' "$COPY_JSON" | jq -r .request.identity.request_id)
```

The expected publication state is `published`. The programmatic path is the host-loopback API and
requires an explicit idempotency key:

```sh
COPYTRADING_TOKEN=$(sudo cat secrets/copytrading/api-token)
curl -sS http://127.0.0.1:8092/v1/status \
  -H "Authorization: Bearer $COPYTRADING_TOKEN" | jq .

EXPIRES_AT=$(date -u -d '+2 hours' '+%Y-%m-%dT%H:%M:%SZ')
IDEMPOTENCY_KEY="deployment-check-$(date -u '+%Y%m%dT%H%M%SZ')"
API_RESPONSE=$(mktemp)
curl -sS -o "$API_RESPONSE" -w 'API HTTP %{http_code}\n' \
  http://127.0.0.1:8092/v1/requests \
  -H "Authorization: Bearer $COPYTRADING_TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "$(jq -nc --arg expires "$EXPIRES_AT" \
    '{expires_at:$expires,note:"Authenticated API verification",terms:{input_mint:"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",input_decimals:"6",output_mint:"So11111111111111111111111111111111111111112",output_decimals:"9",max_slippage_bps:"50"}}')"
jq . "$API_RESPONSE"
rm -f "$API_RESPONSE"
```

Expect HTTP 201 when it was stored and published (202 means stored locally and pending gateway
delivery). Retrying the identical body with the identical key returns the same request; reusing the
key for different terms is refused.

Read through the public gateway, not through the demo:

```sh
curl --http2 -sS "https://$DOMAIN/seekervault.gateway.v1.FeedService/ListProposals" \
  -H 'Content-Type: application/json' \
  -d "{\"channel\":\"server/$COPYTRADING_ID\",\"pageSize\":10}" | jq .
```

Cancel through the same CLI and read again. Cancellation is a higher revision, not deletion:

```sh
COPYTRADING_API_TOKEN=$(sudo cat secrets/copytrading/api-token) \
  docker compose -f compose.yaml -f compose.demos.yaml run --rm --no-deps \
  copytrading-ctl cancel "$PROPOSAL_ID"
```

## 6. Prove real streaming through external TLS

Install `grpcurl` or use its container. First mint the anonymous, channel-scoped ticket through the
public gateway:

```sh
TICKET_JSON=$(curl --http2 -sS \
  "https://$DOMAIN/seekervault.gateway.v1.FeedService/GetStreamTicket" \
  -H 'Content-Type: application/json' \
  -d "{\"channels\":[\"server/$COPYTRADING_ID\"]}")
printf '%s\n' "$TICKET_JSON" | jq .
STREAM_TICKET=$(printf '%s\n' "$TICKET_JSON" | jq -r .ticket)
```

In terminal A, from `deploy/server`, open the actual unidirectional gRPC method. `--add-host` keeps
the same public TLS name while reaching this host without relying on provider hairpin routing:

```sh
docker run --rm -i \
  --add-host="$DOMAIN:host-gateway" \
  -v "$PWD/../../third_party/centrifugo:/protos:ro" \
  fullstorydev/grpcurl:v1.9.1 \
  -import-path /protos -proto centrifugal/centrifugo/unistream/unistream.proto \
  -d "{\"token\":\"$STREAM_TICKET\"}" \
  "$DOMAIN:443" \
  centrifugal.centrifugo.unistream.CentrifugoUniStream/Consume
```

It must stay open and print `connect` plus a positioned `subscribe` result. In terminal B, create a
second CopyTrading signal with the command in step 5. Terminal A must print a `pub` on
`feed:server/<ID>` with a nonzero offset. That is the streaming PASS: a real document crossed the
external certificate, HTTP/2 Caddy route and Centrifugo. Merely getting a unary answer, an ALPN
`h2`, a broker health result or a ticket is not a streaming pass.

## 7. Phone and Firebase checklist

Give the owner the two `seekervault://feed` references. A capable app build validates each manifest,
reads its snapshot from the gateway, opens the gateway stream in foreground, and subscribes to the
topic returned by `GetFeedTopics`. It never calls ports 8092/8094 or learns `broadcast:8082`.

The Add connection screen accepts each reference as scanned, typed or pasted text. It shows the
gateway, server ID and public/no-credential boundary before it stores anything. There is no Android
intent filter for the URI, so tapping it outside the app is not a substitute for this flow.

Perform these separately on a physical device:

1. Add both references and confirm the shown source, sandbox environment and proposals.
2. Keep the app foregrounded, publish once, and confirm stream delivery without Refresh.
3. Background or terminate the app, publish once, and confirm one generic feed notification.
4. Tap it and confirm the app performs an authoritative read and opens the read-only current feed.
5. Deny notifications and repeat: synchronization may still occur; no wallet opens automatically.
6. Repeat after process death and a device reboot. Record PASS, FAIL or NOT RUN for each case.

With Firebase disabled, `GetFeedTopics` returns `no_push`; the foreground stream and manual read
remain the expected behavior.

### SEE-107 device record — 2026-09-18

A physical Seeker (`SM02G4061936191`) was attached and the public gateway was reachable. The two
deployed CopyTrading and Prediction feed references/server IDs were not available in this checkout
or the SEE-106 evidence, and this environment had no server SSH or publisher control-plane access
from which to obtain them. The debug APK also could not replace the differently signed app already
on the phone (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and its existing app/data was not uninstalled.
Because the references and a safely installable build are required by every line, the deployed-demo
run is recorded rather than inferred:

| Checklist line | Result |
| --- | --- |
| Add both references; confirm source, Sandbox and proposals | **NOT RUN** — deployed feed references/server IDs unavailable; debug APK signing did not match the installed app. |
| Foreground publish arrives without Refresh | **NOT RUN** — no deployed feed could be added. |
| Background/process-absent publish produces one generic notification | **NOT RUN** — no deployed feed could be added; no Firebase credential/deployment was available. |
| Notification tap performs an authoritative read and opens the feed | **NOT RUN** — no notification could be produced. |
| Notification denial preserves synchronization and opens no wallet | **NOT RUN** — no deployed feed could be added. |
| Process-death and reboot repeat | **NOT RUN** — no deployed feed could be added. |

This is no longer blocked by absent onboarding UI. Full evidence and the expected `no_push` case are
in [`docs/testing/see-107.md`](../../docs/testing/see-107.md).

## 8. Stop, update or back up only the demos

Stopping the demos leaves the gateway, reads and streams running, and leaves already published
documents in `broadcast-data`:

```sh
docker compose -f compose.yaml -f compose.demos.yaml stop \
  copytrading-admin copytrading-proxy copytrading prediction-proxy prediction
docker compose -f compose.yaml ps
```

Stopping only `copytrading-admin` leaves feeds and the CopyTrading API running. After the event,
stop the UI, rotate `secrets/copytrading/api-token` and the password file, then recreate the
publisher pair so the new token is loaded.

Update only the publisher image and recreate only demo containers. Recreate publisher + proxy +
trader UI together:

```sh
docker compose -f compose.yaml -f compose.demos.yaml build \
  copytrading copytrading-ctl copytrading-admin copytrading-pass \
  prediction prediction-ctl
docker compose -f compose.yaml -f compose.demos.yaml up -d --no-deps \
  copytrading copytrading-proxy copytrading-admin \
  prediction prediction-proxy
```

Their identities, idempotency records, discovery state and publication outboxes remain in the two
separate volumes. A restart republishes only pending identical documents; it does not mint a new
manifest revision merely because it restarted. Back up each volume while its own service is
stopped, then start that service again. The infrastructure need not stop.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| A Caddy container restarts with `exec /usr/bin/caddy: operation not permitted` | Its `cap_add: NET_BIND_SERVICE` was removed. The official binary carries that file capability and cannot be executed without it. |
| Behind Funnel: handshake fails or the certificate is refused | `ls -ln tls` must show the key as `10001 0` mode `0640` and the directory enterable; `BROADCAST_DOMAIN` must be the node's exact MagicDNS name; `tailscale funnel status` must show `tcp://…:443` forwarding to `GATEWAY_HTTPS_BIND`'s port. |
| Caddy cannot obtain a certificate | DNS A/AAAA, inbound 80/443, `BROADCAST_DOMAIN`, and `gateway-proxy` logs. Never disable phone certificate validation. |
| Unary works but `grpcurl` closes or never shows `subscribe` | The exact stream path in `Caddyfile`, external ALPN `h2`, Centrifugo health, ticket lifetime, and broker logs. Unary is not a substitute. |
| A `*-ctl` command says `127.0.0.1:8093` (or `8095`) `connection refused`, or a proxy turns unhealthy after a restart | The publisher was restarted without its proxy. A proxy shares its publisher's network namespace (`network_mode: service:…`), and a restarted publisher gets a new one while the old proxy stays in the dead one. Restart the pair: `restart copytrading copytrading-proxy` (likewise `prediction prediction-proxy`, `broadcast gateway-proxy`). Restarting a proxy alone is always safe. |
| Compose warns `Found orphan containers (…-sidecar-1)` | Expected: the sidecar belongs to the same project through `compose.direct.yaml`, which the command did not name. Never answer it with `--remove-orphans`; set `COMPOSE_IGNORE_ORPHANS=1` in `.env` to silence it. |
| Publisher logs `other_gateway` | `PUBLISHER_GATEWAY_URL` is not the gateway's own origin, character for character, port included. `docker inspect` the `broadcast` container for `BROADCAST_PUBLIC_URL`; behind Funnel on 8443 it needs `GATEWAY_PUBLIC_PORT=8443` in `.env` and a recreated `broadcast`. A refusal is not retried until the publisher restarts. |
| Publisher logs 404 while publishing | `PUBLISHER_PUBLISH_URL` was overridden or the demo is not on `publisher-ingress`; it must use `http://broadcast:8082`. |
| Gateway refuses `other_gateway` | `PUBLISHER_GATEWAY_URL` differs from `https://BROADCAST_DOMAIN`. Internal hostnames never belong there. |
| Demo API is unreachable remotely | Expected. It binds host loopback. Use SSH/VPN or the password-gated `/trader` page. Never publish `/v1` on the public origin. |
| `/trader` returns 502 | CopyTrading demo is not running, or `copytrading-admin` was not started with the publisher pair. Recreate `copytrading copytrading-proxy copytrading-admin` and `gateway-proxy` so Caddy has the route. |
| Prediction publishes nothing | Run `prediction-ctl discovery`; inspect `last_cycle` and `skipped_because`, then narrow or correct filters. Provider failure closes nothing. |
| Push topics return `no_push` | Supported no-Firebase mode, or the credential path/environment is unset. Check only the gateway's mount and logs. |
| Push fails but streaming works | Verify Firebase project match, uid/mode of the gateway service-account file and `BROADCAST_PUSH_ENVIRONMENT`. Publishers must still have no Firebase mount. |
| A demo restart loses state | Check the Compose project name and the `copytrading-data`/`prediction-data` volumes. Do not use `down -v`. |
| Existing direct pairing disappeared | The deployment used a different project/volume name. Stop before changing anything and restore use of `seeker-agent-wallet-server_sidecar-data`. |
| Port bind fails | Only gateway 80/443, demo host loopback 8092/8094 and optional sidecar loopback 8443/public TCP 10000 are intended. Stop older standalone stacks that own those host ports. |

Further contract and security detail: [broadcast](../../docs/development/broadcast.md),
[publisher templates](../../docs/development/publisher.md),
[Firebase](../../docs/guides/firebase.md), and
[the Stage 7.1 environment model](../../docs/wiki/environments.md).
