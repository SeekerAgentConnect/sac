# SEE-129 — architecture baseline and exact migration map

Ticket: https://linear.app/seekeragentwallet/issue/SEE-129/18-establish-the-architecture-baseline-and-exact-migration-map

Parent: https://linear.app/seekeragentwallet/issue/SEE-128/refactor-sac-into-a-typescript-server-sdk-self-hosted-mcp-public-feeds

Source: both Linear issues were read in full through the runner-provided Linear MCP fallback after the configured `superset mcp` Linear connection reported that it was not connected.

Starting branch: `superset/feat/see-128`

Starting commit: `a4feaa1551d974d4f23e6e5ea2a6301a79078035`

## Plan

- [x] 1. Record the starting commit/branch and applicable repository instructions; inventory modules, runtime entry points, dependencies, generated code, CI commands, and deployment configurations.
- [x] 2. Trace direct MCP → phone → direct result, CopyTrading → gateway → subscribers, and Prediction → gateway → subscribers, including sources of truth and authentication boundaries.
- [x] 3. Trace all gateway-private hosted-invitation paths and classify every affected symbol as removable, shared-and-retained, or migration-only.
- [x] 4. Map `sidecar/` into Server SDK versus MCP application responsibilities, including lifecycle, pairing, verification, updates, push, adapters, authentication, configuration, providers, and business behavior.
- [x] 5. Map `publisher/` into independent `demo-copytrading/` and `demo-prediction/` modules with the smallest non-service shared support arrangement.
- [x] 6. Map `broadcast/` to `feed-gateway/` and old `gateway/` assets to direct-server deployment/ingress; enumerate import, build, image, script, workflow, and documentation changes and retained identities.
- [x] 7. Inventory sanitized persistent data/deployment identities, schema versions, ownership/modes, credentials, pairings, app records, subscriptions, and history without touching live data.
- [x] 8. Record current device-count, OAuth, operations, network/environment, and background/push behavior as preservation requirements.
- [x] 9. Run and record relevant baseline checks with exact versions and PASS/FAIL/NOT RUN, separating pre-existing failures and assigning focused checks to later children; do not run/install on a physical Android device.
- [x] 10. Commit the concise migration/baseline document, completion evidence, per-child ownership matrix, and rollback/data-retention expectations.
- [x] Verify `pnpm run build` plus the documented relevant checks, review the diff, and confirm there are no runtime/deployment changes.
- [x] Push the baseline commit and open one PR into `master`; attempt to inspect CI.
- [x] Update SEE-129 to In Review and post a Linear evidence comment.
- [x] Re-read SEE-128, SEE-129, and all comments before handoff.
- [ ] POST the required finished/blocked/failed webhook and stop before SEE-130.

## Acceptance criteria (verbatim)

- [x] A reviewer can identify the current implementation and exact destination of every affected runtime/library/deployment concern.
- [x] Removable gateway-private behavior is distinguished from direct pairing/results, publisher credentials, public feed topics/tickets and shared request types.
- [x] The migration map covers existing direct/feed data and explicit retirement of obsolete gateway-private connections.
- [x] A concrete package/build strategy lets each demo build and run without building or starting the other.
- [x] SQLite remains a local durable file; Redis/Centrifugo addressability and external-origin settings are identified at their actual owning processes.
- [x] Required regression commands and known failures are recorded without claiming unrun checks passed.

## Review

- Added `docs/development/see-128-migration-map.md` as the single current-to-target source for the
  eight-child refactor and linked it from `CODEBASE.md` and the 2026-09-20 changelog.
- Confirmed the SEE-128 two-mode target does not contradict the current tree; the current third mode
  is isolated enough to retire through the mapped protocol, server, Android, data, and deployment
  boundaries.
- No production/runtime/proto/deployment file changed, no package was published, and no live data
  was read, written, moved, or reset.
- PASS: `pnpm run check`, `check:generated`, `check:broadcast`, `check:publisher`,
  `check:loadtest`, `check:android`, `test:hello`, `test:integration`, and `build`.
- NOT RUN: physical Seeker (explicitly prohibited), live Solana/provider/FCM/full broker-load
  checks; the integration harness also reported its broker stream leg NOT RUN because
  `SEEKERVAULT_CENTRIFUGO` was unset.
- Linear In Review/comment and webhook evidence are completed only after the final evidence commit;
  their checklist items intentionally remain open until then.
- The final ticket re-read caught and corrected the child-ownership assignments before handoff:
  SEE-130 retires gateway-private end to end, SEE-133 isolates the feed gateway, SEE-134 extracts
  both demos, SEE-135 owns deployment separation, and SEE-136 owns final docs/verification.
- PR #38 is open into `master`. GitHub accepted the PR, but the available token returned HTTP 403
  for both check rollups and Actions runs; local equivalents above are the available CI evidence.
- SEE-129 is In Review with evidence comment `20e3e571-0a32-4607-960a-340c7ff1853c` linking PR #38,
  commits, evidence paths, API/schema scope, migration/rollback notes, PASS/NOT RUN results, the CI
  visibility limitation, and the untouched SEE-130 handoff. SEE-128 remains In Progress.
