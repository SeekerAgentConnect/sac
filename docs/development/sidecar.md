# Sidecar

The sidecar is the self-hosted TypeScript/Node server that sits between agents and the phone. In Stage 1 it runs the live diagnostic flow from [`docs/protocol.md`](../protocol.md): an agent calls an MCP tool, the Seeker shows the text, and the user's OK goes back to the agent. All state is in memory, and the sidecar listens on loopback only.

## Configuration

`pnpm dev:sidecar` reads the git-ignored root `.env`; start from `.env.example`. Variables already set in the environment take precedence over `.env`. Every variable is required.

| Variable | Meaning | Rules |
| --- | --- | --- |
| `SIDECAR_HOST` | Address to listen on | `127.0.0.1`, `::1`, or `localhost`. Stage 1 never listens beyond the machine. |
| `SIDECAR_PORT` | Port to listen on | 1 to 65535; `.env.example` uses 8080 |
| `MCP_TOKEN` | Bearer token that agents send to `/mcp` | At least 32 characters, and not the placeholder |
| `PHONE_TOKEN` | Bearer token that the phone sends to the Connect API | At least 32 characters, not the placeholder, and different from `MCP_TOKEN` |
| `LIVE_COMMAND_TIMEOUT_SECONDS` | How long a command waits for the user's OK | 1 to 3600 |
| `MCP_URL` | Not read by the sidecar; the test agent (SAW-005) uses it | None |

Generate each token with `openssl rand -hex 32`. If the configuration is invalid, the sidecar names every problem and exits with status 1. It never prints a token value.

## Start and stop

```bash
cp .env.example .env    # then replace both token placeholders
pnpm dev:sidecar
```

The expected output is:

```text
[sidecar] listening on http://127.0.0.1:8080: MCP at http://127.0.0.1:8080/mcp, phone API at http://127.0.0.1:8080/seekervault.live.v1.LiveCommandService
```

To check that it's up, run `curl -s http://127.0.0.1:8080/healthz`, which prints `{"status":"ok"}`.

To stop it, press Ctrl+C or send SIGTERM. Stopping happens in this order:

1. The in-flight command, if any, is cancelled, and the agent gets `CANCELLED`.
2. The phone's stream ends with `unavailable`.
3. The process exits within about a second.

A restart loses the in-flight command by design, and nothing is replayed after it.

`pnpm build` compiles the sidecar to `sidecar/dist`. To run that build from the repository root, use `node --env-file-if-exists=.env sidecar/dist/main.js`.

## Endpoints

| Path | Caller | Authentication | Purpose |
| --- | --- | --- | --- |
| `GET /healthz` | Anything on the machine | None | Liveness check: `{"status":"ok"}` |
| `/mcp` | Agents | `Authorization: Bearer <MCP_TOKEN>` | MCP Streamable HTTP with sessions, and the tool `vault_display_command` |
| `/seekervault.live.v1.LiveCommandService/WatchCommands` | The phone | `Authorization: Bearer <PHONE_TOKEN>` | Connect server stream of live commands |
| `/seekervault.live.v1.LiveCommandService/AcknowledgeCommand` | The phone | `Authorization: Bearer <PHONE_TOKEN>` | Connect unary call that acknowledges a command |

The sidecar checks requests to `/mcp` as follows, following the MCP transport specification's defense against DNS rebinding:

- **Host header:** must be a loopback name (`127.0.0.1`, `localhost`, or `[::1]`), on any port so that SSH tunnels work. Otherwise it returns 403.
- **Origin header:** if present, it must also be a loopback origin. Otherwise it returns 403.
- **Token:** a missing or wrong token gets 401 with `WWW-Authenticate: Bearer`.
- **Session:** an unknown session ID gets 404, and the client then starts a new session.
- **Request format:** the MCP SDK checks the `Accept` and `Content-Type` headers and the protocol version.

On the phone API:

- **Token:** a missing or wrong token fails with `unauthenticated`. The MCP token is refused here, and the phone token is refused on `/mcp`.
- **Size:** each message is limited to 64 KiB.

**Tokens and logs:** tokens are read only from the `Authorization` header, compared in constant time, and never logged. Log lines carry command IDs and sizes, never command text.

## The MCP tool

`vault_display_command` takes `{"text": string}`. To call it from the command line, run `pnpm agent hello "Hello Seeker"`; see [`test-agent/README.md`](../../test-agent/README.md). To connect Hermes, on the Mac or on a VPS, see [`docs/integrations/hermes.md`](../integrations/hermes.md).

- **When the phone acknowledges the command,** the result carries `structuredContent: {"id": "<command UUID>", "result": "OK"}`, plus the same JSON as text.
- **When the command fails,** the result has `isError: true`, and its text starts with the error code:
  - `OFFLINE:`
  - `BUSY:`
  - `INVALID_TEXT:`
  - `TIMEOUT:`
  - `CANCELLED:`

  Failures carry no `structuredContent`, because MCP clients validate it against the success schema.
- **Set the client's request timeout above `LIVE_COMMAND_TIMEOUT_SECONDS`.** The MCP TypeScript SDK's default is 60 seconds.

