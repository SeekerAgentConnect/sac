# SEE-21 / SAW-014 — Validate the queued workflow and update agent-facing examples

Linear: https://linear.app/seekeragentwallet/issue/SEE-21 · Branch: `develop` · One PR `develop` → `master` after this ticket

## Checklist

- [x] Demo-only ack tool: the sidecar serves `vault_request_ack` only with `MCP_DEMO_TOOLS=true`. Without it, agents get `vault_display_command`, `vault_get_request`, and `vault_cancel_request`, and the instructions and the startup log say which.
- [x] Test agent: `pnpm agent ack` checks that the sidecar offers the tool, and exits 3 naming `MCP_DEMO_TOOLS` when it doesn't. `get` reads a request's status. SEE-20 added `ack`, `get`, and `cancel`.
- [x] Deterministic clocks:
  - [x] `SidecarOptions.now` for in-process tests
  - [x] a test-only preload (`sidecar/src/testing/clock.ts`) that runs a sidecar process's clock ahead, for time that passes while it's down
- [x] Integration tests over MCP and Connect, with a fake clock and throwaway databases: expiry on both endpoints, and an expired pairing code
- [x] `pnpm test:queue` (`test-agent/src/stage2.acceptance.ts`): two sidecar processes, the real CLI, and a test phone paired with both
  - [x] a request queued while the app is closed survives a sidecar restart (SIGKILL), and completes once the phone opens
  - [x] a rejection on the other server
  - [x] a request that expires while its sidecar is down
  - [x] a revoked pairing, then pairing again
  - [x] a result can't be read or submitted with another connection's identity, on the same server or the other one
  - [x] the live Stage 1 diagnostic stores nothing and replays nothing
- [x] `Stage2AcceptanceTest` (Android, Robolectric): the app's repository and files, with two real sidecars
  - [x] app and sidecar restarts
  - [x] an answer given while its sidecar is down
  - [x] expiry, rejection, and revocation
- [x] CI: `pnpm test:queue` in the Node job
- [x] Hermes:
  - [x] `examples/hermes.config.yaml` allows the durable tools
  - [x] `docs/integrations/hermes.md` explains the change from the live tool's bounded wait to an ID returned at once, with an example that creates a request and checks it later
  - [x] a run with Hermes's own MCP client, if it installs
- [x] Docs:
  - [x] `docs/testing/stage-2.md` (the acceptance report), `test-agent/README.md`, `docs/integrations/hermes.md`
  - [x] `docs/development/sidecar.md`, `docs/protocol.md`, `docs/guides/pending-requests.md`, `docs/testing/hello-world.md`, `.env.example`
  - [x] README, AGENTS, CODEBASE, the changelog, and the decisions
- [x] Verify: `pnpm check`, `pnpm test:hello`, `pnpm test:queue`, `pnpm check:android`, `pnpm check:generated`, and deliberate breaks, each with a time limit
- [ ] Commit on `develop`, push, and get CI green; then one PR `develop` → `master`
- [ ] Linear: tick the checklist, add a summary, and move the issue to In Review

## Design

- **The demo tool is opt-in.** A sidecar serves `vault_request_ack` only when `MCP_DEMO_TOOLS=true`. `.env.example`, which is a development configuration, sets it. A deployment that copies nothing gets only the tools that later stages' wallet requests use: `vault_get_request` and `vault_cancel_request`, plus the Stage 1 diagnostic.
- **Time passes while a sidecar is down.**
  - The request and pairing stores read the clock through `Date.now()`, or through the `now` option.
  - In-process tests pass a fake clock through `SidecarOptions.now`.
  - Process tests restart a sidecar with `--import sidecar/src/testing/clock.ts`, which moves `Date.now()` ahead by a fixed amount for that process. Shipped code is unchanged, and the build and the boundary checks leave out `testing/`.
- **Two acceptance suites, one per side.**
  - `pnpm test:queue` drives the agent's side: the CLI, sidecar processes, and restarts, with a Connect test client as the phone.
  - `Stage2AcceptanceTest` drives the app's side: the repository and its files, with real sidecars.
  - Neither counts as the physical Seeker check.

## Review

Everything above is done except the commit, the push and CI, the pull request, and the Linear update, which follow this review.

- **What changed:**
  - The sidecar serves `vault_request_ack` only with `MCP_DEMO_TOOLS=true`. `createMcpEndpoint` takes its allowed hosts and that flag as options, and `startSidecar` takes a `now` clock.
  - `pnpm agent ack` requires the tool, and exits 3 without it.
  - `pnpm test:queue` and `Stage2AcceptanceTest` run the Stage 2 acceptance scenario, and CI's Node job runs `pnpm test:queue`.
  - Hermes's example config allows the durable tools, and the guide explains the move from the live tool's bounded wait to an ID returned at once.
- **Verified (PASS):**
  - `pnpm check`: 239/239 sidecar tests and 20/20 test agent tests
  - `pnpm test:queue` 7/7, and `pnpm test:hello` 9/9
  - `pnpm check:android`: 185/185 unit tests, lint clean, both APKs
  - `pnpm check:generated`, and `pnpm build`
  - Hermes v0.21.1's own MCP client, which ran these:
    - create a request, retry it, and read it as PENDING
    - read COMPLETED from a new session
    - reject a request, cancel one, and look up an unknown ID (NOT_FOUND)
    - find 3 tools without the demo flag
- **Deliberate breaks:** all 6 caught, each under a time limit, with each file restored byte for byte:
  - the demo tool always on
  - `ack` without its tool check
  - the test clock not moved ahead
  - revocation that leaves PENDING requests
  - a request looked up without its connection
  - the app never resending a waiting answer
- **NOT RUN:** the owner-run checks on the physical Seeker, because no device was attached
- **Caveats:**
  - An existing `.env` without `MCP_DEMO_TOOLS=true` loses `vault_request_ack` with this change. `pnpm agent ack` then says so, and exits 3.
  - `hermes mcp list` counts the `include` list, so it says `4 selected` even when the sidecar serves 3. `hermes mcp test` shows the real number.
  - The process clock moves only `Date.now()`, and timers are unaffected. That's enough for expiry, which the sidecar applies on each operation rather than with a timer.
