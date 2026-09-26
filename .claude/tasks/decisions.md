# Architectural Decisions

## 2026-09-11 — SAW-001 bootstrap (SEE-6)

- **Application ID `io.github.brrenat.seekervault`.** It's a reverse-DNS name under the owner's GitHub Pages domain and matches the `seeker-vault` working name. Treat it as fixed once builds are installed on devices.
- **Latest stable toolchain, pinned exactly** (see `docs/development/toolchain.md`). Exception: TypeScript 6.0.3, because typescript-eslint 8.70 does not support TypeScript 7 yet.
- **Plain pnpm workspace, no orchestration framework.** Root scripts are the command contract, and `pnpm --recursive` fans out to the packages.
- **The sidecar runs TypeScript natively** (Node 24 type stripping, `erasableSyntaxOnly`) and tests with `node:test`, so it needs no bundler and no test framework. For the same reason, protoc-gen-es emits `js+dts` rather than `ts`.
- **The JDK is pinned with Gradle Daemon JVM criteria** (Temurin 21, auto-provisioned), so the terminal, Android Studio Panda+, and CI build on the same JDK without a manual install. The bytecode target stays 17.
- **minSdk 31, compile and target SDK 37.** The app targets the Seeker only. A modern floor avoids compatibility branches and allows the platform day/night themes.
- **Stock UI only:** baseline Material 3 light and dark color schemes, platform themes, and the system default launcher icon. Wallpaper-based dynamic color is left out so screenshots and tests stay deterministic.
- **App data never leaves the device:** backups are disabled, and data-extraction rules exclude every domain, because later stages store connection credentials.
- **Commands that aren't implemented yet fail loudly.** `pnpm agent` and `pnpm test:hello` exit 1, and `pnpm generate` shows Buf's "no .proto files" error until SAW-002 adds the first proto.
- **CI can't write to the repository.** It runs with `permissions: contents: read` and `persist-credentials: false`, and every action is pinned by commit SHA. Gradle caching uses the MIT-licensed `basic` provider of `setup-gradle`.

## 2026-09-11 — SAW-002 live-command protocol (SEE-8)

- **`WatchCommands` opens with a `ready` event.** Without it, the phone and test clients can't tell a registered, authenticated stream from one that is still connecting, and a race would turn the first command into `OFFLINE`.
- **One watcher; the newest wins.** A new stream replaces the old one, which ends with `canceled`, and its in-flight command is cancelled. That way a reconnect never fails as busy, and nothing is redelivered.
- **Repeated acknowledgements are idempotent.** The sidecar remembers only the latest finished command's outcome, which is not a backlog. A repeated OK succeeds without a second effect, and a late one gets `TIMEOUT` or `CANCELLED`. Any other ID gets `UNKNOWN_COMMAND`.
- **Text limit: 4096 UTF-8 bytes of well-formed Unicode, not whitespace-only.** Bytes are the unambiguous measure on the wire, and unpaired surrogates would be silently altered by UTF-8 encoding.
- **Deadline:** a command is live strictly before `expires_at`. The sidecar writes millisecond precision, and both runtimes compare nanoseconds exactly.
- **`LiveCommandError` is a proto enum.** It gives the MCP errors and the phone's Connect codes a single set of names. Connect codes map one to one, so no error-detail message is needed.
- **The rules are pure code in `sidecar/src/live/command.ts`** (`LiveCommandSlot`). SAW-003 adds I/O around them: timers, streams, MCP, and watcher presence for `OFFLINE`.
- **`buf convert` writes the fixtures from JSON.** Buf's Go runtime is a neutral third implementation, and both runtimes must match its bytes in both directions.
- **Generated code is committed.** `pnpm check:generated` compares a fresh generation in a temporary directory, so it never touches the working tree. Kotlin output goes in its own `generated/java` and `generated/kotlin` directories, because `clean: true` owns them.

## 2026-09-11 — SAW-003 live MCP command bridge (SEE-9)

- **MCP runs in stateful sessions.** Cancellation arrives as `notifications/cancelled` in a separate POST, and only a session routes it to the waiting tool call. A restart drops every session, and clients re-initialize after getting 404.
- **A dropped agent connection also cancels.** An AsyncLocalStorage-scoped signal fires when the tool call's HTTP response closes before it's answered. That way a crashed agent frees the phone at once, instead of holding it until the deadline.
- **Tool errors are `isError` text `"<CODE>: <message>"` with no `structuredContent`.** The MCP SDK client validates `structuredContent` against the output schema even on errors, so a structured error would be rejected by the client.
- **`/mcp` accepts only loopback Host and Origin names, on any port.** That blocks DNS rebinding and still allows SSH tunnels on another port. The phone API skips the Host check, because an emulator reaches the host as 10.0.2.2 and the bearer token protects that API anyway.
- **Tokens are compared as SHA-256 digests with `timingSafeEqual`,** which is constant time for any input length. They are read only from `Authorization` and never logged.
- **Shutdown order:**
  1. Cancel the in-flight command, so the agent gets `CANCELLED`.
  2. End the phone stream with `unavailable`.
  3. Give in-flight responses up to 1 second to finish.
  4. Close the MCP sessions and any remaining connections.
- **Integration tests use the real MCP SDK client and a Connect client against an in-process server on port 0.** The restart test runs `node src/main.ts` as a child process, so the process boundary is real.

## 2026-09-11 — SAW-004 Android live-test screen (SEE-10)

