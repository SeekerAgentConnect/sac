# SEE-146 — Codex review fixes (PR #54)

Base stays `feat/see-145`. Branch already sits on the latest 145 tip (`7855fb2`), so the
`dirty` mergeable state GitHub reports is a stale computation; a push forces a recompute.

## Items

- [x] **P1 — confirmation tracking is never wired in** (`skr-staking-server/src/server.ts`)
      `openDirectServer` gets only `stakingProvider`, so the SDK builds no `ConfirmationTracker`.
      `direct.requests.confirmations` stays undefined, `settled()` returns a SUBMITTED request
      unchanged forever, and the phone's `CheckStatus` answers `CHAIN_UNAVAILABLE`.
      Fix: give it a `ConfirmationProvider` over the same configured RPC.
      - [x] `ChainReader`/`SolanaRpc` gain `signatureStatus` and `confirmedTransaction`
      - [x] byte-exact `getTransaction` (base64 JSON-RPC) so the on-chain copy can be compared
      - [x] `isApprovedTransaction` for this package (message-region comparison)
- [x] **P2 — status trusts a non-mainnet binding** (`src/requests/tools.ts`)
      `get_staking_status` reads the mainnet-only provider but labels the answer with the
      binding's own network. Fix: `assertNetwork(binding.network)` before the position read,
      and map this package's chain errors to `RequestFailure` so an agent gets a readable code.
- [x] **P3 — allowed-host problems are collected after the throw** (`src/config.ts`)
      `hosts(env, problems)` runs inside the return expression, after the `problems.length`
      check. Fix: parse before the check, return the parsed value.

## Tests (each must fail without its fix)

- [x] `src/config.test.ts` — malformed `SKR_STAKING_ALLOWED_HOSTS` throws `ConfigError`
- [x] `src/requests/tools.test.ts` — devnet binding is refused by `get_staking_status`
- [x] `src/skr/confirmation.test.ts` — approved/on-chain message comparison
- [x] `src/server.test.ts` — a started server exposes `direct.requests.confirmations`

## Then

- [x] lint, typecheck, build, package tests
- [x] docs: `docs/wiki/skr-staking.md`, `docs/changelog/2026-09-24.md`, `CODEBASE.md`
- [x] reply on all three threads, resolve them
- [x] push, update PR body, Linear → In Review, finish webhook

## Review

All three findings fixed in `afd4822`, each pinned by a test that fails on `55932d9`:

| Fix | Test | Fails without the fix |
| --- | --- | --- |
| `ConfirmationProvider` wired in | `src/server.test.ts` | 1 of 3 |
| status asserts the network | `src/requests/tools.test.ts` | 4 of 4 |
| allowed hosts parsed before the throw | `src/config.test.ts` | 3 of 6 |

Verified by running each test file against `git show HEAD:<file>` restored in place.

Checks: `pnpm --recursive run typecheck` clean; `skr-staking-server` 95 tests pass; `pnpm run build`
in the package succeeds; `prettier --check` and `eslint` clean over everything this branch touches;
`pnpm run check:deployments` passes. The repo-wide `check:format` and `check:lint` failures
(feed-gateway Go HTML templates, `mcp-server/src/mcp-endpoint.ts`) are the pre-existing baseline in
files this branch does not modify.

No rebase was needed: `origin/feat/see-145` (`7855fb2`) was already an ancestor of the branch, and
GitHub's own compare API reports 7 ahead / 0 behind. The PR's `mergeable_state: dirty` is stale on
GitHub's side — `git merge-tree` finds no conflict, and the state did not change after the push.

Two things worth carrying forward, not corrections:

- `confirmedTransaction` is the only read in this package that bypasses `@solana/web3.js`
  `Connection`. That is deliberate and commented: the call exists to compare chain bytes against
  approved bytes, and `Connection.getTransaction` returns a parsed message with no base64 option.
- `isApprovedTransaction` is duplicated from `mcp-server/src/solana/confirmation.ts` rather than
  shared. The SDK's `ConfirmationProvider` asks each host for `matchesApprovedTransaction` by
  design — the host owns its own parser — so a per-package copy is the shape the interface expects.
  If a third host appears, that is the moment to lift it into the SDK.
