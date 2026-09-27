# test-agent

A minimal MCP client for regression tests and demos. It calls the sidecar's tools through the same MCP Streamable HTTP interface that Hermes uses, with no LLM involved. `hello` sends Stage 1's `vault_display_command` and waits for the phone's acknowledgement. `ack`, `get`, and `cancel` drive Stage 2's durable requests, which answer at once; see [live commands and durable requests](#live-commands-and-durable-requests). `address` reads the wallet the owner connected, `sign` asks that wallet to sign a message, `transfer` asks the owner to send SOL or a token, `status` and `wait` say what became of a request, and `capabilities` says what the sidecar serves.

Two things it will not do. It never reports success without the owner's own answer — an acknowledgement is not a signature, and a stored request is not a payment. And `hello` and `ack`, the two diagnostics that return an acknowledgement, run only in development mode (`MCP_DEMO_TOOLS=true`, or `--demo`), so a client that lives in a deployment cannot reach for one by accident.

## Usage

Run it from the repository root, while the sidecar is running and the phone's live-test screen is connected:

```console
$ pnpm --silent agent hello "Hello Seeker" --demo
Showing the text on the phone; waiting up to 75 s for OK...
{"id":"1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed","result":"OK"}
```

`--demo` is not needed when `MCP_DEMO_TOOLS=true` is in the environment, as it is in `.env.example`.

- **`hello [text]`:** discovers `vault_display_command` and calls it with the text, which defaults to `Hello Seeker`. Once the user taps OK, it prints the acknowledgement on stdout. Progress messages and errors go to stderr. It is a **development diagnostic**: it runs only with `MCP_DEMO_TOOLS=true` in the environment or `--demo` on the command line, and exits 2 otherwise. The acknowledgement it prints is the owner tapping OK on a screen — not a wallet signature, and not a payment.
- **`ack <text>`:** queues the text for the owner to acknowledge (`vault_request_ack`), and prints the request, PENDING, as JSON. It is a **development diagnostic** too, gated the same way as `hello`. It doesn't wait unless `--wait` says so: the owner answers later on the phone ([`docs/guides/pending-requests.md`](../../docs/guides/pending-requests.md)). The phone must be paired first. `vault_request_ack` is a demo tool, which the sidecar serves only with `MCP_DEMO_TOOLS=true`; without it, `ack` exits 3. Options:
  - `--key <key>`: the idempotency key. Without it, the agent makes one and prints it on stderr, so that a retry with the same key returns the same request.
  - `--note <text>`: a note for the owner, which the phone shows apart from the text.
  - `--expires <seconds>`: the request's lifetime, from 60 to 604800.
- **`address`:** prints the wallet the owner connected on their phone, and its network (`vault_get_address`): `{"wallet":"…","network":"devnet","bound_at":"…"}`. It exits 9 with `WALLET_NOT_CONNECTED` when they've connected none; there is no fallback address. See [`docs/guides/wallet-setup.md`](../../docs/guides/wallet-setup.md).
- **`sign <text>`:** asks the owner's wallet to sign the text (`vault_sign_message`), and prints the request, PENDING, as JSON. It doesn't wait, and nothing is signed until the owner reviews the message on their phone and approves it ([`docs/guides/message-signing.md`](../../docs/guides/message-signing.md)). Without `--wallet`, it reads the owner's wallet with `vault_get_address` first, since naming another one is refused. It takes the same `--key`, `--note`, and `--expires` options as `ack`.
- **`transfer <recipient> <amount> --wallet <address> --network <name>`:** asks the owner to send `<amount>` base units to `<recipient>` (`vault_transfer`), and prints the request, PENDING, as JSON. Nothing is built, signed, or sent: the sidecar builds the transaction only when the owner opens the request, and their own wallet signs it ([`docs/guides/transfers.md`](../../docs/guides/transfers.md)). The sidecar serves the tool only with `SOLANA_RPC_URL` set; without it, `transfer` exits 3. It takes the same `--key`, `--note`, and `--expires` options as `ack`, plus `--mint <address>` for a classic SPL token.

  **Every part of a payment is named, and none of it is guessed.** `--wallet` and `--network` are required, and neither falls back to `vault_get_address`: a command that doesn't say whose money moves, or which cluster it moves on, is refused with exit code 2 before a session is even opened. Read both with `pnpm agent address` and pass them. `<amount>` is a whole number of base units above zero — lamports for SOL, the mint's own units for a token — and `1.5`, `0`, `2_500_000`, and `1e6` are all refused for the same reason: a decimal is the mistake that sends a billionth of what was meant, or a billion times it.
- **`capabilities`:** prints what the sidecar actually serves (`vault_get_capabilities`): `{"approval":"manual","signing":"wallet","operations":["sign_message"],…}`. Anything missing from `operations` isn't implemented there.
- **`get <id>`:** prints the request as it is now (`vault_get_request`). Once the owner has answered, its `status` is `COMPLETED` or `REJECTED`, and `terminal` is true. For a signed message it adds `signature`, `wallet`, and `signed_message_base64`, and `signature_verified`, which the agent works out itself with its own Ed25519 verifier (`src/verify.ts`) rather than trusting the sidecar.
- **`status <id>`:** prints what became of a request, for a script to branch on. It reads the same `vault_get_request`, and reports the state, `outcome` — `succeeded`, `failed`, or `unsettled`, the same three answers as the exit codes, so nothing has to know the state table — what the server last read from the chain (`confirmation`, `slot`, `chain_error`, `checked_with`), and, for a transfer that was sent, `explorer_url` for **its own cluster**. It exits **0** once the request ended the way it was asked for (`CONFIRMED`, `COMPLETED`), **10** while no outcome is established (`PENDING`, `PROCESSING`, `SUBMITTED`, `UNKNOWN`), and **11** once it ended any other way (`FAILED`, `REJECTED`, `CANCELLED`, `EXPIRED`).

  `signature_is_transaction` says which kind of signature the request carries, and there is no `explorer_url` when it is false. **A signed message is not a payment**: it carries a 64-byte signature too, it moved nothing, no cluster has it, and no explorer can show it. `UNKNOWN` counts as unsettled rather than failed, on purpose — a script that treats it as a failure is a script that pays twice.
- **`wait <id>`:** reads the request until it settles or the deadline passes, whichever comes first, and then prints exactly what `status` prints, plus `waited_seconds`, `polls`, and `timed_out`. The exit codes are `status`'s, so a script branches the same way. **Giving up is not a failure and never becomes one:** the deadline passing means the owner has not answered yet, the request is exactly as it was, and reading it again — or waiting again — is always safe. Nothing here withdraws a request, retries one, or creates a second.
  - `--for <seconds>`: how long to keep reading, 1 to 86400. Default 300.
  - `--every <seconds>`: how long to leave between reads, 1 to 300. Default 3.
- **`swap <from> <amount>`:** swaps are a later stage, and no sidecar serves `vault_swap` yet. The command exists so that asking for one says what is missing instead of failing as an unknown word: it exits **3**, and it queues nothing. It is held to the same rules as `transfer` — `--wallet` and `--network` are required — so that the command which reports a swap is unserved never becomes a looser one on the day it is served.
- **`cancel <id>`:** withdraws a request that is still PENDING (`vault_cancel_request`).
- **`tools`:** prints the server's tools as JSON.
- **`--wait`:** on `ack`, `sign`, and `transfer`, keeps reading the request after the sidecar accepts it, as `wait` does. The `request_id` goes to **stderr** the moment the request exists, before any waiting, so an interrupted wait still leaves you with the one thing you need; stdout carries a single JSON document at the end. It takes `--for` and `--every`.
- **`--demo`:** runs `hello` or `ack` without `MCP_DEMO_TOOLS=true` in the environment. Both are diagnostics that return an acknowledgement, and an acknowledgement is neither a wallet signature nor a payment confirmation.
- **`--timeout <seconds>`:** sets the client timeout. The default is `LIVE_COMMAND_TIMEOUT_SECONDS` plus 15 seconds, so the sidecar's own `TIMEOUT` normally arrives first.
- **`--silent`:** a pnpm flag that stops pnpm from echoing the command, so stdout carries only the JSON.

`pnpm agent` runs `node tools/test-agent/src/main.ts` directly rather than through `pnpm --filter`, which would turn every nonzero exit code into 1.

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

## Waiting for the owner

A request is answered by a person, so a script either comes back later or waits. Both are here, and
neither changes what the request is.

```console
$ pnpm --silent agent sign "Release 4.2" --wait --for 600
idempotency key: sign-8f1c0f0a-2f0e-4a3a-9c0a-1b2c3d4e5f60
The owner reviews it on their Seeker; nothing is signed until they approve.
request_id: 2c1b0a9f-8e7d-4c6b-9a5f-4e3d2c1b0a9f
Waiting up to 600 s for the owner, reading every 3 s. Stopping here changes nothing: the request stays as it is.
{"request_id":"2c1b0a9f-…","action":"sign_message","status":"COMPLETED","outcome":"succeeded","terminal":true,…,"waited_seconds":48,"polls":17,"timed_out":false}
```

The `request_id` is on stderr before any waiting starts, so interrupting the wait still leaves the
caller with it. Exit 0 means the request ended the way it was asked for, 11 that it ended some
other way, and 10 that it has not ended — which is also what a deadline that passes means:

```console
$ pnpm --silent agent wait 2c1b0a9f-… --for 30; echo "exit $?"
{"request_id":"2c1b0a9f-…","status":"PENDING","outcome":"unsettled",…,"timed_out":true}
still PENDING after 30 s. Nothing was withdrawn and nothing was sent twice; read it again with `status 2c1b0a9f-…`.
exit 10
```

**10 is not a failure.** A script that treats "no answer yet" as one is a script that asks for the
same payment twice. `UNKNOWN` is in the same category on purpose: it means nobody knows.

## Through the packaged stack

The Stage 7 stack publishes the sidecar's MCP endpoint through its gateway
([`docs/guides/self-hosting.md`](../../docs/guides/self-hosting.md)), and the CLI speaks to it exactly
as it speaks to a sidecar started with `pnpm dev:sidecar` — the same tools, the same JSON, the same
exit codes. From the checkout:

```sh
MCP_URL=http://127.0.0.1:8080/mcp MCP_TOKEN=<the stack's MCP_TOKEN> pnpm --silent agent capabilities
```

The stack also carries the CLI as a container, in the `agent` profile, so `docker compose up` never
starts it:

```sh
cd gateway
docker compose run --rm test-agent capabilities
```

The container reaches the gateway on the stack's own network (`AGENT_MCP_URL`), and it is built
from this checkout, so the two routes run the same code. Development mode follows the stack's
`MCP_DEMO_TOOLS`, which a deployment leaves off, so `hello` and `ack` need `--demo` there unless
it is on.

## Configuration

`pnpm agent` reads the root `.env`, which is git-ignored; start from `.env.example`. Variables already set in the environment take precedence over `.env`.

| Variable | Meaning |
| --- | --- |
| `MCP_URL` | The sidecar's MCP endpoint, for example `http://127.0.0.1:8080/mcp` |
| `MCP_TOKEN` | The sidecar's `MCP_TOKEN` |
| `LIVE_COMMAND_TIMEOUT_SECONDS` | Optional. The sidecar's deadline, from which the default client timeout is computed. If unset, it's taken as 60. |
| `MCP_DEMO_TOOLS` | Optional, `true` or `false`. Development mode: it is what lets `hello` and `ack` run at all. `.env.example` sets it; a deployment leaves it off, and `--demo` covers a one-off. |

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | `hello`: the phone acknowledged, and the JSON acknowledgement is on stdout. `ack`, `get`, and `cancel`: the request is on stdout as JSON, in whatever state it's in. `address`: the wallet is on stdout as JSON. |
| 1 | An unexpected result: the tool answered without an acknowledgement, or an internal error |
| 2 | A usage or configuration problem, including `hello` or `ack` outside development mode |
| 3 | A connection problem. One of these: the sidecar is unreachable; it rejected `MCP_TOKEN` (HTTP 401); it refused the request (HTTP 403); it doesn't offer the tool, as for `ack` on a sidecar without `MCP_DEMO_TOOLS=true` or `swap` on any sidecar so far; or the connection dropped before the phone answered, which the agent reports at once. The sidecar cancels a command whose agent connection drops. |
| 4 | `OFFLINE`: no phone is watching |
| 5 | `BUSY`: another command is waiting |
| 6 | `TIMEOUT`: the sidecar's deadline passed, or the client timeout did. On a client timeout, the agent cancels the command. |
| 7 | `CANCELLED`: the phone disconnected, or the sidecar stopped |
| 8 | `INVALID_TEXT`: the text is empty, whitespace-only, or over 4096 UTF-8 bytes |
| 9 | `ack`, `get`, `cancel`, or `address`: the sidecar refused, and stderr carries its `<CODE>: <message>`. For example `NOT_PAIRED` (pair the phone first), `WALLET_NOT_CONNECTED` (connect a wallet in the app), `NOT_FOUND`, `INVALID_STATE`, `IDEMPOTENCY_CONFLICT`, or `INVALID_PARAMETERS`. |
| 10 | `status` and `wait`: the request has no outcome yet — `PENDING`, `PROCESSING`, `SUBMITTED`, or `UNKNOWN`. It is on stdout as JSON. |
| 11 | `status` and `wait`: the request ended, and not the way it was asked for — `FAILED`, `REJECTED`, `CANCELLED`, or `EXPIRED`. It is on stdout as JSON. |

**The agent never reports success without the phone's acknowledgement.** For `ack`, exit code 0 means only that the request is stored, never that the owner has answered it.

**Tokens are never printed.** The CLI removes `MCP_TOKEN` and `PHONE_TOKEN` from all of its output, and prints error messages without stack traces.

## In regression tests

Run the CLI as a process, then check its exit code and stdout. `src/cli.test.ts` does exactly that against a real sidecar, with a Connect client standing in for the phone. `src/stage1.acceptance.ts`, which `pnpm test:hello` runs, walks through the Stage 1 acceptance cases the same way, with the sidecar as a separate process. `src/stage2.acceptance.ts`, which `pnpm test:queue` runs, does the same for Stage 2, with two sidecars that restart ([`docs/testing/stage-2.md`](../../docs/testing/stage-2.md#the-acceptance-scenario-saw-014)).

`src/stage4.acceptance.ts`, which `pnpm test:transfer` runs, does the same for a transfer, end to end: the sidecar as its own process, this CLI as another, the phone's Connect client as the phone, a throwaway key pair as the wallet, and a fake Solana JSON-RPC endpoint as the chain. It covers a SOL transfer, an SPL transfer to someone with no token account, a transaction the chain rejected, the owner's rejection, the same result twice, a restart with a transaction in flight, and an endpoint that stops answering. **Nothing in it reaches a cluster and nothing in it spends anything.** One case does talk to a real network — it reads devnet, needs no funds, and is skipped unless `SEEKER_VAULT_NETWORK_CHECKS=1` ([`docs/testing/stage-4.md`](../../docs/testing/stage-4.md#the-opt-in-devnet-check)).

[`docs/testing/hello-world.md`](../../docs/testing/hello-world.md) describes the automated and the physical-device checks.
