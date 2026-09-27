# Self-host Seeker Agent Connect

The one step-by-step deployment runbook is [`deploy/README.md`](../../deploy/README.md). Start there
for direct MCP only, public feeds only, or the collision-free combined-host setup. It includes
native HTTPS/HTTP/2 updates, credentials, publisher registration, SAC verification, restarts,
backup, rollback, and troubleshooting.

This page is a boundary reference. It deliberately does not duplicate commands from the canonical
runbook.

## Deployment boundaries

- [`deploy/mcp`](../../deploy/mcp) starts one owner's direct MCP server and database. The generic
  [`compose.tls.yaml`](../../deploy/mcp/compose.tls.yaml) overlay mounts operator-managed PEM files
  and preserves HTTP/2 to UpdateService without requiring Tailscale.
- [`deploy/feed`](../../deploy/feed) starts the shared public-feed gateway, Centrifugo, Redis, and a
  local operator profile. [`deploy/ingress/feed`](../../deploy/ingress/feed) is its separately
  managed public HTTPS edge.
- [`deploy/copytrading`](../../deploy/copytrading) and
  [`deploy/prediction`](../../deploy/prediction) each start one demo and one database. Neither owns
  or imports the gateway or the other demo.
- [`deploy/operators/tailscale`](../../deploy/operators/tailscale) is optional operator-specific
  routing. It is not the generic production path.
- [`deploy/ingress/direct`](../../deploy/ingress/direct) is an independent optional Caddy project
  for MCP and unary phone calls. It is not the complete production path because it does not carry
  the bidirectional UpdateService stream.

Every application remains an independent Compose project. The combined overlays add only one
private demo-to-gateway publication network; publishers do not gain ingress/trusted-proxy status.

## What this needs from outside

A public deployment needs resources the repository does not issue or operate:

- DNS names and normally trusted certificates for the origins the phone uses;
- public TCP reachability for the ports selected in the canonical guide;
- a Solana RPC provider only when direct transfer preparation/confirmation is enabled;
- a prediction provider for the Prediction demo (the documented keyless default is sufficient);
- an external authorization server only when optional MCP OAuth is enabled; and
- an operator-supplied Firebase project/credential only when optional wake-up hints are enabled.

The repository supplies no hosted service, certificate authority, OAuth issuer, Firebase project
or wallet key. It does publish the SDK and both MCP servers on npm under `@seeker_agent_connect`,
and the gateway, MCP servers and demos as images in `docker.io/brenat/seeker-agent-connect`
([docs/guides/installation.md](installation.md)); running them is still entirely yours to operate.

## Security and data rules

The direct server holds no wallet key and cannot approve or sign. Agent, phone, publisher, and
operator credentials are distinct. Public-feed publishers never learn subscribers, owner choices,
approvals, signatures, or outcomes.

SQLite files and the MCP ownership database belong on local storage with working filesystem locks.
NFS/SMB is unsupported. A persistent MCP `*.mcp-server-owner.sqlite` file is normal: the live
exclusive transaction, not file presence or a PID, establishes ownership. Never delete it to
recover from a crash.

The current and legacy physical volume mapping, one-time ownership upgrade rule, exact cold-backup
procedure, and restore/rollback constraints are maintained only in
[`deploy/README.md`](../../deploy/README.md#7-back-up-replace-and-roll-back).

## Application references

- Direct application settings and source/npm starts: [`servers/mcp-server/README.md`](../../servers/mcp-server/README.md)
- Hermes: [`docs/integrations/hermes.md`](../integrations/hermes.md)
- OpenClaw: [`docs/integrations/openclaw.md`](../integrations/openclaw.md)
- Feed operation and publisher registration: [`services/gateway/README.md`](../../services/gateway/README.md)
- Feed publisher development: [`docs/guides/server-development.md`](server-development.md)
- Optional Firebase: [`docs/guides/firebase.md`](firebase.md)
- General troubleshooting: [`docs/guides/troubleshooting.md`](troubleshooting.md)
