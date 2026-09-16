# Self-hosting the sidecar with Docker Compose

The stack in [`gateway/compose.yaml`](../../gateway/compose.yaml) runs the sidecar on your own machine — a Mac, or a Linux VPS — behind a gateway, with durable requests on a volume that survives a restart. It has two configurations: plain HTTP on your own machine while you are working locally, and HTTPS on a domain you control when you put it on the internet ([Going public](#going-public-tls-dns-and-ports)). Everything builds from this checkout. There is no image to pull from us, no account to make, and no service of ours in the path.

What this gives you is the server half of Seeker Agent Connect. The phone still reviews and approves every request, and the wallet still signs; the sidecar holds no key and signs nothing ([`docs/architecture.md`](../architecture.md)).

**Starting the stack does nothing on its own.** It opens a port and waits. It creates no request, asks for no signature, moves no funds, and runs no LLM. The test agent is not started by `docker compose up` at all.

## Before you start

- Docker Engine 24 or newer with the Compose v2 plugin. `docker compose version` should print `v2.x`.
- A clean checkout of this repository. Nothing else: the images build Node, the workspace, and the sidecar from source.

## Start it

```sh
cd gateway
cp .env.example .env
```

Open `.env` and replace the two token placeholders with different random values:

```sh
openssl rand -hex 32   # MCP_TOKEN
openssl rand -hex 32   # PHONE_TOKEN
```

`MCP_TOKEN` is what an agent authenticates with. `PHONE_TOKEN` belongs to the Stage 1 live diagnostic. The sidecar refuses to start while either still holds its placeholder, is shorter than 32 characters, or matches the other. The rest of `.env` is documented in place and can stay as it is for a first run.

Then build and start:

```sh
docker compose up -d --build
```

The first build compiles the workspace and takes a few minutes; later builds reuse the dependency layer. Check it came up:

```sh
docker compose ps
curl -fsS http://127.0.0.1:8080/healthz
```

`/healthz` answers `{"status":"ok"}`. It is the one unauthenticated route; everything else needs a token.

Stop the stack with `docker compose down`. Your requests and pairing credentials stay on the `sidecar-data` volume. `docker compose down -v` deletes that volume, and with it the paired phone and every stored request.

## What is running, and what is reachable

Two containers, and one published port.

| | |
| --- | --- |
| **sidecar** | The Node server. Listens on `127.0.0.1:8080` **inside its own network namespace**. |
| **gateway** | Caddy, listening on `:8081` in that same namespace, passing requests to the sidecar. |

The gateway joins the sidecar's network namespace (`network_mode: "service:sidecar"` in `compose.yaml`). That is what lets the sidecar keep the loopback-only bind it has had since Stage 1 while still being reachable by something. The consequence is worth stating plainly: **the sidecar's port is not reachable from your host, and not reachable from any other container** — not merely unmapped, but on a loopback interface nothing else shares. The only way in is the gateway.

Because the namespace belongs to the sidecar service, the published port is declared there too. `GATEWAY_PORT` (default `8080`) is the host port, and it forwards to the gateway's `8081`, never to the sidecar's `8080`.

`GATEWAY_BIND` defaults to `127.0.0.1`, so the port stays on the machine itself. That is the right setting for a Mac, and for a VPS you reach over a VPN or an SSH tunnel. Leave it there: this configuration speaks plain HTTP, and plain HTTP belongs on a loopback address. To put the stack on the internet, use the HTTPS configuration below rather than opening this port to the world.

## Reaching it from somewhere else

The sidecar checks the `Host` and `Origin` headers on `/mcp` against loopback, as a defence against DNS rebinding. The gateway passes those headers through unchanged rather than rewriting them, so the check stays a real check. When an agent reaches the stack by a hostname, name that hostname:

```sh
MCP_ALLOWED_HOSTS=vault.example.com
```

Comma-separated, no scheme and no port. Leave it empty while everything arrives over loopback.

For the phone, set `SIDECAR_PUBLIC_URL` to the URL a pairing code should carry. It has to be `https://`, or `http://` on a loopback host for `adb reverse`. A phone on another network needs a trusted TLS endpoint in front, which is what the next section sets up; [`pairing.md`](pairing.md) covers pairing itself.

## Going public: TLS, DNS, and ports

The internet-facing stack is the same `compose.yaml` plus an overlay, run as a separate command:

```sh
docker compose -f compose.yaml -f compose.public.yaml up -d --build
```

Two files, two commands, and no switch that could be left in the wrong position: plain HTTP cannot become the public default by forgetting something. The overlay mounts [`Caddyfile.public`](../../gateway/Caddyfile.public) instead of [`Caddyfile`](../../gateway/Caddyfile), publishes ports 80 and 443, and derives the two settings a public deployment must not get wrong from the domain itself.

### What you need before the first start

- **A domain name you control**, with an `A` record — and an `AAAA` record if the host has IPv6 — pointing at this host. The certificate authority resolves that name and connects back to it, so it has to be right before Caddy asks for a certificate, not after.
- **Ports 80 and 443 reachable from the internet.** 443 serves HTTPS. 80 answers the certificate authority's HTTP challenge and redirects anything that arrives in plaintext. A firewall, a cloud security group, or a router that swallows either one means no certificate. Nothing else needs to be open.
- **A contact address** for the expiry and problem notices the certificate authority sends.

Put the first and the last in `gateway/.env`:

```dotenv
GATEWAY_DOMAIN=vault.example.com
ACME_EMAIL=ops@example.com
```

The overlay refuses to start without either, and names the one that is missing. From `GATEWAY_DOMAIN` it also sets `MCP_ALLOWED_HOSTS` — the `Host` header `/mcp` will accept — and `SIDECAR_PUBLIC_URL`, the HTTPS URL a pairing code carries. Both are things a public deployment has to get right and neither has to be typed twice.

Caddy obtains the certificate on the first start and renews it on its own afterwards; nothing needs to be scheduled. [Automatic HTTPS](https://caddyserver.com/docs/automatic-https) describes what it does.

### Certificate storage

The account key and the certificates live on the `gateway-data` volume, under `/data/caddy`. **Keep that volume.** `docker compose down` keeps it and `docker compose down -v` deletes it; a deployment that keeps losing it asks for a new certificate every time it starts, and a certificate authority's rate limits will eventually refuse. A restart that keeps the volume reuses the certificate it already has.

### What is public, and what is not

| Path | On the internet | Authenticated by |
| --- | --- | --- |
| `/mcp` | yes | `MCP_TOKEN`, or an OAuth access token when the OAuth profile is on |
| `/.well-known/oauth-protected-resource` | yes, and only while the OAuth profile is on | nothing; a client reads it before it has a credential |
| `/seekervault.request.v1.PairingService/*` | yes | a one-use pairing token |
| `/seekervault.request.v1.RequestService/*` | yes | the paired phone's own credential |
| `/seekervault.update.v1.UpdateService/*` | yes, but not served here — see below | the paired phone's own credential |
| `/seekervault.live.v1.LiveCommandService/*` | **no** | the Stage 1 diagnostic's development token |
| `/healthz` | **no** | nothing; it says only `{"status":"ok"}` |
| anything else | **no** | — |

Each of those keeps its own credential. The gateway adds no authentication and removes none: it never inserts an `Authorization` header and never strips one, so the boundaries in the [role matrix](../protocol.md#roles) are still the sidecar's own, exactly as they are without a gateway. `Host` reaches the sidecar unchanged too, which is what keeps its DNS-rebinding check a real check.

Everything in the last four rows is refused at the gateway and never forwarded. `/healthz` moves to an endpoint that binds the network namespace's loopback address, so it cannot be published to the host at all — the health check and the test agent reach it, nothing outside does:

```sh
docker compose exec gateway wget -qO- http://127.0.0.1:8081/healthz
```

The `GATEWAY_PORT` mapping from the development configuration is still declared in this mode and is simply unused; nothing listens behind it.

### Live updates are not carried by this gateway

The phone's live update stream is gRPC over HTTP/2 end to end, and it terminates at the sidecar's *own* TLS listener ([production update listener](../development/sidecar.md#production-update-listener)). Behind a gateway that terminates TLS the sidecar speaks HTTP/1.1, so no update endpoint is configured — and nothing is advertised that this endpoint could not deliver: pairing simply omits the update capability, the phone never tries to open a stream, and its manual refresh and background synchronization keep working.

If you want the live stream, give the sidecar a publicly trusted PEM identity of its own as the sidecar guide describes, and put only a pass-through or HTTP/2-preserving proxy in front of it. An HTTP/1.1 reverse proxy is not a fallback transport for that stream, and `SIDECAR_UPDATE_PORT` is cleartext HTTP/2 for `adb reverse` on one machine — never for a public deployment.

### Pair the phone over HTTPS

With the stack up and the certificate issued, print a pairing code from inside the sidecar container:

```sh
docker compose exec sidecar node sidecar/dist/pairing/cli.js
```

The code carries `https://<your domain>`, because the overlay set `SIDECAR_PUBLIC_URL` from the domain. Scan it from **Connections → Add connection**. [`pairing.md`](pairing.md) covers the rest, including `status` and `revoke`.

### A hosted MCP client: the optional OAuth profile

A hosted client — Claude's custom connectors are the one this was written for — runs on somebody
else's machine, and pasting `MCP_TOKEN` into it hands that product a secret everything else uses.
The third overlay replaces it with OAuth: a person authorizes the client at an authorization
server you choose, and the sidecar accepts the short-lived token it was issued.

```sh
docker compose -f compose.yaml -f compose.public.yaml -f compose.oauth.yaml up -d --build
```

It is optional, and off by default. Running Hermes or an agent of your own needs none of it.

Nothing in this repository issues a token: the sidecar publishes the discovery document and
validates what arrives, and the authorization server is a product you already run or sign up for.
[`docs/integrations/claude.md`](../integrations/claude.md) is the whole setup — what that server
has to support, the four values to write down, how to check it with `curl` before involving
Claude, and what each refusal means. While the profile is on, `MCP_TOKEN` no longer opens `/mcp`
under the public name; it still opens the stack's own private endpoint, which is what the health
check and the test agent use.

### Never work around a certificate warning

If the phone or an agent refuses the certificate, the certificate is the problem — the refusal is the check working. Do not reach for a self-signed certificate, a private CA, a pinning exception, or a "trust this anyway" setting: release builds of the app do not offer one, and the way to make a phone accept an untrusted certificate is to weaken exactly the check that makes this endpoint safe to expose. Fix the name, the DNS record, or the port instead, and let Caddy issue a real certificate.


## The test agent

The test agent is a CLI. It is in the `agent` Compose profile, which means `docker compose up` never starts it — a service in a profile runs only when that profile is named. That is deliberate: no ordinary start of this stack can create a request or reach a wallet.

Run it a command at a time:

```sh
docker compose run --rm test-agent tools      # list the MCP tools the sidecar serves
docker compose run --rm test-agent address    # the wallet the owner connected, if any
```

Both only read. It reaches the sidecar through the gateway, the way an outside agent does, rather than going around it.

The commands that create a request — `ack`, `sign`, `transfer` — are deliberate acts, and each one puts something on the owner's phone to approve. `transfer` additionally needs `SOLANA_RPC_URL` set and requires `--wallet` and `--network` explicitly; it guesses neither. Without `SOLANA_RPC_URL` the sidecar serves no transfer tool at all, which is the default. [`test-agent/README.md`](../../test-agent/README.md) documents every command and its exit codes.

## Persistence

Requests, the paired phone's credential, and the server ID live in SQLite on the `sidecar-data` volume, at `/data/sidecar.db`. Nothing durable is written into the container's own filesystem, which the sidecar's account cannot write to anyway.

To check it for yourself: create a pending request, restart, and read it back.

```sh
docker compose run --rm test-agent ack "restart check"   # prints a request id; needs MCP_DEMO_TOOLS=true
docker compose restart sidecar
docker compose run --rm test-agent get <id>              # still PENDING
```

`MCP_DEMO_TOOLS=true` is what serves `vault_request_ack`, and it is for development only. A deployment leaves it `false` and uses a real request instead.

## How the images are built

Both images are multi-stage, and both build from the repository root, because the pnpm workspace's lockfile and catalog resolve each package's dependencies.

- Node and pnpm are pinned to the versions `.nvmrc` and `package.json` name, and dependencies install with `--frozen-lockfile`, so a build of one commit resolves one tree.
- Dependencies install before any source is copied, so editing a source file does not re-resolve them.
- The runtime stage installs again with `--prod`, so no compiler, linter, or test tooling ships.
- Each container runs as its own unprivileged account with a fixed uid (`10001` for the sidecar, `10002` for the test agent) — outside the range a first human account on a Linux host takes, so a volume's ownership is the same everywhere. The application tree is root-owned and not writable by that account; only `/data` is.
- Both drop every Linux capability and set `no-new-privileges`.
- Node is PID 1, so it receives `SIGTERM` itself. `main.ts` cancels the in-flight live command, ends the streams, stops listening, and closes SQLite; Compose allows 30 seconds for that.

## Architectures

The images are built for the architecture of the machine that builds them, from the same Dockerfiles, with no cross-building involved:

- **Apple silicon Mac** (`linux/arm64` under Docker Desktop)
- **Linux VPS** (`linux/amd64`, the common case; `linux/arm64` on an Ampere or Graviton host)

A successful `docker buildx` for a platform is not evidence that the container runs there. [`docs/testing/stage-7.md`](../testing/stage-7.md) records which runtime checks were actually performed on which machine, and which are still outstanding.

## Troubleshooting

**The stack will not start, and the log names a variable.** The sidecar validates its whole configuration before it listens and names every problem it found, without ever echoing a token. Fix them in `gateway/.env` and `docker compose up -d` again.

**`docker compose up` reports a missing `MCP_TOKEN` or `PHONE_TOKEN`.** Those two are required by `compose.yaml` itself, so the error arrives before a container starts. Copy `.env.example` to `.env` and fill them in.

**An agent gets a rejection mentioning the `Host` header.** The hostname it used is not loopback and not in `MCP_ALLOWED_HOSTS`. Add it.

**Port 8080 is already taken** — often by a sidecar started with `pnpm dev:sidecar`. Stop that one, or set `GATEWAY_PORT` to something else.

**The gateway container keeps restarting.** It shares the sidecar's namespace, so it cannot start before the sidecar is healthy. Read `docker compose logs sidecar` first.

**The certificate never arrives.** `docker compose logs gateway` says which step failed. The usual causes are a DNS record that does not resolve to this host yet, port 80 or 443 blocked before it reaches the machine, or a `GATEWAY_DOMAIN` that does not match the record. Caddy keeps retrying, so fix the cause and leave it running. Do not put a self-signed certificate in its place.

**Port 80 or 443 is already in use.** Another web server or reverse proxy on the host has it. Stop that one, or give it the domain and let it forward to this stack; two things cannot both answer the certificate authority on port 80.

**A request gets no response at all — the connection simply closes.** That is the gateway refusing a path it does not serve, or a `Host` that is not `GATEWAY_DOMAIN`. On the internet-facing configuration only `/mcp`, pairing, and the phone's request and update services are forwarded; `/healthz` and the Stage 1 diagnostic are deliberately not. Check the URL, and check the hostname the client used.

**Claude cannot find an authorization server.** The discovery document is public by design, and the client reads it before it has any credential. Check it from outside your network: `curl -s https://<domain>/.well-known/oauth-protected-resource`. A 404 means the OAuth profile is not on — `MCP_OAUTH_ISSUER` is unset, or the third overlay was left off the command.

**Every access token is refused, and the reason names a claim.** The refusal says which check failed, in words, both in the `WWW-Authenticate` header and in `docker compose logs sidecar`. [`docs/integrations/claude.md`](../integrations/claude.md#when-it-does-not-work) has each one and what to change; a trailing slash on the issuer and an audience the authorization server never set are the two common ones.

**The test agent stopped working after turning OAuth on.** It reaches `/mcp` through the stack's private endpoint, which still takes `MCP_TOKEN`; `AGENT_MCP_URL` must be the loopback one (`http://127.0.0.1:8081/mcp`), not the public domain. Under the public name only an access token is accepted.

**The phone or an agent reports an untrusted certificate.** Read the gateway's log and fix the certificate. There is no setting here that turns the check off, and adding one would remove the only thing that makes a public endpoint safe.

[`troubleshooting.md`](troubleshooting.md) covers the development setup, and [`docs/development/sidecar.md`](../development/sidecar.md) the sidecar's configuration variable by variable.
