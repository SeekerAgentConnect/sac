# AGENTS.md

Rules for coding agents working in this repository. The product plan is in `RFC.md`, and commands and milestone status are in `README.md`.

## Stage boundaries

- **Current stage: Stage 2, persistent requests and connections.** Stage 1, the wallet-free hello world, is accepted. SAW-009 defined the durable request contract (`docs/protocol.md`), and SAW-010 added the sidecar's SQLite request queue. Pairing, and the phone's connections and inbox, arrive in SAW-011 to SAW-013. Until the task that adds each one lands, the limits below still hold.
- **Out of scope until a later stage:**
  - wallet SDKs, keys, or signing
  - storage on the phone (SAW-012, SAW-013)
  - policies
  - QR pairing (SAW-011)
  - OAuth
  - Docker deployment
  - background services
  - push notifications
- **Storage stays in one place.** The sidecar stores durable requests in SQLite, and only through `sidecar/src/storage/`. The live diagnostic stays in memory, and nothing re-executes a request after a restart.
- **`StageOneBoundaryTest` (Android) and `sidecar/src/stage-boundary.test.ts` enforce these limits:**
  - no wallet library
  - no key generation
  - no storage outside the sidecar's `src/storage/`
  - nothing stored on the phone
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
