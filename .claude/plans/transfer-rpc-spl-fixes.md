# Stage 4 review fixes: RPC binding, SPL authority, freshness, CI

Four reported problems plus the three open review threads on PR #4.

## 1. Confirmation must verify the RPC cluster before settling (sidecar)

- [x] `ConfirmationTracker` verifies the configured endpoint's genesis hash against the request's
      bound network before it reads a signature status, a transaction, or a block height.
- [x] A mismatched or unknown cluster records a confirmation that explains it and moves nothing.
- [x] A block height from another cluster is never read as evidence of expiry.
- [x] Regression test: a submitted transfer, restart, `SOLANA_RPC_URL` on the wrong cluster, a
      block height past `lastValidBlockHeight` — the request stays SUBMITTED.

## 2. Android SPL inspection must not treat ATA derivation as authority (android + sidecar)

- [x] The sidecar always emits `CreateIdempotent` for a token transfer's destination, so the
      associated-token-account program checks the account's owner and mint on chain at execution.
- [x] The phone treats the destination as the recipient's only when the transaction carries that
      instruction; derivation alone is no longer proof, and such a preparation is not approvable.
- [x] Regression test: an ATA-addressed destination whose current authority is someone else.
- [x] `docs/security.md` says exactly what is verified and by whom.

## 3. Recheck freshness immediately before invoking MWA (android)

- [x] The wallet lock is taken before the approval is committed and held until the wallet answers.
- [x] Freshness is checked under that lock, before the commit and again immediately before MWA.
- [x] A stale preparation fetches a fresh one and needs a new review; the old approval is dropped.
- [x] Test: time passes the blockhash window while the lock is held; MWA is never called.

## 4. Run the Stage 4 transfer acceptance suite in CI

- [x] `pnpm test:transfer` in the node job; `SEEKER_VAULT_NETWORK_CHECKS` stays unset.

## Review threads on PR #4

- [x] P1 `ConnectionRepository`: an ambiguous transport failure keeps the approval and reconciles
      it, instead of deleting it and leaving the sidecar in PROCESSING for good.
- [x] P2 `mcp-tools.ts`: an idempotency replay is answered before the mint is read.
- [x] P2 `config.ts`: a chain-backed operation has a total budget, and the phone's deadline for
      `PrepareRequest` and `CheckStatus` is above it.

## Verification

- [x] `pnpm check` — PASS (396 sidecar tests, 29 test-agent tests)
- [x] `pnpm test:hello`, `pnpm test:queue`, `pnpm test:transfer`, `pnpm build` — PASS (9/9, 7/7,
      7/7 with the devnet case skipped, both packages build)
- [ ] `pnpm check:android` — NOT RUN: no Android SDK in this environment, and `dl.google.com` is
      blocked by the network policy. ktfmt 0.64 (kotlinlang style, the version Spotless pins) was
      run over every source instead and reports no change. CI's Android job is the real check.
- [ ] `pnpm check:generated` — NOT RUN: `buf generate` needs the remote module, which the network
      policy blocks. No `.proto` changed, so no generated file should have.
- [x] Docs: `docs/security.md`, `docs/guides/transfers.md`, `docs/testing/stage-4.md`,
      `docs/protocol.md`, `CODEBASE.md`, changelog.
- [x] SEE-33 physical-device checks stay NOT RUN.

## Review

**What changed, and why it is shaped this way.**

- *Confirmation and the cluster.* `ConfirmationTracker.settle` now calls `assertNetwork` — the same
  genesis-hash check a preparation makes — before it reads anything, and a mismatch produces a
  finding with no `to`, so nothing moves. The alternative, checking only before the block-height
  read, would still have let another cluster's `getTransaction` answer settle a request.
- *SPL authority.* The phone cannot reach a chain (the stage boundary forbids it), so "the minimum
  trusted chain verification" had to be something the chain itself enforces when the transaction
  runs. The associated-account `CreateIdempotent` instruction is exactly that: it re-derives the
  address, reads the account, and fails the transaction unless its owner and mint are the
  recipient's. Putting it in every token transfer costs nothing when the account exists and turns
  the destination's ownership from an inference into an enforced fact. The phone then requires it
  before naming a recipient at all.
- *Freshness.* The wallet lock is now taken before the approval is committed, which is the only
  order in which the recovery the task asks for is possible: a stale preparation found before the
  commit leaves the request PENDING, so a fresh preparation can be fetched and reviewed. After the
  commit the request is PROCESSING and cannot be re-prepared, so that case reports an execution
  failure instead — nothing signed, nothing sent.
- *The P1 review thread.* An ambiguous transport failure keeps the approval and reconciles it by
  **reading** the request, never by sending the approval again. The sidecar already refuses
  `CheckStatus` for a request with nothing on chain and attaches the request to the refusal, which
  is exactly the answer needed.

**Caveats.**

- The Android checks could not be run here. The code was formatted and parsed by the pinned ktfmt,
  which catches syntax errors but not type errors; CI's Android job is the first real compile.
- SEE-33's physical-device checks are still NOT RUN, and Stage 4 is still not accepted.
