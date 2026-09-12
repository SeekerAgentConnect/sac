# MWA authorization refresh, and message signing back to the Stage 3 scope

Two fixes to Stage 3, plus the open review comments on the Stage 3 pull request.

## 1. Keep the wallet's refreshed authorization

Mobile Wallet Adapter reauthorizes this app at the start of every `transact`, and the wallet may
hand back a replacement authorization token. `MwaWalletAdapter.signMessage` threw it away, so
`WalletRepository` kept signing with a token the wallet had already replaced.

- [x] `WalletAdapter.signMessage` returns `SigningAnswer`: the `SignResult` and the authorization the
      wallet reported while answering (`authToken`), redacted in `toString`.
- [x] `MwaWalletAdapter` captures `AuthorizationResult.authToken` inside the `transact` block, before
      it asks for a signature, so a declined signing still carries the refreshed authorization. The
      wallet is asked exactly once, as before.
- [x] `WalletRepository.sign` stores a refreshed token that differs from the one it offered, keeping
      the selected wallet, address, and network as they were, and never logs or publishes it.
- [x] An authorization the wallet refused still clears the stored wallet, and a declined signing
      never does.
- [x] Tests in `WalletRepositoryTest`: token A → B is persisted, the next signing uses B, a reload
      uses B, the binding is unchanged, a decline keeps B, and a refusal still clears.

## 2. `vault_sign_message` takes text only

SEE-24 scoped the first signing tool to explicit UTF-8 text. `message_base64` let an agent queue
bytes the owner can't read, which is not the Stage 3 scope.

- [x] `message` is required; `message_base64` is gone from the tool's input schema.
- [x] The UTF-8 bytes of exactly that text are signed; empty messages and the 4096-byte limit are
      unchanged (`invalidActionReason`).
- [x] The result keeps `signed_message_base64` for independent verification.
- [x] `SignMessageAction.data` stays in the protobuf for later stages; no Stage 3 public tool can
      create a binary signing request.
- [x] The phone still renders a `data` message, and the invisible-character review is unchanged.
- [x] Tests and docs updated (`endpoints.test.ts`, `protocol.md`, `sidecar.md`, guides, changelog).

## 3. Review comments on PR #5

- [x] P1 `WalletViewModel`: publish the wallet as soon as a newly usable connection appears, not only
      after a background/foreground cycle.
- [x] P1 `InboxText.isHidden`: mark every invisible code point by Unicode category (control, format,
      unassigned, private use, separators, variation selectors, invisible fillers) instead of a
      hand-picked list, over code points rather than chars.
- [x] P1 `RequestDetailsScreen`: drop `FontFamily.Monospace`; stock Material typography only.
- [x] P2 `InboxViewModel`: clamp a wallet failure detail to the protocol's 1024 UTF-8 bytes and drop
      unpaired surrogates before storing it, so a submission can't be refused for ever.

## 4. Verification

- [x] `pnpm check` — PASS
- [x] `pnpm test:hello` — PASS
- [x] `pnpm test:queue` — PASS
- [ ] `pnpm check:generated` — NOT RUN: `buf.build` is blocked by this environment's network policy,
      so buf's remote plugins can't be fetched. No `.proto` changed. CI runs it.
- [ ] `pnpm check:android` — NOT RUN: `dl.google.com` is blocked by this environment's network
      policy, so neither the Android SDK nor the Android Gradle Plugin can be fetched. The changed
      Kotlin was parsed and format-checked with ktfmt 0.64 (kotlinlang style) instead. CI runs it.

## Review

**What changed.** The wallet boundary now answers a signing with a `SigningAnswer`: the outcome and
the authorization the wallet reported. `MwaWalletAdapter` reads that authorization from inside the
`transact` block, which Mobile Wallet Adapter calls with the `AuthorizationResult` of the
reauthorization it has just done, so a declined signature carries it too and no second wallet
session is ever opened for it. `WalletRepository.sign` stores a token that differs from the one it
offered, leaving the selection, address, and network untouched and publishing nothing new. A
refused authorization still clears the wallet, and that is checked before any replacement is
stored.

`vault_sign_message` takes `message` and nothing else. `SignMessageAction.data` stays in the
protobuf, unused by anything an agent can call, so a later stage can use it without a contract
change; `messageBytes` still handles both forms, and the phone still renders a `data` message.

**Trade-off.** If storing a replaced authorization fails — a Keystore or file-system error — the
signing outcome is kept and the old token stays. The next signing is then refused and the owner
connects the wallet again, which is what an expired authorization already does. Throwing instead
would have lost a signature the owner had just approved.

**Tested.** `WalletRepositoryTest` covers A → B stored, the next signing and a reloaded repository
using B, the binding and the publications unchanged, B kept when the owner declines, and a refused
authorization still cleared even when the wallet reported a replacement. `WalletViewModelTest`
covers pairing while the screen is open. `MessagePreviewTest` covers U+061C, an invisible operator,
a tag character beyond the basic plane, and a variation selector. `InboxViewModelTest` covers a
wallet message cut to the protocol's limit. `endpoints.test.ts` covers the tool's input schema and
the refusal of a message sent as bytes.

**Not run here.** `pnpm check:android` and `pnpm check:generated`: this environment's network policy
blocks `dl.google.com` and `buf.build`, so neither the Android SDK nor buf's remote plugins can be
fetched. No `.proto` changed, so nothing regenerates. The Kotlin was checked with ktfmt 0.64
(kotlinlang style), which parses every file it formats, and the MWA API used here was read from the
published `mobile-wallet-adapter-clientlib` 2.2.0 artifacts. CI runs both commands.
