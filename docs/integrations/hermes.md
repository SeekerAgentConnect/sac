# Hermes

This page connects [Hermes Agent](https://hermes-agent.nousresearch.com) to the sidecar's MCP endpoint, for two kinds of tool:

- **The live diagnostic, `vault_display_command`.** Hermes sends text, the Seeker shows it, you tap **OK**, and Hermes gets the acknowledgement in the same call.
- **Durable requests, from Stage 2 on.** Hermes creates a request and gets its ID at once. You answer on the phone whenever you next open the app, and Hermes reads the result later; see [queued requests](#4-queued-requests-create-now-read-the-result-later).

It covers Hermes on the Mac and Hermes on a VPS.

> **What has been tested.** Hermes Agent v0.21.1 (2026.9.7) was run with this configuration against the sidecar, including through a forwarded port, and every output below is real. Hermes's own MCP client made the tool calls, without an LLM, and a test client stood in for the phone. On 2026-09-11, the owner also ran the live round trip and reported it passed. That run used their own Hermes on a VPS, reaching the Mac [over Tailscale](#over-a-vpn-you-already-use), and their physical Seeker; see [`docs/testing/stage-1.md`](../testing/stage-1.md). The durable tools were run the same way on 2026-09-11, without a model or the Seeker; see [`docs/testing/stage-2.md`](../testing/stage-2.md#acceptance-report-saw-014). The wallet tools of [section 5](#5-sign-a-message-with-your-wallet) and [section 6](#6-send-a-transfer-with-your-wallet) have **not** been run through Hermes: they have only been driven by `pnpm agent` and the automated tests, and those sections say so where their results are shown. **No transfer has ever been sent from a real wallet to any cluster** by this repository, through Hermes or otherwise.

## Before you start

- The sidecar runs, and the Seeker is connected: steps 19 to 23 of the [MacBook → Seeker quickstart](../guides/macbook-seeker-quickstart.md).
- Hermes is installed and works on its own. Its MCP support is part of the standard install.
- You need the `MCP_TOKEN` value from the sidecar's `.env`. It's the agent's token; the phone's `PHONE_TOKEN` won't work here.
- For the durable tools, the phone is paired with the sidecar ([`pairing.md`](../guides/pairing.md)), and the sidecar's `.env` has `MCP_DEMO_TOOLS=true`, as `.env.example` does.

## 1. Add the server entry

Hermes reads MCP servers from the `mcp_servers` section of `~/.hermes/config.yaml`. Add one entry to it, and keep everything else in the file.

1. Back up the file: `cp ~/.hermes/config.yaml ~/.hermes/config.yaml.bak`.
2. Open `~/.hermes/config.yaml` in an editor.
   - If it has no `mcp_servers:` line, paste all of [`examples/hermes.config.yaml`](../../examples/hermes.config.yaml) at the end.
   - If it already has `mcp_servers:`, paste only the `seeker_vault:` entry below that line, indented like the other servers there.
   - Don't replace the file, and don't remove other entries.

The entry:

```yaml
mcp_servers:
  seeker_vault:
    url: "http://127.0.0.1:8080/mcp"
    headers:
      Authorization: "Bearer ${MCP_SEEKER_VAULT_API_KEY}"
    timeout: 90
    tools:
      include:
        [
          vault_display_command,
          vault_get_address,
          vault_get_capabilities,
          vault_sign_message,
          vault_transfer,
          vault_request_ack,
          vault_get_request,
          vault_cancel_request,
        ]
      resources: false
      prompts: false
```

3. Put the token in `~/.hermes/.env`, not in `config.yaml`. From the repository root, this appends it without printing it:

   ```bash
   printf 'MCP_SEEKER_VAULT_API_KEY=%s\n' "$(grep '^MCP_TOKEN=' .env | cut -d= -f2)" >> ~/.hermes/.env
   chmod 600 ~/.hermes/.env
   ```

   If `~/.hermes/.env` already has a `MCP_SEEKER_VAULT_API_KEY` line, edit that line instead.

What each setting does:

| Setting | Meaning |
| --- | --- |
| `url` | The sidecar's MCP endpoint. It listens only on the Mac's loopback address. On a VPS, this is the tunnel's end instead; see [Hermes on a VPS](#7-hermes-on-a-vps). |
| `Authorization` | Hermes fills in `${MCP_SEEKER_VAULT_API_KEY}` from `~/.hermes/.env`, or from the environment, when it loads the configuration. If the variable is missing, Hermes sends the literal text, and the sidecar refuses the connection. `hermes mcp add` uses the same variable name for a server called `seeker_vault`. |
| `timeout` | How long Hermes waits for a tool call, in seconds; Hermes's default is 300. Only `vault_display_command` waits for you. Keep this above the sidecar's `LIVE_COMMAND_TIMEOUT_SECONDS` (60 by default), so that the sidecar's own `TIMEOUT` answer arrives first, and below 300, Hermes's fixed HTTP read limit. If you raise `LIVE_COMMAND_TIMEOUT_SECONDS`, raise this too. The durable tools answer at once. |
| `tools` | The tools Hermes may call, each allowed on purpose:<ul><li>`vault_display_command`: the live diagnostic</li><li>`vault_get_address`: reads the wallet you connected on the phone, and its network (SAW-015). It's read-only, and fails with `WALLET_NOT_CONNECTED` rather than inventing an address.</li><li>`vault_get_capabilities`: says what this sidecar serves, and that approval is always manual (SAW-016)</li><li>`vault_sign_message`: asks your wallet to sign a message (SAW-016). It returns a request ID at once; nothing is signed until you approve it on the phone.</li><li>`vault_transfer`: asks the owner's wallet to send SOL or a classic SPL token (SAW-019). It returns a request ID at once; nothing is built, signed, or sent until they approve it on the phone, and the sidecar serves it only with a `SOLANA_RPC_URL`.</li><li>`vault_request_ack`: queues an acknowledgement. It's a demo tool with no wallet involved, and the sidecar serves it only with `MCP_DEMO_TOOLS=true`.</li><li>`vault_get_request`: reads a request's status by its ID</li><li>`vault_cancel_request`: withdraws a request you haven't answered</li></ul>A tool listed here that the sidecar doesn't serve is missing from the session. The sidecar offers no resources or prompts. |

Leave `trust` unset, which Hermes treats as `full`. With `trust: untrusted`, Hermes asks for approval before every call to a tool that isn't read-only.

Hermes names each tool `mcp__seeker_vault__<tool>`, for example `mcp__seeker_vault__vault_display_command`. Its MCP documentation page shows an older form with single underscores; v0.21.1 registers the double-underscore form.

## 2. Check the connection and reload

With the sidecar running:

```bash
hermes mcp list
hermes mcp test seeker_vault
```

```text
  MCP Servers:

  Name             Transport                      Tools        Status
  ──────────────── ────────────────────────────── ──────────── ──────────
  seeker_vault     http://127.0.0.1:8080/mcp      4 selected   ✓ enabled
```

```text
  Testing 'seeker_vault'...
  Transport: HTTP → http://127.0.0.1:8080/mcp
    Authorization: Bear***xxxx
  ✓ Connected (1539ms)
  ✓ Tools discovered: 4

    vault_display_command                Shows display-only text on the owner's Seeker (its live...
    vault_request_ack                    A development and demo tool, with no wallet involved. Q...
    vault_get_request                    Returns a request as it is now, by request_id: its stat...
    vault_cancel_request                 Withdraws a PENDING request so the owner can no longer ...
```

Hermes masks the header and shows only its first and last four characters; `xxxx` stands for your token's last four. For errors instead of `✓ Connected`, see [what failures look like](#8-what-failures-look-like).

The output above is from the run on 2026-09-11, before `vault_get_address`, `vault_get_capabilities`, `vault_sign_message`, and `vault_transfer` existed; with the configuration as it is now, the counts are four higher and those four are listed too.

`hermes mcp list` counts the tools in `include`, not the ones the sidecar serves. A sidecar without `MCP_DEMO_TOOLS=true` is still counted in full, but `hermes mcp test` discovers one fewer: every tool but `vault_request_ack`. A sidecar without `SOLANA_RPC_URL` leaves out `vault_transfer` the same way.

Hermes connects to MCP servers when a session starts. After changing the configuration, type `/reload-mcp` in the running session, or start a new session. Do the same if the sidecar wasn't running when the session started: Hermes then tried three times, parked the server, and the session has none of its tools.

## 3. Run the test prompt

1. Make Hermes show its tool calls: type `/verbose` until it reports `verbose`, or set `display.tool_progress: verbose` in `~/.hermes/config.yaml`. Verbose mode shows each tool call's arguments and result.
2. Keep the Seeker on the Live test screen with the status "Connected".
3. Send Hermes exactly this prompt:

   ```text
   Call the vault_display_command tool with the text "Hello from Hermes — seeker-check-001" and report the real result.
   ```

4. The Seeker shows `Hello from Hermes — seeker-check-001`, with the em dash. Check that the text is exact **before** you tap **OK**. Then tap **OK**.
5. Hermes's verbose output shows the call to `mcp__seeker_vault__vault_display_command` with `{"text": "Hello from Hermes — seeker-check-001"}`, and this result, which is exactly what the model receives:

   ```text
   {"result": "{\"id\":\"fbf298f6-878f-4fef-a47e-9c3b55c2422e\",\"result\":\"OK\"}"}
   ```

6. The sidecar's terminal logs the same ID:

   ```text
   [sidecar] command fbf298f6-878f-4fef-a47e-9c3b55c2422e sent (38 bytes)
   [sidecar] command fbf298f6-878f-4fef-a47e-9c3b55c2422e acknowledged
   ```

7. Hermes's answer reports that ID and `OK`.

### A listed tool is not a passed test

`hermes mcp test`, and the tool appearing in a session, prove only that Hermes can connect and read the tool's description. They prove nothing about delivery or the acknowledgement. A model can also say "done" without calling the tool, or describe a result it never got. Count the test as passed only when all of these hold:

- The Seeker showed the exact text before you tapped OK.
- Hermes's tool output shows the call and the `{"id", "result": "OK"}` result, not just the model's summary.
- That ID matches the sidecar's `acknowledged` log line.

To keep a record of the session, including every tool call and result, run `hermes sessions export <file>`. Add `--session-id <id>` to export a single session.

## 4. Queued requests: create now, read the result later

`vault_display_command` is a live diagnostic. The call stays open until you tap **OK**, for at most `LIVE_COMMAND_TIMEOUT_SECONDS`, and only while the live-test screen is open. Nothing is queued: with no phone watching, the call fails with `OFFLINE`, and nothing is delivered later.

From Stage 2 on, requests are durable instead. The wallet tools of later stages work the same way: `vault_sign_message`, `vault_transfer`, and `vault_swap` ([`docs/protocol.md`](../protocol.md#agent-api-mcp)).

| | Live diagnostic | Durable request |
| --- | --- | --- |
| **The call** | Waits for your OK, up to `LIVE_COMMAND_TIMEOUT_SECONDS` | Returns at once, with a `request_id` and the status `PENDING` |
| **The phone** | The live-test screen must be open | The app may be closed. You see the request the next time you open it. |
| **The result** | In the call's answer | Read later with `vault_get_request`, until `terminal` is true |
| **A retry** | Shows the text again | Uses the same `idempotency_key`, and gets the same request back |
| **Hermes's `timeout`** | Must be longer than the sidecar's deadline | Doesn't matter: every call answers at once |
| **On the sidecar** | Nothing is stored | Stored through restarts, until you answer or it expires (after a day, by default) |

**PENDING isn't an answer.** It means the request is stored and you haven't answered it; you may not have seen it yet. The model should report it as waiting, and check it again with `vault_get_request` in a later turn or session, or when you ask. There's nothing to wait on in the meantime.

For now, the only request an agent can create is a queued acknowledgement, `vault_request_ack`: display-only text that you acknowledge or reject. It's a demo tool with no wallet involved, and the sidecar serves it only with `MCP_DEMO_TOOLS=true`. Until the phone is paired, it answers `NOT_PAIRED`.

### Example: create a request, and check it later

1. In a Hermes session, with verbose tool output, send:

   ```text
   Call vault_request_ack with the text "Deploy finished — seeker-check-002" and the idempotency_key "seeker-check-002". Report the real request_id and status, and don't wait for an answer.
   ```

   The call returns at once, and the model receives:

   ```text
   {"result": "{\"request_id\":\"28e5d676-787f-4fdf-a2c6-666d9ecc1b9a\",\"action\":\"ack\",\"status\":\"PENDING\",\"terminal\":false,\"created_at\":\"2026-09-11T17:54:25.157Z\",\"expires_at\":\"2026-09-12T17:54:25.157Z\",\"updated_at\":\"2026-09-11T17:54:25.157Z\"}"}
   ```

   The sidecar logs `request 28e5d676-787f-4fdf-a2c6-666d9ecc1b9a stored (ack)`. The same prompt sent again returns the same `request_id`. The sidecar then logs `returned again for its idempotency key`, and stores nothing new.

2. On the Seeker, open the app, then **Pending requests**. Open the request, check the text, and tap **Acknowledge** ([`pending-requests.md`](../guides/pending-requests.md)).
3. Later, in the same session or a new one, send:

   ```text
   Call vault_get_request with the request_id "28e5d676-787f-4fdf-a2c6-666d9ecc1b9a" and report the real status.
   ```

   ```text
   {"result": "{\"request_id\":\"28e5d676-787f-4fdf-a2c6-666d9ecc1b9a\",\"action\":\"ack\",\"status\":\"COMPLETED\",\"terminal\":true,\"created_at\":\"2026-09-11T17:54:25.157Z\",\"expires_at\":\"2026-09-12T17:54:25.157Z\",\"updated_at\":\"2026-09-11T17:54:39.720Z\"}"}
   ```

These IDs come from the verification run, where a test client tapped **Acknowledge**; yours will differ. A request ends in one of these statuses:

- `COMPLETED`: you acknowledged it.
- `REJECTED`: you rejected it. The result carries `"detail":"The owner rejected the request."`
- `EXPIRED`: nobody answered before `expires_at`.
- `CANCELLED`: the agent withdrew it, or the phone's pairing was revoked. To withdraw a request you haven't answered, ask Hermes to call `vault_cancel_request` with its `request_id`. The result then carries `"detail":"The agent cancelled the request."`

## 5. Sign a message with your wallet

`vault_sign_message` asks the wallet you connected on the phone to sign a message. It's a durable request: the call answers at once with a request ID, the wallet app opens only when you tap **Approve and sign** on the Seeker, and Hermes reads the outcome afterwards. A signature proves your wallet signed those exact bytes; it moves no funds and sends nothing on chain.

Connect the wallet first ([`../guides/wallet-setup.md`](../guides/wallet-setup.md)). Without one, the tool fails with `WALLET_NOT_CONNECTED`, and no address is invented.

> **Run with Hermes on 2026-09-12.** The owner drove the wallet tools from Hermes on the physical Seeker and answered on the phone: [steps 24 to 31 of the owner's checks](../testing/stage-3.md#the-hermes-round-trip-saw-018) are recorded as PASS. The exact JSON below still comes from `pnpm agent` and the automated tests rather than from that session's transcript, so read it as the shape of an answer, not as a transcript.

1. **Read what this sidecar serves**, once per session, before asking for anything:

   ```text
   Call vault_get_capabilities and report the real result.
   ```

   ```text
   {"approval":"manual","signing":"wallet","operations":["ack","sign_message"],"wallet_connected":true,
    "max_message_bytes":4096,"max_note_bytes":280,"max_pending_requests":100,
    "min_expires_in_seconds":60,"max_expires_in_seconds":604800}
   ```

   `"approval":"manual"` is not a setting an agent can change: every request waits for a tap on the phone. `operations` lists what is implemented right now, so a model should treat anything missing from it — a transfer, a swap — as unavailable rather than attempting it.

2. **Read the wallet to name**, rather than assuming one:

   ```text
   Call vault_get_address and report the real wallet and network.
   ```

   ```text
   {"wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","network":"devnet","bound_at":"2026-09-12T09:30:00.000Z"}
   ```

   A request that names another wallet is refused with `WALLET_MISMATCH`.

3. **Create the request.** Give the message exactly as it should be signed; nothing is trimmed, normalized, or re-encoded anywhere.

   ```text
   Call vault_sign_message with the wallet "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW", the
   message "Sign in to example.com\nNonce: 4711", and the idempotency_key "seeker-check-003".
   Report the real request_id and status, and don't wait for an answer.
   ```

   ```text
   {"request_id":"f7e6d5c4-…","action":"sign_message","status":"PENDING","terminal":false,
    "wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","created_at":"…","expires_at":"…","updated_at":"…"}
   ```

   **The wallet app does not open, and nothing is signed.** PENDING means the request is stored, and you may not have seen it yet. The same prompt sent again, with the same key and message, returns the same request instead of queueing another; the same key with a different message is refused with `IDEMPOTENCY_CONFLICT`.

4. **Answer on the Seeker.** Open the app and then **Pending requests**, or tap **Refresh**. The request shows the complete message with its invisible characters marked, the number of bytes, and the wallet that would sign. Tap **Approve and sign**, and approve in the wallet as well ([`../guides/message-signing.md`](../guides/message-signing.md)).

5. **Read the outcome.** Nothing pushes it to Hermes; ask when you next need it.

   ```text
   Call vault_get_request with the request_id "f7e6d5c4-…" and report the real status.
   ```

   ```text
   {"request_id":"f7e6d5c4-…","action":"sign_message","status":"COMPLETED","terminal":true,
    "wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","signature":"52o3UtBit8…",
    "signed_message_base64":"U2lnbiBpbiB0by…"}
   ```

6. **Verify the signature yourself.** `signed_message_base64` is exactly the bytes that were signed, so an agent checks the signature against those rather than re-encoding the message and hoping it matches. Any Ed25519 verifier does it; [`../guides/message-signing.md`](../guides/message-signing.md#the-agent-reads-the-result) has a ten-line one for Node. From the repository, `pnpm agent get <request_id>` does the same with its own verifier, which shares no code with the sidecar's, and prints `"signature_verified":true`.

A model saying "signed" is not a signature. Count this as passed only when `vault_get_request` returned COMPLETED with a `signature`, and a verifier you ran accepted it against `signed_message_base64` and the wallet address.

### What a refusal looks like

Not every end is a signature, and none of these is an error to retry around.

| What you did | What the agent reads |
| --- | --- |
| Tapped **Reject** on the phone | `REJECTED`, with `"detail":"The owner rejected the request."` No wallet was opened. |
| Tapped **Approve and sign**, then declined inside the wallet | `REJECTED`. You said no; nothing went wrong and nothing was signed. |
| The wallet couldn't sign, or the phone never learned what it did | `FAILED`, with the reason as `detail`. Nothing was signed, and nothing reached the network. |
| Changed the wallet or the network while the request was waiting | `CANCELLED`: the sidecar withdrew the requests the new binding no longer fits. |
| Left it unanswered past `expires_at` | `EXPIRED`. |

## 6. Send a transfer with your wallet

`vault_transfer` asks the wallet you connected on the phone to send SOL or a classic SPL token. It's
a durable request, like `vault_sign_message`: the call answers at once with a request ID, the wallet
app opens only when you tap **Approve and send** on the Seeker, and your wallet — not the sidecar —
signs and broadcasts. The whole path, from the owner's side, is
[`../guides/transfers.md`](../guides/transfers.md).

The sidecar serves this tool only when its `.env` has a `SOLANA_RPC_URL`. Without one it isn't
offered at all, `vault_get_capabilities` leaves `transfer` out of `operations`, and Hermes reports
`Unknown tool`. Add `vault_transfer` to the `include` list in [section 1](#1-add-the-server-entry)
as well, or Hermes won't call it even when the sidecar serves it.

> **Run with Hermes on 2026-09-12.** The owner asked for a transfer from Hermes, approved it by hand
> on the Seeker, and their wallet sent it: one SOL transfer **finalized on devnet**, recorded in
> [`../testing/stage-4.md`](../testing/stage-4.md#verification-record-saw-024). That is the whole
> point of this section, and it has now been done end to end. The exact JSON below still comes from
> `pnpm agent` and the automated tests rather than from that session's transcript, so read it as the
> shape of an answer. **Nothing has ever been sent on mainnet.**

1. **Read what this sidecar serves**, every session, before asking for anything:

   ```text
   Call vault_get_capabilities and report the real result.
   ```

   ```text
   {"approval":"manual","signing":"wallet","operations":["ack","sign_message","transfer"],…}
   ```

   No `transfer` in `operations` means this sidecar can't build one. That is a fact to report, not
   a thing to work around.

2. **Read the wallet and the network,** rather than remembering them:

   ```text
   Call vault_get_address and report the real wallet and network.
   ```

   ```text
   {"wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","network":"devnet","bound_at":"…"}
   ```

   Both go into the call below, unchanged. The owner can change either at any time, and a request
   naming anything else is refused with `WALLET_MISMATCH` and stored nowhere.

3. **Create the request.** The amount is an exact whole number of **base units** — lamports for SOL
   (1 SOL is 1,000,000,000), the mint's own base units for a token, which the sidecar reads from the
   mint on chain. There is no decimal form of this field anywhere in the API.

   ```text
   Call vault_transfer with the wallet "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW", the network
   "devnet", the recipient "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh", the amount "100000",
   and the idempotency_key "seeker-check-004". Report the real request_id and status, and don't
   wait for an answer.
   ```

   ```text
   {"request_id":"f7e6d5c4-…","action":"transfer","status":"PENDING","terminal":false,
    "wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","network":"devnet","created_at":"…",…}
   ```

   **Nothing has been built, signed, or sent.** No transaction exists yet: it is compiled when the
   owner opens the request, with a blockhash taken then. The same key with the same terms returns
   this same request; the same key with a different amount or recipient is refused with
   `IDEMPOTENCY_CONFLICT`, so a retry can never pay twice.

   For a token, add its `token_mint`. The `recipient` is always a wallet address, never a token account:
   the sidecar finds or creates the recipient's token account itself.

4. **The owner answers on the Seeker.** They see the transaction read out of its own bytes — amount,
   recipient, mint, the wallet that pays — and the server's fee and rent estimate beside it. Nothing
   pushes their decision to Hermes.

5. **Read the outcome.**

   ```text
   Call vault_get_request with the request_id "f7e6d5c4-…" and report the real status.
   ```

   ```text
   {"request_id":"f7e6d5c4-…","action":"transfer","status":"CONFIRMED","terminal":true,
    "network":"devnet","signature":"5Yb4Dn9m…","confirmation":"finalized","slot":310000001,
    "checked_with":"api.devnet.solana.com"}
   ```

   Reading the request is also what makes the sidecar look the signature up on chain, so a model
   that asks again later gets a newer answer rather than a cached one.

6. **Verify it against the chain, not against the answer.** `signature` and `network` together name
   one transaction: open `https://explorer.solana.com/tx/<signature>?cluster=devnet` (mainnet takes
   no `cluster`) and check that the amount, the recipient, and the fee payer are the ones in step 3.
   `pnpm agent status <request_id>` prints the same link.

   A model saying "sent" is not a transaction, and neither is a `signature` on its own. Count this
   as passed only when the explorer shows that transaction on that cluster.

### What each status means to an agent

`vault_transfer`'s answer is the start of the story, and three of these are routinely misread.

| Status | What it means | What an agent should do |
| --- | --- | --- |
| `PENDING` | Stored. The owner may not have seen it. | Report it as waiting. Ask again later. There is nothing to wait on. |
| `PROCESSING` | The owner approved it, and the wallet has it. | Wait. Nothing else to do. |
| `SUBMITTED` | The wallet sent it. **It is not confirmed.** | Read the request again; that check asks the chain. Never treat this as success. |
| `CONFIRMED` | The transaction on chain under that signature is the one the owner approved, byte for byte. | Done. Terminal. |
| `FAILED` | It ran and failed, or the wallet refused, or its blockhash expired. `chain_error` says which. | Report it. **Don't build a replacement**: ask the owner to request a new one. |
| `REJECTED` | The owner said no, in the app or in the wallet. | Report it. Not an error, and not worth retrying. |
| `UNKNOWN` | The phone never learned what the wallet did. The transaction may be on chain. | **Never retry.** Report it as unknown and let the owner check their wallet's own history. A retry here is how you pay twice. |

### What a transfer refusal looks like

| What happened | What the agent reads |
| --- | --- |
| The sidecar has no `SOLANA_RPC_URL` | `Unknown tool: mcp__seeker_vault__vault_transfer` from Hermes, and `transfer` missing from `operations`. Nothing is stored. |
| The endpoint didn't answer while the owner was reviewing | `CHAIN_UNAVAILABLE`. Nothing about the request is wrong; it stays `PENDING` and the next attempt works. |
| The request names a wallet or network other than the owner's | `WALLET_MISMATCH`, and nothing is stored. Read `vault_get_address` again. |
| The same idempotency key with different terms | `IDEMPOTENCY_CONFLICT`. Use a new key for a new transfer. |
| A Token-2022 mint, an NFT, a token account as the recipient, a frozen account, or too little of the token held | The preparation is refused by name on the phone; the request stays `PENDING` until the owner rejects it or it expires. The reasons are in [`../guides/transfers.md`](../guides/transfers.md#what-it-refuses-and-why). |
| An amount that isn't a whole number of base units | Refused before anything is stored. There is no rounding anywhere. |

## 7. Hermes on a VPS

In Stage 1, the sidecar listens only on the Mac's loopback address. A Hermes on a VPS reaches it through an SSH reverse tunnel that the Mac opens to the VPS. The VPS needs no public domain, no open port, and no OAuth.

On the Mac, with the sidecar running, open the tunnel and keep it running:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -R 127.0.0.1:18080:127.0.0.1:8080 <you>@<vps>
```

- **`-R 127.0.0.1:18080:127.0.0.1:8080`** makes port 18080 on the VPS's loopback address lead to the sidecar on the Mac. Nothing listens on the VPS's public addresses.
- **`-N`** runs no remote command.
- **`ExitOnForwardFailure=yes`** stops at once if port 18080 is already taken on the VPS.
- **`ServerAliveInterval=30`** notices a dead connection.

On the VPS:

1. Add the same entry to the VPS's `~/.hermes/config.yaml`, with `url: "http://127.0.0.1:18080/mcp"`.
2. Put the token in the VPS's `~/.hermes/.env`, and run `chmod 600` on it.
3. Run `hermes mcp test seeker_vault`, then `/reload-mcp` in the session.

The sidecar accepts tunnelled requests because their `Host` is still a loopback address (`127.0.0.1:18080`); it accepts loopback names on any port. Every process on the VPS can reach `127.0.0.1:18080`, which is one more reason the MCP token matters. Don't run this on a VPS shared with people you don't trust.

The tunnel lasts only as long as the SSH session. When it drops, calls fail with the errors below. Start it again, then run `/reload-mcp` if Hermes parked the server.

In every case, the sidecar itself stays on the Mac's loopback address: never set `SIDECAR_HOST` to a public address, and never expose port 8080 to the internet. The sidecar speaks plain HTTP. A public gateway with TLS and OAuth comes in a later stage.

### Over a VPN you already use

If the Mac and the VPS already share a private VPN that you control, such as Tailscale, Hermes can reach the sidecar over it instead of an SSH tunnel. The owner's Stage 1 check passed this way.

1. **Forward a port on the Mac's VPN address to the sidecar.** For example, with `socat`:

   ```bash
   socat TCP-LISTEN:8081,fork,reuseaddr,bind=<mac-vpn-ip> TCP:127.0.0.1:8080
   ```

   Only this forward listens on the VPN address, which for Tailscale is a `100.x.y.z` address. The sidecar stays on loopback.

2. **Limit who can reach that port.** In the VPN's access rules (Tailscale ACLs), allow only the VPS to reach port 8081 on the Mac.
3. **Let the sidecar accept the address.** Add `MCP_ALLOWED_HOSTS=<mac-vpn-ip>` to the sidecar's `.env`, and restart the sidecar. Without it, the sidecar refuses Hermes with 403, because the requests' `Host` isn't a loopback address.
4. **Point Hermes at the forward.** On the VPS, set `url: "http://<mac-vpn-ip>:8081/mcp"` in the `seeker_vault` entry. Then run `hermes mcp test seeker_vault` and `/reload-mcp`.

The traffic is plain HTTP inside the VPN's encrypted tunnel. Never bind the forward to a public address.

## 8. What failures look like

These are real results from Hermes v0.21.1. "The model sees" is the tool result the model receives.

| Situation | What Hermes reports |
| --- | --- |
| The app isn't connected | The model sees `{"error": "OFFLINE: no phone is watching; open the live-test screen and connect"}` at once. Nothing is queued: after you connect, the phone receives nothing until Hermes calls again. |
| The tunnel or connection drops while the phone waits for OK | The model sees `{"error": "MCP call failed: MCPError: SSE stream ended without a response"}` at once. The sidecar cancels the command, so tapping OK on the phone shows "The agent cancelled this command." Nothing is replayed. |
| The tunnel is down, or the sidecar isn't running, when the session starts | Hermes tries three times, then parks the server, and the tool is missing from the session. A call gets `{"error": "Unknown tool: mcp__seeker_vault__vault_display_command"}`, and `hermes mcp test` says `✗ Connection failed (7784ms): All connection attempts failed`. Start the tunnel or sidecar, then run `/reload-mcp`. |
| The token is missing from `~/.hermes/.env`, or wrong | `hermes mcp test` says `✗ Connection failed (6330ms): a valid MCP token is required`, and the sidecar logs `rejected POST /mcp: a valid MCP token is required`. Hermes treats this as permanent and parks the server. Fix `.env`, then run `/reload-mcp`. |
| Hermes reaches the sidecar through a VPN address, and the sidecar refuses it | `hermes mcp test` fails, and the sidecar logs `rejected POST /mcp: the Host header is not a loopback address or an MCP_ALLOWED_HOSTS entry`. Add the Mac's VPN address to `MCP_ALLOWED_HOSTS` and restart the sidecar; see [over a VPN](#over-a-vpn-you-already-use). |
| Nobody taps OK in time | Not run with Hermes. The sidecar's answer is `TIMEOUT: no acknowledgement within 60 seconds`, and Hermes passes it on as an error, the same way as `OFFLINE`. |
| The sidecar runs without `MCP_DEMO_TOOLS=true` | `hermes mcp test` discovers 3 tools, though `hermes mcp list` still says `4 selected`. A call gets `{"error": "Unknown tool: mcp__seeker_vault__vault_request_ack"}` from Hermes itself, and never reaches the sidecar. `vault_get_request` still works. Set the variable, restart the sidecar, and run `/reload-mcp`. |
| A request ID the sidecar doesn't know | The model sees `{"error": "NOT_FOUND: no such request"}`. |
| The owner has connected no wallet | Not run with Hermes. The sidecar answers `WALLET_NOT_CONNECTED: the owner has no wallet connected on their phone; ask them to connect one in the app`, and Hermes passes it on as an error. Connect one ([`wallet-setup.md`](../guides/wallet-setup.md)); no address is ever invented. |
| A signing request names another wallet | Not run with Hermes. The sidecar answers `WALLET_MISMATCH`, and nothing is stored. Read `vault_get_address` again rather than caching an address: the owner can change it at any time. |
| No phone is paired | Not run with Hermes. The sidecar answers `NOT_PAIRED: no phone is paired with this sidecar`, and Hermes passes it on as an error, the same way as `NOT_FOUND`. Pair the phone ([`pairing.md`](../guides/pairing.md)). |

For problems on the phone or the Mac, see [`troubleshooting.md`](../guides/troubleshooting.md).

## Keeping the token safe

- The token belongs in `~/.hermes/.env` with mode 600, never in `config.yaml` or a chat.
- Anyone with the MCP token can show text on your phone, queue requests for you to answer, and read your answers. In later stages the same endpoint carries wallet requests, so treat it like a password.
- Hermes redacts `Bearer …` values from MCP error messages, and the sidecar never logs tokens or command text.
