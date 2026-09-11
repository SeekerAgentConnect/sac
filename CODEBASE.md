# Codebase Map

> Keep updated — see CLAUDE.md for rules.

## Project Overview

`seeker-vault` (repo: SeekerAgentWallet) is an Android app for the Solana Seeker phone that acts as a control center for requests from external AI agents, plus the self-hosted server software it talks to. Agents propose actions (message signing, transfers, Jupiter swaps) over MCP to a self-hosted TypeScript sidecar; the user reviews each request on the phone against per-connection policies and approves it through Mobile Wallet Adapter and Seed Vault Wallet. The sidecar never holds keys or signs. Stack: Kotlin/Compose (Android), TypeScript/Node (sidecar, test agent), Protobuf + Buf + Connect (phone–sidecar contract), Docker Compose (gateway/deployment).

**Status:** Stage 1 (wallet-free hello world) is accepted. Its automated checks pass, and the owner's Hermes → Seeker → OK → Hermes round trip passed on 2026-09-11 (`docs/testing/stage-1.md`). Done:

- SAW-001, the bootstrap: pnpm workspace, sidecar skeleton, empty Android app, and CI
- SAW-002, the live-command protocol: `LiveCommandService` contract, generated TypeScript and Kotlin code, sidecar protocol rules, and cross-runtime fixtures
- SAW-003, the live MCP command bridge: `/mcp` with `vault_display_command`, the phone's Connect API, and `/healthz`
- SAW-004, the Android live-test screen: stock Material 3 UI, a ViewModel, a Connect/OkHttp transport, and debug-only loopback cleartext
- SAW-005, the MCP test client: `pnpm agent hello`, with exit codes for each outcome
- SAW-006, the MacBook → Seeker guide: a quickstart and a troubleshooting page in `docs/guides/`
- SAW-007, the Hermes connection: `examples/hermes.config.yaml`, `docs/integrations/hermes.md`, and the Stage 1 test record in `docs/testing/stage-1.md`
- SAW-008, the acceptance gate: `pnpm test:hello` (simulated device), `pnpm test:hello --device` (the instrumentation round trip), stage boundary guards, the CI emulator job, and the report in `docs/testing/stage-1.md`

Stage 2 (persistent requests and connections) is in progress:

- SAW-009, the durable request contract: `seekervault.request.v1` (pairing, the request queue, results), the lifecycle, idempotency, and validation rules as pure code in `sidecar/src/requests/`, cross-runtime fixtures, and `docs/architecture.md`
- SAW-010, the persistent sidecar queue: SQLite storage with migrations (`sidecar/src/storage/`), the `RequestStore`, the MCP tools `vault_request_ack`, `vault_get_request`, and `vault_cancel_request`, and the phone's `RequestService`
- SAW-011, pairing and roles: one-use pairing codes (`pnpm pair`, as a QR code and a URI), the phone's credential from `PairingService.Pair`, revocation, one active phone per sidecar, and `docs/security.md`
- SAW-012, Android connections: the Connections, Connection details, and Add connection screens (QR scanning with CameraX and ZXing, or the typed code), metadata and Keystore-encrypted credentials in `connections/storage/`, no backups, and `docs/guides/pairing.md`

Commands and milestone status are in `README.md`, and agent rules in `AGENTS.md`.

## Directory Structure

