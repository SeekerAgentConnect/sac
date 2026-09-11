# Hermes

This page connects [Hermes Agent](https://hermes-agent.nousresearch.com) to the sidecar's MCP endpoint, for two kinds of tool:

- **The live diagnostic, `vault_display_command`.** Hermes sends text, the Seeker shows it, you tap **OK**, and Hermes gets the acknowledgement in the same call.
- **Durable requests, from Stage 2 on.** Hermes creates a request and gets its ID at once. You answer on the phone whenever you next open the app, and Hermes reads the result later; see [queued requests](#4-queued-requests-create-now-read-the-result-later).

It covers Hermes on the Mac and Hermes on a VPS.

> **What has been tested.** Hermes Agent v0.21.1 (2026.9.7) was run with this configuration against the sidecar, including through a forwarded port, and every output below is real. Hermes's own MCP client made the tool calls, without an LLM, and a test client stood in for the phone. On 2026-09-11, the owner also ran the live round trip and reported it passed. That run used their own Hermes on a VPS, reaching the Mac [over Tailscale](#over-a-vpn-you-already-use), and their physical Seeker; see [`docs/testing/stage-1.md`](../testing/stage-1.md). The durable tools were run the same way on 2026-09-11, without a model or the Seeker; see [`docs/testing/stage-2.md`](../testing/stage-2.md#acceptance-report-saw-014).

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
| `url` | The sidecar's MCP endpoint. It listens only on the Mac's loopback address. On a VPS, this is the tunnel's end instead; see [Hermes on a VPS](#5-hermes-on-a-vps). |
| `Authorization` | Hermes fills in `${MCP_SEEKER_VAULT_API_KEY}` from `~/.hermes/.env`, or from the environment, when it loads the configuration. If the variable is missing, Hermes sends the literal text, and the sidecar refuses the connection. `hermes mcp add` uses the same variable name for a server called `seeker_vault`. |
| `timeout` | How long Hermes waits for a tool call, in seconds; Hermes's default is 300. Only `vault_display_command` waits for you. Keep this above the sidecar's `LIVE_COMMAND_TIMEOUT_SECONDS` (60 by default), so that the sidecar's own `TIMEOUT` answer arrives first, and below 300, Hermes's fixed HTTP read limit. If you raise `LIVE_COMMAND_TIMEOUT_SECONDS`, raise this too. The durable tools answer at once. |
| `tools` | The tools Hermes may call, each allowed on purpose:<ul><li>`vault_display_command`: the live diagnostic</li><li>`vault_request_ack`: queues an acknowledgement. It's a demo tool with no wallet involved, and the sidecar serves it only with `MCP_DEMO_TOOLS=true`.</li><li>`vault_get_request`: reads a request's status by its ID</li><li>`vault_cancel_request`: withdraws a request you haven't answered</li></ul>A tool listed here that the sidecar doesn't serve is missing from the session. The sidecar offers no resources or prompts. |

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

Hermes masks the header and shows only its first and last four characters; `xxxx` stands for your token's last four. For errors instead of `✓ Connected`, see [what failures look like](#6-what-failures-look-like).

`hermes mcp list` counts the tools in `include`, not the ones the sidecar serves. A sidecar without `MCP_DEMO_TOOLS=true` still shows as `4 selected`, but `hermes mcp test` discovers 3: every tool but `vault_request_ack`.

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

## 5. Hermes on a VPS

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

## 6. What failures look like

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
| No phone is paired | Not run with Hermes. The sidecar answers `NOT_PAIRED: no phone is paired with this sidecar`, and Hermes passes it on as an error, the same way as `NOT_FOUND`. Pair the phone ([`pairing.md`](../guides/pairing.md)). |

For problems on the phone or the Mac, see [`troubleshooting.md`](../guides/troubleshooting.md).

## Keeping the token safe

- The token belongs in `~/.hermes/.env` with mode 600, never in `config.yaml` or a chat.
- Anyone with the MCP token can show text on your phone, queue requests for you to answer, and read your answers. In later stages the same endpoint carries wallet requests, so treat it like a password.
- Hermes redacts `Bearer …` values from MCP error messages, and the sidecar never logs tokens or command text.
