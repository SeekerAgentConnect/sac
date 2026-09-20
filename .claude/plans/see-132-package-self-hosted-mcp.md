# SEE-132 — package the self-hosted MCP server for Docker and npm CLI

Ticket: https://linear.app/seekeragentwallet/issue/SEE-132/48-package-the-self-hosted-mcp-server-for-docker-and-npm-cli

Parent: https://linear.app/seekeragentwallet/issue/SEE-128

Ticket source: the configured Linear plugin returned `401 invalid_token`, so the repository-mandated
fallback `superset tasks get SEE-128` and `superset tasks get SEE-132` was used on 2026-09-20. The
SEE-131 implementation and delivery commits are present on this branch; its plan records all required
automated checks PASS and the issue is In Review on PR #38.

## Ordered implementation steps

- [x] 1. Move MCP endpoint/tool code, configuration, optional OAuth wiring, provider/push setup and operator CLI into `mcp-server/`. SDK request/phone machinery stays in `server-sdk/`.
- [x] 2. Route every MCP tool through the SDK public API. The SDK owns lifecycle/validation/direct results; the MCP layer owns agent authentication and tool input/output mapping. Remove deep imports and duplicate request engines.
- [x] 3. Preserve single-owner self-hosting, existing token/OAuth behavior, actual device count and operation support. MCP credentials, direct phone credentials and wallet authorization remain separate.
- [x] 4. Provide one application composition/startup/shutdown path used by source, npm CLI and Docker entry points. Preserve the HTTP MCP endpoint and phone-facing direct API, configurable listeners/public origins, optional integrations and graceful shutdown.
- [x] 5. Add executable npm packaging: an explicit package name distinct from the SDK, executable `bin` mapping and launcher, built JavaScript/runtime assets, accurate Node `engines`, declared dependencies, license, version/help output and a precise files allowlist. Document the actual supported start and pairing-management commands. Users must not need TypeScript compilation, pnpm, a repository clone, workspace links or Docker for the npm route.
- [x] 6. Keep the SDK a real code dependency through its public API. Since it is also unpublished, make the MCP artifact self-contained with respect to that SDK (for example, bundle the built SDK runtime or package it as a bundled dependency using the public entry points). Do not manually copy/reimplement the engine, depend on unavailable registry versions, leave workspace/catalog/file references in a distributed dependency manifest, or require the user to install a sibling SDK tarball first. Record the release dependency strategy.
- [x] 7. Resolve packaged assets from the package location and all writable state from a stable configurable data/config directory. The default must not depend on the user's current directory, global install directory or temporary npm/npx cache. Pairing credentials and the SQLite file must survive package upgrades, a different launch directory and reinstall/cache cleanup. Do not store secrets in packaged assets.
- [x] 8. Provide an independent Dockerfile/build/start/health route using the same application. Its build may use the documented repository context for declared library inputs, but must not compile or start a feed demo/gateway. It carries no mandatory Caddy process, personal certificate path or tunnel configuration.
- [x] 9. Preserve direct pairing/scan/paste/link behavior and existing data. Document old-to-new image, volume and local-data mappings with an explicit backup/upgrade/rollback procedure. Refuse or safely handle concurrent launches against the same store/listener rather than silently starting competing instances. Switching launch formats must use a documented offline migration/shared compatible data mapping, not simultaneous writers.
- [x] 10. Prepare the standalone Docker build/image-distribution configuration using existing repository conventions. Describe tested image references and availability truthfully. npm work stops at preparation and local artifact verification: no npm publication/upload, token provisioning or automatic npm release workflow.
- [x] 11. Build `npm pack --dry-run` and `npm pack` artifacts and inspect their full file/dependency contents. Install the actual MCP tarball into a clean temporary location outside the repository, and run the documented CLI through local executable resolution/`npm exec` or `npx` against that artifact. The check must not fall back to a similarly named registry package or installed development checkout. Demonstrate the future version-pinned registry command as release documentation only.
- [x] 12. Exercise local/global-style installation and a transient execution route with the declared Node runtime. Verify start/help/errors, pairing, health, MCP tool discovery and a complete request/result round trip with a real MCP test client and protocol test phone. Change the working directory and restart/reinstall the artifact; check persistent identity/state, clean shutdown and no duplicate execution.
- [x] 13. Write executable, version-specific Hermes and OpenClaw integration instructions based on their supported configuration surfaces and verify each when available. Distinguish server startup from agent MCP transport: launching the CLI with `npx` does not itself implement MCP stdio. The baseline route is a long-running local server plus the agent's HTTP MCP connection. Show command/args auto-launch only when the actual client/server transport supports and passes it; do not invent config keys, silently add a new stdio transport, or claim compatibility based solely on npm packaging. Record unavailable real-client checks as NOT RUN with a usable documented route and follow-up.
- [x] 14. Explain the second connection leg: the phone still needs a reachable authenticated direct endpoint and appropriate HTTPS/HTTP2 deployment. Agent access to localhost does not make that address reachable from a physical phone. Keep exposure/proxy/VPN examples separate and preserve authentication/certificate checks. Do not restore private gateway routing to solve local reachability.
- [x] 15. Write `mcp-server/README.md` and linked detail satisfying the parent guide checklist, with three explicit startup options: source, Docker, npm CLI. Include Node/dependency requirements, unpublished local-tarball testing versus future registry installation, stable data/config location, credentials, pairing, Hermes/OpenClaw configuration, first request/result, process lifetime/restarts, optional FCM/OAuth, external endpoint setup, logs, upgrades/backups/restore and troubleshooting.
- [x] 16. Update workspace scripts, CI/build/package checks, integrations and canonical path references. Keep only explicitly documented migration aliases.