| Path | Description |
| ---- | ----------- |
| `.claude/` | Claude Code project settings, plans, and task notes |
| `.claude/plans/` | Task plans with checkable items |
| `.claude/tasks/` | `lessons.md` (learned patterns) and `decisions.md` (architectural decisions) |
| `.github/workflows/` | `ci.yml`, three jobs, with read-only permissions and actions pinned by SHA: Node (`pnpm check`, `pnpm test:hello`, `pnpm check:generated`, `pnpm build`), Android (`pnpm check:android`), and device (`pnpm test:hello --device` on an API 36 emulator) |
| `android/` | Gradle build: wrapper 9.7.1, AGP 9.4 with built-in Kotlin, catalog `gradle/libs.versions.toml`, JDK pin `gradle/gradle-daemon-jvm.properties`, Spotless/ktfmt in `build.gradle.kts`; one `app` module |
| `android/app/src/main/` | `AndroidManifest.xml` (INTERNET, and CAMERA with the camera optional; backups off); `java/io/github/brrenat/seekervault/`: `MainActivity.kt` (the ViewModels, foreground/background hooks, theme), `SeekerVaultApp.kt` (the screens and their saved back stack of route strings: Connections, details, Add connection, Live test), `SeekerVaultApplication.kt` (the shared OkHttp client, the live transport factory, the connection repository, and the cleartext policy) |
| `android/app/src/main/java/.../live/` | `LiveCommandScreen.kt` (stateless Compose screen, test tags), `LiveCommandViewModel.kt` (UI state, one stream, one-tap OK, deadline, lifecycle), `LiveCommandTransport.kt` (interface, events, error kinds), `ConnectLiveCommandTransport.kt` (Connect-Kotlin over OkHttp), `LiveCommandDeadline.kt` (`isExpiredAt`) |
| `android/app/src/main/java/.../connections/` | Pairing and connections (SAW-012).<ul><li>`PairingCode.kt`: the `seekervault://pair` parser, which allows HTTP only where cleartext is permitted</li><li>`Connection.kt`, and `ConnectionGateway.kt` with `ConnectConnectionGateway.kt`: `Pair`, `ListPending`, and `RevokeConnection` over Connect-Kotlin, with errors classified through suppressed exceptions too</li><li>`ConnectionRepository.kt`: pair (checking the response), refresh, rename, disconnect, remove, and the start-up clean-up</li><li>`ConnectionsViewModel.kt`, and the screens `ConnectionsScreen.kt`, `ConnectionDetailsScreen.kt`, `AddConnectionScreen.kt` (with the camera permission), and `ConnectionText.kt`</li><li>`QrScanner.kt` (CameraX) and `QrDecoder.kt` (ZXing)</li></ul>`storage/` is the app's only storage: `ConnectionStore.kt` (one JSON file per connection), `CredentialVault.kt` (AES-256-GCM, with the connection ID as associated data), and `AndroidKeystoreKey.kt`. |
| `android/app/src/main/res/` | Strings (`strings_connections.xml` for the connection screens), platform day/night themes, `drawable/ic_arrow_back.xml`, and `xml/data_extraction_rules.xml` (nothing backed up or transferred) |
| `android/app/src/debug/` | Debug-only manifest overlay and `res/xml/network_security_config.xml` (cleartext to 127.0.0.1/localhost only) |
| `android/app/src/main/generated/` | Generated by `pnpm generate`; do not edit. `java/` holds the protobuf-lite messages, and `kotlin/` holds the Kotlin DSL and the Connect clients, for `live/v1` (`LiveCommandServiceClient`) and `request/v1` (`PairingServiceClient`, `RequestServiceClient`) |
| `android/app/src/test/` | JVM unit tests. `MainActivityTest` covers rotation, a double tap, and background/foreground (Robolectric). `live/` holds `LiveCommandViewModelTest` (fake transport, virtual time), `LiveCommandScreenTest` (Compose on Robolectric), `ConnectLiveCommandTransportTest` (against the real Node sidecar, with a throwaway database), `ConnectLiveCommandTransportUnreachableTest` (a closed port maps to Unreachable), `LiveProtocolFixturesTest`, `LiveCommandDeadlineTest`, and the `FakeSidecar` helper. `requests/RequestProtocolFixturesTest` checks the durable request fixtures. `connections/` holds these tests:<ul><li>`PairingCodeTest` and `QrDecoderTest`</li><li>`storage/CredentialVaultTest` and `storage/ConnectionStoreTest`</li><li>`ConnectionRepositoryTest` and `ConnectionsViewModelTest`, against `FakeConnectionGateway`</li><li>the Compose tests `ConnectionsScreenTest`, `ConnectionDetailsScreenTest`, and `AddConnectionRouteTest`</li><li>`ConnectConnectionGatewayTest` and `TwoSidecarsTest`, against the real sidecar through `RealSidecar`</li><li>`ConnectConnectionGatewayTlsTest`, with MockWebServer over TLS</li></ul>`ConnectionsActivityTest` runs the connection screens in the activity. `StageBoundaryTest` guards the stage boundary: the manifest, app code, backups, and the classpath. `resources/robolectric.properties` sets SDK 36. |
| `android/app/src/androidTest/` | Instrumentation tests, which `pnpm test:hello --device` runs. `LiveCommandDeviceTest` runs the round trip on a device or emulator: open Live test, enter the token, connect, check the exact text, and double-tap OK. `CredentialVaultDeviceTest` checks the vault with the real Keystore key. |
| `sidecar/` | TypeScript/Node package `@seeker-vault/sidecar`; runs `.ts` via Node type stripping; `tsconfig.json` (typecheck) and `tsconfig.build.json` (emit to `dist/`, `allowJs` for generated code). `data/` (git-ignored) holds the default database. |
| `sidecar/src/` | `main.ts` (`pnpm dev:sidecar` entry; SIGINT and SIGTERM), `server.ts` (HTTP server: `/healthz`, `/mcp`, the phone API; opens the database and closes it on shutdown), `mcp-endpoint.ts` (MCP sessions, `vault_display_command` and the durable tools, the token check, Host/Origin checks against loopback plus `MCP_ALLOWED_HOSTS`, and the 64 KiB body limit), `phone-api.ts` (Connect `LiveCommandService`), `auth.ts` (`bearerToken`, and the constant-time check of `MCP_TOKEN` and `PHONE_TOKEN`), `config.ts` (environment validation, including the optional `MCP_ALLOWED_HOSTS`, `DATABASE_PATH`, `REQUEST_TTL_SECONDS`, `REQUEST_PENDING_LIMIT`, `SIDECAR_PUBLIC_URL`, and `PAIRING_TOKEN_TTL_SECONDS`). Tests: `server.test.ts` (real MCP SDK and Connect clients), `restart.test.ts` (child-process restart), `config.test.ts`, and `stage-boundary.test.ts` (no wallet packages in the lockfile, no key APIs, and file system and SQLite access only in `src/storage/`). |
| `sidecar/src/live/` | `command.ts`: the protocol rules (`invalidTextReason`, `isExpired`, and `LiveCommandSlot`, which holds the one in-flight command and the latest outcome). `bridge.ts`: the in-memory waiter (one watcher, deadline timers, cancellation). Tests: `command.test.ts`, `bridge.test.ts` (mocked timers), `fixtures.test.ts` (cross-runtime fixtures). |
| `sidecar/src/requests/` | The durable request workflow.<br>**Pure rules (SAW-009):**<ul><li>`action.ts`: each action kind's fields, base-unit amounts, base58 addresses, and exact message bytes</li><li>`identity.ts`: connection scope for references, idempotency keys, and action fingerprints</li><li>`lifecycle.ts`: the transition table, the phone's results with the approval binding, and expiry</li></ul>**Served (SAW-010):**<ul><li>`store.ts`: `RequestStore`, which applies the rules in SQLite transactions (creation, reads, cancellation, pending pages, results, expiry, the pending limit), and `RequestFailure`</li><li>`mcp-tools.ts`: `vault_request_ack`, `vault_get_request`, `vault_cancel_request`, and the request view</li><li>`phone-service.ts`: the Connect `RequestService`, which takes the paired phone's credential (SAW-011), with a `RequestErrorDetail` on each error</li></ul>**Tests:** `action.test.ts`, `identity.test.ts`, `lifecycle.test.ts`, `fixtures.test.ts`, `live-compat.test.ts`, `store.test.ts` (idempotency, the pending limit, expiry, results, cancellation races, connection scope, pagination while states change, and restarts), `endpoints.test.ts` (MCP and Connect end to end, and size limits), and `restart.test.ts` (SIGKILL right after an answer) |
| `sidecar/src/pairing/` | Pairing and the phone's credentials (SAW-011).<ul><li>`uri.ts`: the pairing URI (`seekervault://pair?v=1&url=…&server=…&token=…`), and the server URL rule (HTTPS, or HTTP on loopback)</li><li>`store.ts`: `PairingStore`, with one-use pairing tokens bound to their URL, pairing that revokes the previous phone, credentials stored as SHA-256, revocation that cancels PENDING requests, and the lasting server ID</li><li>`service.ts`: the Connect `PairingService`</li><li>`cli.ts`: `pnpm pair`, `pnpm pair status`, and `pnpm pair revoke`, with QR codes drawn by `uqr`</li></ul>**Tests:** `uri.test.ts`, `store.test.ts`, `roles.test.ts` (every credential against every RPC and MCP method), `tls.test.ts` (pairing through a TLS endpoint, and certificate and host name failures), and `cli.test.ts` |
| `sidecar/src/storage/` | The only code that touches the file system or SQLite. `database.ts`: `openDatabase` (`node:sqlite`, WAL with synchronous FULL, foreign keys), `migrate` (`PRAGMA user_version`, each migration in a transaction, and a newer database is refused), and `transaction` (BEGIN IMMEDIATE). `migrations.ts`: numbered migrations. v1 has connections, requests, idempotency keys, prepared transactions, and results, all STRICT tables. v2 adds `server` and `pairing_tokens`, adds the credential hash and device name to `connections`, and revokes SAW-010's stand-in connection. `fixtures/schema-v1.sql` is a frozen v1 database. Test: `database.test.ts`. |
| `sidecar/src/gen/` | Generated protobuf-es code (`.js` + `.d.ts`); do not edit |
| `sidecar/src/testing/` | Test-only code, excluded from the build. `clients.ts`: the MCP SDK and Connect clients (`requestClient`, `pairingClient`, `callTool`, `viewOf`), and `pairPhone`, which pairs a test phone the way the owner does. `process.ts`: runs the sidecar as a child process with given settings and a throwaway database. |
| `proto/` | Buf module. `seekervault/live/v1/live.proto` defines the Stage 1 `LiveCommandService`. `seekervault/request/v1/` defines the durable workflow: `request.proto` (`ActionRequest`, actions, `RequestState`, `PreparedTransaction`, `PolicyEvaluation`, `RequestError`) and `service.proto` (`PairingService`, `RequestService`). |
| `proto/fixtures/` | Cross-runtime fixtures: `<package path>/<Message>/<case>.json` plus the `.binpb` written by `buf convert` |
| `scripts/` | `generate.mjs`: `buf generate` and fixture conversion; `--check` compares a fresh generation in a temp dir. `test-hello.mjs`: `pnpm test:hello`, which runs the simulated-device acceptance suite, or with `--device` runs the round trip on a device or emulator (a sidecar with throwaway tokens and database, `adb reverse`, `connectedDebugAndroidTest`, and the CLI). |
| `test-agent/` | `@seeker-vault/test-agent` (`pnpm agent`). `src/main.ts` is the CLI (`hello`, `tools`, exit codes, token redaction); `src/agent.ts` is the MCP client (discover, call, validate the acknowledgement); `src/config.ts` reads MCP_URL, MCP_TOKEN, and the client timeout. Tests: `cli.test.ts` (the real CLI process against the real sidecar and a Connect phone client), `config.test.ts`. `stage1.acceptance.ts` holds the Stage 1 acceptance cases, run only by `pnpm test:hello`. |
| `gateway/` | Placeholder README; Docker/TLS/OAuth gateway lands in Stage 7 |
| `examples/` | Configuration to merge into other tools: `hermes.config.yaml` (a Hermes `mcp_servers` entry for the sidecar) |
| `docs/` | Project documentation. `architecture.md`: components, trust boundaries, where state lives, invariants, and stages. `protocol.md`: the Stage 1 live flow; the Stage 2 durable contract (identity, actions, idempotency, lifecycle, the phone API, the MCP tools, errors, pairing, roles, compatibility); generated code; fixtures. `security.md`: roles and credentials, pairing, one active phone, revocation, TLS through a trusted endpoint, and redaction. |
| `docs/development/` | Developer docs. `toolchain.md`: pinned versions, MacBook setup, Studio/terminal compatibility, and a verification record. `sidecar.md`: configuration, start and stop, endpoints, the MCP tools, storage and lifecycle, examples, and code and tests. `android.md`: the connection screens and their code, the live-test screen, the debug URL, lifecycle limitations, and tests. |
| `docs/testing/` | Test procedures and records: `hello-world.md` (test agent: automated and physical-Seeker checks) and `stage-1.md` (the owner-run Hermes check and the SAW-007 record) |
| `docs/guides/` | Owner guides: `pairing.md` (pairing, managing connections, statuses, failures, and a new or lost phone), `macbook-seeker-quickstart.md` (fresh Mac → tools, build, USB debugging, install, `adb reverse`, hello world, cleanup, verification record) and `troubleshooting.md` (ADB, cable, JDK, SDK path, Node version, ports, reverse mapping, cleartext, app messages) |
| `docs/changelog/` | Release notes and change logs |
| `docs/wiki/` | Feature documentation |
| `docs/integrations/` | Integration guides: `hermes.md` (connect Hermes on the Mac, or on a VPS through an SSH reverse tunnel, and run the Stage 1 check). MWA, Jupiter, and gateway guides come in later stages. |

