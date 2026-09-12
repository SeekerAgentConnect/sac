# Android

The app opens on **Connections**: the sidecars this phone is paired with (SAW-012). From there, **Pending requests** shows what agents asked (SAW-013), **Add connection** pairs with a new sidecar, a connection's details refresh, rename, or disconnect it, and **Live test** opens the Stage 1 live-test screen. That screen connects to the sidecar's `LiveCommandService` ([`docs/protocol.md`](../protocol.md)), shows an agent's text, and sends the user's OK back. The app uses stock Jetpack Compose and Material 3 components only, and no wallet.

## Connections

The owner's walkthrough is [`docs/guides/pairing.md`](../guides/pairing.md), and the security model, including what the phone stores, is [`docs/security.md`](../security.md#local-storage-and-recovery).

| Screen | What it shows and does |
| --- | --- |
| **Connections** | The app's first screen. Its first row is **Wallet**, then **Pending requests**, then each paired sidecar with its name, host, and status. **Add connection** pairs a new one, and **Live test** opens the Stage 1 screen. |
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

## Pending requests

The owner's guide is [`docs/guides/pending-requests.md`](../guides/pending-requests.md), and the test procedure is [`docs/testing/stage-2.md`](../testing/stage-2.md).

| Screen | What it shows and does |
| --- | --- |
| **Pending requests** | Opened from its row on Connections for every connection, or from a connection's details for that one only. It lists **Waiting for you**, **Waiting to be sent**, and **Answered**. Each request shows its source, action, age, and expiry. A connection whose fetch failed is named at the top, and there are empty and no-server states. **Refresh** fetches again. |
| **Request details** | The source, action, message (plain text, never parsed), the agent's note (apart, marked as not verified), created, expires, and request ID. A pending acknowledgement offers **Acknowledge** and **Reject**, both disabled while the answer is sent. A pending message to sign offers **Approve and sign** and **Reject**, and adds the complete message with its invisible characters marked, how many bytes will be signed, the wallet and network that would sign, and a line saying a signature is not a payment (SAW-016). Once answered, it shows the stored outcome instead. A waiting answer offers **Send again**. |

How the code works:

- **`ConnectionRepository` does the fetching and answering,** because each call needs a connection's credential, which never leaves it:
  - `refresh` first sends the answers still waiting, then reads every page of PENDING requests, up to 10 pages of 100, into an in-memory `Inbox`. It counts only the connection's own requests, and only those with a UUID request ID. One fetch per connection runs at a time, and a request whose answer settled meanwhile doesn't come back.
  - `answer` writes a `LocalResult` through `ResultStore` before calling `SubmitResult`, and a request gets one answer.
  - `deliver` sends a waiting answer, with at most one send per answer at a time. A send that finds another already running waits for it and then returns what that one settled, so an answer stored while a send was in the air still goes out; a refresh, which has other work to get through, leaves it to the send in flight instead (SAW-017). The sidecar's reply settles it: accepted, superseded (`INVALID_STATE`, with the request from the `RequestErrorDetail`), or undeliverable (revoked). A failure keeps it waiting. A reply is written only if the connection and the answer are still there, checked under the lock that removal holds, so a connection removed mid-send stays removed. A failure never turns an answer that a revocation settled back into a waiting one. `deliver` talks to a sidecar and never to a wallet.
- **Settled answers are kept for a week from when they settled,** so a reopened request still shows its outcome. Removing a connection deletes its answers.
- **`InboxViewModel` guards the buttons:** a request that's being sent or already answered ignores further taps.
- **Approving a message is two sends with the wallet in between (SAW-016).** `InboxViewModel.approve` takes the wallet the screen showed; if that isn't the one connected now, it stops and says so, and the wallet is never opened. Otherwise `answer(key, Answer.Approve)` stores the approval and sends it, and only once the sidecar has accepted it does `WalletRepository.sign` open the wallet. `recordSigning` stores what the wallet did — a signature, a refusal, or a failure — and sends that as `message_signature`, `rejection`, or `execution_failure`. `deliver` re-sends whatever the sidecar hasn't taken.
- **An answer the phone never received is unresolved (SAW-017).** If the app dies while the message is with the wallet, or the wallet never answers within ten minutes, the approval is settled as `SigningOutcome.Unresolved`: the screen says this phone never learned what the wallet did, and the sidecar is told the request failed. `InboxViewModel.onAppVisible`, from `MainActivity.onStart`, settles them on every return to the foreground, skipping the signings still in flight in this process; `ConnectionRepository.load` does the same when the app starts. Neither ever asks a wallet, and the first outcome stored stands, so an answer that turns up afterwards changes nothing. See [`docs/testing/wallet-lifecycle.md`](../testing/wallet-lifecycle.md).
- **A signature is kept only if it's over the bytes that were asked for.** A wallet that reports other bytes has signed nothing this request asked for, and the phone records a failure instead.
- **Nothing runs in the background.** The app fetches when a connection is opened, on **Refresh**, and when it opens or comes back to the foreground (`ConnectionsViewModel`). `MainActivity` reports the return in `onStart`, after an `onStop` that wasn't a rotation. Loading the inbox sends no answer.

