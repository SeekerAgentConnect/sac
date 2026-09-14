# Optional Firebase Cloud Messaging setup

SAW-054 prepares the optional Android Firebase client and self-hosted sidecar sender. SAW-055 binds
the client's current direct-send registration to paired connections: initial registration and
later refreshes are sent to each sidecar through that connection's authenticated phone API.
SAW-056 sends a content-free invalidation after committed request changes. SAW-057 keeps receipt
inside the Firebase callback budget, deduplicates the handoff, and schedules only the connections
that need the existing authoritative Sync path. SAW-058 adds one private request channel, an
isolated runtime notification-permission request, and a read-only tap route which fetches current
state before showing review controls. Adding or removing Firebase, or denying notification
permission, does not change the Stage 5.2 foreground stream, manual **Refresh**, unary Sync, or
periodic WorkManager recovery.

## What belongs to one deployment

Choose one Firebase project controlled by the person operating the deployment. Register an Android
app with the exact package name `io.github.brrenat.seekervault`, and use that project's ID for the
sidecar sender. The Android project file identifies the receiving app; the sidecar's Application
Default Credentials authorize sending. They are different files with different trust:

- `google-services.json` contains the Android app's project identifiers. Firebase documents these
  values as non-secret, but this repository still treats the file as deployment-specific and
  ignores it.
- A service-account JSON file is a credential. Keep it outside the checkout, do not copy its JSON
  into `.env`, and do not print it, its access tokens, or a device registration token.

The Firebase project can serve more than one self-hosted sidecar. Each sidecar needs authorized
credentials for that project. The configured app publishes the same current device target to each
usable paired sidecar separately, with each connection's own credential. One unreachable or old
sidecar does not receive another sidecar's credential and does not stop the others.

## Configure Android

