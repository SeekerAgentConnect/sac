# Stage 5.1 tests

Stage 5.1 extends the accepted phone-local policy layer with global defaults, per-connection overrides, and an additional daily threshold across connections. It does not change the rule that policies decide nothing and never reach a sidecar.

## SAW-043 — model, resolution, storage, and migration

Automated on 2026-09-13 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.0, and the build's pinned Temurin 21 toolchain. Gradle was launched by Oracle JDK 19.0.2. The installed Android SDK was supplied through `ANDROID_HOME`; no `local.properties` or other machine-specific file was written.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS — Prettier, Buf format/lint, ESLint, both TypeScript type checks, 396 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — all 9 Stage 1 simulated-device acceptance cases |
| `pnpm test:queue` | PASS — all 7 Stage 2 two-sidecar acceptance cases |
| `ANDROID_HOME=… pnpm check:android` | PASS — Spotless, all 677 Android unit tests, Android lint, debug APK, and instrumentation APK |
| `pnpm check:generated` | PASS — generated protocol code and fixtures are current; this ticket changes no protocol |

The first Android invocation stopped before compilation because this worktree had neither `ANDROID_HOME` nor a machine-local `android/local.properties`. The SDK was present at the standard user installation and was passed to later commands only through their environment. A later preflight found three new Kotlin formatting differences; `spotlessApply` fixed them, and the complete `pnpm check:android` command above then passed.

The new table-driven and storage tests cover:

- global programs combined with local recipients;
- full same-section replacement without list union;
- inheritance, explicit local no-check, and an explicit empty allowlist as distinct states;
- per-asset and per-network per-request threshold resolution with exact source metadata;
- separate global and connection daily thresholds, including retention of the global value after an asset replacement, local no-check, reset, or missing connection document;
- a valid global daily threshold below a local per-request threshold without cross-scope malformed-policy rejection;
- a newly paired connection's no-document inheritance;
- version 1 migration, unchanged Stage 5 assessment, restart, repeated migration without a rewrite, and no inferred global file;
- recovery from an interrupted atomic write, unknown and newer schemas, missing versus unreadable documents, storage isolation, and deletion of either scope without deleting the other;
- removal of a real connection through `ConnectionRepository` deleting its override while preserving the global document.

### Deliberate breaks

Both mutations were applied, run, observed failing, reverted, and followed by a passing focused run:

| Break | Expected failure observed |
| --- | --- |
| A local replacement incorrectly kept the global value | Three `EffectivePolicyTest` cases failed: whole-section replacement, inherit/no-check/empty distinctions, and per-asset threshold resolution |
| A missing version 1 field migrated to `NoCheck` instead of `Inherit` | `PolicyStoreV2Test.version1MigratesOnceAndKeepsExistingBehaviorWithoutCreatingGlobalRules` failed |

## Physical-Seeker checks

**NOT RUN.** SAW-043 changes pure policy models and phone-local JSON storage, with no screen or wallet behavior. Robolectric tests and successful APK builds do not count as a physical-device pass. Stage 5.1's later UI and end-to-end tickets own the new device walkthrough.
