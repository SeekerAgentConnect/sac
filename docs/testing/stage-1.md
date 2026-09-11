# Stage 1 tests

This page covers the Stage 1 flow with a real agent: Hermes sends text over MCP, the Seeker shows it, the owner taps OK, and Hermes receives the acknowledgement. It holds the owner-run Hermes check and the record of what has been run so far. The same flow with the test agent instead of Hermes is in [`hello-world.md`](hello-world.md). The acceptance report for the whole stage is at the end of this page: [SAW-008](#acceptance-report-saw-008).

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
| The owner's real Hermes session: a model, the prompt above, and the physical Seeker | PASS, run and reported by the owner on 2026-09-11. The owner used their own Hermes on a VPS, reaching the Mac [over Tailscale](../integrations/hermes.md#over-a-vpn-you-already-use), and their Seeker (Android 16). Not run during this verification. |
| Hermes on a real VPS through `ssh -R` | NOT RUN. The owner's VPS reached the Mac over Tailscale instead. |

## Acceptance report: SAW-008

**Stage 1 is accepted.** Every automated check passes, including the round trip on an emulator in CI. On 2026-09-11, the owner ran the real Hermes → Seeker → OK → Hermes round trip on their Seeker and reported it passed. That is the device acceptance check, and a simulated device or an emulator never closes it.

One optional automated check is still NOT RUN: `pnpm test:hello --device` on the Seeker. To run it, attach and authorize the Seeker. The script must report "the Seeker". Gradle removes the app after the run, so reinstall it with `adb install` afterwards.

### What was tested

| Item | Value |
| --- | --- |
| Commit | `1962324` on `develop`, the acceptance gate. This report was added in the next commit. |
| Mac | macOS 26.5.2 (Apple silicon), Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1 on Temurin 21, AGP 9.4.0 |
| CI | GitHub Actions on `ubuntu-24.04`. The emulator runs Android 16 (API 36), `google_apis`, x86_64: [run 34560580432](https://github.com/BrRenat/SeekerAgentWallet/actions/runs/34560580432) |
| Hermes | For the round trip, the owner's own Hermes on a VPS; its version wasn't reported. For the SAW-007 client check above, v0.21.1 (2026.9.7). |
| Seeker | The owner's Solana Mobile Seeker, Android 16 (API 36), as read over adb |
| Credentials | Every check uses fixed test tokens or throwaway random ones. None is printed or recorded, and no Hermes chat content is recorded. |

### Commands

```bash
pnpm install --frozen-lockfile
pnpm check                 # formatting, lint, types, unit and integration tests
pnpm test:hello            # the Stage 1 acceptance suite on a simulated device
pnpm check:android         # Kotlin formatting, unit tests, lint, the debug and test APKs
pnpm check:generated
pnpm build
pnpm test:hello --device   # with one device or emulator attached
```

### Results by case

| Case | Simulated device (`pnpm test:hello`) | Emulator (`pnpm test:hello --device` in CI) | Physical Seeker |
| --- | --- | --- | --- |
| Happy path: the exact text, then one OK with the same ID | PASS | PASS | PASS, in the owner's Hermes run |
| App offline: `OFFLINE` at once, and nothing replayed later | PASS | Not covered | NOT RUN |
| A second simultaneous command: `BUSY`, and the first one still completes | PASS | Not covered | NOT RUN |
| Timeout: `TIMEOUT` at the deadline, and a late OK is refused | PASS | Not covered | NOT RUN |
| Double tap: one acknowledgement | PASS | PASS: the UI test tapped OK twice, and the sidecar logged one acknowledgement | NOT RUN |
| Sidecar restart: `CANCELLED` on SIGTERM, and exit code 3 at once on SIGKILL; nothing replayed | PASS | Not covered | NOT RUN |
| App restart: `CANCELLED`, and the reopened app receives nothing | PASS, simulated by closing the stream | Not covered | NOT RUN |
| Real Hermes → Seeker → OK → Hermes | Not applicable | Not applicable | PASS, run and reported by the owner on 2026-09-11 |

On the app side, `pnpm check:android` also covers these with Robolectric: the double tap, rotation during a command, backgrounding, and every connection and command message.

### Other checks

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: sidecar 67/67, test agent 15/15 |
| `pnpm test:hello` | PASS: 9/9, in about 9 seconds |
| `pnpm check:android` | PASS: 45/45 unit tests, lint with no issues, and both the debug APK and the instrumentation APK built |
| `pnpm check:generated`, `pnpm build` | PASS |
| Stage boundary | PASS. `StageOneBoundaryTest` and `sidecar/src/stage-boundary.test.ts` found no wallet library, key API, stored data, or background component. In the built release APK, the merged manifest holds `MainActivity`, the `INTERNET` permission, AndroidX's app-private receiver permission, and two AndroidX library components: `androidx.startup.InitializationProvider` and `androidx.profileinstaller.ProfileInstallReceiver`. Neither is a service, and neither runs app code in the background. The debug APK adds two debug-only tooling activities: `PreviewActivity` and the Compose test host. |
| Deliberate breaks | Each one failed its check. See the list below. |
| After the PR #2 review fixes | PASS: `pnpm check` (sidecar 73/73, test agent 15/15), `pnpm test:hello` 9/9, and `pnpm check:android` (45/45 unit tests, lint with no issues, both APKs). Each new test failed when its fix was removed: the overdue-command timeout, the 400 for a malformed request target, the bearer-token characters, `MCP_ALLOWED_HOSTS`, and the app's port range. |
| CI on `1962324` | PASS on all three jobs. The emulator job built and ran `connectedDebugAndroidTest` in 7 minutes 12 seconds, and printed: `PASS on an emulator (sdk_gphone64_x86_64, Android 16, API 36): the app showed the exact text, the double tap sent one OK, and the agent printed {"id":"2a4bd0d6-fa7f-45bc-a998-62fba0083aba","result":"OK"}`, then `This was an emulator. It doesn't count as the physical Seeker check.` |

The deliberate breaks:

- **The test agent's drop detection, disabled.** The SIGKILL restart case failed: exit code 6 after the 20-second client timeout, instead of 3 at once.
- **A `<service>` in the manifest and `getSharedPreferences` in `MainActivity`.** Two of the three `StageOneBoundaryTest` checks failed. The first attempt caught only the manifest, because the regex required a word boundary before `SharedPreferences`. It now matches substrings, and the rerun caught both.
- **An `@solana/web3.js` entry in the lockfile and a `node:fs` import in the sidecar.** Both Node boundary tests failed.
