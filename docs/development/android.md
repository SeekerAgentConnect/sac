# Android

The Stage 1 app is one screen: the live-test screen. It connects to the sidecar's `LiveCommandService` ([`docs/protocol.md`](../protocol.md)), shows an agent's text, and sends the user's OK back. It uses stock Jetpack Compose and Material 3 components only, and no wallet.

## The hello screen

| Element | Behavior |
| --- | --- |
| **Server URL** | Defaults to `http://127.0.0.1:8080`, the Mac's sidecar through `adb reverse`. Editable only while disconnected. |
| **Phone token** | `PHONE_TOKEN` from the sidecar's `.env`. The field is masked, and the token lives only in memory for as long as the app does. |
| **Connect / Disconnect** | Connect opens `WatchCommands`; Disconnect closes it. Only one of the two buttons is enabled at a time. |
| **Status** | Disconnected, Connecting…, or Connected. After a problem it shows the reason instead: a bad URL, a missing or rejected token, blocked plain HTTP, a sidecar it couldn't reach on the first attempt (it then names the URL and the `adb reverse` command to run), the stream replaced by a newer one, a lost connection, or backgrounding. |
| **Received text** | The agent's text exactly as sent, shown as plain text: never parsed, linked, or executed. Long text scrolls. |
| **OK** | Enabled only while the command waits for the user. The first tap disables it and sends `AcknowledgeCommand`. |
| **Command status** | Tap OK, Sending OK…, OK sent, Timed out, or the reason an OK failed: cancelled by the agent, unknown to the sidecar, rejected token, or sidecar unreachable. |

`MainActivity` hosts `LiveCommandScreen`, a stateless composable in `live/LiveCommandScreen.kt`, and gets its state from `LiveCommandViewModel`. The network sits behind `LiveCommandTransport`. The real implementation, `ConnectLiveCommandTransport`, uses the generated Connect-Kotlin client over OkHttp.

## Debug URL and USB connection

The phone reaches the Mac's sidecar over USB with `adb reverse`:

```bash
adb reverse tcp:8080 tcp:8080     # the phone's 127.0.0.1:8080 now reaches the Mac's 127.0.0.1:8080
```

Then enter `http://127.0.0.1:8080` and the phone token in the app, and tap **Connect**. The full MacBook-to-Seeker walkthrough is in [`docs/guides/macbook-seeker-quickstart.md`](../guides/macbook-seeker-quickstart.md), and fixes for common problems are in [`docs/guides/troubleshooting.md`](../guides/troubleshooting.md).

**Plain HTTP is allowed only in debug builds, and only to loopback.**

