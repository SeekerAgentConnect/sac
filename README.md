# seeker-vault

seeker-vault is an Android app for the Solana Seeker that acts as a control center for requests from external AI agents. The repository also contains the self-hosted server software the app talks to. Agents propose actions over MCP. The owner reviews each request on the phone and approves it through the wallet. The full plan is in [`RFC.md`](RFC.md).

## Current milestone

**Stage 3: The wallet.** The app connects the wallet the owner already has, through Mobile Wallet Adapter, publishes the address and network it selected to every paired sidecar, and has that wallet sign messages the owner reviewed and approved by hand. A signature moves no funds: nothing spends yet.

**Status:** every Stage 3 task is written, and every automated check passes. The stage is **not accepted**: it needs the owner's own Seeker with Seed Vault Wallet, and those checks are NOT RUN. See [`docs/testing/stage-3.md`](docs/testing/stage-3.md#the-owners-checks-on-the-seeker) and [`docs/testing/wallet-lifecycle.md`](docs/testing/wallet-lifecycle.md#the-owners-checks-on-the-seeker).

| Task | Status |
| --- | --- |
| SAW-018: the wallet setup guide and the Stage 3 device checks | Done in writing. [`docs/guides/wallet-setup.md`](docs/guides/wallet-setup.md) walks the whole round trip — open Seed Vault Wallet, connect, check the address and network, sign the example message, disconnect — and never asks for a seed phrase or a private key. [`docs/integrations/hermes.md`](docs/integrations/hermes.md#5-sign-a-message-with-your-wallet) gives Hermes the same trip, with the commands for creating a request, reading the result, and verifying the signature. New boundary tests confirm the stage needs no funds, swaps, agent keys, or biometrics of the app's own. The owner's device checks, including the Hermes round trip and both rejection paths, are NOT RUN. |
| SAW-017: the wallet lifecycle and reliable result delivery | Done. One trip to the wallet produces one reported outcome, whatever Android does in between: a rotation or a trip out of the app doesn't dismiss the request or ask the wallet twice, what the wallet did is stored on the phone before it's sent and sent again until the sidecar takes it, a retry never re-opens the wallet, a repeated result returns the same terminal request, and an answer this phone never received is reported as unresolved rather than as a success. See [`docs/testing/wallet-lifecycle.md`](docs/testing/wallet-lifecycle.md). |
| SAW-016: manual message signing | Done. An agent asks with `vault_sign_message`; the owner sees the complete message on their phone, with every invisible character marked, together with the wallet that would sign it, and taps **Approve and sign**. Only then is the wallet opened. The sidecar verifies the signature against the request's wallet and its own copy of the message, and hands the agent the signature, the address, and the exact signed bytes. `vault_get_capabilities` says what a sidecar serves. A signature moves no funds. See [`docs/guides/message-signing.md`](docs/guides/message-signing.md). |
| SAW-015: Mobile Wallet Adapter and the wallet binding | Done. The **Wallet** screen connects the installed wallet on the network the owner picks, shows the address it selected, and disconnects again. The app creates no wallet and holds no key: the wallet's authorization stays on the phone, encrypted and never backed up, and only the public address and network are published, to each paired sidecar. Agents read them with `vault_get_address`, or get `WALLET_NOT_CONNECTED`. Changing or disconnecting the wallet cancels the pending requests it no longer fits. See [`docs/guides/wallet-setup.md`](docs/guides/wallet-setup.md) and [`docs/testing/stage-3.md`](docs/testing/stage-3.md). |

## Stage 2

**Stage 2: Persistent requests and connections.** The sidecar stores agents' requests, which survive restarts, and the phone fetches them when the app opens. Several self-hosted servers can be connected. There's still no wallet: a queued acknowledgement tests the workflow.

**Status:** every Stage 2 task is done, and the automated acceptance scenario passes from both sides: `pnpm test:queue` and the app's `Stage2AcceptanceTest`. The owner's checks on the physical Seeker are NOT RUN; see [`docs/testing/stage-2.md`](docs/testing/stage-2.md#acceptance-report-saw-014).

| Task | Status |
| --- | --- |
| SAW-009: Durable request contract and lifecycle | Done. `seekervault.request.v1` defines the phone's `PairingService` and `RequestService`, the actions, the lifecycle and its transitions, idempotency, and the errors. [`docs/protocol.md`](docs/protocol.md#stage-2-durable-requests) specifies all of that plus the agent's MCP tools, and [`docs/architecture.md`](docs/architecture.md) shows how the parts fit together. The rules exist as tested pure code in `sidecar/src/requests/`, with fixtures checked in both runtimes. SAW-010 serves it. |
| SAW-010: Persistent sidecar queue and async MCP lifecycle | Done. The sidecar stores requests in SQLite (`DATABASE_PATH`, default `sidecar/data/sidecar.db`), with migrations and durable commits, and creation and results are idempotent. Agents call `vault_request_ack`, `vault_get_request`, and `vault_cancel_request`, which answer at once. The phone's `RequestService` lists, reads, and answers requests. Requests survive restarts, and nothing runs on its own. See [`docs/development/sidecar.md`](docs/development/sidecar.md#storage-and-lifecycle). |
| SAW-011: Secure pairing and separate access roles | Done. `pnpm pair` shows a one-use pairing code, as a QR code and as text, and the phone exchanges it for its own credential. Only that credential opens the phone's `RequestService`. The agent's MCP token opens `/mcp` only, and `PHONE_TOKEN` stays with the Stage 1 live-test screen. One phone is paired at a time, and `pnpm pair revoke` revokes it. A phone on another network reaches the loopback sidecar through a trusted TLS endpoint, such as Tailscale Serve. See [`docs/security.md`](docs/security.md). |
| SAW-012: Android pairing and multiple connections | Done. The app opens on **Connections**. It pairs by scanning the `pnpm pair` QR code or by entering the code, and it shows the server for the owner to confirm first. Each sidecar's connection is kept apart, with its own name, address, and credential. The credential is encrypted under an Android Keystore key and never backed up. **Connection details** refreshes, renames, and disconnects, which revokes the connection on the sidecar. See [`docs/guides/pairing.md`](docs/guides/pairing.md). |
| SAW-013: Pending inbox and queued acknowledgements | Done. The phone fetches pending requests when the app opens or comes back to the foreground, when a connection is opened, and on **Refresh**. There's no push or background service. **Pending requests** shows each request's source, action, age, and expiry, with empty, offline, and error states. **Request details** acknowledges or rejects a queued acknowledgement. The answer is stored before it's sent, and sent again after a failure until the sidecar confirms it. A reopened request shows its outcome. `pnpm agent ack`, `get`, and `cancel` drive the flow from the agent's side. See [`docs/guides/pending-requests.md`](docs/guides/pending-requests.md) and [`docs/testing/stage-2.md`](docs/testing/stage-2.md). |
| SAW-014: Stage 2 validation | Done. `pnpm test:queue` runs the acceptance scenario with two sidecars from the agent's side, and `Stage2AcceptanceTest` runs it from the app's. It covers restarts of both, expiry, rejection, a revoked pairing, isolation between connections, and a live diagnostic that still stores nothing. CI runs both. `vault_request_ack` is now a demo tool, served only with `MCP_DEMO_TOOLS=true`. Hermes's example config allows the durable tools, and [`docs/integrations/hermes.md`](docs/integrations/hermes.md#4-queued-requests-create-now-read-the-result-later) creates a request and checks it later. The owner's checks on the physical Seeker are NOT RUN. |

## Stage 1

**Stage 1: Hello world (Hermes → Seeker → OK → Hermes).** A real agent sends display-only text over MCP, and the Seeker shows it while the app is open. The user taps OK, and the agent receives the acknowledgement. Stage 1 has no wallet, keys, queue, persistence, policies, QR pairing, OAuth, Docker, or background service.

**Status:** Stage 1 is accepted. Every automated check passes, including the round trip on an emulator in CI. On 2026-09-11, the owner ran the real Hermes → Seeker → OK → Hermes round trip on the physical Seeker and reported it passed; see [`docs/testing/stage-1.md`](docs/testing/stage-1.md).

| Task | Status |
| --- | --- |
| SAW-001: Repository, toolchains, basic CI | Done. The empty Android app and the sidecar skeleton build and pass the checks. |
| SAW-002: Live-command protocol and generated clients | Done. `LiveCommandService`, generated TypeScript and Kotlin code, and cross-runtime fixtures; see [`docs/protocol.md`](docs/protocol.md). |
| SAW-003: Live MCP command bridge | Done. `/mcp` with `vault_display_command`, the phone's Connect API, and `/healthz`; see [`docs/development/sidecar.md`](docs/development/sidecar.md). |
| SAW-004: Android hello-world screen | Done. A stock Material 3 live-test screen: connect, the received text, and a one-tap OK, with lifecycle handling; see [`docs/development/android.md`](docs/development/android.md). The owner's check on the physical Seeker passed on 2026-09-11. |
| SAW-005: MCP test client | Done. `pnpm agent hello "Hello Seeker"` calls the tool over MCP and prints the acknowledgement; see [`test-agent/README.md`](test-agent/README.md). The owner's check on the physical Seeker passed on 2026-09-11. |
| SAW-006: MacBook → Seeker build and run guide | Done. A quickstart from a fresh MacBook to an acknowledged "Hello Seeker", and a troubleshooting page; see [`docs/guides/macbook-seeker-quickstart.md`](docs/guides/macbook-seeker-quickstart.md). The owner followed it on their Seeker on 2026-09-11. The Android Studio run isn't recorded. |
| SAW-007: Real Hermes connection | Done. A Hermes `mcp_servers` entry to merge ([`examples/hermes.config.yaml`](examples/hermes.config.yaml)) and a guide for Hermes on the Mac or on a VPS through an SSH reverse tunnel; see [`docs/integrations/hermes.md`](docs/integrations/hermes.md). Hermes's own MCP client passed against the sidecar. The owner's real Hermes session with the Seeker passed on 2026-09-11. |
| SAW-008: Stage 1 acceptance gate | Done. `pnpm test:hello` runs the acceptance suite, and `pnpm test:hello --device` runs the round trip on a device or emulator. CI runs both, the device one on an emulator. Stage boundary guards run on every check. See [`docs/testing/stage-1.md`](docs/testing/stage-1.md). The owner's Hermes → Seeker → OK → Hermes round trip passed on 2026-09-11. `pnpm test:hello --device` hasn't run on the Seeker yet. |

## Repository structure

| Path | Contents |
| --- | --- |
| `android/` | Kotlin/Compose/Material 3 app with one `app` module: the connection screens (pairing, details) and the live-test screen, with their Connect clients; see [`docs/development/android.md`](docs/development/android.md) |
| `sidecar/` | TypeScript/Node sidecar: the MCP endpoint `/mcp`, the phone's Connect API, and `/healthz`, on loopback, around an in-memory live-command bridge; see [`docs/development/sidecar.md`](docs/development/sidecar.md) |
| `proto/` | Protobuf contract (a Buf module) and cross-runtime fixtures in `proto/fixtures`; see [`docs/protocol.md`](docs/protocol.md) |
| `scripts/` | `generate.mjs`, which backs `pnpm generate` and `pnpm check:generated` |
| `test-agent/` | Minimal MCP test client (`pnpm agent`). It uses the same MCP interface as Hermes, with no LLM; see [`test-agent/README.md`](test-agent/README.md). |
| `gateway/` | Docker Compose, TLS, and OAuth gateway configuration, landing in Stage 7 |
| `examples/` | Configuration to merge into other tools: `hermes.config.yaml`; see [`docs/integrations/hermes.md`](docs/integrations/hermes.md) |
| `docs/` | The architecture and the protocol, plus development docs, guides, testing notes, and the changelog |
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
| `pnpm pair [status \| revoke]` | Shows a one-use pairing code for the phone, as a QR code and as text. `status` shows the paired phone, and `revoke` revokes it. See [`docs/development/sidecar.md`](docs/development/sidecar.md#pairing-a-phone), and for the app, [`docs/guides/pairing.md`](docs/guides/pairing.md). | Works |
| `pnpm generate` | Regenerates the TypeScript and Kotlin protocol code and the binary fixtures from `proto/`; needs network access | Works |
| `pnpm check:generated` | Fails if the committed generated code or fixtures differ from a fresh generation; changes no files | Works |
| `pnpm agent hello [text]` | Shows text on the phone through MCP and prints the acknowledgement. OFFLINE, BUSY, TIMEOUT, and connection errors each get their own exit code. | Works |
| `pnpm agent ack <text>`, `get <id>`, `cancel <id>` | Queues an acknowledgement for the owner, reads a request back, or withdraws one, through the durable MCP tools. Each prints the request as JSON. See [`test-agent/README.md`](test-agent/README.md). | Works; needs a paired phone, and `ack` needs `MCP_DEMO_TOOLS=true` |
| `pnpm agent address` | Prints the wallet the owner connected on their phone, and its network, through `vault_get_address`. See [`docs/guides/wallet-setup.md`](docs/guides/wallet-setup.md). | Works; exits 9 with `WALLET_NOT_CONNECTED` until the owner connects one |
| `pnpm agent sign <text>` | Asks the owner's wallet to sign the text, through `vault_sign_message`. It prints the request as PENDING; the owner approves it on the phone, and `pnpm agent get <id>` reads the signature back and verifies it. See [`docs/guides/message-signing.md`](docs/guides/message-signing.md). | Works; needs a connected wallet |
| `pnpm agent capabilities` | Prints what the sidecar serves, through `vault_get_capabilities`: manual approval, the operations it implements, and the limits. | Works |
| `pnpm test:hello` | Runs the Stage 1 acceptance suite on a simulated device: the real CLI, the sidecar as a separate process, and a test client as the phone. With `--device`, it runs the round trip on the attached device or emulator instead: the app's UI test taps OK while the CLI sends over MCP. See [`docs/testing/stage-1.md`](docs/testing/stage-1.md). | Works; `--device` needs a device or an emulator |
| `pnpm test:queue` | Runs the Stage 2 acceptance scenario: the real CLI, two sidecars as separate processes that restart, and a test client as the phone. See [`docs/testing/stage-2.md`](docs/testing/stage-2.md#the-acceptance-scenario-saw-014). | Works |
| `pnpm format`, `pnpm format:android` | Apply Prettier and `buf format`, and ktfmt for Kotlin | Works |

## Development configuration

```bash
cp .env.example .env
openssl rand -hex 32   # run twice: once for MCP_TOKEN, once for PHONE_TOKEN
```

- `.env` is git-ignored.
- Variables already set in the environment take precedence over `.env`.
- The sidecar rejects placeholder or short tokens, identical MCP and phone tokens, and hosts that are not loopback addresses.
- Agents send `MCP_TOKEN`, and the Stage 1 live-test screen sends `PHONE_TOKEN`, each as `Authorization: Bearer <token>`; see [`docs/development/sidecar.md`](docs/development/sidecar.md). The durable workflow uses the credential that a phone gets by pairing (`pnpm pair`); see [`docs/security.md`](docs/security.md).
- `SIDECAR_PUBLIC_URL` and `PAIRING_TOKEN_TTL_SECONDS` are optional. `SIDECAR_PUBLIC_URL` is the URL that pairing codes carry. It defaults to the loopback URL, for `adb reverse`. For a phone on another network, set it to a trusted HTTPS endpoint; see [transport security](docs/security.md#transport-security).
- `MCP_DEMO_TOOLS=true`, which `.env.example` sets, serves the demo tool `vault_request_ack` for `pnpm agent ack` and Hermes. Leave it off outside development and demos.
- `MCP_ALLOWED_HOSTS` is optional. It lets `/mcp` accept a VPN address, for Hermes on a VPS that reaches the Mac over a VPN; see [`docs/integrations/hermes.md`](docs/integrations/hermes.md#over-a-vpn-you-already-use).
- Durable requests are stored in `sidecar/data/sidecar.db` unless `DATABASE_PATH` says otherwise. `REQUEST_TTL_SECONDS` and `REQUEST_PENDING_LIMIT` are optional too; see [storage and lifecycle](docs/development/sidecar.md#storage-and-lifecycle).

## CI

`.github/workflows/ci.yml` runs on pull requests and on pushes to `master` and `develop`:

- **Node:** `pnpm install --frozen-lockfile`, then `pnpm check`, `pnpm test:hello`, `pnpm test:queue`, `pnpm check:generated`, and `pnpm build`
- **Android:** `pnpm check:android` on Temurin 21
- **Emulator:** `pnpm test:hello --device` on an Android 16 (API 36) emulator. An emulator run never counts as the physical Seeker check.

The workflow has read-only repository permissions and never commits.

## License

See [`LICENSE`](LICENSE).
