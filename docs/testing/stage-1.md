# Stage 1 tests

This page covers the Stage 1 flow with a real agent: Hermes sends text over MCP, the Seeker shows it, the owner taps OK, and Hermes receives the acknowledgement. It holds the owner-run Hermes check and the record of what has been run so far. The same flow with the test agent instead of Hermes is in [`hello-world.md`](hello-world.md). The complete acceptance gate comes in SAW-008.

## Owner-run Hermes check

**Before you start:**

- Finish the [MacBook → Seeker quickstart](../guides/macbook-seeker-quickstart.md). The app must be connected, with the status "Connected".
- Configure Hermes as described in [`docs/integrations/hermes.md`](../integrations/hermes.md).

**The check:**

1. Run `hermes mcp test seeker_vault`. Expect `✓ Connected` and `Tools discovered: 1`.
2. Start a new Hermes session, or run `/reload-mcp` in a running one. Then turn on verbose tool output with `/verbose`.
3. Send the exact prompt:

   ```text
   Call the vault_display_command tool with the text "Hello from Hermes — seeker-check-001" and report the real result.
   ```

4. On the Seeker, confirm that exactly `Hello from Hermes — seeker-check-001` appears. Only then tap **OK**.
5. In Hermes's tool output, confirm the call and its result, `{"id":"<uuid>","result":"OK"}`. Compare the ID with the sidecar's log line `command <uuid> acknowledged`. Hermes's answer must report that ID. If Hermes claims success without a tool call in its output, the check fails.

**Negative checks:**

6. **App disconnected.**
   1. Tap **Disconnect** on the phone and send the prompt again. Hermes's tool result must be an `OFFLINE` error.
   2. Tap **Connect**. The phone must show nothing from the failed attempt.
7. **Tunnel stopped** (VPS setup only).
   1. Stop the `ssh -R` command and send the prompt. The call must fail with a connection error, or the tool must be missing from a new session.
   2. Restart the tunnel, run `/reload-mcp`, and connect the phone. Nothing old may appear.
8. **Tunnel dropped while waiting** (VPS setup only).
   1. Send the prompt, and stop the tunnel before you tap OK. Hermes must report `MCP call failed: … SSE stream ended without a response`.
   2. Tap **OK**. The phone must say "The agent cancelled this command."

**Record:** note the commit, `hermes --version`, where Hermes ran (the Mac or a VPS), the Seeker's Android version (`adb shell getprop ro.build.version.release`), and PASS, FAIL, or NOT RUN for each step.

## Verification record: SAW-007

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), against the sidecar at `5965a76`. The sidecar and test agent are unchanged since then.

**How Hermes was run:**

- Hermes Agent v0.21.1 (2026.9.7) was installed from its release tag into a scratch Python 3.13 environment. Its MCP client is `mcp` 2.0.0, and the sidecar's is the MCP TypeScript SDK 1.30.0.
- `HERMES_HOME` pointed to a scratch directory. `config.yaml` was [`examples/hermes.config.yaml`](../../examples/hermes.config.yaml), and `.env` held `MCP_SEEKER_VAULT_API_KEY`. `~/.hermes` wasn't touched.
- The tool calls went through Hermes's own dispatch path, the one a model's tool call takes, without an LLM:

  ```python
  from model_tools import handle_function_call
  from tools.mcp_tool_discovery import discover_mcp_tools

  discover_mcp_tools()  # loads HERMES_HOME/config.yaml and .env, connects, registers mcp__* tools
  print(handle_function_call("mcp__seeker_vault__vault_display_command",
                             {"text": "Hello from Hermes — seeker-check-001"}))
  ```

- The sidecar's Connect test client stood in for the phone.
- A local TCP forwarder on `127.0.0.1:18080` stood in for the VPS end of `ssh -R`, because the verification Mac runs no SSH server.

| Check | Result |
| --- | --- |
| The example config in Hermes's CLI: `hermes mcp list`, `hermes mcp test seeker_vault` | PASS: `1 selected ✓ enabled`, then `✓ Connected` and `Tools discovered: 1`. Hermes filled in the `Authorization` header from `.env`. |
| Protocol compatibility: Python `mcp` 2.0.0 client, TypeScript SDK 1.30.0 server | PASS: Hermes's client initialized, listed the tool, and called it |
| The test prompt's call, with the test client tapping OK | PASS. The phone received exactly `Hello from Hermes — seeker-check-001` (38 bytes). The model-visible result was `{"result": "{\"id\":\"fbf298f6-…\",\"result\":\"OK\"}"}`, and the ID matched the phone's command and the sidecar's log. |
| App disconnected | PASS. The model saw `{"error": "OFFLINE: …"}` at once, and a phone that connected afterwards received nothing. |
| Token missing from `.env`, or wrong | PASS: `✗ Connection failed (…ms): a valid MCP token is required` |
| The VPS path, through the forwarded port `127.0.0.1:18080` | PASS (simulated): discovery and a tool call worked through the forwarder |
| Tunnel dropped while waiting for OK | PASS. Hermes reported `MCP call failed: MCPError: SSE stream ended without a response` at once. The sidecar cancelled the command, the phone's late OK was refused with `canceled`, and nothing was replayed. |
| Tunnel stopped | PASS. `hermes mcp test` said `All connection attempts failed`. A new session parked the server, and a call got `Unknown tool: …`. After the tunnel came back, the phone received nothing. |
| The same path with the test agent (`MCP_URL=http://127.0.0.1:18080/mcp pnpm agent`) | PASS: discovery; OFFLINE with exit code 4; the exact text with a matching ID; exit code 3 with the tunnel stopped. On a mid-call drop, the sidecar cancelled the command at once, but the test agent reported it only at its own 75-second client timeout, as `TIMEOUT` with exit code 6. |
| Tokens and command text in the logs | PASS: none in the sidecar log or the check transcripts |
| `pnpm check`, `pnpm check:android`, `pnpm check:generated`, `pnpm build` | PASS |
| The owner's real Hermes session: a model, the prompt above, and the physical Seeker | NOT RUN: no Hermes session with a model, and no Seeker, on the verification Mac |
| Hermes on a real VPS through `ssh -R` | NOT RUN: no VPS or SSH server was available |