- `src/debug/AndroidManifest.xml` points to `src/debug/res/xml/network_security_config.xml`, which permits cleartext to `127.0.0.1` and `localhost` only.
- Every other host is still refused. The app then says to use `adb reverse` and `127.0.0.1`.
- Release builds don't include this configuration, so Android's default applies: no cleartext traffic.
- The only permission the app requests is `INTERNET`, and it requests no notification or biometric permissions. The manifest also carries `io.github.brrenat.seekervault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX Core declares for the app's own non-exported receivers. That one is private to the app, signature-level, and never shown to the user.
- `NetworkSecurityPolicyTest` keeps it that way. It fails if the main source set sets `networkSecurityConfig` or `usesCleartextTraffic`, or if the debug exception covers anything but `127.0.0.1` and `localhost`.

## Lifecycle and its limits

- **The stream exists only in the foreground.** When the app goes to the background (`onStop` without a configuration change), the screen closes `WatchCommands`. When the app returns, it reconnects on its own, but only if it was connected before.
- **Rotation keeps everything.** A rotation or any other configuration change recreates the activity but keeps the ViewModel. The stream, the received text, and the command status all survive, and no second stream or second OK is sent.
- **One tap, one OK.** The ViewModel moves a command to Sending before the request leaves, so a rapid double tap sends one acknowledgement.
- **A lost connection clears the received text,** and the screen says why. When the phone disconnects for any reason, the sidecar cancels the waiting command, and the agent gets `CANCELLED`. It never resends the command, and text sent while the phone is disconnected fails with `OFFLINE`. Commands aren't queued, and nothing reaches the phone in the background.
- **The deadline is checked locally but decided by the sidecar.** The screen marks a command as timed out at `expires_at`, using the phone's clock. If the phone's and the Mac's clocks disagree, the sidecar's answer to the OK decides.
- **A process restart forgets everything.** If Android kills the process, the URL and token are forgotten too; the token is never written to disk.

## Tests

`pnpm check:android` runs these as JVM unit tests (`testDebugUnitTest`):

| Test | What it covers |
| --- | --- |
| `LiveCommandViewModelTest` | The ViewModel against a fake transport, on virtual time: showing text and sending one OK, a rapid second tap, the deadline, a command that is already expired, the sidecar's answers to an OK, connection loss clearing the command, a wrong token, a replaced stream, reconnect, disconnect, background and foreground, and input validation |
| `LiveCommandScreenTest` | Compose tests on Robolectric: exact plain-text display, the one-tap OK, the disabled states, the 4096-byte maximum text, and every status and error message |
| `MainActivityTest` | The activity with a fake transport, on Robolectric: rotation during a command, a rapid double tap, and background then foreground |
| `ConnectLiveCommandTransportTest` | The real transport against the real sidecar (`node sidecar/src/main.ts`), with an MCP SDK client as the agent: text in, the same command's OK out. Also covers a wrong token, an unknown command, and a sidecar stop. It needs Node 24 and `pnpm install`. `ConnectLiveCommandTransportUnreachableTest` covers a closed port. |
| `LiveProtocolFixturesTest`, `LiveCommandDeadlineTest` | Protocol fixtures and deadline boundaries (SAW-002) |
| `StageOneBoundaryTest` | The Stage 1 boundary (SAW-008). The manifest declares only `MainActivity` and `INTERNET`. App code uses no storage, key, or background APIs. No wallet, storage, or background library is on the classpath. |

Robolectric 4.16 runs the UI tests on SDK 36 (`src/test/resources/robolectric.properties`), its newest supported SDK. The app itself targets SDK 37.

### On a device or emulator

`src/androidTest/.../LiveCommandDeviceTest.kt` runs the round trip on real Android. It enters the phone token, taps **Connect**, waits for the agent's text, checks that the text is exact, and taps **OK** twice. It needs a running sidecar and an agent, so run it with `pnpm test:hello --device`. The script:

1. Picks the one attached device or emulator, or the one in `ANDROID_SERIAL`.
2. Starts the sidecar on a free port with throwaway tokens, never your `.env`, and runs `adb reverse` for that port.
3. Runs `./gradlew :app:connectedDebugAndroidTest`, passing the URL, the phone token, and the text as instrumentation arguments.
4. Once the app has connected, sends the text over MCP with the test agent.
5. Passes only if all of these hold: the UI test passes, the agent prints `{"id","result":"OK"}`, and the sidecar logged exactly one acknowledgement.

Gradle installs the debug app and the test APK, then removes both after the run. To get the app back, reinstall it with `adb install`.

`pnpm check:android` builds the test APK (`assembleDebugAndroidTest`) but doesn't run it. CI runs it on an API 36 emulator. An emulator run never counts as the physical Seeker check.

## Verification record: SAW-004

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`toolchain.md`](toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check:android` | PASS: Spotless, 40/40 unit tests, Android lint with no issues, debug APK |
| ViewModel with a fake transport | PASS (12). Covered: exact text and a single OK; a rapid second tap ignored; a timeout exactly at `expires_at`; a command that arrives already expired; the sidecar's answers to an OK (timeout, cancelled, unknown, unreachable); connection loss clearing the text; a wrong token; a replaced stream; reconnect; disconnect; background and foreground; input validation. |
| Compose screen on Robolectric (SDK 36) | PASS (6). Covered: exact plain text, including Unicode and text that looks like markup or a link; the one-tap OK; the disabled states and every status message; the 4096-byte maximum text with OK still reachable; the form locked while connected; every error message and the limitation note. |
| Activity lifecycle on Robolectric | PASS (3). Two rotations during a command keep one stream, the text, and a single OK. A rapid double tap sends one OK. Backgrounding closes the stream, and returning opens a new one without the old text. |
| Real transport against the real sidecar | PASS (4). An MCP SDK agent's text, with Unicode and a newline, arrives through `ConnectLiveCommandTransport` over HTTP/1.1, and the agent gets back `{"id", "result": "OK"}` with the same ID. A wrong token maps to Unauthenticated, an unknown command to UnknownCommand, and a sidecar SIGTERM to Unreachable. |
| Release network policy | PASS. `aapt2` finds `networkSecurityConfig` and its resource in the debug APK only. Both APKs request only `INTERNET`, plus AndroidX's app-private signature permission. `NetworkSecurityPolicyTest` guards the source files. |
| Deliberate breaks | Each break failed the tests meant to catch it; see the list below. |
| `pnpm check` | PASS: 65/65 sidecar tests |
| Install on a physical Seeker, with a USB connection to the Mac's sidecar | NOT RUN: no device was attached during verification. The steps are in "Debug URL and USB connection" above; SAW-006 and SAW-008 record the device checks. |

The deliberate breaks, and what caught each one:

- **Removing the ViewModel's double-tap guard** failed three ViewModel tests. The Activity double-tap test still passed, because the disabled OK button also blocks the second tap.
- **Removing the rotation guard** failed the rotation test.
- **Keeping the command after a disconnect** failed three ViewModel tests.
- **Moving the cleartext config into the main manifest** failed `NetworkSecurityPolicyTest`.
