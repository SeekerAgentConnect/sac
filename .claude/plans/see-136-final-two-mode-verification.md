# SEE-136 — Reconcile documentation and verify the complete two-mode architecture

Ticket: https://linear.app/seekeragentwallet/issue/SEE-136/88-reconcile-documentation-and-verify-the-complete-two-mode

Parent: https://linear.app/seekeragentwallet/issue/SEE-128/refactor-sac-into-a-typescript-server-sdk-self-hosted-mcp-public-feeds

Ticket source: SEE-128, SEE-136, their complete comment threads, and the full SEE-129 through
SEE-135 issue/evidence chain were read through the native Linear MCP fallback on 2026-09-20 after
the configured `linear` plugin returned `401 invalid_token`. The work continues on the existing
`superset/feat/see-128` branch and PR #38. GitHub Actions is not a completion gate for this run per
Renat's explicit authorization.

## Ordered implementation and verification

- [x] Review the final dependency graph and root layout: TypeScript server-sdk, standalone
      mcp-server, feed-gateway, independent demo-copytrading/demo-prediction, android, proto and
      optional deployment assets. Confirm no extra mandatory sidecar or feed-publisher-client module
      was introduced.
- [x] Update README, docs/architecture.md, SDK/API docs, developer guides, integrations and active
      deployment/test commands consistently. Describe exactly two active modes and distinguish SDK
      library, direct MCP application, public gateway and optional feed demos.
- [x] Preserve the scope of architecture-only PR #36. Review its current documentation overlap and
      coordinate rather than adding runtime code there or silently overwriting concurrent edits.
- [x] Walk every step of each server's own guide from a clean checkout/environment: prerequisites,
      build/image, configuration/secrets, persistent data, optional dependencies, startup,
      readiness, onboarding/publication, verification, updates/backups/restore and troubleshooting.
      Correct commands from actual execution.
- [x] Re-run both npm pack validations. Exercise the SDK tarball in a standalone TypeScript host and
      the MCP tarball in a separate clean CLI environment outside the workspace. Verify exports,
      types, runtime dependencies, CLI/bin/start/help, minimal lifecycle, changed-cwd and
      reinstall/cache persistence. Confirm neither package was published and no automatic publish
      workflow exists.
- [x] Exercise the independent MCP server through source, Docker and npm CLI with a real MCP test
      client and protocol test phone. Cover documented lifecycle/parity, phone reachability,
      pairing, first request, cancellation, approval/result delivery, duplicate retries,
      restart/reconnect, preparation/result verification, uncertainty, and OAuth/token boundaries.
      Walk version-specific Hermes/OpenClaw instructions against the served transport; unavailable
      real-client, physical-device, or Docker-daemon checks are **NOT RUN**, never PASS.
- [x] Start each feed demo independently against the gateway and two test subscribers. Cover
      publication/update/withdrawal, source isolation, independent owner state, no owner-result
      upload, no phone-to-demo communication and no Direct SDK dependency.
- [x] Exercise gateway restart, broker/history loss, duplicate delivery, authoritative SQLite and
      snapshot recovery, atomic publication/outbox handling, no repeated execution, an alternative
      Redis endpoint by configuration only, and optional ingress HTTP/2 streaming.
- [x] Exercise notifications enabled/disabled with controlled credentials or stand-ins. Prove that
      update/FCM paths only trigger authoritative reads and never approve, sign or open a wallet.
- [x] Verify upgrades from representative pre-refactor direct/feed/private-gateway fixtures:
      preserved direct pairings/feed subscriptions/local history, explicit obsolete-connection
      retirement, inert legacy routes/notifications, volume/schema migration, backup/restore and
      rollback instructions.
- [x] Run final affected CI/build/lint/codegen/test commands from the baseline; update stale path
      guards for the final boundaries. Search active code/docs for obsolete private-gateway routes,
      mixed SDK/demo imports and ambiguous deployment instructions, allowing only labelled history,
      migrations, reservations and negative tests.
- [x] Publish a repository verification record for SEE-136 and link it from SEE-128. Record tested
      commit/configurations, full PASS/FAIL/NOT RUN matrix, restrictions and a short remaining live /
      physical-device checklist without presenting stand-ins as live/device evidence.

## Acceptance criteria (ticket wording)

- [ ] The final implementation matches every fixed decision and definition-of-done item in SEE-128.
- [ ] All canonical paths, commands and deployment guides agree with real builds/startup.
- [ ] SDK, MCP, gateway and demos can be consumed/run independently with only their stated
      dependencies.
- [x] Data-preservation, protocol, identity, approval and feed privacy invariants hold after the
      refactor.
- [x] Both SDK and executable MCP artifacts pass isolated local-tarball checks; neither npm package
      was published. MCP package runtime cannot rely on a monorepository link or an unavailable SDK
      registry version.
- [x] Required automated checks pass; pre-existing failures and unavailable live/device checks are
      explicit and do not get silently waived.
- [x] Parent closure is supported by linked evidence from every child. Unresolved required behavior
      or migration failures keep the parent open.

## Completion and handoff

- [x] Add the shipped-change entry and update CODEBASE.md for the verification record/path guards or
      architecture changes.
- [x] Review the complete diff against `6937d0d`; re-read SEE-128 and SEE-136 plus comments; append a
      PASS/FAIL/NOT RUN review section here.
- [x] Run `pnpm run build` as the required final build verification and all ticket-specific checks
      justified by the matrix.
- [ ] Commit and push `superset/feat/see-128`, updating PR #38 without merging it or waiting on CI.
- [ ] Move SEE-136 to In Review and comment with commit/PR/evidence. Link the verification record
      from SEE-128. Mark SEE-128 Done only if every required acceptance item is evidenced; otherwise
      leave it In Progress and name every gap.
- [ ] Before stopping, POST the required `finished` webhook for SEE-136 with PR #38.

## Review record

The final graph has only Direct and Public feed modes. SDK/MCP exact-tarball, native
gateway/demos/two-reader, direct lifecycle, notifications, representative direct/feed/private
upgrades, path guards, Android and repository checks pass. The joined run found and fixed one stale
`sidecar/` test working directory. Docker runtime/parity, live broker/Redis/TLS, live
Hermes/OpenClaw/Firebase/Jupiter and physical-device checks are NOT RUN because the necessary
daemon, services, credentials, clients or device are unavailable. These gaps intentionally leave
the first three acceptance boxes above unchecked and keep SEE-128 In Progress. Full command and
result evidence is in `docs/testing/see-136.md`.
