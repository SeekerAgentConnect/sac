# AGENTS.md

Rules for coding agents working in this repository. The product plan is in `RFC.md`, and commands and milestone status are in `README.md`.

## Stage boundaries

- **Current stage: Stage 1, a wallet-free hello world.** Hermes or the test agent sends display-only text over MCP. The Seeker shows it while the app is open, the user taps OK, and the agent gets the acknowledgement.
- **Out of scope until a later stage:**
  - wallet SDKs, keys, or signing
  - request queues or any persistence
  - policies
  - QR pairing
  - OAuth
  - Docker deployment
  - background services
  - push notifications
- **`StageOneBoundaryTest` (Android) and `sidecar/src/stage-boundary.test.ts` enforce this list:** no wallet library, no key generation, no stored commands, and no background components. The stage that adds one of these changes the guard on purpose.
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
