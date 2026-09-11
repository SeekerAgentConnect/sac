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
