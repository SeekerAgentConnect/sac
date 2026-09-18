# Runbook: one small server, published images, Tailscale Funnel

The shortest path through [`README.md`](README.md) for one concrete layout, written down after
doing it on a DigitalOcean droplet on 2026-09-18. README stays the reference for why each piece is
the way it is; this file is the order to type things in, and how to take it all away again.

What you end up with, on a node named `hermes.tail5e20a8.ts.net` (substitute yours everywhere):

| Public address                          | Funnel forward                         | What answers                                                        |
| --------------------------------------- | -------------------------------------- | ------------------------------------------------------------------- |
| `https://hermes.tail5e20a8.ts.net:8443` | `--tcp=8443` → `localhost:9443`        | Caddy → broadcast gateway and the Centrifugo stream (public feeds). |
| `https://hermes.tail5e20a8.ts.net:10000` | `--tcp=10000` → `localhost:8443`       | The direct sidecar's own TLS listener (pairing, MCP, live updates). |
| `https://hermes.tail5e20a8.ts.net`      | whatever the node already serves there | Not touched. This is why the gateway is on 8443 and not 443.        |

Both forwards are raw TCP, so TLS and HTTP/2 end in our containers and both streams work. One
`tailscale cert` pair in `./tls` serves both. The server holds this folder and pulls images; it has
no checkout and builds nothing.

Status of this runbook: steps 1–8 were carried out on the droplet on 2026-09-18, by its owner.
Both demos run there and publish to the gateway; the notes in step 8 are the three things that went
wrong on the way. No result of the `grpcurl` stream check through Funnel was recorded, and nothing
was checked on a phone: the app cannot add a feed yet (SEE-107).

## 0. Once per tailnet and server

- Tailnet admin console: **MagicDNS** and **HTTPS Certificates** on; the node has the `funnel`
  attribute in the policy.
- Server: Docker with the Compose plugin, `tailscale up`, and `docker login` for the private
  repository.
- See what Funnel already serves, so nothing is overwritten:

  ```sh
  tailscale serve status
  ```

  If `https://<node>` (443) is in use, keep it and use 8443 for the gateway as below. If 443 is
  free you may use it instead: drop `GATEWAY_PUBLIC_PORT`, forward `--tcp=443`, and the origin has
  no port.

## 1. On the Mac: build and push the images

From the repository root. The Go compiler runs natively and cross-compiles, so Apple silicon
produces `linux/amd64` without emulation.

```sh
docker buildx build --platform linux/amd64 -f broadcast/Dockerfile \
  -t brenat/seeker-agent-connect:broadcast-v1 --load .
docker buildx build --platform linux/amd64 -f publisher/Dockerfile \
  -t brenat/seeker-agent-connect:publisher-v1 --load .
docker buildx build --platform linux/amd64 -f sidecar/Dockerfile \
  -t brenat/seeker-agent-connect:sidecar-v1 --load .
docker push brenat/seeker-agent-connect:broadcast-v1
docker push brenat/seeker-agent-connect:publisher-v1
docker push brenat/seeker-agent-connect:sidecar-v1
```

One repository, one tag per part. Bump the suffix (`-v2`) for a release you want to be able to roll
back from; re-pushing the same tag is fine for a fix.

## 2. On the Mac: copy this folder to the server

`-L` turns the three symlinks (`centrifugo.yaml`, `Caddyfile.copytrading`, `Caddyfile.prediction`)
into real files. The excludes make a later re-sync safe: the server's own state is never touched.

```sh
rsync -avL --exclude '.env*' --exclude tls --exclude secrets --exclude backups \
  deploy/server/ root@hermes:seeker-agent-connect/
```

Everything below is on the server, in `~/seeker-agent-connect`. Check the copy:

```sh
cd ~/seeker-agent-connect
ls -l centrifugo.yaml        # a regular file, not a link
```

## 3. The sidecar's configuration

```sh
cp direct.env.template .env.direct && chmod 600 .env.direct
mkdir -p tls secrets/sidecar
chmod 0755 tls && chmod 0700 secrets/sidecar && chown 10001:10001 secrets/sidecar
```

In `.env.direct`: `SERVER_DOMAIN`, `SIDECAR_PUBLIC_URL` (`https://<node>:10000`) and
`MCP_ALLOWED_HOSTS` all name the node; `MCP_TOKEN` and `PHONE_TOKEN` are two different
`openssl rand -hex 32` values. For push, set `FCM_PROJECT_ID` and install the key:

```sh
install -o 10001 -g 10001 -m 0400 /path/to/service-account.json \
  secrets/sidecar/fcm-service-account.json
```

Coming from the older sidecar-only layout instead: `cp .env .env.direct`, add the
`SIDECAR_PUBLIC_URL` and `MCP_ALLOWED_HOSTS` lines, move `secrets/fcm-service-account.json` into
`secrets/sidecar/`, and keep `tls/`. The `sidecar-data` volume, and the pairing in it, carry over by
project name.

