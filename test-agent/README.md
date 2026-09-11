# test-agent

A minimal MCP client for regression tests and demos. It calls the sidecar's tools through the same MCP Streamable HTTP interface that Hermes uses, with no LLM involved. `hello` sends Stage 1's `vault_display_command` and prints the phone's acknowledgement. `ack`, `get`, and `cancel` drive Stage 2's durable requests.

## Usage

Run it from the repository root, while the sidecar is running and the phone's live-test screen is connected:

```console
$ pnpm --silent agent hello "Hello Seeker"
Showing the text on the phone; waiting up to 75 s for OK...
{"id":"1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed","result":"OK"}
```

- **`hello [text]`:** discovers `vault_display_command` and calls it with the text, which defaults to `Hello Seeker`. Once the user taps OK, it prints the acknowledgement on stdout. Progress messages and errors go to stderr.
- **`ack <text>`:** queues the text for the owner to acknowledge (`vault_request_ack`), and prints the request, PENDING, as JSON. It doesn't wait: the owner answers later on the phone ([`docs/guides/pending-requests.md`](../docs/guides/pending-requests.md)). The phone must be paired first. Options:
  - `--key <key>`: the idempotency key. Without it, the agent makes one and prints it on stderr, so that a retry with the same key returns the same request.
  - `--note <text>`: a note for the owner, which the phone shows apart from the text.
  - `--expires <seconds>`: the request's lifetime, from 60 to 604800.
- **`get <id>`:** prints the request as it is now (`vault_get_request`). Once the owner has answered, its `status` is `COMPLETED` or `REJECTED`, and `terminal` is true.
- **`cancel <id>`:** withdraws a request that is still PENDING (`vault_cancel_request`).
- **`tools`:** prints the server's tools as JSON.
- **`--timeout <seconds>`:** sets the client timeout. The default is `LIVE_COMMAND_TIMEOUT_SECONDS` plus 15 seconds, so the sidecar's own `TIMEOUT` normally arrives first.
- **`--silent`:** a pnpm flag that stops pnpm from echoing the command, so stdout carries only the JSON.

`pnpm agent` runs `node test-agent/src/main.ts` directly rather than through `pnpm --filter`, which would turn every nonzero exit code into 1.

## Configuration

`pnpm agent` reads the root `.env`, which is git-ignored; start from `.env.example`. Variables already set in the environment take precedence over `.env`.

| Variable | Meaning |
| --- | --- |
| `MCP_URL` | The sidecar's MCP endpoint, for example `http://127.0.0.1:8080/mcp` |
| `MCP_TOKEN` | The sidecar's `MCP_TOKEN` |
| `LIVE_COMMAND_TIMEOUT_SECONDS` | Optional. The sidecar's deadline, from which the default client timeout is computed. If unset, it's taken as 60. |

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | The phone acknowledged, and the JSON acknowledgement is on stdout |
| 1 | An unexpected result: the tool answered without an acknowledgement, or an internal error |
| 2 | A usage or configuration problem |
| 3 | A connection problem. One of these: the sidecar is unreachable; it rejected `MCP_TOKEN` (HTTP 401); it refused the request (HTTP 403); it doesn't offer the tool; or the connection dropped before the phone answered, which the agent reports at once. The sidecar cancels a command whose agent connection drops. |
| 4 | `OFFLINE`: no phone is watching |
| 5 | `BUSY`: another command is waiting |
| 6 | `TIMEOUT`: the sidecar's deadline passed, or the client timeout did. On a client timeout, the agent cancels the command. |
| 7 | `CANCELLED`: the phone disconnected, or the sidecar stopped |
| 8 | `INVALID_TEXT`: the text is empty, whitespace-only, or over 4096 UTF-8 bytes |
| 9 | `ack`, `get`, or `cancel`: the sidecar refused, and stderr carries its `<CODE>: <message>`. For example `NOT_PAIRED` (pair the phone first), `NOT_FOUND`, `INVALID_STATE`, `IDEMPOTENCY_CONFLICT`, or `INVALID_PARAMETERS`. |

**The agent never reports success without the phone's acknowledgement.**

**Tokens are never printed.** The CLI removes `MCP_TOKEN` and `PHONE_TOKEN` from all of its output, and prints error messages without stack traces.

## In regression tests

Run the CLI as a process, then check its exit code and stdout. `src/cli.test.ts` does exactly that against a real sidecar, with a Connect client standing in for the phone. `src/stage1.acceptance.ts`, which `pnpm test:hello` runs, walks through the Stage 1 acceptance cases the same way, with the sidecar as a separate process. [`docs/testing/hello-world.md`](../docs/testing/hello-world.md) describes the automated and the physical-device checks. Later stages add commands here, starting with message signing in Stage 3.
