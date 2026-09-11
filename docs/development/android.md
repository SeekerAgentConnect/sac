# Android

The app opens on **Connections**: the sidecars this phone is paired with (SAW-012). From there, **Add connection** pairs with a new sidecar, a connection's details refresh, rename, or disconnect it, and **Live test** opens the Stage 1 live-test screen. That screen connects to the sidecar's `LiveCommandService` ([`docs/protocol.md`](../protocol.md)), shows an agent's text, and sends the user's OK back. The app uses stock Jetpack Compose and Material 3 components only, and no wallet.

## Connections

The owner's walkthrough is [`docs/guides/pairing.md`](../guides/pairing.md), and the security model, including what the phone stores, is [`docs/security.md`](../security.md#local-storage-and-recovery).

| Screen | What it shows and does |
| --- | --- |
| **Connections** | The app's first screen. It shows each paired sidecar with its name, host, and status. **Add connection** pairs a new one, and **Live test** opens the Stage 1 screen. |
| **Add connection** | **Scan QR code** asks for the camera permission, then scans with the back camera. The code can also be typed or pasted. A malformed code gets the reason. A valid one shows the server's URL and ID to confirm, and notes a server the phone already knows. **Pair** exchanges the code for a connection. |
| **Connection details** | The status and the last refresh, the server URL and ID, the connection ID, when it paired, and the device name the sidecar saw. It offers **Refresh**, **Rename**, and **Disconnect**. A connection the sidecar no longer accepts offers **Remove from this phone** instead. |

The code is in `connections/`:

| File | Role |
| --- | --- |
| `PairingCode.kt` | Reads `seekervault://pair` codes by the sidecar's rules. Plain HTTP is accepted only where the platform's network security policy permits cleartext: loopback, in debug builds. |
| `ConnectConnectionGateway.kt` | `Pair`, `ListPending`, and `RevokeConnection` over Connect-Kotlin and OkHttp, with the platform's certificate and host name checks. It classifies errors for the screens, including a certificate failure that OkHttp suppressed behind another address's failure. |
| `ConnectionRepository.kt` | Pairs, refreshes, renames, disconnects, and removes. It checks each `PairResponse`, sends each credential only to its own URL, counts only the connection's own requests, and deletes a credential the sidecar rejects. |
| `storage/ConnectionStore.kt`, `storage/CredentialVault.kt`, `storage/AndroidKeystoreKey.kt` | The app's only storage: one JSON file per connection in `filesDir/connections/`, and the credentials, AES-256-GCM under a Keystore key, in `noBackupFilesDir/credentials/` |
| `ConnectionsViewModel.kt` | The screens' state: the pairing flow, refreshes, dialogs, and messages. The code being entered stays in memory, never in saved state. |
| `ConnectionsScreen.kt`, `ConnectionDetailsScreen.kt`, `AddConnectionScreen.kt`, `ConnectionText.kt` | The stateless screens, the camera permission, and their texts |
| `QrScanner.kt`, `QrDecoder.kt` | The CameraX preview and frame analysis, and ZXing's QR decoder |

`SeekerVaultApp.kt` holds the navigation: a back stack of route strings in saved state, so a rotation or a process restart keeps the screen. No route carries a secret.

