# SAW-020 (SEE-29) — Inspect transfer bytes independently on Android

**Branch:** `superset/feat/see-27-stage-4` (continues SAW-019's `e5c1e5d`)
**Acceptance:** the review screen derives supported transfer details from the transaction, not from
the server's summary.

## The decoding choice

`sidecar/src/solana/transfer.ts` emits exactly one shape: an unsigned v0 `VersionedTransaction`,
no address lookup tables, the owner's wallet as fee payer and only signer, and one or two
instructions from the System, SPL Token, and Associated Token Account programs.

**A correction made during the work.** The first version of this plan argued against pinning
`com.solanamobile:web3-solana` because it would add an Ed25519 implementation, and so key creation,
to the app's classpath. That was wrong, and the stage-boundary test proved it: the Mobile Wallet
Adapter client already pulls `web3-solana` (with `TransactionDecoder`) and BouncyCastle in
transitively. Keeping an SDK off this classpath was never available, so it cannot be a reason.

The reason that survives is about what the parser has to do, not what it costs:

| | Verdict |
| --- | --- |
| `com.solanamobile:web3-solana` (already present via MWA) | `TransactionDecoder` is built to parse a transaction. What this task needs is something that *refuses* one: trailing bytes, a non-canonical compact-u16, an address lookup table, and an instruction index out of range all have to end the review, not be tolerated or skipped. A decoder's contract is "read what you can", and here the whole point is to read all of it or none. |
| `org.sol4k:sol4k` | The same, plus signing and an RPC client that this app must not have. |
| **Decode it here** | **Chosen.** About 150 lines, total and strict: every byte is accounted for or the transaction is refused. It cannot sign and cannot reach a network, and it is small enough to audit in one sitting — which matters, because it is the thing standing between the owner and a transaction they did not agree to. |

Correctness is established by the mechanism the ticket asks for: fixtures built in TypeScript by
the real builder and decoded in Kotlin. So what is pinned is the *format*, by those fixtures.
`StageBoundaryTest.theTransactionTheOwnerReviewsIsReadByThisAppsOwnParser` keeps the decision
honest by failing if a source file starts importing an SDK decoder instead.

## No chain reads are needed, and that is the point

For the shapes this stage supports, nothing has to be fetched to verify them:

- **Associated token accounts are derived, not looked up.** The phone computes the ATA for
  (owner, mint) itself and compares it with the account the instruction names.
- **`TransferChecked` carries the decimals, and the chain enforces them.** A wrong value makes the
  transaction fail on chain, so displaying the amount with them is safe.
- **No ticker is ever shown.** A token is named by its mint address and its base units only, so
  there is no name for a sidecar to lie about.

Anything outside those shapes is reported as UNVERIFIED, never as safe.

## Implementation

- [x] `transactions/Reader.kt`: compact-u16 and a bounds-checked byte reader.
- [x] `transactions/SolanaTransaction.kt`: the v0 and legacy decoder. Trailing bytes, a bad length,
      or a lookup table make it malformed.
- [x] `transactions/Pda.kt`: `isOnCurve`, `findProgramAddress`, `associatedTokenAddress`.
- [x] `transactions/Programs.kt`: the program IDs, and the System, SPL Token, ATA, and
      compute-budget instructions this stage recognizes.
- [x] `transactions/TransferInspection.kt`: cross-check the decoded transaction against the
      immutable `ActionRequest` and the selected wallet; return verified fields plus findings.
- [x] `ConnectionGateway.prepareRequest` and its Connect implementation.
- [x] `ConnectionRepository`: prepare a PENDING transfer and keep its inspection in the inbox.
- [x] `RequestDetailsScreen`: a transfer section showing derived facts, the coverage, and the
      warnings, with the agent's note kept where it already is — apart from them.
- [x] `StageBoundaryTest`: the guard lifted on purpose for decoding, still forbidding signing and
      sending on the phone.

## Tests

- [x] Shared fixtures built by the sidecar and decoded in Kotlin, compared field by field.
- [x] Changed recipient, changed amount, an unexpected signer, a fake ticker, a delegate or
      authority instruction, an extra transfer, and malformed payloads.
- [x] Nothing unparsed is ever presented as a fully verified action.

## Docs

- [x] `docs/security.md`: what inspection guarantees and what it doesn't.
- [x] `docs/testing/transaction-fixtures.md`: the shared fixtures and how to add one.
- [x] `README.md`, `AGENTS.md`, `CODEBASE.md`, the changelog.

## Review

Done as planned, apart from the decoding choice, which changed mid-task and is written up above and
in `docs/security.md`. What is worth keeping:

- **The classpath argument I started with was wrong, and a test caught it.** The first guard I wrote
  asserted that no Solana SDK was on the Android classpath; it failed immediately, because the
  Mobile Wallet Adapter client already brings `web3-solana` and BouncyCastle in transitively. The
  decision to write the parser here survived, but for a different and better reason — a decoder
  reads what it can, and a review needs something that refuses what it cannot fully account for —
  and the guard became a source-level check that no file imports an SDK decoder instead.
- **Facts come from the bytes, and a test caught that too.** The first version read the recipient
  out of the request rather than out of the instruction, which is exactly the mistake this whole
  task exists to prevent. The `changed_recipient` fixture failed on it. `TransferFacts.recipient` is
  now null whenever the bytes don't establish a wallet, rather than borrowing the request's.
- **Unrecognized is not approvable.** The ticket asks for UNVERIFIED as a category, and it is one,
  but `approvable` is true only for `Verified`. An unread instruction is a gap in the review, and a
  review with a gap in it is not a review.
- **A program is not a permission.** An instruction the app can't read from a program that can move
  value — `Approve`, `SetAuthority` — is invalid, not merely uncovered. Only an unknown program's
  unread instruction is a coverage gap.
- **No chain reads, and that is a feature.** Deriving the associated token account, and taking the
  decimals from `TransferChecked` (which the token program enforces), removes the need for a trusted
  RPC on the phone entirely. Showing a mint address and base units, never a name, removes the
  ticker question with it.
- **The fixture generator lives in `sidecar/src/testing/`,** because it writes a file and the
  sidecar's stage boundary allows that only in test-only code and `storage/`. The boundary test
  caught that as well.

Not in this task, by design: the owner still cannot approve a transfer, and no wallet is opened for
one. The screen says so rather than offering a button that would do nothing.

Checks, all on 2026-09-12: `pnpm check` (362 sidecar, 27 test-agent), `pnpm check:android`,
`pnpm check:generated`, `pnpm test:hello` (9), `pnpm test:queue` (7), `pnpm build`, and six
deliberate breaks that each failed the right test. The record is in `docs/security.md` and
`docs/testing/transaction-fixtures.md`.
