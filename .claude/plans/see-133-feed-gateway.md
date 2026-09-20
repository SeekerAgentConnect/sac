# SEE-133 — isolate the public feed gateway

Ticket: https://linear.app/seekeragentwallet/issue/SEE-133/58-isolate-the-public-feed-gateway-publication-api-and-sqlite-storage

Parent: https://linear.app/seekeragentwallet/issue/SEE-128/refactor-sac-into-a-typescript-server-sdk-self-hosted-mcp-public-feeds

Ticket source: both issue bodies were read in full through `superset tasks get ... --json` after the configured Linear integration returned `401 invalid_token`. The runner fallback exposes the synced issue body and status but not comments. SEE-132 is In Review and its implementation/evidence commits are the clean branch baseline.

## Scope and ordered work

- [x] Move `broadcast/` to root `feed-gateway/`, updating module/build/generated-code paths, commands, operator tooling, CI and current callers while preserving public-feed wire identity.
- [x] Keep only public-feed publisher grants/authentication, validation/lifecycle, authoritative reads, outbox, stream recovery/tickets and optional topic invalidation responsibilities.
- [x] Put SQL, schema management and transaction execution behind a focused storage contract; keep SQLite as the only implementation and preserve the existing local-file schema/data identity.
- [x] Preserve atomic publication plus outbox, publisher scope, revision/idempotency/conflict/withdrawal and cursor/snapshot invariants; success or fan-out follows durable commit.
- [x] Keep broker/public-stream configuration at the delivery boundary and Redis at Centrifugo; add no gateway-to-Redis dependency.
- [x] Document the real authenticated HTTP/Connect JSON publisher API, advertised versus internal origins, deterministic errors/retries and ordinary-client usage.
- [x] Provide independent gateway build/start/health/Docker/operator/configuration/data/update/backup/restore guidance satisfying the parent server-guide checklist.
- [x] Update demos and tests in place; do not extract demos, restore private routing or perform SEE-135 deployment work.
- [x] Update `CODEBASE.md`, focused architecture/development/integration docs and the changelog for the shipped path/boundary change.

## Acceptance criteria (Linear, verbatim)

- [x] There is one canonical public gateway implementation and runnable module at `feed-gateway/`.
- [x] A plain HTTP client can publish a manifest and create/update/withdraw a public item through documented authenticated API calls.
- [x] A feed publisher is not required to host a phone-facing gRPC server, depend on the Direct SDK or install a dedicated publisher library.
- [x] Authoritative publications survive gateway restart independently of Redis cache loss.
- [x] SQLite operations are behind a clear contract retaining publication/outbox atomicity and all existing revision/scope invariants.
- [x] Duplicate/stale/conflicting writes have deterministic documented behavior; one publisher cannot modify another source.
- [x] Current gateway, Centrifugo and Redis processes can be configured separately; only SQLite requires local file access.
- [x] The README distinguishes current single-host SQLite deployment from future network-database possibilities.

## Verification

- [ ] Run the moved gateway tests/build and repository codegen/path checks. (All pass except the local codegen freshness retry, blocked by Buf registry rate limiting; PR CI must pass it.)
- [x] Run authenticated publication HTTP smoke tests for manifest create/update and item create/update/withdrawal.
- [x] Run cross-publisher isolation and transaction/outbox rollback/replay tests.
- [x] Exercise restart after commit before dispatch, duplicate delivery and snapshot recovery without broker history.
- [x] Check optional push disabled and enabled paths with controlled fixtures.
- [x] Run the repository build; run broader checks proportionate to the changed paths. Do not run or install Android on a real device.
- [ ] Review the final diff against `0cce770`, re-read SEE-128/SEE-133, and record PASS/FAIL/NOT RUN evidence below.
- [ ] Commit and push `superset/feat/see-128`, update PR #38, move SEE-133 to In Review/comment with evidence if the available Linear path supports it, then send the required finished webhook.

## Review

Pending implementation and verification.
