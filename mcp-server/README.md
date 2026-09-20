# Seeker Agent Connect MCP server

This is the self-hosted server for one owner, their one currently paired phone, and their agents. It
serves authenticated, sessionful MCP Streamable HTTP at `/mcp`, the direct phone API on the same
listener, and `GET /healthz`. It embeds `@seeker-vault/server-sdk` in-process through the SDK's two
public entry points. The server holds no wallet key, cannot approve a request, and never signs.

The same application entry point has three supported starts:

1. TypeScript source in this checkout;
2. the standalone Docker image built from this checkout; or
3. the executable npm tarball built locally from this checkout.

`@seeker-vault/mcp-server` and `@seeker-vault/server-sdk` are **not published to npm**, and no MCP
image from SEE-132 is published to a registry. The commands below build local artifacts. They do
not publish or upload either package.

## Requirements and boundaries

Source and local package creation require Node `24.21.0` and pnpm `12.3.4`. The installed npm CLI
requires Node `>=24.21.0 <25`; it does not require pnpm, TypeScript, Docker, a checkout, or a second
SDK package. Docker requires a recent Docker Engine/BuildKit; the image itself includes Node
24.21.0. SQLite is Node's built-in `node:sqlite`, so there is no database service.

No feed gateway, Centrifugo, Redis, CopyTrading demo, or Prediction demo is used or started. Solana
JSON-RPC and Firebase Cloud Messaging are optional. OAuth is an optional resource-server profile;
an external authorization server is required when it is enabled.

Two independent network legs matter:

- the agent connects to `/mcp`, often on the same machine at `http://127.0.0.1:8080/mcp`;
- the phone connects to the direct phone API named by `SIDECAR_PUBLIC_URL`.

Agent access to localhost does not make localhost reachable from a physical phone. A debug phone
can use loopback only with `adb reverse`; this task does not run or install Android on a device. A
real remote phone needs a reachable `https://` origin with a normally trusted certificate. The
production update stream also needs HTTP/2 end to end; use the direct TLS listener or a raw TCP/VPN
forward. Do not route this through the retired private gateway mode, disable certificate checks, or
mistake `MCP_ALLOWED_HOSTS` for phone reachability.

## Configuration and durable state

All secrets arrive at runtime. Copy [`.env.example`](.env.example), use an explicitly named config
file, or set environment variables. Existing `SIDECAR_*` names remain stable for upgrades.

| Setting | Required/default | Meaning |
| --- | --- | --- |
| `SIDECAR_HOST` | source/npm: required; image: `0.0.0.0` | Internal bind. Only loopback or a container wildcard is accepted. A wildcard requires an explicit public URL. |
| `SIDECAR_PORT` | source/npm: required; image: `8080` | One HTTP/TLS listener for health, MCP and direct unary phone APIs. |
| `SIDECAR_PUBLIC_URL` | loopback bind: derived; wildcard: required | Externally advertised direct-phone origin. HTTPS is required off loopback. |
| `MCP_ENABLED` | `true` | `false` removes `/mcp`; direct phone services remain. |
| `MCP_TOKEN` | required when MCP is enabled | Agent bearer token. It must differ from `PHONE_TOKEN`. |
| `PHONE_TOKEN` | required | Legacy live-diagnostic phone token, separate from paired-phone credentials. |
| `MCP_ALLOWED_HOSTS` | loopback only | Comma-separated extra `/mcp` Host/Origin names, without scheme or port. |
| `MCP_DEMO_TOOLS` | `false` | Adds the wallet-free `vault_request_ack` development tool. |
| `LIVE_COMMAND_TIMEOUT_SECONDS` | required, 1–3600 | Live diagnostic deadline. |
| `MCP_SERVER_DATA_DIR` | `~/.seeker-agent-connect/mcp-server` | Absolute stable application home for npm/source runs. Never the install, cache, temp directory, or current directory. |
| `MCP_SERVER_CONFIG` | `<data-dir>/config.env` if present | Absolute optional env-file path. CLI `--config` overrides it. Existing process variables win. |
| `DATABASE_PATH` | `<data-dir>/direct-server.db` | Direct SQLite store. A relative value is resolved under the data directory. Docker pins `/data/sidecar.db` for compatibility. |
| `REQUEST_TTL_SECONDS` | `86400` | Default durable-request lifetime, 60–604800. |
| `REQUEST_PENDING_LIMIT` | `100` | Maximum pending requests, 1–10000. |
| `PAIRING_TOKEN_TTL_SECONDS` | `600` | One-use pairing-code lifetime, 60–3600. |
| `SOLANA_RPC_URL` | off | Optional read-only preparation/confirmation provider. Without it transfer is not advertised. |
| `SOLANA_RPC_TIMEOUT_MS` | `10000` | Per-call provider timeout, 1000–20000. |
| `FCM_PROJECT_ID` | off | Optional notification invalidations; credentials come from ADC. |
| `SIDECAR_TLS_CERT_PATH`, `SIDECAR_TLS_KEY_PATH` | off; both or neither | PEM identity for the HTTP/2 + HTTP/1.1 production listener. |
| `SIDECAR_UPDATE_PORT` | off | Loopback-only cleartext HTTP/2 development listener; cannot accompany TLS. |
| `MCP_OAUTH_ISSUER` | off | Enables OAuth validation for `/mcp`; requires an external issuer. |
| `MCP_OAUTH_RESOURCE` | public URL + `/mcp` | Required token audience override. |
| `MCP_OAUTH_JWKS_URL` | issuer metadata | Public key-set override. |
| `MCP_OAUTH_SCOPE` | any | Space-separated required scopes. |

