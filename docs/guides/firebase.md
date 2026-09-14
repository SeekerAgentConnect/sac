# Optional Firebase Cloud Messaging setup

SAW-054 prepares the optional Android Firebase client and self-hosted sidecar sender. SAW-055 binds
the client's current direct-send registration to paired connections: initial registration and
later refreshes are sent to each sidecar through that connection's authenticated phone API.
SAW-056 sends a content-free invalidation after committed request changes and has Android schedule
the existing authoritative Sync path. This revision still does **not** request runtime notification
permission, show a notification, create a channel, or route a tap. Adding or removing Firebase does
not change the Stage 5.2 foreground stream, manual **Refresh**, unary Sync, or periodic WorkManager
recovery.

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
entries—including `POST_NOTIFICATIONS`—to the merged APK manifest whether or not a project file is
present. SAW-055 adds one non-exported `SeekerVaultMessagingService` for `onRegistered` and
`onUnregistered`; SAW-056 adds its exact-match data-message callback and Sync scheduling. The app
makes no runtime notification-permission request and displays no notification in this revision.
These entries are plumbing, not a claim of guaranteed delivery.

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
  priority, because that event is intended to become a time-sensitive user-visible notification
  when the later notification child lands. State, outcome, confirmation, cancellation, and expiry
  changes use `normal`. Firebase says normal messages may wait while the device is in Doze; high
  priority attempts immediate delivery and limited processing, but should be reserved for
  time-sensitive user-visible notifications. Repeated high-priority traffic that does not produce
  user-visible notifications can be deprioritized. SAW-056 itself displays nothing, so the complete
  Stage 5.3 feature must add the corresponding honest notification before deployment.
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

## Off, unavailable, and incorrectly configured

| Condition | Behavior through SAW-056 |
| --- | --- |
| No Android `google-services.json` | The Google Services plugin is not applied, no default Firebase app exists, and registration calls are no-ops. The app and every Stage 5.2 path still build and run. |
| No sidecar `FCM_PROJECT_ID` | No Firebase Admin app, dispatcher, listener, or sender is constructed. Durable events still commit and feed Stage 5.2 normally. |
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
