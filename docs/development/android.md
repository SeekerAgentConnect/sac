# Android

The app opens on **Home**, with the connected wallet, requests waiting for the owner, Global rules, and paired sidecars. **Home**, **Requests**, **Wallet**, and **Activity** are persistent root destinations; request, connection, policy, and record details open as a bottom-sheet stack over the selected root. **Add connection** pairs with a new sidecar, and the retained **Live test** route opens the Stage 1 diagnostic. That screen connects to the sidecar's `LiveCommandService` ([`docs/protocol.md`](../protocol.md)), shows an agent's text, and sends the user's OK back.

## Material 3 v4 presentation (SEE-64)

SEE-64 changes presentation and navigation structure only. Request preparation and answering, transaction inspection, policy evaluation and storage, wallet hand-off, connection pairing, Activity storage, and all of their security boundaries are unchanged.

- `SeekerVaultTheme` follows the system light/dark setting. Its colour schemes use the exact opaque v4 tokens, and `SeekerTheme` carries the one extra semantic token whose light and dark values differ from the standard Material role.
- `SeekerComponents.kt` owns the shared solid Material surfaces: buttons, cards, network chips, bottom navigation, sheets, dialogs, and transient messages. Every colour is fully opaque. Elevation shadows, translucent scrims, alpha fades, gradients, and blur-behind are deliberately absent.
- Home keeps its 64 dp app bar outside the scrolling body. After 48 dp of body scroll it replaces the product title with the shortened wallet address and network chip. Waiting requests are whole-card actions in a horizontally snapping 204 by 192 dp carousel. Symmetric viewport-derived content padding puts the first and last cards on the same centre snap point, and the active treatment follows the card actually closest to that point. Answers remain on Request details.
- The selected root remains mounted beneath a detail sheet; the active sheet covers the bottom bar, as in the reference. Each deeper detail adds an opaque 12 dp recessed backplate and becomes the active sheet. Close removes only that layer. Sheet entry is 260 ms and exit is 240 ms.
- Identifiers use the theme's monospace style, controls use Material icons, cards are separated by solid containers rather than divider lines, and primary actions use the v4 lime tokens and shapes.
- Home and Pending requests reserve the bottom-navigation height plus the system navigation inset in their scroll content, so their final actions and cards can move completely above navigation.
- Transfer review separates the owner's approval, verification performed on this device, and the Solana network outcome. Human-readable amount, recipient, wallet, network, fee, and known program names lead; addresses, program IDs, blockhash, instruction count, base units, and the raw transaction ID remain available in collapsed technical details. Sent, confirming, confirmed, and failed are distinct states, and a transaction ID can be copied without implying confirmation.
- Global rules uses the reference sheet's complete, expandable short explanation; a separate defaults caption; Global provenance chips; section icons; smoothly animated solid switches; concise on/off/empty status copy; and individual action, asset, recipient, and program cards. An empty document has an explicit warning and empty rows instead of orphaned prose. Clear all remains an unsaved, reversible edit, while a failed write remains visible in the pinned footer until the owner edits or retries. The footer uses tonal Discard plus filled Save, and the rules model and persistence path are unchanged (SEE-74). The stage-boundary allowlist admits only the read-only v4 theme token added to the policy UI; it still rejects any unlisted app dependency.

The source-of-truth comparison and verification record are in [`docs/testing/see-64.md`](../testing/see-64.md).

## Shared synchronization and cache (SAW-050)

`SynchronizationRepository` is the one application-scoped convergence path for `UpdateService.Sync` responses and revisioned stream events. Manual Refresh, the SAW-051 foreground owner, the SAW-052 periodic worker, and SAW-057 push recovery all use it. `ConnectionRepository.synchronizeAll()` is the process-start entry point: it loads connection metadata, encrypted credentials, stored answers, Activity, and the sync cache without constructing an Activity or ViewModel. Recovery workers may select only connections without a healthy foreground stream; the same four-server bound and per-connection coordinator apply.

The code is under `sync/`:

| File | Role |
| --- | --- |
| `SynchronizationRepository.kt` | Discovers capabilities, coalesces one in-flight Sync per connection, pages and validates a complete snapshot, applies request revisions monotonically, buffers bounded stream overlap, rotates more than 100 nonterminal Activity references across later runs, and publishes `SynchronizationState` |
| `ConnectUpdateTransport.kt`, `UpdateTransport.kt` | Existing-connection capability discovery over Connect, unary `UpdateService.Sync`, and a genuine bidirectional gRPC subscription whose send side remains open for heartbeats. The bearer credential is accepted only as a transient call argument and is redacted by the sole access object's `toString()` |
| `SyncState.kt` | Observable capability, endpoint, request/status, cursor, last-success, and recovery state; no credential, wallet authorization, policy, assessment, or local answer |
| `storage/SyncStore.kt` | One versioned `AtomicFile` document per connection at `filesDir/sync/<connection ID>.json` |

Each document is replaced whole. Snapshot pages remain in memory until the final page passes connection, instance, cursor, reference, revision, size, and duplicate checks; the complete snapshot and every buffered event after it are then one atomic write with the cursor that covers them. A process killed before `finishWrite` reads the previous complete document. A damaged document or unknown future version is not partially decoded: the phone keeps owner-owned results and Activity, starts from no cursor, and rebuilds server state with a full Sync. There is no schema predecessor to migrate in version 1; later versions must either migrate the complete document atomically or take that same full-sync recovery path.

Per-request revisions reject duplicates and stale delivery, equal-revision conflicts, gaps, changed request identity, and state rollback. A complete snapshot may remove cached pending state by absence; it never removes a `LocalResult` or Activity row. At most 512 removal markers are retained to reject late duplicates without turning the cache into server history. A connection deletion or revocation increments its local epoch, cancels its in-flight call, deletes its document, and makes any late response or older stream generation inert.

Synchronization has deliberately narrow authority. Before reading a snapshot it asks `ConnectionRepository` to retry only results already stored by the owner's earlier action. Server state may advance the request copy inside such a result and the existing Activity row derived from it. It cannot create an answer or Activity record, evaluate a policy, prepare a transaction, invoke Mobile Wallet Adapter, sign, send, simulate, or replace a transaction. Credentials remain encrypted in `noBackupFilesDir/credentials/`; they are never placed in the sync document, observable state, worker input, or a log.

## Foreground update lifecycle (SAW-051)

`ForegroundUpdateManager` belongs to `SeekerVaultApplication`. `MainActivity.onStart` announces foreground once, while `onStop` announces background only when it is not a configuration change. Consequently navigation and rotation retain the same per-connection jobs and cannot open a competing stream. A pairing observed on `ConnectionRepository.connections` starts one; removal or revocation cancels it and invalidates its generation before late responses can apply.