## Wallet

The owner's guide is [`docs/guides/wallet-setup.md`](../guides/wallet-setup.md), the boundary is in [`docs/architecture.md`](../architecture.md#the-wallet-adapter-boundary), and the contract in [`docs/protocol.md`](../protocol.md#the-wallet-binding).

| Screen | What it shows and does |
| --- | --- |
| **Wallet** | Opened from the first row on Connections. With no wallet connected it explains what the app does and doesn't learn, offers **Mainnet**, **Devnet**, and **Testnet**, and **Connect wallet**. With one connected it shows the address, the network, the wallet's own name for the account, and when the owner connected it, plus **Disconnect wallet**. At the bottom it says how many connections were told, and offers **Tell them again** for the ones that couldn't be. |

The code is in `wallet/`:

| File | Role |
| --- | --- |
| `Wallet.kt` | `WalletNetwork` (its MWA chain and its protocol `Network`) and `SelectedWallet`: the address, network, label, when it was chosen, and whether the wallet confirmed the network |
| `WalletAdapter.kt` | The boundary: `connect(network, authToken)`, `disconnect(authToken)`, `signMessage(message, wallet, authToken)` (SAW-016), and the outcomes (connected or signed, no wallet, declined, authorization expired, network unsupported, failed) |
| `MwaWalletAdapter.kt` | The only file that imports the Mobile Wallet Adapter client. It connects through the activity's `ActivityResultSender`, waiting briefly for the next screen's while a rotation replaces one (SAW-017), reads the account and authorization from `AuthorizationResult`, and signs with `signMessagesDetached` on the chain the owner connected on, checking that the wallet signed with the account that was asked and returning what it says it signed. It maps the wallet's errors: `AUTHORIZATION_FAILED` is a refusal when the phone offered no authorization and an expiry when it did, `NOT_SIGNED` is a refusal to sign, and `CLUSTER_NOT_SUPPORTED` is the network. |
| `Base58.kt` | Writes an address the way the sidecar's `requests/action.ts` does, and reads one back for the wallet, which takes an account as its raw key bytes |
| `storage/WalletStore.kt` | The selection as JSON in `filesDir/wallet/`, and the wallet's authorization, AES-256-GCM under the Keystore key with its own associated data, in `noBackupFilesDir/wallet/` |
| `WalletRepository.kt` | Connects, keeps, and disconnects the wallet, and publishes the binding to each usable connection. It reuses the stored authorization, drops one the wallet refused, and tracks which connections have already been told, so a connection paired later is told on the next publication. `sign` asks the wallet for a signature, and only for the selection the owner reviewed. |
| `WalletViewModel.kt`, `WalletScreen.kt`, `WalletText.kt` | The screen's state, the stateless screen, and its texts |

The approval and the message itself live with the inbox: `connections/SignMessage.kt` holds the exact message bytes and the approval's SHA-256, and `inbox/InboxText.kt` the preview that makes invisible characters visible.

