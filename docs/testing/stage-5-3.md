# Stage 5.3 verification

Stage 5.3 adds optional FCM wake-up and user-visible request notifications on top of the completed
Stage 5.2 convergence path. Firebase never becomes the source of truth: foreground Subscribe,
unary Sync, and the periodic WorkManager job must continue to recover every durable request when
push is absent, unavailable, delayed, duplicated, or dropped.

## SAW-054 — optional Firebase client and sidecar sender

SAW-054 is deployment plumbing only. Android has the current Firebase Messaging client and applies
the Google Services plugin only for an operator-supplied project file. The sidecar can own one
Firebase Admin sender behind `FCM_PROJECT_ID` and Application Default Credentials. There is no
token registration/storage, invalidation call, app-defined messaging service, runtime notification
permission request or channel, notification UI, deep link, or push-triggered work in this revision. The
Firebase dependency's standard components and permissions do appear in the merged manifest, but
there is no app-defined handler or runtime permission request. Messaging
auto-init is explicitly false until SAW-055 can pair token creation with ownership and cleanup.

### Automated behavior

- `loadSidecarConfig` proves an absent/empty `FCM_PROJECT_ID` creates no setting, accepts a valid
  trimmed project ID, and rejects invalid IDs without printing supplied values.
- `FcmSender` tests exact message hand-off, opaque FCM message IDs, idempotent Firebase Admin app
  deletion, and create/close without reading credentials or contacting FCM.
- `server.test.ts` injects the sender boundary. Firebase-off startup never invokes the factory;
  configured startup owns and closes one sender and logs neither the project ID nor credential
  data.
- Node `stage-boundary.test.ts` confines `firebase-admin` imports to `sidecar/src/push/` and rejects
  logging from that credential-bearing module. No request lifecycle calls the sender.
- Android `StageBoundaryTest` requires Firebase Messaging on the classpath while allowing only the
  disabled-auto-init metadata beside `MainActivity`, `INTERNET`, and optional camera access in the
  app's source manifest. It still rejects an app-declared service, receiver, alarm, foreground service,
  legacy GCM, AndroidX Security credential store, or WorkManager use outside `sync/`.
- A build from the normal checkout, with no `android/app/google-services.json`, passed all Android
  checks. A temporary fake, credential-free file for the correct package made
  `processDebugGoogleServices` and `assembleDebug` pass; the file was removed immediately and was
  never tracked.
- The complete Stage 5.2 production-path suite passed with Firebase off. Its real sidecar HTTP/2
  tests and Android sync/lifecycle tests continue to use the same stream, Sync, cache, and worker.

### Commands and results

Run on **2026-09-14** on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, Gradle 9.7.1,
launcher JDK 19.0.2, and the pinned Temurin 21 daemon criteria. New integration pins were Firebase
Admin 14.4.0, Firebase Android BoM 34.19.0, and Google Services plugin 4.5.0.

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | **PASS.** Lockfile and supply-chain policy accepted; Firebase web-app and protobuf warning-only postinstalls remain explicitly disabled. |
| `pnpm test:updates` | **PASS.** 8/8 sidecar update tests plus Android sync and gRPC interoperability tests; run with no Firebase project file or sidecar project ID. |
| `pnpm check` | **PASS.** Prettier, Buf format/lint, ESLint, both TypeScript checks, 417/417 sidecar tests, and 29/29 test-agent tests. |
| `pnpm test:hello` | **PASS.** 9/9 Stage 1 simulated-device cases. |
| `pnpm test:queue` | **PASS.** 7/7 Stage 2 two-sidecar cases. |
| `pnpm check:android` | **PASS.** Spotless, all debug JVM tests, Android lint, debug APK, and instrumentation APK in the Firebase-off checkout. |
| Temporary fake `google-services.json`; `:app:processDebugGoogleServices :app:assembleDebug` | **PASS.** Proved the configured Gradle branch and correct package selection; no real project, API key, or credential was used. |
| `pnpm check:generated` | **PASS.** Generated protocol code and fixtures are current; SAW-054 changes no protocol. |
| `pnpm build` | **PASS.** Sidecar and test-agent TypeScript builds. |

### Deliberate failures

The failure probes were reverted before the passing commands above:

- Removing `implementation(libs.firebase.messaging)` made
  `StageBoundaryTest.firebaseMessagingIsOnTheClasspathFromSaw054` fail.
- Enabling Firebase Messaging auto-initialization in the manifest made
  `StageBoundaryTest.manifestDeclaresOnlyTheActivityDisabledFcmTheNetworkAndAnOptionalCamera`
  fail. SAW-054 must not create an unowned token before SAW-055 adds its authenticated lifecycle.
- Removing `FcmSender.close()`'s one-time shutdown guard made the focused sender test fail with two
  Admin-app deletions instead of one.

### Physical Seeker

`adb devices -l` listed no device on 2026-09-14.

| Check | Result |
| --- | --- |
| Install and open the Firebase-off APK; exercise foreground updates, manual Refresh, and eventual WorkManager recovery | **NOT RUN:** no physical Seeker attached. Automated coverage and APK assembly do not count as a device pass. |
| Install an operator-configured APK and inspect Firebase initialization | **NOT RUN:** no physical Seeker or real Firebase project/credential was used. SAW-054 has no delivery behavior to claim. |

