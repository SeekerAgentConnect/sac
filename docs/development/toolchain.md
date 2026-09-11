# Toolchain

This page covers the pinned versions, MacBook setup, and how the Android Studio and terminal builds stay on the same tools. The step-by-step MacBook → Seeker walkthrough is in [`docs/guides/macbook-seeker-quickstart.md`](../guides/macbook-seeker-quickstart.md).

## Tested versions

Verified on 2026-09-11 on macOS 26.5.2 (Apple silicon). Each version is pinned in exactly one place.

### Node side

| Tool | Version | Pinned in |
| --- | --- | --- |
| Node.js | 24.21.0 (LTS) | `.nvmrc`, and `devEngines.runtime` in `package.json`. pnpm stops with `ERR_PNPM_BAD_RUNTIME_VERSION` on any other version. |
| pnpm | 12.3.4 | `packageManager` in `package.json` |
| TypeScript | 6.0.3 | `catalog` in `pnpm-workspace.yaml` |
| @types/node | 24.13.4 | `catalog` in `pnpm-workspace.yaml` |
| ESLint, @eslint/js, typescript-eslint | 10.10.0, 10.0.1, 8.70.0 | `package.json` |
| Prettier | 3.9.6 | `package.json` |
| Buf CLI (`@bufbuild/buf`) | 1.72.0 | `package.json` |
| MCP TypeScript SDK (`@modelcontextprotocol/sdk`), used by the sidecar and the test agent | 1.30.0 | `catalog` in `pnpm-workspace.yaml` |
| Connect for Node (`@connectrpc/connect`, `@connectrpc/connect-node`) | 2.2.0 | `sidecar/package.json` |
| zod, for the MCP tool schemas and the SDK's peer | 4.6.1 | `catalog` in `pnpm-workspace.yaml` |
| protoc-gen-es (generator), @bufbuild/protobuf (runtime) | 2.14.1 | `catalog` in `pnpm-workspace.yaml`; generator and runtime move together |

**TypeScript stays on 6.0** because typescript-eslint 8.70 supports only `typescript <6.1`. TypeScript 7 can follow once typescript-eslint supports it.

**The sidecar has no build step in development.** Node runs its `.ts` files directly through type stripping, so `sidecar/tsconfig.json` sets `erasableSyntaxOnly`: no enums, namespaces, or parameter properties.

**`@bufbuild/buf` has its install script denied** (`allowBuilds` in `pnpm-workspace.yaml`). The script only locates the platform binary, which pnpm already installs as an optional dependency.

### Android side

| Tool | Version | Pinned in |
| --- | --- | --- |
| Gradle | 9.7.1, distribution sha256-verified | `android/gradle/wrapper/gradle-wrapper.properties` |
| JDK for the Gradle daemon | Eclipse Temurin 21 (tested: 21.0.12.1, downloaded by Gradle) | `android/gradle/gradle-daemon-jvm.properties` |
| Android Gradle Plugin | 9.4.0 | `android/gradle/libs.versions.toml` |
| Kotlin (AGP built-in Kotlin and the Compose compiler plugin) | 2.4.20 | `android/gradle/libs.versions.toml` |
| Compose BOM | 2026.09.00 | `android/gradle/libs.versions.toml` |
| androidx.activity:activity-compose | 1.13.0 | `android/gradle/libs.versions.toml` |
| Connect-Kotlin (`connect-kotlin`, `connect-kotlin-okhttp`, `connect-kotlin-google-javalite-ext`), which brings OkHttp 5.4 | 0.9.0 | `android/gradle/libs.versions.toml` |
| `com.google.protobuf:protobuf-kotlin-lite` | 4.36.1 | `android/gradle/libs.versions.toml` |
| AndroidX Lifecycle (`lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`) | 2.11.0 | `android/gradle/libs.versions.toml` |
| JUnit | 4.13.2 | `android/gradle/libs.versions.toml` |
| Robolectric, running the tests on SDK 36 | 4.16.1 | `android/gradle/libs.versions.toml`, `android/app/src/test/resources/robolectric.properties` |
| AndroidX Test (`core`, `ext:junit`) | 1.7.0, 1.3.0 | `android/gradle/libs.versions.toml` |
| kotlinx-coroutines-test | 1.11.0 | `android/gradle/libs.versions.toml` |
| Spotless, ktfmt (kotlinlang style) | 8.10.2, 0.64 | `android/gradle/libs.versions.toml` |
| Foojay toolchain resolver | 1.0.0 | `android/settings.gradle.kts` |
| Android SDK Platform | 37.0, used for `compileSdk` and `targetSdk` 37 with `minSdk` 31 | `android/app/build.gradle.kts` |
| Android SDK Build-Tools | 36.0.0, the AGP 9.4 default | AGP |
| Android SDK Platform-Tools, Command-line Tools | 37.0.1, 23.0.0 | Not pinned; any recent version works |
| Android Studio | Quail 4 (2026.1.4) or newer, which supports AGP up to 9.4 | Not pinned; not run during verification (see below) |

