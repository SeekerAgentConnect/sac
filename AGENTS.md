# AGENTS.md

Rules for coding agents working in this repository. The product plan is in `RFC.md`, and commands and milestone status are in `README.md`.

## Stage boundaries

- **Current stage: Stage 3, the wallet.** Stage 1, the wallet-free hello world, is accepted. Stage 2 is complete in code: SAW-009 defined the durable request contract (`docs/protocol.md`), SAW-010 added the sidecar's SQLite request queue, SAW-011 added pairing and separate roles (`docs/security.md`), SAW-012 added the phone's connections (`docs/guides/pairing.md`), SAW-013 added the pending inbox (`docs/guides/pending-requests.md`), and SAW-014 validated it with `pnpm test:queue` and `Stage2AcceptanceTest` (`docs/testing/stage-2.md`); the owner's checks on the Seeker close it. SAW-015 opened Stage 3 with Mobile Wallet Adapter and the wallet binding (`docs/guides/wallet-setup.md`, `docs/testing/stage-3.md`), and SAW-016 added manual message signing (`docs/guides/message-signing.md`). Until the task that adds each capability lands, the limits below still hold.
- **Out of scope until a later stage:**
  - wallet keys, and sending. SAW-015 lifted the wallet limit for the Mobile Wallet Adapter client alone: the app connects the wallet the owner already has and reads the address it selected. SAW-016 lifted signing for messages only: the owner's wallet signs bytes the owner reviewed and approved by hand, and the sidecar verifies the signature without ever making one. The app still creates no wallet, holds no key, builds no transaction, and sends nothing to the network.
  - storage on the phone beyond its connections, the owner's answers, and the wallet the owner selected
  - policies
  - OAuth
  - Docker deployment
  - background services
  - push notifications
- **Storage stays in one place.** The sidecar stores durable requests in SQLite, and only through `sidecar/src/storage/`. The live diagnostic stays in memory, and nothing re-executes a request after a restart. The phone stores its connections and the owner's answers only through `connections/storage/`, and the wallet it selected only through `wallet/storage/`: metadata, answers, and the wallet selection in `filesDir`, and credentials and the wallet's authorization token, encrypted under an Android Keystore key, in `noBackupFilesDir`. Nothing on the phone is backed up. The inbox is fetched when the app opens or comes back to the foreground, when a connection is opened, or when the owner refreshes. Nothing answers a request but the owner.
- **Roles stay separate.** The agent's MCP token never opens a phone RPC, and no MCP tool pairs, prepares, approves, submits a result, or revokes. Tokens and credentials never reach a log. Every new RPC or tool joins the matrix in `sidecar/src/pairing/roles.test.ts`.
- **Demo tools stay opt-in.** A tool that exists only to exercise the workflow, such as `vault_request_ack`, is served only with `MCP_DEMO_TOOLS=true`.
- **`StageBoundaryTest` (Android) and `sidecar/src/stage-boundary.test.ts` enforce these limits:**
  - no wallet library but the Mobile Wallet Adapter client (SAW-015); Seed Vault's own SDK stays out, since SAW-016 signs through Mobile Wallet Adapter
  - no signing or key creation in the sidecar: it verifies signatures and nothing more (SAW-016)
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
