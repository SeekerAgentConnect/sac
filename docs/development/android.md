# Android

The app opens on **Home**, with the connected wallet, requests waiting for the owner, Global rules, and direct or feed connections. **Home**, **Requests**, **Wallet**, and **Activity** are persistent root destinations; request, connection, policy, and record details open as a bottom-sheet stack over the selected root. **Add connection** pairs with a sidecar or adds a public feed, and the retained **Live test** route opens the Stage 1 diagnostic. That screen connects to the sidecar's `LiveCommandService` ([`docs/protocol.md`](../protocol.md)), shows an agent's text, and sends the user's OK back.

## Material 3 v4 presentation (SEE-64)

SEE-64 changes presentation and navigation structure only. Request preparation and answering, transaction inspection, policy evaluation and storage, wallet hand-off, connection pairing, Activity storage, and all of their security boundaries are unchanged.

- `SeekerTheme` follows the system light/dark setting and installs the exact opaque colour, type, radius, and spacing tokens from the finalized design export. Dark primary is `#E7FC6E`, dark primary container is `#C2E60F`, light accent text uses the readable `#4F5C00` role, and orange remains advisory rather than destructive. Dynamic/wallpaper colour and parameterless Material schemes are not used.
- `SeekerComponents.kt` owns the shared solid Material surfaces: buttons, cards, network chips, bottom navigation, sheets, dialogs, and transient messages. Every colour is fully opaque. Elevation shadows, translucent scrims, alpha fades, gradients, and blur-behind are deliberately absent.
- Home keeps its 64 dp app bar outside the scrolling body. After 48 dp of body scroll it replaces the product title with the shortened wallet address and network chip. Waiting requests are whole-card actions in a horizontally snapping 204 by 192 dp carousel. Its fixed content inset and index-aware snap position put the first card on the left content edge, each interior active card at the viewport centre, and the last card on the right content edge. The active treatment follows the stable card key closest to its own snap position, so prepending new records preserves both the visible card and its scroll offset. Newly prepended off-screen cards are counted by an overlaid `N new` marker until each becomes visible. Answers remain on Request details.
- The selected root remains mounted beneath a detail sheet; the active sheet covers the bottom bar, as in the reference. Each deeper detail adds an opaque 12 dp recessed backplate and becomes the active sheet. Close removes only that layer. Sheet entry is 260 ms and exit is 240 ms.
- Identifiers use the theme's monospace style, controls use Material icons, cards are separated by solid containers rather than divider lines, and actions use the approved v4 semantic mappings.
- Home and Pending requests reserve the bottom-navigation height plus the system navigation inset in their scroll content, so their final actions and cards can move completely above navigation.
- Transfer review separates the owner's approval, verification performed on this device, and the Solana network outcome. Human-readable amount, recipient, wallet, network, fee, and known program names lead; addresses, program IDs, blockhash, instruction count, base units, and the raw transaction ID remain available in collapsed technical details. Sent, confirming, confirmed, and failed are distinct states, and a transaction ID can be copied without implying confirmation.
- Global rules uses the reference sheet's complete, expandable short explanation; a separate defaults caption; Global provenance chips; section icons; smoothly animated solid switches; concise on/off/empty status copy; and individual action, asset, recipient, and program cards. An empty document has an explicit warning and empty rows instead of orphaned prose. Clear all remains an unsaved, reversible edit, while a failed write remains visible in the pinned footer until the owner edits or retries. The footer uses tonal Discard plus filled Save, and the rules model and persistence path are unchanged (SEE-74). The policy UI uses Material roles plus the v4 `primaryText` semantic token where the pale light primary would not be readable on the ground.

The checked-in [design and flow references](../design/README.md) govern this presentation. The original implementation record is in [`docs/testing/see-64.md`](../testing/see-64.md), and the regression repair is in [`docs/testing/see-82.md`](../testing/see-82.md).

## Design-system module (SEE-114)

Android is now a two-module build. `:app` owns behavior and depends on `:designsystem`;
`:designsystem` owns only visual tokens and Compose theme code and has no dependency in the other
direction. Its only library dependency is Compose Material 3, so it cannot import app models,
ViewModels, storage, transports, network clients, or wallet code. The module layout and the exact
font provenance are documented in [`android/designsystem/README.md`](../../android/designsystem/README.md).

`SeekerTheme` supplies the light/dark colour roles, the source-chip palette, the full extracted
spacing and radius scales, and the styles that do not fit Material's standard slots. Roboto and
Roboto Mono are bundled under `res/font`, so Chrome references and Android use the same typefaces.
Every text style has an explicit CSS-derived line height and zero letter spacing, disables Android
font padding, and uses a fixed, centred, untrimmed line box.

The wrapper also closes Material escape hatches: `surfaceTint` is transparent, tonal elevation is
disabled, every Material shape is supplied from the extracted radius scale, ripple colour is the
theme's readable accent, and `LocalMinimumInteractiveComponentSize` is unspecified so the design's
32 dp chips and compact buttons do not silently grow. Components remain responsible for their
explicit hit targets and accessibility semantics.

The `checkDesignSystemLiterals` Gradle task scans production Kotlin in both modules, excluding only
the design-system theme package. A raw `Color(0x…)`, `.dp`, or `.sp` literal fails with its file and
line. `./gradlew check`, `spotlessCheck`, and `pnpm check:android` all run it; this is the permanent
SEE-82 regression guard, not a one-time migration script.

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

