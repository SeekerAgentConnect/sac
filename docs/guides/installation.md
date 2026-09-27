# Installing Seeker Agent Connect

Everything here runs from a registry. Nothing on this page needs a clone of this repository
(SEE-168). For building from a checkout, see [docs/guides/self-hosting.md](self-hosting.md); for
how these artifacts are produced, see
[docs/development/releases.md](../development/releases.md).

The published namespaces are the npm scope **`@seeker_agent_connect`** and the Docker Hub
repository **`docker.io/brenat/seeker-agent-connect`**. Every image shares that one repository and
is told apart by its tag prefix: `<prefix>-<version>`, `<prefix>-latest` (stable only) and
`<prefix>-sha-<commit>`.

npm releases currently run as a dry run (`registries.npmDryRun` in `release/components.json`), so
new versions reach npm only once that switch is turned off; the versions already on the registry
stay installable.

Requirements: Node **24.21.0 or newer** for the npm packages (the SDK and both MCP servers use the
stable `node:sqlite`, which arrived in Node 24), and Docker with `buildx`/Compose v2 for the
images. Images are published for `linux/amd64` and `linux/arm64`.

---

## The Direct Server SDK

Embed a direct server in your own Node application.

```bash
npm install @seeker_agent_connect/server-sdk
```

```ts
import { openDirectServer, privateRequest } from "@seeker_agent_connect/server-sdk";

const direct = openDirectServer({
  databasePath: "./direct-server.db",
  publicUrl: "https://server.example",
});

const pairing = direct.pairing.issue();
console.log("Pair the phone with:", pairing.uri);

const request = direct.requests.createRequest(
  privateRequest(
    { case: "acknowledgement", value: { text: "Hello from my own server" } },
    "Review",
    "hello-1",
    300,
  ),
);
console.log(request.ref?.requestId);
```

Generated protocol types are a second entry point on the same package — there is no separate
protocol package to install:

```ts
import { RequestState } from "@seeker_agent_connect/server-sdk/protocol";
```

The package is ESM-only and ships its own TypeScript declarations. `docs/integrations/server-sdk.md`
covers the API; `packages/server-sdk/README.md` travels inside the tarball.

---

## The MCP servers with `npx`

Both MCP servers are executables. Neither holds a key and neither signs anything: they prepare
requests and the owner approves them on the phone.

### General MCP server

```bash
npx --package=@seeker_agent_connect/mcp-server -- seeker-agent-connect-mcp --help
```

Run one for real with a config file and a data directory of its own:

```bash
mkdir -p ~/.seeker-agent-connect
cat > ~/.seeker-agent-connect/config.env <<'ENV'
MCP_SERVER_DATA_DIR=/home/you/.seeker-agent-connect
DATABASE_PATH=direct-server.db
SIDECAR_HOST=127.0.0.1
SIDECAR_PORT=8080
SIDECAR_PUBLIC_URL=https://server.example
# Two different values: openssl rand -hex 32
MCP_TOKEN=<64 hex characters>
PHONE_TOKEN=<64 hex characters>
MCP_ENABLED=true
LIVE_COMMAND_TIMEOUT_SECONDS=60
REQUEST_TTL_SECONDS=86400
REQUEST_PENDING_LIMIT=100
PAIRING_TOKEN_TTL_SECONDS=600
ENV
chmod 600 ~/.seeker-agent-connect/config.env

npx --package=@seeker_agent_connect/mcp-server -- \
  seeker-agent-connect-mcp --config ~/.seeker-agent-connect/config.env start
```

Pair a phone against the same configuration:

```bash
npx --package=@seeker_agent_connect/mcp-server -- \
  seeker-agent-connect-mcp --config ~/.seeker-agent-connect/config.env pair
```

Every one of those variables is required — the server refuses to start half-configured and names
what is missing. `servers/mcp-server/.env.example` lists the full set, including the optional
Solana RPC, FCM, OAuth and TLS groups.

