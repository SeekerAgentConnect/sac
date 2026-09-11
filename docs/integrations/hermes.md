# Hermes

This page connects [Hermes Agent](https://hermes-agent.nousresearch.com) to the sidecar's MCP endpoint, so a Hermes session can call `vault_display_command`: Hermes sends text, the Seeker shows it, you tap **OK**, and Hermes gets the acknowledgement. It covers Hermes on the Mac and Hermes on a VPS.

> **What has been tested.** Hermes Agent v0.21.1 (2026.9.7) was run with this configuration against the sidecar, including through a forwarded port, and every output below is real. Hermes's own MCP client made the tool calls, without an LLM, and a test client stood in for the phone. A real Hermes session with a model and the physical Seeker has not been run yet. See [`docs/testing/stage-1.md`](../testing/stage-1.md).

## Before you start

- The sidecar runs, and the Seeker is connected: steps 19 to 23 of the [MacBook → Seeker quickstart](../guides/macbook-seeker-quickstart.md).
- Hermes is installed and works on its own. Its MCP support is part of the standard install.
- You need the `MCP_TOKEN` value from the sidecar's `.env`. It's the agent's token; the phone's `PHONE_TOKEN` won't work here.

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
      include: [vault_display_command]
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
| `url` | The sidecar's MCP endpoint. It listens only on the Mac's loopback address. On a VPS, this is the tunnel's end instead; see [Hermes on a VPS](#4-hermes-on-a-vps). |
| `Authorization` | Hermes fills in `${MCP_SEEKER_VAULT_API_KEY}` from `~/.hermes/.env`, or from the environment, when it loads the configuration. If the variable is missing, Hermes sends the literal text, and the sidecar refuses the connection. `hermes mcp add` uses the same variable name for a server called `seeker_vault`. |
| `timeout` | How long Hermes waits for the tool call, in seconds; Hermes's default is 300. Keep it above the sidecar's `LIVE_COMMAND_TIMEOUT_SECONDS` (60 by default), so that the sidecar's own `TIMEOUT` answer arrives first, and below 300, Hermes's fixed HTTP read limit. If you raise `LIVE_COMMAND_TIMEOUT_SECONDS`, raise this too. |
| `tools` | Exposes only `vault_display_command`. The sidecar offers no resources or prompts. Later stages add tools to this server, and each one should be allowed on purpose. |

Leave `trust` unset, which Hermes treats as `full`. With `trust: untrusted`, Hermes asks for approval before every call, because this tool isn't read-only.

Hermes names the tool `mcp__seeker_vault__vault_display_command`. Its MCP documentation page shows an older name with single underscores; v0.21.1 registers the double-underscore form.

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
  seeker_vault     http://127.0.0.1:8080/mcp      1 selected   ✓ enabled
```

```text
  Testing 'seeker_vault'...
  Transport: HTTP → http://127.0.0.1:8080/mcp
    Authorization: Bear***xxxx
  ✓ Connected (2115ms)
  ✓ Tools discovered: 1

    vault_display_command                Shows display-only text on the owner's Seeker (its live...
```

Hermes masks the header and shows only its first and last four characters; `xxxx` stands for your token's last four. For errors instead of `✓ Connected`, see [what failures look like](#5-what-failures-look-like).

Hermes connects to MCP servers when a session starts. After changing the configuration, type `/reload-mcp` in the running session, or start a new session. Do the same if the sidecar wasn't running when the session started: Hermes then tried three times, parked the server, and the session has no `vault_display_command`.

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

## 4. Hermes on a VPS

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

If you already have a secured path from the VPS to the Mac, such as a VPN you manage, you can run the same tunnel over it. In every case, keep the sidecar on the Mac's loopback: never set `SIDECAR_HOST` to a public address, and never expose port 8080. The sidecar speaks plain HTTP. A public gateway with TLS and OAuth comes in a later stage.

## 5. What failures look like

These are real results from Hermes v0.21.1. "The model sees" is the tool result the model receives.

| Situation | What Hermes reports |
| --- | --- |
| The app isn't connected | The model sees `{"error": "OFFLINE: no phone is watching; open the live-test screen and connect"}` at once. Nothing is queued: after you connect, the phone receives nothing until Hermes calls again. |
| The tunnel or connection drops while the phone waits for OK | The model sees `{"error": "MCP call failed: MCPError: SSE stream ended without a response"}` at once. The sidecar cancels the command, so tapping OK on the phone shows "The agent cancelled this command." Nothing is replayed. |
| The tunnel is down, or the sidecar isn't running, when the session starts | Hermes tries three times, then parks the server, and the tool is missing from the session. A call gets `{"error": "Unknown tool: mcp__seeker_vault__vault_display_command"}`, and `hermes mcp test` says `✗ Connection failed (7784ms): All connection attempts failed`. Start the tunnel or sidecar, then run `/reload-mcp`. |
| The token is missing from `~/.hermes/.env`, or wrong | `hermes mcp test` says `✗ Connection failed (6330ms): a valid MCP token is required`, and the sidecar logs `rejected POST /mcp: a valid MCP token is required`. Hermes treats this as permanent and parks the server. Fix `.env`, then run `/reload-mcp`. |
| Nobody taps OK in time | Not run with Hermes. The sidecar's answer is `TIMEOUT: no acknowledgement within 60 seconds`, and Hermes passes it on as an error, the same way as `OFFLINE`. |

For problems on the phone or the Mac, see [`troubleshooting.md`](../guides/troubleshooting.md).

## Keeping the token safe

- The token belongs in `~/.hermes/.env` with mode 600, never in `config.yaml` or a chat.
- Anyone with the MCP token can show text on your phone and wait for your OK. In later stages the same endpoint carries wallet requests, so treat it like a password.
- Hermes redacts `Bearer …` values from MCP error messages, and the sidecar never logs tokens or command text.

## Notes

- confugured rules for 8081 port in tailscale
- socat TCP-LISTEN:8081,fork,reuseaddr,bind=100.119.134.109 TCP:127.0.0.1:8080