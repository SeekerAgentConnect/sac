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
- [ ] Report in `docs/testing/stage-1.md`: commit, versions, commands, and results by environment (simulated, emulator, device), with credentials and chat content redacted
- [ ] Docs: README milestone and commands, `android.md`, `toolchain.md`, `test-agent/README.md`, `CODEBASE.md`, changelog, decisions
- [ ] Verify: `pnpm check`, `pnpm test:hello`, `pnpm check:android`, `pnpm check:generated`, `pnpm build`, and CI's emulator run
- [ ] Commit, push, and confirm CI is green
- [ ] Linear: tick the done items, leave the Hermes → Seeker round trip open (NOT RUN), and move the issue to In Review
- [ ] One PR `develop` → `master`, covering SEE-6, SEE-8 to SEE-14; link it on the Linear issues

## Review

To be written when the work is done.
