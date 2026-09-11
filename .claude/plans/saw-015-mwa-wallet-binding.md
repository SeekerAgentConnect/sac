# SAW-015 — Integrate MWA and bind the selected wallet/network (SEE-23)

Stage 3's first task. The app connects the wallet the owner already has through Mobile Wallet
Adapter, shows the address and network it selected, and publishes that binding to every paired
sidecar so an agent can read it with `vault_get_address`. No keys are generated, nothing is signed,
and no funds move.

## Contract

- [x] `request.proto`: `WalletBinding { wallet, network, bound_at }` and
      `REQUEST_ERROR_WALLET_NOT_CONNECTED = 10`
- [x] `service.proto`: `RequestService.PublishWallet`, where an absent binding clears it
- [x] `pnpm generate`, and commit the TypeScript and Kotlin output
- [x] Fixtures for `WalletBinding` and `PublishWalletRequest`, in the TS `cases` list and the Kotlin
      `FIXTURES` list

## Sidecar

- [x] Migration 3: `wallet_address`, `wallet_network`, `wallet_bound_at_ms` on `connections`
- [x] `RequestStore.wallet()` and `RequestStore.publishWallet()`, which cancel the PENDING requests
      the new binding no longer fits
- [x] `RequestStore.create` refuses a wallet action with `WALLET_NOT_CONNECTED` or `WALLET_MISMATCH`
- [x] `export invalidBindingReason` from `requests/action.ts`
- [x] `phone-service.ts`: the `publishWallet` handler, for the caller's own connection only
- [x] `mcp-tools.ts`: `vault_get_address`, always served, read-only
- [x] `roles.test.ts`: the new RPC and the new tool
- [x] Tests: the store, the endpoints, and the migration

## Android

- [x] Pin `mobile-wallet-adapter-clientlib-ktx` in `libs.versions.toml` and depend on it
- [x] `wallet/WalletAdapter.kt`: the boundary, its result, and its failure kinds
- [x] `wallet/MwaWalletAdapter.kt`: MWA through `ActivityResultSender`, from `MainActivity`
- [x] `wallet/storage/WalletStore.kt`: the binding in `filesDir`, the authorization token encrypted
      in `noBackupFilesDir`
- [x] `wallet/WalletRepository.kt`: connect, disconnect, and publish to every usable connection
- [x] `ConnectionGateway.publishWallet` and its Connect implementation
- [x] `ConnectionRepository.publishWallet`, which also drops inbox requests the new binding doesn't fit
- [x] `wallet/WalletViewModel.kt`, `WalletScreen.kt`, `WalletText.kt`, `strings_wallet.xml`, and the
      route from Connections
- [x] `StageBoundaryTest`: allow the MWA client library and `wallet/storage/`, on purpose
- [x] Tests: a fake adapter covering success, no wallet, denial, an expired authorization, and a
      changed address or network; the screen; the repository; the activity

## Agent side

- [x] `pnpm agent address`

## Documentation

- [x] `docs/guides/wallet-setup.md`
- [x] `docs/architecture.md`: the wallet adapter boundary
- [x] `docs/protocol.md`: the new RPC, the new tool, and the new error
- [x] `docs/security.md`: what the wallet authorization is, and what never leaves the phone
- [x] `docs/development/android.md`, `docs/development/sidecar.md`
- [x] `docs/testing/stage-3.md`: the owner's device checks, recorded as NOT RUN
- [x] `README.md`, `AGENTS.md`, `RFC.md` if needed, `CODEBASE.md`, `docs/changelog/`

## Checks

- [x] `pnpm check`
- [x] `pnpm test:hello`
- [x] `pnpm test:queue`
- [x] `pnpm check:android`

## Review

Done on 2026-09-12, on branch `develop`.

### What changed from the plan

- **`PublishWalletResponse` returns the cancelled `RequestRef`s,** not just the stored binding. The phone takes those requests off its inbox without a second fetch.
- **The sidecar stamps `bound_at`,** and ignores what the phone sends. The `PublishWalletRequest` fixture leaves it unset, and the proto says so.
- **`RequestStore.create` stores a wallet action whose binding fits,** instead of also refusing it for the stage. No MCP tool creates one yet, so nothing an agent can call changed; the tools arrive with the later tasks.
- **`ConnectionRepository.publishWallet` is per connection,** and `WalletRepository` tracks which connections have heard the current binding. `publishWalletToAll` was dropped as dead code.
- **The Connections screen always shows the Wallet row,** including with no connections, so a wallet can be connected before pairing.
- **`StageBoundaryTest` gained a positive check** that the MWA client is on the classpath, so the "no library" check can't pass by finding nothing, and its forbidden-API list now covers `PrivateKey` and `SecretKeySpec`.

### Checks

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: 260/260 sidecar, 21/21 test agent |
| `pnpm check:generated` | PASS |
| `pnpm test:hello` | PASS: 9/9 |
| `pnpm test:queue` | PASS: 7/7 |
| `pnpm check:android` | PASS: Spotless, 245/245 unit tests, lint with no issues, both APKs |
| Real connect and disconnect on the Seeker | NOT RUN: no device attached. `docs/testing/stage-3.md` holds the owner's steps. |

### Left for later

- The tools that create wallet actions (`vault_sign_message` and the rest) and the phone's signing flow are the next Stage 3 task.
- Which networks Seed Vault Wallet serves on the Seeker can only be determined on the device; step 9 of the owner's checks records it.
