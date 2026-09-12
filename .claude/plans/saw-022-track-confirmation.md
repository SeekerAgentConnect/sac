# SAW-022 — Track on-chain confirmation, and recover uncertain outcomes (SEE-31)

SAW-021 ends at SUBMITTED: the wallet said it sent the transaction, and this was as far as
anyone knew. SAW-022 asks the chain what became of that signature, and settles the request
honestly — including when it cannot be settled at all.

## The shape of it

The sidecar has no background component (AGENTS.md), so its knowledge advances only when
somebody asks. Two people ask:

- **the agent**, every time it polls `vault_get_request` on a SUBMITTED transfer;
- **the owner**, when they tap *Check status* on the phone (`RequestService.CheckStatus`).

Both run the same check, and both go through the same commit point in the request store.

## What the check decides

| What the chain says | Where the request goes |
| --- | --- |
| The signature is `confirmed` or `finalized`, no error, and the transaction under it is the approved one | CONFIRMED |
| The signature carries a chain error | FAILED, with the chain's own error recorded |
| No status, and the approved version's `last_valid_block_height` has passed (asked again over transaction history) | FAILED: it can never land now |
| No status, and the window is still open | unchanged (SUBMITTED), the attempt recorded |
| `processed` only | unchanged (SUBMITTED): a delayed confirmation is not a result |
| The transaction under that signature is **not** the approved one | unchanged, and disclosed as not matching |
| The endpoint timed out, refused, or answered nonsense | unchanged, the attempt recorded — never proof of anything |

## Items

- [x] `proto`: `Confirmation` + `ConfirmationLevel`, `Outcome.confirmation`, `RequestService.CheckStatus`
- [x] `pnpm generate`, and extend the `transfer_confirmed` fixture
- [x] `solana/rpc.ts`: `signatureStatus` and `transaction` reads, both read-only
- [x] `solana/confirmation.ts`: the message region of a wire transaction, and whether two match
- [x] `requests/confirmation.ts`: `ConfirmationTracker`, the decision above
- [x] ~~`requests/lifecycle.ts`: SUBMITTED -> UNKNOWN by the sidecar~~ — dropped, see the review
- [x] `storage/request-store.ts`: `recordConfirmation`, one atomic sidecar move
- [x] `requests/mcp-tools.ts`: `vault_get_request` settles first; the view discloses what was checked and by whom
- [x] `requests/phone-service.ts` + `server.ts`: `CheckStatus`
- [x] Guards: the chain client's method list, and the roles matrix
- [x] Android: `checkStatus`, the request's own state on the phone, and *Check status* in the inbox
- [x] Tests on both sides, and a deliberate break for each behaviour
- [x] Docs: `docs/protocol.md` result semantics, `docs/guides/troubleshooting.md` unknown outcomes

## Review

Done on 2026-09-12, committed on `superset/feat/see-27-stage-4`. 384 sidecar tests (+21), 379
Android unit tests (+14), `check:generated`, `test:hello`, `test:queue`, and `build` all pass.

### The one plan item that was wrong

The plan had a mismatched signature move the request `SUBMITTED -> UNKNOWN`. That was written
before reading `lifecycle.test.ts`, which proves the transition table is **acyclic** — and
`UNKNOWN -> SUBMITTED` already exists, so adding the reverse made a cycle and failed the test.

The test was right and the plan was wrong. A mismatch now settles nothing: the request stays
SUBMITTED, `matches_approval` is false, and the detail says plainly that nothing here can account
for the approved transaction. SUBMITTED already means "sent, not settled", so nothing is lost by
saying it there, and the invariant that no state moves backward is worth more than a second word
for the same condition. `docs/protocol.md` now gives that rule its real reason.

### A second thing the tests caught

The first tracker handled an UNKNOWN request that carried a signature, including a two-step
recovery through SUBMITTED. Writing the test for it showed the state is unreachable: nothing sets
`Outcome.signature` except `transaction_submission`, which moves the request to SUBMITTED. That was
dead code claiming a capability, and it came out. What replaced it is the honest answer an UNKNOWN
transfer gets: there is no signature to look up, and nothing will send it again.

### Worth knowing for the next task

- `getSignatureStatuses` without `searchTransactionHistory` only sees the status cache, so a
  missing status is two different things depending on the blockhash window. The distinction is the
  whole of the "never landed" rule.
- The sidecar's `stage-boundary.test.ts` treats `transaction(` as SQL (`\btransaction\(`), so the
  chain read is named `confirmedTransaction`. Worth remembering before naming a method `transaction`.
- The declared sidecar transitions that need a signature nobody reported stay unimplemented, and
  `docs/protocol.md` now says which ones and why, rather than leaving the table to imply otherwise.
