# OpenClaw

This guide targets OpenClaw
[2026.9.5](https://github.com/openclaw/openclaw/releases/tag/v2026.9.5). Its configuration follows
that version's official [MCP registry](https://github.com/openclaw/openclaw/blob/v2026.9.5/docs/cli/mcp/registry.md),
[transport](https://github.com/openclaw/openclaw/blob/v2026.9.5/docs/cli/mcp/transports.md), and
[environment](https://github.com/openclaw/openclaw/blob/v2026.9.5/docs/help/environment.md) references.

OpenClaw is not installed in the SEE-132 build environment, so a real OpenClaw 2026.9.5 run with
the packaged artifact is **NOT RUN**. The npm artifact is independently exercised with the MCP SDK
over the same Streamable HTTP endpoint, including tool discovery and a complete request/phone
result round trip. Use the checks below as the follow-up on a host with OpenClaw installed.

## Start the server first

Choose the source, standalone Docker, or exact npm-tarball route in
[`mcp-server/README.md`](../../mcp-server/README.md). Keep the process running and confirm:

```sh
curl --fail http://127.0.0.1:8080/healthz
```

The server implements sessionful MCP Streamable HTTP at `/mcp`. It does not implement MCP stdio.
Do not put `npx`, `npm exec`, or `seeker-agent-connect-mcp start` into an OpenClaw MCP
command/arguments entry: npm starts a long-running HTTP server, not a child process speaking stdio.

## Add the HTTP server

Store the agent credential outside the registry entry. The following source-start example reads the
root `.env`; for Compose read `deploy/mcp/.env`, and for npm read the `config.env` passed to the
executable:

```sh
mkdir -p ~/.openclaw
printf 'MCP_SEEKER_VAULT_API_KEY=%s\n' "$(grep '^MCP_TOKEN=' .env | cut -d= -f2)" >> ~/.openclaw/.env
chmod 600 ~/.openclaw/.env
```

If that variable already exists, edit it rather than appending a second value. Then register the
server with the transport named explicitly:

```sh
openclaw mcp set seeker_vault '{
  "url":"http://127.0.0.1:8080/mcp",
  "transport":"streamable-http",
  "headers":{"Authorization":"Bearer ${MCP_SEEKER_VAULT_API_KEY}"},
  "connectionTimeoutMs":30000,
  "requestTimeoutMs":90000,
  "toolFilter":{"include":[
    "vault_display_command",
    "vault_get_address",
    "vault_get_capabilities",
    "vault_sign_message",
    "vault_transfer",
    "vault_request_ack",
    "vault_get_request",
    "vault_cancel_request"
  ]}
}'
```

`transport` is deliberate: an omitted transport selects OpenClaw's legacy SSE behavior, which is
not this endpoint. `requestTimeoutMs` is longer than the default 60-second live-display deadline;
durable tools return immediately. Tools the deployment does not serve are absent: the demo
acknowledgement needs `MCP_DEMO_TOOLS=true`, and transfers need `SOLANA_RPC_URL`.

The OpenClaw-qualified tool name is `seeker_vault__<tool>`, for example
`seeker_vault__vault_get_capabilities`.

## Verify the real client

With the server running:

```sh
openclaw mcp doctor seeker_vault --probe
openclaw mcp probe seeker_vault --json
```

The probe must connect over `streamable-http` and discover the intended tools. A listing alone is
not a request/result test. Pair a phone, enable the wallet-free demo tool, call
`vault_request_ack` with a unique `idempotency_key`, answer it on the phone, and call
`vault_get_request` until it is terminal. PENDING means stored, not approved.

The `MCP_TOKEN` is only the agent credential. A pairing code is a one-use credential, the paired
phone receives another credential, and wallet authorization remains inside the wallet app. Never
reuse one for another role or put any of them in a prompt.

## Remote agents and the phone's second leg

When OpenClaw is on another machine, change `url` to an address that machine can reach and add its
host to `MCP_ALLOWED_HOSTS`. Use trusted HTTPS on an exposed network; do not disable certificate
verification.

The phone separately calls `SIDECAR_PUBLIC_URL`. OpenClaw reaching `127.0.0.1` does not make that
address reachable from a physical phone. A real phone needs a reachable authenticated HTTPS origin,
and the live update stream needs HTTP/2 end to end. Use a direct TLS listener, VPN, or raw TCP
forward as documented by the deployment; do not restore gateway-private routing. Pair only after
the advertised URL is the one the phone can actually reach.

Static bearer configuration above is the tested baseline surface. The server can instead validate
OAuth access tokens from an external issuer, but do not leave this fixed bearer header in place for
that profile; configure a client-supported OAuth flow and verify it separately.
