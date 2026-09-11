# SEE-13 / SAW-007 — Connect real Hermes and document local and VPS setups

Linear: https://linear.app/seekeragentwallet/issue/SEE-13 · Branch: `develop`

## Checklist

- [x] Research the Hermes MCP client from its docs and its v0.21.1 source: config format, headers from environment variables, timeouts, tool naming, reload, how to inspect calls, error handling
- [x] `examples/hermes.config.yaml`: a mergeable `mcp_servers` entry with the sidecar URL, `Authorization` from an environment variable, and a tool timeout above the sidecar's deadline
- [x] `docs/integrations/hermes.md`:
  - [x] merge the entry without overwriting the owner's config
  - [x] reload or restart, and check that the tool was discovered
  - [x] the exact test prompt, "Hello from Hermes — seeker-check-001"
  - [x] Hermes on the Mac, and Hermes on a VPS through an SSH reverse tunnel (no public domain, no OAuth)
  - [x] a listed tool is not a passed test: check the actual call and the acknowledgement
- [x] `docs/testing/stage-1.md`: the owner-run Hermes check, including negative cases (app disconnected, tunnel stopped, no delayed replay), plus a verification record
- [x] Local verification:
  - [x] Hermes's own MCP client, against the example config in a scratch `HERMES_HOME`
  - [x] the test agent, through a simulated tunnel
  - [x] cases: discovery, OFFLINE, the exact text, a missing or wrong token, a mid-call tunnel drop, the tunnel stopped, no replay
- [x] Docs: README stage row, `CODEBASE.md`, integration docs moved to `docs/integrations/`, `sidecar.md` pointer, changelog, decisions
- [x] Verify: `pnpm check`, `pnpm check:android`, `pnpm check:generated`, `pnpm build`
- [ ] Commit, push, and confirm CI is green
- [ ] Linear: tick the done items, leave the owner's real Hermes checks open (NOT RUN), and move the issue to In Review

## Review

**What changed:**

- **`examples/hermes.config.yaml`** is a `seeker_vault` entry to merge into `~/.hermes/config.yaml`. It sets `Authorization: "Bearer ${MCP_SEEKER_VAULT_API_KEY}"` with the token kept in `~/.hermes/.env`, `timeout: 90`, and only `vault_display_command` allowed.
- **`docs/integrations/hermes.md`** covers:
  - the merge, discovery, and reload
  - the exact prompt
  - the VPS setup through `ssh -R`
  - real failure outputs
  - why a listed tool isn't a passed test
- **`docs/testing/stage-1.md`** holds the owner-run check and the verification record.

**How it was verified:**

- **Hermes v0.21.1's real code.** Installed in a scratch venv with a scratch `HERMES_HOME`, it ran `hermes mcp list` and `test`, then made tool calls through `discover_mcp_tools` and `handle_function_call`. That's the path a model's call takes, with no LLM involved.
- **Every case, directly and through a TCP forwarder standing in for `ssh -R`:** discovery, OFFLINE, the exact text with a matching ID, a missing or wrong token, a mid-call drop, the tunnel stopped, and no replay.
- **The test agent over the same forwarder.**

**Caveats:**

- **The owner's real Hermes session with a model and the Seeker is NOT RUN, and so is a real VPS tunnel.** Those Linear items stay open.
- **On a mid-call drop, the test agent waits for its 75-second client timeout and reports `TIMEOUT`.** Hermes reports the drop at once. This is recorded in `decisions.md` for SAW-008.
