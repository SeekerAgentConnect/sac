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
