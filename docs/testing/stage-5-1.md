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

## SAW-046 — sourced review and fresh approval

Automated on 2026-09-13 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.20, and the build's pinned Temurin 21.0.12.1 toolchain. Gradle was launched by Oracle JDK 19.0.2. The installed Android SDK was supplied through `ANDROID_HOME`; no `local.properties` or other machine-specific file was written.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS — Prettier, Buf format/lint, ESLint, both TypeScript type checks, 396 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — all 9 Stage 1 simulated-device acceptance cases |
| `pnpm test:queue` | PASS — all 7 Stage 2 two-sidecar acceptance cases |
| `ANDROID_HOME=… pnpm check:android` | PASS — Spotless, all 729 Android unit tests, Android lint, debug APK, and instrumentation APK |
| `pnpm check:generated` | PASS — generated protocol code and fixtures are current; this ticket changes no protocol |
| `git diff --check` and documentation Prettier | PASS |

The evaluator, ViewModel, Compose, Activity storage/details, and existing boundary tests cover:

- Global, Connection override, and Not configured source labels for every effective check, including a connection with no override inheriting configured global rules;
- independent Global daily and Connection daily rows with confirmed, unresolved, and projected totals, including global-only, connection-only, both-warning, and neither-configured cases;
- separately named unreadable global, connection, and both-document states, alongside accurate uncovered checks;
- complete Activity reloads and both policy reads on review, new preparation, foreground return, and the final affirmative check;
- a same-looking global edit while transfer approval waits for the wallet lock, a connection reset to inheritance, and a re-preparation all invalidating consent before the sidecar or wallet is asked;
- cross-connection unresolved exposure, confirmation, and chain failure refreshing both scoped daily compositions and clearing affected consent;
- both daily warnings using the existing one deliberate override step, while a matching verdict remains manual, Reject needs no override, and failed transaction verification cannot be overruled;
- stable rule-source and daily scope/source/status/reason codes surviving later rule edits, additive decoding of old Activity snapshots, and no allowlist, threshold, counter, policy decision, or policy reason in a sidecar or wallet payload.

### Deliberate breaks

Both mutations were applied, run, observed failing, reverted, and followed by a passing focused run:

| Break | Expected failure observed |
| --- | --- |
| Warning consent ignored the applicable effective policy | `InboxViewModelTest.aGlobalEditWithTheSameRenderedWarningStopsAfterTheWalletLockWait` failed at its `RulesChanged` assertion, proving a same-looking global edit would otherwise pass the stale assessment guard |
| Assessment reused the process's Activity snapshot instead of reloading disk | Both focused cross-connection tests failed: newly unresolved exposure did not stop approval, and foreground confirmation/failure did not clear consent |

## SAW-047 — end-to-end acceptance and owner walkthrough

Automated on 2026-09-13 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.20, and the build's pinned Temurin 21.0.12.1 toolchain. Gradle was launched by Oracle JDK 19.0.2. The installed Android SDK was supplied through `ANDROID_HOME`; no `local.properties` or other machine-specific file was written.

**Revision under test:** the SEE-63 working tree on top of `577968d` (SAW-046). The final SEE-63 commit SHA is recorded in Linear with this report; a commit cannot contain its own SHA.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS — Prettier, Buf format/lint, ESLint, both TypeScript type checks, 396 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — all 9 Stage 1 simulated-device acceptance cases |
| `pnpm test:queue` | PASS — all 7 Stage 2 two-sidecar acceptance cases |
| `ANDROID_HOME=… pnpm check:android` | PASS — Spotless, all 737 Android unit tests, Android lint, debug APK, and instrumentation APK |
| `pnpm check:generated` | PASS — generated protocol code and fixtures are current; this ticket changes no protocol |
| `git diff --check` and documentation Prettier | PASS |
| Network tests | NOT RUN — the acceptance suite uses committed deterministic transaction bytes and temporary phone-local stores |