## Key Files

| File | Description |
| ---- | ----------- |
| `README.md` | Overview, milestone status, quickstart link, prerequisites, bootstrap/build commands, command status table |
| `AGENTS.md` | Rules for coding agents: stage boundaries, stock UI, tests, documentation |
| `RFC.md` | MVP implementation plan: scope, components, core workflow, protocol, policies, stages, readiness criteria |
| `docs/architecture.md` | How the agent, sidecar, app, and wallet fit together, and the invariants every stage keeps |
| `docs/protocol.md` | The protocol: Stage 1's live flow and Stage 2's durable request contract, with pairing, the role matrix, the error mappings (MCP and Connect), generated code, and fixtures |
| `docs/security.md` | Roles and credentials, pairing, revocation, transport security, and redaction (SAW-011) |
| `CLAUDE.md` | Claude Code working conventions for this repo |
| `CODEBASE.md` | This structural map |
| `LICENSE` | Project license |
| `package.json` | Root command contract (`check`, `check:android`, `check:generated`, `build`, `generate`, `dev:sidecar`, `pair`, `agent`, `test:hello`, `format`), pinned pnpm (`packageManager`) and Node (`devEngines.runtime`, which pnpm enforces), root dev tools |
| `pnpm-workspace.yaml` | Workspace packages, `catalog` versions (TypeScript, protoc-gen-es + @bufbuild/protobuf), `allowBuilds` |
| `.nvmrc` | Pinned Node version |
| `.env.example` | Development variables (placeholders only; `.env` is ignored), including the optional storage and pairing settings |
| `buf.yaml`, `buf.gen.yaml` | Buf module config (STANDARD lint) and pinned generators with managed Java package `io.github.brrenat.*` |
| `eslint.config.js`, `.prettierignore` | TypeScript lint (typescript-eslint, type-checked) and Prettier scope |
| `.claude/settings.json` | Shared Claude Code permissions (tracked) |
| `.claude/settings.local.json` | Local Claude Code settings (enabled plugins) |