Each foreground connection first calls the shared Sync path, then subscribes from the cursor that call made durable. A retained cursor replays through `ReplayComplete`; a restart, gap, invalid cursor, or overflow starts barrier Sync while subsequent responses enter the repository's bounded buffer. The resulting cache publishes through the existing `StateFlow`s, so Home counts, Inbox, Request details, Activity, and connection status recompose without screen entry or manual refresh. One connection has a supervisor-isolated loop: another server being offline does not interrupt it.

The connection status is runtime state, not proof that cached data is current. Screens show **Connecting**, **Live**, **Reconnecting**, actionable unreachable/certificate/cleartext failures, **Revoked**, and update-protocol/configuration failures separately from **Last synced**. Intentional background is **Live updates paused**, never an outage. The bidirectional send side remains open for client heartbeats; three unanswered 15–60 second intervals are the liveness deadline. Transient failures retry with jittered exponential delays from one second through a 30-second cap.

Closing these jobs cannot cancel or start Mobile Wallet Adapter work. The foreground return still invokes the existing unresolved-wallet/result reconciliation, while the stream only retries a result already stored and observes request/Activity state. SAW-051 adds no worker, service, FCM component, preparation, approval, signing, sending, or transaction replacement.

## Periodic background synchronization (SAW-052)

`BackgroundSyncScheduler` starts when the app is used and observes loaded connection state. It does nothing with the initial unknown empty list. Once storage is loaded, at least one usable connection keeps exactly one `sidecar-background-sync` periodic request; no usable connection cancels it. The request uses WorkManager 2.11.2, a connected-network constraint, a 15-minute repeat interval and initial delay, and exponential retry beginning at 30 seconds. `ExistingPeriodicWorkPolicy.KEEP` is deliberate: app starts, rotation, foreground/background transitions, renames, and other connection publications cannot replace the existing request and postpone its next eligible run.

WorkManager persists its own database and can run `BackgroundSyncWorker` after ordinary process death or reboot. The worker takes no credential, URL, request, cursor, or wallet data as input. In a worker-only process it obtains `SeekerVaultApplication`, reloads the connection files and Keystore-encrypted credentials through `ConnectionRepository`, and selects usable connections for synchronization. The shared path retries only a result already saved by the owner, asks the sidecar to reconcile existing nonterminal Activity (including bounded confirmation of already-submitted transfers), and atomically stores the request/status cache and last successful sync before returning. Reopening the app loads that disk result before a screen fetch is needed.

Headless work admits at most four sidecars at once. Each connection has a two-minute overall bound around the transport's existing 30-second per-RPC deadline, so one offline or pathological server cannot hold the others indefinitely. An unreachable connection makes the WorkManager run retry with exponential backoff. Authentication/revocation removes the credential and is permanent for that connection; certificate, cleartext, protocol, and malformed-response failures wait for the next normal period rather than tight-looping. Every usable connection whose foreground stream is already Live is excluded; a mixed two-sidecar run fetches only the connection that needs recovery. Any race with stream reconciliation, Refresh, or push work still reaches the repository's per-connection coordinator and safely coalesces or buffers overlap.

Fifteen minutes is the minimum configured interval, not a delivery deadline. Doze, battery optimization, standby, network constraints, and vendor policy can defer or skip an eligible run. Android Settings **Force stop** suppresses WorkManager until the owner reopens the app. SAW-052 adds no foreground service, direct service, receiver, exact alarm, continuous background socket, artificial rescheduling loop, Firebase dependency, or push wake-up. SAW-054 later places the optional Firebase client on the classpath, but the worker neither imports nor invokes it.

The worker has no preparation, approval, policy-decision, or wallet dependency. It cannot build or replace a transaction, open Mobile Wallet Adapter, sign, send, or repeat a transfer. Those boundaries remain enforced by `StageBoundaryTest`.

## Cross-component update acceptance (SAW-053)

`Stage52AcceptanceTest` starts real Node sidecar processes, drives their actual MCP tools, and connects the production Kotlin update transport to the sidecars' loopback h2c listeners. OkHttp must opt into `H2_PRIOR_KNOWLEDGE` for an advertised loopback `http://` update origin; the transport's ordinary client remains in place for capability discovery and every `https://` endpoint, preserving platform trust, host checking, and ALPN. A deliberate downgrade to HTTP/1.1 makes the joined test fail.

The cases cross the runtime boundary instead of replacing either half with a fake: MCP mutations enter the durable SQLite queue, the production gRPC service publishes them, and the Android repository validates and persists them for the existing reactive screens. They cover foreground delivery, two independently paired sidecars, restart and expiry, background closure, worker-only unary recovery, offline result retry with bounded backoff, and stream cleanup. Sidecar update tests separately exercise secure TLS/HTTP/2 and automatic read-only confirmation of a submitted transfer without `CheckStatus`.

Run the complete joined suite with `pnpm test:updates`; keep running `pnpm test:hello` separately for Stage 1. JVM/Robolectric and sidecar-process success never count as a physical Seeker result. The MacBook-to-Seeker setup, scheduling inspection, and device-only checklist are in the [live and background updates runbook](../guides/live-background-updates.md) and [Stage 5.2 verification record](../testing/stage-5-2.md).

## Optional Firebase registration, Sync, and notifications (SAW-054–SAW-058)

The app pins the current Firebase Android BoM and its main `firebase-messaging` module. The Google
Services plugin is present but conditional: `android/app/build.gradle.kts` applies it only when the
deployment supplies the ignored `android/app/google-services.json`. A clean checkout therefore
builds the same debug and test APKs with no Firebase project configuration, while a configured
operator build gets the resources generated from its own project file.

The source default remains `firebase_messaging_auto_init_enabled=false`. SAW-055's
`FcmRegistrationManager` explicitly enables current Firebase registration only after stored
connections have loaded and at least one is usable; it unregisters and disables again after the
last usable connection disappears. `firebase_messaging_installation_id_enabled=true` selects the
current direct-send API. `SeekerVaultMessagingService` implements `onRegistered` and
`onUnregistered` for ownership, plus the exact SAW-056 invalidation handler below.

Registration callbacks and connection changes enter one application-scoped serialized channel.
The current opaque target is sent through `ConnectionRepository` separately to every usable
connection, using that connection's fixed sidecar URL and encrypted phone credential. A callback
refresh atomically replaces each sidecar's older value. Unregistration sends a compare-clear, so a
delayed callback for an older value cannot erase its replacement. The phone never persists the
value and diagnostics redact it. An unconfigured build has no default `FirebaseApp`, so the client
does nothing; an older or unavailable sidecar cannot stop the other sidecars or any Stage 5.2 path.

