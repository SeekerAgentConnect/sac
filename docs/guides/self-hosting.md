# Self-host the direct MCP server

The direct server is one owner's MCP endpoint and paired-phone request service. Its portable Docker
deployment is [`deploy/mcp/compose.yaml`](../../deploy/mcp/compose.yaml). It starts only
`mcp-server`: no feed gateway, Redis, demo, reverse proxy, domain, certificate, OAuth issuer, or
Tailscale process is bundled with it.

For source and npm-package starts, every application setting, agent-client example, request flow,
and recovery procedure, use the [`mcp-server` guide](../../mcp-server/README.md). This page covers
the deployment boundary and safe Docker operations.

## Local portable deployment

Requirements are Docker with Compose v2 and a repository checkout. From the repository root:

```sh
cp deploy/mcp/.env.example deploy/mcp/.env
# replace MCP_TOKEN and PHONE_TOKEN with different random values
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml up -d --build
curl --fail http://127.0.0.1:8080/healthz
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml exec mcp-server \
  node mcp-server/dist/cli.js pair
```

The default host bind is `127.0.0.1:8080`. The application listens on `0.0.0.0:8080` only inside
its container. Its MCP endpoint is `http://127.0.0.1:8080/mcp`; the direct phone API shares that
listener. A physical phone cannot reach the host's loopback address, so use a real HTTPS origin for
a remote phone. Never restore the retired private gateway routing or disable TLS verification.

The portable project creates the internal network named by `DIRECT_INGRESS_NETWORK`, but nothing is
published on that network unless an independent ingress is started. `MCP_ALLOWED_HOSTS` controls
accepted MCP Host/Origin values; it is not a reachability or TLS setting.

## Optional public ingress

The independent [`deploy/ingress/direct`](../../deploy/ingress/direct) project can publish MCP,
OAuth resource metadata, pairing, and unary request calls through a normal domain:

```sh
cp deploy/ingress/direct/.env.example deploy/ingress/direct/.env
docker compose --env-file deploy/ingress/direct/.env \
  -f deploy/ingress/direct/compose.yaml up -d
```

DNS must resolve to the host and ports 80 and 443 must reach it. Set `SIDECAR_PUBLIC_URL` in
`deploy/mcp/.env` to the same HTTPS origin and replace only `mcp-server`. The ingress has its own
Compose project and lifecycle; restarting it does not recreate the application or its data.

That HTTP reverse-proxy example deliberately does not advertise the production UpdateService
stream. Production updates require HTTP/2 end to end. Terminate TLS in the application and use a
raw TCP/VPN forward, as in the isolated
[`deploy/operators/tailscale`](../../deploy/operators/tailscale/README.md) example. The portable
deployment contains no tailnet, Funnel, certificate path, or host-specific networking.

No public form exposes `/healthz`, the legacy live diagnostic, an admin surface, a database, or a
credential endpoint. OAuth remains validation against an external authorization server; this
repository does not add an identity provider.

## Data identity and backup

SQLite remains local to the MCP server. The default Compose project is
`seeker-agent-connect-mcp`, the explicit physical volume is
`seeker-agent-connect-mcp_mcp-data`, and the file is `/data/sidecar.db`. The container runs as
UID/GID `10001:10001`.

Older installations have separate valid identities. The direct deployment in retired `gateway/`
used `seeker-agent-wallet_sidecar-data`; the old combined server used
`seeker-agent-wallet-server_sidecar-data`. To keep either lineage, set `MCP_VOLUME_NAME` to that
exact inspected volume and keep `DATABASE_PATH=/data/sidecar.db`. The complete mapping and migration
rules are in [`deploy/README.md`](../../deploy/README.md#persistent-identities-and-upgrades).

Never delete an unknown volume, run Compose's volume-removing down command to silence a warning, or
merge two non-empty SQLite files. Inventory and inspect first:

```sh
docker volume ls
docker volume inspect <exact-volume-name>
```

For a cold backup, stop the sole writer and archive only the chosen exact volume:

```sh
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml stop mcp-server
mkdir -p backups
docker run --rm \
  -v seeker-agent-connect-mcp_mcp-data:/from:ro \
  -v "$PWD/backups:/to" alpine:3.22 \
  sh -c 'cd /from && tar czf /to/mcp-data.tgz .'
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml start mcp-server
```

If `MCP_VOLUME_NAME` selects a legacy volume, substitute that one exact physical name in the backup
command. Restores happen only while the server is stopped, retain UID/GID ownership, and must be
followed by an integrity check plus verification of the stable server ID and pairing status.

## Replace, roll back, and remove

Build or pull a replacement, take a backup, then target only the MCP service:

```sh
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml \
  up -d --build --no-deps mcp-server
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml ps
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml logs --tail=100 mcp-server
```

Pairing, credentials, server identity, requests, results, and update state remain in SQLite. An
older binary must not open a schema migrated by a newer binary; rollback means the old image plus
its matching pre-upgrade archive.

Stopping or removing the container does not require removing its volume:

```sh
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml down
```

The data stays available for a later start. Delete a volume only as a separate, explicit operator
decision after its exact identity and backup have been verified.

## Troubleshooting

- A local agent receives 401: use `MCP_TOKEN`, not the phone or pairing credential.
- A phone cannot connect: confirm `SIDECAR_PUBLIC_URL` is the phone-reachable HTTPS origin and that
  the certificate is normally trusted.
- Pairing fails after a restore: verify the selected physical volume, `/data/sidecar.db`, file
  ownership, and the stable server ID before creating a new pairing.
- MCP works but production updates do not: the phone path lost HTTP/2; use native TLS plus a raw
  TCP/VPN forward.
- Compose warns about another volume: stop and inspect both. Do not remove either to hide the
  warning.
