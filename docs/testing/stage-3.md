# Stage 3 tests

Stage 3 brings the wallet in. SAW-015 is its first task: the app connects the wallet the owner already has through Mobile Wallet Adapter, and publishes the address and network to every paired sidecar so an agent can read them. SAW-016 adds the first thing the wallet is asked to do: sign a message the owner has reviewed and approved by hand. No funds move in either: a signature is not a transaction.

SAW-017 is about what happens around that trip to the wallet — rotation, backgrounding, a killed process, a dead network, a restarted sidecar — and has its own page: [`wallet-lifecycle.md`](wallet-lifecycle.md).

SAW-018 closes the stage. It adds no feature: it writes the owner-facing walkthrough ([`../guides/wallet-setup.md`](../guides/wallet-setup.md)), gives Hermes and the test agent the commands for creating a signing request and reading its result back ([`../integrations/hermes.md`](../integrations/hermes.md#5-sign-a-message-with-your-wallet)), confirms that nothing in this stage needs funds, swaps, agent keys, or a biometric of the app's own, and records what passed, what failed, and what was not run — automated checks and physical-Seeker checks kept apart.

## Automated checks

`pnpm check` runs the sidecar's and the test agent's tests, and `pnpm check:android` runs the app's JVM and Robolectric tests. CI runs both.

| Area | What the tests cover | Where |
| --- | --- | --- |
| The contract | `WalletBinding` and `PublishWalletRequest`, set and cleared, byte for byte in both runtimes | `server-sdk/src/requests/fixtures.test.ts`, `RequestProtocolFixturesTest` |
| The sidecar's store | Publishing, clearing, replacing, and republishing the same binding; a malformed wallet or missing network; an unknown connection; cancelling the PENDING requests a new binding no longer fits, and leaving acks alone; `bound_at` stamped by the sidecar; each connection's binding kept to itself | `server-sdk/src/storage/request-store.test.ts` |
| The sidecar's endpoints | `PublishWallet` for the caller's own connection only, and `vault_get_address` before, during, and after a wallet is connected | `mcp-server/src/requests/endpoints.test.ts` |
| Roles | `RequestService.PublishWallet` and `vault_get_address` against every credential | `mcp-server/src/pairing/roles.test.ts` |
| Migration 3 | An upgraded v1 database has no binding, and reaches the current schema version | `server-sdk/src/storage/database.test.ts` |
| The agent's side | `pnpm agent address` before and after the phone publishes, and the tool list | `test-agent/src/cli.test.ts` |
| The wallet boundary | Success, no wallet installed, the owner declining, an expired authorization, an unsupported network, and a changed address or network, all through `FakeWalletAdapter` | `WalletRepositoryTest`, `WalletViewModelTest` |
| The phone's storage | The selection read back, the authorization encrypted, another key that can't open it, damaged files, and clearing both together | `WalletStoreTest` |
| The screens | The network choice, the address and network, each problem, the unpublished connections, and the disabled controls | `WalletScreenTest`, `WalletActivityTest` |
| The stage boundary | Storage only in `connections/storage/` and `wallet/storage/`; no wallet-key API anywhere; the MWA client on the classpath on purpose and Seed Vault's SDK off it; nothing backed up; the sidecar verifies signatures and creates none | `StageBoundaryTest`, `mcp-server/src/stage-boundary.test.ts` |

These cover SAW-016:

| Area | What the tests cover | Where |
| --- | --- | --- |
| Ed25519 verification | The RFC 8032 §7.1 known-answer vectors, a tampered signature, a tampered message, a message with a byte appended, another wallet's key, a signature made in the test and verified, and everything that isn't an address or a 64-byte signature | `server-sdk/src/requests/signature.test.ts` |
| The lifecycle | A `message_signature` accepted from PROCESSING and from UNKNOWN, and refused when it isn't the request's wallet's signature over the request's own bytes — another wallet's signature, a trailing newline, the NFC form of the same text, and 64 bytes that are no signature | `server-sdk/src/requests/lifecycle.test.ts` |
| The sidecar's store | An approval and the verified signature kept together in the outcome, and a signature the wallet didn't make never stored | `server-sdk/src/storage/request-store.test.ts` |
| The sidecar's endpoints | `vault_sign_message` end to end: refused for another wallet, stored as PENDING, approved, a signature over other bytes refused with the request left where it was, and the COMPLETED view carrying the wallet, the signature, and the exact bytes; the tool's input taking the message as text and nothing else, with an empty or over-long one refused; `vault_get_capabilities` before and after a wallet is connected | `mcp-server/src/requests/endpoints.test.ts` |
| Roles | `vault_sign_message` and `vault_get_capabilities` against every credential | `mcp-server/src/pairing/roles.test.ts` |
| The agent's side | `pnpm agent capabilities`, and `pnpm agent sign` followed by the owner approving and the wallet signing, with `pnpm agent get` verifying the signature itself | `test-agent/src/cli.test.ts` |
| The phone's flow | No wallet call before the owner approves; the approval sent before the signature; a wallet that declined or couldn't sign; a signature over other bytes discarded; a request that moved on while it was reviewed; a selection that changed during the review; no wallet connected; a request for another wallet; a rejection that never touches the wallet; and an approval the wallet never answered recorded as a failure when the app opens again | `InboxViewModelTest`, `InboxTest` |
| What the owner sees | The complete message with every invisible code point marked, by Unicode category rather than a list of ranges, beyond the basic plane too; the byte count; a 4096-byte message shown whole; bytes as hex; the signing wallet and network; and every outcome | `MessagePreviewTest`, `RequestDetailsScreenTest` |
| The phone's storage | An approval and each signing outcome across a restart, and an answer written before approvals existed | `ResultStoreTest` |

These cover SAW-018:

| Area | What the tests cover | Where |
| --- | --- | --- |
| No spending, and no swap | Every MCP tool the sidecar registers is one of the seven named ones, so no tool creates a transfer or a swap; no shipped source reaches a chain RPC or broadcasts anything | `mcp-server/src/stage-boundary.test.ts`, `endpoints.test.ts` |
| No agent key | No shipped sidecar source holds a private key, a secret key, or a keypair: an agent authenticates with the bearer token the owner issued it | `mcp-server/src/stage-boundary.test.ts` |
| No biometric of the app's own | The app calls no `BiometricPrompt`, `BiometricManager`, `FingerprintManager`, `KeyguardManager`, or device-credential intent, requires no user authentication on its Keystore key, and has no biometric library on the classpath; the wallet decides for itself what it asks for | `StageBoundaryTest.nothingSpendsSwapsOrAsksForABiometricOfItsOwn` |
| No transaction on the phone | The app calls no `signTransactions`, `signAndSendTransactions`, or `sendTransaction`, and names no RPC host | `StageBoundaryTest`, and the same test's positive control proves the scan reads the real sources |

### What the automated checks deliberately can't show

Every wallet outcome above comes from `FakeWalletAdapter`. No test starts a real wallet app: Mobile Wallet Adapter needs an installed wallet and a real activity association, so the wallet's own behaviour is the owner's check below. In particular, **which networks the installed wallet serves is a property of that wallet**, and this repository must not assume Seed Vault Wallet exposes a devnet switch.

**A development wallet never stands in for the acceptance check.** `FakeWalletAdapter` may exercise error handling — no wallet installed, a declined authorization, an unsupported network, a wallet that couldn't sign — and it does. Stage 3 is accepted only when the owner's own Seeker, with Seed Vault Wallet, connects and signs by hand ([R8](https://docs.solanamobile.com/get-started/development-setup)). A mock, an emulator, or a successful APK build is recorded as NOT RUN.

For SAW-016 this also means **no automated test has ever seen a real signature from Seed Vault Wallet**. The signatures in the sidecar's tests are made with a throwaway Ed25519 key in the test process, which proves the verification but not the wallet. Whether the installed wallet signs a message at all, and what it shows the owner while doing it, is steps 16 to 23 below.

## The owner's checks on the Seeker

Run these on the physical Seeker with Seed Vault Wallet set up, and record PASS, FAIL, or NOT RUN. An emulator or a successful APK build never counts.

Before starting: pair the phone with a sidecar ([`docs/guides/pairing.md`](../guides/pairing.md)) and have `pnpm agent address` ready on the computer that runs it.

| # | Step | Expected |
| --- | --- | --- |
| 1 | With no wallet connected, run `pnpm agent address` | Exit code 9, and `WALLET_NOT_CONNECTED: …`. No address is printed. |
| 2 | Open the app; the first row on Connections is **Wallet** | It says "No wallet connected." |
| 3 | Open it, pick **Mainnet**, and tap **Connect wallet** | Seed Vault Wallet opens and asks which account to use. |
| 4 | Approve in the wallet | The app shows the address, Mainnet, the account's name, and the time, and says it told 1 connection. |
| 5 | Run `pnpm agent address` again | It prints the same address, `"network":"mainnet"`, and a `bound_at`. |
| 6 | Check the address against the wallet | The address the app shows is the account the wallet shows, character for character. |
| 7 | Go back; look at the Wallet row on Connections | It shows the same address. |
| 8 | Close the app and open it again | The wallet is still there, and `pnpm agent address` still answers. |
| 9 | Connect again and choose **Devnet** | Record what the wallet actually offers. If it serves devnet, the app shows Devnet and the agent reads `"network":"devnet"`. If it doesn't, the app says "The wallet doesn't serve this network." Either outcome is a PASS for this step; the point is to record which one is true on the Seeker. |
| 10 | Connect again and choose a **different account**, if the wallet has one | The app shows the new address, and `pnpm agent address` reads it. |
| 11 | Queue a demo request (`pnpm agent ack "Still here"`) and then change the wallet | The ack stays in **Pending requests**: it needs no wallet. (A wallet request would be cancelled; no tool creates one yet.) |
| 12 | Tap **Disconnect wallet** | The app says no wallet is connected, and `pnpm agent address` exits 9 with `WALLET_NOT_CONNECTED` again. |
| 13 | Revoke the app in the wallet's own settings, then tap **Connect wallet** | The app either asks afresh or says the authorization is no longer accepted and lets you connect again. It never shows a stale address as if it still worked. |
| 14 | Check the sidecar's log | It carries the connection ID and the address, and no token, credential, or wallet authorization. |
| 15 | Check that no secret left the phone: `sqlite3 "$HOME/.seeker-agent-connect/mcp-server/direct-server.db" "SELECT wallet_address, wallet_network FROM connections"` (or use the configured `DATABASE_PATH`) | The address and network only. There is no column, and no value anywhere in the database, that holds a seed phrase, a private key, or the wallet's authorization token. |

### Message signing (SAW-016)

Connect the wallet again first, so there is one to sign with.

| # | Step | Expected |
| --- | --- | --- |
| 16 | Run `pnpm agent capabilities` | `"approval":"manual"`, `"signing":"wallet"`, and `operations` containing `sign_message`. |
| 17 | Run `pnpm agent sign "Sign in to example.com\nNonce: 4711"` | It prints the request as PENDING with the wallet, and says the owner still has to approve. **The wallet app does not open.** |
| 18 | Open the app and the request | The whole message is shown, the line break is marked `␊`, the byte count is right, and the wallet and network you connected are named. It says a signature is not a payment. |
| 19 | Tap **Approve and sign** | Seed Vault Wallet opens and asks you to sign. Record what it shows: whether it displays the message itself. |
| 20 | Approve in the wallet | The app says your wallet signed it. `pnpm agent get <id>` prints COMPLETED, the signature, `signed_message_base64`, and `"signature_verified":true`. |
| 21 | Repeat with `pnpm agent sign` and decline in the wallet instead | The app says you declined and nothing was signed, and the agent reads REJECTED. |
| 22 | Ask for one more, and change the wallet on the **Wallet** screen before approving | The request disappears from Pending requests, cancelled by the sidecar, and the agent reads CANCELLED. Nothing was signed. |
| 23 | Check the sidecar's log and database again | The signature and the address are there; no seed phrase, private key, or wallet authorization token is, anywhere. |

### The Hermes round trip (SAW-018)

Steps 16 to 23 drive the sidecar with `pnpm agent`, which is the repository's own client. These drive it with a real agent instead, which is what the stage is for. Set Hermes up first: [`../integrations/hermes.md`](../integrations/hermes.md). Keep verbose tool output on, so each result is visible rather than only the model's summary.

| # | Step | Expected |
| --- | --- | --- |
| 24 | In a Hermes session, ask it to call `vault_get_capabilities` | `"approval":"manual"`, `"signing":"wallet"`, `"wallet_connected":true`, and `operations` containing `sign_message` and no transfer or swap. |
| 25 | Ask it to call `vault_get_address` | The wallet and network the app shows, character for character. |
| 26 | Ask it to call `vault_sign_message` with that wallet, the message `Sign in to example.com\nNonce: 4711`, and an idempotency key you choose | It answers at once with a `request_id` and `PENDING`. **Seed Vault Wallet does not open, and the phone shows no prompt of its own.** |
| 27 | Send the same prompt again, unchanged | The same `request_id` comes back. The sidecar logs that it returned the request for its idempotency key, and stores nothing new. |
| 28 | On the Seeker, open the request, check the message and the byte count, and tap **Approve and sign** | Seed Vault Wallet opens. Record what it shows: whether it displays the message itself. |
| 29 | Approve in the wallet | The app says your wallet signed it. |
| 30 | Ask Hermes to call `vault_get_request` with that `request_id` | `COMPLETED`, `"terminal":true`, with `wallet`, `signature`, and `signed_message_base64`. |
| 31 | Verify that signature yourself: `pnpm agent get <request_id>` on the sidecar's machine | `"signature_verified":true`, from a verifier that shares no code with the sidecar's. A model saying "signed" is not a signature; this is. |
| 32 | Ask for another signature through Hermes, and tap **Reject** on the phone | No wallet opens. Hermes reads `REJECTED` with `"detail":"The owner rejected the request."` |
| 33 | Ask for one more, tap **Approve and sign**, and decline inside Seed Vault Wallet | The app says you declined and nothing was signed. Hermes reads `REJECTED`, not `FAILED`. |

Record Hermes's own version with the result, and the wallet's version in [the wallet under test](../guides/wallet-setup.md#the-wallet-under-test). A session transcript, including every tool call and result, comes from `hermes sessions export <file>`.

## What this stage never needs

Stage 3 signs messages. A message signature is not a transaction: nothing is built, priced, or broadcast, and no balance is read. So none of the following is needed, and each is checked rather than promised.

| | Why not, and where it's checked |
| --- | --- |
| **Funds** | Nothing reads a balance or sends anything to a network. An empty account signs exactly as well as a funded one, on any network the wallet serves. `mcp-server/src/stage-boundary.test.ts` fails if any shipped source names an RPC host or a broadcast API, and `StageBoundaryTest` fails if the app does. |
| **Swaps, or any transfer** | The sidecar registers seven MCP tools and no more, none of which creates a transfer or a swap; `endpoints.test.ts` pins the exact set, in demo mode and out of it. The protocol knows both kinds, and the sidecar refuses them, but no tool can ask for one until Stages 4 and 6. |
| **A key of the agent's own** | An agent authenticates with the bearer token the owner issued it, compared as a hash in constant time. No shipped sidecar source holds a private key, a secret key, or a keypair, and none creates one. |
| **A custom biometric** | The app asks for no authentication of its own: the owner taps **Approve and sign**, and the wallet app decides for itself whether it wants a PIN, a fingerprint, or a face before signing. The app uses no `BiometricPrompt`, `BiometricManager`, `FingerprintManager`, or device-credential intent, its Keystore key sets no `setUserAuthenticationRequired`, and no biometric library is on the classpath. |

Mainnet is the app's default network because it's the one a Seeker owner actually has a wallet on, and connecting on it still spends nothing. Devnet and Testnet are offered for the same reason they always were: the wallet may or may not serve them, and step 9 records which.

## Verification record: SAW-015

Run on 2026-09-12 on macOS 26.5.2 (Apple silicon), with the versions in [`toolchain.md`](../development/toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 260/260 sidecar tests (25 more than before), and 21/21 test agent tests |
| `pnpm check:generated` | PASS: the committed generated code and fixtures match a fresh generation |
| `pnpm test:hello` | PASS: 9/9 Stage 1 acceptance cases |
| `pnpm test:queue` | PASS: 7/7 Stage 2 acceptance cases |
| `pnpm check:android` | PASS: Spotless, 245/245 unit tests (64 more than before), Android lint with no issues, and the debug and instrumentation APKs |
| The wallet boundary, through a fake adapter | PASS: success, no wallet installed, the owner declining, an authorization the wallet refused, an unsupported network, an unconfirmed network, and a changed address or network |
| No key, seed, or authorization leaves the phone | PASS, in `WalletRepositoryTest` and `WalletActivityTest`: the authorization appears in no call the gateway received and in no published binding. `StageBoundaryTest` finds no wallet-key API in any source file. |
| The wallet binding on the sidecar | PASS, in `request-store.test.ts` and `endpoints.test.ts`: published, cleared, replaced, republished unchanged, refused for another connection, and `vault_get_address` answering `WALLET_NOT_CONNECTED` rather than an invented address |
| `pnpm agent address` | PASS, in `cli.test.ts` against the real sidecar: exit 9 with `WALLET_NOT_CONNECTED` before, and the address and network after |
| Real connect and disconnect on the Seeker with Seed Vault Wallet | **PASS**, 2026-09-12: [the owner's checks](#the-owners-checks-on-the-seeker), steps 1 to 15, on the physical Seeker. |
| Which networks Seed Vault Wallet serves on the Seeker | **PASS — devnet is served.** Step 9 on 2026-09-12: the wallet connected on devnet, and Stage 4's transfer was finalized there. Mainnet and testnet were not exercised. The app still assumes nothing: it offers all three and reports what the wallet says. |

## Verification record: SAW-016

Run on 2026-09-12 on macOS 26.5.2 (Apple silicon), with the versions in [`toolchain.md`](../development/toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 281/281 sidecar tests (21 more than before), and 23/23 test agent tests |
| `pnpm check:generated` | PASS: the committed generated code and fixtures match a fresh generation. SAW-016 changed no `.proto` file: the contract already carried `SignMessageAction`, `Approval`, and `MessageSignature`. |
| `pnpm test:hello` | PASS: 9/9 Stage 1 acceptance cases |
| `pnpm test:queue` | PASS: 7/7 Stage 2 acceptance cases |
| `pnpm check:android` | PASS: Spotless, 283/283 unit tests (38 more than before), Android lint with no issues, and the debug and instrumentation APKs |
| Signatures verified with an independent verifier | PASS, in `signature.test.ts`: the RFC 8032 §7.1 known-answer vectors, which come from the specification rather than from this repository, are accepted, and tampered messages and signatures are rejected. `test-agent/src/verify.ts` is a second verifier, sharing no code with the sidecar's, and `cli.test.ts` runs it over a real round trip. |
| The sidecar never signs | PASS: `stage-boundary.test.ts` finds no signing or key-creation API in any shipped source, and `requests/signature.ts` only verifies |
| No wallet call before the owner approves | PASS, in `InboxViewModelTest`: the fake adapter records nothing until **Approve and sign**, and nothing at all when the wallet changed during the review, when no wallet is connected, when the request names another wallet, or when the owner rejects |
| The approval binds to the reviewed content | PASS, in `InboxTest` and `lifecycle.test.ts`: the approval carries the SHA-256 of the exact message bytes, and a signature over anything else — including the same text in NFC, or with a trailing newline — is refused |
| Rejection, wallet rejection, Unicode, long messages, and a request that moved on | PASS, in `InboxViewModelTest`, `MessagePreviewTest`, and `endpoints.test.ts` |
| Real signing on the Seeker with Seed Vault Wallet | **PASS**, 2026-09-12: steps 16 to 23 in [the owner's checks](#message-signing-saw-016), signed by hand in Seed Vault Wallet. |
| What Seed Vault Wallet shows while signing a message | **PASS** as an approval: the owner signed in the wallet by hand. **The wallet's exact screen text was not captured**; step 19 is where it goes when it is. |

## Verification record: SAW-018

Run on 2026-09-12 on macOS 26.5.2 (Apple silicon), with the versions in
[`toolchain.md`](../development/toolchain.md). SAW-018 is documentation and verification, so the two halves are kept apart: what a machine checked, and what only the owner's Seeker can.

### Automated

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 283/283 sidecar tests (one more than before), and 23/23 test agent tests |
| `pnpm check:generated` | PASS: SAW-018 changed no `.proto` file, and the committed generated code and fixtures match a fresh generation |
| `pnpm test:hello` | PASS: 9/9 Stage 1 acceptance cases |
| `pnpm test:queue` | PASS: 7/7 Stage 2 acceptance cases |
| `pnpm check:android` | PASS: Spotless, 294/294 unit tests (one more than before), Android lint with no issues, and the debug and instrumentation APKs |
| No funds and no swap | PASS: `mcp-server/src/stage-boundary.test.ts` finds no chain RPC host and no broadcast API in any shipped source, and every registered MCP tool is one of the seven named ones; `StageBoundaryTest` finds no transaction API in the app |
| No agent key | PASS: no private key, secret key, or keypair in any shipped sidecar source; agents authenticate with the owner's bearer token, compared as a hash in constant time |
| No custom biometrics | PASS: `StageBoundaryTest` finds no biometric, fingerprint, or device-credential API in the app, and no biometric library on the classpath. The wallet asks for whatever it wants; this app asks for nothing. |
| Deliberate breaks | Each break failed the matching test, and each file was restored byte for byte afterwards:<ul><li>An RPC host in `mcp-tools.ts` failed the sidecar's new boundary test.</li><li>Registering a tool under an unlisted name failed it too, naming the tool.</li><li>A `BiometricPrompt` mention in `InboxViewModel.kt`, and a `sendTransaction` mention in `MwaWalletAdapter.kt`, each failed `nothingSpendsSwapsOrAsksForABiometricOfItsOwn`.</li></ul> |

### On the physical Seeker

| Check | Result |
| --- | --- |
| The owner's checks, steps 1 to 15 (connect, address, network, disconnect) | **PASS**, 2026-09-12 |
| Message signing by hand, steps 16 to 23 | **PASS**, 2026-09-12 |
| The Hermes round trip, steps 24 to 31 | **PASS**, 2026-09-12: Hermes drove the wallet tools and the owner answered on the Seeker |
| Rejection at the app and at the wallet, steps 32 and 33 | **PASS**, 2026-09-12: nothing was signed either way |
| The wallet lifecycle checks, steps 1 to 14 of [`wallet-lifecycle.md`](wallet-lifecycle.md#the-owners-checks-on-the-seeker) | **PASS**, 2026-09-12 |
| Seed Vault Wallet's version, and the network path it serves | **Devnet is served** — see step 9 above. **The wallet's version string was not captured**; [the wallet under test](../guides/wallet-setup.md#the-wallet-under-test) is where it goes when it is. |
| What Seed Vault Wallet shows while signing | **PASS** as an approval, **not captured as text**: steps 19 and 28 record the wording when someone writes it down. |

**Stage 3's acceptance is met.** Everything a machine can check passes, the guide is reproducible, and on 2026-09-12 the owner's own Seed Vault Wallet connected, signed a message by hand, and answered a Hermes round trip on the physical Seeker. What is still missing from this record is text, not coverage: the wallet's version string and the exact wording of its signing screen.
