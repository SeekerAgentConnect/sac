# seeker-vault

seeker-vault is an Android app for the Solana Seeker that acts as a control center for requests from external AI agents. The repository also contains the self-hosted server software the app talks to. Agents propose actions over MCP. The owner reviews each request on the phone and approves it through the wallet. The full plan is in [`RFC.md`](RFC.md).

## Current milestone

**Stage 1: Hello world (Hermes → Seeker → OK → Hermes).** A real agent sends display-only text over MCP, and the Seeker shows it while the app is open. The user taps OK, and the agent receives the acknowledgement. Stage 1 has no wallet, keys, queue, persistence, policies, QR pairing, OAuth, Docker, or background service.

| Task | Status |
| --- | --- |
| SAW-001: Repository, toolchains, basic CI | Done. The empty Android app and the sidecar skeleton build and pass the checks. |
| SAW-002: Live-command protocol and generated clients | Done. `LiveCommandService`, generated TypeScript and Kotlin code, and cross-runtime fixtures; see [`docs/protocol.md`](docs/protocol.md). |
| SAW-003: Live MCP command bridge | Not started |
| SAW-004: Android hello-world screen | Not started |
| SAW-005: MCP test client | Not started |
| SAW-006: MacBook → Seeker build and run guide | Not started |
| SAW-007: Real Hermes connection | Not started |
| SAW-008: Stage 1 acceptance gate | Not started |

## Repository structure

| Path | Contents |
| --- | --- |
| `android/` | Kotlin/Compose/Material 3 app with one `app` module, plus the Gradle wrapper and version catalog |
| `sidecar/` | TypeScript/Node sidecar. It validates the Stage 1 configuration and holds the live-command rules (`src/live`) and the generated protocol code (`src/gen`). The Connect API and the MCP endpoint land in SAW-003. |
| `proto/` | Protobuf contract (a Buf module) and cross-runtime fixtures in `proto/fixtures`; see [`docs/protocol.md`](docs/protocol.md) |
| `scripts/` | `generate.mjs`, which backs `pnpm generate` and `pnpm check:generated` |
| `test-agent/` | Minimal MCP test client, landing in SAW-005 |
| `gateway/` | Docker Compose, TLS, and OAuth gateway configuration, landing in Stage 7 |
| `docs/` | Development docs, guides, testing notes, and the changelog |
| `.github/workflows/ci.yml` | CI for pull requests and pushes |

## Prerequisites

Tested on a MacBook with macOS 26.5.2 on Apple silicon. Exact versions and setup details are in [`docs/development/toolchain.md`](docs/development/toolchain.md).

- **Node.js 24.21.0** (`.nvmrc`), for example through nvm
- **pnpm 9.7 or newer.** It switches to the pinned 12.3.4 from `package.json` automatically.
- **Android SDK Platform 37.0 and Build-Tools 36.0.0**, from Android Studio Quail 4 or newer or from the command-line tools
- **A JDK 17 or newer to launch Gradle.** Android Studio's bundled one works. Gradle downloads the pinned Temurin 21 for the build itself.

## Bootstrap and build

```bash
nvm install                      # Node.js from .nvmrc
corepack enable pnpm             # or: npm install --global pnpm
pnpm install --frozen-lockfile
pnpm check                       # formatting, lint, type checks, tests
pnpm check:android               # Kotlin formatting, unit tests, Android lint, debug APK
(cd android && ./gradlew :app:assembleDebug)
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. The application ID is `io.github.brrenat.seekervault`.

## Commands

| Command | What it does | Status |
| --- | --- | --- |
| `pnpm install --frozen-lockfile` | Installs exactly what the committed lockfile specifies | Works |
| `pnpm check` | Runs Prettier, `buf format`, ESLint, `buf lint`, TypeScript type checks, and the sidecar tests without changing any files | Works |
| `pnpm check:android` | Runs Spotless (ktfmt), Android unit tests, Android lint, and a debug APK build | Works |
| `pnpm build` | Compiles the sidecar to `sidecar/dist` | Works |
| `pnpm dev:sidecar` | Loads `.env`, validates the Stage 1 configuration, and reports the result | Works for configuration only. Endpoints land in SAW-003. |
| `pnpm generate` | Regenerates the TypeScript and Kotlin protocol code and the binary fixtures from `proto/`; needs network access | Works |
| `pnpm check:generated` | Fails if the committed generated code or fixtures differ from a fresh generation; changes no files | Works |
| `pnpm agent ...` | Runs the MCP test-agent CLI | Not implemented until SAW-005; exits with an error |
| `pnpm test:hello` | Runs the live-bridge integration tests | Not implemented until SAW-008; exits with an error |
| `pnpm format`, `pnpm format:android` | Apply Prettier and `buf format`, and ktfmt for Kotlin | Works |

## Development configuration

```bash
cp .env.example .env
openssl rand -hex 32   # run twice: once for MCP_TOKEN, once for PHONE_TOKEN
```

- `.env` is git-ignored.
- Variables already set in the environment take precedence over `.env`.
- The sidecar rejects placeholder or short tokens, identical MCP and phone tokens, and hosts that are not loopback addresses.
- Endpoint authentication with these tokens arrives in SAW-003.

## CI

`.github/workflows/ci.yml` runs on pull requests and on pushes to `master` and `develop`:

- **Node:** `pnpm install --frozen-lockfile`, then `pnpm check`, `pnpm check:generated`, and `pnpm build`
- **Android:** `pnpm check:android` on Temurin 21

The workflow has read-only repository permissions and never commits.

## License

See [`LICENSE`](LICENSE).
