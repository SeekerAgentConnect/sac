# SEE-82 — approved v4 theme regression repair

SEE-82 repairs the presentation regression introduced by `d180549` without reverting that commit's transport, synchronization, notification, or correctness changes, or the later SEE-81 carousel endpoint and SEE-65 branding work.

## Reference and audit

The SEE-82 description and both HTML attachments were read through Linear MCP before implementation. Their exact bytes are checked in under [`docs/design/`](../design/README.md). The main reference was served locally and rendered with its live dark and light phones side by side; the flow map was rendered and read across all 12 screen states. Both loaded with no browser console errors.

The audit compared the UI-related diff in `d180549` with pre-regression revision `21ef9bf2ff72bb466a47e5922b291bd31aba2a20`. It restored only the removed theme definitions and semantic usages in Home/carousel/navigation, Pending requests, request review, Global/connection rules, and shared sheets/dialogs. Wallet and Activity already consume the common Material roles, so restoring the production theme restores their palette/type/shape values without replacing those screens. The later index-aware carousel behavior remains present.

## Regression coverage

`SeekerVaultThemeTest` asserts every approved dark and light colour role, the light-only readable `primaryText`, dim and destructive-text roles, representative type sizes/line heights, all five shape radii, and full opacity. It also reads the production `MainActivity` entry point, verifies that it wraps the application in `SeekerVaultTheme`, and rejects parameterless `darkColorScheme()` or `lightColorScheme()` calls. `StageBoundaryTest` independently retains the opaque-layer guard and the production lime-role/default-scheme checks.

## Automated verification

Implementation revision `8731f203ff2dc850b53112e17f42842856940d8f` was tested on 2026-09-15 on macOS 26.5.2 arm64 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, the project-pinned Kotlin plugin 2.4.20, and the configured Java 21 Temurin Gradle daemon (Java 19.0.2 launcher). The Android SDK was supplied through `ANDROID_HOME`; no machine-specific path was written to the repository.

| Command | Result |
| --- | --- |
| Focused `SeekerVaultThemeTest` and `StageBoundaryTest` Gradle run | **PASS** |
| `pnpm test:push` | **PASS** — 35 sidecar tests and the selected Android push, notification, tap, and recovery suite |
| `pnpm test:updates` | **PASS** — 10 sidecar tests and the selected Android HTTP/2/update suite |
| `pnpm check` | **PASS** — formatting, lint, generated-protocol check, TypeScript types, 430 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | **PASS** — 9 tests |
| `pnpm test:queue` | **PASS** — 7 tests |
| `pnpm check:android` | **PASS** — Spotless, 854 Android unit tests with zero failures/errors, lint, debug APK, and instrumentation APK |
| `pnpm check:generated` | **PASS** — generated protocol code and fixtures are current |

### Deliberate break

The production theme selection was temporarily replaced with parameterless `darkColorScheme()` and `lightColorScheme()` calls. `productionEntryPointUsesTheApprovedThemeAndRejectsParameterlessDefaults` then **FAILED as expected**. The approved scheme selection was restored with the patch tool, and the complete focused theme/boundary run passed. The deliberate break was never committed.

## Visual and device checks

- Checked-in HTML reference: **PASS** — exact attachment sizes and SHA-256 digests match the imported files; both local pages rendered with no console errors.
- App screenshot comparison: **NOT RUN** — no emulator or physical Seeker screenshot source was available during implementation. Automated Compose assertions are not a pixel comparison.
- Large-text physical check: **NOT RUN**.
- Physical Seeker theme, system switching, and affected flows: **NOT RUN**.

The remaining device pass must compare Home/carousel/navigation, request review, Global rules, and connection rules in both light and dark modes, then repeat the affected layouts at the largest system text size. A build, Robolectric run, emulator, or HTML render does not count as physical-device evidence.