## Architecture

The overview, with diagrams, is in `docs/architecture.md`.

**Stage 1 (accepted):**

- Hermes or the test agent calls the MCP tool `vault_display_command` on the sidecar.
- The sidecar delivers the text over the foreground-only `WatchCommands` stream (which opens with `ready`) to the open Android screen.
- The user taps OK, and `AcknowledgeCommand` returns `{id, result: OK}` to the MCP caller.
- The sidecar holds one watcher and one in-flight command, with a deadline (`LIVE_COMMAND_TIMEOUT_SECONDS`) and the errors OFFLINE, BUSY, TIMEOUT, CANCELLED, UNAUTHENTICATED, UNKNOWN_COMMAND, and INVALID_TEXT.
- The live flow is in memory. The sidecar is loopback-only, and remote agents use an SSH tunnel.
- Progress: the contract and rules (SAW-002), the sidecar bridge (SAW-003), the Android screen (SAW-004), the test agent (SAW-005), and the MacBook → Seeker guide (SAW-006), and the Hermes connection (SAW-007) exist. See `docs/protocol.md`, `docs/development/sidecar.md`, `docs/development/android.md`, `docs/guides/`, and `docs/integrations/hermes.md`.

**Stage 2 (in progress):**

- SAW-009 defined the durable contract in `proto/seekervault/request/v1` and `docs/protocol.md`:
  - An agent creates a request over MCP and gets `request_id` and PENDING at once; it reads the result with `vault_get_request`.
  - The phone uses unary `RequestService` RPCs, and every reference names the connection and the request.
  - The lifecycle has ten states and explicit transitions, and each transition names who causes it.
  - Approval binds to a prepared version and its content hash, and it's the commit point before the wallet is invoked.
  - Idempotency keys fingerprint the action, so a changed retry is refused.