- **The foreground is tracked with Activity `onStart` and `onStop`, skipping `isChangingConfigurations`.** A rotation keeps the ViewModel and its stream, because closing and reopening the stream would make the sidecar cancel the command. Backgrounding closes the stream, and coming back reopens it only if the user had connected. There's no extra lifecycle-process dependency.
- **The ViewModel owns the connection. The screen is a stateless composable.** The transport sits behind `LiveCommandTransport`, so the ViewModel, Compose, and Activity tests all use one `FakeSidecar`. `SeekerVaultApplication.liveCommandTransports` is the injection point for tests.
- **Double taps are guarded in the state, not only in the UI.** The status moves to Sending synchronously before the request, so a second tap does nothing even before recomposition.
- **Connection loss clears the received text and says why.** The sidecar has already cancelled the command, so showing it would invite an OK that must fail.
- **Connect-Kotlin runs with `streamTimeout = null`, over an OkHttp client without a read timeout.** Its defaults are 10 seconds, which would end an idle WatchCommands stream. Server streams aren't duplex, so HTTP/1.1 over `adb reverse` works.
- **Cleartext is a debug source-set overlay:** a network security config for 127.0.0.1 and localhost only. Release builds carry no such config, and the aapt2 check confirmed it.
- **UI tests run on Robolectric, as JVM tests, so CI needs no emulator.** Robolectric 4.16.1 runs them at SDK 36, because 4.17, which adds SDK 37, was released less than a day ago. Device and emulator instrumentation tests are SAW-008's.
- **The transport is also tested against the real sidecar,** with `node sidecar/src/main.ts` and an MCP SDK agent script. This is the closest automated stand-in for the device check, and it is not reported as one.
- **Tokens stay in memory only, never in saved state.** Process death forgets them.

## 2026-09-11 — SAW-005 MCP test client (SEE-11)

- **`pnpm agent` runs `node tools/test-agent/src/main.ts` directly.** A plain `pnpm run` keeps the script's exit code, but `pnpm --filter …` turns every failure into 1, which was measured.
- **The exit codes are fixed, and tests assert them:**
  - 0 OK
  - 1 unexpected
  - 2 usage or configuration
  - 3 connection
  - 4 OFFLINE, 5 BUSY, 6 TIMEOUT, 7 CANCELLED, 8 INVALID_TEXT

  Tool errors map through the sidecar's `"<CODE>: "` prefix. Success needs a real `{id, result: "OK"}`.
- **The client timeout is the sidecar's deadline plus 15 seconds** (`--timeout` overrides it), so the sidecar's own TIMEOUT normally wins. On a client timeout, the SDK sends `notifications/cancelled`, and the CLI ends its MCP session, so the sidecar frees the phone.
- **Tokens are redacted.** `MCP_TOKEN` and `PHONE_TOKEN` are removed from every line the CLI writes, and errors print a message without a stack.
- **The tests reuse the sidecar's code.** They run the CLI as a real process against an in-process sidecar, and the sidecar's test clients act as the phone. Relative imports into `sidecar/src` are for tests only; the test-agent build excludes them. `Code` and `ConnectError` are re-exported from the sidecar's testing module so both packages share one Connect instance.
- **The MCP SDK, zod, and `@types/node` moved to the pnpm catalog,** shared by the sidecar and the test agent.

## 2026-09-11 — SAW-006 MacBook → Seeker guide (SEE-12)

- **pnpm enforces the Node version through `devEngines.runtime` (`onFail: "error"`), which replaced `engines`.** Measured with pnpm 12.3.4: it ignored the root `engines.node`, even with `engineStrict`, so Node 24.20.0 installed and ran scripts without a warning. With `devEngines.runtime`, every pnpm command stops with `ERR_PNPM_BAD_RUNTIME_VERSION`, and the lockfile doesn't change. CI still reads `.nvmrc`.
- **The app names the fix when the first connection fails.** An Unreachable error before `ready` becomes `DisconnectReason.Unreachable(url, port)`, and the screen asks whether the sidecar is running and `adb reverse` was run for that port. After `ready`, the same error stays a lost connection: a setup that worked and then dropped needs a different explanation, and the received text was cleared.
- **`adb reverse` plus loopback cleartext stays the only local path.** The guides never suggest a LAN address, extra cleartext hosts, or skipping TLS validation, and the troubleshooting page says so explicitly.
- **The guide's outputs are real.** A script ran the Mac-side steps in a fresh clone, and the guide quotes its output verbatim. Steps that couldn't run (Seeker, Android Studio, USB reconnect, a JDK older than 17) are marked NOT RUN, and troubleshooting cases that weren't reproduced say so.
- **Token entry through `adb shell input text` is offered as a convenience.** Typing 64 hex characters on a phone is error-prone, and hex needs no shell quoting. The token is a local development credential that never leaves the USB connection.

## 2026-09-11 — SAW-007 Hermes connection (SEE-13)

- **The Hermes token lives in `~/.hermes/.env` as `MCP_SEEKER_VAULT_API_KEY`, never in `config.yaml`.** `hermes mcp add --auth header` generates the same name for a server called `seeker_vault` (`_env_key_for_server`), so both setup paths agree. Hermes sends an unset `${VAR}` as literal text, so the guide checks with `hermes mcp test` before the first prompt.
- **`timeout: 90`.** That's above the sidecar's 60-second default deadline, so the sidecar's `TIMEOUT` wins, and below Hermes's fixed 300-second HTTP read timeout.
- **`tools.include: [vault_display_command]`.** Later stages add wallet tools to the same server. Each should be allowed on purpose rather than appear in the owner's sessions automatically.
- **The VPS path is an SSH reverse tunnel from the Mac to the VPS's loopback.** The sidecar stays on loopback, and the VPS needs no open port, domain, or OAuth. The sidecar's Host check already accepts loopback names on any port. A public gateway with TLS and OAuth is Stage 7.
- **Verification ran Hermes's own code, without an LLM.** Hermes v0.21.1 was installed from its release tag into a scratch venv, with a scratch `HERMES_HOME`. `hermes mcp list` and `test` ran, then a tool call went through `discover_mcp_tools` and `model_tools.handle_function_call`, the path a model's tool call takes. That covers the compatibility risks: Python `mcp` 2.0.0 against the TypeScript SDK 1.30 server, header interpolation, and the model-visible result. The owner's session with a model stays NOT RUN.
- **Integration docs moved to `docs/integrations/`,** matching the backlog's paths. The empty `docs/guide/` placeholder is removed and `CLAUDE.md` updated, as SEE-6 did for `docs/development/`.
- **Known gap, left for SAW-008:** the test agent notices a mid-call connection drop only at its client timeout, and reports it as `TIMEOUT` (exit code 6). Hermes reports the same drop at once. The sidecar cancels the command immediately either way.

