# SEE-87 — Make MCP an optional adapter over the existing server core

Stage 7.1, second child of SEE-85. Branch `superset/feat/see-85`.

## What this task is, and what it is not

The Node sidecar currently *is* its MCP endpoint: `MCP_TOKEN` is a required setting, `/mcp` is
always mounted, and the MCP tools reach the request store, the transaction preparer and the
confirmation tracker directly. This makes MCP one **optional adapter** over a core that stands
without it, so the Stage 7.1 Go publisher templates and the shared gateway are separate components
rather than things that have to speak MCP.

It is not a new protocol, not a plugin marketplace, not a rewrite in Go, and not a migration of
private agent traffic to the shared gateway. The default behaviour of an existing deployment does
not change.

## Where the seams already are

| Concern | Owner today |
| --- | --- |
| Configuration | `config.ts` — `mcpToken` is **required**; `mcpAllowedHosts`, `demoTools`, `oauth` are MCP-only |
| Startup / shutdown | `server.ts` `serve()` — builds the endpoint unconditionally, routes `/mcp`, closes it |
| The agent endpoint | `mcp-endpoint.ts` — Streamable HTTP, token/OAuth auth, `vault_display_command` |
| The agent's request tools | `requests/mcp-tools.ts` — takes `RequestStore` + `TransactionPreparer` + `ConfirmationTracker` |
| Request core | `storage/request-store.ts`, `requests/lifecycle.ts`, `requests/action.ts` |
| Phone-facing APIs | `phone-api.ts`, `requests/phone-service.ts`, `pairing/service.ts`, `updates/service.ts` |
| Push | `push/fcm.ts`, `push/invalidation.ts` |
| OAuth resource metadata | `oauth.ts`, served from `server.ts` at the well-known paths |

The adapter uses a small, countable set of core operations: `create`, `get`, `cancel`, `replayOf`,
`connectedWallet`, `activeWallet`, `pendingLimit`, the preparer's `checkAsset`, and the tracker's
`endpoint` + `settle`. That set is the boundary to name.

## Design

**1. One internal boundary — `requests/agent-api.ts`.** `AgentRequests` names exactly those
operations, with `transfers` and `confirmations` as optional sub-objects that are absent when no
chain endpoint is configured. `agentRequests(store, {preparer, tracker})` builds one. Idempotency,
validation, credentials and result handling stay where they are: the boundary forwards, it does not
reimplement.

**2. The adapter takes the boundary, not the core.** `createMcpEndpoint` and `registerRequestTools`
take `AgentRequests` instead of a store plus two collaborators. A stage-boundary check fails if an
adapter file names `RequestStore`, `TransactionPreparer` or `ConfirmationTracker` again.

**3. One switch, one source of truth.** `MCP_ENABLED` (default `true`) is the explicit switch. In
the typed config, `mcpToken === undefined` means the adapter is off — the same shape the sidecar
already uses for every optional subsystem (`solanaRpcUrl`, `fcmProjectId`, `oauth`). With the
adapter off:

- `MCP_TOKEN` is not required, and a leftover value is ignored with a log line. Turning the adapter
  off must not force an operator to edit credentials they may want back.
- `MCP_ALLOWED_HOSTS` and `MCP_DEMO_TOOLS` are likewise ignored, and named in the log.
- `MCP_OAUTH_*` is **refused**, because the OAuth profile publishes a public promise: metadata at
  the well-known paths telling clients where to authorize for an endpoint that would not exist.
  That is a contradiction rather than a leftover, and it mirrors the orphan rule `oauthConfig`
  already applies.

**4. Startup and shutdown stay generic.** `serve()` builds the endpoint through one small function
and skips it when the config has none; `/mcp` answers 404; `close()` closes what exists. Pairing,
the request store, the phone API, updates and push are untouched by the switch.

**5. Deployment assets extend Stage 7.** `gateway/compose.yaml` gains `MCP_ENABLED` and stops
making `MCP_TOKEN` a compose-level hard requirement, so the sidecar's own configuration error — the
one place that knows the rule — reports it and names `MCP_ENABLED=false` as the alternative. No new
compose file and no second stack.

## Items

- [x] `requests/agent-api.ts`: the boundary and its builder
- [x] `mcp-endpoint.ts` and `requests/mcp-tools.ts` take `AgentRequests`
- [x] `config.ts`: `MCP_ENABLED`, optional `mcpToken`, ignored-versus-refused MCP-only settings
- [x] `server.ts`: build the adapter only when configured; 404 `/mcp`; honest logs; close what exists
- [x] `sidecar/src/stage-boundary.test.ts`: adapters reach the core only through the boundary, and
      the core does not import the adapter
- [x] Tests: config for both modes; startup/shutdown/auth with the adapter off; pairing, phone API,
      updates and push unaffected; the MCP flow unchanged with it on
