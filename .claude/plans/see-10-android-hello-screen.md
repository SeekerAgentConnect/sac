# SEE-10 / SAW-004 — Build the standard Android hello-world screen

Linear: https://linear.app/seekeragentwallet/issue/SEE-10 · Branch: `develop`

## Checklist

- [x] Dependencies:
  - [x] Connect-Kotlin OkHttp transport and javalite codec 0.9.0
  - [x] Lifecycle 2.11.0
  - [x] Robolectric 4.16.1 (SDK 36), AndroidX Test, coroutines-test
- [x] `LiveCommandTransport` and `ConnectLiveCommandTransport`:
  - [x] no stream timeout; OkHttp without a read timeout
  - [x] errors classified into kinds
- [x] `LiveCommandViewModel`:
  - [x] one stream; validation
  - [x] Sending set synchronously (double tap)
  - [x] local deadline
  - [x] connection loss clears the command and says why
  - [x] background and foreground
- [x] `LiveCommandScreen`: stock Material 3, test tags, no custom visuals or wallet terms
- [x] `MainActivity`: `onStart`/`onStop` hooks that skip configuration changes; `SeekerVaultApplication` as the transport injection point
- [x] Manifest:
  - [x] INTERNET only
  - [x] debug-only loopback cleartext overlay
  - [x] `NetworkSecurityPolicyTest` guard
- [x] Tests:
  - [x] ViewModel with a fake transport
  - [x] Compose screen (Robolectric)
  - [x] Activity lifecycle (Robolectric)
  - [x] transport against the real sidecar
- [x] Compose test rules moved to `junit4.v2`; lint's network-based version checks disabled so lint is deterministic
- [x] Docs:
  - [x] `docs/development/android.md`, with a verification record
  - [x] README, `CODEBASE.md`, toolchain, protocol doc
  - [x] changelog, decisions
- [x] Verify:
  - [x] `pnpm check:android` (40/40, lint clean)
  - [x] `pnpm check` (65/65)
  - [x] release manifest via `aapt2`
  - [x] four deliberate breaks, each caught
- [ ] Commit, push, and confirm CI is green
- [ ] Linear: tick the SEE-10 checklist, leaving the physical Seeker item open (NOT RUN), and move it to In Review

## Review

**What changed.** The app now opens on the Stage 1 live-test screen. It has the server URL and phone token fields, Connect and Disconnect, a status line, the received text, and a one-tap OK, all built from stock Material 3 components.

The screen is stateless; `LiveCommandViewModel` owns the state:

- **One stream.** It keeps a single `WatchCommands` stream, open only while the app is in the foreground.
- **Rotation keeps everything.** The stream, the text, and the command status all survive a rotation.
- **One OK per command.** A second tap does nothing.
- **Local deadline.** It marks a command timed out at its local deadline.
- **Lost connection.** It clears the text and explains why.

`ConnectLiveCommandTransport` talks to the sidecar through Connect-Kotlin over OkHttp, with no stream timeout. Only debug builds allow cleartext HTTP, and only to loopback.

**How it was verified.** The verification record is in `docs/development/android.md`. It covers:

- ViewModel tests with a fake transport
- Compose and Activity tests on Robolectric, covering rotation, the double tap, and background and foreground
- the Kotlin transport against the real Node sidecar and MCP path
- the release manifest, checked with `aapt2`
- four deliberate breaks, each caught by its tests

**Caveats:**

- **The physical Seeker check is NOT RUN**, because no device was available. The Linear checklist item stays open, and SAW-006 and SAW-008 cover it.
- **The UI tests run on Robolectric at SDK 36,** not 37. Robolectric 4.17 adds SDK 37 but was released less than a day ago.
- **The local timeout uses the phone's clock.** The sidecar's deadline is authoritative when the two clocks disagree.
