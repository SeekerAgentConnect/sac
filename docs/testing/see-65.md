# SEE-65 — Seeker Agent Connect branding

SEE-65 changes the public Android product name to **Seeker Agent Connect** and replaces the platform placeholder with the lime icon supplied on the Linear issue in `Solana Seeker app overview (2).zip`. It does not rename the repository, Android namespace/application ID, Kotlin packages, protocol packages, `seekervault://` pairing scheme, stored-data paths, MCP server identifier, or other compatibility identifiers.

## Icon wiring

The supplied legacy launcher PNGs are copied without resampling into the `mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, and `xxxhdpi` mipmap directories at 48, 72, 96, 144, and 192 pixels. The supplied transparent adaptive foregrounds are copied at 108, 162, 216, 324, and 432 pixels. `mipmap-anydpi-v26/ic_launcher.xml` places that foreground over the supplied `#C2E60F` lime and exposes the same silhouette as Android's monochrome layer. Both `android:icon` and `android:roundIcon` use that adaptive resource, with the density-specific complete lime PNGs as the legacy fallback.

The app has no custom splash activity or splash artwork. On the supported API 31 and newer devices, the platform splash derives its branding from the application icon, so the same adaptive lime icon replaces the former platform placeholder there.

`BrandingTest` reads the source assets rather than accepting a successful build as visual proof. It fixes the two public name resources, manifest icon references, unchanged application ID/namespace, the adaptive layer references, the exact lime value, every required raster dimension, the opaque lime legacy edge, the transparent adaptive edge, and the dark mark. It also rejects either previous spaced product name in Android string resources, README, RFC, or Markdown documentation.

## Automated verification

Run on 2026-09-14 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.0, Android SDK Platform 37 and Build-Tools 36.0.0. Gradle launched from Oracle JDK 19.0.2 and used the pinned compatible Temurin 21 daemon. The relaunched shell did not export `ANDROID_HOME`, so Android commands pointed that variable at the existing local SDK without adding the ignored, machine-specific `local.properties` file.

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | PASS — all 400 locked packages came from the existing content-addressable store |
| `./gradlew :app:testDebugUnitTest --tests io.github.brrenat.seekervault.BrandingTest` before the implementation | FAIL as intended — the old label, missing icon resources, and old documentation made all four regression tests fail |
| `./gradlew :app:testDebugUnitTest --tests io.github.brrenat.seekervault.BrandingTest` after the implementation | PASS — 4 tests |
| Targeted pairing CLI/URI and MCP endpoint Node tests | PASS — 30 tests |
| `pnpm test:push` | PASS — Stage 5.3 sidecar and Android acceptance |
| `pnpm test:updates` | PASS — Stage 5.2 sidecar and Android acceptance |
| `pnpm check` | PASS — formatting, lint, types, 430 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — 9 simulated-device cases |
| `pnpm test:queue` | PASS — 7 two-sidecar simulated-phone cases |
| `pnpm check:android` | PASS — Spotless, Android unit tests, lint, debug APK, and instrumentation APK |
| `aapt dump badging android/app/build/outputs/apk/debug/app-debug.apk` | PASS — label `Seeker Agent Connect`, application ID `io.github.brrenat.seekervault`, and `res/mipmap-anydpi-v26/ic_launcher.xml` at every reported density |

## Physical Seeker

**NOT RUN.** `adb devices -l` listed no device on 2026-09-14. Launcher masking, the platform splash, an in-place upgrade with retained data, and screenshots therefore remain device-only checks; no build, JVM test, or static icon preview is reported as a physical-device pass.
