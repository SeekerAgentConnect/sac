# The sidecar on its own TLS listener: live updates on a server

This deployment runs the sidecar alone, with a publicly trusted certificate of its own and nothing
in front of it that speaks HTTP. It is the one layout in which the phone's live update stream
works: `UpdateService.Subscribe` is gRPC over HTTP/2 end to end, and it terminates at the
sidecar's own TLS listener ([production update
listener](../../docs/development/sidecar.md#production-update-listener)). Every other shipped
deployment — the Compose gateway, the Render image, an HTTPS reverse proxy such as Tailscale
Funnel's default mode — puts an HTTP/1.1 hop in front of the sidecar, and behind such a hop
pairing advertises no update endpoint and the phone stays on refresh, background sync, and push.

It is written for a Linux server on a tailnet, with **Tailscale Funnel in raw TCP mode** passing
TLS through to the sidecar and `tailscale cert` providing the certificate. Any other server works
the same way with its own certificate and a TCP-level (not HTTP-level) path to the port.

## What runs, and what it trades

| | |
| --- | --- |
| Container | `sidecar/Dockerfile`, unchanged: Node as PID 1, uid 10001, `/data` the only writable path, no capabilities. |
| Network | `network_mode: host`. The sidecar binds `127.0.0.1:8443` **on the server's loopback**, by the sidecar's own rule that it only ever binds loopback. Only processes on the server reach it: tailscaled, an agent running there, you. Docker publishes nothing. |
| In front | `tailscale funnel --tcp=10000 tcp://localhost:8443`: the public `https://<node>.<tailnet>.ts.net:10000` is a TCP pipe to that port. TLS terminates in the sidecar, HTTP/2 survives, the stream works. |
| Certificate | `tailscale cert`, a Let's Encrypt certificate for the node's MagicDNS name, 90 days, renewed by re-running it. The sidecar reads the files once at start, so a renewal ends in a restart; `tls-from-tailscale.sh` does both. |
| Public origin | `https://<node>.<tailnet>.ts.net:10000`. It is in every pairing code, it is where the stream opens, and its hostname is the only `Host` `/mcp` accepts. |
| Push | Optional. `FCM_PROJECT_ID` plus a service-account file under `./secrets` turns the sidecar's FCM sender on; the phone then syncs on a push even with the app closed. |

The trade: with no gateway there is no route filter. Everything the sidecar serves is on the
public origin, each part behind its own credential — `/mcp` behind `MCP_TOKEN`, pairing behind a
one-use code, the phone's services behind the paired credential, the Stage 1 `LiveCommandService`
diagnostic behind `PHONE_TOKEN`, and `/healthz`, which says `ok` and nothing else. Every other path
is `404`. The Compose and Render gateways keep the diagnostic and `/healthz` off the public
interface; here they are on it, protected by tokens alone. Keep `PHONE_TOKEN` as strong as
`MCP_TOKEN`, and keep `MCP_DEMO_TOOLS` off.

## Before you start

- A Linux server with Docker and the Compose plugin. Building the image needs about 2 GB of free
  memory; a small server pulls the published image instead (the default).
- Tailscale on the server, with **MagicDNS** and **HTTPS Certificates** enabled for the tailnet
  (admin console, DNS page) and **Funnel** allowed for the node in the tailnet policy (`funnel`
  node attribute). `tailscale status --json | grep -i funnel` on the node shows the capability
  once it is granted.
- The image, `docker.io/brenat/seeker-agent-connect:sidecar-v1`, built from this repository for
  `linux/amd64`. To build your own, see [Building the image](#building-the-image).
- For push: a Firebase project the app is already registered with, and permission to create a
  service-account key in it.

## 1. Put the files on the server

Copy this directory to the server, for example to `/opt/seeker-agent-connect/server`:

```bash
ssh SERVER 'mkdir -p /opt/seeker-agent-connect'
rsync -av deploy/server/ SERVER:/opt/seeker-agent-connect/server/
```

Or clone the repository there and work in `deploy/server`. Nothing else from the repository is
needed at run time.

## 2. Configure

```bash
cd /opt/seeker-agent-connect/server
cp .env.template .env
chmod 600 .env
```

Fill in:

| Variable | Value |
| --- | --- |
| `SERVER_DOMAIN` | The node's MagicDNS name, `tailscale status --self` shows it. This is the name on the certificate. |
| `MCP_TOKEN`, `PHONE_TOKEN` | Two different values from `openssl rand -hex 32`. |
| `PUBLIC_PORT` | `10000` (the default). Funnel allows 443, 8443, or 10000, and a node that already serves HTTPS on 443 keeps it. |
| `SIDECAR_PORT` | `8443` (the default), a free port on the server's loopback. |
| `SOLANA_RPC_URL` | Devnet is prefilled. Empty it to serve no transfer tool at all. |
| `FCM_PROJECT_ID` | Leave empty for now; [step 7](#7-push-fcm-on-the-server) turns it on. |

## 3. The certificate

```bash
sudo ./tls-from-tailscale.sh
```

It runs `tailscale cert` for `SERVER_DOMAIN`, writes `tls/fullchain.pem` and `tls/privkey.pem`
owned by uid 10001 (the key readable by that account alone), and prints the certificate's subject
and expiry. If the sidecar is already running and the certificate changed, it restarts it.

Renewal is not automatic anywhere: `tailscale cert` renews only when expiry is near, and the
sidecar reads the files at start. Run the script weekly from root's crontab and both are handled:

```cron
17 4 * * 1 /opt/seeker-agent-connect/server/tls-from-tailscale.sh >> /var/log/seeker-tls.log 2>&1
```

A certificate from anywhere else works the same: put the full chain and the key at those two
paths, owned by uid 10001, mode 0644 and 0600, and restart the sidecar when you replace them.

## 4. Funnel, in TCP mode

```bash
sudo tailscale funnel --bg --tcp=10000 tcp://localhost:8443
tailscale funnel status
```

Status should list `tcp://<node>.<tailnet>.ts.net:10000 (Funnel on)` forwarding to
`tcp://localhost:8443`. The two numbers are `PUBLIC_PORT` and `SIDECAR_PORT`. This is a raw TCP
forward: Tailscale does not terminate TLS, sees no HTTP, and adds no headers. It coexists with an
HTTPS `serve` on port 443 on the same node. To remove it later:

```bash
sudo tailscale funnel --tcp=10000 tcp://localhost:8443 off
```

## 5. Start, and check from outside

```bash
docker compose pull
docker compose up -d
docker compose logs -f sidecar
```

The log must contain these two lines, in this form:

```text
[sidecar] listening on https://127.0.0.1:8443: MCP at https://127.0.0.1:8443/mcp, ...
[sidecar] production updates are served as gRPC over HTTP/2 at https://<node>.<tailnet>.ts.net:10000
```

"production updates are not configured" means the TLS paths did not reach the sidecar — the
`tls/` files are missing or unreadable by uid 10001 (`ls -ln tls`).

From any machine on the internet:

```bash
curl -sS -o /dev/null -w 'healthz %{http_code} over HTTP/%{http_version}\n' https://<node>.<tailnet>.ts.net:10000/healthz
curl -sS -o /dev/null -w 'mcp %{http_code}\n' -X POST https://<node>.<tailnet>.ts.net:10000/mcp
```

Expect `healthz 200 over HTTP/2` and `mcp 401`. A certificate error here is real: the name, the
files, or the port is wrong. Fix it; never tell a client to ignore it.

## 6. Pair the phone

```bash
docker compose exec sidecar node sidecar/dist/pairing/cli.js
docker compose exec sidecar node sidecar/dist/pairing/cli.js status
```

The pairing code carries `https://<node>.<tailnet>.ts.net:10000` and, because the TLS listener
is configured, the update capability. Scan it on the Seeker over mobile data, not the same
network. The connection now opens the stream whenever the app is in the foreground: create a
request from an agent and it appears without a tap on refresh. `revoke` in place of `status` ends
the pairing, closes its stream, and cancels its pending requests.

If a previous deployment runs on this server — the Render image behind an HTTPS Funnel path, or
the Compose stack — its database is a different volume, so its pairing does not carry over: pair
again from this one, then stop the old container and remove its Funnel entry. The phone keeps the
old connection until you remove it in the app.

## 7. Push (FCM) on the server

The app side is described in [`docs/guides/firebase.md`](../../docs/guides/firebase.md). The
server side is a project ID and a credential; nothing else changes.

1. In the Firebase project the app is registered with (the `project_id` in the app's
   `google-services.json`), enable the **Firebase Cloud Messaging API (V1)**.
2. Create a service account with only the **Firebase Cloud Messaging API Admin** role, and create
   a JSON key for it. The key is a secret: it lets its holder send pushes to your app.
3. Put it on the server as `secrets/fcm-service-account.json`, owned by the sidecar's account and
   readable by nothing else:

   ```bash
   mkdir -p secrets
   # copy the key file here, then:
   chown 10001:10001 secrets/fcm-service-account.json
   chmod 0400 secrets/fcm-service-account.json
   ```

4. Set `FCM_PROJECT_ID` in `.env` to that project ID and restart:

   ```bash
   docker compose up -d
   docker compose logs sidecar | grep FCM
   ```

   Expect `FCM sender is configured through Application Default Credentials`. `compose.yaml` sets
   `GOOGLE_APPLICATION_CREDENTIALS` to the mounted file whenever `FCM_PROJECT_ID` is set, so the
   credential's path never appears in `.env`.

What to know:

- The project must be the same on both sides. With a different one, sends are rejected and the
  phone falls back to its own recovery; the request itself is never affected.
- A project ID without a readable key file starts fine — the SDK checks credentials at the first
  send, not at start — and then every send fails as `delivery unavailable` in the log. If pushes
  never arrive, check the file's presence and ownership first.
- The sidecar logs that FCM is configured and whether a delivery was unavailable, and never the
  project ID, the file, its contents, a token, or Firebase's error text.
- To rotate the key, replace the file and `docker compose restart sidecar`. To turn push off,
  empty `FCM_PROJECT_ID` and `docker compose up -d`; nothing needs re-pairing.

## 8. Connect an agent

Hermes, on this server or anywhere else, uses the public origin. In `~/.hermes/config.yaml`:

```yaml
mcp_servers:
  seeker_vault:
    url: "https://<node>.<tailnet>.ts.net:10000/mcp"
    headers:
      Authorization: "Bearer ${MCP_SEEKER_VAULT_API_KEY}"
    timeout: 90
    tools:
      include:
        [
          vault_get_address,
          vault_get_capabilities,
          vault_sign_message,
          vault_transfer,
          vault_get_request,
          vault_cancel_request,
        ]
      resources: false
      prompts: false
```

with `MCP_SEEKER_VAULT_API_KEY=<MCP_TOKEN>` in `~/.hermes/.env` (mode 600). This is
[`examples/hermes.config.hosted.yaml`](../../examples/hermes.config.hosted.yaml) with the port
added. An agent on the server itself still uses this URL, not `127.0.0.1`: the certificate names
the node, and `/mcp` accepts that hostname on any port. The repository's test agent works from a
checkout with `MCP_URL=https://<node>.<tailnet>.ts.net:10000/mcp` and `MCP_TOKEN`.

## Operating it

- **Logs:** `docker compose logs sidecar`. The sidecar names events, never tokens, credentials,
  request text, or a push target.
- **Certificate:** the cron line in step 3. `openssl x509 -in tls/fullchain.pem -noout -enddate`
  shows the expiry; the phone refuses an expired certificate, correctly.
- **Rotating a token:** change it in `.env`, `docker compose up -d`. `MCP_TOKEN` cuts every agent
  over at once; `PHONE_TOKEN` is the diagnostic's token and does not affect the paired phone —
  `revoke` does.
- **Updating:** set `SIDECAR_IMAGE` to the new tag (or pull it), `docker compose up -d`. The
  sidecar migrates its schema forward; the stream reconnects on the phone's side.
- **Backup and restore:** the `sidecar-data` volume, as
  [`self-hosting.md`](../../docs/guides/self-hosting.md#backing-up-and-restoring) describes.
  Restoring an older copy moves the record backwards and does nothing else.
- **Stopping the public exposure without stopping the sidecar:** the `funnel ... off` command in
  step 4. The port stays on loopback for the server's own agent.

## Building the image

From a checkout, on any machine with Docker — the Dockerfile installs and compiles on the building
machine's own platform and assembles only the runtime stage for the target, so an Apple silicon
Mac builds the `linux/amd64` image a server needs:

```bash
docker buildx build --platform linux/amd64 -f sidecar/Dockerfile \
  -t YOUR-USER/seeker-agent-connect:sidecar-v1 --load .
docker push YOUR-USER/seeker-agent-connect:sidecar-v1
```

Then set `SIDECAR_IMAGE` in `.env`. On a server with enough memory, `docker build -f
sidecar/Dockerfile -t seeker-agent-wallet/sidecar:local .` in a checkout works too. The image
carries no credential and no configuration.

## Troubleshooting

| Symptom | Cause, and what to do |
| --- | --- |
| Log says `production updates are not configured` | The TLS paths are unset or the files unreadable. `ls -ln tls` should show uid 10001; run `sudo ./tls-from-tailscale.sh`. |
| `tailscale cert` fails | HTTPS Certificates not enabled for the tailnet, or Let's Encrypt's rate limit after many issuances (wait; the script never re-issues a valid certificate). |
| Connection refused on port 10000 | The Funnel TCP forward is missing (`tailscale funnel status`), or its target port is not `SIDECAR_PORT`, or the node lacks the `funnel` attribute. |
| The phone or curl rejects the certificate | The name in `SERVER_DOMAIN` is not the node's MagicDNS name, or the certificate expired. Never work around it. |
| `403` on `/mcp` | The `Host` is not `SERVER_DOMAIN`; the port does not matter, the name does. |
| `401` on `/mcp` | Wrong or missing `MCP_TOKEN`. The stream and pairing are unaffected. |
| The sidecar refuses to start and names a variable | Read the line: both TLS paths must be set together, the two tokens must differ and be 32+ characters, `FCM_PROJECT_ID` must be a valid project ID. |
| Pushes never arrive | The service-account file is missing or not readable by uid 10001, or its project differs from the app's. The log says `delivery unavailable`; the request is unaffected. |
| The stream drops and reconnects | Expected across a network change or a deploy. Persistent drops through Funnel are worth reporting; the phone's unary sync and refresh still work. |

## Verification record

Run on 2026-09-18 on an Apple silicon Mac (Docker Desktop, engine 20.10):

- PASS: `sidecar/Dockerfile` built natively and, after its install and build stages were pinned
  to `$BUILDPLATFORM`, for `linux/amd64`; the amd64 image was pushed as
  `docker.io/brenat/seeker-agent-connect:sidecar-v1`.
- PASS: `docker compose config` on `compose.yaml` with FCM on (the credential path is set) and
  off (it is empty).
- PASS: the container, with a self-signed certificate for a test name and a structurally valid
  fake service-account file, logged `FCM sender is configured through Application Default
  Credentials` and `production updates are served as gRPC over HTTP/2 at
  https://sidecar.test.example:10000`; a client negotiated `h2` on the TLS listener and got
  `/healthz` `200`; `/mcp` answered `401` without a token, `200` with a real `initialize`, and an
  unknown path `404`; the pairing code carried the origin with its port; the Compose healthcheck
  command passed; `SIGTERM` ended with exit `0`. The same image ran under amd64 emulation with
  the same result. A project ID without a credential file starts and logs the same FCM line,
  as the guide says.
- NOT RUN in this pass: the `Host` check on `/mcp` (Node's `fetch` drops a custom `Host`
  header; the sidecar's own tests and the Compose stack's record cover it).
- NOT RUN: anything on a real server — `tailscale cert`, Funnel's TCP mode, a Seeker holding a
  stream through it, a real push delivery. Those need the server and the phone.

## Sources

- Tailscale Funnel, TCP mode and allowed ports: https://tailscale.com/kb/1311/tailscale-funnel
- `tailscale cert`, HTTPS certificates, renewal: https://tailscale.com/kb/1153/enabling-https
- Firebase Admin SDK credentials: https://firebase.google.com/docs/admin/setup