## 2026-09-11 — SAW-008 Stage 1 acceptance gate (SEE-14)

- **`pnpm test:hello` runs `tools/test-agent/src/stage1.acceptance.ts`, one case per acceptance scenario.** The file name keeps it out of the package test glob, so `pnpm check` doesn't run it twice; CI runs `pnpm test:hello` as its own step. The suite runs the real CLI against the sidecar as a separate process (`sidecar/src/testing/process.ts`), because the restart cases need a process to kill.
- **`--device` is orchestrated from the host.**
  - It uses throwaway tokens and a free port, never the owner's `.env` or a running sidecar.
  - It runs `adb reverse` for that port.
  - The instrumentation test gets the URL, the phone token, and the text (base64url, for safe `am instrument` quoting) as instrumentation arguments.
  - The CLI sends only after the sidecar logs the phone's connection.
  - It passes only when the UI test passes, the agent prints the OK, and the sidecar logged exactly one acknowledgement.
  - The run labels itself as an emulator or a device, from `ro.kernel.qemu` and `ro.boot.qemu`.
- **The instrumentation test drives the real UI.** It enters the token and taps Connect and OK like the owner would, and uses the app's real transport, with nothing injected.
- **The emulator job runs Android 16 (API 36) `google_apis` x86_64, through `android-emulator-runner`, with KVM enabled.** The debug build's loopback-only cleartext rule works unchanged, because the emulator also reaches the host through `adb reverse`.
- **The SAW-007 gap is fixed in the test agent.** When the SDK reports that the call's SSE response stream disconnected, `displayCommand` aborts the pending call and exits 3 at once. Without resumable streams, the SDK would otherwise leave the call waiting for its timeout. The SIGKILL restart case asserts this.
- **Stage boundary guards run on every check.**
  - Android: the manifest's components and permissions, forbidden storage, key, and background APIs in app code, and the absence of wallet, storage, and background classes on the classpath.
  - Node: no wallet packages in the lockfile, and no file-system, database, or key APIs in the sidecar's shipped code.
- **Stage 1 stays unaccepted until the owner records the device round trip.** The physical Seeker and the real Hermes round trip are NOT RUN; an emulator and a simulated device don't close them.

## 2026-09-11 — PR #2 review

- **A VPN address for `/mcp` is configuration, not code.**
  - Commit `e035411` put the owner's Tailscale address in `mcp-endpoint.ts`'s loopback list, so that their Hermes on a VPS could connect through `socat`.
  - That address is now `MCP_ALLOWED_HOSTS`, validated as host names or IP addresses without a scheme, port, or wildcard. The owner's `.env` carries it.
  - The DNS-rebinding defense still holds. A page on another origin fails the Origin check, and DNS rebinding can't produce an IP-literal Host.
- **Node is pinned exactly: `devEngines.runtime` requires 24.21.0.** `.nvmrc`, the docs, and CI all say 24.21.0, and a range would let untested patch releases through silently. Updating Node means changing `.nvmrc` and `package.json` together.
- **Tokens are limited to RFC 6750 bearer-token characters:** `A-Z a-z 0-9 - . _ ~ + /`, plus a trailing `=`. The `Authorization` parser accepts only tokens without spaces, so any other token would start a sidecar that no client can use.
- **`--device` identifies the Seeker by `ro.product.brand=solanamobile` and `ro.product.model=Seeker`.** These values were read from the owner's device, which reports manufacturer "Solana Mobile Inc." and Android 16. Other phones are labeled "a phone that isn't a Seeker", and their runs don't count.
- **`sdkmanager "platforms;android-37.0"` stays.** The review suggested `platforms;android-37`. The installed platform's own `package.xml`, however, declares `path="platforms;android-37.0"` (API level 37.0). Since API 36.1, platform packages carry a minor version.
- **An overdue command is settled before the next one starts.** `LiveCommandSlot.start()` already expired it, but the bridge's waiter wasn't answered, so the first MCP call hung. `display()` now settles it as `TIMEOUT` first.
- **The owner's device and Hermes pass is recorded with attribution:** "run and reported by the owner on 2026-09-11". The Seeker's details come from adb. The owner's Hermes version wasn't reported.

## 2026-09-11 — SAW-009 durable request contract (SEE-16)

- **A new package, `seekervault.request.v1`, sits beside the live one.** The live diagnostic stays as it was, and neither package imports the other. The live stream holds one command in flight and stores nothing, which is the opposite of a durable queue, so building on it would have changed Stage 1's behavior.
- **A connection is one phone paired with one sidecar, and every reference carries both IDs.** A request ID is unique only within its connection, because another sidecar can issue the same one. A reference to another connection gets NOT_FOUND, the same as a request that doesn't exist.
- **Idempotency keys are scoped to the agent, meaning the whole sidecar, not to the connection.** A retry after the phone re-paired must still find the original request. Scoped per connection, the retry would create a second request, and possibly a second payment.
- **The fingerprint is the SHA-256 of the action's deterministic Protobuf encoding.** protobuf-es writes fields in number order, the action has no maps, and unknown fields are dropped, so equal actions give equal bytes. The agent's note and the request's lifetime are left out, so an LLM that rewords its retry gets the original request instead of an error.
- **Approval is a step of its own, and it's the commit point (PENDING → PROCESSING).** The phone reports the approval before it invokes the wallet, so a cancellation and an approval can't both win. The step also records that the wallet may have been invoked, which is what makes an UNKNOWN outcome reportable.
- **No state moves backward.** A retry is a new request with a new key.
  - UNKNOWN isn't terminal: a late report or a chain lookup can settle it.
  - SUBMITTED never becomes UNKNOWN: once the signature is known, the chain can always answer.
