# SEE-135 — Separate portable deployments from ingress and host-specific configuration

Ticket: https://linear.app/seekeragentwallet/issue/SEE-135/78-separate-portable-deployments-from-ingress-and-host-specific

Parent: https://linear.app/seekeragentwallet/issue/SEE-128/refactor-sac-into-a-typescript-server-sdk-self-hosted-mcp-public-feeds

Ticket source: the configured Linear plugin was enabled but unavailable (`superset mcp tools
--plugin linear` returned `401 invalid_token`). The repository-approved fallback
`superset tasks get SEE-128 --json` and `superset tasks get SEE-135 --json` returned both current
Linear-backed issue descriptions in full. The live Linear page reached a login wall, so comments
could not be inspected there. SEE-134 is already in review at branch commit `b0c39b5` with its
committed evidence. The owner explicitly authorized continuing without waiting for confirmation or
GitHub Actions.

## Ordered implementation steps

1. [x] Define a small canonical `deploy/` layout for portable local/reference orchestration,
   optional ingress and isolated operator-specific examples. Document the exact files/presets;
   remove overlapping obsolete launch instructions instead of adding another competing stack.
2. [x] Provide a base feed setup using gateway, Centrifugo, Redis and a gateway SQLite volume.
   Provide independent ways to launch MCP, CopyTrading and Prediction against their configured
   dependencies. An optional full-demo composition is convenience only.
3. [x] Replace mandatory proxy/application network-namespace sharing, including
   `network_mode: service:...`, with ordinary configurable container networking and separate
   service addresses. A proxy restart must not require an application restart and vice versa.
4. [x] Configure listener bind addresses explicitly for internal container use. Preserve
   authentication and route segregation when moving away from loopback-only shared namespaces. Do
   not accidentally publish control/admin/credential-management endpoints or Redis to the public
   internet.
5. [x] Separate internal connection origins from externally advertised URLs. Configure the
   gateway-to-Centrifugo API, public feed/stream origin, direct phone origin and publication
   endpoints at their proper owners. Configure Centrifugo's Redis URL/authentication and supported
   TLS settings; no hardcoded local-only Redis requirement.
6. [x] Keep SQLite local on a persistent volume with configurable path, correct ownership and safe
   backup/restore instructions. Do not add a network filesystem, database service, alternative DB
   implementation or multi-writer SQLite scaling claim.
7. [x] Move current `gateway/` reverse-proxy/TLS/OAuth assets into the appropriate direct-server
   deployment/ingress examples. Establish `feed-gateway/` as the unambiguous shared gateway name.
   Remove legacy active entry points only after documented replacements exist.
8. [x] Supply an optional public HTTPS/HTTP2 ingress example, such as the existing Caddy
   configuration after cleanup. Keep HTTP/2 streaming, route/authentication policy,
   upgrade/reconnect behavior and external identity binding correct. Plaintext/h2c belongs only on
   the documented trusted internal or local path; production Android certificate validation is not
   weakened.
9. [x] Keep Tailscale/Funnel/host-specific certificates, port choices and workarounds in a labelled
   optional operator example. They must not affect module defaults, image startup or portable local
   setup.
10. [x] Apply explicit project/service/volume migration mappings before renaming Compose
    identities. Reuse existing data safely or provide a documented copy/backup procedure with
    rollback. Never answer orphan warnings by deleting unknown services/volumes or reset data to
    make the new stack start.
11. [x] Update all four server guides with exact standalone and optional ingress commands. Document
    dependency ownership, exposed ports, data/secret mounts, health/readiness, startup order,
    external-service requirements and how to update one component without restarting unrelated
    components.
12. [ ] **NOT RUN — Docker daemon permission denied.** Run both the colocated reference setup and a Redis connection test using a different
    reachable endpoint/network namespace. Demonstrate that relocation changes configuration only.
    Exercise actual gRPC/feed streaming through the optional HTTPS ingress, not just a unary HTTP
    response.

## Acceptance criteria

- [x] A clean portable deployment requires no personal domain, tunnel, host-network mode or bundled
  proxy.
- [x] Optional external exposure supports the required HTTPS and HTTP/2 without coupling app and
  proxy lifecycle.
- [x] Feed base runs without MCP or demos; MCP runs without feed infrastructure; either demo can
  run independently.
- [ ] **Runtime NOT RUN; resolved configuration PASS.** Colocated SQLite/Redis reference setup works; Redis may be elsewhere through supported network
  settings.
- [ ] **Runtime NOT RUN; lifecycle and data mapping documented.** A component can be restarted/upgraded independently with documented effects and no unintended
  loss of pairings/publications.
- [x] Routes/ports retain the intended authentication and public/private exposure boundaries.
- [x] Deployment and migration commands use the new canonical paths and preserve real
  volume/database identities.

## Planned verification and evidence

- [x] Add focused static/resolved-Compose checks for every supported preset and all forbidden
  namespace-sharing/public-exposure/data-identity regressions.
- [x] Resolve every Compose preset with sanitized configuration and record exact PASS/FAIL/NOT RUN.
- [x] Run independent component/build checks and the repository build; do not run the broad test
  command unless explicitly required by the ticket's verification.
- [x] Docker was unavailable; record the daemon blocker instead of claiming runtime evidence. If Docker is available, run the feed base, each independent service, independent restarts,
  persistence/backup/restore, external Redis namespace, and real TLS gRPC stream checks.
- [x] Mark Docker-daemon and physical-device checks NOT RUN with the concrete blocker when absent;
  do not install or run Android on a physical device.
- [x] Update `CODEBASE.md`, the relevant server guides, a SEE-135 verification record, and the
  current changelog without taking SEE-136's final documentation reconciliation scope.
- [x] Re-read SEE-135 through the Superset fallback before handoff, append a PASS/FAIL/NOT RUN review
  section, self-review the diff, and prepare the commit/status/webhook handoff.

## Review

- Canonical portable projects now have independent service sets, ordinary named networks, explicit
  host-loopback publication, and explicit physical volume names. Optional public ingress and
  Tailscale/Funnel examples have their own projects/lifecycles.
- The feed's proxyless default is honestly snapshot-only. Enabling the ingress also enables the
  gateway's internal Centrifugo client keys; Redis can move by URL/auth/TLS/prefix configuration.
- Trusted proxy CIDRs preserve per-reader rate limiting without accepting spoofed forwarded
  addresses from arbitrary peers.
- Legacy active Compose/Caddy entry points were removed only after the canonical replacements and
  exact standalone/combined data-lineage mapping existed.
- PASS: deployment resolution/boundary gate (8 presets), feed gateway checks, both demo checks,
  loadtest checks, direct guide test (5/5), formatting, lint, build, and `git diff --check`.
- NOT RUN: all container runtime/persistence/restart checks, a live external Redis connection,
  Caddy runtime validation, actual TLS gRPC feed streaming, and physical-device checks. The Docker
  daemon socket denied permission; no local Caddy executable was present; the ticket prohibits a
  physical Android run. See `docs/testing/see-135.md`.
