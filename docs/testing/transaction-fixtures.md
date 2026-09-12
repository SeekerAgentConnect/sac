# Shared transfer fixtures

The transactions in [`fixtures/transactions/cases.json`](../../fixtures/transactions/cases.json) are
built by the sidecar and decoded by the phone. They are what ties the two halves of a transfer
together: the sidecar's builder (`sidecar/src/solana/transfer.ts`) and the phone's parser
(`android/.../transactions/`) never share a line of code, so the only thing that can show they agree
is a transaction one of them made and the other read.

## What a case is

Each case carries the request the owner would have been shown, the transaction as bytes, the
sidecar's content hash, and what the phone must conclude about it: a verdict, the findings it must
report, and the facts it must read out of the bytes.

```json
{
  "name": "changed_recipient",
  "description": "A transaction that pays somebody else, presented against the original request.",
  "request": { "wallet": "…", "network": "devnet", "recipient": "…", "amount": "2500000000" },
  "transaction": "<base64>",
  "contentHash": "<base64>",
  "version": 1,
  "verdict": "invalid",
  "findings": ["RecipientMismatch"],
  "facts": { "recipient": "…", "destinationAccount": null, "amount": "2500000000", "mint": null, "decimals": 9, "ensuresRecipientAccount": false }
}
```

- **`request` is the stored action, which never changes.** A tampered case is one where the
  transaction was built for a *different* action and is then presented against this request. That is
  what a dishonest or compromised sidecar would look like.
- **`facts` are what the phone must read out of the bytes,** not what the request says. In
  `changed_recipient` the two differ, which is the point: `facts.recipient` is who the transaction
  really pays.
- **`recipient` is null when the bytes establish no wallet** — for a token, when the destination
  account doesn't derive from the recipient the request names, and also when it does derive but
  nothing in the transaction has the chain confirm the account is still theirs.
- **`ensuresRecipientAccount` is the transaction's own proof of the destination,** true only when it
  carries the associated-account instruction for the recipient *and the mint the request names*. A
  transaction for another mint carries that instruction for a different account, which vouches for
  somebody else, so the fact is false there however honest the instruction looks.
- **Amounts are strings,** so a value above 2^53 can't lose its last digits on either side.
- **`findings` are sorted by name,** so the phone can compare its own list directly.

## What they cover

| | |
| --- | --- |
| **Sound** | SOL; a token with and without an existing recipient account; the u64 maximum; a mint with no decimals; a compute-budget priority fee; a legacy message |
| **Tampered** | A changed recipient, amount, or mint; an extra transfer; an extra signer; the wrong fee payer |
| **Not covered** | An SPL Token `Approve` riding along with the transfer, and an instruction from a program the phone doesn't read |
| **Unreadable** | Truncated bytes, bytes left over at the end, and a content hash that isn't the bytes' own |

The tampered cases are built with the same library as the sound ones, on purpose. A malformed
transaction would only prove that the phone rejects nonsense; what matters is that it rejects a
perfectly valid transaction that simply isn't the one the owner was asked to approve.

## Both sides

| Runtime | Test | What it checks |
| --- | --- | --- |
| Node | `sidecar/src/solana/fixtures.test.ts` | The committed file is the one the builder produces now, every verdict appears, and the findings are sorted |
| Android | `TransactionFixturesTest` | Each case decodes to the recorded verdict, findings, addresses, and base units; nothing unread reads as verified; only a fully read and matching transaction is approvable |

The Android test reads the file straight from `fixtures/`, which `android/app/build.gradle.kts` adds
to the unit tests' resources. Nothing is copied, so the two sides cannot drift apart quietly.

## Changing or adding a case

1. Edit `transactionFixtures()` in `sidecar/src/testing/transaction-fixtures.ts`. A case built by `built(…)` goes
   through the real builder; one assembled by `crafted(…)` is for shapes the sidecar would never
   produce, such as a second signer.
2. Run `node sidecar/src/testing/transaction-fixtures.ts` to rewrite the file.
3. Run `pnpm check` and `pnpm check:android`. Both sides read the same file, so a case whose
   expectation is wrong fails in one of them.

Never edit `fixtures/transactions/cases.json` by hand: `sidecar/src/solana/fixtures.test.ts` rebuilds
it and fails if what is committed isn't what the builder produces.

## Verification record: SAW-020

Run on 2026-09-12 on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, and the other
versions in [`docs/development/toolchain.md`](../development/toolchain.md). No network and no
cluster was reached by any check.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 362/362 sidecar tests (3 new, for the shared fixtures) and 27/27 test-agent tests |
| `pnpm check:android` | PASS: Spotless, 341/341 unit tests (47 new: the shared fixtures, the decoder's refusals, the derivation, the wallet and network checks, the review screen, and the view model's preparation), lint with no issues, and the debug and instrumentation APKs |
| `pnpm check:generated` | PASS |
| `pnpm test:hello`, `pnpm test:queue` | PASS: 9/9 and 7/7, unchanged |
| `pnpm build` | PASS |
| Deliberate breaks | Each break failed the matching tests, and each file was restored byte for byte afterwards:<ul><li>A decoder that tolerates bytes left over at the end failed both `TransactionDecoderTest` and the shared fixtures.</li><li>Reading the recipient from the request instead of from the instruction failed `changed_recipient` — the case that exists for exactly that mistake.</li><li>Treating an unread instruction as approvable failed the coverage test.</li><li>Downgrading a value-moving program's unread instruction to a coverage gap let the delegate case through, and failed.</li><li>Skipping the curve check in the derivation failed `PdaTest` and every token case.</li><li>Editing the committed fixture file by hand failed `sidecar/src/solana/fixtures.test.ts`.</li></ul> |
| Physical device | **PASS**, indirectly, 2026-09-12: the devnet transfer recorded in [`stage-4.md`](stage-4.md#verification-record-saw-024) was decoded and shown on the Seeker before the owner approved it. SAW-020 itself adds no wallet interaction. |
