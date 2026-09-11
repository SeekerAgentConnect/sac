# seeker-vault

seeker-vault is an Android app for the Solana Seeker that acts as a control center for requests from external AI agents. The repository also contains the self-hosted server software the app talks to. Agents propose actions over MCP. The owner reviews each request on the phone and approves it through the wallet. The full plan is in [`RFC.md`](RFC.md).

## Current milestone

**Stage 1: Hello world (Hermes → Seeker → OK → Hermes).** A real agent sends display-only text over MCP, and the Seeker shows it while the app is open. The user taps OK, and the agent receives the acknowledgement. Stage 1 has no wallet, keys, queue, persistence, policies, QR pairing, OAuth, Docker, or background service.

**Status:** every automated Stage 1 check passes, including the round trip on an emulator in CI. Stage 1 is accepted once the owner records two checks in [`docs/testing/stage-1.md`](docs/testing/stage-1.md): the round trip on the physical Seeker, and the real Hermes → Seeker → OK → Hermes run. Both are NOT RUN.

| Task | Status |
| --- | --- |
| SAW-001: Repository, toolchains, basic CI | Done. The empty Android app and the sidecar skeleton build and pass the checks. |
| SAW-002: Live-command protocol and generated clients | Done. `LiveCommandService`, generated TypeScript and Kotlin code, and cross-runtime fixtures; see [`docs/protocol.md`](docs/protocol.md). |
| SAW-003: Live MCP command bridge | Done. `/mcp` with `vault_display_command`, the phone's Connect API, and `/healthz`; see [`docs/development/sidecar.md`](docs/development/sidecar.md). |
| SAW-004: Android hello-world screen | Done. A stock Material 3 live-test screen: connect, the received text, and a one-tap OK, with lifecycle handling; see [`docs/development/android.md`](docs/development/android.md). The physical Seeker check is NOT RUN. |
| SAW-005: MCP test client | Done. `pnpm agent hello "Hello Seeker"` calls the tool over MCP and prints the acknowledgement; see [`test-agent/README.md`](test-agent/README.md). The physical Seeker check is NOT RUN. |
| SAW-006: MacBook → Seeker build and run guide | Done. A quickstart from a fresh MacBook to an acknowledged "Hello Seeker", and a troubleshooting page; see [`docs/guides/macbook-seeker-quickstart.md`](docs/guides/macbook-seeker-quickstart.md). The Seeker and Android Studio checks are NOT RUN. |
| SAW-007: Real Hermes connection | Done. A Hermes `mcp_servers` entry to merge ([`examples/hermes.config.yaml`](examples/hermes.config.yaml)) and a guide for Hermes on the Mac or on a VPS through an SSH reverse tunnel; see [`docs/integrations/hermes.md`](docs/integrations/hermes.md). Hermes's own MCP client passed against the sidecar. The owner's real Hermes session with the Seeker is NOT RUN. |
| SAW-008: Stage 1 acceptance gate | Done. `pnpm test:hello` runs the acceptance suite, and `pnpm test:hello --device` runs the round trip on a device or emulator. CI runs both, the device one on an emulator. Stage boundary guards run on every check. See [`docs/testing/stage-1.md`](docs/testing/stage-1.md). The physical Seeker and the owner's Hermes round trip are NOT RUN. |

## Repository structure

| Path | Contents |
| --- | --- |
| `android/` | Kotlin/Compose/Material 3 app with one `app` module: the live-test screen and its Connect client; see [`docs/development/android.md`](docs/development/android.md) |
| `sidecar/` | TypeScript/Node sidecar: the MCP endpoint `/mcp`, the phone's Connect API, and `/healthz`, on loopback, around an in-memory live-command bridge; see [`docs/development/sidecar.md`](docs/development/sidecar.md) |
| `proto/` | Protobuf contract (a Buf module) and cross-runtime fixtures in `proto/fixtures`; see [`docs/protocol.md`](docs/protocol.md) |
| `scripts/` | `generate.mjs`, which backs `pnpm generate` and `pnpm check:generated` |
| `test-agent/` | Minimal MCP test client (`pnpm agent`). It uses the same MCP interface as Hermes, with no LLM; see [`test-agent/README.md`](test-agent/README.md). |
| `gateway/` | Docker Compose, TLS, and OAuth gateway configuration, landing in Stage 7 |
| `examples/` | Configuration to merge into other tools: `hermes.config.yaml`; see [`docs/integrations/hermes.md`](docs/integrations/hermes.md) |
| `docs/` | Development docs, guides, testing notes, and the changelog |
| `.github/workflows/ci.yml` | CI for pull requests and pushes |

## Quickstart

To go from a fresh MacBook to "Hello Seeker" on the phone, follow [`docs/guides/macbook-seeker-quickstart.md`](docs/guides/macbook-seeker-quickstart.md). It assumes no Android experience. If a step fails, see [`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md).

## Prerequisites

Tested on a MacBook with macOS 26.5.2 on Apple silicon. Exact versions and setup details are in [`docs/development/toolchain.md`](docs/development/toolchain.md).

- **Node.js 24.21.0** (`.nvmrc`), for example through nvm. pnpm refuses to run on any other version.
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
| `pnpm check:android` | Runs Spotless (ktfmt), Android unit tests, and Android lint, and builds the debug APK and the instrumentation test APK | Works |
| `pnpm build` | Compiles the sidecar to `sidecar/dist` | Works |
| `pnpm dev:sidecar` | Starts the sidecar with the `.env` configuration: `/mcp`, the phone API, and `/healthz`. Ctrl+C stops it. | Works |
| `pnpm generate` | Regenerates the TypeScript and Kotlin protocol code and the binary fixtures from `proto/`; needs network access | Works |
| `pnpm check:generated` | Fails if the committed generated code or fixtures differ from a fresh generation; changes no files | Works |
| `pnpm agent hello [text]` | Shows text on the phone through MCP and prints the acknowledgement. OFFLINE, BUSY, TIMEOUT, and connection errors each get their own exit code. | Works |
| `pnpm test:hello` | Runs the Stage 1 acceptance suite on a simulated device: the real CLI, the sidecar as a separate process, and a test client as the phone. With `--device`, it runs the round trip on the attached device or emulator instead: the app's UI test taps OK while the CLI sends over MCP. See [`docs/testing/stage-1.md`](docs/testing/stage-1.md). | Works; `--device` needs a device or an emulator |
| `pnpm format`, `pnpm format:android` | Apply Prettier and `buf format`, and ktfmt for Kotlin | Works |

## Development configuration

```bash
cp .env.example .env
openssl rand -hex 32   # run twice: once for MCP_TOKEN, once for PHONE_TOKEN
```

- `.env` is git-ignored.
- Variables already set in the environment take precedence over `.env`.
- The sidecar rejects placeholder or short tokens, identical MCP and phone tokens, and hosts that are not loopback addresses.
- Agents send `MCP_TOKEN` and the phone sends `PHONE_TOKEN`, each as `Authorization: Bearer <token>`; see [`docs/development/sidecar.md`](docs/development/sidecar.md).

## CI

`.github/workflows/ci.yml` runs on pull requests and on pushes to `master` and `develop`:

- **Node:** `pnpm install --frozen-lockfile`, then `pnpm check`, `pnpm test:hello`, `pnpm check:generated`, and `pnpm build`
- **Android:** `pnpm check:android` on Temurin 21
- **Emulator:** `pnpm test:hello --device` on an Android 16 (API 36) emulator. An emulator run never counts as the physical Seeker check.

The workflow has read-only repository permissions and never commits.

## License

See [`LICENSE`](LICENSE).