- **Each financial action names its wallet and network, and the agent must supply them.** A mismatch is refused at creation. That makes the agent's intent explicit, and the phone never signs with a wallet the agent didn't name.
- **The asset is explicit: `native_sol` or a `token_mint`.** If an empty mint meant SOL, a dropped field would silently become a SOL transfer.
- **Amounts are u64 decimal strings, and slippage runs from 1 to 10000.** A slippage of 0 counts as missing, so proto3's zero default stays unambiguous without `optional`.
- **One `RequestError` enum serves both MCP text and Connect details.** A Connect code alone can't tell a superseded version from an expired request, so each RPC error carries a `RequestErrorDetail` with the request as it is now. Both runtimes can read it: connect-kotlin 0.9.0 has `unpackedDetails` and a javalite parser (checked in the jar), and connect-es has `findDetails` (checked in the types).
- **`PolicyEvaluation` is defined, but nothing sends it.** The RFC keeps policies on the phone. The message fixes the shape and makes the separation from `RequestState` explicit. Stage 5 decides where the phone keeps it.
- **The rules are pure code in `sidecar/src/requests/`, as SAW-002's were.** SAW-010 adds storage, duplicate submissions, and transactions around them. The Stage 1 boundary guards stay unchanged, because nothing here stores anything.

## 2026-09-11 — SAW-010 persistent sidecar queue (SEE-17)

- **SQLite through Node's built-in `node:sqlite`.** Node 24.21 ships it without a flag or a warning, so there's no native build, no new dependency, and no difference between the Mac and CI. The ticket rules out Redis and external databases.
- **WAL with `synchronous = FULL`, and one IMMEDIATE transaction per operation that commits before the answer.**
  - WAL with NORMAL could lose the last commits in a power cut, which would break "persist before acknowledging".
  - The store's API is synchronous, so Node's single thread never interleaves two operations.
  - The write lock also covers a second process.
- **Expiry runs first in every operation, not on a timer.** A timer would add a background component, and a window in which a PENDING request is already past its deadline. Applying expiry inside each transaction gives the contract's exact boundary.
- **Repeated results are recognized by their bytes.** Every accepted result is kept in `results`, and an identical submission returns the request unchanged. That's simpler and stricter than comparing states, because a different result that leads to the same state is still a conflict.
- **Until pairing, the sidecar has one connection, created with the database, and `PHONE_TOKEN` authenticates as it.** Requests need a connection now, and pairing is SAW-011. The connection's ID survives restarts, and the startup log prints it.
- **Wallet actions are refused with `WALLET_MISMATCH`.** Until a wallet connects in Stage 3, only the ack can be created. That matches the contract: no wallet binding matches when there's no wallet.
- **The database defaults to `sidecar/data/sidecar.db`, resolved from the sidecar package rather than the working directory.** That way, `pnpm dev:sidecar` and `node sidecar/dist/main.js` use the same file. Test harnesses always pass a throwaway path, so tests never touch the developer's requests.
- **`/mcp` bodies are limited to 64 KiB.** The MCP SDK reads a body of any size. The endpoint now reads the body itself and passes it on parsed:
  - A declared length over the limit is refused at once.
  - A larger chunked body is drained without being kept.
- **The stage guard changed on purpose.** It now allows the file system and SQLite only in `src/storage/`, and still forbids key generation and wallet packages.
- **The v1 fixture is SQL, not a binary database.** It's reviewable and diffable, and the test proves it's a real v1 database by comparing its schema with migration 1's.
- **The CI emulator job runs without the Gradle cache.**
  - The emulator needs 7.2 GB of free disk for its data partition. After the Android job saved an 886 MB Gradle cache, restoring it left 6.1 GB, so from run 34593182239 on, the emulator never booted.
  - Passing and failing runs had the same runner image and the same emulator build (37.1.11.0).
  - `disk-size: 4096M` didn't lower the 7.2 GB, so it's removed.
  - The job builds the app without the cache, as the passing runs did.

## 2026-09-11 — SAW-011 secure pairing and separate roles (SEE-18)

- **Four credentials, each accepted in exactly one place.** `MCP_TOKEN` opens `/mcp`, the pairing token opens `Pair`, the phone credential opens `RequestService` and `RevokeConnection`, and `PHONE_TOKEN` opens only the Stage 1 `LiveCommandService`. Keeping `PHONE_TOKEN` for the live diagnostic leaves Stage 1 and its device test unchanged, and gives the operator's `.env` no approval authority.
- **The sidecar creates the phone credential and returns it once.** It keeps only the SHA-256, so the database, its backups, and the log can't reveal it. The secret is 32 random bytes, so a plain hash is enough. A slow password hash protects guessable secrets, which these aren't.
- **`pnpm pair` writes the pairing token straight into the database,** instead of asking the running sidecar over an admin endpoint. An admin endpoint would be one more credential to protect. SQLite's locking makes a short-lived second process safe, and the operator can pair or revoke whether or not the sidecar is running.
- **The pairing token is bound to the URL in its code.** A `Pair` for another URL is refused, and the token stays usable. The URL is normalized first, so a trailing slash or the host's case doesn't matter.
- **Pairing always creates a new connection, and never changes an existing one.** So a code can't redirect an existing connection to another host. The phone must treat every code as a new pairing too. The lasting `server_id` lets the phone recognize a sidecar it knows, without trusting a code's URL for an old connection.
- **One active phone per sidecar, enforced at pairing.** Pairing revokes every active connection. A phone with several sidecars (SAW-012) has one connection to each.
- **Refusals don't say which check failed.** Unknown, expired, used, and missing pairing tokens all get one UNAUTHENTICATED message. A URL mismatch is answered differently: only a holder of a valid token can reach that check, and telling them lets them retry at the right URL.
- **Revocation cancels PENDING requests in the same transaction, and leaves overdue ones to expire.** Expiry comes first everywhere else, so a request past its deadline must never become CANCELLED.
- **Migration 2 revokes SAW-010's stand-in connection.** It has no credential, so no phone could ever use it. Its PENDING requests are cancelled the way a revocation cancels them, and the frozen v1 fixture checks that.
- **TLS ends at a trusted endpoint in front of the loopback sidecar,** such as Tailscale Serve or Caddy, and not in the sidecar. The sidecar never listens beyond loopback, the phone keeps Android's normal certificate checks, and Stage 7's gateway can take over later. The server URL must be HTTPS, apart from the loopback development URL. One function, `invalidServerUrlReason`, holds that rule for the configuration, the CLI, and `Pair`.
- **uqr 0.1.3 draws the QR code.** It has no dependencies, and it renders to the terminal in block characters.