`Stage51PolicyScenarioTest` is the combined acceptance layer. It takes `sol_transfer` from `fixtures/transactions/cases.json`, inspects those bytes through the phone's real decoder, reloads versioned policy and Activity documents from temporary on-disk stores, evaluates the current effective rules, and carries the result into Request details. It asserts exact verdicts, ordered reason codes, uncovered checks, rule sources, scoped daily statuses and totals, input-verification precedence, and manual action availability.

The scenarios cover:

- global Programs and a connection Recipient applying as one conjunction, with both sources visible;
- a connection Programs list replacing the global list whole, reset restoring inheritance, and a connection with no override file inheriting immediately after pairing;
- global exceeded/local passed, local exceeded/global passed, both exceeded, both exactly equal, and a higher or absent local threshold unable to suppress a global warning;
- confirmed and unresolved totals, two connections sharing a wallet, another wallet, another network, and retained Activity from a connection with no live policy document;
- a Stage 5 version 1 document migrating without a global file or assessment change, followed by a new store and Activity read over the same directories;
- damaged global rules, damaged connection rules, both together, and a damaged Activity record, none of which becomes missing or an empty day;
- same-looking global edits and cross-connection spending producing different consent, backed by `InboxViewModelTest` cases that stop stale approval before either sidecar or wallet;
- inherited and overridden source labels, both daily rows, and the deliberate warning step at twice the system font scale.

The Stage 5 regressions remain in the required Android suite: `PolicyEvaluatorTest.aDayNobodyHasReadYetIsNotADayWithNothingInIt`, `InboxViewModelTest.aTransactionPreparedAgainTakesTheOwnersWordWithItHoweverTheRulesRead`, and `PolicyEditorViewModelTest.editsTypedWhileSavingAreNotMarkedAsSaved`. The SAW-046 foreground, wallet-lock, local-reset, and cross-connection stale-review tests remain alongside them.

### Deliberate break

The mutation was applied, run, observed failing, restored, and followed by a passing complete `Stage51PolicyScenarioTest` run:

| Break | Expected failure observed |
| --- | --- |
| Connection `Replace` incorrectly retained the global value | `aProgramOverrideReplacesGlobalAndResetAndNewPairingRestoreInheritance` failed at the expected UNDER_RESTRICTIONS verdict, proving the acceptance path detects a hidden list union |

## Physical Seeker checks — SAW-047

**All checks 79–100: NOT RUN.** No physical Seeker was attached or controlled for SEE-63. Robolectric, an APK build, and a deterministic transaction fixture do not count as device evidence. No network test ran and no transfer was requested, approved, signed, or sent.

