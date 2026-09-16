# Self-hosting the sidecar with Docker Compose

The stack in [`gateway/compose.yaml`](../../gateway/compose.yaml) runs the sidecar on your own machine — a Mac, or a Linux VPS — behind a gateway, with durable requests on a volume that survives a restart. Everything builds from this checkout. There is no image to pull from us, no account to make, and no service of ours in the path.

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

`GATEWAY_BIND` defaults to `127.0.0.1`, so the port stays on the machine itself. That is the right setting for a Mac, and for a VPS you reach over a VPN or an SSH tunnel. Do not set it to `0.0.0.0` until there is a TLS listener in front of it — that is SAW-035's work, and until then the gateway speaks plain HTTP.

## Reaching it from somewhere else

The sidecar checks the `Host` and `Origin` headers on `/mcp` against loopback, as a defence against DNS rebinding. The gateway passes those headers through unchanged rather than rewriting them, so the check stays a real check. When an agent reaches the stack by a hostname, name that hostname:

```sh
MCP_ALLOWED_HOSTS=vault.example.com
```

Comma-separated, no scheme and no port. Leave it empty while everything arrives over loopback.

For the phone, set `SIDECAR_PUBLIC_URL` to the URL a pairing code should carry. It has to be `https://`, or `http://` on a loopback host for `adb reverse`. A phone on another network needs a trusted TLS endpoint in front — see [a trusted endpoint](../security.md#a-trusted-endpoint-for-this-stages-remote-test) and [`pairing.md`](pairing.md).

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

[`troubleshooting.md`](troubleshooting.md) covers the development setup, and [`docs/development/sidecar.md`](../development/sidecar.md) the sidecar's configuration variable by variable.