The database contains the lasting server ID, pairing and direct phone credential, requests,
results, wallet address/network binding, and update state. The MCP bearer token, TLS private key,
OAuth configuration, Firebase service-account credential, and wallet authorization do not belong
in the database or packaged assets.

One running server owns a database. A second launch against the same canonical file is refused even
on another port; a crashed process leaves a lock that is reclaimed after its PID is gone. Pairing
commands intentionally remain usable while the server is running.

## Option 1: source

From the repository root:

```sh
pnpm install --frozen-lockfile
cp .env.example .env
# replace the two token placeholders
pnpm dev:mcp-server
```

`pnpm dev:sidecar` is a documented migration alias for this source command. In another terminal:

```sh
curl --fail http://127.0.0.1:8080/healthz
pnpm pair
pnpm pair status
```

The health body is `{"status":"ok"}`. `pnpm pair` prints one private QR/URI; scan, paste, or type
that URI in the app. Pairing replaces the one active phone. `pnpm pair revoke` revokes its direct
credential and cancels its pending requests.

## Option 2: standalone Docker

The build uses the repository root only to compile the declared MCP package and unpublished SDK:

```sh
docker build -f mcp-server/Dockerfile \
  -t seeker-agent-connect/mcp-server:local .
cp deploy/mcp/.env.example deploy/mcp/.env
# replace the tokens; keep the local public URL for host-loopback development
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml up -d --build
curl --fail http://127.0.0.1:8080/healthz
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml exec mcp-server \
  node mcp-server/dist/cli.js pair
```

The Compose project publishes the container only on host loopback and persists `/data/sidecar.db`
in `seeker-agent-connect-mcp_mcp-data`. It starts no proxy or feed process. To use another host port,
set `MCP_SERVER_PORT` and make `SIDECAR_PUBLIC_URL` match the phone's real origin.

The tested reference is the local tag above. No SEE-132 image was pushed. If an operator later
pushes an image, pin its immutable digest; do not treat `:local` or the older `:sidecar-v1` image as
a public MCP-server release.

## Option 3: executable npm tarball

Build the staged, self-contained package and inspect exactly what npm will pack:

```sh
pnpm run build
npm pack --dry-run --json ./mcp-server/package
mkdir -p ./artifacts
npm pack --json --pack-destination ./artifacts ./mcp-server/package
```

The source workspace imports `@seeker-vault/server-sdk` through its public API. During the MCP build,
the SDK's built public runtime is copied under `dist/vendor/server-sdk` and emitted imports are
rewritten to that vendored output. The distributed manifest therefore has no workspace, file, or
unpublished registry SDK dependency. It is still the SDK implementation—not a copied/reimplemented
request engine—and the package test rejects private SDK imports.