## Acceptance criteria

- [x] Source, Docker and npm CLI run the same MCP application through the SDK public API and preserve the same request semantics.
- [x] A clean user can install/run the MCP tarball without Docker, the monorepository or a separately published SDK.
- [x] The npm package has a working executable and all runtime dependencies/assets; no unresolved workspace-only dependency or development-checkout fallback.
- [x] Direct pairing, MCP request -> phone review -> returned result, token/OAuth boundaries and baseline operations work.
- [x] Data/credentials survive changes of working directory, cache/reinstall and supported upgrades; duplicate launch has explicit safe behavior.
- [x] Feed gateway, Centrifugo, Redis and both demos may be absent.
- [x] Hermes/OpenClaw guides identify the tested client version, real MCP transport/configuration and phone reachability requirements.
- [x] No guide presents planned registry names/commands as an already published package or conflates `npx` execution with stdio support.
- [x] Neither the SDK nor MCP package was published and no automatic npm publication was enabled.

## Verification and delivery

- [x] Record package identity/bin/runtime requirements, SDK bundling strategy and complete pack file/dependency audit.
- [x] Verify the real MCP tarball in isolated local/global-style and transient installs outside the workspace, including help/version/errors, pairing, health, discovery, request/result, restart/reinstall persistence, changed working directory, clean shutdown and duplicate-launch behavior.
- [x] Build and run the standalone Docker image and record health plus format-parity request/result evidence when Docker is available. Docker was unavailable, so this is recorded as NOT RUN rather than inferred from the native artifact.
- [x] Run SDK/MCP regressions, workspace lint/type/build/generated checks, and record every required check as PASS, FAIL or NOT RUN.
- [x] Record real Hermes, real OpenClaw and physical-device checks as PASS, FAIL or NOT RUN with the precise blocker; do not run/install Android on a real device.
- [x] Update `mcp-server/README.md`, linked detail, migration map, canonical docs, `CODEBASE.md`, changelog and this plan's Review section.
- [x] Re-read SEE-128 and SEE-132 immediately before handoff.
- [x] Commit and push `superset/feat/see-128`, updating PR #38 without merging or completing SEE-128.
- [x] Move SEE-132 to In Review and comment with evidence if Linear access works. The fallback task API moved the ticket and attached PR #38; the Linear MCP plugin still returned `401 invalid_token`, and the fallback exposes no comment operation, so no evidence comment could be posted.
- [ ] POST the required `finished` webhook before stopping; do not start SEE-133.

## Review

Implemented one `mcp-server/` product with a shared CLI/application composition for source, Docker
and npm execution. Its packaged JavaScript imports the SDK only through public entry points, then
vendors that built SDK runtime into the tarball so no sibling package or registry publication is
needed. State now defaults to a stable external application home, and an ownership lock prevents
two servers from driving the same direct store while leaving operator pairing commands available.

The exact tarball passed its dry-pack/real-pack audit, local install, global-style reinstall and
exact-tarball `npm exec` route. The packaged server passed help/version/error, health, pairing,
real MCP SDK discovery, protocol-phone acknowledgement/result, changed-CWD persistence,
reinstall/cache removal, clean shutdown and duplicate-store refusal checks. SDK packaging, Stage 1,
Stage 2, Stage 4, the complete non-Android workspace check and the workspace build also passed.

Docker was NOT RUN because the daemon socket is unavailable; Hermes v0.21.3 and OpenClaw 2026.9.5
were NOT RUN because their executables are absent; the physical-device leg was prohibited and NOT
RUN. Android CI was NOT RUN because no Android SDK path is configured. Two generated-code checks
were stopped by the remote Buf registry's `resource_exhausted` limit; generated outputs have no
diff, and CI remains the authoritative retry. No SDK/MCP artifact was published or uploaded, no
publish credential/workflow was added, and SEE-133/SEE-134 were not started.

Implementation commit `2d4c451` was pushed to `superset/feat/see-128`, updating open PR #38 without
merging. SEE-132 is In Review with that PR attached; SEE-128 remains In Progress and was not
completed. GitHub reported no checks for the branch, while Actions-run inspection was unavailable
to the current token (`403 Resource not accessible by personal access token`).
