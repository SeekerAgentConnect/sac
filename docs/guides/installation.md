# Installing Seeker Agent Connect

Everything here runs from a registry. Nothing on this page needs a clone of this repository
(SEE-168). For building from a checkout, see [docs/guides/self-hosting.md](self-hosting.md); for
how these artifacts are produced, see
[docs/development/releases.md](../development/releases.md).

The published namespaces are the npm scope **`@seekeragentconnect`**, the GitHub Container
Registry namespace **`ghcr.io/seekeragentconnect`** (one repository per component) and the
[GitHub Releases](https://github.com/SeekerAgentConnect/sac/releases) of this repository, which
carry the signed Android APK (SEE-182). Every component is versioned on its own, and every
component's version restarted at `0.0.1` under this scheme. All eight `0.0.1` releases were
published on 2026-10-07; the table below records them, and the examples on this page use them.
A later version exists only once its component's release has actually run, so check the registry or
the Releases page before pinning one.

The images are public: `docker pull` works anonymously, with no `docker login`. npm packages are
published with provenance from the release workflow.

Requirements: Node **24.21.0 or newer** for the npm packages (the SDK and both MCP servers use the
stable `node:sqlite`, which arrived in Node 24), and Docker with `buildx`/Compose v2 for the
images. Images are published for `linux/amd64` and `linux/arm64`.

## Published versions

Every component's first public release, built from commit
[`f3521ff7`](https://github.com/SeekerAgentConnect/sac/commit/f3521ff7f3f0a05523453105ad5d09bcafa53cb5)
and published on 2026-10-07. The identities below are what to pin: an npm integrity is what
`npm install` verifies, an image digest cannot be moved, and the APK's SHA-256 and signing
certificate are what a download is checked against. Each GitHub Release carries the same record.

| Component | Release | Artifact | Identity |
| -- | -- | -- | -- |
| `server-sdk` | [`server-sdk-v0.0.1`](https://github.com/SeekerAgentConnect/sac/releases/tag/server-sdk-v0.0.1) | [`@seekeragentconnect/server-sdk@0.0.1`](https://www.npmjs.com/package/@seekeragentconnect/server-sdk/v/0.0.1) | `sha512-pMR5fpq7YJASTMuZxO4TGG8dhF3J/HzpB/RKylRnUqiEwnId4ds2IrM57/o+jmrbBM2kR5lqIl6xI6gsGEw1Hg==` |
| `mcp-server` | [`mcp-server-v0.0.1`](https://github.com/SeekerAgentConnect/sac/releases/tag/mcp-server-v0.0.1) | [`@seekeragentconnect/mcp-server@0.0.1`](https://www.npmjs.com/package/@seekeragentconnect/mcp-server/v/0.0.1) | `sha512-iX9gYneNyejvD5s/4/m7b8IIwhAWZlJYuevHcd1iGRS5xdkvjhMDdOxUuDijk+GcgBBCALzf6gUlMuhMwcuW6w==` |
| | | `ghcr.io/seekeragentconnect/mcp-server:0.0.1` | `sha256:1d2dc54636c861ec93020769176c19ed53433d98874c0565a0739e341e6c318a` |
| `mcp-skr-staking` | [`mcp-skr-staking-v0.0.1`](https://github.com/SeekerAgentConnect/sac/releases/tag/mcp-skr-staking-v0.0.1) | [`@seekeragentconnect/mcp-skr-staking@0.0.1`](https://www.npmjs.com/package/@seekeragentconnect/mcp-skr-staking/v/0.0.1) | `sha512-Ma1sxbLYnbAjfZJJI2BEL7t5b+P3ZafdlYiQ2QJl7IPEl8Wq1XKmwoFMGOf2EwM0k+5Alz7cZ5HgjhwZImcQGA==` |
| | | `ghcr.io/seekeragentconnect/mcp-skr-staking:0.0.1` | `sha256:a14c57c13f6bec8ec64288d65ab29f90af8e96620bae3780f347ba33c401e510` |
| `gateway` | [`gateway-v0.0.1`](https://github.com/SeekerAgentConnect/sac/releases/tag/gateway-v0.0.1) | `ghcr.io/seekeragentconnect/gateway:0.0.1` | `sha256:7e744e77ff05b7277373051761c2be7eeee4b6772de7168d3bd7396830084d67` |
| `gateway-centrifugo` | [`gateway-centrifugo-v0.0.1`](https://github.com/SeekerAgentConnect/sac/releases/tag/gateway-centrifugo-v0.0.1) | `ghcr.io/seekeragentconnect/gateway-centrifugo:0.0.1` | `sha256:cf724aaf36fe105e1ec9c49a0006ee80ebe5c55a05d01b181d82188f4e4cf938` |
| `demo-signals` | [`demo-signals-v0.0.1`](https://github.com/SeekerAgentConnect/sac/releases/tag/demo-signals-v0.0.1) | `ghcr.io/seekeragentconnect/demo-signals:0.0.1` | `sha256:dd44bb9ca75384ed5ac0a613ed3da131178fcd12ececcb7602dadce9bbcd8a14` |
| `demo-prediction` | [`demo-prediction-v0.0.1`](https://github.com/SeekerAgentConnect/sac/releases/tag/demo-prediction-v0.0.1) | `ghcr.io/seekeragentconnect/demo-prediction:0.0.1` | `sha256:561a278912745e97409dd71e4622eff9e18d92787e702ff105b2ff5e1061e95f` |
| `android` | [`android-v0.0.1`](https://github.com/SeekerAgentConnect/sac/releases/tag/android-v0.0.1) | `sac-0.0.1.apk` (`versionCode` 2) | APK SHA-256 `73947127396becce6b17dce8051b2e0886c9c82b5033425504d637303859496b` |

Both MCP servers bundle `@seekeragentconnect/server-sdk` 0.0.1 (vendored files SHA-256
`d02df39a83b11d1cbb5a14363242e8ffb0db80e1a07b5543d64fe6ca17b0c011`). Every image also carries the
tag `sha-f3521ff7f3f0a05523453105ad5d09bcafa53cb5`, and `latest` points at `0.0.1` until a newer
stable release moves it. The npm packages were published with SLSA provenance
(`npm audit signatures` verifies it), and every image pulls anonymously.

The Android signing certificate's SHA-256 is
`3b29a71fb3c5ff63c9b693f1e9e1fdc945bfd5231a2c1ec1537f4f7ad2164f28`. Every app release is signed
with the same key, so this value is the one to compare against for `0.0.1` and for every update.
The official `0.0.1` build has push (Firebase) and Solana RPC configured and uses
`https://seeker-gateway-sg8g3.ondigitalocean.app` as its relay and Discover catalog; the release
notes record each build's configuration.

---

## The Direct Server SDK

Embed a direct server in your own Node application.

```bash
npm install @seekeragentconnect/server-sdk
```

```ts
import { openDirectServer } from "@seekeragentconnect/server-sdk";

const direct = openDirectServer({
  databasePath: "./direct-server.db",
  publicOrigin: "https://server.example",
  requestTtlSeconds: 86_400,
  pendingLimit: 100,
  pairingTokenTtlSeconds: 600,
  liveCommandTimeoutSeconds: 30,
  log: (message) => console.info(`[direct] ${message}`),
});

console.info("Pair the phone with:", direct.pairing.issue().uri);
```

Generated protocol types are a second entry point on the same package — there is no separate
protocol package to install:

```ts
import { RequestState } from "@seekeragentconnect/server-sdk/protocol";
```

The package is ESM-only and ships its own TypeScript declarations. To check that it installs and
typechecks in a project of its own, outside any checkout:

```bash
mkdir sdk-check && cd sdk-check
npm init -y
npm pkg set type=module
npm install @seekeragentconnect/server-sdk typescript @types/node

cat > index.ts <<'TS'
import { openDirectServer } from "@seekeragentconnect/server-sdk";
import { RequestState } from "@seekeragentconnect/server-sdk/protocol";

const options: Parameters<typeof openDirectServer>[0] = {
  databasePath: "./direct-server.db",
  publicOrigin: "https://server.example",
  requestTtlSeconds: 86_400,
  pendingLimit: 100,
  pairingTokenTtlSeconds: 600,
  liveCommandTimeoutSeconds: 30,
  log: (message) => console.info(message),
};
const pending: RequestState = RequestState.PENDING;
console.info(options.publicOrigin, pending);
TS

cat > tsconfig.json <<'JSON'
{
  "compilerOptions": {
    "target": "es2024",
    "module": "nodenext",
    "moduleResolution": "nodenext",
    "strict": true,
    "types": ["node"]
  },
  "files": ["index.ts"]
}
JSON

npx tsc --noEmit
```

Creating and observing requests is in the package README. `docs/integrations/server-sdk.md`
covers the API; `packages/server-sdk/README.md` travels inside the tarball.

---

## The MCP servers with `npx`

Both MCP servers are executables. Neither holds a key and neither signs anything: they prepare
requests and the owner approves them on the phone.

### General MCP server

```bash
npx @seekeragentconnect/mcp-server@0.0.1 --version
npx --package=@seekeragentconnect/mcp-server -- seeker-agent-connect-mcp --help
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

npx --package=@seekeragentconnect/mcp-server -- \
  seeker-agent-connect-mcp --config ~/.seeker-agent-connect/config.env start
```

Pair a phone against the same configuration:

```bash
npx --package=@seekeragentconnect/mcp-server -- \
  seeker-agent-connect-mcp --config ~/.seeker-agent-connect/config.env pair
```

Every one of those variables is required — the server refuses to start half-configured and names
what is missing. `servers/mcp-server/.env.example` lists the full set, including the optional
Solana RPC, FCM, OAuth and TLS groups.

The server speaks sessionful MCP Streamable HTTP at `/mcp` on the same listener as the phone API.
It does not speak MCP over stdio. `servers/mcp-server/README.md` is the full reference.

### SKR staking MCP server

```bash
npx --package=@seekeragentconnect/mcp-skr-staking -- seeker-skr-staking-mcp --help
```

Its variables are its own — `SKR_STAKING_DATA_DIR`, `SKR_STAKING_DATABASE_PATH`,
`SKR_STAKING_HOST`, `SKR_STAKING_PORT` — and deliberately share nothing with the general server, so
the two can run side by side on one host. `servers/mcp-skr-staking/.env.example` is the list.

Installing globally works the same way and is the same artifact:

```bash
npm install --global @seekeragentconnect/mcp-server
seeker-agent-connect-mcp --version
```

Each MCP server is versioned independently of the SDK. Its tarball and image carry their own copy
of the SDK, and the published manifest records which one in the `seekerAgentConnect.serverSdk`
field (`name`, `version`, `contentSha256`; the same is in `dist/vendor/server-sdk/package.json`):

```bash
npm view @seekeragentconnect/mcp-server seekerAgentConnect
```

---

## Docker

| Image | Ports | Data | Health |
| -- | -- | -- | -- |
| `ghcr.io/seekeragentconnect/mcp-server` | 8080 | `/data` (SQLite) | `HEALTHCHECK` in the image |
| `ghcr.io/seekeragentconnect/mcp-skr-staking` | 8090 | `/data` (SQLite) | `HEALTHCHECK` in the image |
| `ghcr.io/seekeragentconnect/gateway` | 8090 read, 8091 publish, 8092 admin | `/data`, or Postgres | external probe, see below |
| `ghcr.io/seekeragentconnect/gateway-centrifugo` | 8000 client, 11000 internal | none | Centrifugo's own `/health` |
| `ghcr.io/seekeragentconnect/demo-signals` | 8092 | `/data` (SQLite) | external probe |
| `ghcr.io/seekeragentconnect/demo-prediction` | 8092 | `/data` (SQLite) | external probe |

Tags are the exact version (`0.0.1`), `sha-<commit>` with the full 40-character commit the release
was built from, and `latest`, which only stable releases move and only ever forwards.

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
  ghcr.io/seekeragentconnect/mcp-server:0.0.1
```

The process is PID 1 and handles `SIGTERM` itself: it stops accepting, finishes what is in flight,
closes SQLite and releases the instance lock. `docker stop` is a clean shutdown; the SQLite file in
the volume survives it and a restart picks up the same server identity and pairings.

### Compose

The presets live under `compose/` in the separate
[`do-deploy`](https://github.com/SeekerAgentConnect/do-deploy/tree/main/compose) repository, and
SEE-182 changed nothing there: they build nothing and still pull the legacy Docker Hub images
(`docker.io/brenat/seeker-agent-connect`) by default. Every one of them takes the image as a
variable, so running a GHCR image (or a local build) is one setting away. From a `do-deploy`
checkout:

```bash
MCP_SERVER_IMAGE=ghcr.io/seekeragentconnect/mcp-server:0.0.1 \
  docker compose -f compose/mcp/compose.yaml up -d
```

```bash
BROADCAST_IMAGE=ghcr.io/seekeragentconnect/gateway:0.0.1 \
  docker compose -f compose/feed/compose.yaml up -d
```

The variables are `MCP_SERVER_IMAGE`, `SKR_STAKING_IMAGE`, `BROADCAST_IMAGE`, `CENTRIFUGO_IMAGE`,
`COPYTRADING_IMAGE` and `PREDICTION_IMAGE`.

Without a checkout at all, this is a complete file:

```yaml
# compose.yaml
services:
  mcp-server:
    image: ghcr.io/seekeragentconnect/mcp-server:0.0.1
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

Pin an exact version everywhere, and a digest anywhere that matters. A digest cannot be moved.
No login is needed for any of this:

```bash
docker buildx imagetools inspect ghcr.io/seekeragentconnect/gateway:0.0.1
# "Digest: sha256:<digest>" is the multi-platform index; deploy that form
docker pull ghcr.io/seekeragentconnect/gateway@sha256:<digest>
```

Rolling back is pulling the previous exact version or digest. `latest` is a convenience for a first
look, not something to deploy.

Release candidates are published under `X.Y.Z-rc.N` — the image gets that version tag and its
`sha-<commit>` tag, the npm package the `next` dist-tag — and never move `latest`. To try one
deliberately:

```bash
npm install @seekeragentconnect/server-sdk@next
docker pull ghcr.io/seekeragentconnect/gateway:0.0.2-rc.1
```

Development builds, run by hand from the release workflow, are images only, tagged
`dev-sha-<commit>` and `develop`. They are for testing, never for a deployment.

---

## Moving from the Docker Hub images

Until SEE-182 every image was published to the one Docker Hub repository
`docker.io/brenat/seeker-agent-connect`, told apart by tag prefix. The release workflow no longer
publishes there. Each component now has its own GHCR repository, and every component's version
restarted at `0.0.1` as a new public baseline: `0.0.1` on GHCR is **newer** than the `0.1.x` tags on Docker Hub,
despite the lower number.

| Docker Hub (legacy) | GHCR |
| -- | -- |
| `docker.io/brenat/seeker-agent-connect:gateway-0.1.10` | `ghcr.io/seekeragentconnect/gateway:0.0.1` |
| `docker.io/brenat/seeker-agent-connect:centrifugo-0.1.2` | `ghcr.io/seekeragentconnect/gateway-centrifugo:0.0.1` |
| `docker.io/brenat/seeker-agent-connect:mcp-0.1.8` | `ghcr.io/seekeragentconnect/mcp-server:0.0.1` |
| `docker.io/brenat/seeker-agent-connect:skr-staking-mcp-0.1.3` | `ghcr.io/seekeragentconnect/mcp-skr-staking:0.0.1` |
| `docker.io/brenat/seeker-agent-connect:copytrading-0.1.7` | `ghcr.io/seekeragentconnect/demo-signals:0.0.1` |
| `docker.io/brenat/seeker-agent-connect:prediction-0.1.9` | `ghcr.io/seekeragentconnect/demo-prediction:0.0.1` |

No npm package was ever published under the old `@seeker-vault` scope, so there is nothing to
migrate there; `@seeker-vault/*` names in this repository's history refer to packages that only
ever existed inside the workspace.

**What an operator has to do.** Change the image reference and keep everything else. The runtime
contract is unchanged — environment variable names, ports, the `/data` path, the uid/gid `10001`,
the health checks and the database file names — so the existing named volumes mount onto the new
images unchanged and the services start on the same state. With the `do-deploy` presets that is
setting the component's image variable (above); with your own Compose file, the `image:` line:

```bash
docker compose pull
docker compose up -d
```

Do **not** run `docker compose down -v`. That removes the volumes, and with them the server
identity, the pairings and the publication history. The `do-deploy`
[deployment runbook](https://github.com/SeekerAgentConnect/do-deploy/blob/main/compose/README.md#7-back-up-replace-and-roll-back) lists the durable volume and
database names each deployment has carried, including the ones inherited from earlier layouts.

The Docker Hub tags are not deleted; anything already pinned to one, including the `do-deploy`
presets' defaults, keeps working. They simply stop receiving new versions.

---

## Android app

The signed APK is published as a GitHub Release of this repository, tagged `android-vX.Y.Z`, with
the asset `sac-X.Y.Z.apk` and a `SHA256SUMS` file. The repository's
[latest release](https://github.com/SeekerAgentConnect/sac/releases/latest) is always the newest
stable Android release; the other components' releases are never marked latest. The application id
is `io.github.brrenat.seekervault`.

Download both assets into one directory, then verify and install:

```bash
sha256sum -c SHA256SUMS
apksigner verify --print-certs sac-0.0.1.apk
# "Signer #1 certificate SHA-256 digest" must be
# 3b29a71fb3c5ff63c9b693f1e9e1fdc945bfd5231a2c1ec1537f4f7ad2164f28 (also in the release notes)
adb install --replace sac-0.0.1.apk
```

Every release is signed with the same release key — the certificate above, first used for
`0.0.1` — so an update installs over the previous version and keeps the app's data. An APK whose certificate does not match the one in the release
notes did not come from this release; do not install it. An app already installed from a build
signed with another key — a debug build, or one you signed yourself — cannot be replaced in place;
Android refuses the update until that app is uninstalled, which removes its data.
