# SEE-6 / SAW-001 — Bootstrap the repository, toolchains, and basic CI

Linear: https://linear.app/seekeragentwallet/issue/SEE-6 · Branch: `develop` (single develop → master PR after SEE-6…SEE-14)

## Decisions

- **Versions:** latest stable releases, verified against registries on 2026-09-11. The one exception is TypeScript 6.0.3, because typescript-eslint 8.70 supports `typescript <6.1`, so TypeScript 7.0 is not usable yet.
- **Node 24 LTS**, pinned in `.nvmrc` and `engines`. **pnpm 12**, pinned through `packageManager`. The workspace is `sidecar` only; the test agent joins in SAW-005. No monorepo orchestration framework.
- **Sidecar:** runs as plain TypeScript through Node's type stripping (`erasableSyntaxOnly`) and is tested with `node:test`. No bundler and no test framework dependency.
- **JDK:** pinned for Gradle through `gradle/gradle-daemon-jvm.properties` (JDK 21, auto-provisioned). Android Studio Panda+ honours the same criteria.
- **Buf:** config and generator pins now. Protos, generated code, and protocol checks in `pnpm check` and CI come in SAW-002, because Buf fails on a module with no `.proto` files.
- **Later commands:** `pnpm agent` and `pnpm test:hello` are placeholders that exit 1 until SAW-005 and SAW-008. They never report fake success.
- **Application ID:** `io.github.brrenat.seekervault`. minSdk 31, compile and target SDK 37.

## Checklist

- [x] Root workspace: `package.json` (command contract), `pnpm-workspace.yaml`, `.nvmrc`, `.gitignore`, `.env.example`, Prettier and ESLint config
- [x] Sidecar: config loading and validation, tests, `dev`, `build`, `typecheck`, and `test` scripts
- [x] Protocol scaffolding: `buf.yaml`, `buf.gen.yaml` with pinned generators, and a `proto/` placeholder
- [x] Android: Gradle wrapper 9.7.1 with checksum, version catalog, AGP 9.4 with built-in Kotlin, Compose + Material 3 empty screen, Spotless/ktfmt, daemon JVM criteria
- [x] Placeholders: `test-agent/`, `gateway/`
- [x] CI: `.github/workflows/ci.yml` with Node and Android jobs, actions pinned by SHA, read-only permissions
- [x] Docs: `README.md`, `AGENTS.md`, `docs/development/toolchain.md`, the `RFC.md` Stage 1 update, changelog, `CODEBASE.md`, decisions
- [x] Verify: install, check, build, `dev:sidecar`, `check:android`, and APK path
- [x] Verify: a deliberate failure in each check fails it (format, lint, type, test, Kotlin test, Kotlin compile)
- [x] Verify: a clean clone builds; no credentials or machine-specific paths in tracked files
- [ ] Commit on `develop`, push to `origin/develop`, CI green

## Review

**What changed.** The repository now builds an empty Compose app and a TypeScript sidecar from pinned tools. The sidecar only validates its Stage 1 configuration. The full command contract is in place; commands owned by later tasks fail with a clear message. CI has a Node job and an Android job, and the docs above were added or updated.

**How it was verified.** Commands and results are recorded in `docs/development/toolchain.md` (Verification record). A fresh clone of the commit also passed every documented command, and the tracked files were scanned for credentials and local paths. Each check was also shown to fail on a deliberate break:

- **Node:** Prettier violation, ESLint error, `tsc` error, failing `node:test`.
- **Android:** failing JUnit test, Kotlin compile error, ktfmt violation, Android lint error.

**Caveats:**

- **Not run in this environment:** Android Studio (not installed) and a physical Seeker (no device). Both are marked NOT RUN; SAW-004 and SAW-006 cover them.
- **Buf needs a proto first.** `pnpm generate` fails until SAW-002 adds a `.proto` file. The generator pins were verified against a throwaway proto.
- **Local environment changes, outside the repo:** Node 24.21.0 installed via nvm; Android SDK command-line tools, Platform 37.0, Build-Tools 36.0.0, and Platform-Tools installed in `~/Library/Android/sdk`, with the SDK licenses accepted; Gradle downloaded Temurin 21 into `~/.gradle/jdks`.
- **`CLAUDE.md` changes:** it now points dev-process docs at `docs/development/`, the layout the tickets use, and references `AGENTS.md`. The empty `docs/dev/` was removed.