- SAW-010 serves it:
  - The sidecar stores requests in SQLite: `DATABASE_PATH`, by default `sidecar/data/sidecar.db`.
  - Each operation is one transaction that commits before it answers, and expiry is applied first.
  - Nothing runs on its own after a restart.
- SAW-011 adds pairing and separate roles (`docs/security.md`):
  - `pnpm pair` issues a one-use pairing token bound to `SIDECAR_PUBLIC_URL`, and shows it as a QR code. `PairingService.Pair` exchanges it for a new connection and its credential.
  - There are four credentials, each accepted in one place: `MCP_TOKEN` on `/mcp`, the pairing token on `Pair`, the phone credential on `RequestService` and `RevokeConnection`, and `PHONE_TOKEN` on the Stage 1 `LiveCommandService`.
  - One phone is active per sidecar. Pairing again, or revoking, cancels the previous connection's PENDING requests.
  - The sidecar stays on loopback, and a phone elsewhere reaches it through a trusted TLS endpoint.
- SAW-012 adds the phone's connections (`docs/guides/pairing.md`):
  - The app opens on Connections. Add connection scans a pairing code or takes it typed in, shows the server to confirm, and pairs.
  - Each connection is keyed by its connection ID: a JSON file of metadata, and the credential, encrypted under a Keystore key, in `noBackupFilesDir`. Nothing is backed up.
  - A credential goes only to its own URL. A code never changes an existing connection, and a credential the sidecar rejects is deleted.