Headless work admits at most four sidecars at once. Each connection has a ten-minute overall bound around the transport's existing 30-second per-RPC deadline, so a maximum valid size-trimmed queue has time to page while one offline or pathological server still cannot run indefinitely. An unreachable connection makes the WorkManager run retry with exponential backoff. Authentication/revocation removes the credential and is permanent for that connection; certificate, cleartext, protocol, and malformed-response failures wait for the next normal period rather than tight-looping. Every usable connection whose foreground stream is already Live is excluded; a mixed two-sidecar run fetches only the connection that needs recovery. Any race with stream reconciliation, Refresh, or push work still reaches the repository's per-connection coordinator and safely coalesces or buffers overlap.

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
pending keys before and after the fetch. It cancels departed keys and accepts the cache's current
`ActionRequest`s only to choose human copy for the request kind; a key with no authoritative record
posts nothing. SEE-105's shared native appearance uses `ic_notification_sac` as a transparent
monochrome small icon, the existing lime launcher resource as a large icon, theme-qualified
accent colours, source text, and `BigTextStyle`. `ProposalNotificationManager` applies the same
shape to a validated `ProposalRecord`. Neither path displays free text, notes, terms, amounts,
addresses, policies, prepared bytes, answers, or raw enum/operation identifiers.

Each key still has its own immutable explicit `PendingIntent` to `MainActivity`, differentiated by
an app-local URI so one request cannot replace another's tap. The route validates both UUIDs,
fetches the named paired connection again, and hides review controls while loading or when the
current state is gone, removed, revoked, or unavailable. A request this phone already answered
opens its existing result. The tap chooses no answer and calls no wallet method; the shared Sync may
retry only an answer the owner already stored.

Foreground streams, manual Refresh, unary reconciliation, and the periodic worker remain
independent and authoritative when permission is denied or a hint is delayed, dropped, expired,
throttled, or unavailable. Setup and exact delivery/off behavior are in the [optional Firebase
guide](../guides/firebase.md); automated evidence is in [Stage 5.3
verification](../testing/stage-5-3.md).

## Feed hints, on the same pipeline (SEE-92)

Stage 7.1's second kind of server gets the same treatment and nothing new is declared. The one
`SeekerVaultMessagingService` now matches a second exact, content-free payload —
`kind=feed_invalidation`, `version=1` — and does with it what it does with the first: enqueue one
unique WorkManager job with an **empty input**. Nothing in a payload is read for anything else, and
a message with an extra field is ignored rather than partly trusted.

`FeedSyncRunner` is `PushSyncRunner` for feeds. It loads what the phone holds from disk (a process
woken by a hint holds nothing in memory), reads every feed whose gateway is not already streaming to
a foreground listener, and passes each read the boundary that feed was last read at so an unchanged
feed costs one small answer. A feed it could not read leaves nothing remembered and makes the job
retry. Because the read covers every feed, two hints are one read and a hint that Firebase replaced
under its collapse key loses nothing.

`FeedTopicManager` owns which topics this phone asked for, on the same serialized channel
`FcmRegistrationManager` uses. It derives its intent from the connection list rather than storing a
registry — so a feed added is subscribed and a feed removed is unsubscribed, and a registration
refresh says everything again, because topic membership belongs to the installation. It asks the
gateway for each topic's name (`FeedService.GetFeedTopics`) instead of deriving one, and a gateway
that relays nothing answers `Unimplemented`, which is recorded and not retried. The one case a
derived intent cannot cover is a feed removed while the app was not running; the hint that arrives
on that topic is what unsubscribes it.

`ProposalNotificationManager` is `RequestNotificationManager` for a proposal: its own channel (at
default importance, because a proposal is an offer to everyone subscribed rather than one server
waiting for this owner), its own tags so neither can cancel the other's alerts, and the same
before/after comparison — of the proposals that are *reviewable*, which is what makes a withdrawal,
an expiry, a dismissal here and an operation already begun all remove an alert without any of that
being restated. The tap route opens the feed the proposal is on, which is the deepest current review
state this build has, and carries the proposal's own validated ID for the screen SEE-93 and SEE-94
will bring. It chooses nothing, prepares nothing and calls no wallet method.

## Connections

The owner's walkthrough is [`docs/guides/pairing.md`](../guides/pairing.md), and the security model, including what the phone stores, is [`docs/security.md`](../security.md#local-storage-and-recovery).

| Screen | What it shows and does |
| --- | --- |
| **Connections** | The app's first screen. Its first row is **Wallet**, then **Pending requests**, then direct and feed connections with their name, origin and status. **Add connection** pairs or adds one, and **Live test** opens the Stage 1 screen. |
| **Add connection** | **Scan QR code** asks for camera permission, then scans with the back camera. A pairing code or `seekervault://feed` reference can also be typed or pasted. They route exclusively and malformed input gets scheme-specific reasons. An Android `seekervault://pair` link from a CLI, bot or operator enters this same confirmation flow and performs no exchange until **Pair**. Pairing confirms the server URL and ID before credential exchange. Feed onboarding confirms the gateway origin, server ID and public/no-credential boundary before `addFeed`; Added opens its details, Already writes nothing, and only a transient check failure offers Retry. There is no feed deep link or intent filter. An old `seekervault://invite` or hosted `/invite/` URL is recognized only to explain that the private gateway was retired; it performs no network call. |
| **Connection details** | Active direct/feed records show their normal status and controls. A retired gateway-private record is visibly inert, offers removal and a blank fresh-direct-pairing route, and never derives a direct endpoint or credential from the old record. |

The code is in `connections/`:

| File | Role |
| --- | --- |
| `PairingCode.kt` | Reads `seekervault://pair` codes by the sidecar's rules. Plain HTTP is accepted only where the platform's network security policy permits cleartext: loopback, in debug builds. |
| `ConnectConnectionGateway.kt` | `Pair`, legacy `ListPending`, authenticated FCM target updates, and `RevokeConnection` over Connect-Kotlin and OkHttp, with the platform's certificate and host name checks. It classifies errors for the screens, including a certificate failure that OkHttp suppressed behind another address's failure. |
| `ConnectionRepository.kt` | Pairs, refreshes, renames, disconnects, and removes. Refresh delegates to the shared update synchronizer and retains `ListPending` for old or unconfigured sidecars. It checks each `PairResponse`, sends each credential only to its own URL, counts only the connection's own requests, and deletes a credential the sidecar rejects. |
| `storage/ConnectionStore.kt`, `storage/CredentialVault.kt`, `storage/AndroidKeystoreKey.kt` | The app's only connection storage: one JSON file per connection in `filesDir/connections/`, and credentials encrypted under a Keystore key in `noBackupFilesDir/credentials/`. Version 5 rewrites old `gateway_private` files to a no-mode `gateway_private_removed` record, preserving label/identity/origin/times while dropping the credential-bearing/cached executable state. Versions 1–4 Direct and Feed records retain their modes and data. Repository startup then deletes the obsolete credential and restart-safely settles or removes unfinished private work without changing local Activity history. |
| `FeedGateway.kt` | The one way a publisher's feed is resolved: through the shared gateway, never from the publisher. This build carries no implementation (SEE-88, SEE-90). |
| `ConnectionsViewModel.kt` | The screens' state: exclusive pairing/feed parsing, both pre-write confirmations, every feed add/reference outcome, refreshes, dialogs, and messages. Input stays in memory, never in saved state. |
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
| `WalletAdapter.kt` | The boundary: `connect(network, authToken)`, `disconnect(wallet, authToken)`, `signMessage(message, wallet, authToken)` (SAW-016), `signAndSendTransaction(transaction, wallet, authToken)` (SAW-021), and the outcomes (connected or signed or sent, no wallet, declined, authorization expired, network unsupported, failed, unknown). Both wallet requests answer with the outcome **and** the authorization the wallet reported while answering — a `SigningAnswer` and a `SendingAnswer`, each redacting the token in `toString` (SEE-84). |
| `WalletClient.kt` | The narrow, injectable session boundary (SEE-84), in this app's own types: `WalletSessionClient` (a session on one network, with the authorization it offers, `connect`, `transact`, `close`), `WalletRequests` (`signMessages`, `signAndSend`), `WalletAuthorization` (the token and the accounts the wallet reported, redacting the token), `WalletError`, and `WalletOutcome` (answered, no wallet, no activity, failed). Nothing here names Mobile Wallet Adapter, so a test drives the adapter with `FakeWalletClient`. |
| `MwaWalletAdapter.kt` | The only file that imports the Mobile Wallet Adapter client. It holds one `WalletSessionClient` per wallet session — `MwaSession`, in this file — and reuses it across connecting, signing and sending, so the client's learned wallet endpoint survives the session; it drops it on disconnect, on an authorization the wallet refused, and on a network change. The session connects through the activity's `ActivityResultSender`, waiting briefly for the next screen's while a rotation replaces one (SAW-017). Inside the session the adapter reads the wallet's own reauthorization first, checks it names the account the owner reviewed and doesn't contradict its network, and only then signs with `signMessagesDetached` or sends with `signAndSendTransactions` (SEE-84). It keeps the replaced authorization for every outcome, and maps the wallet's errors: `AUTHORIZATION_FAILED` is a refusal when the phone offered no authorization and an expiry when it did, `NOT_SIGNED` is a refusal, `CLUSTER_NOT_SUPPORTED` is the network, and an error with no code of the wallet's own is a failure before the transaction reached the wallet and an unknown outcome after it. |
| `Base58.kt` | Writes an address the way the sidecar's `requests/action.ts` does, and reads one back for the wallet, which takes an account as its raw key bytes |
| `storage/WalletStore.kt` | One `StoredSession` — the selection and the wallet's authorization for that account — as a versioned JSON record, AES-256-GCM under the Keystore key with its own associated data, in `noBackupFilesDir/wallet/wallet-session` (SEE-84). It migrates the `filesDir/wallet/wallet.json` + `noBackupFilesDir/wallet/wallet-authorization` pair an older build wrote, and refuses half of one. |
| `WalletRepository.kt` | Connects, keeps, and disconnects the wallet, and publishes the binding to each usable connection. It reads the stored record whole and refuses one whose account and network aren't the selection in hand, drops one the wallet refused, stores one the wallet replaced without touching the selection — for a transfer as for a message — and tracks which connections have already been told, so a connection paired later is told on the next publication. `sign` and `signAndSend` ask the wallet only for the selection the owner reviewed, and forget the wallet when it reports that its own authorization no longer names that account. |
| `WalletViewModel.kt`, `WalletScreen.kt`, `WalletText.kt` | The screen's state, the stateless screen, and its texts |

The approval and the message itself live with the inbox: `connections/SignMessage.kt` holds the exact message bytes and the approval's SHA-256, and `inbox/InboxText.kt` the preview that makes invisible characters visible.

- **MWA runs the wallet from an Activity.** `MainActivity` registers an `ActivityResultSender` in `onCreate` and clears it in `onDestroy`, and `SeekerVaultApplication` hands it to `MwaWalletAdapter`. There is no dedicated wallet activity and no foreground service.
- **The authorization never leaves the phone.** It goes to the wallet and to `WalletStore`, and nowhere else. `WalletRepositoryTest` and `WalletActivityTest` assert that it reaches no server.
- **Publishing is idempotent and retried.** `ConnectionRepository.publishWallet` sends the binding to one connection, marks the connection revoked on `UNAUTHENTICATED`, and takes the requests the sidecar cancelled off the inbox. Opening the app again re-sends what a connection hasn't been told yet.
- **The network is the owner's explicit choice,** and it's fixed while a wallet is connected. If the wallet lists chains for the account and the chosen one isn't among them, the screen says the wallet didn't confirm it rather than pretending it did.

### Wallet capabilities, and where an SDK boundary would fall (SEE-84)

Mobile Wallet Adapter can say what a wallet supports — `getCapabilities` reports the transaction
versions it takes, how many payloads one request may carry, and whether it signs and sends at all.
This app asks for none of it, and that is on purpose:

- **A capability is not a permission to use it.** The transactions this app puts in front of a
  wallet are the ones a sidecar built and this phone read byte for byte (`transactions/`, SAW-020).
  Enabling a format because a wallet accepts it would put bytes in front of the owner that the
  inspection can't account for, which is the one thing the review exists to prevent. Anything new is
  the intersection of what a wallet supports **and** what this app can inspect and show, and the
  second half is code, not a flag.
- **If it is exposed, it is exposed in this app's own types.** A `WalletCapabilities` of our own
  next to `WalletAuthorization` — supported transaction versions, the request limits, whether
  sign-and-send exists — read once per session through `WalletRequests` and never handed on raw. No
  screen, policy, or sidecar ever reads a Mobile Wallet Adapter type.
- **One transaction per request stays.** Every limit this app could hit is one it already respects:
  it asks for one message or one transaction, and treats anything but one answer as an outcome it
  can't match to what it asked.

The reusable wallet layer a later stage would extract is `Wallet.kt`, `WalletAdapter.kt`,
`WalletClient.kt`, `MwaWalletAdapter.kt`, `Base58.kt`, `Ed25519.kt`, and `storage/WalletStore.kt`.
The split a follow-up would make, in this order:

1. **The wallet API** — the types and the adapter boundary, with no Android Mobile Wallet Adapter in
   it. `WalletClient.kt` is already that shape.
2. **The Android MWA integration** — `MwaWalletAdapter.kt` and its session, the one place that knows
   the library and the activity.
3. **The server request and approval workflow** — `connections/`, `inbox/`, `policy/`: what a
   request is, who approved it, and what the sidecar is owed.
4. **The app's UI** — the screens over all three.

`WalletRepository.kt` sits across 1 and 3 today: it publishes the binding to sidecars, so it holds
`ConnectionRepository` and the generated protocol types. Extraction means splitting it, not moving
it, and the reusable half must need neither. Packaging and publishing an SDK is out of scope here
(SEE-102); this is the boundary a later stage would cut along.

## Client plugins (SEE-86)

The architecture page is [`docs/wiki/client-plugins.md`](../wiki/client-plugins.md); this is the Android-side summary.

`plugins/` is one boundary where a bundled client plugin can be registered, so the Stage 7.1 Jupiter plugins (SEE-93, SEE-94) can be written without changing `connections/`, `sync/`, `live/`, `push/`, `policy/`, `transactions/`, `wallet/`, or any storage package. It is data and pure functions: no coroutine scope, no store, no transport, no Compose.

- **`ActionPlugin`** has three behaviours and no others. `parameters(subject)` describes the fields the operation leaves to the owner, as a typed form rather than a screen — the app owns its own presentation. `prepare(subject, choice)` fetches whatever the operation needs to be executable now and returns the exact bytes. `inspect(subject, choice, prepared)` reads those bytes and returns typed facts — it is given core's own copy of the owner's choice rather than trusting what it prepared against.
- **`ActionSubject` is the complete list of what a plugin receives:** the connection ID, the operation, the environment, the structured request, and the owner's `SelectedWallet`. `SelectedWallet` is an address, a network, a label and a timestamp — the wallet's authorization token lives in `wallet/storage/WalletStore` and is not part of it (SEE-84). Nothing in the subject can reach a sidecar, approve anything, or sign.
- **`SeekerVaultApplication.plugins` is the build-time selection**, and it is where SEE-93 and SEE-94 added their plugins: it composes `PluginRegistry.of(JupiterSwapPlugin(…), JupiterPredictionPlugin(…))`. Making it settable is how tests register a plugin; `MainActivity` passes the composed registry to `InboxViewModel` without naming the package.
- **`InboxViewModel.factsFor` is the only place it is consulted.** `actionOwner(request)` separates the actions the app carries out itself — ack, sign_message, transfer — from an operation a plugin would serve. The first path is unchanged. The second resolves against the registry, and an unresolved operation produces `RequestFacts.unread`, so it can never be `ALLOWED`.
- **Two `StageBoundaryTest` checks hold the line.** One reads the package's imports against an exact list and fails if its code names a wallet interaction, a wallet token, a transport, an HTTP client, a store, or an approval. The other fails if a provider's name appears in core transport, policy, transaction or activity code, or if a file other than `SeekerVaultApplication.kt` and `InboxViewModel.kt` imports the package. Deliberately breaking either fails the named check.

Writing a plugin, when a stage calls for one: implement `ActionPlugin`, add it to the registry `SeekerVaultApplication.plugins` composes, and put its strings in resources — a `ParameterField` carries a `@StringRes` label rather than English. Nothing else in the app should need to change; if it does, the boundary is in the wrong place.

## Server manifests and connection modes (SEE-88)

The architecture page is [`docs/wiki/server-manifests.md`](../wiki/server-manifests.md); this is the
Android-side summary.

`servers/` is data and pure functions, like `plugins/`: the validated `ServerManifest` model,
`manifestFrom` with one `ManifestProblem` per rule, `FeedReferences` for `seekervault://feed`
references, and `serverSupport`, which matches a manifest's requirements against the plugins
compiled into this build. It holds no state, opens nothing, and does not suspend.

- **The mode is stored per connection.** `Connection.mode` and `Connection.server` — `Unknown`,
  `Legacy`, `Known(manifest)` or `Refused(problem)` — are written by `ConnectionStore`, now at
  version 3, which still reads a version 1 file as a direct connection whose server hasn't been
  asked. Two invariants hold the record together: a feed always has a validated manifest, and a
  manifest a connection holds always agrees with its mode.
- **So is the environment (SEE-97).** `Connection.environment` is which promise the connection
  keeps: the third invariant is that a direct connection is always production, stated where a
  connection is built rather than checked where one is used. A feed starts in sandbox whenever its
  publisher serves one, `ConnectionRepository.setEnvironment` is the only way it moves and refuses
  an environment the server does not serve, and the four places that used to default to production
  — `ConnectionsViewModel`, `InboxViewModel`, `ProposalRepository`, `OperationViewModel` — now read
  it off the connection instead ([`docs/wiki/environments.md`](../wiki/environments.md)).