The server speaks sessionful MCP Streamable HTTP at `/mcp` on the same listener as the phone API.
It does not speak MCP over stdio. `servers/mcp-server/README.md` is the full reference.

### SKR staking MCP server

```bash
npx --package=@seeker_agent_connect/mcp-skr-staking -- seeker-skr-staking-mcp --help
```

Its variables are its own — `SKR_STAKING_DATA_DIR`, `SKR_STAKING_DATABASE_PATH`,
`SKR_STAKING_HOST`, `SKR_STAKING_PORT` — and deliberately share nothing with the general server, so
the two can run side by side on one host. `servers/mcp-skr-staking/.env.example` is the list.

Installing globally works the same way and is the same artifact:

```bash
npm install --global @seeker_agent_connect/mcp-server
seeker-agent-connect-mcp --version
```

---

## Docker

| Image | Ports | Data | Health |
| -- | -- | -- | -- |
| `docker.io/brenat/seeker-agent-connect:mcp-*` | 8080 | `/data` (SQLite) | `HEALTHCHECK` in the image |
| `docker.io/brenat/seeker-agent-connect:skr-staking-mcp-*` | 8090 | `/data` (SQLite) | `HEALTHCHECK` in the image |
| `docker.io/brenat/seeker-agent-connect:gateway-*` | 8090 read, 8091 publish, 8092 admin | `/data`, or Postgres | external probe, see below |
| `docker.io/brenat/seeker-agent-connect:centrifugo-*` | 8000 client, 11000 internal | none | Centrifugo's own `/health` |
| `docker.io/brenat/seeker-agent-connect:copytrading-*` | 8092 | `/data` (SQLite) | external probe |
| `docker.io/brenat/seeker-agent-connect:prediction-*` | 8092 | `/data` (SQLite) | external probe |

Every image runs as an unprivileged fixed uid/gid (`10001`), so a named volume's ownership is the
same on every host. The Go images — the gateway and both demos — are `scratch` images with no
shell, which is why they carry no `HEALTHCHECK`: there is no `/bin/sh` for Docker's default shell
form and no shipped probe binary. Probe them from outside the container over HTTP instead, which is
what the Compose presets and the platform specs in the `do-deploy` repository do.

None of these images contains a credential, a host name, or a database. Configuration is entirely
environment variables, and the gateway deliberately has no default for `BROADCAST_PUBLIC_URL` or
for its store, so it refuses to start half-configured rather than guessing.

### Running one

```bash
docker run --rm \
  --name seeker-mcp \
  -p 127.0.0.1:8080:8080 \
  -v seeker-mcp-data:/data \
  -e SIDECAR_PUBLIC_URL=https://server.example \
  -e MCP_TOKEN=<64 hex characters> \
  -e PHONE_TOKEN=<64 hex characters> \
  -e MCP_ENABLED=true \
  docker.io/brenat/seeker-agent-connect:mcp-0.2.0
```

The process is PID 1 and handles `SIGTERM` itself: it stops accepting, finishes what is in flight,
closes SQLite and releases the instance lock. `docker stop` is a clean shutdown; the SQLite file in
the volume survives it and a restart picks up the same server identity and pairings.

### Compose

