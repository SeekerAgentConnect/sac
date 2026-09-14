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