## 2026-09-11 — SAW-012 Android pairing and connections (SEE-19)

- **Files for connections, not a database.** Each connection has one JSON file (`AtomicFile`) and one credential file. That keeps each connection's data physically separate, so removing one can't touch another, and it needs no Room, DataStore, or KSP. SAW-013 can add a database for the inbox if it needs one.
- **The Android Keystore directly, not EncryptedSharedPreferences.** Jetpack Security's crypto library is deprecated. An AES-256-GCM key in the Keystore does the same job with platform code. The connection ID as associated data binds each ciphertext to its connection.
- **No user authentication on the key.** The app reads a credential only in the foreground, when the owner acts. A biometric prompt for every refresh would add friction and protect no approval: from Stage 3 on, the wallet confirms those itself.
- **Nothing is backed up.** A restored credential would be useless anyway, because the Keystore key doesn't travel. Recovery is pairing again, and the one-phone model makes that safe: the new pairing revokes the old connection.
- **The connection ID from the sidecar names local files, so it's checked as a lowercase UUID first.** A hostile sidecar can't write outside the app's directories. The credential must also have the right format, and the server ID must match the code's.
- **A code never updates a connection.** Even with a known server ID, it pairs anew, and the app then refreshes the old connection, which the sidecar has revoked. A hostile code that claims a known server ID can't take over a connection or its credential. It can't make the app delete one either: only the old connection's own sidecar can revoke it.
- **Plain HTTP follows the platform's policy.** The parser accepts loopback HTTP only where `NetworkSecurityPolicy` permits cleartext to that host: loopback in debug builds, and nothing in release builds. The rule can't drift from the network security config.
- **CameraX and ZXing for scanning.** ZXing's core decoder has no dependencies and needs no Google Play services. Google's code scanner would have needed Play services and brings its own UI, which runs outside the app's permission.
- **Navigation is a saved list of route strings,** not a navigation library. Four screens don't need one, and nothing secret goes into saved state. The code being entered stays in the ViewModel's memory.
- **The error classifier reads suppressed exceptions.** When `localhost` resolves to both `::1` and `127.0.0.1`, OkHttp throws the first route's failure and suppresses the rest. A refused IPv6 connection hid the certificate failure on IPv4, and the TLS test caught it.

## 2026-09-11 — SAW-013 pending inbox and queued acknowledgements (SEE-20)

- **The repository that holds the credentials also does the fetching and answering.** Every call needs a connection's credential, and keeping those calls in `ConnectionRepository` keeps one rule in one place: a credential goes only to its own URL. A separate inbox class would have needed the credential handed to it.
- **Answers are stored first; pending lists aren't stored at all.** The sidecar holds the requests, so the phone fetches them fresh. Only the owner's decision must survive a crash or a dead network, so only answers are written, one file per answer under its connection's directory, which keeps identical request IDs on two servers apart.
- **A failed send is retried by resending, never by asking first.** `SubmitResult` already returns the request unchanged for a repeat of an accepted result. So after a lost response, sending again is the whole recovery. `INVALID_STATE` with its `RequestErrorDetail` tells the phone the request moved on (cancelled or expired), and the answer is marked superseded, not retried.
- **Retries happen only when the owner acts:** on opening the app, opening a connection, refreshing, or **Send again**. That keeps the no-background-service rule. Resending the owner's own answer isn't automatic execution: nothing is answered that the owner didn't answer.
- **One send per answer at a time,** guarded in the repository. A refresh that overlaps a tap skips the answer being sent, and the ViewModel ignores taps while a send runs or after an answer exists.
- **Settled answers are kept for a week,** so reopening a request shows its outcome, and then pruned at start-up. Waiting answers are kept until they settle.
- **The test agent gained `ack`, `get`, and `cancel`.** Hermes is limited to the Stage 1 tool until SAW-014, and the owner-run Stage 2 check needs an agent that can queue a request and read it back.

## 2026-09-11 — SAW-014 Stage 2 acceptance gate (SEE-21)

- **The demo tool is opt-in, with `MCP_DEMO_TOOLS=true`.** `vault_request_ack` isn't a financial action, but it exists only to exercise the workflow without a wallet.
  - A sidecar that nobody configured serves only the tools that later stages keep: `vault_display_command`, `vault_get_request`, and `vault_cancel_request`.
  - `.env.example` is a development configuration, so it sets the flag.
  - A value other than `true` or `false` is a configuration error, so a typo can't turn the tool off without a word.
- **Hermes gets `vault_cancel_request` too.** Withdrawing its own request costs the owner nothing, and the wallet tools of later stages need the same way out. As before, no tool pairs, answers, or revokes.
- **Two acceptance suites, one per side.** Each side's real code runs only in its own runtime: the CLI, the sidecar processes, and `pnpm pair` in Node, and the app's repository and files on the JVM.
  - `pnpm test:queue` runs in the Node job in seconds.
  - `Stage2AcceptanceTest` runs in `pnpm check:android`, against the same real sidecars.