The presets live under `compose/` in the separate
[`do-deploy`](https://github.com/SeekerAgentConnect/do-deploy/tree/main/compose) repository. They
run the published images by default and build nothing; every one of them takes the image as a
variable, so a different tag (or a local build) is one setting away. From a `do-deploy` checkout:

```bash
MCP_SERVER_IMAGE=docker.io/brenat/seeker-agent-connect:mcp-0.2.0 \
  docker compose -f compose/mcp/compose.yaml up -d
```

```bash
BROADCAST_IMAGE=docker.io/brenat/seeker-agent-connect:gateway-0.2.0 \
  docker compose -f compose/feed/compose.yaml up -d
```

The variables are `MCP_SERVER_IMAGE`, `SKR_STAKING_IMAGE`, `BROADCAST_IMAGE`, `CENTRIFUGO_IMAGE`,
`COPYTRADING_IMAGE` and `PREDICTION_IMAGE`.

Without a checkout at all, this is a complete file:

```yaml
# compose.yaml
services:
  mcp-server:
    image: docker.io/brenat/seeker-agent-connect:mcp-0.2.0
    restart: unless-stopped
    environment:
      SIDECAR_HOST: 0.0.0.0
      SIDECAR_PORT: "8080"
      SIDECAR_PUBLIC_URL: ${SIDECAR_PUBLIC_URL:?set the URL the phone will reach}
      MCP_TOKEN: ${MCP_TOKEN:?64 hex characters}
      PHONE_TOKEN: ${PHONE_TOKEN:?64 hex characters}
      MCP_ENABLED: "true"
    ports:
      - "127.0.0.1:8080:8080"
    volumes:
      - mcp-data:/data
    stop_grace_period: 30s

volumes:
  mcp-data:
```

```bash
docker compose up -d
docker compose exec mcp-server node servers/mcp-server/dist/cli.js pair
```

---

## Pinning and rolling back

Pin an exact version everywhere, and a digest anywhere that matters. A digest cannot be moved:

```bash
docker pull docker.io/brenat/seeker-agent-connect:gateway-0.2.0
docker inspect --format '{{index .RepoDigests 0}}' docker.io/brenat/seeker-agent-connect:gateway-0.2.0
# then deploy the digest form
#   docker.io/brenat/seeker-agent-connect@sha256:<digest>
```

Rolling back is pulling the previous exact version or digest. `latest` is a convenience for a first
look, not something to deploy.

Release candidates are published under `X.Y.Z-rc.N` and the npm `next` dist-tag, and never move
`latest`. To try one deliberately:

```bash
npm install @seeker_agent_connect/server-sdk@next
docker pull docker.io/brenat/seeker-agent-connect:gateway-0.2.0-rc.1
```

---

## Upgrading from the 0.1.x images

The images stay in the same Docker Hub repository with the same tag prefixes as before SEE-168;
only the version moved on, restarting at `0.2.0` above every previous tag.

| Previous | Now |
| -- | -- |
| `docker.io/brenat/seeker-agent-connect:gateway-0.1.10` | `docker.io/brenat/seeker-agent-connect:gateway-0.2.0` |
| `docker.io/brenat/seeker-agent-connect:centrifugo-0.1.2` | `docker.io/brenat/seeker-agent-connect:centrifugo-0.2.0` |
| `docker.io/brenat/seeker-agent-connect:mcp-0.1.8` | `docker.io/brenat/seeker-agent-connect:mcp-0.2.0` |
| `docker.io/brenat/seeker-agent-connect:skr-staking-mcp-0.1.3` | `docker.io/brenat/seeker-agent-connect:skr-staking-mcp-0.2.0` |
| `docker.io/brenat/seeker-agent-connect:copytrading-0.1.7` | `docker.io/brenat/seeker-agent-connect:copytrading-0.2.0` |
| `docker.io/brenat/seeker-agent-connect:prediction-0.1.9` | `docker.io/brenat/seeker-agent-connect:prediction-0.2.0` |

No npm package was ever published under the old `@seeker-vault` scope, so there is nothing to
migrate there; `@seeker-vault/*` names in this repository's history refer to packages that only
ever existed inside the workspace.

**What an operator has to do.** Change the image reference and keep everything else. Environment
variable names, ports, the `/data` path, the uid/gid and the database file names are all unchanged,
so the existing named volumes mount onto the new images and the services start on the same state:

```bash
docker compose pull
docker compose up -d
```

Do **not** run `docker compose down -v`. That removes the volumes, and with them the server
identity, the pairings and the publication history. The `do-deploy`
[deployment runbook](https://github.com/SeekerAgentConnect/do-deploy/blob/main/compose/README.md#7-back-up-replace-and-roll-back) lists the durable volume and
database names each deployment has carried, including the ones inherited from earlier layouts.

The old Docker Hub tags are not deleted; anything already pinned to one keeps working. They will
simply stop receiving new versions.