- **MWA runs the wallet from an Activity.** `MainActivity` registers an `ActivityResultSender` in `onCreate` and clears it in `onDestroy`, and `SeekerVaultApplication` hands it to `MwaWalletAdapter`. There is no dedicated wallet activity and no foreground service.
- **The authorization never leaves the phone.** It goes to the wallet and to `WalletStore`, and nowhere else. `WalletRepositoryTest` and `WalletActivityTest` assert that it reaches no server.
- **Publishing is idempotent and retried.** `ConnectionRepository.publishWallet` sends the binding to one connection, marks the connection revoked on `UNAUTHENTICATED`, and takes the requests the sidecar cancelled off the inbox. Opening the app again re-sends what a connection hasn't been told yet.
- **The network is the owner's explicit choice,** and it's fixed while a wallet is connected. If the wallet lists chains for the account and the chosen one isn't among them, the screen says the wallet didn't confirm it rather than pretending it did.

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
| `ConnectionsViewModelTest` | The pairing flow (malformed codes, confirmation, a known server, a refused code, a retry), disconnect and removal, rename, and the refresh when the app opens or comes back to the foreground, but not on a rotation |
| `ConnectionsScreenTest`, `ConnectionDetailsScreenTest`, `AddConnectionRouteTest` | Compose on Robolectric: the statuses, rename errors, and every dialog; camera denial and grant through the activity result registry, and a phone without a camera; malformed codes; and no token or credential on screen |
| `ConnectionsActivityTest` | The activity with the app's own storage and a fake sidecar: pairing, rotation, rename, disconnect, and where the secrets are on disk |
| `ConnectConnectionGatewayTest`, `TwoSidecarsTest` | The real client against the real sidecar, `node sidecar/src/main.ts`. A code printed by `pnpm pair` is read by the app's parser and paired. They also cover pending requests, a reused code, a code for another address, revocation by the phone and by `pnpm pair revoke`, a replaced phone, a stopped sidecar, and two sidecars at once. |
| `ConnectConnectionGatewayTlsTest` | HTTPS with MockWebServer: an untrusted certificate and a certificate for another host name fail before anything is sent, and a trusted one pairs |
| `QrDecoderTest` | QR codes drawn by ZXing, decoded from luminance planes with and without row padding |
| `InboxTest`, `InboxRealSidecarTest` | The inbox against fake sidecars and the real one:<ul><li>an approved message: the approval sent first and the signature after it, both kept while the server is unreachable and sent on the next refresh, the first outcome kept, a rejection with no approval, an approval of something that isn't a message refused, an approval the wallet never answered settled as unresolved when the app opens again or comes back to the foreground, a signing still with the wallet left alone, and a signature stored while an earlier send was running still delivered</li><li>publishing another wallet takes the requests it no longer fits off the inbox, and a binding goes only to its own connection's server</li><li>a request made while the app was closed, fetched, answered, and read back by the agent</li><li>every page, and nothing answered by a fetch</li><li>one answer per request</li><li>an answer kept through an unreachable server and a restart</li><li>a lost response sent again and recognized</li><li>overlapping sends</li><li>identical request IDs on two servers</li><li>a request cancelled first, and a revoked connection</li><li>a removed connection's answers, and pruning</li><li>an older fetch that returns last, and a fetch that crosses an answer</li><li>a request ID that isn't a UUID</li><li>retention counted from when an answer settled</li><li>a fetch that finishes after its connection was removed</li><li>a reply, successful or failed, that arrives after its connection was removed, and a failure that arrives after a revocation</li></ul> |
| `ResultStoreTest` | Stored answers: a restart, identical request IDs on two connections, damaged files, file names that aren't UUIDs, an approval and each signing outcome across a restart — including the unresolved one — and a file written before approvals existed |
| `InboxViewModelTest` | Rapid second taps, refreshing every connection, sending again, requests that aren't pending, and message signing (SAW-016): no wallet call before the approval is accepted, the approval sent before the signature, a wallet that declined or couldn't sign, a signature over other bytes discarded, a request that moved on while it was reviewed, a selection that changed during the review, no wallet connected, a request for another wallet, and a rejection that never touches the wallet. The lifecycle (SAW-017): rapid taps on Approve, a signing settled as unresolved when the screen comes back without one, a wallet that never answers, a lost response sent again with the counted wallet calls still at one, and a reply that can't settle another connection's request. |
| `MessagePreviewTest` | What the owner sees of a message: text as it is, the bytes the wallet will sign, control characters and zero-width, bidirectional, and no-break characters marked where they are, a 4096-byte message shown whole, and bytes shown as hex |
| `PendingRequestsScreenTest`, `RequestDetailsScreenTest` | Compose on Robolectric: source, action, age, and expiry; the empty, no-server, and offline states; disabled buttons while sending; the stored outcome; **Send again**; expired and superseded requests; and a message to sign — the whole message with its invisible characters marked, the byte count, the signing wallet, **Approve and sign** disabled without a wallet, the wallet-changed warning, and each outcome the wallet can give |
| `InboxActivityTest` | The activity with the app's own storage and a fake sidecar: a request fetched when the app opens, answered, and still answered after a rotation; a connection's own requests; a request made while the app was in the background, fetched when it comes back but not on a rotation; and a rotation while the wallet holds an approved message, after which the request is still there, the wallet has been asked exactly once, and the signature settles it (SAW-017) |
| `Stage2AcceptanceTest` | The Stage 2 acceptance scenario (SAW-014), with the app's own repository and storage and two real sidecars, which `RealSidecar` restarts:<ul><li>requests queued while the app is closed survive both sidecars' restarts, and complete after the app reopens</li><li>an answer given while its sidecar is down goes out after the app and the sidecar restart</li><li>a request that expires while its sidecar is down is superseded</li><li>`pnpm pair revoke` shuts out only that connection</li></ul>See [`docs/testing/stage-2.md`](../testing/stage-2.md#the-acceptance-scenario-saw-014). |
| `Base58Test` | The address encoder and decoder: the fixtures' public keys both ways, leading zero bytes, bytes above 0x7F, and characters outside the alphabet |
| `WalletStoreTest` | The wallet on Robolectric: the selection read back, the authorization encrypted and absent from the plain file, another key that can't open it, damaged and truncated files, and clearing both files together |
| `WalletRepositoryTest` | Against a fake wallet and a fake sidecar: the selection stored and published, the authorization kept off the wire and reused on the next connect, a decline that changes nothing, no wallet installed, an authorization the wallet refused, an unsupported network, a network the wallet didn't confirm, disconnecting, reading the wallet back after a restart, a selection whose authorization is gone, the connections that couldn't be told, telling only the ones that haven't heard it, and signing: the stored authorization used, nothing asked without a connected wallet or for a selection that isn't the reviewed one, and an authorization the wallet refused while signing forgotten |
| `WalletViewModelTest` | The screen's state: the chosen network, every refusal the wallet can give, disconnecting, the connections that couldn't be told, and a connection paired later being told when the app comes back |
| `WalletScreenTest` | Compose on Robolectric: the networks and **Connect wallet**, the address and network once connected, the unconfirmed-network warning, each problem message, the unpublished connections and **Tell them again**, and the disabled controls while the wallet is busy |
| `WalletActivityTest` | The activity with the app's own storage, a fake wallet, and a fake sidecar: connecting from the Connections screen publishes the address and survives a restart, disconnecting tells the wallet and the sidecar, a connection paired afterwards is told when the app comes back, and no wallet installed is explained |
| `StageBoundaryTest` | The stage boundary. The manifest declares only `MainActivity`, `INTERNET`, and an optional camera. Storage and Keystore APIs appear only in `connections/storage/` and `wallet/storage/`, and background APIs and wallet-key APIs nowhere. Nothing is backed up. The Mobile Wallet Adapter client is on the classpath on purpose (SAW-015); Seed Vault's own SDK, Room, DataStore, and WorkManager are not. |

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

## Verification record: SAW-013

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`toolchain.md`](toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check:android` | PASS: Spotless, 181/181 unit tests (39 more than before), Android lint with no issues, and the debug and instrumentation APKs |
| A request made while the app was closed | PASS, in `InboxRealSidecarTest` against the real sidecar. The agent's request was stored before the app's repository existed. The next start fetched it, and the agent still read PENDING. It was acknowledged, and the agent read COMPLETED. A repeat of the result returned COMPLETED again. |
| Lost responses, retries, and restarts | PASS, in `InboxTest`. An answer that couldn't be sent waited through a restart, and went out on the next refresh. A lost response was sent again, and applied once. |
| Duplicate taps and overlapping sends | PASS. A second tap while sending, or on an answered request, sent nothing (`InboxViewModelTest`). A refresh during a send didn't send it again (`InboxTest`). |
| Identical request IDs on two servers | PASS: each answer went only to its own server, and was stored apart |
| Nothing automatic | PASS. Fetching submitted nothing (`InboxTest`, `InboxActivityTest`). `StageBoundaryTest` found no background component and no push library. |
| Screens | PASS:<ul><li>source, action, age, and expiry</li><li>the empty, no-server, and offline states</li><li>buttons disabled while sending</li><li>the stored outcome on reopening, and after a rotation</li></ul> |
| `pnpm check`, `pnpm test:hello`, `pnpm check:generated` | PASS: 235/235 sidecar tests and 19/19 test agent tests, the 9/9 Stage 1 acceptance cases, and current generated code |
| Deliberate breaks | NOT RUN (timed out): the run hung during its third break, in `InboxTest`, and was stopped before it reported. The file that break had changed was restored. |
| Physical Seeker | NOT RUN: no device was attached. The owner-run check is in [`docs/testing/stage-2.md`](../testing/stage-2.md). |

## Verification record: SAW-014

The Stage 2 acceptance report, including the app's side of the scenario, is in [`docs/testing/stage-2.md`](../testing/stage-2.md#acceptance-report-saw-014).