- **Time passes while a sidecar is down through a preload, not a setting.** Expiry across a restart needs the sidecar's clock to jump, and a configuration variable for the clock would ship a way to change the time.
  - Test harnesses start the process with `--import sidecar/src/testing/clock.ts`, which moves `Date.now()` ahead by a fixed amount. The stores already read the time through `Date.now()`.
  - In-process tests get a fake clock through `SidecarOptions.now`, which moves only when the test moves it.
  - A harness never restarts a sidecar with its clock behind where it was, so no request is created in the future.
- **Stage 2 isn't declared accepted yet.** Every automated check passes. But Stage 1 was accepted only after the owner's run on the Seeker, and Stage 2 follows the same rule. `docs/testing/stage-2.md` holds the owner-run steps.

## 2026-09-11 — PR #3 review

- **Coming back to the foreground counts as opening the app.** An owner who switches back expects what opening the app would show.
  - The fetch runs from the activity's `onStart`, and only after an `onStop` that wasn't a rotation, so a rotation still fetches nothing.
  - It's a foreground action, and nothing runs in the background.
- **Fetches of one connection are serialized in the repository,** with one lock per connection. A guard in each ViewModel can't see the others.
  - A second fetch waits, then reads again, so the newest page wins.
  - The list is published under the repository's lock, without requests whose answers have settled. A page read before an answer can't bring its request back.
  - A fetch that finishes after its connection was removed publishes nothing. `remove` doesn't wait for fetches, so the fetch checks under the same lock.
- **A request ID must be a UUID before it enters the inbox,** because it names the file its answer is stored in. A request whose ID isn't one is left out, like another connection's request.
- **Retention counts from `settledAt`.**
  - Answers stored before this change have no `settledAt`, and count from `answeredAt` as before.
  - The file format stays at version 1, because the field is optional.
- **Device names are escaped when printed, not refused at pairing.**
  - Escaping covers names already stored, and needs no change to the contract.
  - Control, format, and line separator characters print as `\u{…}`, and backslashes are doubled, so the output can't be mistaken for an escape.
- **The stores moved into `storage/`, and the guard now checks for SQL.**
  - `AGENTS.md` and `docs/development/sidecar.md` said only `storage/` touches SQLite. The test checked imports only, while `RequestStore` and `PairingStore` ran SQL through the database handle.
  - Moving the two stores was a file move, with no change in behavior.
  - The pure rules stay in `requests/` and `pairing/`. `RequestFailure` moved to `requests/failure.ts`, so the workflow's error type doesn't live in storage.
- **A removal is final, for replies too.** The fetch guard didn't cover `SubmitResult` replies. `settle` and the retry handler wrote back the answer they had captured before the send, so a reply that came back after `remove` recreated the connection's answers.
  - Every write of a delivery outcome now rereads the connection and the stored answer under the repository's lock, the lock `remove` holds, and writes nothing if either is gone.
  - It writes from the stored answer, not the captured one. A retryable failure leaves an answer that something else settled meanwhile, such as a revocation, as it is.

## 2026-09-12 — SAW-015, the wallet binding

- **The app never becomes a wallet, so the boundary is one interface.** `wallet/WalletAdapter.kt` has `connect` and `disconnect` and six outcomes, and `MwaWalletAdapter` is the only file that imports the Mobile Wallet Adapter client.
  - Every test drives `FakeWalletAdapter`, so success, no wallet, a refusal, an expired authorization, an unsupported network, and a changed address all run without a wallet app or an activity association.
  - MWA needs an Activity, so `MainActivity` registers the `ActivityResultSender` in `onCreate` and clears it in `onDestroy`, and the Application hands it to the adapter. A dedicated activity or a foreground service would have been the alternative, and neither is needed.
- **The wallet refusing a fresh request and refusing a stored authorization are the same MWA error.** `AUTHORIZATION_FAILED` covers both, so the adapter tells them apart by whether the phone offered an authorization: with one, the authorization expired; without one, the owner declined.
- **The sidecar stamps `bound_at`.** Every other timestamp in the contract is the sidecar's, and a phone clock that's behind would otherwise show agents a binding older than the one it replaced. `PublishWalletRequest.binding.bound_at` is documented as ignored, and the fixture leaves it unset.
- **`RequestStore.create` now applies the binding rule instead of refusing every wallet kind.** Stage 2 refused them all with `WALLET_MISMATCH` and the message "wallets connect from Stage 3", which stops being true here.
  - A wallet action is refused with `WALLET_NOT_CONNECTED` or `WALLET_MISMATCH`, and stored otherwise.
  - No MCP tool creates one yet, so nothing an agent can call changes. The tools arrive with the later tasks of Stages 3, 4, and 6.
- **`sign_message` is bound to a wallet but not to a network.** A signature over bytes doesn't depend on a cluster, so `actionBinding` returns no network for it, and changing only the network leaves such a request pending.
- **Publishing is per connection, and repeated rather than queued.** The repository remembers which connections have heard the current binding, in memory only.
  - Connecting, disconnecting, opening the app, and pairing a new connection each publish what's missing. An unchanged binding is a no-op at the sidecar, so a repeat costs nothing.
  - A connection that couldn't be reached is named on the screen, with **Tell them again**. There is no background retry, in keeping with the rest of the app.
- **The owner picks the network, and the app reports what the wallet says.** It offers Mainnet, Devnet, and Testnet, and never assumes the installed wallet serves any of them.
  - `CLUSTER_NOT_SUPPORTED` becomes "The wallet doesn't serve this network."
  - When the wallet returns the account but doesn't list the chosen chain among its chains, the screen says the wallet didn't confirm it, rather than claiming it did. A wallet that lists no chains has contradicted nothing.
  - What Seed Vault Wallet actually offers on the Seeker is step 9 of the owner's checks in `docs/testing/stage-3.md`, and is recorded as NOT RUN.
