# SAW-016 (SEE-24) — Async manual message signing

Let an agent ask for a message signature, have the owner review the exact bytes on the phone,
approve manually, and get the wallet to sign through Mobile Wallet Adapter. The sidecar stores the
request, verifies the signature against the request's wallet, and hands the agent the signed bytes,
the address, and the signature. Nothing signs without the owner's tap, and the sidecar never signs.

## Contract

- [x] No `proto/` change: `SignMessageAction`, `Approval`, `MessageSignature`, and `COMPLETED`
      already exist from SAW-009. Record why in `.claude/tasks/decisions.md`.
- [x] The signed bytes are always the request's own bytes, because the sidecar verifies the
      signature against them; nothing new has to travel on the wire.

## Sidecar

- [x] `requests/signature.ts`: `verifySignature(wallet, message, signature)` over Ed25519 with
      `node:crypto` (raw 32-byte key wrapped as SPKI). Verify only — never sign.
- [x] `lifecycle.ts`: `decideResult` refuses a `message_signature` that doesn't verify against the
      request's wallet and its exact message bytes (`INVALID_PARAMETERS`).
- [x] `mcp-tools.ts`: `vault_sign_message` (text or base64 bytes) creating a PENDING request.
- [x] `mcp-tools.ts`: `vault_get_capabilities`, read-only, advertising manual approval and only the
      operations actually served.
- [x] `RequestView` gains `wallet` (a wallet action's address) and `signed_message_base64` (the
      exact bytes, once signed).
- [x] `mcp-endpoint.ts`: the session instructions name the new tools.
- [x] Tests: `signature.test.ts` (RFC 8032 vectors, a generated key pair, tampered message and
      signature), `lifecycle.test.ts`, `mcp-tools`/`endpoints.test.ts`, `roles.test.ts`,
      `server.test.ts`, `live-compat.test.ts`, `stage-boundary.test.ts` (the sidecar never signs).

## Test agent

- [x] `pnpm agent sign <text>` and `pnpm agent capabilities`.
- [x] `pnpm agent get <id>` verifies the signature itself, with its own Ed25519 verifier, and prints
      `signature_verified`.
- [x] `cli.test.ts` covers both, and the tool list.

## Android

- [x] `wallet/WalletAdapter.kt`: `signMessage(message, wallet, authToken)` and `SignResult`
      (`Signed`, `NoWallet`, `Declined`, `AuthorizationExpired`, `Failed`, plus `NotConnected` and
      `Changed`, which only the repository returns).
- [x] `wallet/MwaWalletAdapter.kt`: `signMessagesDetached` through the same activity sender, with
      `ERROR_NOT_SIGNED` mapped to `Declined`.
- [x] `wallet/WalletRepository.kt`: `sign(message, wallet)` — the wallet is asked only for the
      selection the owner reviewed.
- [x] `connections/LocalResult.kt` + `storage/ResultStore.kt`: an answer can now be `Approve`, and
      carries the wallet outcome (signature, declined, or a failure). Version 2 of the file format.
- [x] `connections/ConnectionRepository.kt`: `answer(key, Approve)` stores and sends the approval,
      and `recordSigning` stores and sends what the wallet did; `deliver` resends whatever the
      sidecar hasn't taken. The wallet call in between belongs to the ViewModel, so the connections
      package keeps no dependency on the wallet one.
- [x] `inbox/InboxViewModel.kt`: `approve(key)` with the reviewed wallet, and the stale-review check.
- [x] `inbox/RequestDetailsScreen.kt` + `InboxText.kt`: show the complete message with control
      characters and other invisible characters made visible, its byte count and encoding, the
      requesting connection, and the wallet and network that will sign; Approve and Reject.
- [x] Tests: `WalletRepositoryTest`, `FakeWalletAdapter`, `InboxTest`, `InboxViewModelTest`,
      `RequestDetailsScreenTest`, `storage/ResultStoreTest`, `inbox/MessagePreviewTest`,
      `wallet/Base58Test`.

## Rules this keeps

- [x] No wallet call happens before the owner approves.
- [x] The approval binds to the reviewed content: the message bytes' SHA-256, with the wallet and
      network as shown. A different message, wallet, or network needs a new review.
- [x] A signature is not a transfer: nothing is sent on chain, and the app says so.
- [x] A lost signing is FAILED, never UNKNOWN: nothing was broadcast, so nothing is in doubt.

## Documentation

- [x] `docs/guides/message-signing.md` (new), `docs/protocol.md` (message result encoding, the new
      tools, the capabilities view), `docs/architecture.md`, `docs/security.md`,
      `docs/development/sidecar.md`, `docs/development/android.md`, `docs/testing/stage-3.md`,
      `docs/changelog/2026-09-12.md`, `README.md`, `AGENTS.md`, `CODEBASE.md`,
      `test-agent/README.md`, `examples/hermes.config.yaml`, `docs/integrations/hermes.md`.

## Checks

- [x] `pnpm check`
- [x] `pnpm check:generated`
- [x] `pnpm test:hello`
- [x] `pnpm test:queue`
- [x] `pnpm check:android`
- [ ] Owner's device check on the Seeker: **NOT RUN** — no device was attached.

## Review

Done, and every automated check passes. What changed, and what didn't:

- **The contract didn't change.** SAW-009 had already defined everything a message signing needs, and the signed bytes are always the request's own, so nothing new travels on the wire. `pnpm check:generated` passes with no regeneration.
- **The sidecar gained one pure module,** `requests/signature.ts`, which verifies and never signs — and a stage-boundary check that says so. `decideResult` now refuses a `message_signature` that isn't the request's wallet's signature over the request's exact bytes.
- **Two MCP tools:** `vault_sign_message`, which stores a request and contacts no wallet, and `vault_get_capabilities`, which advertises manual approval and only the operations actually served.
- **The phone approves first and asks the wallet second.** The approval carries the SHA-256 of the reviewed bytes; the wallet is opened only after the sidecar accepts it; and what the wallet did is stored before it's sent. A selection that changed during the review stops the approval instead of signing.
- **The review screen shows the whole message** with its invisible characters marked, its byte count, and the wallet that would sign, and says plainly that a signature is not a payment.

Results:

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: 281/281 sidecar, 23/23 test agent |
| `pnpm check:generated` | PASS |
| `pnpm test:hello` | PASS: 9/9 |
| `pnpm test:queue` | PASS: 7/7 |
| `pnpm check:android` | PASS: 283/283, lint clean, both APKs |
| Real signing on the Seeker | **NOT RUN**: no device attached (`docs/testing/stage-3.md`, steps 16 to 23) |

Two things worth flagging:

- **A message signing is never UNKNOWN.** Nothing is broadcast, so a signature the phone never received exists nowhere. An approval the wallet never answered is reported as FAILED when the app next opens, rather than leaving the request non-terminal with nothing that could settle it.
- **`ResultStore` is at version 2.** An answer can now be an approval and carry what the wallet did; files written by version 1 are still read.
