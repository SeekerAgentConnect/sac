# SEE-146 — Codex review fixes (PR #54)

Base stays `feat/see-145`. Branch already sits on the latest 145 tip (`7855fb2`), so the
`dirty` mergeable state GitHub reports is a stale computation; a push forces a recompute.

## Items

- [ ] **P1 — confirmation tracking is never wired in** (`skr-staking-server/src/server.ts`)
      `openDirectServer` gets only `stakingProvider`, so the SDK builds no `ConfirmationTracker`.
      `direct.requests.confirmations` stays undefined, `settled()` returns a SUBMITTED request
      unchanged forever, and the phone's `CheckStatus` answers `CHAIN_UNAVAILABLE`.
      Fix: give it a `ConfirmationProvider` over the same configured RPC.
      - [ ] `ChainReader`/`SolanaRpc` gain `signatureStatus` and `confirmedTransaction`
      - [ ] byte-exact `getTransaction` (base64 JSON-RPC) so the on-chain copy can be compared
      - [ ] `isApprovedTransaction` for this package (message-region comparison)
- [ ] **P2 — status trusts a non-mainnet binding** (`src/requests/tools.ts`)
      `get_staking_status` reads the mainnet-only provider but labels the answer with the
      binding's own network. Fix: `assertNetwork(binding.network)` before the position read,
      and map this package's chain errors to `RequestFailure` so an agent gets a readable code.
- [ ] **P3 — allowed-host problems are collected after the throw** (`src/config.ts`)
      `hosts(env, problems)` runs inside the return expression, after the `problems.length`
      check. Fix: parse before the check, return the parsed value.

## Tests (each must fail without its fix)

- [ ] `src/config.test.ts` — malformed `SKR_STAKING_ALLOWED_HOSTS` throws `ConfigError`
- [ ] `src/requests/tools.test.ts` — devnet binding is refused by `get_staking_status`
- [ ] `src/skr/confirmation.test.ts` — approved/on-chain message comparison
- [ ] `src/server.test.ts` — a started server exposes `direct.requests.confirmations`

## Then

- [ ] lint, typecheck, build, package tests
- [ ] docs: `docs/wiki/skr-staking.md`, `docs/changelog/2026-09-24.md`, `CODEBASE.md`
- [ ] reply on all three threads, resolve them
- [ ] push, update PR body, Linear → In Review, finish webhook

## Review

(filled in at the end)
