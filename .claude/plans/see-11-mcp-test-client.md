# SEE-11 / SAW-005 — Create a minimal real MCP test client

Linear: https://linear.app/seekeragentwallet/issue/SEE-11 · Branch: `develop`

## Checklist

- [x] Workspace:
  - [x] add the `test-agent` package
  - [x] move the MCP SDK, zod, and `@types/node` to the catalog
  - [x] root `agent` script runs Node directly, because `--filter` collapses exit codes
- [x] `src/config.ts`: MCP_URL, MCP_TOKEN, and a client timeout of the deadline plus 15 seconds
- [x] `src/agent.ts`:
  - [x] discover the tool, then call it
  - [x] validate the acknowledgement
  - [x] map tool errors, the client timeout, and connection errors to exit codes
- [x] `src/main.ts`: `hello [text]`, `tools`, `--timeout`, `--help`; token redaction; no stack traces
- [x] Tests:
  - [x] config unit tests
  - [x] CLI process against the real sidecar and a Connect phone client (ID correlation, exit codes, `.env` precedence, no tokens in output)
- [x] Docs:
  - [x] `test-agent/README.md`, `docs/testing/hello-world.md` with a verification record
  - [x] README, `CODEBASE.md`, toolchain, `sidecar.md` pointer
  - [x] changelog, decisions
- [x] Verify:
  - [x] `pnpm check` (15 + 65), `build`, `check:generated`, `check:android`
  - [x] live `pnpm agent` through the root script and `.env`
  - [x] four deliberate breaks
- [ ] Commit, push, and confirm CI is green
- [ ] Linear: tick the checklist, leaving the physical Seeker item open (NOT RUN), and move it to In Review

## Review

**What changed.** `pnpm agent hello [text]` is now a real MCP client, built on the MCP SDK with no LLM. It works in five steps:

1. Reads `MCP_URL` and `MCP_TOKEN` from the root `.env`; variables already in the environment win.
2. Discovers `vault_display_command`.
3. Calls it, with a client timeout of the sidecar's deadline plus 15 seconds.
4. Prints the phone's `{"id","result":"OK"}` on stdout.
5. Ends the MCP session.

Every other outcome has its own exit code: connection error, OFFLINE, BUSY, TIMEOUT, CANCELLED, and INVALID_TEXT. Tokens are redacted from all output. `pnpm agent tools` lists the server's tools.

**How it was verified.** The verification record is in `docs/testing/hello-world.md`. It covers:

- the CLI run as a real process against the real sidecar, with a Connect client acting as the phone (ID correlation and every exit code)
- a live run through the documented `pnpm agent` path, with a temporary root `.env`
- four deliberate breaks, each caught by its tests

**Caveats:**

- **The physical Seeker check is NOT RUN**, because no device was available. The Linear item stays open.
- **On a failure, pnpm adds an `[ELIFECYCLE]` line to stderr.** The exit code itself passes through unchanged. `pnpm --silent` keeps stdout to the JSON alone.
- **The tests import the sidecar's test helpers by relative path.** That's for tests only; the test-agent build excludes them.