## 4. The gateway's configuration

```sh
cp .env.template .env && chmod 600 .env
mkdir -p secrets/broadcast backups
chmod 0700 secrets secrets/broadcast backups && chown 10001:10001 secrets/broadcast
```

In `.env`:

```dotenv
BROADCAST_DOMAIN=hermes.tail5e20a8.ts.net
ACME_EMAIL=unused@example.com
CENTRIFUGO_API_KEY=<openssl rand -hex 32>
CENTRIFUGO_TOKEN_KEY=<a different openssl rand -hex 32>
GATEWAY_HTTP_BIND=127.0.0.1:9080
GATEWAY_HTTPS_BIND=127.0.0.1:9443
GATEWAY_PUBLIC_PORT=8443
BROADCAST_IMAGE=docker.io/brenat/seeker-agent-connect:broadcast-v1
PUBLISHER_IMAGE=docker.io/brenat/seeker-agent-connect:publisher-v1
```

`9443` and `9080` are any free loopback ports other than the sidecar's `8443`. Optional feed push:
install the same kind of key as `secrets/broadcast/fcm-service-account.json` (uid 10001, `0400`)
and set `BROADCAST_PUSH_CREDENTIALS=/run/secrets/fcm-service-account.json` and
`BROADCAST_PUSH_ENVIRONMENT=sandbox`.

## 5. Certificate, then start

```sh
./tls-from-tailscale.sh
ls -ln tls                   # privkey.pem: 10001 0  -rw-r-----
```

The group-root bit is there because `.env` names the same domain: Caddy reads the key as root's
group, the sidecar as its owner. Renewal, weekly, from root's crontab:

```cron
17 4 * * 1 /root/seeker-agent-connect/tls-from-tailscale.sh >> /var/log/seeker-tls.log 2>&1
```

Start both parts. Never `--build` here; there is nothing to build from.

```sh
docker compose -f compose.direct.yaml pull
docker compose -f compose.direct.yaml up -d sidecar
docker compose -f compose.yaml -f compose.tailscale.yaml pull
docker compose -f compose.yaml -f compose.tailscale.yaml up -d --no-build
docker ps --format 'table {{.Names}}\t{{.Status}}'
```

