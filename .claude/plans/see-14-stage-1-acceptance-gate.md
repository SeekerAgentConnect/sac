# SEE-14 / SAW-008 — Run the Stage 1 acceptance gate and add repeatable checks

Linear: https://linear.app/seekeragentwallet/issue/SEE-14 · Branch: `develop` · Then one PR `develop` → `master`

## Checklist

- [x] `pnpm test:hello` replaces the placeholder:
  - [x] Default: the Stage 1 acceptance suite runs the real CLI against a real sidecar process. The Connect test client acts as the phone, labeled "simulated device". The cases are: happy path, app offline, second simultaneous command, timeout, double tap, and process restart.
  - [x] `--device`: the sidecar runs with throwaway tokens on a free port, plus `adb reverse`. An instrumentation UI test on the attached device or emulator enters the token, connects, checks the exact text, and double-taps OK while the host CLI sends over MCP. The run reports whether it was an emulator or a device.
- [x] Android instrumentation: `androidTest` source set, AndroidJUnitRunner, and the Compose UI test
- [x] Stage boundary guard (Android and Node): no wallet SDK, no keys, no persistence, no background services
- [x] CI: add `pnpm test:hello` to the Node job, and a new emulator job that runs `pnpm test:hello --device`
- [x] Known gap from SAW-007: a sidecar crash mid-call should reach the test agent as a connection error, not as a late TIMEOUT (if the SDK allows a clean fix)
- [x] Report in `docs/testing/stage-1.md`: commit, versions, commands, and results by environment (simulated, emulator, device), with credentials and chat content redacted
- [x] Docs: README milestone and commands, `android.md`, `toolchain.md`, `test-agent/README.md`, `CODEBASE.md`, changelog, decisions
- [x] Verify: `pnpm check`, `pnpm test:hello`, `pnpm check:android`, `pnpm check:generated`, `pnpm build`, and CI's emulator run
- [ ] Commit, push, and confirm CI is green
- [ ] Linear: tick the done items, leave the Hermes → Seeker round trip open (NOT RUN), and move the issue to In Review
- [ ] One PR `develop` → `master`, covering SEE-6, SEE-8 to SEE-14; link it on the Linear issues

## Review

**What changed:**

- **`pnpm test:hello` is real.** It runs `test-agent/src/stage1.acceptance.ts`, the acceptance cases, through the real CLI and the sidecar as a separate process. With `--device`, it runs the round trip on a device or emulator:
  - a sidecar with throwaway tokens, and `adb reverse`
  - the new instrumentation test `LiveCommandDeviceTest`, which enters the token, connects, checks the exact text, and double-taps OK
  - the CLI sending over MCP

  It passes only with exactly one acknowledgement, and it labels emulator runs.
- **Stage boundary guards:** `StageOneBoundaryTest` and `sidecar/src/stage-boundary.test.ts`.
- **The SAW-007 gap is fixed.** The test agent now reports a dropped connection at once, as exit code 3.
- **CI** runs `pnpm test:hello`, plus a new job that runs `--device` on an API 36 emulator.
- **The report** is in `docs/testing/stage-1.md`.

**How it was verified:**

- **Local checks:** `pnpm check` (67 + 15), `pnpm test:hello` (9/9), `pnpm check:android` (45/45, with lint, the debug APK, and the test APK), `check:generated`, and `build`.
- **Three deliberate breaks.** One caught a real weakness: the Android regex missed `getSharedPreferences` until it matched substrings.
- **The merged manifests of the debug and release APKs.**
- **The emulator round trip in CI:** PASS on `sdk_gphone64_x86_64`, Android 16 (API 36), in [run 34560580432](https://github.com/BrRenat/SeekerAgentWallet/actions/runs/34560580432). The UI test showed the exact text, the double tap sent one OK, and the agent printed the same ID the sidecar logged once.

**Caveats:**

- **The physical Seeker round trip and the real Hermes round trip are NOT RUN.** Stage 1 stays unaccepted until the owner records them. The Linear item for it stays open.
- **The emulator run covers only the happy path and the double tap.** The other cases run on the simulated device.
- **Gradle's connected test removes the app from the device after the run.** The docs say to reinstall it.

## PR #2 review

The owner reported that the Seeker and Hermes round trip passed. Codex left seven review comments, and the owner added commit `e035411` (Tailscale access).

- [x] Codex, bridge: settle an overdue command as `TIMEOUT` before starting the next one, with a mocked-clock test
- [x] Codex, server: answer 400 to a malformed request target instead of crashing, with a test
- [x] Codex, config: reject tokens with characters that aren't allowed in a bearer token, with a test
- [x] Codex, app: reject ports outside 1 to 65535 as an invalid URL, with a test
- [x] Codex, `package.json`: pin Node exactly (24.21.0), and update the quoted pnpm error
- [x] Codex, `--device`: identify the Seeker by brand and model, from the owner's device's properties
- [x] Codex, sdkmanager ID: keep `platforms;android-37.0`, backed by the installed `package.xml`
- [x] `e035411`: move the hard-coded Tailscale address to `MCP_ALLOWED_HOSTS`, with tests; turn the notes into the Hermes guide's VPN section; fix Prettier
- [x] Record the owner's pass in the report, README, quickstart, Hermes guide, and `CODEBASE.md`
- [x] Verify locally, including deliberate breaks for the new tests: all five were caught
- [ ] Commit, push, and confirm CI is green
- [ ] Reply on each review thread, resolve the fixed ones, and tick the last SEE-14 item in Linear
