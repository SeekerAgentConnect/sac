# SEE-9 / SAW-003 — Implement the live MCP command bridge

Linear: https://linear.app/seekeragentwallet/issue/SEE-9 · Branch: `develop`

## Checklist

- [x] Dependencies: `@modelcontextprotocol/sdk` 1.30.0, `@connectrpc/connect` and `@connectrpc/connect-node` 2.2.0, `zod` 4.6.1 (the newest release at least a day old)
- [x] `live/bridge.ts`:
  - [x] one watcher, where the newest wins
  - [x] one in-flight command
  - [x] deadline timer
  - [x] cancellation on agent abort, phone disconnect, replacement, or shutdown
- [x] `phone-api.ts`: Connect `LiveCommandService` with the phone token; the `ready` event; errors mapped to Connect codes
- [x] `mcp-endpoint.ts`:
  - [x] stateful Streamable HTTP sessions
  - [x] `vault_display_command` with `outputSchema`
  - [x] Host and Origin loopback checks, and a 401 for a bad token
  - [x] cancellation when the agent's connection drops
- [x] `server.ts`:
  - [x] `/healthz`, `/mcp`, and the phone API on one server
  - [x] graceful close
- [x] `main.ts`: signal handling
- [x] `auth.ts`: constant-time bearer check
- [x] Tests:
  - [x] bridge unit tests with mocked timers
  - [x] integration tests with the real MCP SDK and Connect clients
  - [x] child-process restart test (SIGTERM and SIGKILL)
- [x] Docs:
  - [x] `docs/development/sidecar.md`, with a verification record
  - [x] protocol doc, README, toolchain, `CODEBASE.md`
  - [x] changelog, decisions
- [x] Verify:
  - [x] `pnpm check` (65/65), `check:generated`, `build`, `check:android`
  - [x] a live `pnpm dev:sidecar` run with the documented curl examples
  - [x] deliberate failures
- [ ] Commit, push, and confirm CI is green
- [ ] Linear: tick the SEE-9 checklist and move it to In Review

## Review

**What changed.** `pnpm dev:sidecar` now runs the Stage 1 sidecar on loopback. It serves three things:

- `/mcp`: MCP Streamable HTTP with sessions and `vault_display_command`
- the phone's Connect `LiveCommandService`
- `/healthz`

They all share an in-memory `LiveCommandBridge`, which holds one phone stream, where the newest one wins, and one waiting call. That call ends in one of these ways:

- the phone's OK, which returns `{id, result: "OK"}`
- OFFLINE, BUSY, or INVALID_TEXT, at once
- TIMEOUT, at the deadline
- CANCELLED, when the agent cancels, the agent's connection drops, the phone disconnects or is replaced, or the sidecar shuts down

MCP and phone requests use separate bearer tokens, compared in constant time and never logged. `/mcp` rejects non-loopback Host and Origin headers.

**How it was verified.** The verification record is in `docs/development/sidecar.md`. It covers:

- the integration suite, with the real MCP SDK client and a Connect phone client
- real-process restarts on SIGTERM and SIGKILL, with no replay afterwards
- the documented curl examples, run against a live `pnpm dev:sidecar`
- three deliberate breaks (token check, cancellation, Origin check), each caught by its tests

**Caveats:**

- **Error results carry no `structuredContent`.** MCP clients validate that field against the success schema even on errors. The error code is instead the prefix of the error text.
- **Idle MCP sessions stay open.** They live until the client sends DELETE or the sidecar restarts. That's acceptable for a loopback development tool.
- **Agents need a longer timeout.** An agent's MCP request timeout must exceed `LIVE_COMMAND_TIMEOUT_SECONDS`; the SDK's default is 60 seconds. The test agent in SAW-005 sets it explicitly.
- **The device check is NOT RUN.** It waits for the Android screen in SAW-004.