- Next comes the inbox (SAW-013).

Later stages (see `RFC.md` §3–6):

- **Agent → sidecar (MCP):** agents call `vault_*` MCP tools (`get_address`, `get_capabilities`, `sign_message`, `transfer`, `swap`, `get_request`). The sidecar persists an *action request* (not a prebuilt transaction) and immediately returns `request_id` + pending; retries must not create duplicate payments.
- **Phone → sidecar (Connect API, unary):** the Android app pairs via QR code (server address + one-time token), then calls `Pair`, `ListPending`/`GetRequest`, `PrepareRequest`, `SubmitResult`. Pending requests are fetched on app open/refresh; there's no push or persistent stream.
- **Transaction prep:** the sidecar builds a fresh unsigned transaction at review time (blockhash expiry); swaps use Jupiter `/build`. Approval is bound to a specific `PreparedTransaction` version.
- **Policy:** policies live and are enforced only on the phone, per connection. The phone parses the actual transaction and produces a `PolicyEvaluation` of `ALLOWED` or `UNDER_RESTRICTIONS`; unrecognized contents are marked unverified. Manual approval is always required.
- **Signing:** the phone invokes MWA `signAndSendTransactions` from an Activity; Seed Vault Wallet signs and sends. The result returns to the sidecar and then to the agent. On-chain confirmation is checked separately from MWA's submission response.
- **Contract:** `proto/` is the single source of truth, and both `sidecar/` and `android/` consume generated code.
- **Deployment:** `docker compose up` runs the sidecar (persistent storage), gateway (TLS, optional OAuth), and test agent.

## Commands

| Command | Purpose |
| ------- | ------- |
| `pnpm install --frozen-lockfile` | Reproducible install |
| `pnpm check` | Prettier, `buf format`, ESLint, `buf lint`, `tsc`, sidecar tests (no file changes) |
| `pnpm check:android` | Spotless, Android unit tests, lint, debug APK |
| `pnpm check:generated` | Fail if committed generated code or fixtures are stale (no file changes) |
| `pnpm build` | Sidecar → `sidecar/dist` |
| `pnpm dev:sidecar` | Run the sidecar on loopback with the `.env` configuration |
| `pnpm pair [status \| revoke]` | Show a one-use pairing code (QR code and URI), show the paired phone, or revoke it |
| `pnpm agent hello [text]` | Call `vault_display_command` over MCP and print the acknowledgement (exit codes in `test-agent/README.md`) |
| `pnpm test:hello [--device]` | Stage 1 acceptance suite on a simulated device; with `--device`, the round trip on a device or emulator |
| `pnpm generate` | Regenerate protocol code and `.binpb` fixtures (needs network) |
