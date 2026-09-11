# Hello-world tests

This page describes how to test the Stage 1 transport without an LLM. The test agent (`pnpm agent`) calls the sidecar's MCP tool, the phone shows the text, and the agent prints the acknowledgement. The client is the same MCP interface that Hermes uses; see [`test-agent/README.md`](../../test-agent/README.md). The same flow with Hermes itself is in [`stage-1.md`](stage-1.md).

## Automated tests

`pnpm check` runs `test-agent/src/cli.test.ts`. It starts the real CLI as a process against an in-process sidecar, and the sidecar's Connect test client acts as the phone (the protocol test device). The test checks:

- **ID correlation:** the printed `{"id","result":"OK"}` names the same command that the phone received.
- **Exit codes:**
  - 0 for OK
  - 4 for OFFLINE
  - 5 for BUSY
  - 6 for TIMEOUT, from the sidecar's deadline and from the client timeout; a client timeout also cancels the command on the sidecar
  - 8 for INVALID_TEXT
  - 3 for an unreachable sidecar, a rejected token, or the phone token used as the MCP token, and for `ack` on a sidecar without the demo tool
  - 2 for missing configuration or bad arguments
- **Tool discovery:** `pnpm agent tools` lists `vault_display_command`, followed by the durable request tools `vault_get_request` and `vault_cancel_request` (SAW-010). A sidecar with `MCP_DEMO_TOOLS=true` also lists `vault_request_ack` (SAW-014).
- **Configuration:** `.env` is loaded, and variables already in the environment take precedence.
- **Credentials:** no token appears in stdout or stderr on any run.

## With the physical Seeker

The first-time setup is in the [MacBook → Seeker quickstart](../guides/macbook-seeker-quickstart.md): the tools, the APK, USB debugging, and `adb reverse`.

1. Configure and start the sidecar with `pnpm dev:sidecar`; see [`docs/development/sidecar.md`](../development/sidecar.md).
2. Connect the phone over USB and run `adb reverse tcp:8080 tcp:8080`; see [`docs/development/android.md`](../development/android.md).
3. On the Seeker, open Seeker Vault, tap **Live test**, enter the phone token, and tap **Connect**. The status reads "Connected".
4. On the Mac, run `pnpm --silent agent hello "Hello Seeker"`.
5. The Seeker shows "Hello Seeker". Tap **OK**.
6. The agent should exit with code 0 and print `{"id":"<uuid>","result":"OK"}`. The ID must match the sidecar's log line `command <uuid> acknowledged`.

Negative checks:

- **OFFLINE:** tap **Disconnect** on the phone, then repeat step 4. The agent should exit with code 4 and print `OFFLINE: ...`.
- **TIMEOUT:** repeat step 4 and don't tap OK. After `LIVE_COMMAND_TIMEOUT_SECONDS`, the agent should exit with code 6 and print `TIMEOUT: ...`, and the phone shows "Timed out".
- **BUSY:** repeat step 4 in two terminals at once. The second agent should exit with code 5 and print `BUSY: ...`.

Record each result as PASS, FAIL, or NOT RUN, together with the phone's Android version and the commit tested. A run against the Connect test client doesn't count as a device pass.

## Verification record: SAW-005

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`docs/development/toolchain.md`](../development/toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: test agent 15/15 (3 configuration, 12 CLI), sidecar 65/65 |
| CLI against the real sidecar, with the Connect test client as the phone | PASS. The printed ID matched the phone's command. Exit codes: 0; 4; 5; 6 for both the sidecar's deadline and the client timeout, which also cancelled the command; 8; 3 for an unreachable sidecar, a wrong token, and the phone token; 2. `tools` listed the tool, and `.env` precedence held. |
| Live `pnpm agent` through the root script and a temporary `.env` | PASS. `tools` exited 0. `hello` with no phone exited 4, and pnpm kept that code. `hello` with a scripted phone exited 0 and printed `{"id":"e082a227-…","result":"OK"}`, the same ID the phone received. A wrong `MCP_TOKEN` in the environment overrode `.env`, and the agent exited 3. |
| Credentials in output | PASS: no token appeared in any test or live output |
| Deliberate breaks | Each break was caught; see the list below. |
| `pnpm build`, `pnpm check:generated`, `pnpm check:android` | PASS |
| Physical Seeker: tap OK and inspect the returned payload | NOT RUN: no device was attached during verification |

The deliberate breaks:

- **Reporting success without an acknowledgement** (skipping the tool-error and acknowledgement checks) failed the OFFLINE, BUSY, INVALID_TEXT, and TIMEOUT tests.
- **Printing the token in the progress line, with redaction on,** still passed, because the output showed `[redacted]`.
- **The same leak with redaction off** failed the test "never prints a token".
- **Mapping BUSY to exit code 1** failed the BUSY test.