**Application ID and namespace: `io.github.brrenat.seekervault`.** Treat it as fixed: changing it would make the next build a different app on the device.

**Backups are disabled.** The manifest turns off backups and `res/xml/data_extraction_rules.xml` excludes all app data from cloud backup and device transfer, because later stages store connection credentials.

### Code generators

| Generator in `buf.gen.yaml` | Output | Runtime library that must match |
| --- | --- | --- |
| `protoc-gen-es` 2.14.1 (local), `target=js+dts` | `sidecar/src/gen` | `@bufbuild/protobuf` 2.14.1, plus `@connectrpc/connect` 2.2.0 for the service |
| `buf.build/protocolbuffers/java:v36.1`, `lite` | `android/app/src/main/generated/java` | `com.google.protobuf:protobuf-kotlin-lite` 4.36.1 |
| `buf.build/protocolbuffers/kotlin:v36.1`, `lite` | `android/app/src/main/generated/kotlin` | `com.google.protobuf:protobuf-kotlin-lite` 4.36.1 |
| `buf.build/connectrpc/kotlin:v0.9.0` | `android/app/src/main/generated/kotlin` | `com.connectrpc:connect-kotlin` 0.9.0, with its OkHttp transport and lite codec at the same version |

- **Generation is covered in the protocol doc.** [`docs/protocol.md`](../protocol.md#generated-code) describes generation, the cross-runtime fixtures, and the stale-output check (`pnpm check:generated`).
- **The TypeScript output is JavaScript plus type declarations.** Node's type stripping can't run the TypeScript `enum`s that `target=ts` produces. `sidecar/tsconfig.build.json` sets `allowJs`, so `pnpm build` also copies that JavaScript to `dist/`.
- **Generation needs network access.** `pnpm generate` and `pnpm check:generated` call the Kotlin plugins, which run remotely on the Buf Schema Registry.
- **Android compiles the generated code in place.** `android/app/build.gradle.kts` adds the generated directories to the `main` source set and adds `proto/fixtures` to the unit-test resources.

## MacBook setup

1. **Node.js:** install nvm, then run `nvm install` from the repository root. It reads `.nvmrc`.
2. **pnpm:** run `corepack enable pnpm`, or `npm install --global pnpm`. pnpm 9.7 or newer switches to the version in `packageManager` automatically.
3. **Android Studio and SDK:** install Android Studio Quail 4 or newer. In **Settings → Languages & Frameworks → Android SDK**, install these and accept the licenses:
   - Android SDK Platform 37.0
   - Android SDK Build-Tools 36.0.0
   - Android SDK Platform-Tools

   Without Android Studio, the command-line tools work too:

   ```bash
   sdkmanager --licenses
   sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
   ```

4. **Shell environment:** add this to `~/.zshrc`:

   ```bash
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   export PATH="$ANDROID_HOME/platform-tools:$PATH"
   # Any JDK 17+ can launch Gradle; Android Studio's bundled JetBrains Runtime is enough.
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   ```

5. **First build:** run `pnpm install --frozen-lockfile && pnpm check:android`. The first time, Gradle downloads the pinned Temurin 21 into `~/.gradle/jdks`.

## How Android Studio and the terminal stay compatible

- **Gradle:** both run the wrapper, so both use Gradle 9.7.1. The distribution checksum is pinned.
- **JDK:** `gradle/gradle-daemon-jvm.properties` requires Temurin 21 and lists a download URL for each platform.
  - The terminal and Android Studio Panda 1 or newer both honor these Daemon JVM criteria. Gradle downloads the JDK if it's missing.
  - `JAVA_HOME` only needs to start the wrapper.
  - To change the pin, run `./gradlew updateDaemonJvm --jvm-version=<N> --jvm-vendor=adoptium` in `android/`, then commit the regenerated file.
- **Android SDK:** Android Studio writes `sdk.dir` to `android/local.properties`, which is git-ignored. The terminal uses `ANDROID_HOME`. Point both at `~/Library/Android/sdk`.
- **Project root:** in Android Studio, open the `android/` directory, not the repository root.
- **Bytecode:** Java and Kotlin compile to JVM 17 bytecode (`compileOptions`), regardless of which JDK runs the build.

## Updating versions

1. Change the version where the tables above say it's pinned. Keep each generator paired with its runtime library.
2. Run `pnpm install`, `pnpm check`, and `pnpm check:android`.
3. Update this page.

Since pnpm 11, versions published less than a day ago aren't resolved (`minimumReleaseAge`).

## Verification record: SAW-001

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions above. Gradle was launched on a local JDK 19. The daemon ran on the Temurin 21.0.12.1 that Gradle downloaded.

| Check | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | PASS |
| `pnpm check` | PASS: Prettier, ESLint, `tsc --noEmit`, 8/8 sidecar tests |
| `pnpm build` | PASS: produces `sidecar/dist/main.js` |
| `pnpm dev:sidecar` | PASS. Without configuration, it lists every missing variable and exits 1. It accepts valid variables from both the environment and `.env`. `SIDECAR_PORT=9090` in the environment overrides the value in `.env`. |
| `pnpm check:android` | PASS: `spotlessCheck`, `testDebugUnitTest` (no tests yet), `lintDebug` (no issues), `assembleDebug` |
| `(cd android && ./gradlew :app:assembleDebug)` | PASS: produces `android/app/build/outputs/apk/debug/app-debug.apk` |
| `pnpm generate` | Fails as expected with `Module "proto" had no .proto files`. `buf.gen.yaml` was also run against a throwaway proto: it produced Kotlin and TypeScript output, identical across two runs. |
| `pnpm agent`, `pnpm test:hello` | Exit 1 with "not implemented yet" (SAW-005, SAW-008) |
| Deliberate failures, Node side | Each of these made `pnpm check` exit 1: a Prettier violation, an ESLint error, a type error, a failing `node:test`. All were removed afterwards. |
| Deliberate failures, Android side | Each of these made the `pnpm check:android` task chain exit 1: a failing JUnit test, a Kotlin compile error, a ktfmt violation, an Android lint error. All were removed afterwards. |
| Clean clone | PASS. A fresh clone of the commit passed `pnpm install --frozen-lockfile`, `pnpm check`, `pnpm build`, `pnpm check:android` (with `--no-build-cache`), and `./gradlew :app:assembleDebug`, and no tracked files changed. |
| Credentials and machine paths | PASS: no tokens, keys, `.env`, `local.properties`, or absolute local paths in tracked files |
| Android Studio sync and Run | NOT RUN: Android Studio isn't installed on the verification machine. Covered by SAW-006. |
| Install on a physical Seeker | NOT RUN: no device attached. Covered by SAW-004 and SAW-006. |

## References

- [Android Gradle plugin 9.4.0 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) and [AGP requirements](https://developer.android.com/build/releases/gradle-plugin)
- [About the Android Gradle plugin](https://developer.android.com/build/releases/about-agp)
- [Java versions in Android builds](https://developer.android.com/build/jdks)
- [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Gradle Daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html)
- [protoc-gen-es plugin options](https://github.com/bufbuild/protobuf-es/tree/main/packages/protoc-gen-es)
- [pnpm 11 release notes](https://github.com/pnpm/pnpm/releases/tag/v11.0.0) (`allowBuilds`, `minimumReleaseAge`)
