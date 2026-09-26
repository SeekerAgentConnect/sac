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
| Connect for Node (`@connectrpc/connect`, `@connectrpc/connect-node`) | 2.2.0 | `packages/server-sdk/package.json` and `servers/mcp-server/package.json` |
| Firebase Admin SDK for the optional FCM sender | 14.4.0 | `servers/mcp-server/package.json` |
| uqr, which draws the pairing QR code in the terminal (no dependencies) | 0.1.3 | `servers/mcp-server/package.json` |
| zod, for the MCP tool schemas and the SDK's peer | 4.6.1 | `catalog` in `pnpm-workspace.yaml` |
| protoc-gen-es (generator), @bufbuild/protobuf (runtime) | 2.14.1 | `catalog` in `pnpm-workspace.yaml`; generator and runtime move together |

**TypeScript stays on 6.0** because typescript-eslint 8.70 supports only `typescript <6.1`. TypeScript 7 can follow once typescript-eslint supports it.

**The host runs TypeScript directly in development.** Node runs the sidecar's `.ts` files through
type stripping, while the workspace builds `packages/server-sdk/dist` first because the host resolves the
same package exports an external consumer does. Both TypeScript configs use `erasableSyntaxOnly`.

**Unneeded dependency install scripts are denied** through `allowBuilds` in `pnpm-workspace.yaml`.
`@bufbuild/buf` only locates the platform binary pnpm already installs. `protobufjs` only checks
dependency version syntax. `@firebase/util`'s script can read `FIREBASE_WEBAPP_CONFIG`, contact a
Firebase endpoint, and write web-app defaults; the sidecar uses runtime Admin SDK credentials
instead, so installation remains deterministic and credential-independent.

### The Go side (SEE-90, SEE-95, SEE-99, SEE-134)

Five Go modules, and nothing in any of them is shared with the Node or Android sides: the shared
gateway in [`services/gateway/`](../../services/gateway), the public-feed demos' source library in
[`packages/publisher-support/`](../../packages/publisher-support), the two demos themselves in
[`examples/demo-signals/`](../../examples/demo-signals) and [`examples/demo-prediction/`](../../examples/demo-prediction), and
the load harness in [`tools/loadtest/`](../../tools/loadtest). `pnpm check:gateway`,
`pnpm check:publisher-support`, `pnpm check:demo-signals`, `pnpm check:demo-prediction` (or
`pnpm check:demos` for those three at once) and `pnpm check:loadtest` run their checks; CI runs the
first four, each as its own job reading that module's own `go.mod`. Installing Go is not needed for
`pnpm check`.

They are separate modules on purpose. Each demo is meant to be copyable out of this repository and
to build, test, image and run without the other — which is why they are checked one module at a
time rather than together (SEE-134, [`demos.md`](demos.md)) — and the harness holds both ends of a
feed at once — the publisher API, the client API and the broker's own client schema — which nothing
that ships is allowed to hold together, so keeping it out of `services/gateway/` is what keeps the
gateway's dependency list at three. Each has its own `go.mod`, and the versions below are the same
in all of them; the two demos reach the library through
`replace github.com/BrRenat/SeekerAgentWallet/publisher-support => ../publisher-support`, so they
inherit its pins rather than choosing their own. Where the gateway and a publisher could drift, a
test in `packages/publisher-support/` reads the gateway's own source rather than trusting the match
(`signals/contract_test.go`).

| Tool | Version | Pinned in |
| --- | --- | --- |
| Go | 1.27.1 | the `go` line in `services/gateway/go.mod`, `packages/publisher-support/go.mod`, `examples/demo-signals/go.mod`, `examples/demo-prediction/go.mod` and `tools/loadtest/go.mod`, which `actions/setup-go` reads through `go-version-file` |
| `connectrpc.com/connect`, the Connect runtime for all of them | 1.21.0 | every `go.mod`; it must match the `connectrpc/go` generator |
| `google.golang.org/protobuf`, the message runtime | 1.36.12 | every `go.mod`; it must match the `protocolbuffers/go` generator |
| `modernc.org/sqlite`, the pure-Go SQLite driver | 1.59.0 | `services/gateway/go.mod` and `packages/publisher-support/go.mod`, from which both demos take it; the harness holds no database |

The harness's own dependency list is those two libraries and nothing else. It speaks gRPC to the
broker without grpc-go: connect-go does the protocol, and since Go 1.24 the standard library opens
an unencrypted HTTP/2 connection by itself (`net/http.Protocols.SetUnencryptedHTTP2`), which is the
same argument `services/gateway/internal/stream` makes for using the broker's HTTP API rather than its gRPC
one.