- **`wallet/storage/` seals its own file rather than reusing `CredentialVault`.** The vault is keyed by connection ID, which the wallet's authorization has none of. Both use the same Keystore key and the same file format, with different associated data, and `StageBoundaryTest` now allows storage APIs in both packages.

## 2026-09-12 — SAW-016, manual message signing

- **No `packages/protocol/proto/` change was needed, so none was made.** SAW-009 already defined `SignMessageAction`, `Approval`, `MessageSignature`, and `COMPLETED`. The alternative was carrying the wallet's reported bytes in `MessageSignature` so the sidecar could compare them.
  - That comparison is already implied: the sidecar verifies the signature against the bytes it stored, so a signature over anything else can't be accepted. Carrying the bytes would add a field that changes no decision.
  - The phone still checks that the wallet returned the bytes it asked for, and records a failure otherwise, so a wallet that signs something else is caught before anything is sent.
  - The agent gets the exact bytes back as `signed_message_base64`, derived from the stored action, which is what it needs to verify without re-deriving the encoding.
- **The sidecar verifies signatures, and the guard now says it signs none.** `requests/signature.ts` uses `node:crypto` with the raw Ed25519 key wrapped in its SPKI header; a Solana address *is* the public key, so nothing else is needed. `stage-boundary.test.ts` gained a check that no shipped source calls a signing API, beside the existing one for key creation.
- **"Independent verifier" means independent of this repository.** `signature.test.ts` uses the RFC 8032 §7.1 known-answer vectors, which come from the specification, and rejects tampered copies of each. `tools/test-agent/src/verify.ts` is a second implementation that shares no code with the sidecar's, and `pnpm agent get` runs it over a real round trip.
- **A lost message signing is FAILED, not UNKNOWN.** UNKNOWN exists for a transaction that may be on chain. A message signature is never broadcast, so one the phone never received exists nowhere; leaving such a request UNKNOWN would leave it non-terminal forever, with nothing that could ever settle it. `ConnectionRepository.load` turns an approval the wallet never answered into an execution failure, and sends the approval first so the sidecar sees the same order.
- **The approval is stored and sent before the wallet is opened, in two submissions.** The lifecycle already requires PENDING → PROCESSING before a result, and the phone's outbox already resends what the sidecar hasn't taken.
  - `LocalResult` gained `approved` and `signing`, and the stored file went to version 2, reading version 1 as it was.
  - `deliver` sends the approval when it hasn't been accepted yet, then the outcome when there is one. Both retries are idempotent at the sidecar.
- **The ViewModel sequences the approval and the wallet, not the repository.** `WalletRepository` already depends on `ConnectionRepository` for publishing, so the reverse dependency would be a cycle. `InboxViewModel.approve` stores and sends the approval, asks `WalletRepository.sign`, and records the outcome; `ConnectionRepository` knows nothing about wallets.
- **A changed selection stops the approval rather than signing.** `approve` takes the wallet the screen showed and compares it with the one connected now; a difference reports "your wallet changed" and reaches no wallet. `WalletRepository.sign` checks again under its own lock, so a change that slips through the first check still asks the wallet nothing.
  - The sidecar's own rule already cancels a PENDING request a new binding no longer fits, so this only covers the window where the phone hasn't learned that yet, such as an unreachable sidecar.
- **Invisible characters are marked where they are, not stripped or escaped wholesale.** `visibleText` keeps a line break as a line break and adds `␊`, maps the other control characters to their Control Pictures symbol, and writes zero-width, bidirectional, and no-break characters as `<U+XXXX>`. The message itself is never changed: the wallet signs the original bytes.
- **`vault_get_capabilities` lists only what is served.** It reports `approval: "manual"` and `signing: "wallet"` as literals rather than as anything configurable, and `operations` is built from the tools actually registered, so a later stage can't leave it claiming something that isn't there.

## 2026-09-12 — SAW-017, the wallet lifecycle and reliable result delivery

- **An answer the phone never received is its own outcome, not a wallet failure.** SAW-016 recorded
  it as `SigningOutcome.Failed`, which reads as "the wallet told us it couldn't sign". It never did:
  the app died, or the wallet never came back. `SigningOutcome.Unresolved` says exactly that on
  screen, and `ResultStore` went to version 3 for it.
  - On the wire it is still `execution_failure`, and the request is still FAILED. Nothing was
    broadcast, so a signature that never reached this phone exists nowhere; there is no third state
    for the sidecar to be in, and UNKNOWN would leave the request non-terminal forever.
  - It is never retried at the wallet. A signature the owner still wants is a new request they
    review afresh.
- **Abandoned signings are settled when the app comes back, not only when it starts.**
  `ConnectionRepository.load` runs once per process; an approval left open by a ViewModel that went
  away would otherwise wait for the rest of the session. `InboxViewModel.onAppVisible`, from
  `MainActivity.onStart`, sweeps on every return to the foreground.
  - Coming back from the wallet app is such a return, so the sweep takes the keys currently being
    sent as an exclusion set: a signing this process is still waiting for is left alone. After a
    process death that set is empty, which is exactly right.
  - The first outcome stored still stands, so a late answer from a coroutine that outlived the
    sweep changes nothing.
- **The wallet call has a timeout, ten minutes.** It is the owner's own time in the wallet app, so
  it is generous; the point is only that a wallet which never answers at all can't hold a request
  open forever. The timeout produces the same unresolved outcome.
