# AGENTS.md

Rules for coding agents working in this repository. The product plan is in `RFC.md`, and commands and milestone status are in `README.md`.

## Stage boundaries

- **Current stage: Stage 4, transfers.** SAW-019 opened it on the sidecar: an agent asks with `vault_transfer`, and `RequestService.PrepareRequest` builds one fresh unsigned transaction from the chain for the owner to review (`docs/protocol.md#transfers-saw-019`, `docs/guides/transfers.md`). The sidecar reads the chain and builds bytes; it holds no key, signs nothing, and has no way to send. SAW-020 added the phone's own inspection of those bytes (`docs/security.md#inspecting-a-transfer`, `docs/testing/transaction-fixtures.md`): it decodes the transaction itself, checks it against the stored request and the selected wallet, and refuses anything it can't account for byte for byte. SAW-021 added the owner's manual approval (`docs/architecture.md#approval-binding`, `docs/guides/transfers.md`): only a transaction the phone read whole can be approved, the approval binds to the preparation's version and content hash and to the wallet and network, the sidecar accepts it before any wallet opens, and the wallet is handed the exact bytes that were reviewed and signs and sends them. Confirmation (SAW-022) is not written yet, so a sent transaction stops at SUBMITTED, and an outcome this phone never learned stops at UNKNOWN.
- **Stage 3, the wallet.** Stage 1, the wallet-free hello world, is accepted. Stage 2 is complete in code: SAW-009 defined the durable request contract (`docs/protocol.md`), SAW-010 added the sidecar's SQLite request queue, SAW-011 added pairing and separate roles (`docs/security.md`), SAW-012 added the phone's connections (`docs/guides/pairing.md`), SAW-013 added the pending inbox (`docs/guides/pending-requests.md`), and SAW-014 validated it with `pnpm test:queue` and `Stage2AcceptanceTest` (`docs/testing/stage-2.md`); the owner's checks on the Seeker close it. SAW-015 opened Stage 3 with Mobile Wallet Adapter and the wallet binding (`docs/guides/wallet-setup.md`, `docs/testing/stage-3.md`), SAW-016 added manual message signing (`docs/guides/message-signing.md`), SAW-017 made one wallet interaction produce one reported outcome across the Android lifecycle (`docs/testing/wallet-lifecycle.md`), and SAW-018 wrote the owner's walkthrough and the device checks that close the stage (`docs/guides/wallet-setup.md`, `docs/testing/stage-3.md`). The stage is accepted only when the owner's own Seeker signs by hand; a mock or an emulator never counts. Until the task that adds each capability lands, the limits below still hold.
- **Out of scope until a later stage:**
  - wallet keys, and sending. SAW-015 lifted the wallet limit for the Mobile Wallet Adapter client alone: the app connects the wallet the owner already has and reads the address it selected. SAW-016 lifted signing for messages only: the owner's wallet signs bytes the owner reviewed and approved by hand, and the sidecar verifies the signature without ever making one. SAW-019 lifted the sidecar's chain limit for reading alone: it may read a configured Solana RPC endpoint (`SOLANA_RPC_URL`) to build an unsigned transfer, and may not sign, send, or simulate one. SAW-021 lifted signing and sending for transfers, and only through the wallet: the app hands the owner's wallet a transaction they approved by hand, and the wallet signs and submits it. The app still creates no wallet, holds no key, builds no transaction, makes no signature of its own, and has no chain endpoint to broadcast through.
  - storage on the phone beyond its connections, the owner's answers, and the wallet the owner selected
  - policies
  - OAuth
  - Docker deployment
  - background services
  - push notifications
