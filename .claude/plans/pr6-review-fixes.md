# PR #6 review fixes (two Codex P2 comments)

Both comments are valid against the project's own stated rules, so both are fixed here.

## 1. The wallet is opened only after the sidecar accepted the approval

`InboxViewModel.approve` gated the wallet call on `stored.delivery == Waiting` alone. A sidecar that
couldn't be reached leaves the approval `Waiting` with `approved == false`, so the message path
opened the wallet for an approval nobody had taken — the request may have been cancelled or expired
there. `AGENTS.md:27` and `docs/testing/wallet-lifecycle.md` both state the rule for *both* paths:
the wallet is reached only after the sidecar has accepted the approval.

- [x] `InboxViewModel.approve`: require `stored.approved` before the wallet is opened, and show the
      owner `SigningProblem.NotSentYet` when the approval is still here.
- [x] **Not** done, deliberately: deleting the approval the way `approveTransfer` does. Tried first,
      and it broke three tests whose names state the opposite intent for messages — the stored-first
      outbox re-sends a message approval, and an approval with no wallet answer settles as
      unresolved, which tells the agent the request failed. A message's bytes never go stale, so
      there is nothing to re-review and no reason to drop the answer the sidecar is owed. The prose
      rule was about opening the wallet; it is now enforced without changing the outbox.
- [x] Tests: `opensNoWalletForAnApprovalTheServerHasNotTaken`, and
      `anApprovalTheServerNeverTookEndsAsAFailureWithNoWalletEverOpened` for what becomes of it.

## 2. The wallet's signature is verified before it is believed

`MwaWalletAdapter.signed` accepted any 64 bytes. The sidecar verifies Ed25519 and refuses anything
else with `INVALID_PARAMETERS`, and the first local signing outcome is immutable — so a wallet that
answered with garbage left the request PROCESSING for ever, resending the same invalid signature.
This is the same stuck-request class as the 1024-byte detail clamp in this branch's first commit.

- [x] `wallet/Ed25519.kt`: RFC 8032 verification in pure Kotlin (the platform has none below API
      33, and the app supports 31). Cofactorless equation, canonical `S < L`, canonical points.
- [x] `MwaWalletAdapter.signed`: verify over the bytes the wallet says it signed, with the selected
      wallet's key, before returning `Signed`; otherwise a terminal `Failed`, so the request settles
      as FAILED instead of never settling at all.
- [x] Tests: `Ed25519Test` against the JDK's own Ed25519 as the oracle (random keys and messages),
      tampered signature/message/key, non-canonical scalars and points; `MwaWalletAdapterTest` for
      the adapter's own answer-checking. The companion object became `internal` so those checks can
      be tested directly; nothing else about it changed.

## Verification

- [x] `pnpm check`, `pnpm check:android`, `pnpm check:generated`, `pnpm build`
- [x] CI green on PR #6

## Review

- 448 JVM unit tests pass, 0 failures; `pnpm check`, `check:generated`, and `check:android` are green.
- The first attempt widened `uncommittedTransfer` to every approval. The suite refused it, and it was
  right to: for a message the approval is the answer the sidecar is owed, and dropping it would have
  removed a designed capability. The narrow gate fixes what was reported and keeps the rest.
- Not covered by any automated check: a real wallet's answer on a real device. `Ed25519Test` proves
  the verifier against the JDK's, and `MwaWalletAdapterTest` proves the wiring, but no wallet has
  signed anything for this repository — the Stage 3 and Stage 4 device checks are still NOT RUN.
- See the changelog entry for 2026-09-12, `.claude/tasks/decisions.md`, and `.claude/tasks/lessons.md`.