SAW-056 accepts only data exactly equal to `kind=request_invalidation` and `version=1`. SAW-057
keeps the callback to that constant-time validation and `PushSyncScheduler.enqueue`; there is no
sidecar or cache fetch inside `FirebaseMessagingService`'s execution budget. The scheduler persists
one unique `push-authoritative-sync` request with empty input, a connected-network constraint,
`KEEP` coalescing, and exponential transient retry. A delivered high-priority ping requests
expedited WorkManager execution with non-expedited fallback.

The worker loads usable connections and encrypted credentials from their normal stores. While the
app is foreground, it omits every connection whose gRPC stream is already Live and fetches only the
recovery set; in background or a worker-only process, every usable connection is eligible. Push
and periodic recovery use the same selective, four-sidecar-bounded repository entry point. The
repository coalesces one in-flight snapshot per connection and buffers stream events across it, so
simultaneous Refresh, stream recovery, periodic work, and push work cannot become competing state
writers. FCM collapse plus WorkManager unique work coalesce duplicate hints at both delivery
boundaries.

SAW-058 declares `POST_NOTIFICATIONS` explicitly and makes configuration presence a generated
boolean without embedding a Firebase identifier. A configured APK creates one high-importance,
secret-lock-screen request channel. Once stored connections load and one is usable, Android 13+
gets one runtime permission request for that activity session; an existing denial/rationale is not
automatically prompted again. The response is not passed to FCM registration, foreground updates,
manual Sync, or either worker. An unconfigured APK creates no app channel, requests no permission,
and posts no app notification.

After a push worker completes authoritative Sync, `RequestNotificationManager` compares only the
pending keys before and after the fetch. It cancels departed keys and posts generic text for new
ones; it never receives an `ActionRequest`. Each key has its own immutable explicit `PendingIntent`
to `MainActivity`, differentiated by an app-local URI so one request cannot replace another's tap.
The route validates both UUIDs, fetches the named paired connection again, and hides review controls
while loading or when the current state is gone, removed, revoked, or unavailable. A request this
phone already answered opens its existing result. The tap chooses no answer and calls no wallet
method; the shared Sync may retry only an answer the owner already stored.

Foreground streams, manual Refresh, unary reconciliation, and the periodic worker remain
independent and authoritative when permission is denied or a hint is delayed, dropped, expired,
throttled, or unavailable. Setup and exact delivery/off behavior are in the [optional Firebase
guide](../guides/firebase.md); automated evidence is in [Stage 5.3
verification](../testing/stage-5-3.md).

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
| `ConnectConnectionGateway.kt` | `Pair`, legacy `ListPending`, authenticated FCM target updates, and `RevokeConnection` over Connect-Kotlin and OkHttp, with the platform's certificate and host name checks. It classifies errors for the screens, including a certificate failure that OkHttp suppressed behind another address's failure. |
| `ConnectionRepository.kt` | Pairs, refreshes, renames, disconnects, and removes. Refresh delegates to the shared update synchronizer and retains `ListPending` for old or unconfigured sidecars. It checks each `PairResponse`, sends each credential only to its own URL, counts only the connection's own requests, and deletes a credential the sidecar rejects. |
| `storage/ConnectionStore.kt`, `storage/CredentialVault.kt`, `storage/AndroidKeystoreKey.kt` | The app's only storage: one JSON file per connection in `filesDir/connections/`, and the credentials, AES-256-GCM under a Keystore key, in `noBackupFilesDir/credentials/` |
| `ConnectionsViewModel.kt` | The screens' state: the pairing flow, refreshes, dialogs, and messages. The code being entered stays in memory, never in saved state. |
| `ConnectionsScreen.kt`, `ConnectionDetailsScreen.kt`, `AddConnectionScreen.kt`, `ConnectionText.kt` | The stateless screens, the camera permission, and their texts |
| `QrScanner.kt`, `QrDecoder.kt` | The CameraX preview and frame analysis, and ZXing's QR decoder |

`SeekerVaultApp.kt` holds the navigation: a back stack of route strings in saved state, so a rotation or a process restart keeps the screen. No route carries a secret.