- **Chain access stays in one place.** Only `sidecar/src/solana/` imports the chain client, and only read-only JSON-RPC methods exist there. A `SOLANA_RPC_URL` can carry an API key, so it never reaches a log or an error message. Without one the sidecar serves no transfer tool, rather than accepting a request it couldn't prepare.
- **Storage stays in one place.** The sidecar stores durable requests in SQLite, and only through `sidecar/src/storage/`. The live diagnostic stays in memory, and nothing re-executes a request after a restart. The phone stores its connections and the owner's answers only through `connections/storage/`, and the wallet it selected only through `wallet/storage/`: metadata, answers, and the wallet selection in `filesDir`, and credentials and the wallet's authorization token, encrypted under an Android Keystore key, in `noBackupFilesDir`. Nothing on the phone is backed up. The inbox is fetched when the app opens or comes back to the foreground, when a connection is opened, or when the owner refreshes. Nothing answers a request but the owner.
- **Roles stay separate.** The agent's MCP token never opens a phone RPC, and no MCP tool pairs, prepares, approves, submits a result, or revokes. Tokens and credentials never reach a log. Every new RPC or tool joins the matrix in `sidecar/src/pairing/roles.test.ts`.
- **Demo tools stay opt-in.** A tool that exists only to exercise the workflow, such as `vault_request_ack`, is served only with `MCP_DEMO_TOOLS=true`.
- **`StageBoundaryTest` (Android) and `sidecar/src/stage-boundary.test.ts` enforce these limits:**
  - no wallet library but the Mobile Wallet Adapter client on the phone (SAW-015) and `@solana/web3.js` in the sidecar's `src/solana/` (SAW-019); Seed Vault's own SDK stays out, since SAW-016 signs through Mobile Wallet Adapter
  - no signing or key creation in the sidecar: it verifies signatures, and reads the chain to build unsigned bytes (SAW-016, SAW-019)
  - no wallet call while delivering a result: the wallet is asked once, between the owner's approval and the outcome, and every retry only re-sends what is already stored (SAW-017)
  - nothing that spends without the owner: no swap tool, no broadcast or simulation from the sidecar, no key of an agent's own, and no biometric or device-credential prompt of the app's own (SAW-018). SAW-021 lifted one line and one file: `wallet/MwaWalletAdapter.kt` may call `signAndSendTransactions`, handing the owner's wallet bytes the sidecar built and this phone read. The Android guard still forbids building or signing a transaction on the phone and reaching a chain from it, and it fails if `signAndSendTransactions` appears in any other file. The sidecar's guard names every tool it serves and every chain method it calls (SAW-019).
  - reading a transaction on the phone stays in `transactions/` and only reads: it holds no key, signs nothing, and reaches no network. SAW-020 lifted the limit for that and nothing else, and the guard fails if the app starts using an SDK decoder instead (`docs/security.md#inspecting-a-transfer`).
  - the wallet is reached only after the sidecar has accepted the owner's approval, and only with the bytes stored with that approval. A transfer approval the sidecar didn't take is deleted rather than kept, and an outcome this phone never learned is UNKNOWN for a transfer and FAILED for a message (SAW-021, `docs/testing/wallet-lifecycle.md`).
  - no wallet keys; on the phone, a Keystore key only for the connection credentials and the wallet's authorization token
  - no storage outside the sidecar's `src/storage/` and the app's `connections/storage/` and `wallet/storage/`
  - no backups of the phone's data
  - no background components

  The stage that lifts a limit changes the guard on purpose.
- **Don't implement later tasks early.** A directory for a later component doesn't mean the component should be built yet.
- **Never fake success.** A command whose task hasn't landed must fail clearly, and a check must never pass without doing what it claims.
- **Live commands are display-only text.** They are never shell code or instructions to execute on the phone.

## UI

- Use stock Jetpack Compose and Material 3 components with default styling.
- No custom theme, colors, typography, illustrations, animations, or branding.

## Tests and checks

- **Before handing off,** run these:
  - `pnpm check`: formatting, lint, types, tests
  - `pnpm test:hello`: the Stage 1 acceptance suite
  - `pnpm test:queue`: the Stage 2 acceptance scenario
  - `pnpm check:android`: Kotlin formatting, unit tests, Android lint, and the debug and instrumentation APKs

  CI runs the same commands, plus `pnpm test:hello --device` on an emulator, and never commits.
- **Test every behavior change.** A deliberately broken test must make the relevant check fail.
- **Report physical-device checks as PASS, FAIL, or NOT RUN.** Mocks, emulators, and a successful APK build never count as a device pass.
- **Never commit secrets.** That covers credentials, tokens, `.env`, `local.properties`, keystores, real wallet keys, and machine-specific paths. Default checks must never spend mainnet funds.
- **Generated protocol code and fixtures come only from `pnpm generate`.** Don't edit them by hand. After changing `proto/`, run `pnpm generate` and commit the output. `pnpm check:generated`, which CI also runs, fails if the committed output is stale.

## Documentation

- **Update docs in the same change as the code:**
  - `README.md`: commands and milestone status
  - `docs/development/`: toolchain and component docs
  - `docs/guides/`: owner-facing walkthroughs
  - `docs/testing/`: test reports
  - `RFC.md`: when the scope changes
- **Record what actually ran:** the commands and their results, with the tool versions tested.
- **Keep version pins consistent** across `.nvmrc`, `package.json`, `pnpm-workspace.yaml`, `android/gradle/libs.versions.toml`, `buf.gen.yaml`, and `docs/development/toolchain.md`.
