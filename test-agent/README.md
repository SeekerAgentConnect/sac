# test-agent

A minimal MCP client for regression tests and demos. It calls the sidecar's tools through the same MCP Streamable HTTP interface that Hermes uses, with no LLM involved. `hello` sends Stage 1's `vault_display_command` and waits for the phone's acknowledgement. `ack`, `get`, and `cancel` drive Stage 2's durable requests, which answer at once; see [live commands and durable requests](#live-commands-and-durable-requests). `address` reads the wallet the owner connected, `sign` asks that wallet to sign a message, `transfer` asks the owner to send SOL or a token, and `capabilities` says what the sidecar serves.

## Usage

Run it from the repository root, while the sidecar is running and the phone's live-test screen is connected:

```console
$ pnpm --silent agent hello "Hello Seeker"
Showing the text on the phone; waiting up to 75 s for OK...
{"id":"1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed","result":"OK"}
```

- **`hello [text]`:** discovers `vault_display_command` and calls it with the text, which defaults to `Hello Seeker`. Once the user taps OK, it prints the acknowledgement on stdout. Progress messages and errors go to stderr.
- **`ack <text>`:** queues the text for the owner to acknowledge (`vault_request_ack`), and prints the request, PENDING, as JSON. It doesn't wait: the owner answers later on the phone ([`docs/guides/pending-requests.md`](../docs/guides/pending-requests.md)). The phone must be paired first. `vault_request_ack` is a demo tool, which the sidecar serves only with `MCP_DEMO_TOOLS=true`; without it, `ack` exits 3. Options:
  - `--key <key>`: the idempotency key. Without it, the agent makes one and prints it on stderr, so that a retry with the same key returns the same request.
  - `--note <text>`: a note for the owner, which the phone shows apart from the text.
  - `--expires <seconds>`: the request's lifetime, from 60 to 604800.
- **`address`:** prints the wallet the owner connected on their phone, and its network (`vault_get_address`): `{"wallet":"…","network":"devnet","bound_at":"…"}`. It exits 9 with `WALLET_NOT_CONNECTED` when they've connected none; there is no fallback address. See [`docs/guides/wallet-setup.md`](../docs/guides/wallet-setup.md).
- **`sign <text>`:** asks the owner's wallet to sign the text (`vault_sign_message`), and prints the request, PENDING, as JSON. It doesn't wait, and nothing is signed until the owner reviews the message on their phone and approves it ([`docs/guides/message-signing.md`](../docs/guides/message-signing.md)). Without `--wallet`, it reads the owner's wallet with `vault_get_address` first, since naming another one is refused. It takes the same `--key`, `--note`, and `--expires` options as `ack`.
- **`transfer <recipient> <amount>`:** asks the owner to send `<amount>` base units to `<recipient>` (`vault_transfer`), and prints the request, PENDING, as JSON. Lamports for SOL, or the mint's base units with `--mint <address>` for a classic SPL token; never a human-readable decimal. It reads the owner's wallet and network with `vault_get_address` first, and takes the same `--key`, `--note`, and `--expires` options as `ack`, plus `--wallet`. Nothing is built, signed, or sent: the sidecar builds the transaction only when the owner opens the request, and their own wallet signs it ([`docs/guides/transfers.md`](../docs/guides/transfers.md)). The sidecar serves the tool only with `SOLANA_RPC_URL` set; without it, `transfer` exits 3.
- **`capabilities`:** prints what the sidecar actually serves (`vault_get_capabilities`): `{"approval":"manual","signing":"wallet","operations":["sign_message"],…}`. Anything missing from `operations` isn't implemented there.
- **`get <id>`:** prints the request as it is now (`vault_get_request`). Once the owner has answered, its `status` is `COMPLETED` or `REJECTED`, and `terminal` is true. For a signed message it adds `signature`, `wallet`, and `signed_message_base64`, and `signature_verified`, which the agent works out itself with its own Ed25519 verifier (`src/verify.ts`) rather than trusting the sidecar.
- **`cancel <id>`:** withdraws a request that is still PENDING (`vault_cancel_request`).
- **`tools`:** prints the server's tools as JSON.
- **`--timeout <seconds>`:** sets the client timeout. The default is `LIVE_COMMAND_TIMEOUT_SECONDS` plus 15 seconds, so the sidecar's own `TIMEOUT` normally arrives first.
- **`--silent`:** a pnpm flag that stops pnpm from echoing the command, so stdout carries only the JSON.

`pnpm agent` runs `node test-agent/src/main.ts` directly rather than through `pnpm --filter`, which would turn every nonzero exit code into 1.

## Live commands and durable requests

`hello` and `ack` both put text in front of the owner, but they work differently:

| | `hello` (`vault_display_command`) | `ack` (`vault_request_ack`), then `get` |
| --- | --- | --- |
| **The call** | Waits for the owner's OK, up to `LIVE_COMMAND_TIMEOUT_SECONDS` | Returns at once with the request, PENDING |
| **The phone** | The live-test screen must be open and connected | The app may be closed. The owner answers the next time they open it. |
| **The result** | Printed when the owner taps OK | Read later with `get <id>`, until `terminal` is true |
| **A retry** | Sends the text again | With the same `--key`, returns the same request |
| **After a sidecar restart** | Nothing is left | The request is still there |

The wallet tools of later stages (message signing from Stage 3, then transfers and swaps) answer at once too, like `ack`. A script queues a request, and checks it later:

```console
$ pnpm --silent agent ack "Deploy finished" --key deploy-42 > request.json
$ pnpm --silent agent get "$(node -p 'require("./request.json").request_id')"
{"request_id":"f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19","action":"ack","status":"PENDING","terminal":false,...}
```

Once the owner answers on the phone, the same `get` prints `"status":"COMPLETED"` or `"status":"REJECTED"`, with `"terminal":true`. Running the `ack` line again with the same `--key` returns the same request instead of queueing another.

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
| 0 | `hello`: the phone acknowledged, and the JSON acknowledgement is on stdout. `ack`, `get`, and `cancel`: the request is on stdout as JSON, in whatever state it's in. `address`: the wallet is on stdout as JSON. |
| 1 | An unexpected result: the tool answered without an acknowledgement, or an internal error |
| 2 | A usage or configuration problem |
| 3 | A connection problem. One of these: the sidecar is unreachable; it rejected `MCP_TOKEN` (HTTP 401); it refused the request (HTTP 403); it doesn't offer the tool, as for `ack` on a sidecar without `MCP_DEMO_TOOLS=true`; or the connection dropped before the phone answered, which the agent reports at once. The sidecar cancels a command whose agent connection drops. |
| 4 | `OFFLINE`: no phone is watching |
| 5 | `BUSY`: another command is waiting |
| 6 | `TIMEOUT`: the sidecar's deadline passed, or the client timeout did. On a client timeout, the agent cancels the command. |
| 7 | `CANCELLED`: the phone disconnected, or the sidecar stopped |
| 8 | `INVALID_TEXT`: the text is empty, whitespace-only, or over 4096 UTF-8 bytes |
| 9 | `ack`, `get`, `cancel`, or `address`: the sidecar refused, and stderr carries its `<CODE>: <message>`. For example `NOT_PAIRED` (pair the phone first), `WALLET_NOT_CONNECTED` (connect a wallet in the app), `NOT_FOUND`, `INVALID_STATE`, `IDEMPOTENCY_CONFLICT`, or `INVALID_PARAMETERS`. |

**The agent never reports success without the phone's acknowledgement.** For `ack`, exit code 0 means only that the request is stored, never that the owner has answered it.

**Tokens are never printed.** The CLI removes `MCP_TOKEN` and `PHONE_TOKEN` from all of its output, and prints error messages without stack traces.

## In regression tests

Run the CLI as a process, then check its exit code and stdout. `src/cli.test.ts` does exactly that against a real sidecar, with a Connect client standing in for the phone. `src/stage1.acceptance.ts`, which `pnpm test:hello` runs, walks through the Stage 1 acceptance cases the same way, with the sidecar as a separate process. `src/stage2.acceptance.ts`, which `pnpm test:queue` runs, does the same for Stage 2, with two sidecars that restart ([`docs/testing/stage-2.md`](../docs/testing/stage-2.md#the-acceptance-scenario-saw-014)). [`docs/testing/hello-world.md`](../docs/testing/hello-world.md) describes the automated and the physical-device checks. Later stages add commands here, starting with message signing in Stage 3.