Expect five containers, all `Up`, four of them `(healthy)` (the gateway has no healthcheck of its
own; its proxy's covers it). The sidecar log must say `production updates are served as gRPC over
HTTP/2 at https://<node>:10000`.

## 6. Funnel

```sh
tailscale funnel --bg --tcp=10000 tcp://localhost:8443     # sidecar
tailscale funnel --bg --tcp=8443  tcp://localhost:9443     # gateway
tailscale funnel status
```

`cannot serve TCP; already serving web on 443` means you typed `--tcp=443` on a node whose 443 is an
HTTPS serve: that is the case this layout avoids by using 8443.

## 7. Check from outside, then pair

From the Mac, not the server:

```sh
D=hermes.tail5e20a8.ts.net
curl -sS -o /dev/null -w 'sidecar healthz %{http_code} HTTP/%{http_version}\n' https://$D:10000/healthz
curl -sS -o /dev/null -w 'sidecar mcp %{http_code}\n' -X POST https://$D:10000/mcp
openssl s_client -connect $D:8443 -servername $D -alpn h2 </dev/null 2>/dev/null | grep ALPN
curl --http2 -sS -w '\ngateway HTTP/%{http_version} %{http_code}\n' \
  "https://$D:8443/seekervault.gateway.v1.FeedService/GetServerManifest" \
  -H 'Content-Type: application/json' -d '{"serverId":"00000000-0000-4000-8000-000000000000"}'
```

Expect `200 HTTP/2`, `401`, `ALPN protocol: h2`, and a `no_such_server` error over HTTP/2. A
certificate error is real; never tell a client to ignore it.

Pairing survives an upgrade, so look before pairing again:

```sh
docker compose -f compose.direct.yaml exec sidecar node sidecar/dist/pairing/cli.js status
docker compose -f compose.direct.yaml exec sidecar node sidecar/dist/pairing/cli.js    # only if unpaired
```

An agent uses `https://<node>:10000/mcp` with `Authorization: Bearer <MCP_TOKEN>`. The gateway on
8443 needs no pairing: feeds are public, and the current app build has no screen to add one yet.

## 8. The two demos (optional)

README Part 2, with three differences: every command carries
`-f compose.yaml -f compose.tailscale.yaml -f compose.demos.yaml`; `pull` and `--no-build` replace
`build`; and in both `.env.copytrading` and `.env.prediction`

```dotenv
PUBLISHER_GATEWAY_URL=https://hermes.tail5e20a8.ts.net:8443
```

exactly as the gateway prints its origin, port included — check with
`docker inspect seeker-agent-wallet-server-broadcast-1 | grep BROADCAST_PUBLIC_URL` before starting
them; a mismatch is refused as `other_gateway` and retried only when the publisher restarts. Restart
a publisher together with its proxy (`restart copytrading copytrading-proxy`), never alone: the
proxy lives in the publisher's network namespace and is stranded by a lone restart. Compose's
`Found orphan containers (…-sidecar-1)` warning is expected; never answer it with
`--remove-orphans`. Create `secrets/copytrading` and
`secrets/prediction` owned by `10001:10001`. Run the `grpcurl` stream check from the Mac (it needs
`third_party/` from the checkout) against `$D:8443`, without `--add-host`.

## Updating later

The three parts are independent compose files. A command that does not name a file does not pull
or recreate the containers in it: `compose.yaml` plus `compose.tailscale.yaml` is the gateway,
`compose.direct.yaml` is the sidecar, and the demos are only in `compose.demos.yaml`.

```sh
# Mac: rebuild and push the tags (step 1), re-sync the folder if compose files changed (step 2)
# Server:
docker compose -f compose.yaml -f compose.tailscale.yaml pull
docker compose -f compose.yaml -f compose.tailscale.yaml up -d --no-build
docker compose -f compose.direct.yaml pull && docker compose -f compose.direct.yaml up -d sidecar
# Demos, if they run (same three -f files as step 8). --no-deps leaves Part 1 alone:
docker compose -f compose.yaml -f compose.tailscale.yaml -f compose.demos.yaml pull
docker compose -f compose.yaml -f compose.tailscale.yaml -f compose.demos.yaml \
  up -d --no-build --no-deps \
  copytrading copytrading-proxy prediction prediction-proxy
```

`centrifugo` and `redis` staying at their previous age is expected: their tags are pinned and this
update does not rebuild them. `gateway-proxy` is recreated with `broadcast` because it shares that
container's network namespace. Recreate each publisher with its proxy, never alone (step 8).
Without the demo lines, `copytrading` and `prediction` keep the image they started with even when
`publisher-v1` was rebuilt and pushed. Volumes are kept; the services migrate their own schemas
forward. Never add `-v` to a `down` during an update.

## Removing everything from the server

This is the one place `down -v` belongs. It deletes the pairing, every stored request, publisher
identities and publications. Take what you want to keep first (README, "Operate, update and back
up").

1. End the pairing cleanly, so the phone's stream is closed and pending requests are cancelled
   rather than orphaned. Then remove the connection in the app.

   ```sh
   cd ~/seeker-agent-connect
   docker compose -f compose.direct.yaml exec sidecar node sidecar/dist/pairing/cli.js revoke
   ```

2. Close the public entry points. Only these two; anything else the node serves stays.

   ```sh
   tailscale funnel --tcp=8443  tcp://localhost:9443 off
   tailscale funnel --tcp=10000 tcp://localhost:8443 off
   tailscale serve status
   ```

3. Remove containers, networks and volumes of the one Compose project, whichever parts ran:

   ```sh
   docker compose -p seeker-agent-wallet-server down -v --remove-orphans
   ```

   If that Compose version insists on a file, the label does the same:

   ```sh
   L=label=com.docker.compose.project=seeker-agent-wallet-server
   docker rm -f $(docker ps -aq --filter $L)
   docker volume rm $(docker volume ls -q --filter $L)
   docker network rm $(docker network ls -q --filter $L)
   ```

4. Remove the images. Skip `caddy`, `redis` or `centrifugo` if something else on the server uses
   them; `docker image rm` refuses an image a container still holds.

   ```sh
   docker image rm \
     brenat/seeker-agent-connect:broadcast-v1 \
     brenat/seeker-agent-connect:publisher-v1 \
     brenat/seeker-agent-connect:sidecar-v1 \
     caddy:2.10-alpine centrifugo/centrifugo:v6.9.6 redis:8.2-alpine
   ```

5. Remove the cron line (`crontab -e`, the `tls-from-tailscale.sh` entry) and its log:

   ```sh
   rm -f /var/log/seeker-tls.log
   ```

6. Remove the folder. It holds the TLS key, both token files and the Firebase keys, so delete it
   rather than leaving it around:

   ```sh
   rm -rf ~/seeker-agent-connect
   ```

7. Off the server: take the `seeker_vault` entry and its key out of the agent's configuration
   (`~/.hermes/config.yaml`, `~/.hermes/.env`); delete the Firebase service-account key in the
   Firebase console if no other deployment uses it; `docker logout` if this server needs the
   registry for nothing else.

Verify nothing is left:

```sh
docker ps -a --filter label=com.docker.compose.project=seeker-agent-wallet-server
docker volume ls --filter label=com.docker.compose.project=seeker-agent-wallet-server
tailscale serve status
ss -ltn | grep -E ':(8443|9443|9080|8092|8094)\b'
```

All four should show nothing of ours. `tailscaled` keeps the node's certificate in its own state
directory; it is the node's, not this deployment's, and expires by itself.
