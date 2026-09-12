# SAW-017 (SEE-25) — Wallet lifecycle and reliable result delivery

One manual wallet interaction must produce exactly one accurately reported outcome, whatever the
Android lifecycle does in between: a rotation, a trip to the wallet app and back, a killed process,
a dead network after the signature, or a restarted sidecar. Nothing may invent a success, ask the
wallet twice, or let a reply settle another connection's request.

## Android

- [x] `SigningOutcome.Unresolved`: a wallet answer this phone never received. Shown as unresolved,
      reported to the sidecar as an execution failure (nothing was signed), never retried at the
      wallet. `ResultStore` version 3.
- [x] `ConnectionRepository.resolveAbandonedSignings(except)`: every stored approval with no wallet
      answer becomes Unresolved and is sent. `load()` uses it; the inbox uses it on every return to
      the foreground, excluding the signings in flight in this process.
- [x] `InboxViewModel.onAppVisible()`, from `MainActivity.onStart`, so a process that died in the
      wallet doesn't leave an approval waiting for an answer that can never arrive.
- [x] A wallet that never answers times out, so a lost result can't hold a request open forever.
- [x] `deliver` serializes per request instead of dropping a send that arrives while another runs:
      a signature stored during a refresh still reaches the sidecar.
- [x] The Mobile Wallet Adapter sender survives the activity being recreated: the app waits briefly
      for the next activity's sender rather than failing, and only the activity that registered a
      sender clears it.
- [x] Retrying delivery never reaches the wallet: the wallet is asked in `InboxViewModel.approve`
      only, and only once per request.

## Sidecar

- [x] Repeated `SubmitResult` returns the same terminal result, across a restart too. It already
      does; this task proves it, for approvals and for message signatures.
- [x] A result naming another connection's request is refused.

## Tests and checks

- [x] Rotation while the wallet is open: the ViewModel, the request, and the signing survive.
- [x] Rapid taps: one approval, one wallet call.
- [x] Wallet cancellation stays a rejection, and a transport failure stays a failure to send.
- [x] Network loss after the signature: the signature is stored, and the next send delivers it.
- [x] The process dies between approval and answer: Unresolved, not success, and no second signing.
- [x] Counted fake-adapter invocations prove a delivery retry starts no second signature.
- [x] A reply for connection A cannot complete a request on connection B.
- [x] Sidecar: repeated submission, before and after a restart.
- [x] `pnpm check`, `pnpm check:generated`, `pnpm test:hello`, `pnpm test:queue`, `pnpm check:android`.
- [ ] Owner's device check on the Seeker: **NOT RUN** — no device was attached.

## Documentation

- [x] `docs/testing/wallet-lifecycle.md` (new), `docs/guides/troubleshooting.md`,
      `docs/guides/message-signing.md`, `docs/protocol.md`, `docs/architecture.md`,
      `docs/development/android.md`, `docs/changelog/2026-09-12.md`, `CODEBASE.md`, `AGENTS.md`,
      `README.md`, `.claude/tasks/decisions.md`.

## Review

Done, and every automated check passes. What changed:

- **The wallet is asked once, and delivery is a separate thing.** `InboxViewModel.approve` is the only caller of `WalletRepository.sign`; `ConnectionRepository.deliver`, which every refresh, **Send again**, and retry goes through, talks to a sidecar and never to a wallet. The fake adapter counts its calls, and the count stays 1 through a lost response, a resend, and a rotation.
- **An answer this phone never received is its own outcome.** `SigningOutcome.Unresolved` replaces the SAW-016 `Failed(APP_CLOSED)`: the screen says this phone never learned what the wallet did, and the sidecar is still told the request failed, because nothing was broadcast. `InboxViewModel.onAppVisible`, from `MainActivity.onStart`, settles them on every return to the foreground, leaving the signings still in flight alone; a ten-minute timeout covers a wallet that never answers at all.
- **Nothing is dropped between two sends.** `deliver` serializes per request instead of returning null, so a signature stored while an earlier send was in the air still goes out. A refresh still leaves a send in flight to itself, which is both the right behaviour and what keeps the "a revocation settles the answer mid-send" test from deadlocking.
- **A rotation changes nothing.** `InboxActivityTest` recreates the activity while the wallet holds the message: the request is still there, the wallet has been asked exactly once, and the signature settles it. The Mobile Wallet Adapter sender now waits briefly for the next screen's rather than failing, and only the activity that registered one clears it.
- **The sidecar needed no change.** Repeated `SubmitResult` already returned the same terminal result, and a foreign reference was already refused. The task added the tests that say so, for an approval and for a signature, before and after reopening the database.

Results:

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: 282/282 sidecar, 23/23 test agent |
| `pnpm check:generated` | PASS |
| `pnpm test:hello` | PASS: 9/9 |
| `pnpm test:queue` | PASS: 7/7 |
| `pnpm check:android` | PASS: 293/293, lint clean, both APKs |
| Deliberate breaks | PASS: three breaks each failed the matching tests, and each file was restored |
| The owner's checks on the Seeker | **NOT RUN**: no device attached (`docs/testing/wallet-lifecycle.md`, steps 1 to 14) |

Two things worth flagging:

- **`ResultStore` is at version 3.** Files written by versions 1 and 2 are still read; a version 3 file read by older code is skipped rather than misread.
- **`deliver`'s contract changed.** It no longer returns null when another send of the same answer is running: it waits and returns what that send settled. A refresh keeps the old behaviour through a private overload, and that difference is deliberate.
