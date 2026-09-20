# Optional Tailscale/Funnel example

This directory is an operator-specific example, not a module default. The portable services do not
know a tailnet name, Funnel port, host certificate location, or Tailscale workaround.

## Feed ingress

Start `deploy/feed/compose.yaml` first and set its public and stream settings to the node's MagicDNS
HTTPS origin. Obtain the node certificate into a host directory, then copy `.env.example` to `.env`
and run:

```sh
docker compose --env-file deploy/operators/tailscale/.env \
  -f deploy/operators/tailscale/compose.feed.yaml up -d
tailscale funnel --bg --tcp=443 tcp://localhost:9443
```

Funnel forwards raw TCP. TLS and HTTP/2 terminate in Caddy, so the unidirectional gRPC stream is not
downgraded. The proxy has no publisher, broker API, Redis, health, operator, or demo-control route.

## Direct server with production updates

Use the MCP server's native TLS listener, not the HTTP reverse-proxy example. Put the Tailscale PEM
files in a host directory, set `TAILSCALE_TLS_DIR` to its absolute path, set
`SIDECAR_PUBLIC_URL=https://<node-name>` and `MCP_SERVER_PORT=8443` in `deploy/mcp/.env`, then add the
overlay:

```sh
docker compose --env-file deploy/mcp/.env \
  -f deploy/mcp/compose.yaml \
  -f deploy/operators/tailscale/compose.direct.yaml up -d mcp-server
tailscale funnel --bg --tcp=443 tcp://localhost:8443
```

The raw TCP hop preserves the server's own TLS identity and HTTP/2 UpdateService stream. Certificate
renewal is an operator job: renew the two PEMs, verify ownership/readability, and replace only
`mcp-server`. Nothing here uses host networking.
