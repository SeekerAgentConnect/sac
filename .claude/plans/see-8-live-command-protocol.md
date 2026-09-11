# SEE-8 / SAW-002 — Define the minimal live-command protocol and generate clients

Linear: https://linear.app/seekeragentwallet/issue/SEE-8 · Branch: `develop`

## Checklist

- [x] `proto/seekervault/live/v1/live.proto`:
  - [x] `LiveCommand`, `CommandAcknowledgement`, `AcknowledgementResult`
  - [x] `LiveCommandError`
  - [x] `WatchCommands` (server stream, `ready` first) and `AcknowledgeCommand`
- [x] `buf.gen.yaml`: managed Java package; separate `generated/java` and `generated/kotlin` directories
- [x] `scripts/generate.mjs`: `buf generate` plus `buf convert` fixtures; `--check` compares against a temporary directory
- [x] Root scripts:
  - [x] `generate`, `check:generated`
  - [x] `buf format` and `buf lint` in `pnpm check`
  - [x] CI step `pnpm check:generated`
- [x] Sidecar:
  - [x] `@bufbuild/protobuf` runtime
  - [x] `src/live/command.ts` rules and `LiveCommandSlot`
  - [x] rule tests and fixture tests
  - [x] `allowJs` build
- [x] Android:
  - [x] generated source dirs and fixture test resources
  - [x] `connect-kotlin` and `protobuf-kotlin-lite`
  - [x] `isExpiredAt`, fixture tests, and deadline tests
- [x] Docs:
  - [x] `docs/protocol.md`, README, `CODEBASE.md`
  - [x] RFC contract rows, toolchain, `AGENTS.md`
  - [x] changelog, decisions
- [x] Verify:
  - [x] `pnpm check`
  - [x] `pnpm check:android`
  - [x] `pnpm build` and a run of the built output
  - [x] generate twice with no diff; `check:generated` catches drift
  - [x] deliberate failures
- [ ] Commit, push, and confirm CI is green
- [ ] Linear: tick the SEE-8 checklist and move it to In Review

## Review

**What changed.**

- **Contract:** `proto/seekervault/live/v1/live.proto` defines `LiveCommandService`:
  - a `WatchCommands` server stream that always opens with `ready`
  - a unary `AcknowledgeCommand`
  - `LiveCommand {id, text, expires_at}` and `CommandAcknowledgement {id, result: OK}`
  - the `LiveCommandError` reasons: OFFLINE, BUSY, TIMEOUT, CANCELLED, UNAUTHENTICATED, UNKNOWN_COMMAND, INVALID_TEXT
- **Generation:** Buf generates TypeScript (`sidecar/src/gen`) and Kotlin (`android/app/src/main/generated`), and both are committed. `pnpm check:generated` guards against drift, and CI runs it.
- **Protocol rules:** the sidecar implements them as pure code in `sidecar/src/live/command.ts`, covering text validation, the deadline boundary, and the single in-flight slot with the latest outcome. Android gets `LiveCommand.isExpiredAt` with the same boundary.
- **Fixtures:** `buf convert` writes them from JSON, and the TypeScript and Kotlin tests both check them byte for byte in both directions.

**How it was verified.** The results are in the verification record in `docs/protocol.md`. Two more `pnpm generate` runs produced no diff, and each deliberate break was caught by the matching check:

- drift in generated code, fixtures, or stray files
- a corrupted fixture, which failed both runtimes
- a proto lint violation
- a proto format violation

**Caveats:**

- **The phone-side handling is designed but not built.** The protocol defines where the phone sees each error, and SAW-004 implements it.
- **The error set is fixed; the MCP JSON shape is not.** The MCP tool's exact JSON result is SAW-003's, and it uses the error names defined here.
- **Generation needs the Buf Schema Registry.** `pnpm generate` and `pnpm check:generated` call the remote Kotlin plugins, so they need network access.
