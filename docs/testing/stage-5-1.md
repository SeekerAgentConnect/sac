# Stage 5.1 tests

Stage 5.1 extends the accepted phone-local policy layer with global defaults, per-connection overrides, and an additional daily threshold across connections. It does not change the rule that policies decide nothing and never reach a sidecar.

## SAW-043 — model, resolution, storage, and migration

Automated on 2026-09-13 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.0, and the build's pinned Temurin 21 toolchain. Gradle was launched by Oracle JDK 19.0.2. The installed Android SDK was supplied through `ANDROID_HOME`; no `local.properties` or other machine-specific file was written.

The first direct Gradle preflight omitted `ANDROID_HOME` and stopped before compilation because this worktree deliberately has no machine-specific `local.properties`. Every reported Android run below supplied the installed SDK through the environment.

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

## SAW-044 — effective evaluation and daily spending scopes

Automated on 2026-09-13 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.0, and the build's pinned Temurin 21 toolchain. Gradle was launched by Oracle JDK 19.0.2. The installed Android SDK was supplied through `ANDROID_HOME`; no `local.properties` or other machine-specific file was written.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS — Prettier, Buf format/lint, ESLint, both TypeScript type checks, 396 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — all 9 Stage 1 simulated-device acceptance cases |
| `pnpm test:queue` | PASS — all 7 Stage 2 two-sidecar acceptance cases |
| `ANDROID_HOME=… pnpm check:android` | PASS — Spotless, all 702 Android unit tests, Android lint, debug APK, and instrumentation APK |
| `pnpm check:generated` | PASS — generated protocol code and fixtures are current; this ticket changes no protocol |

The evaluation, counter, storage, application-wiring, request-fact, and stage-boundary tests cover:

- effective global and connection rules applied as one conjunction to the existing independently parsed facts, with exact source metadata and verification precedence unchanged;
- the global 10 SOL / connection 8 SOL example with A confirmed at 6 and B requesting 5, and the global 10 / connection 3 example with B requesting 4;
- equality and one-base-unit-above boundaries, global-only, connection-only and no daily thresholds, two simultaneous warnings, and a connection threshold above global that cannot suppress the global result;
- separation by wallet, network and asset in both scopes, while the global scope includes every connection;
- one confirmed signature deduplicated across connections, unresolved exposure settled by a confirmed or failed chain outcome, connection-qualified unsigned request identities, colliding request IDs, and current-attempt exclusion including a duplicate signature;
- retained records from removed or re-paired connections, policy and history restart behavior, and injected local midnight and timezone changes;
- missing history, a partly unreadable Activity store, an unreadable global document, and an unreadable connection document all withholding the affected assessment rather than passing as an empty value;
- saturating connection and global aggregates at `ULong.MAX_VALUE`, with comparison by remaining room so no overflow wraps into a pass;
- the policy package's read-only boundary: evaluating changes no policy or Activity record and reaches no wallet, sidecar, chain, or network.

### Deliberate breaks

The counter mutations below were applied, run, observed failing, reverted, and followed by passing focused and complete runs:

| Break | Expected failure observed |
| --- | --- |
| A global scope was incorrectly restricted to one connection | Eight focused tests failed across global aggregation, cross-connection signature deduplication, dimension separation, overflow, current-attempt handling, deleted-connection retention, and the 11-of-10 acceptance scenario |
| The current request was left in history before projecting it again | Both current-attempt exclusion tests failed, including the duplicate-signature case |

## SAW-045 — global and connection override editors

Automated on 2026-09-13 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.20, and the build's pinned Temurin 21.0.12.1 toolchain. Gradle was launched by Oracle JDK 19.0.2. The installed Android SDK was supplied through `ANDROID_HOME`; no `local.properties` or other machine-specific file was written.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS — Prettier, Buf format/lint, ESLint, both TypeScript type checks, 396 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — all 9 Stage 1 simulated-device acceptance cases |
| `pnpm test:queue` | PASS — all 7 Stage 2 two-sidecar acceptance cases |
| `ANDROID_HOME=… pnpm check:android` | PASS — Spotless, all 717 Android unit tests, Android lint, debug APK, and instrumentation APK |
| `pnpm check:generated` | PASS — generated protocol code and fixtures are current; this ticket changes no protocol |
| `git diff --check` and documentation Prettier | PASS |

The draft, ViewModel, Compose, navigation, storage-wiring, and accessibility tests cover:

- the Global rules row on the Connections dashboard and the retained connection Rules route;
- global programs with a local recipient, a whole program-list replacement, and accurate Global, Connection override, and Not configured source labels;
- inheritance, an explicit local no-check, a populated replacement, and an empty replacement as distinct visible and persisted states;
- per-asset per-request inheritance and override independently from the asset allowlist;
- read-only global daily context beside an additional connection daily threshold, including local reset retaining the global value;
- global edits refreshing inherited sections while an overridden section and an unsaved local draft retain their values;
- deleting/resetting either scope without deleting the other, including connection removal preserving the global document;
- save failures, opening and rotation without a write, dirty-draft confirmation, edits during a save, and deliberate recovery for unreadable global or connection documents;
- stock Material 3 controls at twice the system text size, complete address/asset removal labels for accessibility, and source/state information expressed in words rather than colour alone.

### Deliberate breaks

Both mutations were applied, run, observed failing, reverted, and followed by passing focused and complete runs:

| Break | Expected failure observed |
| --- | --- |
| **Use global** was incorrectly materialized as a local no-check override | 8 of 15 `PolicyEditorViewModelTest` cases failed across empty-document inheritance, mixed global/local resolution, per-request inheritance, daily reset, unreadable recovery, and saved-state distinctions |
| Refreshing global context replaced the unsaved local draft with its stored copy | Both focused preservation checks failed: the ViewModel state test and the real-activity navigation/storage test |

## Physical-Seeker checks

**NOT RUN.** SAW-045 adds owner-facing policy screens, so its large-text, TalkBack, navigation, and comprehension checks still require the owner's physical Seeker. Robolectric tests and successful APK builds do not count as a physical-device pass. Stage 5.1's end-to-end ticket owns that device walkthrough. SAW-043 and SAW-044 themselves changed models, storage, evaluation, and accounting without new owner-facing UI.