When the owner runs this table, use the existing devnet setup and a recipient they control. The 2.5 SOL request below is a deterministic review amount, not permission to spend it: reject it or cancel in the wallet. A real transfer is separately opt-in through [`docs/guides/transfers.md`](../guides/transfers.md#your-first-transfer-step-by-step) with a deliberately small devnet amount. Cross-connection confirmed/unresolved Activity checks likewise remain NOT RUN unless the owner separately authorizes the prerequisite devnet action. Never substitute mainnet.

| # | Check on the physical Seeker | Expected | Result |
| ---: | --- | --- | --- |
| 79 | Install the SEE-63 debug APK, open **Connections**, and find **Global rules**. | The phone-wide entry is reachable without opening a connection. | NOT RUN |
| 80 | Open **Global rules**. | The title and introduction are global; no connection is presented as owning the document. | NOT RUN |
| 81 | Add the programs needed by the reviewed devnet SOL fixture and a 2.5 SOL global daily threshold, then save. | The summary shows the list and exact `2500000000` base-unit threshold; save returns without changing a connection override. | NOT RUN |
| 82 | Open connection A → **Rules**. | Programs and the daily context say **Global**; untouched sections say **Use global** or **Not configured** in words. | NOT RUN |
| 83 | Override Recipients with the fixture recipient and save. | Recipient says **Connection override** while Programs stays **Global**. | NOT RUN |
| 84 | Override Programs with a different complete list. | The effective list is the connection list only; no global item is described as merged or added. | NOT RUN |
| 85 | Choose **Reset connection overrides**, save, and reopen connection A's Rules. | The local recipient and program values are gone, inheritance is restored, and Global rules are unchanged. | NOT RUN |
| 86 | Pair connection B and open its Rules before saving anything locally. | It inherits the current global values; pairing created no copied local policy. | NOT RUN |
| 87 | Review a 2.5 SOL request with global daily 2 SOL and connection daily 3 SOL. Do not send it. | Global daily warns at 2.5 of 2; Connection daily matches at 2.5 of 3. Both rows show confirmed, not-yet-settled, and projected amounts. | NOT RUN |
| 88 | Change only the values to global 3 SOL and connection 2 SOL, then reopen/reprepare the request. | Global matches and Connection warns, with the same request bytes and independently named scopes. | NOT RUN |
| 89 | Set both daily thresholds to 2 SOL and reopen/reprepare. | Both rows warn and both exact `over_daily_limit` reasons are retained. | NOT RUN |
| 90 | Set both daily thresholds to exactly 2.5 SOL and reopen/reprepare. | Equality matches in both scopes; neither uses a strict-less-than boundary. | NOT RUN |
| 91 | Set global to 2 SOL, then test a 3 SOL local threshold and no local threshold. | The global warning remains in both cases; a higher or absent local value never bypasses it. | NOT RUN |
| 92 | If retained devnet Activity exists for a removed connection using this wallet, review the matching asset. Do not create spending for this check without separate authorization. | Its amount appears in Global daily and not in connection A's daily row. | NOT RUN |
| 93 | Compare retained records for another wallet or network. | Neither contributes to this wallet's devnet SOL rows. | NOT RUN |
| 94 | On any warning, tap **Reject** without ticking the warning box. | Rejection is immediately available and opens no wallet. | NOT RUN |
| 95 | On a fresh warning, try the affirmative button, tick the deliberate-warning box, then continue only as far as the wallet and cancel there. | The button waits for the tick; the wallet still asks. Cancel sends no transfer. | NOT RUN |
| 96 | Tick a warning, leave Request details open, edit Global rules so the rendered warning can remain the same, then return and try to proceed. | The tick clears or the final check stops with a fresh review; the stale action reaches neither sidecar nor wallet. | NOT RUN |
| 97 | If separately authorized retained Activity on connection B changes while A is open, return to A. | Both daily compositions reload, affected consent clears, and a stale affirmative action stops. | NOT RUN |
| 98 | Force-stop and reopen the app, then revisit Global rules, connection A Rules, Activity, and the pending request. | Stored values, inherited/override sources, and retained totals survive restart and read the same. | NOT RUN |
| 99 | Set Android to its largest system text size and repeat checks 82, 83, and 89. | Global/Connection labels, both daily rows, totals, warning control, Approve, and Reject remain readable and reachable without clipped meaning. | NOT RUN |
| 100 | Enable TalkBack and repeat checks 82, 83, and 89 by swipe navigation. | TalkBack announces inherited versus overridden sources, Global daily versus Connection daily, all three totals, statuses, and both manual actions in a meaningful order; no meaning depends on colour. | NOT RUN |

### Device record

| Field | Value |
| --- | --- |
| Date | 2026-09-13 |
| Commit | NOT RUN — use the final SEE-63 commit recorded in Linear |
| Device | NOT RUN |
| Android version | NOT RUN |
| Wallet and version | NOT RUN |
| Network | NOT RUN; devnet only if the owner later opts in |

## Release-check handoff

The cross-component regression in [SEE-52](https://linear.app/seekeragentwallet/issue/SEE-52/saw-039-run-cross-component-reliability-and-security-regression-checks) can cite the SAW-047 automated matrix and rerun the same repository commands. The guide verification in [SEE-54](https://linear.app/seekeragentwallet/issue/SEE-54/saw-041-verify-all-owner-guides-against-the-release) can start from checks 79–100 and the [owner flow](../guides/policies.md#stage-51-owner-flow-and-states). Neither release task should promote the hardware rows to PASS without the owner's own Seeker record.
