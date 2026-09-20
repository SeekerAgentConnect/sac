# SEE-131 — extract the TypeScript Direct Server SDK

Ticket: https://linear.app/seekeragentwallet/issue/SEE-131/38-extract-the-typescript-direct-server-sdk-and-prepare-an-npm-package

Parent: https://linear.app/seekeragentwallet/issue/SEE-128/refactor-sac-into-a-typescript-server-sdk-self-hosted-mcp-public-feeds

Ticket source: the configured Linear plugin returned `401 invalid_token`, so the repository-mandated
fallback `superset tasks get SEE-128 --json` and `superset tasks get SEE-131 --json` was used on
2026-09-20. SEE-130 is In Review and its plan records all required automated checks PASS; physical
device, live provider/FCM, and broker-backed checks were explicitly NOT RUN.

## Ordered implementation steps

- [x] 1. Write the public API and dependency ownership contract before moving code. Identify shutdown/cancellation/error behavior and distinguish library methods from the phone's existing wire API. Reuse current abstractions; avoid a new general-purpose framework.
- [x] 2. Create the `server-sdk/` package and relocate direct core/storage/protocol-facing code. Preserve schema identity, transactions, idempotency, validation, request revisions and result checks.
- [x] 3. Make storage location, listener/bind configuration, public origin, logger and optional integrations explicit. Importing the package must not open a port/database, read a global `.env` file, register signals or start background loops. Initialization and shutdown are explicit; resources are cleaned up predictably.
- [x] 4. Keep direct phone credentials separate from host MCP credentials. No SDK API can approve or sign on the owner's behalf. Preserve exactly-once wallet-interaction assumptions and the distinction between result delivery and execution.
- [x] 5. Adapt the current MCP host to consume the public SDK while retaining its current entry point until the next child relocates it. No remaining imports from private SDK internals; no duplicate request engine.
- [x] 6. Update workspace configuration, dependency declarations, generated bindings, exports and test paths together. SDK tests and a minimal host must run without an MCP server, feed gateway, Redis, Firebase credential or deployment proxy.
- [x] 7. Prepare npm metadata, package identity following repository ownership conventions, documented exports, TypeScript declarations, supported runtime/version policy, license and README. Declare runtime dependencies explicitly. Include built output and necessary generated/runtime assets in a precise package file allowlist.
- [x] 8. Add a minimal SDK consumer example showing initialization, direct pairing setup, request creation, result observation and clean shutdown. The example must use only the documented package entry points.
- [x] 9. Build and run `npm pack --dry-run` and `npm pack` (or the equivalent pack command supported by the repository toolchain). Inspect the tarball for missing runtime files, source/workspace path leaks, environment files, credentials, test databases and unrelated application/deployment content.
- [x] 10. Install the actual tarball into an isolated temporary project outside the monorepository with no workspace linking. Type-check/import it using the documented supported module format(s), start the phone API and exercise a representative direct lifecycle with a protocol test client.
- [x] 11. Document the later release preparation steps. Keep all registry publication disabled: no `npm publish`, npm upload, token provisioning, public release or workflow that automatically publishes when this refactor is merged.

## Acceptance criteria

- [x] A plain TypeScript backend embeds the SDK and services direct phone connections without MCP or feed infrastructure.
- [x] The MCP host consumes the same public SDK and passes existing direct behavior checks.
- [x] Import alone has no runtime side effects; initialization/shutdown and persistence paths are configurable.
- [x] The packaged tarball contains usable exports/types/runtime assets and installs outside the workspace.
- [x] Consumer code cannot accidentally rely on source-relative or private monorepo imports.
- [x] Direct pairing, request lifecycle, update convergence and result validation retain baseline semantics.
- [x] No npm package or release was published, and no automatic publication trigger was enabled.

## Verification

- [x] SDK unit/contract tests pass.
- [x] Focused durable direct/update/result regressions pass, including process/storage restart and duplicate result delivery without new execution.
- [x] Workspace format, lint, type, generated-code and build checks pass.
- [x] The isolated real-tarball consumer scenario passes with deterministic wallet/provider stand-ins.
- [x] `npm pack --dry-run` and `npm pack` file lists are recorded and inspected.
- [x] Physical-device installation/run is recorded **NOT RUN** because the user explicitly prohibited it.

## Documentation and delivery

- [x] Publish the SDK public API/ownership contract, package README/minimal consumer, version/runtime policy, release-preparation notes, and exact verification record.
- [x] Update `docs/development/see-128-migration-map.md`, relevant direct-server developer/integration docs, `CODEBASE.md`, and `docs/changelog/2026-09-20.md` for the extracted architecture.
- [x] Re-read SEE-128 and SEE-131 before handoff and record each ticket item as PASS, FAIL, or NOT RUN.
- [x] Commit and push `superset/feat/see-128`; update PR #38 without merging or completing SEE-128.
- [x] Move SEE-131 to In Review and comment with implementation and verification evidence if Linear access works. The configured Linear plugin remained unavailable with `401 invalid_token`; the Superset ticket fallback moved the issue and linked PR #38, but exposes no comment operation.
- [x] POST the required `finished` webhook before stopping.

## Review

The direct engine is now a private, packable root workspace package with explicit initialization and
shutdown, unchanged persistence semantics and two documented exports. The existing MCP host keeps
its entry point and product concerns while all production composition goes through those exports;
boundary tests reject private production imports and duplicate stores. The real tarball passed the
69-file audit and outside-workspace consumer lifecycle. Full command outcomes, the one corrected
Android SDK-path precondition and prohibited checks are recorded in `docs/testing/see-131.md`.

Implementation commit `4c54d76` is pushed to PR #38. SEE-131 is In Review with the PR attached;
SEE-128 remains open. The required completion webhook was sent after the final delivery push.

No registry publication, package upload, credential provisioning, automatic release workflow,
physical-device run, MCP package relocation or SEE-132 implementation occurred.