- **The app fetches when it opens and when the owner opens a connection** ([`docs/protocol.md`](../protocol.md#phone-api)), and on **Refresh**. Nothing runs in the background.
- **The camera is optional** (`android.hardware.camera.any`, not required). Without a camera, or with the permission denied, the owner enters the code. **Open settings** leads to the app's permission settings.
- **Nothing is backed up.** The manifest sets `allowBackup="false"`, and `data_extraction_rules.xml` excludes every domain from cloud backup and device transfer.

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

**Live test** on Connections opens `LiveCommandScreen`, a stateless composable in `live/LiveCommandScreen.kt`, which gets its state from `LiveCommandViewModel`. The network sits behind `LiveCommandTransport`. The real implementation, `ConnectLiveCommandTransport`, uses the generated Connect-Kotlin client over OkHttp.

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
- The app requests `INTERNET`, and `CAMERA` only when the owner taps **Scan QR code**. It requests no notification or biometric permissions. The manifest also carries `io.github.brrenat.seekervault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX Core declares for the app's own non-exported receivers. That one is private to the app, signature-level, and never shown to the user.
- **Libraries add a few manifest entries of their own.** CameraX brings `MetadataHolderService`, which is disabled and not exported, and holds only its configuration. AndroidX Startup's `InitializationProvider` isn't exported. The profile installer's receiver answers only `adb`. Debug builds add the Compose preview and test activities. None of them runs in the background.
- `NetworkSecurityPolicyTest` keeps it that way. It fails if the main source set sets `networkSecurityConfig` or `usesCleartextTraffic`, or if the debug exception covers anything but `127.0.0.1` and `localhost`.

## Lifecycle and its limits

- **The stream exists only in the foreground.** When the app goes to the background (`onStop` without a configuration change), the screen closes `WatchCommands`. When the app returns, it reconnects on its own, but only if it was connected before.
- **The stream exists only while the live-test screen is open.** Going back to Connections closes it; a rotation doesn't.
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
| `MainActivityTest` | The live-test screen in the activity, with a fake transport, on Robolectric: rotation during a command, a rapid double tap, background then foreground, and leaving the screen |
| `ConnectLiveCommandTransportTest` | The real transport against the real sidecar (`node sidecar/src/main.ts`), with an MCP SDK client as the agent: text in, the same command's OK out. Also covers a wrong token, an unknown command, and a sidecar stop. It needs Node 24 and `pnpm install`. `ConnectLiveCommandTransportUnreachableTest` covers a closed port. |
| `LiveProtocolFixturesTest`, `LiveCommandDeadlineTest` | Protocol fixtures and deadline boundaries (SAW-002) |
| `RequestProtocolFixturesTest` | The durable request fixtures (SAW-009). Each message is built in Kotlin and must match buf's bytes in both directions. The cases cover exact message text, amounts as strings, and the uint32 and uint64 maximums, which Kotlin reads as a signed `Int` and `Long`. |
| `PairingCodeTest` | Pairing codes: the sidecar's own example, loopback HTTP in debug builds only, normalization, every malformed case, and the token kept out of `toString` |
| `CredentialVaultTest`, `ConnectionStoreTest` | Storage on Robolectric: round trips across a restart, no plaintext credential on disk, a fresh IV per write, isolation (deleting one connection keeps the other, and a credential copied under another connection's name doesn't decrypt), another key, damaged files, and file names that aren't connection IDs |
| `ConnectionRepositoryTest` | Against two fake sidecars:<ul><li>connections kept apart, a restart, and a rename</li><li>revocation, and a known server at a new address</li><li>unusable `PairResponse`s</li><li>removal that neither touches the other connection nor gives it the removed one's requests</li><li>unreachable sidecars, an unreadable credential, orphaned credentials, and a Keystore failure</li><li>secrets kept out of the log and the metadata</li></ul> |
| `ConnectionsViewModelTest` | The pairing flow (malformed codes, confirmation, a known server, a refused code, a retry), disconnect and removal, rename, and the refresh when the app opens |
| `ConnectionsScreenTest`, `ConnectionDetailsScreenTest`, `AddConnectionRouteTest` | Compose on Robolectric: the statuses, rename errors, and every dialog; camera denial and grant through the activity result registry, and a phone without a camera; malformed codes; and no token or credential on screen |
| `ConnectionsActivityTest` | The activity with the app's own storage and a fake sidecar: pairing, rotation, rename, disconnect, and where the secrets are on disk |
| `ConnectConnectionGatewayTest`, `TwoSidecarsTest` | The real client against the real sidecar, `node sidecar/src/main.ts`. A code printed by `pnpm pair` is read by the app's parser and paired. They also cover pending requests, a reused code, a code for another address, revocation by the phone and by `pnpm pair revoke`, a replaced phone, a stopped sidecar, and two sidecars at once. |
| `ConnectConnectionGatewayTlsTest` | HTTPS with MockWebServer: an untrusted certificate and a certificate for another host name fail before anything is sent, and a trusted one pairs |
| `QrDecoderTest` | QR codes drawn by ZXing, decoded from luminance planes with and without row padding |
| `StageBoundaryTest` | The stage boundary. The manifest declares only `MainActivity`, `INTERNET`, and an optional camera. Storage and Keystore APIs appear only in `connections/storage/`, and background APIs nowhere. Nothing is backed up. No wallet, Room, DataStore, or WorkManager library is on the classpath. |

Robolectric 4.16 runs the UI tests on SDK 36 (`src/test/resources/robolectric.properties`), its newest supported SDK. The app itself targets SDK 37.

### On a device or emulator

`src/androidTest/.../LiveCommandDeviceTest.kt` runs the round trip on real Android. It opens **Live test**, enters the phone token, taps **Connect**, waits for the agent's text, checks that the text is exact, and taps **OK** twice. It needs a running sidecar and an agent, so run it with `pnpm test:hello --device`. The script:

1. Picks the one attached device or emulator, or the one in `ANDROID_SERIAL`.
2. Starts the sidecar on a free port with throwaway tokens, never your `.env`, and runs `adb reverse` for that port.
3. Runs `./gradlew :app:connectedDebugAndroidTest`, passing the URL, the phone token, and the text as instrumentation arguments.
4. Once the app has connected, sends the text over MCP with the test agent.
5. Passes only if all of these hold: the UI test passes, the agent prints `{"id","result":"OK"}`, and the sidecar logged exactly one acknowledgement.

Gradle installs the debug app and the test APK, then removes both after the run. To get the app back, reinstall it with `adb install`.

`CredentialVaultDeviceTest` runs in the same pass, since the JVM tests have no Keystore. It stores a credential under the real Keystore key and reads it back. It checks that the file holds no plaintext, that the key can't be exported, and that a copy under another connection's name doesn't decrypt.

`pnpm check:android` builds the test APK (`assembleDebugAndroidTest`) but doesn't run it. CI runs it on an API 36 emulator. The script names what it ran on: "the Seeker", identified by brand `solanamobile` and model `Seeker`; "an emulator"; or "a phone that isn't a Seeker". Only the Seeker counts as the physical Seeker check.

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

## Verification record: SAW-012

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`toolchain.md`](toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check:android` | PASS: Spotless, 142/142 unit tests (81 more than before), Android lint with no issues, and the debug and instrumentation APKs. Lint first caught `URLDecoder.decode(String, Charset)`, which needs API 33 while `minSdk` is 31; the parser now uses the charset-name overload. |
| Two connections, rename, restart, revocation | PASS, in `ConnectionRepositoryTest` and `ConnectionsViewModelTest` against two fake sidecars, and in `TwoSidecarsTest` against two real ones. Each credential went only to its own server. A rename and both connections survived a new repository over the same files. A revoked credential was deleted and never sent again. |
| Deleting connection A | PASS. B kept its metadata, its readable credential, and its pending count. A's request, even when a sidecar returned it for B, was never counted for B. On the real sidecar, A's connection was revoked there and B's sidecar never saw it. |
| Changed server identity | PASS. A code for a known server at a new address made a new connection. The old one kept its URL, and its credential never went to the new address. The old connection then showed as revoked. |
| Invalid certificates | PASS, in `ConnectConnectionGatewayTlsTest`. An untrusted certificate and a certificate for another host name both failed with `CertificateRejected`, before anything reached the server. A trusted one paired and sent the token only in its `Authorization` header. The test first found OkHttp hiding the certificate failure behind a refused IPv6 connection, and the classifier now reads suppressed exceptions. |
| Camera denial and malformed pairing data | PASS, in `AddConnectionRouteTest`. The permission went through a fake activity result registry: refused, granted, and already granted. A phone without a camera, a scanned code that isn't a pairing code, and five malformed entered codes each showed their message. |
| Secrets | PASS. No token or credential appeared in the log, in any screen's semantics tree, or in plain text in any file of the app's data directory. The credential file is under `noBackupFilesDir`, and `StageBoundaryTest` checks that nothing is backed up or transferred. |
| Real sidecar | PASS, in `ConnectConnectionGatewayTest`. The app's parser read a code printed by `pnpm pair`, and the app paired with it. A reused code, a code sent from another address, revocation by the phone and by `pnpm pair revoke`, a replaced phone, and a stopped sidecar each got the right error. The sidecar's log held no secret. |
| `pnpm check`, `pnpm test:hello`, `pnpm check:generated` | PASS: 235/235 sidecar and 15/15 test agent tests, the 9/9 Stage 1 acceptance cases, and current generated code |
| Deliberate breaks | Each break failed its test class, and each file was restored byte for byte afterwards:<ul><li>a `PairResponse`'s connection ID used unchecked</li><li>another connection's requests counted</li><li>a rejected credential kept</li><li>credentials not bound to their connection</li><li>plain HTTP accepted like HTTPS</li><li>suppressed TLS failures ignored</li><li>backups turned on</li><li>the token shown on the confirmation</li><li>credentials stored outside `noBackupFilesDir`</li></ul> |
| `CredentialVaultDeviceTest` and `LiveCommandDeviceTest` on an emulator | NOT RUN locally: no device or emulator was attached. CI's emulator job runs both through `pnpm test:hello --device`. |
| Physical Seeker: scanning, pairing, and the Keystore | NOT RUN: no device was attached. The owner's steps are in [`docs/guides/pairing.md`](../guides/pairing.md). |