1. In the [Firebase console](https://console.firebase.google.com/), create or select a project.
2. Add an Android app whose package name is exactly `io.github.brrenat.seekervault`.
3. Download `google-services.json` and place it at `android/app/google-services.json`. Do not commit
   it; `.gitignore` excludes every file with that name.
4. Build the app with `pnpm check:android` or from Android Studio.

The app pins Firebase through the Android BoM and uses the main `firebase-messaging` module, not the
retired KTX artifact. The Google Services Gradle plugin is applied only when
`android/app/google-services.json` exists. Without that file the same debug and test APK tasks build,
there is no default Firebase project configuration, and Firebase Messaging cannot obtain a token.
Delete the file and rebuild to produce an unconfigured installation again.

The source manifest keeps `firebase_messaging_auto_init_enabled=false` as its safe default and sets
`firebase_messaging_installation_id_enabled=true` for the current direct-send API. After stored
connections load, the app explicitly calls Firebase Messaging `register()` only while at least one
connection remains usable. Firebase reports the current Firebase Installation ID through
`onRegistered`; it calls that callback for the initial value and again when registration is
refreshed. The app serializes callbacks, atomically replaces the old value on every sidecar, and
does not write the value to disk. After the last usable connection disappears it calls
`unregister()` and returns auto-init to false.

The Firebase Messaging library contributes its normal receiver, service, provider, and permission
entries to the merged APK manifest whether or not a project file is present. The app now declares
`POST_NOTIFICATIONS` explicitly. SAW-055 adds one non-exported `SeekerVaultMessagingService` for
`onRegistered` and `onUnregistered`; SAW-056 adds its exact-match data-message callback and Sync
scheduling. SAW-058 creates and uses a notification channel only when the project file was present
at build time. These entries are plumbing, not a claim of guaranteed delivery.

## Registration ownership and cleanup

Each sidecar connection row holds either no FCM target or one current opaque value. The phone calls
`PairingService.SetFcmToken` with that connection's fixed URL, connection ID, and encrypted phone
credential. Another connection ID is `NOT_FOUND`; an agent, pairing token, Stage 1 token, absent
credential, or revoked credential is `UNAUTHENTICATED`. No read endpoint returns the target.

- Initial `onRegistered` publishes the value to all usable connections.
- A later `onRegistered` atomically rotates each connection from the old value to the new one.
- `onUnregistered` asks each sidecar to clear only the value Firebase named. If a newer value was
  already stored, the stale clear changes nothing.
- Pairing a replacement phone, `RevokeConnection`, and `pnpm pair revoke` clear the server value in
  the same SQLite transaction that revokes the connection.
- Empty, whitespace/control, non-ASCII, and values over 4096 bytes are ignored by Android and
  rejected by the sidecar without being copied into an error or log.

The sidecar stores the current target in recoverable form because a later ticket's sender must
address it. Treat the database and its backups as private deployment data. The target is not a
bearer credential and grants no request or wallet authority, but it still must never be logged,
placed in support output, or copied into a push payload.

This conditional setup is intentional. Do not add placeholder Firebase resources or a shared
project file just to make the default build look configured.

## Configure the sidecar sender

First enable the **Firebase Cloud Messaging API (V1)** for the Firebase/Google Cloud project. Give
the runtime service account only the permission it needs to send, such as the **Firebase Cloud
Messaging API Admin** role described by Firebase's server setup.

The sidecar uses the Firebase Admin SDK with Application Default Credentials (ADC):

- On Google-managed infrastructure, prefer an attached runtime service account. ADC discovers it
  without a downloaded key file.
- On another self-hosted machine, store the service-account JSON outside the repository with
  access restricted to the sidecar account, and set `GOOGLE_APPLICATION_CREDENTIALS` in the
  process supervisor to its absolute path.

Then set the non-secret project ID and start the sidecar:

```bash
export FCM_PROJECT_ID=your-firebase-project-id
export GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/seeker-vault-fcm.json
pnpm dev:sidecar
```

`FCM_PROJECT_ID` may instead live in the ignored `.env`. Keep
`GOOGLE_APPLICATION_CREDENTIALS` in the deployment environment so a credential path does not leak
into committed configuration. A process supervisor should mount the credential read-only, inject
both variables into only the sidecar process, and restart the sidecar after rotation. On shutdown,
the sidecar deletes its named Firebase Admin app.

At startup the sidecar logs only one of these states:

```text
FCM sender is off; FCM_PROJECT_ID is not configured
FCM sender is configured through Application Default Credentials
```

It does not log the project ID, credential path or contents. The Admin SDK does not fetch an access
token at construction time, so a configured startup is not proof that the credential can send.

<a id="invalidation-delivery-saw-056"></a>

## Invalidation delivery (SAW-056)

A sidecar sends only after a durable request event commits. Several changes ready in the same turn
coalesce for that connection. The Admin request uses the connection's current Firebase Installation
ID in the `fid` routing field, and its complete app-visible data is:

```text
kind=request_invalidation
version=1
```

There is no Firebase notification object and no request or connection ID, URL, credential, policy,
assessment, note, message-to-sign contents, transaction bytes, authorization, approval, signature,
amount, recipient, or program. The FID is an address in the server-to-Firebase envelope, not a data
field delivered to the app. Android accepts only the exact two-field map: an unknown version,
missing field, or extra field is ignored.

The hint is not the state. Receipt enqueues one unique, connected-network WorkManager request with
empty input. Duplicate receipts keep the already-enqueued work. The worker loads every usable
paired connection from phone storage and calls the same bounded authenticated `UpdateService.Sync`
path used by Stage 5.2, so collapsing a hint from either of two sidecars loses no identity: the
phone reconciles both. It retries only transient unreachability. It cannot prepare, approve, answer,
open a wallet, sign, send a transaction, or choose a screen.

### Priority, Doze, TTL, collapse, and throttling

- **Priority:** only the first durable event for a new PENDING request is sent with Android `high`
  priority, because that event can become SAW-058's time-sensitive user-visible notification after
  authoritative Sync. State, outcome, confirmation, cancellation, and expiry changes use `normal`.
  Firebase says normal messages may wait while the device is in Doze; high priority attempts
  immediate delivery and limited processing, but should be reserved for time-sensitive
  user-visible notifications. Repeated high-priority traffic that does not produce user-visible
  notifications can be deprioritized. SAW-056 itself displayed nothing; SAW-058 supplies the
  corresponding generic alert when Android permission and the channel allow presentation.
- **Work after delivery:** a message delivered as high priority requests expedited WorkManager
  execution immediately, with `RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback when expedited quota is
  unavailable. Normal delivery requests ordinary work. A connected network is still required, the
  operating system still schedules the work, and Force stop prevents handling until the owner
  reopens the app.
- **TTL:** every hint has a five-minute (`300000` ms) TTL. An older hint is deliberately allowed to
  expire rather than wake the app much later for stale timing; foreground, manual, and periodic Sync
  still recover the durable state.
- **Collapse:** every hint uses Android collapse key `seeker-vault-request-state-v1`. FCM may replace
  an undelivered hint with the newest for the same installation. That is safe because neither hint
  carries state and every accepted receipt fetches the whole authoritative view. FCM does not
  guarantee message order.
- **Delivery and throttling:** an Admin `send()` message ID means FCM accepted the request, not that
  a device received it. Offline devices, Doze, battery policy, quota, overload, per-device rate
  limits, collapsible-message throttling, app uninstall, and Firebase outages can delay or discard a
  hint. The dispatcher does not retry inside the request transaction. A permanent FID rejection
  compare-clears only that rejected value; a concurrent rotation survives. Other failures keep the
  target and durable request unchanged and log only `delivery unavailable`, never Firebase's error
  text.

These choices follow Firebase's current [Android priority and Doze
guidance](https://firebase.google.com/docs/cloud-messaging/android-message-priority), [message
lifespan](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan),
[collapsible-message](https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types),
and [throttling and quota](https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas)
documentation checked on 2026-09-14. Delivery is intentionally never an acceptance condition for
the durable request commit.

<a id="service-handoff-and-sync-recovery-saw-057"></a>

## Service handoff and Sync recovery (SAW-057)

`FirebaseMessagingService.onMessageReceived` performs only two bounded steps: compare the complete
data map with the fixed version-1 invalidation, then enqueue unique WorkManager work. It does not
load the cache, open a sidecar connection, or wait for Sync. The network-constrained worker performs
that longer work after Android accepts the handoff; its input remains empty.

Deduplication happens at each boundary without treating a ping as state:

- Firebase may collapse several undelivered messages under
  `seeker-vault-request-state-v1`; the newest still means only “fetch current state.”
- Repeated callbacks enqueue one `push-authoritative-sync` work name with `KEEP`, so a queued or
  active job is not replaced by each duplicate.
- At execution time, push and periodic workers omit usable connections whose foreground gRPC stream
  is already Live. Other connections enter the existing four-sidecar-bounded synchronization
  repository. One connection has one snapshot coordinator, and stream events are buffered while a
  snapshot commits, so simultaneous Refresh, stream recovery, periodic work, and push work converge
  rather than applying independent copies.

These rules reduce redundant work; they do not create a delivery guarantee. A hint that arrives
while equivalent work is active may add no separate fetch, and a hint may be delayed, collapsed,
expired, throttled, or dropped before the app sees it. Durable requests remain on the sidecar.
Foreground entry reconciles before opening Subscribe, a healthy foreground stream carries later
events, manual **Refresh** calls the same Sync path, and the persisted 15-minute-minimum periodic job
eventually fetches every usable non-Live connection. A Firebase-off build has exactly those Stage
5.2 paths. Normal process death can still be followed by WorkManager or FCM subject to Android
policy; Settings **Force stop** blocks both until the owner reopens the app.

<a id="notifications-and-tap-to-open-saw-058"></a>

## Notifications and tap-to-open (SAW-058)

A Firebase-configured APK creates one high-importance **Requests waiting for review** channel with
secret lock-screen visibility. On Android 13 and later it asks for notification permission only
after stored connections have loaded and at least one is usable. Denial suppresses presentation
only: foreground streams, manual Refresh, unary Sync, the persisted periodic job, FCM registration,
and push-triggered Sync continue unchanged. Android Settings is the place to enable a permission or
channel that was denied or disabled. A Firebase-off APK creates no channel, makes no permission
request, and posts no notification.

The Firebase callback still displays nothing. After its worker finishes an authoritative Sync, the
app compares the complete pending-key set from before and after that fetch. A newly discovered
pending key gets one generic alert; a key that left PENDING has its earlier alert canceled. The
notification says only that a request is waiting and names the owner's local connection label. It
contains no action, request text, agent note, amount, recipient, policy, credential, authorization,
transaction, signature, or answer, and its lock-screen content is private. Distinct requests have
distinct immutable, explicit intents containing only the connection and request IDs needed to
route inside this app.

The shade can still contain stale information. A status-change hint may be dropped, expire, or be
delayed, or the periodic worker may not have run yet. Tapping therefore never trusts notification
state: it validates both IDs and fetches that paired sidecar before exposing any review controls.
It then opens the current request, the answer already stored by this phone, or an honest state:

- **no longer waiting** for an expired, canceled, or remotely answered request;
- **connection removed** when its local pairing was deleted;
- **connection revoked** when the stored connection is no longer usable; or
- **current state unavailable** when the sidecar cannot be reached, with a retry that fetches again.

Loading and stale/error states have no answer, approval, or wallet controls. A tap never chooses an
answer, approves, signs, or opens a wallet. Its authoritative Sync may retry only an answer the
owner already stored, as every Stage 5.2 Sync can. Once a current request is displayed, every
existing manual review and wallet rule remains exactly the same: only the owner's later explicit
action can answer it or start a wallet interaction.

High-priority invalidations now correspond to the newly created, time-sensitive request that may
produce this user-visible alert when permission is granted. Normal-priority state changes only
reconcile and cancel/update local presentation. If permission is denied, high-priority receipt can
still schedule Sync but cannot show the alert; sustained high-priority traffic without visible
notifications may be deprioritized by FCM. Doze, quota, throttling, the five-minute TTL, the shared
collapse key, non-guaranteed ordering, and Force-stop behavior described above still apply.

## Off, unavailable, and incorrectly configured

| Condition | Behavior through SAW-058 |
| --- | --- |
| No Android `google-services.json` | The Google Services plugin is not applied, no default Firebase app exists, registration calls are no-ops, and the app creates no request channel, permission prompt, or notification. Every Stage 5.2 path still builds and runs. |
| No sidecar `FCM_PROJECT_ID` | No Firebase Admin app, dispatcher, listener, or sender is constructed. Durable events still commit and feed Stage 5.2 normally. |
| Notification permission denied, or the channel disabled | Alerts are absent. Push may still trigger authoritative Sync, and foreground streams, manual Refresh, unary Sync, and periodic recovery are unchanged. Enable the permission/channel in Android Settings if alerts are wanted. |
| Invalid `FCM_PROJECT_ID` | Configuration fails with the variable name and format requirement, never a credential value. Unset the variable to return to the fully functional Firebase-off mode. |
| Missing, expired, or unauthorized ADC; FCM outage; quota/throttling response | The best-effort send fails after the durable change has committed. Fixed logs name only `delivery unavailable`; the target and request stay unchanged, and Stage 5.2 recovery remains authoritative. |
| Android and sidecar use different Firebase projects | Sends are rejected for the target rather than changing request state. Correct both deployment configurations; never copy a target into logs while diagnosing it. |
| Old sidecar without `SetFcmToken`, or sidecar unavailable during registration | That connection misses this registration attempt. Other sidecars continue independently, and its foreground Subscribe, manual Refresh, unary Sync, and periodic recovery keep working. A later registration refresh or usable-connection change retries idempotently. |
| Firebase invalidates or unregisters an old value after rotation | Phone unregistration and a permanent send rejection both compare-clear the exact rejected value. A newer stored value remains untouched. |

Turning FCM off never requires deleting a paired connection or the Stage 5.2 cache. Remove the
Android project file for the next APK, unset `FCM_PROJECT_ID` for the sidecar, and restart/rebuild as
appropriate. A phone on an unconfigured build continues using foreground streams, manual Refresh,
and the periodic worker. WorkManager remains eventual, and neither it nor FCM delivery can
bypass Android Settings **Force stop**; reopen the app after a force-stop.

## Pricing and quotas checked for SAW-054

Checked on **2026-09-14** against Firebase's official pages:

- [Firebase pricing](https://firebase.google.com/pricing) and the
  [Cloud Messaging product page](https://firebase.google.com/products/cloud-messaging) list Cloud
  Messaging itself as no-cost at that time. This is not a permanent zero-cost infrastructure
  promise: hosting the sidecar, network egress, monitoring, or other Firebase/Google Cloud products
  can have separate prices, and Firebase can change its terms.
- [FCM throttling and quotas](https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas)
  lists a default HTTP v1 downstream quota of 600,000 messages per project per minute, with
  `429 RESOURCE_EXHAUSTED` above available quota. It also lists per-device Android limits of 240
  messages per minute and 5,000 per hour, and a collapsible-message burst of 20 per app/device with
  one message replenished every three minutes. Firebase explicitly says these limits can change.

Check the Firebase pricing page again before deployment and inspect the project's current **APIs &
Services → Firebase Cloud Messaging API → Quotas & System Limits** page instead of relying on these
dated numbers. Stage 5.3 invalidations are intended to be minimal, coalesced hints, not a reason to
operate near any published maximum.

## References

- [Add Firebase to an Android project](https://firebase.google.com/docs/android/setup)
- [Google Services plugin and JSON file](https://firebase.google.com/docs/android/google-services-plugin-and-file)
- [Add Firebase Admin to a server](https://firebase.google.com/docs/admin/setup)
- [FCM server environment](https://firebase.google.com/docs/cloud-messaging/server-environment)
- [Send with the Firebase Admin SDK](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)
- [Firebase Admin FID message](https://firebase.google.com/docs/reference/admin/node/firebase-admin.messaging.fidmessage)
- [Firebase Messaging Android API](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessaging)
- [Firebase Messaging service callbacks](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessagingService)