- **The app fetches when it opens and when the owner opens a connection** ([`docs/protocol.md`](../protocol.md#phone-api)), and on **Refresh**. A configured Stage 5.2 sidecar uses the shared frozen Sync path; old or unconfigured sidecars use `ListPending`. SAW-050 schedules nothing in the background.
- **The camera is optional** (`android.hardware.camera.any`, not required). Without a camera, or with the permission denied, the owner enters the code. **Open settings** leads to the app's permission settings.
- **Nothing is backed up.** The manifest sets `allowBackup="false"`, and `data_extraction_rules.xml` excludes every domain from cloud backup and device transfer.

## Pending requests

The owner's guide is [`docs/guides/pending-requests.md`](../guides/pending-requests.md), and the test procedure is [`docs/testing/stage-2.md`](../testing/stage-2.md).

| Screen | What it shows and does |
| --- | --- |
| **Pending requests** | Opened from its row on Connections for every connection, or from a connection's details for that one only. It lists **Waiting for you**, **Waiting to be sent**, and **Answered**. Each request shows its source, action, age, and expiry. A connection whose fetch failed is named at the top, and there are empty and no-server states. **Refresh** fetches again. |
| **Request details** | The source, action, message (plain text, never parsed), the agent's note (apart, marked as not verified), created, expires, and request ID. A pending acknowledgement offers **Acknowledge** and **Reject**, both disabled while the answer is sent. A pending message to sign offers **Approve and sign** and **Reject**, and adds the complete message with its invisible characters marked, how many bytes will be signed, the wallet and network that would sign, and a line saying a signature is not a payment (SAW-016). A pending transfer adds what this phone read out of the transaction and, for one it read whole, **Approve and send** (SAW-020, SAW-021). Once answered, it shows the stored outcome instead. A sent transfer adds what the server read from the chain, whose word that is, and **Check status** while the chain could still settle it (SAW-022). A waiting answer offers **Send again**. |

How the code works:

- **`ConnectionRepository` owns the credentials and answers, while `SynchronizationRepository` owns cached server state:**
  - `refresh` first retries answers already waiting, then atomically applies every page of a frozen Sync snapshot to the persistent cache and `Inbox`. Old or unconfigured sidecars retain the legacy paginated `ListPending` path. Both count only the connection's own UUID request IDs, and an answer that settled meanwhile cannot be brought back by an older response.
  - `answer` writes a `LocalResult` through `ResultStore` before calling `SubmitResult`, and a request gets one answer.
  - `deliver` sends a waiting answer, with at most one send per answer at a time. A send that finds another already running waits for it and then returns what that one settled, so an answer stored while a send was in the air still goes out; a refresh, which has other work to get through, leaves it to the send in flight instead (SAW-017). The sidecar's reply settles it: accepted, superseded (`INVALID_STATE`, with the request from the `RequestErrorDetail`), or undeliverable (revoked). A failure keeps it waiting. A reply is written only if the connection and the answer are still there, checked under the lock that removal holds, so a connection removed mid-send stays removed. A failure never turns an answer that a revocation settled back into a waiting one. `deliver` talks to a sidecar and never to a wallet.
- **Settled answers are kept for a week from when they settled,** so a reopened request still shows its outcome. Removing a connection deletes its answers.
- **`InboxViewModel` guards the buttons:** a request that's being sent or already answered ignores further taps.
- **Approving a message is two sends with the wallet in between (SAW-016).** `InboxViewModel.approve` takes the wallet the screen showed; if that isn't the one connected now, it stops and says so, and the wallet is never opened. Otherwise `answer(key, Answer.Approve)` stores the approval and sends it, and only once the sidecar has accepted it does `WalletRepository.sign` open the wallet. `recordSigning` stores what the wallet did — a signature, a refusal, or a failure — and sends that as `message_signature`, `rejection`, or `execution_failure`. `deliver` re-sends whatever the sidecar hasn't taken.
- **Approving a transfer is the same two sends, with tighter rules (SAW-021).** `InboxViewModel.approveTransfer` takes the `Preparation.Ready` the screen showed; it stops unless that is still the preparation this phone holds, its inspection came back `Verified`, and the wallet is the one they saw. `ConnectionRepository.approveTransfer` then stores an `ApprovedTransaction` — the version, the content hash, and the exact bytes — sends the approval, and returns `ApprovalOutcome.Accepted` only once the sidecar has taken it. An approval the sidecar refused, or a call that never left the phone, is deleted and the request is read again; the wallet is never opened for one. An approval nobody answered — a dropped connection, a lost response — is kept instead, marked `approvalUncertain`, and the next delivery reads the request (`reconcileApproval`) rather than sending the approval again: `PENDING` means it never arrived and it is dropped, `PROCESSING` means it did and the sidecar is told nothing was signed or sent. All of this runs inside `WalletRepository.withWallet`, so the wait for the one wallet lock happens before the commit, and `InboxViewModel.stillFresh` checks the preparation's blockhash window on both sides of it; only then does the session's `signAndSend` open the wallet, with the stored bytes. `recordSigning` stores the transaction's ID, a refusal, a failure, or an unresolved outcome, sent as `transaction_submission`, `rejection`, `execution_failure`, or `unknown_outcome`.
- **Following a sent transaction is asking the server, and nothing else (SAW-022).** `InboxViewModel.checkStatus` calls `ConnectionRepository.checkStatus`, which calls `RequestService.CheckStatus` and writes only the returned `ActionRequest` onto the stored answer: the owner's answer, the wallet's outcome, and how it was delivered all stand. It opens no wallet, sends no result again, and is offered only while `LocalResult.awaitingChain` — an accepted transfer whose state is not terminal. A check that fails says so (`SigningProblem.NotChecked`) and changes nothing about the transaction.
- **An answer the phone never received is unresolved (SAW-017).** If the app dies while the action is with the wallet, or the wallet never answers within ten minutes, the approval is settled as `SigningOutcome.Unresolved`: the screen says this phone never learned what the wallet did. For a message the sidecar is told the request failed; for a transfer it is told the outcome is UNKNOWN, because the wallet may have sent it (SAW-021). `InboxViewModel.onAppVisible`, from `MainActivity.onStart`, settles them on every return to the foreground, skipping the signings still in flight in this process; `ConnectionRepository.load` does the same when the app starts. Neither ever asks a wallet, and the first outcome stored stands, so an answer that turns up afterwards changes nothing. See [`docs/testing/wallet-lifecycle.md`](../testing/wallet-lifecycle.md).
- **A signature is kept only if it's over the bytes that were asked for.** A wallet that reports other bytes has signed nothing this request asked for, and the phone records a failure instead.
- **Nothing runs in the background yet.** The application-owned production streams run only while foreground. It still fetches when a connection is opened, on **Refresh**, and when it opens or comes back to the foreground. `MainActivity` reports a real return in `onStart`, after an `onStop` that wasn't a rotation. Loading the inbox and receiving an update send no new answer.

## Wallet

The owner's guide is [`docs/guides/wallet-setup.md`](../guides/wallet-setup.md), the boundary is in [`docs/architecture.md`](../architecture.md#the-wallet-adapter-boundary), and the contract in [`docs/protocol.md`](../protocol.md#the-wallet-binding).

| Screen | What it shows and does |
| --- | --- |
| **Wallet** | Opened from the first row on Connections. With no wallet connected it explains what the app does and doesn't learn, offers **Mainnet**, **Devnet**, and **Testnet**, and **Connect wallet**. With one connected it shows the address, the network, the wallet's own name for the account, and when the owner connected it, plus **Disconnect wallet**. At the bottom it says how many connections were told, and offers **Tell them again** for the ones that couldn't be. |

The code is in `wallet/`:

| File | Role |
| --- | --- |
| `Wallet.kt` | `WalletNetwork` (its MWA chain and its protocol `Network`) and `SelectedWallet`: the address, network, label, when it was chosen, and whether the wallet confirmed the network |
| `WalletAdapter.kt` | The boundary: `connect(network, authToken)`, `disconnect(authToken)`, `signMessage(message, wallet, authToken)` (SAW-016), and the outcomes (connected or signed, no wallet, declined, authorization expired, network unsupported, failed). Signing answers with a `SigningAnswer`: the outcome, and the authorization the wallet reported while answering, which redacts itself in `toString`. |
| `MwaWalletAdapter.kt` | The only file that imports the Mobile Wallet Adapter client. It connects through the activity's `ActivityResultSender`, waiting briefly for the next screen's while a rotation replaces one (SAW-017), reads the account and authorization from `AuthorizationResult`, and signs with `signMessagesDetached` on the chain the owner connected on, checking that the wallet signed with the account that was asked and returning what it says it signed. It reads the authorization from inside the session, before asking for the signature, so a signing the wallet declines still carries an authorization it replaced and no second session is ever opened for one. It maps the wallet's errors: `AUTHORIZATION_FAILED` is a refusal when the phone offered no authorization and an expiry when it did, `NOT_SIGNED` is a refusal to sign, and `CLUSTER_NOT_SUPPORTED` is the network. |
| `Base58.kt` | Writes an address the way the sidecar's `requests/action.ts` does, and reads one back for the wallet, which takes an account as its raw key bytes |
| `storage/WalletStore.kt` | The selection as JSON in `filesDir/wallet/`, and the wallet's authorization, AES-256-GCM under the Keystore key with its own associated data, in `noBackupFilesDir/wallet/` |
| `WalletRepository.kt` | Connects, keeps, and disconnects the wallet, and publishes the binding to each usable connection. It reuses the stored authorization, drops one the wallet refused, stores one the wallet replaced without touching the selection, and tracks which connections have already been told, so a connection paired later is told on the next publication. `sign` asks the wallet for a signature, and only for the selection the owner reviewed. |
| `WalletViewModel.kt`, `WalletScreen.kt`, `WalletText.kt` | The screen's state, the stateless screen, and its texts |

The approval and the message itself live with the inbox: `connections/SignMessage.kt` holds the exact message bytes and the approval's SHA-256, and `inbox/InboxText.kt` the preview that makes invisible characters visible.

- **MWA runs the wallet from an Activity.** `MainActivity` registers an `ActivityResultSender` in `onCreate` and clears it in `onDestroy`, and `SeekerVaultApplication` hands it to `MwaWalletAdapter`. There is no dedicated wallet activity and no foreground service.
- **The authorization never leaves the phone.** It goes to the wallet and to `WalletStore`, and nowhere else. `WalletRepositoryTest` and `WalletActivityTest` assert that it reaches no server.
- **Publishing is idempotent and retried.** `ConnectionRepository.publishWallet` sends the binding to one connection, marks the connection revoked on `UNAUTHENTICATED`, and takes the requests the sidecar cancelled off the inbox. Opening the app again re-sends what a connection hasn't been told yet.
- **The network is the owner's explicit choice,** and it's fixed while a wallet is connected. If the wallet lists chains for the account and the chosen one isn't among them, the screen says the wallet didn't confirm it rather than pretending it did.

## Activity

The owner's guide is [`docs/guides/transfers.md`](../guides/transfers.md#the-activity-record). The history is the owner's own record of what this phone did (SAW-023), and it is deliberately not the same thing as a `LocalResult`: an answer is what the sidecar is owed, and it is dropped a week after it settles and when its connection is removed. A record of a payment outlives both.

| Screen | What it shows and does |
| --- | --- |
| **Activity** | Opened from the Connections screen, which shows how many actions are recorded. The list is newest first: who asked, what was done, how it ended, and when. **Refresh** reads the store again, **Clear** removes everything after a confirmation. A history that couldn't be read says so and keeps showing what it last read. |
| **Record** | One record in full: the outcome, the reviewed operation, the connection and its host, the kind of request, the network, the wallet, the recipient, the asset, when it was answered, and the signature. A transfer that was sent offers **View on Solana Explorer** on its own cluster. |

The code is in `activity/`:

| File | Role |
| --- | --- |
| `ActivityRecord.kt` | `ActivityKind`, `ActivityOutcome`, `ReviewedTransfer`, and `ActivityRecord`, with `signatureIsTransaction` and the `identity` that says what counts as the same record |
| `storage/ActivityStore.kt` | One JSON file per request, `filesDir/activity/<connection ID>/<request ID>.json`, written atomically. Nothing prunes it. A directory that exists and can't be listed throws rather than coming back empty. |
| `ActivityLog.kt` | Derives a record from a `LocalResult` and writes it, and holds the list as a `StateFlow` |
| `Explorer.kt` | The explorer address, and `openLink`, which hands it to whatever app opens links |
| `ActivityViewModel.kt`, `ActivityScreen.kt`, `ActivityDetailsScreen.kt`, `ActivityText.kt` | The state, the two stateless screens, and their texts |

- **One writer.** Every place `ConnectionRepository` writes an answer goes through its private `save()`, which writes the record too. A record can't be forgotten at one call site and written at another.
- **An approval the sidecar never accepted is recorded as nothing.** No wallet was opened for one, the approval is removed again, and the request goes back to waiting for the owner (SAW-021). There is nothing that happened, so there is nothing to have a record of.
- **A message signature is never a payment.** `signatureIsTransaction` comes from the record's kind, never from the presence of a signature, and the details screen says so in words as well as by offering no link.
- **The cluster is on every transfer.** The same 64 bytes on another cluster are another transaction, or none, so a record with no network gets no link rather than a guessed one.
- **The explorer is a link, never a fetch.** `StageBoundaryTest` proves both halves: the address appears in `Explorer.kt` and nowhere else, that file holds no HTTP client, and the app's HTTP clients exist only in the two sidecar transports and the one client they share.
- **Amounts are kept in base units.** SOL is also shown the readable way; a token's decimals belong to its mint and are read fresh at review time, so a count stored months ago can never show a wrong amount here.

## Policies

The model is [`docs/policy.md`](../policy.md), and the owner's walkthrough is [`docs/guides/policies.md`](../guides/policies.md). SAW-025 added the rules and the verdict, SAW-026 the code that applies them to a request and counts the day's spending, and SAW-027 the **Rules** screen under each connection where the owner writes them. The verdict still isn't displayed anywhere: the review screen says "Not evaluated" until SAW-028.

The code is in `policy/`:

| File | Role |
| --- | --- |
| `Policy.kt` | `ConnectionPolicy` and the allowlists it holds, `PolicyAsset` (a network and a mint), `AssetLimits` in base units, `PolicyAction`, and `policyProblems`, which the store refuses to write past |
| `PolicyDecision.kt` | `PolicyAssessment`, `PolicyCheck`, `PolicyCheckStatus`, `PolicyReason` and its codes, `PolicyDecision`, and `assess` — the conjunction — with `noPolicy` for a connection that has no rules to apply |
| `RequestFacts.kt` | `RequestFacts` — what the phone established about one request — and `policyFacts`, which reads it off the request and its `TransferInspection` |
| `PolicyEvaluation.kt` | `evaluate`, the six checks, and `PolicyEvaluator`, which reads the rules and the records afresh on every call |
| `DailySpending.kt` | `SpendScope`, `SpendStatus`, `Spend`, `DailyTotal`, and the counters derived from the Activity records |
| `PolicyDraft.kt` | The editor's form: `PolicyDraft`, `AssetDraft`, `readAmount` (decimal to base units by shifting digits), `problemsOf`, `draftOf`, and `review`, the one place a draft becomes a `ConnectionPolicy` |
| `PolicyEditorViewModel.kt` | `PolicyUiState`, and open, edit, save, remove, and start over |
| `PolicyEditorScreen.kt` | The Rules screen: stock Material 3 switches, checkboxes, radio buttons, chips, text fields, lists, Save and Cancel |
| `PolicyText.kt` | `PolicyTags`, the owner's words for each rule and problem, and `summaryLines` — the whole draft read back in plain language |
| `storage/PolicyStore.kt` | One JSON file per connection, `filesDir/policies/<connection ID>.json`, written atomically, with the document version and `StoredPolicy`: `None`, `Policy`, or `Unreadable` |

- **An absent list is not an empty one.** No list configures no check; an empty list configures one that allows nothing. The JSON keeps the difference — an absent key against `[]` — and so does the editor: a switch per list, with all three states said in words rather than left to the shape of a blank control.
- **Nothing configured is never ALLOWED.** A connection with no rules, and a policy with nothing in it, both assess as `UNDER_RESTRICTIONS` with `no_policy_configured`.
- **Unreadable rules are their own answer.** A damaged document, one from a later version of the app, or one naming a rule this build has no name for reads as `Unreadable`, never as "no policy". An older build that read a newer document as fewer rules would delete the rest on the next save.
- **Facts, never prose.** The asset, the amount, the recipient and the programs come out of the transaction's bytes; the action kind comes from the structured request; the chain comes from the owner's wallet. An agent's note reaches none of it.
- **What wasn't read whole is never ALLOWED.** A transaction with an instruction nobody read withholds the verdict, with `request_unverified`, whatever rules matched the rest.
- **There is no stored verdict.** `PolicyEvaluator` re-reads the rules and the records every time, so re-evaluating before the owner proceeds is just calling it again.
- **A day is a local day**, worked out when the counters are read, from the moment the owner answered — so a status checked the next morning doesn't move yesterday's payment into today.
- **A threshold belongs to the asset it is about.** The editor keeps one asset list and hangs the per-request and per-day fields off the asset rows, so `LimitForUnlistedAsset` is impossible to write rather than an error to report.
- **SOL is typed in SOL; a token is typed in base units.** Nine decimal places is something this app knows; a mint's decimal count is not, without a transaction that carries it, and a guess three places out is a threshold out by a factor of a thousand. Every field shows the exact base-unit number it will store, as it is typed.
- **Saving nothing removes the rules.** A stored policy that configures nothing and no policy at all assess identically, so the file goes rather than being kept as a document that says nothing. A connection's rules also go when the connection does.
- **An unreadable policy is never replaced by a blank form opening over it.** The editor says what it found and offers Start over, which the owner presses on purpose.
- **The package can't act and can't speak** — the editor included. `StageBoundaryTest` reads its imports, and every one is a read: the connection ID rule, the protocol's requests and networks, what the phone read out of a transaction's bytes, the owner's own activity records, the address rule, and the app's own strings, back button, and date format. Nothing that opens a wallet, a connection, or a socket. There is no `BLOCKED` in it and no branch that acts on a verdict.

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
- App code uses `INTERNET`, and requests `CAMERA` only when the owner taps **Scan QR code**. It makes no runtime notification- or biometric-permission request. The manifest also carries `io.github.brrenat.seekervault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX Core declares for the app's own non-exported receivers. That one is private to the app, signature-level, and never shown to the user.
- **Libraries add manifest entries of their own.** CameraX brings a disabled, non-exported metadata service; AndroidX Startup and the profile installer add their components; WorkManager adds the scheduler components described above. From SAW-054, Firebase Messaging also merges its standard receiver, service, provider, and permissions. SAW-055 adds one non-exported registration service and explicit registration ownership; SAW-056 lets it accept only a fixed data invalidation; SAW-057 limits receipt to its unique-work handoff; and SAW-058 explicitly declares `POST_NOTIFICATIONS`, creates the request channel only in a configured build, and keeps tap routing inside the existing activity. Debug builds add the Compose preview and test activities.
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
| `GrpcBidiInteropTest` | The SAW-048 transport proof: the generated Connect-Kotlin 0.9.0 bidirectional client and OkHttp 5.4.0 use the real loopback h2c transport with explicit HTTP/2 prior knowledge against the Connect Node 2.2.0 adapter. Subscribe/ready and two heartbeat pairs interleave before the client half-closes its send side. Closing the receive side cancels the one Node stream and starts no replacement. The production-listener tests separately cover TLS/ALPN. This is a test harness, not the production transport added by SAW-049. |
| `Stage52AcceptanceTest` | The SAW-053 joined acceptance path: real sidecar processes, real MCP SDK calls, the production h2c gRPC service, and production Android synchronization/lifecycle/storage. It covers foreground changes without Refresh, two-sidecar isolation, restart/expiry, intentional background closure, worker-only unary recovery, persisted cache reload, bounded retry, existing-result delivery, and cleanup. Run it with the sidecar update suites through `pnpm test:updates`. |
| `Stage53AcceptanceTest` | The SAW-059 joined push-recovery path: real MCP requests enter two real sidecar processes and Android's production h2c transport, synchronization repository, persistent cache, and headless runners fetch authoritative state. It covers connection-scoped registration/rotation calls, delayed cancel-before-delivery, dropped hints recovered by the Stage 5.2 periodic path, duplicate idempotence, process-reloaded recovery, revocation isolation, and secret-free process logs. `pnpm test:push` combines it with the configured-sidecar invalidation, callback, permission, notification, tap, and stage-boundary suites. Firebase and OS delivery remain physical-device-only. |
| `LiveProtocolFixturesTest`, `LiveCommandDeadlineTest` | Protocol fixtures and deadline boundaries (SAW-002) |
| `RequestProtocolFixturesTest` | The durable request fixtures (SAW-009). Each message is built in Kotlin and must match buf's bytes in both directions. The cases cover exact message text, amounts as strings, and the uint32 and uint64 maximums, which Kotlin reads as a signed `Int` and `Long`. |
| `PairingCodeTest` | Pairing codes: the sidecar's own example, loopback HTTP in debug builds only, normalization, every malformed case, and the token kept out of `toString` |
| `CredentialVaultTest`, `ConnectionStoreTest` | Storage on Robolectric: round trips across a restart, no plaintext credential on disk, a fresh IV per write, isolation (deleting one connection keeps the other, and a credential copied under another connection's name doesn't decrypt), another key, damaged files, and file names that aren't connection IDs |
| `ConnectionRepositoryTest` | Against two fake sidecars:<ul><li>connections kept apart, a restart, and a rename</li><li>revocation, and a known server at a new address</li><li>unusable `PairResponse`s</li><li>removal that neither touches the other connection nor gives it the removed one's requests</li><li>unreachable sidecars, an unreadable credential, orphaned credentials, and a Keystore failure</li><li>secrets kept out of the log and the metadata</li></ul> |
| `ConnectionsViewModelTest` | The pairing flow (malformed codes, confirmation, a known server, a refused code, a retry), disconnect and removal, rename, and the refresh when the app opens or comes back to the foreground, but not on a rotation |
| `ConnectionsScreenTest`, `ConnectionDetailsScreenTest`, `AddConnectionRouteTest` | Compose on Robolectric: the statuses, rename errors, and every dialog; camera denial and grant through the activity result registry, and a phone without a camera; malformed codes; and no token or credential on screen |
| `ConnectionsActivityTest` | The activity with the app's own storage and a fake sidecar: pairing, rotation, rename, disconnect, and where the secrets are on disk |
| `ConnectConnectionGatewayTest`, `TwoSidecarsTest` | The real client against the real sidecar, `node sidecar/src/main.ts`. A code printed by `pnpm pair` is read by the app's parser and paired. They also cover pending requests, authenticated FCM target register/rotate/compare-clear with redacted logs, a reused code, a code for another address, revocation by the phone and by `pnpm pair revoke`, a replaced phone, a stopped sidecar, and two sidecars at once. |
| `FcmRegistrationManagerTest`, `ConnectionRepositoryTest` | Registration waits for loaded usable connections, covers initial registration, refresh/rotation, invalid callbacks, stale compare-clear, last-connection unregistration, independent multi-sidecar publication and failure, per-sidecar credential isolation, and revocation cleanup. |
| `ConnectConnectionGatewayTlsTest` | HTTPS with MockWebServer: an untrusted certificate and a certificate for another host name fail before anything is sent, and a trusted one pairs |
| `QrDecoderTest` | QR codes drawn by ZXing, decoded from luminance planes with and without row padding |
| `InboxTest`, `InboxRealSidecarTest` | The inbox against fake sidecars and the real one:<ul><li>an approved message: the approval sent first and the signature after it, both kept while the server is unreachable and sent on the next refresh, the first outcome kept, a rejection with no approval, an approval of something that isn't a message refused, an approval the wallet never answered settled as unresolved when the app opens again or comes back to the foreground, a signing still with the wallet left alone, and a signature stored while an earlier send was running still delivered</li><li>publishing another wallet takes the requests it no longer fits off the inbox, and a binding goes only to its own connection's server</li><li>a request made while the app was closed, fetched, answered, and read back by the agent</li><li>every page, and nothing answered by a fetch</li><li>one answer per request</li><li>an answer kept through an unreachable server and a restart</li><li>a lost response sent again and recognized</li><li>overlapping sends</li><li>identical request IDs on two servers</li><li>a request cancelled first, and a revoked connection</li><li>a removed connection's answers, and pruning</li><li>an older fetch that returns last, and a fetch that crosses an answer</li><li>a request ID that isn't a UUID</li><li>retention counted from when an answer settled</li><li>a fetch that finishes after its connection was removed</li><li>a reply, successful or failed, that arrives after its connection was removed, and a failure that arrives after a revocation</li></ul> |
| `PolicyTest`, `PolicyDecisionTest` | The model and the semantics (SAW-025): the default policy, an absent list against an empty one, the same mint on two networks, base units at the 64-bit maximum, every reason a policy is refused, the conjunction, one failed or unverified check putting the whole request under restrictions, coverage reported apart from the verdict, no rules being no approval, and the two verdicts there are |
| `RequestFactsTest` | Where the facts come from (SAW-026), against the real transactions in `fixtures/transactions/cases.json`: a SOL transfer read out of its own bytes, an agent's note changing nothing, the chain coming from the owner's wallet, a token whose recipient the bytes don't establish, a priority fee being a program the transaction calls, and bytes that couldn't be read establishing nothing |
| `PolicyEvaluationTest` | The assessment (SAW-026): the conjunction, thresholds to the exact base unit, a daily threshold measured against today plus this request, the unresolved half named separately, a total that saturates still reading as over, decimals shown and never compared with, an unread instruction withholding the verdict under every arrangement of rules, and one connection's rules never applied to another's request |
| `DailySpendingTest` | The counters (SAW-026): confirmed kept apart from unresolved, a rejection and a failed transaction counting nothing, one request and one signature each counting once, a day running from local midnight, a phone carried into another zone, a movement staying in the day it was answered on, two connections on one wallet and two wallets on one mint, and an unreadable record making the day unknown |
| `PolicyEvaluatorTest` | The rules and records as they stand (SAW-026): read again on every call, surviving a restart, a day ending at local midnight, each connection assessed against its own rules and its own spending, and an assessment changing nothing on disk |
| `PolicyFixturesTest` | The shared case table in `PolicyFixtures.kt`: every case's verdict, its reason codes, and the checks it doesn't cover, plus the rule that every reason a request can produce has a case |
| `PolicyDraftTest` | The editor's form (SAW-027): SOL shifted into lamports and a token left in base units, a locale's comma and a sign and an exponent all refused, the largest amount a transfer can carry and the first one past it, a threshold of zero, a daily below a per-request, a switch that is off against a list that is empty, a threshold without an asset rule, the round trip from saved policy back to form and out again, and every draft the editor can produce being fit for the store |
| `PolicyEditorViewModelTest` | The editor's state (SAW-027): rules read from disk and surviving a restart, one connection's rules never showing under another, a rotation keeping unsaved edits while leaving and returning doesn't, turning every rule off removing the file, unreadable rules refusing to be edited until the owner starts over, a draft that isn't fit to store never being written, and a save that fails keeping what was typed |
| `PolicyEditorScreenTest` | The Rules screen (SAW-027): creating, editing and cancelling, the three states of a switch said in words, amounts refused with a reason and the Save button off while one is, a token's base units, a mint and a recipient that aren't addresses, an asset or an address listed twice, whole addresses in the list, the remove button naming what it removes, the summary's uncovered checks and its wallet line, and the whole form still operable at twice the system text size |
| `PolicyActivityTest` | The editor in the real activity (SAW-027): rules written on screen landing in this connection's own file and coming back after a restart, a second connection seeing none of them, a removed connection taking its rules with it, and discarding unsaved rules leaving what is stored alone |
| `PolicyStoreTest` | The rules on disk: a full policy round trip, the default one, an empty list kept as one and an absent list kept as absent, isolation between connections and removal of one, a file naming another connection, a later document version, a rule with no name, damaged files, a limit that isn't base units, and a policy this app would refuse to write |
| `ResultStoreTest` | Stored answers: a restart, identical request IDs on two connections, damaged files, file names that aren't UUIDs, an approval and each signing outcome across a restart — including the unresolved one — and a file written before approvals existed |
| `InboxViewModelTest` | Rapid second taps, refreshing every connection, sending again, requests that aren't pending, and notification routes that fetch current state and distinguish current, gone, removed, revoked, and unavailable without creating an answer or reaching the wallet (SAW-058). It also covers reading a transfer's transaction (SAW-020), approving one (SAW-021: the approved bytes reaching the wallet even after the server rebuilt the transaction, one wallet call per approval however many taps, a stale preparation refused and read again, a version read again while the owner looked, an unverified transaction, a switched wallet, an approval the server never took, a lost callback settled as UNKNOWN with no second wallet call, a decline, and a rejection in the app), following one to the chain (SAW-022: a confirmation kept without opening a wallet or sending a second result, a chain failure kept with its reason, a check that settles nothing leaving the transfer where it was, a second tap while one runs, a server that couldn't be reached changing nothing, a settled transfer not checked again, and an UNKNOWN transfer never re-sent to the wallet), and message signing (SAW-016): no wallet call before the approval is accepted, the approval sent before the signature, a wallet that declined or couldn't sign, a signature over other bytes discarded, a request that moved on while it was reviewed, a selection that changed during the review, no wallet connected, a request for another wallet, and a rejection that never touches the wallet. The lifecycle (SAW-017): rapid taps on Approve, a signing settled as unresolved when the screen comes back without one, a wallet that never answers, a lost response sent again with the counted wallet calls still at one, and a reply that can't settle another connection's request. |
| `MessagePreviewTest` | What the owner sees of a message: text as it is, the bytes the wallet will sign, control characters and zero-width, bidirectional, and no-break characters marked where they are, a 4096-byte message shown whole, and bytes shown as hex |
| `PendingRequestsScreenTest`, `RequestDetailsScreenTest` | Compose on Robolectric: source, action, age, and expiry; the empty, no-server, and offline states; disabled buttons while sending; the stored outcome; **Send again**; expired and superseded requests; and a message to sign — the whole message with its invisible characters marked, the byte count, the signing wallet, **Approve and sign** disabled without a wallet, the wallet-changed warning, and each outcome the wallet can give |
| `InboxActivityTest` | The activity with the app's own storage and a fake sidecar: a request fetched when the app opens, answered, and still answered after a rotation; a connection's own requests; a request made while the app was in the background, fetched when it comes back but not on a rotation; a rotation while the wallet holds an approved message, after which the request is still there, the wallet has been asked exactly once, and the signature settles it (SAW-017); and the same rotation while the wallet holds an approved transaction (SAW-021) |
| `Stage2AcceptanceTest` | The Stage 2 acceptance scenario (SAW-014), with the app's own repository and storage and two real sidecars, which `RealSidecar` restarts:<ul><li>requests queued while the app is closed survive both sidecars' restarts, and complete after the app reopens</li><li>an answer given while its sidecar is down goes out after the app and the sidecar restart</li><li>a request that expires while its sidecar is down is superseded</li><li>`pnpm pair revoke` shuts out only that connection</li></ul>See [`docs/testing/stage-2.md`](../testing/stage-2.md#the-acceptance-scenario-saw-014). |
| `Base58Test` | The address encoder and decoder: the fixtures' public keys both ways, leading zero bytes, bytes above 0x7F, and characters outside the alphabet |
| `WalletStoreTest` | The wallet on Robolectric: the selection read back, the authorization encrypted and absent from the plain file, another key that can't open it, damaged and truncated files, and clearing both files together |
| `WalletRepositoryTest` | Against a fake wallet and a fake sidecar: the selection stored and published, the authorization kept off the wire and reused on the next connect, a decline that changes nothing, no wallet installed, an authorization the wallet refused, an unsupported network, a network the wallet didn't confirm, disconnecting, reading the wallet back after a restart, a selection whose authorization is gone, the connections that couldn't be told, telling only the ones that haven't heard it, and signing: the stored authorization used, nothing asked without a connected wallet or for a selection that isn't the reviewed one, an authorization the wallet refused while signing forgotten, and one it replaced while signing kept and used by the next signing and by a repository that starts from the stored files, whether the owner's message was signed or declined |
| `WalletViewModelTest` | The screen's state: the chosen network, every refusal the wallet can give, disconnecting, the connections that couldn't be told, and a connection paired later being told when the app comes back |
| `WalletScreenTest` | Compose on Robolectric: the networks and **Connect wallet**, the address and network once connected, the unconfirmed-network warning, each problem message, the unpublished connections and **Tell them again**, and the disabled controls while the wallet is busy |
| `WalletActivityTest` | The activity with the app's own storage, a fake wallet, and a fake sidecar: connecting from the Connections screen publishes the address and survives a restart, disconnecting tells the wallet and the sidecar, a connection paired afterwards is told when the app comes back, and no wallet installed is explained |
| `ActivityStoreTest` | The history on disk: every field across a restart, a token transfer on its own cluster and a message with none, the same request replaced rather than added to, a write refused over a record of something else (another wallet, cluster, or asset), a damaged file skipped, newest first, clearing, and IDs that could name a path |
| `ActivityLogTest` | What is recorded: one transfer through each step it takes, the same result three times, a restart with the confirmation arriving afterwards, a message signature that is never a payment, an acknowledgement and a rejection, an outcome nobody knows, an answer that never reached the server, clearing, a connection that is gone, and an approval the server never accepted, which is recorded as nothing at all |
| `ExplorerTest` | The cluster in the link for each network, no link for a message signature, and no link without a signature or without a cluster |
| `ActivityViewModelTest` | Reading what is stored, a history that can't be read leaving the records on screen and saying so, recovery on the next read, a link nothing could open, and clearing |
| `ActivityScreenTest`, `ActivityDetailsScreenTest` | Compose on Robolectric: the list and the empty state, the unreadable warning with the records still openable, Clear only after a confirmation, the record in full, the cluster named on every transfer, the explorer offered only for a sent transaction and on its own cluster, the words that say a message signature is not a payment, and the message when nothing can open a link |
| `StageBoundaryTest` | The stage boundary. The source manifest declares `MainActivity`, disabled-by-default Firebase auto-init, current installation-ID registration, only the non-exported messaging service, `INTERNET`, optional camera, and `POST_NOTIFICATIONS`. Firebase imports stay under `push/`; the service has registration callbacks and only the fixed invalidation-to-`PushSyncScheduler` message path, with no repository, transport, coroutine, notification, or wallet dependency in the callback. Notification code is limited to one channel, permission, generic post-Sync alerts, immutable activity intents, and a read-only route; it has no wallet, approval, scheduler, or background component. Storage and Keystore APIs stay in their named storage packages; WorkManager access stays under `sync/`; foreground services, other services, alarms, receivers, notification actions, and wallet-key APIs remain absent. Nothing is backed up. The Mobile Wallet Adapter client, WorkManager, and Firebase Messaging are on the classpath on purpose; Seed Vault's own SDK, AndroidX Security crypto, and legacy GCM are not. |

Robolectric 4.16 runs the UI tests on SDK 36 (`src/test/resources/robolectric.properties`), its newest supported SDK. The app itself targets SDK 37.

### On a device or emulator

The Stage 5.2 foreground/background run is physical-Seeker-only; an emulator does not count as PASS. Follow the [MacBook-to-Seeker live/background runbook](../guides/live-background-updates.md) and record every item in the [Stage 5.2 device checklist](../testing/stage-5-2.md#physical-seeker-checklist-saw-053) as PASS, FAIL, or NOT RUN.

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