For an ordinary local install, use the exact tarball path:

```sh
mkdir -p "$HOME/.local/seeker-agent-connect-mcp"
npm install --global --prefix "$HOME/.local/seeker-agent-connect-mcp" \
  "$PWD/artifacts/seeker-vault-mcp-server-0.1.0.tgz"
mkdir -p "$HOME/.seeker-agent-connect/mcp-server"
cp mcp-server/.env.example "$HOME/.seeker-agent-connect/mcp-server/config.env"
chmod 600 "$HOME/.seeker-agent-connect/mcp-server/config.env"
"$HOME/.local/seeker-agent-connect-mcp/bin/seeker-agent-connect-mcp" --version
"$HOME/.local/seeker-agent-connect-mcp/bin/seeker-agent-connect-mcp" start
```

Pair from another terminal with the same external config/state:

```sh
"$HOME/.local/seeker-agent-connect-mcp/bin/seeker-agent-connect-mcp" pair
"$HOME/.local/seeker-agent-connect-mcp/bin/seeker-agent-connect-mcp" pair status
```

Transient execution is also an explicit local-tarball operation:

```sh
npm exec --yes \
  --package "$PWD/artifacts/seeker-vault-mcp-server-0.1.0.tgz" -- \
  seeker-agent-connect-mcp --version
```

This `npm exec` starts a normal command. `seeker-agent-connect-mcp start` is a long-running HTTP
server; it is **not an MCP stdio child process**. Do not put this command in an agent's MCP
`command`/`args` fields.

After a future registry release exists, the equivalent version-pinned command would be:

```sh
# FUTURE ONLY — @seeker-vault/mcp-server is not published by SEE-132
npm exec --yes --package @seeker-vault/mcp-server@0.1.0 -- \
  seeker-agent-connect-mcp start
```

## Connect an agent

Start the server first, then configure the client for Streamable HTTP at its URL. The MCP token is
only for the agent leg. The one-use pairing token, lasting phone credential, and wallet
authorization are different credentials and are never given to the agent.