The broker the gateway fans out through is a service rather than a dependency, and it is pinned
where the deployment names it:

| Service | Version | Pinned in |
| --- | --- | --- |
| Centrifugo | 6.9.6 | `deploy/feed/compose.yaml` (`centrifugo/centrifugo:v6.9.6`), and the schema in `packages/protocol/third_party/centrifugo` is that release's |
| Redis | 8.2 (alpine) | `deploy/feed/compose.yaml`; it holds a bounded recovery cache and nothing durable |

Neither is vendored, so the checks that need them take a path instead: `SEEKERVAULT_CENTRIFUGO` and
`SEEKERVAULT_REDIS`, which `services/gateway/internal/stream/broker_test.go`,
`feeds/CentrifugoStreamIntegrationTest`, `pnpm test:integration` and `pnpm test:load` all read.
Verify a Centrifugo download against the release's own `centrifugo_<version>_checksums.txt` before
using it. The versions actually run for SEE-99's measurements — and how they were obtained — are in
[`../testing/see-99.md`](../testing/see-99.md); a report of that kind is only about the binaries it
names.

**The broker's client schema is vendored, not fetched.** `packages/protocol/third_party/centrifugo` holds a
byte-for-byte copy of the release's `unistream.proto` with its digest in `SHA256SUMS`, and
`pnpm generate` refuses to run if the file no longer matches — so upgrading is an edit to the file
and to the digest, in one commit (see that directory's README). An upgrade also moves the version in
`compose.yaml`: a client schema from one release with a server from another is the mismatch that
directory exists to prevent.

**Three direct dependencies, and no more.** The rate limiter, the page cursor, the credential
hashing and the logging are the standard library's, because each is a few lines and the alternative
is a dependency to audit for a service whose whole point is holding nothing personal.

**The SQLite driver is pure Go, so `CGO_ENABLED=0` is what the image builds with.** That is what
lets the runtime image be `FROM scratch` with no libc and no CA bundle in it
([`services/gateway/Dockerfile`](../../services/gateway/Dockerfile)).

**Formatting is `gofmt`**, which is not configurable and therefore not configured. `go vet` runs
before the tests.

### Android side

| Tool | Version | Pinned in |
| --- | --- | --- |
| Gradle | 9.7.1, distribution sha256-verified | `apps/android/gradle/wrapper/gradle-wrapper.properties` |
| JDK for the Gradle daemon | Eclipse Temurin 21 (tested: 21.0.12.1, downloaded by Gradle) | `apps/android/gradle/gradle-daemon-jvm.properties` |
| Android Gradle Plugin | 9.4.0 | `apps/android/gradle/libs.versions.toml` |
| Kotlin (AGP built-in Kotlin and the Compose compiler plugin) | 2.4.20 | `apps/android/gradle/libs.versions.toml` |
| Compose BOM | 2026.09.00 | `apps/android/gradle/libs.versions.toml` |
| androidx.activity:activity-compose | 1.13.0 | `apps/android/gradle/libs.versions.toml` |
| Connect-Kotlin (`connect-kotlin`, `connect-kotlin-okhttp`, `connect-kotlin-google-javalite-ext`), which brings OkHttp 5.4 | 0.9.0 | `apps/android/gradle/libs.versions.toml` |
| `com.google.protobuf:protobuf-kotlin-lite` | 4.36.1 | `apps/android/gradle/libs.versions.toml` |
| CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-compose`), for scanning pairing codes | 1.6.2 | `apps/android/gradle/libs.versions.toml` |
| ZXing `core`, the QR decoder (no dependencies, no Play services) | 3.5.4 | `apps/android/gradle/libs.versions.toml` |
| Mobile Wallet Adapter client (`mobile-wallet-adapter-clientlib-ktx`), which the app drives the owner's wallet with (SAW-015). It brings `mobile-wallet-adapter-clientlib` and `mobile-wallet-adapter-common` at the same version. | 2.2.0 | `apps/android/gradle/libs.versions.toml` |
| OkHttp `mockwebserver3` and `okhttp-tls`, test-only, on the OkHttp version that Connect-Kotlin brings | 5.4.0 | `apps/android/gradle/libs.versions.toml` |
| AndroidX Lifecycle (`lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`) | 2.11.0 | `apps/android/gradle/libs.versions.toml` |
| AndroidX WorkManager (`work-runtime-ktx`, plus `work-testing` for JVM tests) | 2.11.2 | `apps/android/gradle/libs.versions.toml` |
| Firebase Android BoM; Cloud Messaging comes from its main `firebase-messaging` module | 34.19.0 | `apps/android/gradle/libs.versions.toml` |
| Google Services Gradle plugin, applied only when `apps/android/app/google-services.json` exists | 4.5.0 | `apps/android/gradle/libs.versions.toml` |
| JUnit | 4.13.2 | `apps/android/gradle/libs.versions.toml` |
| Robolectric, running the tests on SDK 36 | 4.16.1 | `apps/android/gradle/libs.versions.toml`, `apps/android/app/src/test/resources/robolectric.properties` |
| AndroidX Test (`core`, `ext:junit`, `runner`) | 1.7.0, 1.3.0, 1.7.0 | `apps/android/gradle/libs.versions.toml` |
| Emulator for `pnpm test:hello --device` in CI | Android 16 (API 36) `google_apis` x86_64, through `reactivecircus/android-emulator-runner` v2.38.0 | `.github/workflows/ci.yml` |
| kotlinx-coroutines-test | 1.11.0 | `apps/android/gradle/libs.versions.toml` |
| Spotless, ktfmt (kotlinlang style) | 8.10.2, 0.64 | `apps/android/gradle/libs.versions.toml` |
| Foojay toolchain resolver | 1.0.0 | `apps/android/settings.gradle.kts` |
| Android SDK Platform | 37.0, used for `compileSdk` and `targetSdk` 37 with `minSdk` 31 | `apps/android/app/build.gradle.kts` |
| Android SDK Build-Tools | 36.0.0, the AGP 9.4 default | AGP |
| Android SDK Platform-Tools, Command-line Tools | 37.0.1, 23.0.0 | Not pinned; any recent version works |
| Android Studio | Quail 4 (2026.1.4) or newer, which supports AGP up to 9.4 | Not pinned; not run during verification (see below) |

**Display name: Seeker Agent Connect. Application ID and namespace: `io.github.brrenat.seekervault`.** The identifier remains fixed across the branding change: changing it would make the next build a different app on the device and would not preserve the existing installation's data.

**Backups are disabled.** The manifest turns off backups and `res/xml/data_extraction_rules.xml` excludes all app data from cloud backup and device transfer, because the app stores connection credentials (SAW-012); see [`docs/security.md`](../security.md#local-storage-and-recovery).

### Code generators

| Generator | Output | Runtime library that must match |
| --- | --- | --- |
| `protoc-gen-es` 2.14.1 (local), `target=js+dts` | direct contracts in `packages/server-sdk/src/gen`; proposal fixture contract in `servers/mcp-server/src/gen` | `@bufbuild/protobuf` 2.14.1, plus `@connectrpc/connect` 2.2.0 for the service |
| `buf.build/protocolbuffers/java:v36.1`, `lite` | `apps/android/app/src/main/generated/java` | `com.google.protobuf:protobuf-kotlin-lite` 4.36.1 |
| `buf.build/protocolbuffers/kotlin:v36.1`, `lite` | `apps/android/app/src/main/generated/kotlin` | `com.google.protobuf:protobuf-kotlin-lite` 4.36.1 |
| `buf.build/connectrpc/kotlin:v0.9.0` | `apps/android/app/src/main/generated/kotlin` | `com.connectrpc:connect-kotlin` 0.9.0, with its OkHttp transport and lite codec at the same version |
| `buf.build/protocolbuffers/go:v1.36.12`, `paths=source_relative` (in `buf.gen.feed-gateway.yaml`) | `services/gateway/internal/gen` | `google.golang.org/protobuf` 1.36.12 |
| `buf.build/connectrpc/go:v1.21.0`, `paths=source_relative` (in `buf.gen.feed-gateway.yaml`) | `services/gateway/internal/gen` | `connectrpc.com/connect` 1.21.0 |
| the same two Go plugins (in `buf.gen.publisher-support.yaml`) | `packages/publisher-support/gen` | the same two runtimes, pinned in `packages/publisher-support/go.mod`, which both demos inherit |
| the same three Kotlin plugins (in `buf.gen.centrifugo.yaml`) | `apps/android/app/src/main/generated/centrifugo` | the vendored broker schema, for the phone alone |
| the same two Go plugins (in `buf.gen.loadtest.yaml`), over `packages/protocol/proto/` **and** the vendored schema | `tools/loadtest/internal/gen` | the same two runtimes, pinned in `tools/loadtest/go.mod` |

- **There are seven templates.** `buf.gen.yaml` writes the phone's Kotlin, while
  `buf.gen.server-sdk.yaml` writes the direct TypeScript contracts and `buf.gen.mcp-server.yaml` keeps
  only the proposal fixture contract with the MCP host. None generates
  `seekervault/gateway/v1/publish.proto` for the phone or direct server — neither is a publisher.
  `buf.gen.feed-gateway.yaml` writes the gateway's Go for the three packages it speaks;
  `buf.gen.publisher-support.yaml` writes the public-feed demos' Go for a fourth subset — the
  publisher API, its problem detail and the documents a publisher writes, and **no feed client at
  all**, because a publisher publishes and never reads a feed (SEE-95). It writes them **once**,
  into the library both demos share, because the two publish the same documents through the same
  API and two generated copies of one contract would be two contracts (SEE-134);
  `buf.gen.centrifugo.yaml` writes Kotlin for the vendored broker schema, into its own directory and
  keeping its own package name, because only the phone speaks that protocol and only one file in it
  may (SEE-91); and
  `buf.gen.loadtest.yaml` writes the load harness's Go from `packages/protocol/proto/` **and** that vendored schema,
  which is the only place both ends of a feed and the broker's own client protocol are compiled
  together — measuring a publication's journey means holding all three ends of it, and nothing that
  ships is allowed to (SEE-99). `pnpm generate` runs all seven, and `pnpm check:generated` compares
  every output directory.
- **Generation is covered in the protocol doc.** [`docs/protocol.md`](../protocol.md#generated-code) describes generation, the cross-runtime fixtures, and the stale-output check (`pnpm check:generated`).
- **The TypeScript output is JavaScript plus type declarations.** Node's type stripping can't run
  the TypeScript `enum`s that `target=ts` produces. The SDK build emits its TypeScript and copies the
  generated JavaScript/declarations into `packages/server-sdk/dist`; the host build copies its remaining
  generated proposal fixture alongside the compiled application.
- **Generation needs network access.** `pnpm generate` and `pnpm check:generated` call the Kotlin plugins, which run remotely on the Buf Schema Registry.
- **Android compiles the generated code in place.** `apps/android/app/build.gradle.kts` adds the generated directories to the `main` source set and adds `packages/protocol/proto/fixtures` to the unit-test resources.

### Bidirectional gRPC support (SAW-048)

The existing pins already support the production update protocol, so SAW-048 adds no dependency. Connect-Kotlin 0.9.0 generates a `BidirectionalStreamInterface` for `UpdateService.Subscribe`; its OkHttp transport 5.4.0 speaks explicit HTTP/2 prior knowledge on the loopback h2c development endpoint, and Connect Node 2.2.0 serves the generated descriptor through its Node HTTP/2 adapter. `GrpcBidiInteropTest` exercises that exact combination, rather than a fake stream or an in-memory handler. The production-listener tests separately exercise TLS/ALPN HTTP/2. See [`docs/testing/stage-5-2.md`](../testing/stage-5-2.md).

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

5. **Go, for the gateway and the public-feed demos (SEE-90, SEE-134):** `brew install go`, or the
   installer from <https://go.dev/dl/>. It is needed only for `pnpm check:gateway`,
   `pnpm check:demos` and `pnpm check:loadtest`, and for working in the five Go modules; the Node
   and Android checks do not use it.
6. **First build:** run `pnpm install --frozen-lockfile && pnpm check:android`. The first time, Gradle downloads the pinned Temurin 21 into `~/.gradle/jdks`.

## How Android Studio and the terminal stay compatible

- **Gradle:** both run the wrapper, so both use Gradle 9.7.1. The distribution checksum is pinned.
- **JDK:** `gradle/gradle-daemon-jvm.properties` requires Temurin 21 and lists a download URL for each platform.
  - The terminal and Android Studio Panda 1 or newer both honor these Daemon JVM criteria. Gradle downloads the JDK if it's missing.
  - `JAVA_HOME` only needs to start the wrapper.
  - To change the pin, run `./gradlew updateDaemonJvm --jvm-version=<N> --jvm-vendor=adoptium` in `apps/android/`, then commit the regenerated file.
- **Android SDK:** Android Studio writes `sdk.dir` to `apps/android/local.properties`, which is git-ignored. The terminal uses `ANDROID_HOME`. Point both at `~/Library/Android/sdk`.
- **Project root:** in Android Studio, open the `apps/android/` directory, not the repository root.
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
| `pnpm build` | PASS: produces `servers/mcp-server/dist/cli.js` |
| `pnpm dev:mcp-server` | PASS. Without configuration, it lists every missing variable and exits 2. It accepts valid variables from both the environment and `.env`. `SIDECAR_PORT=9090` in the environment overrides the value in `.env`. |
| `pnpm check:android` | PASS: `spotlessCheck`, `testDebugUnitTest` (no tests yet), `lintDebug` (no issues), `assembleDebug` |
| `(cd apps/android && ./gradlew :app:assembleDebug)` | PASS: produces `apps/android/app/build/outputs/apk/debug/app-debug.apk` |
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
