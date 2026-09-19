# SEE-130 — remove gateway-private routing

Ticket: https://linear.app/seekeragentwallet/issue/SEE-130/28-remove-gateway-private-routing-and-retire-obsolete-connections

Parent: https://linear.app/seekeragentwallet/issue/SEE-128/refactor-sac-into-a-typescript-server-sdk-self-hosted-mcp-public-feeds

Baseline: `docs/development/see-128-migration-map.md` from SEE-129, commit `6320548` on `superset/feat/see-128`. SEE-129 is In Review with its required local regression commands recorded PASS and no blocking contradiction.

## Ordered implementation steps

- [x] 1. Establish targeted regression fixtures for existing direct pairings/results, public feeds/subscriptions and stored gateway-private connections/history. Record existing schema/protocol identifiers before modifying them.
- [x] 2. Define and implement the app's retirement path for stored gateway-private connections. Such connections must stop syncing, submitting results or executing actions; show a clear unsupported/retired explanation and a fresh direct-pairing route when a compatible direct endpoint exists. Do not invent a server URL or silently reuse/convert credentials.
- [x] 3. Preserve historical local outcomes and unaffected direct/feed records. Keep only the minimal legacy decoding/migration data required to recognize retired records. Remove obsolete private device credentials and pending work through an explicit, restart-safe migration.
- [x] 4. Remove the gateway-private invitation lifecycle, hosted invitation/QR routes, resolution/redemption, private device credential/binding APIs and private request/result routing. Remove only the obsolete listener/routes; keep public read and authenticated publisher boundaries.
- [x] 5. Retire gateway-private storage through versioned migrations with a documented backup/rollback plan. Preserve publisher grants, feed documents, revisions, public outbox and stream configuration. If cleanup must retain old data temporarily, isolate it as inactive migration/archive data with no callable execution path.
- [x] 6. Remove gateway-private SDK calls, Android repositories/onboarding/deep-link/notification routes, configuration and command examples. Cancel obsolete scheduled work and prevent legacy notification taps from reopening an executable private-gateway path.
- [x] 7. Retire protocol fields/enum values/services precisely. Reserve removed protobuf numbers/names where appropriate, regenerate committed bindings, and keep shared request/result contracts used by direct mode. Do not delete generic `RETURN_TO_ORIGIN` semantics needed by direct requests merely because gateway-private used them.
- [x] 8. Preserve working direct pairing, device authentication, preparation, signature/result verification and direct notifications. Preserve feed references, publisher credentials, manifests, snapshots, stream tickets, topic push and client compatibility checks.
- [x] 9. Check direct onboarding from a CLI, bot/link and QR. Retain existing supported methods and close a verified direct onboarding gap on the direct path only; do not restore an invitation relay in the feed gateway.
- [x] 10. Update affected build/codegen/CI/script paths and active docs in this change. Replace obsolete behavior tests with retirement, isolation and retained-path tests; do not remove coverage simply to make CI green.

## Acceptance criteria

- [x] Only `direct` and `gateway_feed` are active runtime modes in server capabilities, Android and supported integration docs.
- [x] Removed gateway-private endpoints cannot create invitations/bindings, route private requests or accept results.
- [x] Stale app records and links cannot trigger execution, and explain retirement without silently converting identity.
- [x] Direct pairing, wallet binding, result return and optional FCM remain operational.
- [x] Feed publications, revisions, snapshots, streaming and optional topic push remain operational and keep subscriber decisions local.
- [x] Restarting migrations does not lose unrelated data or resurrect retired work.
- [x] Active code/docs/configuration have no obsolete gateway-private path; explicitly labelled protocol reservations, migration readers, historical records and negative tests are allowed.

## Verification matrix

- [x] Focused gateway API/storage migration tests cover the schema-v2 private-row cleanup, restart safety, public-row preservation, and every removed route.
- [x] Focused Android migration tests cover old private records and notifications, credential/work cleanup, restart safety, preserved history, and unchanged direct/feed data.
- [x] Direct onboarding is covered through the supported CLI, typed/deep-link and QR routes; no invitation relay is restored.
- [x] A direct request round trip passes.
- [x] Public publication delivery to two independent clients passes.
- [x] `pnpm run check:generated` passes with regenerated committed bindings.
- [x] SEE-129 regression commands pass: `pnpm run check`, `pnpm run check:broadcast`, `pnpm run check:publisher`, `pnpm run check:loadtest`, `pnpm run check:android`, `pnpm run test:hello`, `pnpm run test:integration`, and `pnpm run build`.
- [x] Physical-device installation/run is recorded **NOT RUN** because the user explicitly prohibited it.
- [x] Any unavailable live provider, FCM, or broker checks are recorded **NOT RUN** with the concrete reason.

## Documentation and delivery

- [x] Update active architecture/security/protocol/onboarding/gateway/deployment docs and `docs/development/see-128-migration-map.md` with removed-versus-retained APIs, persisted-data effects, backup/rollback, and the direct onboarding path.
- [x] Append the shipped change to `docs/changelog/2026-09-20.md`.
- [x] Update `CODEBASE.md` for removed files, protocol/API changes, storage migrations, and active two-mode architecture.
- [x] Add a review section with commit/PR, changed modules/configuration, API/schema effects, migration/rollback notes, exact PASS/FAIL/NOT RUN evidence, known limitations, and handoff to SEE-131 without starting it.
- [x] Re-read SEE-128 and SEE-130 plus comments before handoff.
- [ ] Commit and push `superset/feat/see-128`; do not merge PR #38 or complete SEE-128.
- [ ] Comment evidence on SEE-130 and move it to In Review.
- [ ] POST the required `finished` webhook before stopping.

## Review

- Delivery target: PR #38 on `superset/feat/see-128`; implementation commit is pending the final delivery commit.
- Changed boundaries: protobuf/generated clients, feed gateway routing/storage/listeners, publisher SDK/examples, Android persistence/repositories/onboarding/deep links/notifications, deployment configuration, and active documentation.
- API/schema effect: gateway-private invitation, binding, request, and result procedures are removed; retired protobuf names/numbers are reserved; feed gateway schema v3 removes only private tables/records while retaining all public tables and bytes.
- Android migration: version-5 storage rewrites legacy private connections to inert retirement records, removes their credential and pending executable work, preserves local activity/history, and is restart-safe. Direct and feed records remain unchanged.
- Backup/rollback: back up the feed SQLite file and Android app data before upgrade. Code rollback alone cannot recreate removed private tables, credentials, or pending work; restore the matching backup if rollback is required.
- Verification: every command and focused case is recorded in `docs/testing/see-130.md`. All automated gates passed. Physical-device installation, live providers/FCM, and the broker-backed stream case were NOT RUN for the documented reasons.
- Known limitations: retired records intentionally cannot be reactivated or converted; operators must pair directly with a separately supplied compatible endpoint. No live broker/provider or physical-device claim is made.
- Handoff: SEE-131 may begin only after SEE-130 review; this change does not extract or move the SDK/MCP modules.
