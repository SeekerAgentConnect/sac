# SEE-84 — Harden Kotlin MWA authorization, session persistence, and lifecycle tests

Source-level review findings, not device-reproduced failures. Keeps the official Mobile Wallet
Adapter dependency (`mwa = "2.2.0"`) and the `WalletAdapter` abstraction.

## 1. Preserve rotated authorization after transaction sending

- [x] `SendingAnswer(result, authToken)` beside `SigningAnswer`; `WalletAdapter.signAndSendTransaction`
      returns it.
- [x] `MwaWalletAdapter` reads the reauthorization inside the one session, for sending as for signing.
- [x] `WalletRepository.send` keeps a refreshed token for every outcome — sent, declined, unknown —
      and still forgets one the wallet refused.
- [x] A storage failure leaves the wallet's answer standing: it never overwrites the outcome and
      never asks the wallet again.

## 2. Validate the freshly authorized account before signing

- [x] The reauthorization must still list the reviewed public key; otherwise nothing is signed.
- [x] An explicit chain contradiction is rejected; an omitted chain list is unknown, not a match.
- [x] The adapter reports `Changed`, and the repository requires a reconnect/review.
- [x] Signature verification and transfer inspection are untouched.

## 3. Keep wallet targeting consistent

- [x] One `MobileWalletAdapter` per wallet session, reused across connect/sign/send, so its learned
      endpoint survives the session instead of being discarded per operation.
- [x] The session is dropped on disconnect, on a refused authorization, and on a network change.
- [x] Process-restart restoration: `walletUriBase` is private in 2.2.0 with no public setter —
      documented as a limitation and a follow-up, not worked around by reflection.
- [x] Endpoint metadata only ever comes from the wallet's own authorization flow.

## 4. Make wallet-session persistence consistent

- [x] One encrypted, versioned session record (selection + binding + token) in `noBackupFilesDir`.
- [x] Migration from the `wallet.json` + `wallet-authorization` pair, and rejection of a half-written
      legacy pair.
- [x] The repository reads the record whole and refuses a token whose binding isn't the selection.
- [x] Interruption between writes tested.

## 5. Real adapter boundary and failure classification

- [x] `WalletSessionClient` / `WalletRequests`: a narrow seam in this app's own types, so
      authorization, refresh, signing, sending and cleanup are exercised without a wallet app.
- [x] A failure before the send request was dispatched is a failure; anything after it is `Unknown`.
- [x] No automatic repeat signing or sending.

## 6. Capabilities and SDK boundaries

- [x] Documented: how wallet capabilities would be exposed through this app's own types, and why a
      format a wallet supports is not thereby enabled.
- [x] Documented: the follow-up split between wallet API, Android MWA integration, server workflow,
      and app UI.

## Checks

- [x] `pnpm check:android`
- [x] `pnpm check`, `pnpm test:hello`, `pnpm test:queue`, `pnpm test:updates`, `pnpm test:push`
- [x] Physical-device checks recorded separately as NOT RUN.

## Review

The record is [`docs/testing/wallet-lifecycle.md`](../../docs/testing/wallet-lifecycle.md#verification-record-see-84).

- **What changed.** `WalletClient.kt` states one wallet session in this app's own types;
  `MwaWalletAdapter` keeps one session client and checks the wallet's own reauthorization before it
  asks for anything; `signAndSendTransaction` answers with the authorization beside the outcome;
  `WalletStore` keeps one sealed record and migrates the old pair; `WalletRepository` reads that
  record whole and keeps a rotated token for a transfer as for a message.
- **What was run.** `pnpm check`, `check:generated`, `test:hello`, `test:queue`, `test:updates`,
  `test:push`, and `pnpm check:android` — 882 Android unit tests, Spotless, lint, both APKs — plus
  ten deliberate breaks, each of which failed the tests it should.
- **Two caveats, both in the record.** The Android unit tests need `--max-workers=1` on this
  machine; in parallel the same 25 loopback-socket tests fail here as on the commit this branch
  started from, and none is a wallet test. And the device checks, steps 31 to 41, are NOT RUN.
- **What was deliberately not done.** Wallet targeting across a process restart: the client's
  learned endpoint is private in MWA 2.2.0 with no public setter, and reflection was off the table,
  so the limitation is documented with the follow-up rather than worked around. Extracting the SDK
  is SEE-102; only the boundary it would cut along is written down.