- **`Connection.usable` carries the mode.** It already meant "the phone can still call this
  connection's sidecar" and is the condition on refresh, synchronization, push registration, wallet
  publication and every approval path, so requiring the direct mode there keeps a feed out of all
  of them at once.
- **Support is derived on every read, never stored.** `ConnectionsViewModel` computes it for each
  connection from the process's one `PluginRegistry` and that connection's own environment, and
  `InboxViewModel.support(connectionId)` does the same for a request. A verdict on disk would
  outlive the build that reached it.
- **`ConnectionRepository.resolveManifest` reads and caches it.** It runs after pairing and on every
  refresh, and it is where the cache rules live: an unchanged revision keeps what is held, a higher
  one replaces it, and a stale revision, a changed identity, a changed origin, a changed mode or
  content that changed without its revision are recorded as refusals. A server that couldn't be
  reached leaves the record alone.
- **`addFeed` resolves through `FeedGateway` and nothing else.** SEE-91's `feeds/ConnectFeedGateway`
  is the implementation behind it, so a real manifest is resolved from a real gateway; `NoGateway` is
  what is answered where no gateway is wired at all. No screen calls `addFeed` yet, and there is no
  deep link for `seekervault://feed`, so in a shipped build a feed arrives only from a test. The
  publisher's own server is never contacted, and no credential is created for a feed.

What the owner sees, in the approved design's own components and with no new screen:

| Where | What it says |
| --- | --- |
| Connections, and Connection details | A feed follows its application-scoped gateway listener state: live, reconnecting, unreachable, refused, or available without a stream. Its intentional lack of a credential is never disconnection, and details no longer lead with a prominent notice about that implementation fact. Home derives the feed's `N pending` from its current open `ProposalRecord`s, so refresh, stream delivery, withdrawal, expiry, dismissal, and lifecycle recomposition use the same pending set as the inbox. A server this build can't act for says which part is missing: a plugin, a plugin's version, the protocol, the environment, or a manifest that was refused. A feed also says which promise it keeps — sandbox or production — and offers the switch where its publisher serves both (SEE-97). |
| Request details | A request from a server this build doesn't support is shown in full and can be rejected. The affirmative answer is not offered, nothing is prepared, and no wallet is opened — with one line saying why, and no tick to overrule it. |

## Shared proposals (SEE-89)

The architecture page is [`docs/wiki/shared-proposals.md`](../wiki/shared-proposals.md); this is the
Android-side summary.

`proposals/` is data and pure functions, like `plugins/` and `servers/`: the validated `Proposal`
model keyed by `ProposalKey` (publisher, channel, proposal), `proposalFrom` with one
`ProposalProblem` per rule, `ProposalRecord` with the publisher's half and this device's half kept
apart, the derived `proposalAvailability` and `proposalStanding`, and `bindingProblem` — the one gate
between a review and the wallet. It holds no state, opens nothing, and does not suspend.

- **`ProposalStore` keeps one JSON file per proposal** at `filesDir/proposals/<connection
  ID>/<proposal ID>.json`: the publisher's document and this device's decisions, written together.
  One directory per feed, so removing the feed removes its proposals — and every rule the model
  holds itself to is applied again to what comes off the disk.
- **`ProposalRepository.apply` is the one path in,** and it is idempotent: the same revision with the
  same terms writes nothing, an older one is refused, a higher one replaces the publisher's half and
  leaves the device's decisions where they were, and the same revision with different terms is
  recorded as a contradiction that stops further execution until a higher revision arrives.
- **`beginExecution` writes the binding before the wallet opens,** under the lock that reads it, so a
  second tap answers `AlreadyExecuted`. There is one execution per proposal identity, ever, and
  `load()` settles one the app closed on as *unresolved* rather than as a failure.
- **`refresh` goes through `ProposalFeed` and nothing else** — `ConnectFeedGateway` (SEE-91). A build
  wired without one answers `NoFeed` rather than pretending.
- **A dismissal is final for the proposal's identity,** so a republished revision cannot put a
  dismissed proposal back in front of the owner.
- **Nothing on this side goes out.** A feed is excluded from `PublishWallet`, `PrepareRequest`,
  `SubmitResult` and generic sync by `Connection.usable` (SEE-88), so there is no code path that
  could upload a choice, an approval or an outcome.

Activity gained one kind for it: `ActivityKind.Operation` with a `ReviewedOperation` — the
operation and plugin as codes, the proposal revision, the wallet and cluster, the preparation
version, and the parameters the owner chose. The outcomes are the transfer path's own (`Sent` is not
paid, and an answer nobody received is `Unknown`), its signature is a transaction's ID, and its
record outlives the feed being removed.

## The Jupiter swap plugin, and the path to a wallet (SEE-93)

The architecture pages are [`docs/wiki/jupiter-swap.md`](../wiki/jupiter-swap.md) and
[`docs/integrations/jupiter.md`](../integrations/jupiter.md). Android-side, it is two packages.

**`jupiter/` is the plugin.** It implements the boundary and reaches one host of its own:

| File | What is in it |
| --- | --- |
| `SwapTerms.kt` | The payload a publisher broadcasts, with one `SwapTermProblem` per rule. Mints, never tickers |
| `SwapParameters.kt` | The half that is the owner's: the amount and the slippage, and the publisher's bounds on both |
| `JupiterProvider.kt` | The two calls, over the app's shared OkHttp client and `org.json`. The only file in the app that names the provider's host |
| `SwapInstructions.kt` | The aggregator's two routing instructions, both account layouts, the trailing Borsh arguments, and the two token instructions the transfer path does not read |
| `SwapInspection.kt` | `inspectSwap`: every check, one `SwapFinding` each, and the labelled values the owner is shown |
| `JupiterSwapPlugin.kt` | The descriptor, `parameters`, `prepare`, `inspect`, and the offer it holds against the bytes it prepared |

