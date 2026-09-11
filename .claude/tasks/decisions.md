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

- **`pnpm agent` runs `node test-agent/src/main.ts` directly.** A plain `pnpm run` keeps the script's exit code, but `pnpm --filter …` turns every failure into 1, which was measured.
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

- **`pnpm test:hello` runs `test-agent/src/stage1.acceptance.ts`, one case per acceptance scenario.** The file name keeps it out of the package test glob, so `pnpm check` doesn't run it twice; CI runs `pnpm test:hello` as its own step. The suite runs the real CLI against the sidecar as a separate process (`sidecar/src/testing/process.ts`), because the restart cases need a process to kill.
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
