# SEE-142 — Fix MCP h2c healthcheck leaking HTTP/2 sessions on timeout

Linear: https://linear.app/seekeragentwallet/issue/SEE-142/fix-mcp-h2c-healthcheck-leaking-http2-sessions-on-timeout

Ticket source: Linear MCP `get_issue` + `list_comments` (no comments). Related: SEE-140, SEE-137. Out of scope: SEE-140, SEE-141, pairing, SDK, gateway, Android, storage, deployments.

Base: `origin/master` at `ed16d792357cb223a06c80f2e0872709a2577856` (contains PR #40). Branch: `superset/feat/see-142`. Node: `v24.21.0`.

## Defect (confirmed on this branch)

`checkH2cHealth` (`mcp-server/src/healthcheck.ts` L144–188): the timeout callback only `reject()`s. It does not destroy the request or the `http2.connect` session. The error path uses graceful `client.close()`, which can wait on a stalled stream. The standalone command sets `process.exitCode = 1` and does not `process.exit()`, so leaked sockets keep the process alive.

## Files

- `mcp-server/src/healthcheck.ts` — one idempotent settle/cleanup path; destroy request + session on timeout/error; keep original error; swallow late events.
- `mcp-server/src/healthcheck.test.ts` — stalled-peer close observation (fail before fix), headers-without-body, premature close, repeated probes, subprocess of the real entry point.
- `docs/changelog/2026-09-21.md` — bugfix note.
- This plan's review section after verification.

No pairing/page, deploy specs, SDK, gateway, Android, or storage.

## Verification

```bash
node -v   # 24.21.0
# 1. First regression only, before production edit (expect FAIL):
node --test --test-reporter=spec mcp-server/src/healthcheck.test.ts
# 2. After fix, same file (expect PASS), plus:
pnpm --filter @seeker-vault/mcp-server run typecheck
pnpm exec eslint mcp-server/src/healthcheck.ts mcp-server/src/healthcheck.test.ts
pnpm exec prettier --check mcp-server/src/healthcheck.ts mcp-server/src/healthcheck.test.ts
```

Do not add `process.exit()` to hide a leak. Do not mock the HTTP/2 session.

## Acceptance (Linear, verbatim)

- [x] The stalled h2c regression fails before the fix and passes after it.
- [x] Timeout/error cleanup leaves no live session or request owned by the probe.
- [x] The standalone failing probe exits without an external kill.
- [x] Existing healthcheck behavior and TLS verification remain intact.
- [ ] The PR records the tested commit, Node version and exact test results.

## Review

**Why it leaked:** `checkH2cHealth`'s timeout only `reject()`ed. The timer is `unref()`d, so the promise settled while the HTTP/2 session and stalled stream kept the event loop (and the standalone command) alive. The error path used graceful `client.close()`, which waits for active streams.

**Why destroy terminates a peer that never completes:** one `finish()` path sets `settled`, clears the timer, then `request.destroy()` + `client.destroy()`. Late `error`/`close` events cannot replace the original timeout. The peer observes stream/session `close`. No `process.exit()`.

**Fail-before-fix:** `closes the stalled h2c session after the probe deadline` on the unmodified source:

```text
Error: h2c peer did not observe stream/session closure after probe timeout
```

**After fix (Node v24.21.0, base `ed16d792357cb223a06c80f2e0872709a2577856`):**

```bash
node --test --test-reporter=spec mcp-server/src/healthcheck.test.ts
# 9/9 pass, including standalone subprocess exit 1 in ~3112 ms (watchdog 8s, not fired)
pnpm exec eslint mcp-server/src/healthcheck.ts mcp-server/src/healthcheck.test.ts  # PASS
pnpm exec prettier --check mcp-server/src/healthcheck.ts mcp-server/src/healthcheck.test.ts  # PASS
pnpm run build:server-sdk && pnpm --filter @seeker-vault/mcp-server run typecheck  # PASS
```

Out of scope and untouched: SEE-140, SEE-141, pairing, SDK, gateway, Android, storage, deployments.

Docker image rebuild, live App Platform, and physical-device checks: **NOT RUN** (not required; this is the Node healthcheck command).