The later Stage 5.3 acceptance ticket owns real active/background/process-absent, screen-off/Doze,
duplicate/drop, token-rotation, revoked-connection, denied-notification, two-sidecar, tap-routing,
reboot, and Force-stop results. Those remain **NOT RUN** until a real Seeker and real deployment
record them against a revision.

### Pricing and quota verification

The [setup guide](../guides/firebase.md#pricing-and-quotas-checked-for-saw-054) records the official
Firebase pages checked on 2026-09-14: FCM was listed as no-cost, while current project/device and
collapsible-message quotas were finite and explicitly subject to change. The documentation makes
no permanent zero-cost infrastructure promise and directs operators to recheck pricing and the
project's live Google Cloud quota page before deployment.

## SAW-055 — connection-scoped registration and rotation

SAW-055 adds the registration lifecycle only. `PairingService.SetFcmToken` authenticates the phone
before it reads the connection ID or bounded opaque update. The sidecar keeps one current target per
active connection; registration is idempotent, rotation replaces atomically, and invalidation is a
compare-delete. The revocation transaction clears the target. No API reads it back and no log names
it.

Android's application-scoped `FcmRegistrationManager` waits for stored connections to load, enables
current FCM registration only while one is usable, and serializes connection changes with
`onRegistered`/`onUnregistered`. It publishes the current value to every usable sidecar separately
through `ConnectionRepository`, which retrieves only that connection's credential and fixed URL.
The phone persists no target. The app-defined Firebase service implements no message-receipt
callback, so this child sends no FCM message, handles no payload, triggers no Sync, requests no
notification permission, displays nothing, and routes no tap.

### Automated behavior

- `PairingStore` tests persistence across restart, idempotent set, atomic rotation, the 4096-byte
  visible-ASCII bound, generic errors, stale compare-delete, and cleanup on both replacement and
  revocation.
- The complete role matrix includes `SetFcmToken`. Agent, pairing, Stage 1, absent, wrong, and
  revoked credentials are refused; the paired phone can mutate only its own connection. The real
  handler test inspects SQLite and verifies neither targets nor credentials reached the log.
- `FcmRegistrationManagerTest` covers the loaded/usable gate, initial registration, refresh
  rotation, two sidecars, an isolated failed sidecar, invalid callbacks, stale unregistration, a
  newly paired sidecar, and last-connection unregistration.
- `ConnectionRepositoryTest` proves two server credentials remain pinned to their own URLs while
  the same device target is registered, and that an authentication failure marks only that
  connection revoked. `ConnectConnectionGatewayTest` drives generated Kotlin through a real Node
  sidecar for register/rotate/compare-clear and checks its process output for private values.
- `StageBoundaryTest` allows only the non-exported registration-callback service, confines Firebase
  imports to `push/`, and proves it has neither `onMessageReceived` nor `RemoteMessage`. The Node
  boundary still rejects an FCM send caller.

### Commands and results

Run on **2026-09-14** on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, Gradle 9.7.1,
launcher JDK 19.0.2, and the repository's pinned Firebase versions. The checkout contained neither
`android/app/google-services.json` nor `android/local.properties`; the Android SDK path was supplied
only to each process through `ANDROID_HOME`.

| Command | Result |
| --- | --- |
| Focused sidecar storage/role tests and focused Android registration/repository/real-sidecar/boundary tests | **PASS.** The generated TypeScript and Kotlin clients exercised the new unary RPC. |
| `pnpm check` | **PASS.** Formatting, Buf lint, ESLint, both TypeScript checks, 423/423 sidecar tests, and 29/29 test-agent tests. |
| `ANDROID_HOME=… pnpm test:updates` | **PASS.** 8/8 sidecar update tests and all selected Android Stage 5.2 sync/HTTP2 tests. The first invocation without `ANDROID_HOME` completed the eight Node cases, then correctly stopped because this clean checkout has no machine-specific `local.properties`; the environment-configured rerun passed. |
| `pnpm test:hello` | **PASS.** 9/9 Stage 1 simulated-device cases. |
| `pnpm test:queue` | **PASS.** 7/7 Stage 2 two-sidecar cases. |
| `ANDROID_HOME=… pnpm check:android` | **PASS.** Spotless, 820/820 debug JVM tests, Android lint, debug APK, and instrumentation APK, with Firebase unconfigured. |
| `pnpm check:generated` | **PASS.** Generated protocol clients are current after adding `SetFcmToken`. |
| `pnpm build` | **PASS.** Sidecar and test-agent TypeScript builds. |

### Deliberate failure

The compare-and-delete predicate was temporarily changed so any active connection would clear even
when the invalidated value was stale. The focused `compare-and-deletes only the target that became
invalid` test failed (`true !== false`). The predicate was restored before every passing command
above.

### Physical Seeker and Firebase project

`adb devices -l` listed no device on 2026-09-14, and no real Firebase project or credential was
used.

| Check | Result |
| --- | --- |
| Initial registration and registration refresh on a configured physical Seeker | **NOT RUN:** no physical Seeker or Firebase project was available. JVM callbacks and APK assembly do not count. |
| Two real sidecars receive one device target through their own credentials; revocation and final deletion unregister it | **NOT RUN:** no physical Seeker or Firebase deployment was available. Automated multi-sidecar and revocation coverage passed. |

Delivery, background/process-absent handling, notification permission/UI, tap routing, reboot, and
Force-stop behavior remain later Stage 5.3 work and are **NOT RUN**, not inferred from this
registration-only implementation.

## SAW-056 — minimal invalidation pings and authoritative Sync

SAW-056 connects committed durable request events to the optional sender. Events coalesce per
connection, and the only app-visible data is `kind=request_invalidation` and `version=1`. A new
PENDING request is high priority; every other request state or outcome change is normal priority.
Both use a five-minute TTL and `seeker-vault-request-state-v1` collapse key. There is no Firebase
notification object and no request/connection identifier, credential, policy, message-to-sign
content, transaction authorization, approval, or signature in the data.

Android rejects any missing, unknown, or additional payload field. An accepted hint enqueues one
unique connected-network WorkManager request with empty input; it then loads usable paired
connections and their credentials from their existing stores and invokes the same bounded,
authenticated synchronization repository as Stage 5.2. A push cannot prepare, approve, answer, open a
wallet, sign, send a transaction, display a notification, or select a tap destination.

### Automated behavior

- `invalidation.test.ts` drives real SQLite pairing and request stores. It proves same-turn
  coalescing, high priority only for creation, normal priority for a later change, exact TTL and
  collapse key, no notification object, no send for a rolled-back event, safe invalid-target
  compare-clear across rotation, transient target retention, and fixed logs without Firebase error
  text.
- `server.test.ts` registers a target through the authenticated generated phone client, creates a
  durable request through the real MCP tool, and observes the exact content-free send only after
  creation succeeds. The request body, agent note, and target remain absent from payload data and
  logs.
- `SeekerVaultMessagingServiceTest` accepts the exact two fields at both delivered priorities and
  rejects missing, unknown-version, extra, request-ID, and credential fields.
- `PushSynchronizationTest` proves empty WorkManager input, connected-network constraint,
  exponential backoff, expedited fallback only for a high-priority delivery, unique-work
  coalescing, bounded recovery-set authoritative fetch, and retry only for transient
  unreachability.
- `StageBoundaryTest` confines Firebase imports to the registration/message callback package and
  WorkManager use to `sync/`. It rejects notification, `PendingIntent`, wallet, approval, and
  signing behavior in the callback. The sidecar boundary requires the audited dispatcher instead
  of a raw sender call.
- The existing Firebase-off server test still proves no sender construction without
  `FCM_PROJECT_ID`; the complete Stage 5.2 update and earlier acceptance suites pass unchanged.

### Commands and results

Run on **2026-09-14** on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, Gradle 9.7.1,
launcher JDK 19.0.2, and the repository's pinned Firebase versions. The checkout contained neither
`android/app/google-services.json` nor `android/local.properties`; the Android SDK path was supplied
only to each process through `ANDROID_HOME`.

| Command | Result |
| --- | --- |
| Focused sidecar invalidation/server/boundary tests and focused Android callback/scheduler/boundary tests | **PASS.** Exact-payload, commit ordering, priority, TTL, collapse, failure, authoritative-fetch, and no-wallet/no-notification assertions passed. |
| `pnpm check` | **PASS.** Prettier, Buf format/lint, ESLint, both TypeScript checks, 427/427 sidecar tests, and 29/29 test-agent tests. |
| `ANDROID_HOME=… pnpm test:updates` | **PASS.** 8/8 sidecar update tests and all selected Android Stage 5.2 sync/HTTP2 tests, including the new push Sync scheduler because it remains inside `sync/`. |
| `pnpm test:hello` | **PASS.** 9/9 Stage 1 simulated-device cases. |
| `pnpm test:queue` | **PASS.** 7/7 Stage 2 two-sidecar cases. |
| `ANDROID_HOME=… pnpm check:android` | **PASS.** Spotless, 826/826 debug JVM tests, Android lint, debug APK, and instrumentation APK, with Firebase unconfigured. |
| `pnpm check:generated` | **PASS.** Generated protocol clients and fixtures are current; SAW-056 changes no protocol schema. |
| `pnpm build` | **PASS.** Sidecar and test-agent TypeScript builds. |

### Deliberate failure

An additional `request_id` field was temporarily added to the invalidation data map. The focused
payload audit failed its exact object comparison and displayed the unexpected field. The field was
removed before every passing command above.

### Physical Seeker and Firebase delivery

The Android SDK's `adb devices -l` listed no device on 2026-09-14, and no real Firebase project or
credential was used.

| Check | Result |
| --- | --- |
| Receive high- and normal-priority data on an active, backgrounded, Dozing, or process-absent physical Seeker and observe authoritative Sync | **NOT RUN:** no physical Seeker or Firebase deployment was available. JVM callbacks, WorkManager tests, and APK assembly do not count. |
| Observe TTL expiry, collapse, throttling, duplicate/drop behavior, rotation, revocation, reboot, and Force stop against FCM | **NOT RUN:** no physical Seeker or Firebase deployment was available. These delivery conditions remain best-effort and require the later stage acceptance run. |

Notification permission/UI, notification identity, and tap routing remain later tickets. The
[Firebase guide](../guides/firebase.md#invalidation-delivery-saw-056) records the documented
high-priority restriction, Doze and expedited-work behavior, five-minute TTL, shared collapse key,
throttling, non-guaranteed delivery, and Firebase-off fallback verified for this child.

<a id="saw-057--service-handoff-deduplication-and-sync-recovery"></a>

## SAW-057 — service handoff, deduplication, and Sync recovery

SAW-057 keeps the Firebase service callback to exact-map validation and WorkManager enqueue. It
performs no sidecar request, cache reconciliation, or coroutine-owned network work inside the
callback execution budget. The existing unique work name and FCM collapse key coalesce duplicate
hints without assigning any state or request identity to them.

Push and periodic workers now calculate a recovery set after loading usable connections. While the
app is foreground, every connection whose gRPC state is already Live is excluded; background and
worker-only processes include every usable connection. The set enters the existing four-server
bounded `SynchronizationRepository`, which still admits one snapshot per connection and buffers
stream events across it. Refresh, foreground recovery, periodic work, and push work therefore share
one monotonic cache writer.

### Automated behavior

- `SeekerVaultMessagingServiceTest` and `StageBoundaryTest` hold the callback to strict payload
  validation plus `PushSyncScheduler`: no repository, update transport, coroutine scope,
  notification, tap, approval, signing, or wallet dependency is present there.
- `PushSynchronizationTest` keeps empty-input, connected-network, expedited-fallback, exponential
  retry, and unique-work assertions. It additionally proves duplicate triggers do no unary work
  while every foreground stream is Live, and a mixed two-sidecar state fetches only the sidecar
  whose stream needs recovery.
- `BackgroundSynchronizationTest` proves the periodic worker uses the same selective recovery set,
  and explicitly runs a no-FCM/no-callback case in which the Stage 5.2 periodic path fetches both
  usable background connections.
- `SynchronizationRepositoryTest` starts push and periodic runners together while a newer stream
  event arrives. They perform one unary call and one stored-result retry, buffer the stream event,
  and finish with its newer revision. A separate case filters removed/unrequested connections.
- The Firebase-off sidecar test still constructs no sender. `pnpm test:updates` and the complete
  Android suite run without `google-services.json`, retaining foreground, manual, unary, periodic,
  process-recovery, and multi-sidecar behavior.

### Commands and results

Run on **2026-09-14** on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, Gradle 9.7.1,
launcher JDK 19.0.2, and WorkManager 2.11.2. The checkout contained neither
`android/app/google-services.json` nor `android/local.properties`; the Android SDK path was supplied
only to each process through `ANDROID_HOME`.

| Command | Result |
| --- | --- |
| Focused Android callback, push, periodic, synchronization-repository, and stage-boundary tests | **PASS.** Duplicate handoff, Live-stream exclusion, mixed-sidecar recovery, cross-source coalescing, buffered stream advancement, dropped-ping periodic recovery, and callback limits passed. |
| `pnpm check` | **PASS.** Prettier, Buf format/lint, ESLint, both TypeScript checks, 427/427 sidecar tests, and 29/29 test-agent tests. |
| `ANDROID_HOME=… pnpm test:updates` | **PASS.** 8/8 sidecar update tests and the selected Android production Sync, worker, lifecycle, and HTTP/2 tests. |
| `pnpm test:hello` | **PASS.** 9/9 Stage 1 simulated-device cases. |
| `pnpm test:queue` | **PASS.** 7/7 Stage 2 two-sidecar cases. |
| `ANDROID_HOME=… pnpm check:android` | **PASS after formatting.** The first run stopped at `spotlessKotlinCheck`; `./gradlew spotlessApply` made only formatting changes, and the complete rerun passed Spotless, 829/829 debug JVM tests, Android lint, debug APK, and instrumentation APK with Firebase unconfigured. |
| `pnpm check:generated` | **PASS.** Generated protocol clients and fixtures are current; SAW-057 changes no schema. |
| `pnpm build` | **PASS.** Sidecar and test-agent TypeScript builds. |

### Deliberate failure

The push recovery filter was temporarily inverted so it selected the Live connection instead of
the one needing recovery. The focused mixed-sidecar test failed at its recovery-set assertion. The
correct non-Live predicate was restored before the passing focused and full runs above.

### Physical Seeker and Firebase delivery

`adb devices -l` listed no device on 2026-09-14, and no real Firebase project or sender credential
was used.

| Check | Result |
| --- | --- |
| Receive duplicate pings while foreground streams are healthy and while one sidecar needs worker recovery | **NOT RUN:** no physical Seeker or Firebase deployment was available. JVM and WorkManager tests do not count. |
| Drop every ping, then observe foreground and OS-scheduled periodic recovery across normal process death/reboot/Doze | **NOT RUN:** no physical Seeker was attached. Automated recovery passed, but it is not physical timing evidence. |

Notification permission/UI, notification identity, tap routing, reboot delivery timing, and Force
stop delivery remain outside SAW-057. A push tap still does not exist, and no update path approves,
signs, or opens a wallet.

<a id="saw-058--notification-permission-and-tap-to-open"></a>

## SAW-058 — notification permission and tap-to-open

SAW-058 creates one high-importance request-review channel only in an APK built with an
operator-supplied Firebase project file. The channel and each alert use secret lock-screen
visibility. Android 13+ permission is requested only after stored connections load and one is
usable. Denial has no input into FCM registration, foreground streams, manual or unary Sync, push
work, or periodic recovery; a Firebase-off APK creates no channel, prompt, or notification.

Presentation happens after the push worker's authoritative Sync. The worker compares complete
pending-key sets before and after that fetch, posts generic alerts only for newly discovered keys,
and cancels alerts for departed keys. The notification component accepts no request body or
credential. Each alert's explicit immutable activity intent contains only its validated connection
and request IDs and has distinct identity, so one request cannot replace another's route.

A cold or warm tap fetches the named paired sidecar before review controls appear. A current request
opens the existing manual review; an answer stored on this phone opens that result. Expired,
canceled, remotely answered, removed, revoked, and unreachable routes show only the state current
evidence supports. Loading and non-current screens contain no answer, approval, or wallet controls.
The tap code chooses or creates no answer and invokes no approval, signing, or wallet method. Its
shared Sync may retry only a result the owner already stored. After a current transfer appears, its
existing screen may fetch and inspect a fresh unsigned preparation, but a wallet still opens only
after the owner's explicit approval.

### Automated behavior

- `RequestNotificationsTest` proves configured-only channel creation, high importance, secret
  lock-screen visibility, denied-permission suppression, generic content, immutable exact routes,
  distinct per-request identity, departed-key cancellation, malformed-ID rejection, and the
  permission-request decision matrix.
- `PushSynchronizationTest` proves notification reconciliation runs only after authoritative Sync
  has discovered the new pending key. Existing push and Stage 5.2 tests keep the worker's empty
  input, bounded shared repository, foreground-stream exclusion, and dropped-hint recovery.
- `InboxViewModelTest` proves each tap reloads storage and fetches its paired connection without an
  answer or wallet call. It distinguishes current pending, this-phone answer, gone, removed,
  revoked, and unavailable outcomes.
- `InboxActivityTest` drives a cold exact-request tap and a stale canceled tap. The first opens the
  current manual review with zero result submissions and zero wallet calls; the second shows no
  acknowledgement, rejection, or approval control after its fresh fetch.
- `StageBoundaryTest` requires `POST_NOTIFICATIONS`, the channel, immutable explicit activity route,
  UUID validation, and Sync-before-presentation ordering. It rejects notification, wallet, or
  network work in the Firebase callback; wallet, approval, scheduler, or background-component code
  in the notification package; and permission coupling inside either worker.

### Commands and results

Run on **2026-09-14** on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, Gradle 9.7.1,
Kotlin 2.4.0, launcher JDK 19.0.2, and the repository's pinned Firebase and WorkManager versions.
The checkout contained neither `android/app/google-services.json` nor `android/local.properties`;
the Android SDK path was supplied only to each process through `ANDROID_HOME`.

| Command | Result |
| --- | --- |
| Focused notification, push synchronization, inbox-view-model, activity-route, and stage-boundary tests | **PASS.** 107/107 selected tests passed, covering permission denial, post-Sync delta, immutable route identity, current-state fetch, stale/removed/revoked/unreachable states, and no-answer/no-wallet assertions. |
| `pnpm check` | **PASS.** Prettier, Buf format/lint, ESLint, both TypeScript checks, 427/427 sidecar tests, and 29/29 test-agent tests. |
| `ANDROID_HOME=… pnpm test:updates` | **PASS.** 8/8 sidecar update tests and every selected Android Stage 5.2 Sync, worker, and HTTP/2 test with Firebase unconfigured. |
| `pnpm test:hello` | **PASS.** 9/9 Stage 1 simulated-device cases. |
| `pnpm test:queue` | **PASS.** 7/7 Stage 2 two-sidecar cases. |
| `ANDROID_HOME=… pnpm check:android` | **PASS.** Spotless, 841/841 debug JVM tests, Android lint, debug APK, and instrumentation APK with Firebase unconfigured. |
| `pnpm check:generated` | **PASS.** Generated protocol clients and fixtures are current; SAW-058 changes no schema. |
| `pnpm build` | **PASS.** Sidecar and test-agent TypeScript builds. |

### Deliberate failure

The post-Sync notification delta was temporarily inverted from `after - before` to `before -
after`. The focused new-request test failed because no alert existed. The correct delta was restored
before all passing focused and full runs above.

### Physical Seeker and Firebase notification delivery

`adb devices -l` listed no device on 2026-09-14, and no real Firebase project or sender credential
was used.

| Check | Result |
| --- | --- |
| Grant and deny Android notification permission on a configured physical Seeker; verify foreground and periodic recovery in both states | **NOT RUN:** no physical Seeker or Firebase deployment was available. JVM/Robolectric permission tests and APK assembly do not count. |
| Receive distinct request alerts, tap current and stale requests from killed/background/foreground states, and verify no automatic wallet operation | **NOT RUN:** no physical Seeker or Firebase deployment was available. The automated cold-tap route is not physical-device evidence. |
| Observe channel settings, lock-screen privacy, Doze/throttling/drop behavior, reboot, and Force stop | **NOT RUN:** no physical Seeker was attached. These timing and OS-presentation checks remain for Stage 5.3 acceptance. |

<a id="saw-059--joined-acceptance-and-physical-seeker-runbook"></a>

## SAW-059 — joined acceptance and physical Seeker runbook

SAW-059 adds no new production authority or transport. It closes the stage with one repeatable
`pnpm test:push` command, joined sidecar/Android acceptance coverage, the deployment validation
procedure below, and an honest physical-device report. Firebase remains optional: the default
checkout has no project file or sender project, while Stage 5.2 foreground Subscribe, manual
**Refresh**, unary Sync, and the periodic worker remain the complete recovery path.

**Revision under test:** the SEE-79 working tree on top of `ccda9c3` (SAW-058). The final SEE-79
commit is recorded with the Linear handoff; a commit cannot contain its own SHA. No
`android/app/google-services.json`, `FCM_PROJECT_ID`, Application Default Credential path, real
Firebase project, or physical Android device was present during this run.

### Joined automated acceptance

`sidecar/src/push/stage53.acceptance.test.ts` starts two real configured sidecar listeners with
throwaway SQLite databases and injected credential-free sender boundaries. Production Connect
clients pair the phones and register targets; production MCP clients create, retry, cancel, and
read durable requests. The test proves:

- each phone credential can update only its own connection, and two sidecars remain independent;
- registration, rotation, stale compare-clear, permanent-invalid-target cleanup, and revocation
  preserve the current owner and never disclose a target or credential;
- a committed creation sends the fixed high-priority data-only invalidation, a cancellation sends
  normal priority, and both retain the five-minute TTL and shared collapse key;
- an idempotent agent retry creates no second durable mutation or ping; and
- the app-visible data contains exactly `kind=request_invalidation` and `version=1`, with no
  Firebase notification object, request content, identifiers, policy, credential, transaction
  authorization, approval, or signature.

`Stage53AcceptanceTest` starts two real Node sidecar processes with production loopback HTTP/2
updates. Real MCP requests enter their durable stores and Android's production connection gateway,
update transport, persistent cache, bounded synchronization repository, push runner, and periodic
runner consume the state. It proves:

- registration/rotation calls use each stored connection and its own URL/credential;
- a request whose ping is dropped is recovered on both sidecars by the unchanged Stage 5.2
  periodic path;
- a delayed ping after create-then-cancel fetches final state and creates no stale request alert;
- duplicate pings produce one cached request and one new-request delta;
- a worker-style process reload recovers from disk with no Activity and no Firebase callback; and
- revoking one connection removes only it while the other sidecar continues to synchronize.

The same command includes the role matrix, invalidation unit audit, registration manager, exact
Firebase callback, push and periodic runners, notification permission/channel/identity, cold tap,
stale tap, and both stage guards. The tap test loads the exact current request with zero result
submissions, message-signing calls, or transaction-send calls. The Firebase-off registration test
finds no default Firebase app, and `pnpm test:updates` separately reruns the full Stage 5.2
production path with the normal unconfigured checkout.

### Scenario evidence

| Scenario | Automated evidence | Physical evidence required |
| --- | --- | --- |
| Active app | Healthy foreground streams stay active and duplicate push work performs no unary fetch; Stage 5.2 joined tests deliver live changes. | Observe a real configured Seeker with the app visible and confirm one current request, no duplicate, and no wallet launch. |
| Background/process absent | Push handoff is bounded; a process-reloaded repository reads credentials/cache and performs authoritative Sync. | Observe real FCM plus WorkManager after Home/background and ordinary process removal. |
| Screen off/Doze | Priority, expedited fallback, connected constraint, TTL, and collapse are asserted. | Record timestamps and Android idle state on a real Seeker; mocks cannot prove delivery timing. |
| Delayed/dropped/duplicate | Delayed create/cancel yields final state; dropped hints recover through periodic Sync; duplicates yield one pending row/delta. | Exercise real network/FCM delay, sender-off drop, and any reproducible duplicate delivery without asserting exact timing. |
| Token rotation/invalid target/revocation | Authenticated rotation, stale compare-clear, permanent rejection, replacement/revocation cleanup, and two-sidecar isolation pass through real sidecar APIs. | Reinstall/clear and re-pair for a new installation, uninstall for invalidation, and revoke one real connection while watching only redacted logs. |
| Denied notifications | Permission denial suppresses posting only; push and every Stage 5.2 path remain uncoupled. | Deny permission on the Seeker and observe state convergence without an alert. |
| Notification tap | Current and stale routes fetch the named paired sidecar; tests assert no answer or wallet call. | Tap real current/stale shade entries and verify no wallet UI or automatic request outcome. |
| Firebase disabled | No default Firebase app, channel, prompt, sender, or dispatcher; `test:updates` retains Stage 5.2. | Install an APK built without the project file and repeat foreground, Refresh, periodic/process recovery. |

### Commands and results

Run on **2026-09-14** on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4,
Gradle 9.7.1, Kotlin 2.4.0, launcher JDK 19.0.2, and the pinned Temurin 21 daemon criteria. The
Android SDK was supplied through `ANDROID_HOME`; no machine-specific path is stored in the
repository.

| Command | Result |
| --- | --- |
| `ANDROID_HOME=… pnpm test:push` | **PASS.** 35/35 Node ownership/sender/invalidation acceptance tests and 46/46 selected Android registration, callback, recovery, notification, tap, boundary, and joined two-sidecar tests. |
| Deliberate payload-content failure | **PASS.** Adding `request_id` to the app-visible data made the new joined sidecar audit fail with the unexpected third field. The change was reverted before every passing run. |
| `ANDROID_HOME=… pnpm test:updates` | **PASS.** 8/8 production sidecar update cases and all selected Android Stage 5.2 Sync, worker, lifecycle, joined acceptance, and HTTP/2 tests with Firebase unconfigured. |
| `pnpm check` | **PASS after a test-only type fix.** The first invocation stopped because the new TypeScript audit read the FID from Firebase's union `Message` type without narrowing it. The test now requires the FID variant explicitly; the complete rerun passed formatting, Buf, lint, both typechecks, 428/428 sidecar tests, and 29/29 test-agent tests. |
| `pnpm test:hello` | **PASS.** 9/9 Stage 1 simulated-device cases. |
| `pnpm test:queue` | **PASS.** 7/7 Stage 2 two-sidecar cases. |
| `ANDROID_HOME=… pnpm check:android` | **PASS.** Spotless, 843/843 debug JVM tests, Android lint, debug APK, and instrumentation APK with Firebase unconfigured. |
| `pnpm check:generated` | **PASS.** Generated protocol clients and fixtures are current; SAW-059 changes no schema. |
| `pnpm build` | **PASS.** Sidecar and test-agent TypeScript builds. |

<a id="physical-seeker-runbook-saw-059"></a>

### Physical Seeker runbook

This procedure changes a test device's app state, notification permission, idle mode, and possibly
installed app data. Use a non-production Firebase project and sidecars with throwaway requests.
Never run it against a wallet or funds you are unwilling to expose to a test UI. None of the steps
requires approving a request; leave every wallet prompt untouched and treat any automatic wallet
opening as a failure.

Record this header before testing:

```text
Revision:
APK variant and whether google-services.json was present at build time:
Seeker model / Android build:
Firebase project alias (not an ID if the alias is sensitive; never a credential):
Sidecar A host and revision:
Sidecar B host and revision:
Notification permission / channel state:
Started at (UTC):
```

#### 1. Preflight

1. Run `git status --short`, `git rev-parse HEAD`, `pnpm test:push`, and `pnpm test:updates` from
   the exact checkout to deploy. Do not continue from a failing preflight.
2. Put the ignored Firebase Android file in `android/app/google-services.json`; configure each
   sidecar with the same project and ADC as described in the [Firebase setup
   guide](../guides/firebase.md). Never place the ADC JSON in the checkout or `.env`.
3. Build/install the configured APK. Confirm the source file, any credential, `.env`,
   `local.properties`, keystore, and database remain untracked.
4. Confirm one authorized device with `adb devices -l`. For loopback development, allocate
   different ordinary/update ports to sidecars A and B and reverse all four ports. Remote
   sidecars instead need publicly trusted HTTPS and HTTP/2 as in the Stage 5.2 runbook.
5. Pair both sidecars. Keep only normal redacted logs. A startup may say FCM is configured and a
   connection registration changed; a log containing a target, bearer credential, request body,
   ADC value/path, or raw Firebase error is **FAIL**.
6. Grant notifications and leave **Requests waiting for review** enabled. Record
   `adb shell dumpsys deviceidle` and `adb shell dumpsys jobscheduler
   io.github.brrenat.seekervault` as baseline diagnostic state.

#### 2. Delivery and convergence

For each row below, create a uniquely named acknowledgement or message-signing request through the
agent, record the durable request ID only in the private test record, and never approve it.

1. **Active:** leave the app visible with both streams Live; create on A and then B. Each request
   appears once without Refresh, no stream is duplicated, and no wallet opens. A push hint may be
   coalesced with the already-current stream state; do not require an extra notification or fetch.
2. **Background:** press Home, create on A, and record send, shade, and tap times. One generic alert
   may appear; its text/lock-screen preview contains no request content. Tapping opens A's exact
   current request only after a fetch and performs no answer or wallet operation.
3. **Process absent:** background the app, then use `adb shell am kill
   io.github.brrenat.seekervault` while it is eligible for background execution. Create on B. Record
   whether FCM/WorkManager starts reconciliation and whether the request survives even if no alert
   arrives. Do not use Force stop for this row.
4. **Screen off:** turn the screen off, create a request, wait a recorded bounded observation
   window, wake/unlock, and inspect the shade plus current app state. A late/missing notification is
   not lost state if Sync later finds the request.
5. **Doze:** with the app backgrounded, run `adb shell dumpsys battery unplug` and
   `adb shell cmd deviceidle force-idle`; verify the idle state, then create a new request. Record
   high-priority/notification and eventual Sync timing. Always restore with `adb shell cmd
   deviceidle unforce` and `adb shell dumpsys battery reset`, even after failure.
6. **Delayed:** disable network after a hint can be sent but before Sync completes, create and
   cancel one request, then restore network. No review control or alert for a current request may be
   invented; a stale shade entry must open the honest no-longer-waiting state.
7. **Dropped/expired:** turn the sidecar sender off (or keep the phone offline beyond the
   five-minute TTL), create a request, and receive no hint. Open the app for foreground recovery,
   use **Refresh**, and leave enough time for an eligible periodic run in separate repetitions.
   Each path must find the durable request without Firebase.
8. **Duplicate/collapse:** when the deployment can reproducibly deliver duplicate fixed
   invalidations, record how they were produced. The app must retain one unique work item, one
   cached request, and one alert identity. If duplicates cannot be induced without extracting a
   private target or adding a production endpoint, mark this row **NOT RUN**.

#### 3. Registration, permission, and two-sidecar faults

1. **Rotation:** clear app data or reinstall the configured APK, re-pair, and record the old
   connection's replacement/revocation plus the new installation's registration using redacted
   logs only. A later invalidation reaches the new installation; a delayed clear/rejection of the
   old value cannot erase it. If same-connection refresh cannot be induced without test-only app
   code, record that subcase **NOT RUN** and rely only on automated rotation evidence.
2. **Invalid installation:** uninstall the configured app, then create a request so Firebase can
   reject the obsolete installation. The sidecar may log only the fixed “target is no longer
   valid” classification and compare-clear it. Reinstall/re-pair before continuing.
3. **Revoked connection:** revoke A while B remains usable. A receives no later request state or
   alert, B continues, and no stale A credential/target appears in output.
4. **Permission denied:** deny app notification permission, create a request in background, then
   open the app. No alert is expected; foreground streams, **Refresh**, unary/push Sync, and
   periodic recovery still work. Re-enable the permission only for later presentation rows.
5. **Two sidecars:** with both configured under the same Firebase project, create one request on
   each while backgrounded. Authoritative Sync fetches both connections even though the payload
   names neither. Alerts, taps, request lists, revocation, and outages stay connection-scoped.
6. **Current and stale taps:** tap a current request and one canceled/expired/answered-elsewhere
   before tapping. The first opens current review after fetch; the second states what current
   evidence supports and exposes no answer controls. Neither tap opens a wallet or creates an
   Activity/result record.

#### 4. Firebase-off control, reboot, and Force stop

1. Remove `android/app/google-services.json`, rebuild/install, and unset `FCM_PROJECT_ID` on both
   sidecars. Confirm there is no Firebase channel/prompt/sender, then repeat live foreground,
   **Refresh**, process-reloaded unary recovery, and an eligible periodic run. Compare with the
   Stage 5.2 runbook; all four paths must remain functional.
2. Reinstall the configured APK, reboot normally, unlock, and wait for Android to restore eligible
   work. Create a request and record whether push or periodic recovery occurs; exact time is not
   promised.
3. Use Android Settings **Force stop**, then create a request. No FCM or WorkManager handling is
   expected until the owner reopens the app. Reopen once; foreground reconciliation must recover
   the request and restore the unique schedule without approving or opening a wallet.

### Physical result record — 2026-09-14

The Android platform tools were available through `ANDROID_HOME`, but `adb devices -l` listed no
device. The checkout was Firebase-off and no real sender project or ADC was configured. Revision
for every row is therefore `ccda9c3 + SEE-79 working tree`; all device/FCM claims are **NOT RUN**.

| ID | Physical check | Result |
| --- | --- | --- |
| 101 | Configured APK/project and redacted sidecar preflight | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 102 | Active app delivery/coalescing across two healthy streams | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 103 | Background notification delivery and current-request tap | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 104 | Ordinary process-absent FCM/WorkManager recovery | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 105 | Screen-off delivery and later authoritative state | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 106 | Doze high-priority attempt, timing, and eventual recovery | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 107 | Delayed create/cancel convergence and honest stale tap | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 108 | Dropped/TTL-expired hint with foreground, Refresh, and periodic recovery | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 109 | Duplicate/collapsed hints produce one work/request/alert identity | **NOT RUN:** no physical Seeker or reproducible real FCM duplicate source. |
| 110 | Installation/same-connection token rotation and stale compare-clear | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 111 | Uninstalled/invalid installation cleanup with redacted failure | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 112 | Revoked A is silent while sidecar B continues | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 113 | Denied notification permission leaves every Stage 5.2 path intact | **NOT RUN:** no physical Seeker. Automated permission and Firebase-off checks passed. |
| 114 | Two configured sidecars synchronize and route independently | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 115 | Current notification opens exact request without automatic wallet operation | **NOT RUN:** no physical Seeker. Automated cold-tap test passed with zero wallet calls. |
| 116 | Expired/canceled/answered/removed notification reports current evidence | **NOT RUN:** no physical Seeker. Automated stale-route cases passed. |
| 117 | Firebase-off foreground, Refresh, unary/process, and periodic Stage 5.2 control | **NOT RUN:** no physical Seeker. The unconfigured automated production suite passed. |
| 118 | Reboot restores eligible best-effort recovery | **NOT RUN:** no physical Seeker or real Firebase deployment. |
| 119 | Force stop suppresses handling until reopen, then Sync recovers | **NOT RUN:** no physical Seeker or real Firebase deployment. |