- [x] `gateway/compose.yaml`, `gateway/.env.example`, `.env.example`
- [x] `docs/wiki/mcp-adapter.md`: the Node private-server adapter versus the Go broadcast templates
- [x] `docs/development/sidecar.md`, `docs/guides/self-hosting.md`, `docs/architecture.md`,
      `AGENTS.md`, `CODEBASE.md`, `docs/changelog/2026-09-17.md`
- [x] `pnpm check`, `pnpm check:android`, and the acceptance suites; deliberate breaks

## Acceptance, from the ticket

- [x] Existing MCP + Hermes/test-agent flow still creates a durable private request and receives its
      result
- [x] A server with MCP disabled starts and serves the intended generic APIs without exposing `/mcp`
      or requiring an MCP token
- [x] Enabling/disabling the adapter does not change stored request identity, phone permissions,
      direct pairing or wallet-signing rules
- [x] Startup, shutdown, configuration and authentication regression tests cover both modes
- [x] Documentation distinguishes the Node private-server adapter from the Go broadcast publisher
      templates

## Review

**What landed.** One new file, `sidecar/src/requests/agent-api.ts`, naming the whole of what an
adapter may ask the request core for. `mcp-endpoint.ts` and `requests/mcp-tools.ts` take it instead
of a store plus two collaborators. `config.ts` gained `MCP_ENABLED` and made `mcpToken` optional;
`server.ts` builds the endpoint in one `mcpAdapter` function or not at all, answers 404 at `/mcp`
when there is none, and closes what exists.

**Nothing about the existing workflow changed.** Tool names, arguments, results, error codes and
result delivery are identical, a stored request's identity does not depend on which adapter created
it, and the wallet is still asked only after the owner approves. The default is on, so a deployment
that has never heard of the setting behaves exactly as it did — the only visible difference with
MCP on is one sentence in the `MCP_TOKEN is not set` message, which now names the other way out.

**Three decisions worth recording.**
1. *The switch is one setting, and the typed config has one source of truth.* `mcpToken === undefined`
   means the adapter is off. This is the shape every other optional subsystem here already uses
   (`solanaRpcUrl`, `fcmProjectId`, `oauth`), and it cannot disagree with itself the way a separate
   boolean plus a token could. `MCP_ENABLED` is the env-level switch the loader reads; the loader is
   what drops a leftover token.
2. *Ignored versus refused, on a stated line.* `MCP_TOKEN`, `MCP_ALLOWED_HOSTS` and
   `MCP_DEMO_TOOLS` can outlive the adapter, so they are ignored and **named in the startup log** —
   turning MCP off should not force an operator to delete credentials they may want back.
   `MCP_OAUTH_*` is refused, because the profile publishes metadata telling clients where to
   authorize for an endpoint that would not exist. That is a contradiction, not a leftover, and it
   mirrors the orphan rule `oauthConfig` already applied.
3. *404, not 401.* A deployment without the adapter has no such endpoint. An authentication
   challenge would suggest that some credential would open one.

**What the guards now hold.** `sidecar/src/stage-boundary.test.ts` fails if an adapter file names
`RequestStore`, `TransactionPreparer` or `ConfirmationTracker` again; if any shipped source but
`server.ts` imports the endpoint; if the boundary opens a listener, runs SQL, reads an
`Authorization` header, or constructs a store; or if `createMcpEndpoint` is called anywhere but the
one place.

**Deployment.** `gateway/compose.yaml` gained `MCP_ENABLED` and no new file, so Stage 7's stack was
extended rather than forked. `MCP_TOKEN` stopped being a compose-level hard requirement, because
whether it is required depends on the switch and the sidecar's own configuration check is the one
place that knows the rule.

**Verification.** `pnpm check` (475 sidecar tests, 36 test-agent tests, 0 failures),
`pnpm check:generated`, `pnpm check:android` (911 Android tests, untouched by this change),
`pnpm test:hello`, `pnpm test:queue`, `pnpm test:transfer`, `pnpm test:updates` and `pnpm test:push`
all pass. `docker compose config` resolves the stack with the switch true, false, and false with no
`MCP_TOKEN` at all. Four deliberate breaks, each time-limited and restored with `cmp`:

| Break | Failed |
| --- | --- |
| `mcp-tools.ts` importing `RequestStore` again | the boundary check |
| `phone-api.ts` re-exporting from `mcp-endpoint.ts` | the boundary check |
| `/mcp` answering 401 instead of 404 with no adapter | `the sidecar without the MCP adapter` |
| `MCP_TOKEN` required regardless of the switch | three configuration cases |

**Caveats.** With the adapter off nothing else creates requests in this stage, so the phone sees an
empty inbox; that is the honest state of the core standing on its own, and a second request source
is a later child's work. The test-agent container is an MCP client and has nothing to talk to in
that mode. **Physical-device evidence: NOT RUN**, and there is none to run — no phone-facing
behaviour changed. **No Docker daemon was reachable**, so nothing was built or run as a container;
`docker compose config` parses the stack without one.
