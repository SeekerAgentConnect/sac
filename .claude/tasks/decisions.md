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