- [Hermes v0.21.3 instructions](https://github.com/BrRenat/SeekerAgentWallet/blob/master/docs/integrations/hermes.md)
- [OpenClaw 2026.9.5 instructions](https://github.com/BrRenat/SeekerAgentWallet/blob/master/docs/integrations/openclaw.md)
- [Hosted OAuth client requirements](https://github.com/BrRenat/SeekerAgentWallet/blob/master/docs/integrations/claude.md)

Static bearer access and OAuth are alternatives. If OAuth is enabled, remove the static bearer
header and use the client's real MCP OAuth login surface. Never turn off TLS verification.

## First request, result, and cancellation

After pairing, ask the agent to call `vault_get_capabilities`, then an operation it reports. For a
non-wallet walkthrough, enable `MCP_DEMO_TOOLS=true` and call `vault_request_ack` with text and a
unique `idempotency_key`. The response is PENDING with a `request_id`; it is not approval. On the
phone, open the request and answer it. Call `vault_get_request` until `terminal` is true. Repeating
the same request and idempotency key returns the same ID; it never creates duplicate execution.

Create another acknowledgement and call `vault_cancel_request` before answering it. The phone's
authoritative read must show it cancelled. Wallet signing and transfers remain manual on the phone;
a retry, restart, update notification, restore, or UNKNOWN result never signs or submits again.

## Updates, backup, restore, upgrade, and rollback

Stop source/npm with SIGINT or SIGTERM. The Docker stop sends SIGTERM. The process stops accepting,
drains active responses, closes MCP sessions and SQLite, releases its instance lock, and exits.

For a consistent hot backup while the server is up, use SQLite `VACUUM INTO` from a second read-only
connection, or stop it and copy the database plus no stale `-wal`/`-shm` files. Protect backups as
credentials. Restore only while the server is stopped, and never run two launch formats on one
store at once.

Legacy mappings are explicit:

| Old installation | New installation | Procedure |
| --- | --- | --- |
| source `sidecar/data/sidecar.db` | `<MCP_SERVER_DATA_DIR>/direct-server.db` | Stop old and new processes, back up the old file, copy it to the new path, start once, check server ID and `pair status`. |
| Docker volume `seeker-agent-wallet_sidecar-data`, `/data/sidecar.db` | same file path, or the new Compose volume | For zero-copy rollback, mount the existing volume at `/data`; otherwise stop, back up, copy into the new volume, then start. Never attach both containers. |
| image `...:sidecar-v1` | locally built `seeker-agent-connect/mcp-server:local` | Keep the old digest for rollback; change only the image while retaining `/data`, then verify health and identity. No new registry image is claimed here. |
| npm install/cache | external data/config directory | Stop, reinstall or change prefix/cache, and restart with the same external directory. No migration is performed inside npm's directories. |

The SDK runs the existing schema migrations transactionally. Before an update, record the image or
tarball version and back up the store. A binary older than the resulting schema refuses it rather
than guessing; rollback then requires restoring the matching backup. Direct pairings, credentials,
server identity, requests/results and update state otherwise remain compatible across source,
Docker and npm launches.

## Optional integrations and ingress

`SOLANA_RPC_URL` adds transfer preparation and confirmation reads; absence removes that capability.
`FCM_PROJECT_ID` enables content-free invalidations and uses ADC from a credential mounted outside
the image/package. `MCP_OAUTH_ISSUER` makes `/mcp` validate an external issuer's access tokens; this
application does not provide login, client registration, consent, accounts, or a new authority.

TLS, domains, Caddy and Tailscale/Funnel are separate deployment layers. For the phone's live update
stream, use the server's TLS listener with a trusted PEM identity and preserve HTTP/2, or forward raw
TCP. [`deploy/ingress/direct/`](../deploy/ingress/direct) is the separately managed Caddy example
for MCP and unary phone calls; it deliberately does not claim to proxy the production update
stream. [`deploy/operators/tailscale/`](../deploy/operators/tailscale) shows the native-TLS/raw-TCP
layout without host networking. The older gateway-private routing is retired and is not a
reachability solution.

The portable project mounts the explicitly named `MCP_VOLUME_NAME` at `/data`. Its clean default
preserves `seeker-agent-connect-mcp_mcp-data`; older `gateway/` and combined-server lineages are
selected explicitly and backed up first using the mapping in [`deploy/README.md`](../deploy/README.md).
Replace only `mcp-server` with `up -d --no-deps mcp-server`; ingress and every feed component stay
running.

## Logs and troubleshooting

Startup logs name the bind URL, public origin, database path/schema, stable server ID, pairing
status, served operations and optional integrations. They do not print bearer tokens, phone
credentials, requests, notes, RPC URLs, Firebase targets or TLS key bytes.

- `Invalid MCP server configuration`: fix every named variable; values are not echoed.
- `the direct store is already in use`: stop the other MCP server. Pairing commands may still run.
- `EADDRINUSE`: another process owns the listener; choose a different port or stop it.
- health works but an agent fails: confirm `/mcp`, the exact bearer token, Streamable HTTP, and
  `MCP_ALLOWED_HOSTS` for the hostname used.
- agent works but phone does not: check the second leg—`SIDECAR_PUBLIC_URL`, DNS, trusted TLS,
  HTTP/2 for updates, and phone reachability. Agent localhost proves none of those.
- transfer is absent: configure a matching `SOLANA_RPC_URL`; the server never chooses a network.
- OAuth metadata is absent: OAuth is off; use the static bearer path or configure the complete
  external issuer profile.
- database is newer than the binary: restore the pre-upgrade backup or run the newer binary.

For implementation boundaries see
[the MCP adapter note](https://github.com/BrRenat/SeekerAgentWallet/blob/master/docs/wiki/mcp-adapter.md),
the [Direct Server SDK](https://github.com/BrRenat/SeekerAgentWallet/blob/master/server-sdk/README.md),
and the [security model](https://github.com/BrRenat/SeekerAgentWallet/blob/master/docs/security.md).