It calls `decodeTransaction` rather than having a decoder of its own — `StageBoundaryTest` fails if
any file outside `transactions/` reads the message format itself — and it imports nothing that could
sign, store or approve.

**`operations/` is the path a person walks**, and it names no provider, so SEE-94's plugin is shown
by the same screens:

- `OperationViewModel` holds one review at a time and serializes preparing against approving. The
  order is the whole of the safety: preparing writes the owner's choice down and asks the plugin;
  approving re-reads the rules and compares the verdict with the one they were shown, **then** takes
  the wallet lock, and only inside it binds the operation and writes it down — which is where expiry,
  the revision, the choice, the plugin and the wallet are all checked at once (SAW-046's rule for a
  transfer's window, applied here).
- It takes the connection *list* rather than `ConnectionRepository`, because a feed has no
  server-facing half: no pairing, no credential, no outbox.
- `ProposalsScreen` and `ProposalReviewScreen` are the shapes Pending requests and Request details
  already established, and they reuse `PolicyReview` unchanged. A feed connection's details offer
  **Signals** where a direct connection offers Pending requests.
- Changing any parameter throws away what was prepared for the old one, and a revision that moved
  under an open review does the same.

The plugin is selected where the app is composed (`SeekerVaultApplication.plugins`), because a real
plugin needs an HTTP client and `plugins/` holds none.

## The Jupiter prediction plugin, and the chain read it needs (SEE-94)

The architecture page is [`docs/wiki/jupiter-prediction.md`](../wiki/jupiter-prediction.md).
Android-side it adds one package and four files to another.

**`solana/` is the shared component**, and it names no provider so both plugins may use it:

| File | What is in it |
| --- | --- |
| `SolanaAccounts.kt` | One read method, `getMultipleAccounts`, over the app's shared client. The app's first and only chain endpoint, configured by the build (`-Pseekervault.solanaRpc=…`) and **empty by default** |
| `AddressLookupTables.kt` | Parsing and validating a table account, and rebuilding a versioned message's account list in the runtime's own order — static, then every table's writable indexes, then every table's readonly ones |

**`jupiter/` gains the plugin**: `PredictionTerms.kt` (the market payload), `PredictionParameters.kt`
(the side and the stake), `JupiterPrediction.kt` (the market and the order), `PredictionInstructions.kt`
(the order instruction's Borsh layout, with the funding swap delegated to `SwapInstructions`), and
`PredictionInspection.kt` with `JupiterPredictionPlugin.kt`.

Three things about the shape of it are worth knowing before changing any of it:

- **`inspect` is still not suspending.** Resolving reads the chain, so it happens in `prepare` — where
  a plugin may reach a network — and `inspect` returns what that reading found, keyed by the exact
  bytes it was made for. The boundary keeps its promise that an inspection reads bytes and not a
  network, and the ViewModel calls the same two methods it calls for a swap.
- **A resolution failure is a preparation failure.** `inspectPrediction` lets `SolanaException` and
  `LookupException` propagate, and the plugin turns each into a `PluginFailure` with its own reason.
  There is deliberately no path that produces a review of an order whose accounts were never seen.
- **The readers are account-list agnostic.** `transactions.readInstruction` and `jupiter.swapStep`
  take a program, a list of accounts and the data, so the same code reads a self-contained message
  and a resolved one. That is what lets an order's funding swap be read by exactly the code that
  reads a swap.

The plugin also implements `destinations`, which is where the market link comes from, and returns
`references` from its inspection — the order and position accounts — which `operations/` copies into
the Activity record through `ActivityLog.referenced`, the same channel the policy snapshot uses.

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
| `PolicyEditorScreen.kt` | The Rules screen: Material 3 switches, checkboxes, radio buttons, chips, text fields, lists, Save and Cancel under the approved v4 theme |
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

## Debug component gallery

Debug APKs expose every design-system preview fixture on a device. Long-press the app icon, choose
**Component gallery**, then select a component/variant. The list and its activity are compiled only
from `src/debug`; they are not destinations in the production graph and do not exist in the release
APK. See the [component gallery guide](../wiki/component-gallery.md) and the
[Stage 7.2 acceptance record](../testing/stage-7-2.md).

Roborazzi baselines are source-controlled under each Android module's
`src/test/snapshots/images/`. Record only after reviewing the design references:

```bash
android/gradlew -p android :designsystem:recordRoborazziDebug :app:recordRoborazziDebug
android/gradlew -p android designCompare
```

Ordinary verification, including `pnpm check:android` and CI, runs both
`verifyRoborazziDebug` tasks. It never records over a changed baseline.

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
| `ConnectLiveCommandTransportTest` | The real transport against the real sidecar (`node mcp-server/src/cli.ts start`), with an MCP SDK client as the agent: text in, the same command's OK out. Also covers a wrong token, an unknown command, and a sidecar stop. It needs Node 24 and `pnpm install`. `ConnectLiveCommandTransportUnreachableTest` covers a closed port. |
| `GrpcBidiInteropTest` | The SAW-048 transport proof: the generated Connect-Kotlin 0.9.0 bidirectional client and OkHttp 5.4.0 use the real loopback h2c transport with explicit HTTP/2 prior knowledge against the Connect Node 2.2.0 adapter. Subscribe/ready and two heartbeat pairs interleave before the client half-closes its send side. Closing the receive side cancels the one Node stream and starts no replacement. The production-listener tests separately cover TLS/ALPN. This is a test harness, not the production transport added by SAW-049. |
| `Stage52AcceptanceTest` | The SAW-053 joined acceptance path: real sidecar processes, real MCP SDK calls, the production h2c gRPC service, and production Android synchronization/lifecycle/storage. It covers foreground changes without Refresh, two-sidecar isolation, restart/expiry, intentional background closure, worker-only unary recovery, persisted cache reload, bounded retry, existing-result delivery, and cleanup. Run it with the sidecar update suites through `pnpm test:updates`. |
| `Stage53AcceptanceTest` | The SAW-059 joined push-recovery path: real MCP requests enter two real sidecar processes and Android's production h2c transport, synchronization repository, persistent cache, and headless runners fetch authoritative state. It covers connection-scoped registration/rotation calls, delayed cancel-before-delivery, dropped hints recovered by the Stage 5.2 periodic path, duplicate idempotence, process-reloaded recovery, revocation isolation, and secret-free process logs. `pnpm test:push` combines it with the configured-sidecar invalidation, callback, permission, branded native notification, tap, and stage-boundary suites. Firebase and OS delivery remain physical-device-only. |
| `LiveProtocolFixturesTest`, `LiveCommandDeadlineTest` | Protocol fixtures and deadline boundaries (SAW-002) |
| `RequestProtocolFixturesTest` | The durable request fixtures (SAW-009). Each message is built in Kotlin and must match buf's bytes in both directions. The cases cover exact message text, amounts as strings, and the uint32 and uint64 maximums, which Kotlin reads as a signed `Int` and `Long`. |
| `PairingCodeTest` | Pairing codes: the sidecar's own example, loopback HTTP in debug builds only, normalization, every malformed case, and the token kept out of `toString` |
| `CredentialVaultTest`, `ConnectionStoreTest` | Storage on Robolectric: round trips across a restart, no plaintext credential on disk, a fresh IV per write, isolation (deleting one connection keeps the other, and a credential copied under another connection's name doesn't decrypt), another key, damaged files, file names that aren't connection IDs, and (SEE-97) the promise a feed keeps surviving a restart, an older file read as production, a word this build does not know dropping the record rather than being resolved, and a direct connection staying production whatever the file says |
| `ConnectionRepositoryTest` | Against two fake sidecars:<ul><li>connections kept apart, a restart, and a rename</li><li>revocation, and a known server at a new address</li><li>unusable `PairResponse`s</li><li>removal that neither touches the other connection nor gives it the removed one's requests</li><li>unreachable sidecars, an unreadable credential, orphaned credentials, and a Keystore failure</li><li>secrets kept out of the log and the metadata</li></ul> |
| `ConnectionsViewModelTest` | The pairing flow (malformed codes, confirmation, a known server, a refused code, a retry), SEE-107's pairing/feed/neither routing, every feed reference/add/check outcome, one-call confirmation, no-call cancel and retryability, plus disconnect/removal, rename and lifecycle refresh |
| `ConnectionsScreenTest`, `ConnectionDetailsScreenTest`, `AddConnectionRouteTest` | Compose on Robolectric: the statuses, rename errors, and every dialog; camera denial and grant through the activity result registry, and a phone without a camera; pairing- and feed-specific malformed input; feed public-boundary confirmation; Added/Already/refused/failed/no-gateway copy; no token or credential on screen; required plugins; and (SEE-97) a feed saying which promise it keeps, the switch offered only where its publisher serves both, and a direct connection saying nothing about environments at all |
| `ConnectionsActivityTest` | The activity with the app's own storage and a fake sidecar: pairing, rotation, rename, disconnect, and where the secrets are on disk |
| `ConnectConnectionGatewayTest`, `TwoSidecarsTest` | The real client against the real sidecar, `node mcp-server/src/cli.ts start`. A code printed by `pnpm pair` is read by the app's parser and paired. They also cover pending requests, authenticated FCM target register/rotate/compare-clear with redacted logs, a reused code, a code for another address, revocation by the phone and by `pnpm pair revoke`, a replaced phone, a stopped sidecar, and two sidecars at once. |
| `FcmRegistrationManagerTest`, `ConnectionRepositoryTest` | Registration waits for loaded usable connections, covers initial registration, refresh/rotation, invalid callbacks, stale compare-clear, last-connection unregistration, independent bounded retry of a failed sidecar, per-sidecar credential isolation, and target plus notification cleanup on removal/revocation. |
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
| `SwapTermsTest` | What a publisher has to say for a swap to be readable (SEE-93): mints rather than tickers, every rule and the term it broke on, absent told apart from unreadable, a term this plugin does not know changing nothing, and every way the owner's own choice can fall outside what was published |
| `SwapInstructionsTest` (in `SwapInspectionTest`), `JupiterFixturesTest` | The four transactions Jupiter really built, read by the phone's own reader, and the capture script's independent reader agreeing with it instruction for instruction. The accounts the route names are the owner's own derived addresses, the floor the chain will enforce is the one the quote stated, and the wrap and unwrap touch nothing but the owner's own account |
| `SwapInspectionTest` | Everything the review refuses, one changed thing at a time: the amount, the slippage, the quoted output, the source, the destination, either mint, the authority, the payer, a second signer, a platform fee in either form, the number of hops, a second swap, bytes already signed, a transaction with no swap in it, an extra transfer, an `Approve`, an instruction from elsewhere, a routing instruction this plugin was not written for, a lookup table, wrapping into or closing to somebody else, an account created for somebody else, and no wallet or the wrong network |
| `JupiterSwapPluginTest` | The plugin over a stood-in provider: what it declares, a signal it cannot read asking for nothing and saying which term, two owners getting two transactions, every preparation being a new thing to approve, the minute a preparation stands for, preparing not reading the environment at all — the same bytes, the same calls and the same inspection in sandbox as in production (SEE-97) — each provider failure reported as itself, and an offer this phone no longer holds being said rather than assumed |
| `HttpJupiterProviderTest` | The wire, over a real HTTP endpoint: a quote that carries two mints and an amount and nothing about the owner, a build that carries the quote back whole and the account that will sign, every answer to a different question refused, a rate limit and a route failure reported as themselves, a status quoted and never a body, and one of the captured real answers going through the adapter unchanged |
| `JupiterLiveTest` | Opt-in, one real quote and one real build against the live provider, asserting the review still verifies the result. It spends nothing. Run it with `-Dseekervault.jupiter=https://lite-api.jup.ag`; it skips otherwise |
| `OperationViewModelTest` | The whole path with the real plugin: two owners acting on one signal with their own amounts, the fields the owner is asked for, a changed amount throwing away what was prepared, a stale quote prepared again rather than signed, the wallet asked once with exactly the reviewed bytes, a decline and an answer that never arrived recorded honestly, one execution per proposal ever, an operation the app closed on settled as unresolved, a provider that could not quote, bytes that do not do what was chosen never reaching the wallet, a devnet wallet refused before anything is asked, a dismissal a republication cannot undo, terms that moved under an open review, and (SEE-97) a sandbox feed rehearsing everything and opening no wallet — a real review, a `Simulated` record with no signature, and the promise kept in the record — plus a switch under an open review throwing the preparation away and a binding made under the other promise refused by the gate |
| `OperationPrivacyTest` | The captured traffic (SEE-93's privacy acceptance): the whole path against two real HTTP servers, then every byte sent to each read back and searched. The gateway is told a channel and a sequence and none of the owner's numbers; the provider is told two mints, an amount and — for the build alone — the owner's address, and never the publisher, the proposal or the signature; and nothing goes anywhere after the wallet |
| `ProposalScreensTest` | Compose on Robolectric: the list and its empty state, the publisher's words and terms shown as theirs, an amount typed in the asset's own units reaching the app in exact base units, too many decimal places refused rather than rounded, Approve offered only for bytes the phone accounted for whole, a finding shown and nothing to approve beside it, a provider quoted as itself, an unsupported server read in full, an expired proposal offering no preparation, and a sandbox review saying so before anything else and offering `Simulate` rather than `Approve` (SEE-97) |
| `AddressLookupTablesTest` | Resolving a versioned message's accounts (SEE-94), and every way it must refuse to: a table missing, owned by the wrong program, deactivated, the wrong length, an index past its end, and a message reaching past the rebuilt list. The rebuild order has its own case, because getting it wrong would resolve every instruction to the wrong addresses silently |
| `SolanaAccountsTest` | The chain read over a real HTTP endpoint: the one method it names, nothing about the owner in the request, a JSON-RPC error arriving with a 200, a partial answer, data that is not base64, a rate limit, a dead endpoint, and a build with no endpoint asking nobody anything |
| `PredictionTermsTest` | What a publisher has to say for a market to be readable, the provider's minimum as a floor under the publisher's, a stake token the provider does not take, and every way the owner's own side and stake can fall outside what was published |
| `PredictionFixturesTest` | A real order from the live API with the real contents of the tables it names: refused before resolution, resolved from the tables the message itself names, read field for field against the provider's own JSON, its funding swap read by SEE-93's reader unchanged, verified whole, and blocked outright when the chain cannot be read |
| `PredictionInspectionTest` | Everything the order review refuses, one changed thing at a time: the side, the market, the order's identifier and accounts, the contracts, the ceiling, the cost, the slippage, whose order it is, who pays, buying against selling, two orders, the token, whose account funds it, where the funding lands, how much it takes, an account created for somebody else, a second missing signature, the owner's slot already filled, an extra transfer, a foreign program, another instruction of the prediction program, trailing bytes, and each way the tables can be unusable |
| `JupiterPredictionPluginTest` | The order the plugin does things in: the market read before an order is asked for, a closed or settled market refusing without an order, a market in another event or from another source, an order wanting somebody else's signature, the chain failing with its own reason, preparing not reading the environment (SEE-97), a review that belongs to one side and one stake, and two owners backing two sides |
| `HttpJupiterPredictionTest` | The prediction wire over a real HTTP endpoint: what a market read and an order carry, nothing about the publisher in either, an answer about another market or side refused, and the two provider refusals worth telling apart |
| `PredictionOperationTest` | A market proposal from the feed to a signature and the links after it: the side and stake asked for and neither suggested, the market and chain read in order, the wallet asked once, the record keeping which order it was across a restart, a closed market, a chain that cannot be read, an order for the other side never reaching the wallet, a changed side throwing the order away, an answer that never arrived not repeated, and nothing about the side or stake reaching the gateway |
| `StageBoundaryTest` | The stage boundary. The source manifest declares `MainActivity`, disabled-by-default Firebase auto-init, current installation-ID registration, FCM's SAC default icon/accent, only the non-exported messaging service, `INTERNET`, optional camera, and `POST_NOTIFICATIONS`. Firebase imports stay under `push/`; the service has registration callbacks and only the fixed invalidation-to-`PushSyncScheduler` message path, with no repository, transport, coroutine, notification, or wallet dependency in the callback. Notification code is limited to channels, permission, post-Sync type/source presentation, immutable activity intents, and read-only routes; it has no wallet, approval, scheduler, action, custom remote view, or background component. Storage and Keystore APIs stay in their named storage packages; WorkManager access stays under `sync/`; foreground services, other services, alarms, receivers, notification actions, and wallet-key APIs remain absent. Nothing is backed up. The Mobile Wallet Adapter client, WorkManager, and Firebase Messaging are on the classpath on purpose; Seed Vault's own SDK, AndroidX Security crypto, and legacy GCM are not. SEE-93 adds one check and extends four: the plugins reach their provider and nothing else of this app's, the provider's API host is written in one file and its platform link in one other, the build's plugin list is in the composition root, `operations/` names no provider, and the message format is still parsed in exactly one package. SEE-94 adds one more: the chain reader names one RPC method and it is a read, reaches nothing of the app but the decoder's types and base58, names no provider at all, takes its endpoint from the build through the composition root alone, and no storage package persists a URL. |

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
