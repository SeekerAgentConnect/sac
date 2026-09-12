# SAW-021 — Approving a transfer through the wallet (SEE-30)

The phone can read a transfer's own bytes (SAW-020) but can approve nothing. This task lets the
owner approve one by hand, and has the wallet sign **and send** it through Mobile Wallet Adapter.
The sidecar still holds no key and never broadcasts.

## The rules this has to keep

- A transfer reaches the wallet only through the owner's explicit approval of the preparation this
  phone inspected and put in front of them.
- The approval names the preparation's version and content hash, and the request's wallet and
  network. The sidecar is the commit point: it accepts the approval before the wallet is opened.
- The bytes the wallet is handed are the bytes the owner approved, taken from the record this phone
  stored, never fetched again.
- One wallet interaction at a time, and one per approval. An interrupted call is UNKNOWN, never a
  retry.
- An invalid preparation never reaches the wallet. That is input validation, not a policy verdict:
  policies are Stage 5, and the screen says "Not evaluated" until then.

## Implementation

- [x] `wallet/WalletAdapter.kt`: `SendResult` and `signAndSendTransaction`.
- [x] `wallet/MwaWalletAdapter.kt`: `signAndSendTransactions` over MWA, with the answer checked
      before it is believed, and every uncertain failure classified as `Unknown`.
- [x] `wallet/WalletRepository.kt`: `signAndSend`, under the same lock every other wallet call
      takes, so wallet interactions are serialized.
- [x] `connections/LocalResult.kt`: `ApprovedTransaction` (version, content hash, bytes) on the
      stored answer — the execution-attempt record — and `SigningOutcome.Sent`.
- [x] `connections/storage/ResultStore.kt`: version 4 stores it.
- [x] `connections/ConnectionGateway.kt`: `Kind.StalePreparation`, told apart from `InvalidState`
      by the error detail.
- [x] `connections/ConnectionRepository.kt`: `approveTransfer`, which stores the record, sends the
      approval, and returns only once the sidecar has accepted it; a transfer approval the sidecar
      did not accept is removed rather than kept. `submissionFor` reports a transfer's result as a
      transaction submission, and an outcome this phone never learned as UNKNOWN.
- [x] `inbox/InboxViewModel.kt`: `approveTransfer`, bound to the preparation the owner reviewed.
- [x] `inbox/RequestDetailsScreen.kt`: the review screen with Approve and Reject, the wallet and
      network, the policy line, and what happened afterwards.
- [x] Strings and test tags.
- [x] `StageBoundaryTest`: signing and sending is lifted for `MwaWalletAdapter` alone.

## Tests

- [x] Double taps, a stale preparation, a switched wallet, rejection in the app and in the wallet,
      and activity recreation.
- [x] The bytes handed to the wallet are the approved bytes, whatever the sidecar says afterwards.
- [x] A lost wallet callback settles as UNKNOWN, and no second wallet call happens.
- [x] An invalid or unverified preparation never reaches the wallet.

## Documentation

- [x] `docs/architecture.md`: approval binding.
- [x] `docs/testing/wallet-lifecycle.md`: the transfer rules, the automated cover, device checks.
- [x] `docs/security.md`, `docs/guides/transfers.md`, `AGENTS.md`, `CODEBASE.md`, `README.md`,
      changelog.

## Review

Done, on branch `superset/feat/see-27-stage-4`. The contract needed no change: `Approval`,
`TransactionSubmission`, `ExecutionFailure`, and `UnknownOutcome` were already in
`request.proto`, and the sidecar's lifecycle already allowed every transition this uses. So
SAW-021 is entirely Android plus documentation.

**What the design turned on.** The hard question was what an approved transfer with no wallet
answer means. It can only be reported honestly if "approved" has exactly one meaning, so the
sidecar was made the commit point: `approveTransfer` stores the record, sends the approval, and
returns `Accepted` only once the sidecar takes it. An approval it refuses — stale, unreachable,
or anything else — is deleted rather than left waiting. That is a deliberate departure from the
outbox model every other answer uses, and it is what makes UNKNOWN correct rather than a guess:
a stored transfer approval can only have come from a wallet that was actually opened.

**What was deliberately not done.**

- `SigningOutcome.Unresolved` was kept rather than split into a message one and a transfer one.
  The meaning differs by what was with the wallet, and the request already says which, so a
  second variant would have been two names for one fact.
- The message path's behaviour was left alone. It invokes the wallet whenever the approval is
  stored and waiting, even if the sidecar hasn't taken it. That is safe for a message, which is
  never broadcast, and changing it is SAW-016's decision to revisit, not this task's.
- A transfer's `Approve` sits inside the review rather than in the answer row with `Reject`, so
  it is under the facts it approves. `Reject` stayed where every other answer's is.

**Checks.** `pnpm check` (362 + 27), `pnpm check:android` (365, 24 new), `pnpm check:generated`,
`pnpm test:hello` (9), `pnpm test:queue` (7), and `pnpm build` all pass. Six deliberate breaks
each failed the right tests and were restored byte for byte; they are listed in the SAW-021
record in `docs/testing/wallet-lifecycle.md`.

**Not run.** The physical Seeker. No transaction was sent to any cluster, by any test, at any
point. The owner's device checks 15 to 24 are NOT RUN.