The call is cancelled when the agent cancels it (`notifications/cancelled`) or when the agent's HTTP connection drops before the answer.

## Examples

To run these from a shell, export the tokens first: `set -a; source .env; set +a`.

A request without the MCP token:

```console
$ curl -si -X POST http://127.0.0.1:8080/mcp -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'
HTTP/1.1 401 Unauthorized
Content-Type: application/json
WWW-Authenticate: Bearer realm="seeker-vault"

{"jsonrpc":"2.0","error":{"code":-32000,"message":"a valid MCP token is required"},"id":null}
```

A request from a non-loopback origin, for example a web page after DNS rebinding:

```console
$ curl -s -X POST http://127.0.0.1:8080/mcp -H "Authorization: Bearer $MCP_TOKEN" \
    -H 'Origin: http://evil.example' -H 'Content-Type: application/json' -d '{}'
{"jsonrpc":"2.0","error":{"code":-32000,"message":"the Origin header is not a loopback origin"},"id":null}
```

The phone API called with the MCP token:

```console
$ curl -s -X POST http://127.0.0.1:8080/seekervault.live.v1.LiveCommandService/AcknowledgeCommand \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $MCP_TOKEN" \
    -d '{"acknowledgement":{"id":"x","result":"ACKNOWLEDGEMENT_RESULT_OK"}}'
{"code":"unauthenticated","message":"a valid phone token is required"}
```

Tool errors, as the agent sees them:

```text
OFFLINE: no phone is watching; open the live-test screen and connect
BUSY: another live command is in flight
INVALID_TEXT: text is empty
TIMEOUT: no acknowledgement within 60 seconds
CANCELLED: the command was cancelled: the phone disconnected
```

Acknowledgement errors, as the phone sees them:

- `deadline_exceeded`: "the command timed out before it was acknowledged"
- `canceled`: "the command was cancelled before it was acknowledged"
- `not_found`: "the sidecar is not tracking this command"

Typical log lines:

```text
[sidecar] phone connected
[sidecar] MCP session opened
[sidecar] command 1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed sent (12 bytes)
[sidecar] command 1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed acknowledged
[sidecar] rejected POST /mcp: a valid MCP token is required
```

## Code and tests

| File | Role |
| --- | --- |
| `sidecar/src/main.ts` | Entry point: loads the configuration, starts the server, and stops it on SIGINT or SIGTERM |
| `sidecar/src/server.ts` | The HTTP server: `/healthz`, `/mcp`, the phone API, and a graceful close |
| `sidecar/src/mcp-endpoint.ts` | MCP sessions, `vault_display_command`, and the Host, Origin, and token checks |
| `sidecar/src/phone-api.ts` | The Connect `LiveCommandService` |
| `sidecar/src/live/bridge.ts` | The in-memory waiter: one watcher, one in-flight command, deadline timers, and cancellation |
| `sidecar/src/live/command.ts` | The protocol rules from SAW-002 |

`pnpm check` runs these tests:

- **`src/live/bridge.test.ts`** tests the waiter with mocked timers.
- **`src/server.test.ts`** tests the real server with the MCP SDK client and a Connect client. It covers:
  - acknowledgement, OFFLINE, BUSY, and INVALID_TEXT
  - timeout, including a late acknowledgement
  - cancellation, both by the agent and by a dropped agent connection
  - the phone disconnecting, and the phone being replaced by a newer stream
  - missing, wrong, and swapped tokens
  - the Host and Origin checks
  - `/healthz`
  - that no token or command text reaches the logs
- **`src/restart.test.ts`** runs `src/main.ts` as a real process. It stops the process with SIGTERM and then with SIGKILL during a command, and checks that the original caller fails and nothing is replayed after the restart.

## Verification record: SAW-003

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`toolchain.md`](toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: 65/65 tests, including 13 integration tests and 2 restart tests |
| Real MCP SDK client with a Connect phone client | PASS. The call completes only after the phone acknowledges the same command ID. OFFLINE fails at once, and BUSY and INVALID_TEXT are returned. TIMEOUT refuses the late acknowledgement. CANCELLED is returned on agent cancellation, a dropped agent connection, a phone disconnect, and phone replacement. |
| Wrong and swapped tokens | PASS. A wrong token or the phone token on `/mcp` gets 401. A missing token, a wrong token, or the MCP token on the phone API gets `unauthenticated`, for `AcknowledgeCommand` too. |
| Restart during a command | PASS. After SIGTERM the caller gets `CANCELLED`; after SIGKILL the caller's request fails. After the restart the phone receives only `ready`, the old ID is `not_found`, and a new call works. |
| Live `pnpm dev:sidecar` | PASS: the curl examples above produced the documented responses, SIGINT stopped the sidecar cleanly, and no token appeared in the log |
| Deliberate breaks | Each of these failed the matching integration tests: skipping the token check, disabling cancellation, dropping the Origin check |
| `pnpm build`, `pnpm check:generated`, `pnpm check:android` | PASS |
| Physical Seeker | NOT RUN: the Android screen arrives in SAW-004 |