- **Delivery serializes per request instead of dropping.** A second send of the same answer used to
  return null, which could leave a signature stored but unsent until the next refresh — the exact
  race between coming back from the wallet (which refreshes) and the wallet's answer arriving.
  `deliver` now waits for the send in flight and returns what it settled.
  - A refresh keeps the old behaviour and skips: it has other answers and pages to get through, and
    waiting for a send already on its way would gain it nothing. It would also deadlock the case
    where a refresh runs while a held send is being tested.
- **The Mobile Wallet Adapter sender waits for the next screen's.** A rotation destroys one activity
  before creating the next, so there is a moment with no `ActivityResultSender` at all. Failing an
  approval the owner just gave with "the app's screen closed" would be wrong; the adapter's `sender`
  is now suspending and waits up to five seconds. `detachWalletActivity` also takes the activity, so
  only the one that registered a sender clears it.
- **No sidecar change was needed for repeated results.** `RequestStore.submit` already compares the
  submitted result with every result stored for that request, byte for byte, and returns the current
  request on a match; the results table makes that survive a restart. The task added the tests that
  say so, for an approval and a signature, before and after reopening the database.
- **Connection scoping was already right, on both sides.** An answer is keyed by connection and
  request ID, goes to that connection's own URL with its own credential, and the sidecar refuses a
  reference naming another connection's request. The task added the end-to-end checks: the same
  request ID on two servers, answered on one only.

## SAW-018 — the wallet setup guide and the Stage 3 device checks (SEE-26)

- **The task adds no behaviour, so it adds tests instead of prose where it can.** "This stage needs no
  funds, swaps, agent keys, or custom biometrics" is the kind of claim that rots silently, so it is a
  boundary test on each side rather than a sentence: the sidecar's pins every registered MCP tool to
  the seven that exist and fails on a chain RPC, a broadcast API, or any key material in a shipped
  source; the app's fails on a transaction API, a biometric or device-credential API, or a biometric
  library on the classpath. Each scan carries a positive control, so it can't pass by finding nothing.
- **The app asks for no authentication of its own, on purpose.** The owner taps **Approve and sign**,
  and the wallet decides whether it wants a PIN, a fingerprint, or a face. Adding a second prompt
  would teach the owner to approve twice for one action, and would put this app in the business of
  guarding a key it doesn't hold.
- **The Hermes signing section says where its results come from.** Sections 3 and 4 of that page are
  recorded Hermes runs; the wallet tools have only been driven by `pnpm agent` and the automated
  tests. Rather than dress those up as a Hermes transcript, the section is marked as not yet run and
  the owner's device script covers it. A plausible transcript would have been indistinguishable from
  a fake one.
- **The wallet's version and network path are a table to fill in, not a claim.** Which networks Seed
  Vault Wallet serves, and what it shows while signing, are properties of the installed wallet, so
  the guide records them from the device rather than asserting them here.
- **A development wallet is explicitly not the acceptance check (R8).** `FakeWalletAdapter` may
  exercise error handling, and every automated test uses it; the stage is accepted only when the
  owner's own Seeker signs by hand. The verification record therefore has two tables, automated and
  physical, and the physical one is entirely NOT RUN.

## 2026-09-12 — The wallet's refreshed authorization, and text-only signing

- **The signing answer carries the authorization, rather than the adapter exposing it.** Mobile
  Wallet Adapter reauthorizes this app at the start of every session and may replace its token, and
  `TransactionResult.Failure` carries no `AuthorizationResult`. The alternatives were reading
  `MobileWalletAdapter.authToken` back after the call, which depends on the library updating its own
  field, or a second wallet session, which would ask the owner twice. Instead `signMessage` answers
  with a `SigningAnswer`, and `MwaWalletAdapter` reads the `AuthorizationResult` that `transact`
  hands its block, before asking for the signature. A declined signature therefore still carries a
  working authorization, and the boundary stays one call per interaction.
- **A replacement is stored, and the selection isn't touched.** `store.put` writes the selection and
  the authorization together, so the repository passes the selection back unchanged: the wallet,
  address, and network the owner reviewed can't move because a token did, and nothing is published.
- **A storage failure while keeping a replacement is swallowed, deliberately.** The signing outcome
  matters more than the newer token: with the old one the next signing is refused and the owner
  connects the wallet again, which is exactly what an expired authorization already does. Throwing,
  as `connect` does, would have lost a signature the owner had just approved.
- **`vault_sign_message` takes text only, and the protobuf keeps its `data` form.** SEE-24 scoped the
  first signing tool to messages the owner can read, and `message_base64` let an agent queue bytes
  nobody can review. Removing the field from the protocol was the alternative; it would be a
  contract change for a field a later stage wants, so the field stays and no tool served here can
  create one. `messageBytes` still handles both forms, and the phone still renders a `data` message,
  so nothing has to be rewritten when a later stage needs it.
- **Invisible characters are found by Unicode category, not by a list.** The hand-written ranges
  missed U+061C and every tag character, and worked on chars, so a supplementary code point arrived
  as two surrogate halves. `Character.getType` over code points is the same question asked of the
  platform's own tables, and a code point those tables don't know is marked rather than shown: for
  bytes the owner is about to sign, the safe way round is to over-mark.

## SAW-024 (PR #6 review)

- **Ed25519 verification is written out on the phone, not taken from the platform and not added as a dependency.** `Signature.getInstance("Ed25519")` arrives in API 33 and the app supports 31, so a platform check would silently not happen on two API levels; a library for one verification is more surface than the 150 lines it replaces, and the app already derives program addresses with the same field arithmetic. Nothing verified here is secret, so nothing here needs to be constant-time, and `Ed25519Test` holds it to the JDK's own verifier.
- **A message approval the sidecar didn't take is kept, while a transfer's is deleted.** Both stop the wallet from opening. They differ afterwards because a transfer's approval names a preparation that goes stale, so the owner must review a fresh one, while a message's bytes never change: the answer the sidecar is owed is already on disk, it is sent again by itself, and an approval with no wallet answer settles as unresolved rather than being silently dropped.
