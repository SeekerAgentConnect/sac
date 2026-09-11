# Stage 3 tests

Stage 3 brings the wallet in. SAW-015 is its first task: the app connects the wallet the owner already has through Mobile Wallet Adapter, and publishes the address and network to every paired sidecar so an agent can read them. Nothing is signed yet, and no funds move.

## Automated checks

`pnpm check` runs the sidecar's and the test agent's tests, and `pnpm check:android` runs the app's JVM and Robolectric tests. CI runs both.

| Area | What the tests cover | Where |
| --- | --- | --- |
| The contract | `WalletBinding` and `PublishWalletRequest`, set and cleared, byte for byte in both runtimes | `sidecar/src/requests/fixtures.test.ts`, `RequestProtocolFixturesTest` |
| The sidecar's store | Publishing, clearing, replacing, and republishing the same binding; a malformed wallet or missing network; an unknown connection; cancelling the PENDING requests a new binding no longer fits, and leaving acks alone; `bound_at` stamped by the sidecar; each connection's binding kept to itself | `sidecar/src/storage/request-store.test.ts` |
| The sidecar's endpoints | `PublishWallet` for the caller's own connection only, and `vault_get_address` before, during, and after a wallet is connected | `sidecar/src/requests/endpoints.test.ts` |
| Roles | `RequestService.PublishWallet` and `vault_get_address` against every credential | `sidecar/src/pairing/roles.test.ts` |
| Migration 3 | An upgraded v1 database has no binding, and reaches the current schema version | `sidecar/src/storage/database.test.ts` |
| The agent's side | `pnpm agent address` before and after the phone publishes, and the tool list | `test-agent/src/cli.test.ts` |
| The wallet boundary | Success, no wallet installed, the owner declining, an expired authorization, an unsupported network, and a changed address or network, all through `FakeWalletAdapter` | `WalletRepositoryTest`, `WalletViewModelTest` |
| The phone's storage | The selection read back, the authorization encrypted, another key that can't open it, damaged files, and clearing both together | `WalletStoreTest` |
| The screens | The network choice, the address and network, each problem, the unpublished connections, and the disabled controls | `WalletScreenTest`, `WalletActivityTest` |
| The stage boundary | Storage only in `connections/storage/` and `wallet/storage/`; no wallet-key API anywhere; the MWA client on the classpath on purpose and Seed Vault's SDK off it; nothing backed up | `StageBoundaryTest`, `sidecar/src/stage-boundary.test.ts` |

### What the automated checks deliberately can't show

Every wallet outcome above comes from `FakeWalletAdapter`. No test starts a real wallet app: Mobile Wallet Adapter needs an installed wallet and a real activity association, so the wallet's own behaviour is the owner's check below. In particular, **which networks the installed wallet serves is a property of that wallet**, and this repository must not assume Seed Vault Wallet exposes a devnet switch.

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
| 15 | Check that no secret left the phone: `sqlite3 sidecar/data/sidecar.db "SELECT wallet_address, wallet_network FROM connections"` | The address and network only. There is no column, and no value anywhere in the database, that holds a seed phrase, a private key, or the wallet's authorization token. |

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
| Real connect and disconnect on the Seeker with Seed Vault Wallet | NOT RUN: no device was attached. The steps are [the owner's checks](#the-owners-checks-on-the-seeker) above. |
| Which networks Seed Vault Wallet serves on the Seeker | NOT RUN: it can only be determined on the device. Step 9 above records it. The app makes no assumption: it offers all three and reports what the wallet says. |
