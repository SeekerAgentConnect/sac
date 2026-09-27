# Optional Firebase Cloud Messaging setup

SAW-054 prepares the optional Android Firebase client and self-hosted sidecar sender. SAW-055 binds
the client's current direct-send registration to paired connections: initial registration and
later refreshes are sent to each sidecar through that connection's authenticated phone API.
SAW-056 sends a content-free invalidation after committed request changes. SAW-057 keeps receipt
inside the Firebase callback budget, deduplicates the handoff, and schedules only the connections
that need the existing authoritative Sync path. SAW-058 adds one private request channel, an
isolated runtime notification-permission request, and a read-only tap route which fetches current
state before showing review controls. SEE-92 extends the same pipeline to a
publisher's public feed, with a relay in the shared feed gateway and per-feed topics
([below](#the-broadcast-relay-and-feed-topics-see-92)). Adding or removing Firebase, or denying
notification permission, does not change the Stage 5.2 foreground stream, manual **Refresh**, unary
Sync, periodic WorkManager recovery, or Stage 7.1's own feed stream.

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
3. Download `google-services.json` and place it at `apps/android/app/google-services.json`. Do not commit
   it; `.gitignore` excludes every file with that name.
4. Build the app with `pnpm check:android` or from Android Studio.

The app pins Firebase through the Android BoM and uses the main `firebase-messaging` module, not the
retired KTX artifact. The Google Services Gradle plugin is applied only when
`apps/android/app/google-services.json` exists. Without that file the same debug and test APK tasks build,
there is no default Firebase project configuration, and Firebase Messaging cannot obtain a token.
Delete the file and rebuild to produce an unconfigured installation again.

The source manifest keeps `firebase_messaging_auto_init_enabled=false` as its safe default and sets
`firebase_messaging_installation_id_enabled=true` for the current direct-send API. After stored
connections load, the app explicitly calls Firebase Messaging `register()` only while at least one
connection remains usable. Firebase reports the current Firebase Installation ID through
`onRegistered`; it calls that callback for the initial value and again when registration is
refreshed. The app serializes callbacks, atomically replaces the old value on every sidecar, and
does not write the value to disk. A failed sidecar publication is retried independently after 5,
10, and 20 seconds; successful sidecars are not repeated by that retry. The retry is deliberately
bounded because registration is optional and the existing Stage 5.2 paths remain authoritative.
After the last usable connection disappears the app calls `unregister()` and returns auto-init to
false.

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
- Removing or revoking a local connection cancels all of its posted request alerts immediately;
  cleanup does not wait for another FCM message or WorkManager run.
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
pnpm dev:mcp-server
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
  corresponding type-aware native alert when Android permission and the channel allow presentation.
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
pending key gets one alert; a key that left PENDING has its earlier alert canceled. SEE-105 gives
that native alert a dedicated monochrome SAC status icon, the lime app icon as large art where the
OS supports it, an approved system-theme-aware accent, a human title for the cached request kind,
the owner's local connection name, and a concise type-specific summary. Expanding it shows the
complete source and summary through Android's native `BigTextStyle`, so Android still owns
typography, wrapping, truncation and light/dark surfaces. Feed alerts use the same presentation.
The manifest configures the same icon and accent as Firebase's defaults, although production hints
remain data-only and Firebase renders no notification for them.

Presentation is derived only after authoritative state is present locally. It contains no request
text, agent or publisher note, amount, recipient, policy, credential, authorization, transaction,
signature, answer, proposal term or technical identifier, and its lock-screen visibility remains
secret. Distinct items retain distinct immutable, explicit intents containing only the connection
and request/proposal IDs needed to route inside this app.

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

## Validate one deployment (SAW-059)

Use the [Stage 5.3 Seeker runbook](../testing/stage-5-3.md#physical-seeker-runbook-saw-059) after
both halves above are configured. Record the exact Git revision, APK variant, device model/build,
Firebase project alias (never a credential), sidecar host/revision, permission/channel state, and
observed timestamps. Mark every check **PASS**, **FAIL**, or **NOT RUN**. An injected sender,
Robolectric, emulator, successful Admin `send()` response, or assembled APK does not prove physical
delivery.

The repeatable automated preflight uses no real Firebase credentials:

```bash
pnpm test:push
pnpm test:updates
```

`test:push` exercises target ownership/rotation/revocation and exact invalidations at the real
sidecar APIs, then real two-sidecar MCP requests and production Android HTTP/2 Sync under delayed,
dropped, duplicate, and process-reloaded timing. `test:updates` is the Firebase-off Stage 5.2
control. Before attaching the device, also confirm `git status --short` does not list
`google-services.json`, a service-account file, `.env`, `local.properties`, a database, or a
keystore.

For the physical run, keep sidecar logs at their normal redacted level. They may say that FCM is
configured, that a connection's registration changed, or that delivery is unavailable; they must
not print a target, bearer credential, ADC path/content, request body, or Firebase error text. Use
an ordinary agent request to trigger the flow, and inspect request state from the agent and app
rather than copying Firebase routing values into diagnostics.

## Off, unavailable, and incorrectly configured

| Condition | Behavior through SAW-058 |
| --- | --- |
| No Android `google-services.json` | The Google Services plugin is not applied, no default Firebase app exists, registration calls are no-ops, and the app creates no request channel, permission prompt, or notification. Every Stage 5.2 path still builds and runs. |
| No sidecar `FCM_PROJECT_ID` | No Firebase Admin app, dispatcher, listener, or sender is constructed. Durable events still commit and feed Stage 5.2 normally. |
| Notification permission denied, or the channel disabled | Alerts are absent. Push may still trigger authoritative Sync, and foreground streams, manual Refresh, unary Sync, and periodic recovery are unchanged. Enable the permission/channel in Android Settings if alerts are wanted. |
| Invalid `FCM_PROJECT_ID` | Configuration fails with the variable name and format requirement, never a credential value. Unset the variable to return to the fully functional Firebase-off mode. |
| Missing, expired, or unauthorized ADC; FCM outage; quota/throttling response | The best-effort send fails after the durable change has committed. Fixed logs name only `delivery unavailable`; the target and request stay unchanged, and Stage 5.2 recovery remains authoritative. |
| Android and sidecar use different Firebase projects | Sends are rejected for the target rather than changing request state. Correct both deployment configurations; never copy a target into logs while diagnosing it. |
| Old sidecar without `SetFcmToken`, or sidecar unavailable during registration | Other sidecars continue independently. The failed connection gets three bounded in-process retries after 5, 10, and 20 seconds; a later registration refresh or usable-connection change also republishes idempotently. If it remains unavailable, foreground Subscribe, manual Refresh, unary Sync, and periodic recovery keep working. |
| Firebase invalidates or unregisters an old value after rotation | Phone unregistration and a permanent send rejection both compare-clear the exact rejected value. A newer stored value remains untouched. |

Turning FCM off never requires deleting a paired connection or the Stage 5.2 cache. Remove the
Android project file for the next APK, unset `FCM_PROJECT_ID` for the sidecar, and restart/rebuild as
appropriate. A phone on an unconfigured build continues using foreground streams, manual Refresh,
and the periodic worker. WorkManager remains eventual, and neither it nor FCM delivery can
bypass Android Settings **Force stop**; reopen the app after a force-stop.

## The broadcast relay and feed topics (SEE-92)

Stage 7.1 adds a second kind of server — a developer's publisher, broadcasting to everyone
subscribed through the shared gateway — and a second kind of message about it. Everything above
stays exactly as it is: the direct path's registration, its `fid`-addressed invalidation, its Sync
and its request alerts are untouched, and a deployment can run either half, both, or neither.

**Who sends.** The feed gateway, not the publisher. (It also relays for *direct* servers that hold
no credential of their own, which is a different message to a different kind of address:
[the direct-server push relay](#the-direct-server-push-relay-see-144).) The publisher publishes a document to the
gateway as it already did; the gateway commits it and then sends one message to that feed's topic.
A publisher is given no Firebase credential, cannot name a topic, and never learns that any phone
received anything.

**What is sent.** The whole payload:

```text
kind=feed_invalidation
version=1
```

It is beside SAW-056's `request_invalidation`, deliberately a different kind: the two are about
different servers and reach different state, and each is matched whole so a message with an extra
field is ignored rather than partly trusted. Which feed changed is the **topic**, which is a
routing field — the same distinction this guide already makes for a target.

**Where it is sent.** `feed.<environment>.<server_id>`, and the phone is *told* the name rather
than working it out: `FeedService.GetFeedTopics` answers it for the channels a phone holds feed
references for. A name derived on both sides would drift into silence rather than into an error. The
environment (`production` or `sandbox`) scopes every topic so one Firebase project can serve both
kinds of deployment.

A topic name is public and proves nothing. Do not treat one as evidence of access: what arrives on
it is the news that a public broadcast changed, with no document in it at all.

### Configure the relay

One Firebase project for the whole deployment — the same project whose `google-services.json` the
APK was built with, or the messages will be sent to topics no phone is subscribed to. Then, in
`deploy/feed/.env`:

```bash
BROADCAST_PUSH_CREDENTIALS_FILE=/run/secrets/seeker-broadcast-fcm.json
BROADCAST_PUSH_ENVIRONMENT=production
docker compose --env-file deploy/feed/.env -f deploy/feed/compose.yaml \
  -f deploy/feed/compose.push.yaml up -d --build
```

The credential is a service-account JSON for that project, with permission to send (Firebase's
**Firebase Cloud Messaging API Admin** role, as above). Unlike the sidecar's, it is **not**
discovered through Application Default Credentials: the path is named, the file is mounted
read-only into the gateway's container alone, and the gateway reads it once at startup. A missing or
malformed credential stops the process with a message that names the field and no part of its
contents.

The gateway's own image carries a CA bundle from SEE-92 onwards, because this is its first outbound
TLS connection (`services/gateway/Dockerfile` says so in a comment beside the line that copies it).

### What the phone does with a hint

1. The messaging service matches the payload whole and hands the topic to the one component that
   has a use for it.
2. It enqueues one unique WorkManager job with an **empty input** — no topic, no channel, no
   proposal ID is written into WorkManager's database.
3. The job reads every feed this phone holds that is not already live on a foreground stream,
   through the gateway's unary API, with the boundary each feed was last read at (so an unchanged
   feed costs one small answer).
4. Proposals that are newly waiting for the owner get one type-aware native alert on their own
   channel, **Proposals waiting for review**, and the tap opens the feed they are on. Nothing is
   prepared, signed or sent by a notification or by a hint.

Because the read covers every feed, two hints are one read and a hint that Firebase replaced under
its collapse key loses nothing.

### Topic membership

The app subscribes when the owner adds a feed and unsubscribes when they remove one, and it keeps no
list: the connection list is the truth and the subscriptions are derived from it. A registration
refresh re-subscribes, because topic membership belongs to the installation.

One case cannot be derived: a feed removed while the app was not running leaves a subscription
behind. A hint that arrives on a topic no feed wants is unsubscribed from, so it removes itself the
first time it costs anything. A removed feed can never be re-added, re-enabled or acted on by a
message: nothing in a hint names a feed, and the read only ever reads connections the owner still
has.

### Off, unavailable, or misconfigured

| Condition | Behaviour |
| --- | --- |
| No `BROADCAST_PUSH_CREDENTIALS` | The gateway relays nothing and says so at startup. `GetFeedTopics` answers `NO_PUSH`, the phone subscribes to nothing, and the foreground stream and the owner's own reads are unaffected. |
| No Android `google-services.json` | No default Firebase app, so `subscribeToTopic` is a no-op and no channel, prompt or alert exists. Every other path is unchanged. |
| A credential that cannot send, or an FCM outage | The hint is logged as not delivered, by classification only, and the notice is **not** deferred: the document is stored and the stream already carried it. |
| A publisher publishing faster than the quota | Hints are dropped, not queued. Every document is still stored, still streamed and still read on the next hint or glance. |
| Notification permission denied | Hints still cause a read; nothing is displayed. |
| The owner force-stops the app | Nothing is delivered and no job runs until they open it again — the same limitation SAW-059 records for the private path, and it applies to a topic message too. |
| Two deployments, one Firebase project | Give them different `BROADCAST_PUSH_ENVIRONMENT` values. Without that, the same publisher ID in both would be the same topic. |

## The direct-server push relay (SEE-144)

Stage 7.2 adds a third sender of a message that already exists. A server one owner pairs with
directly — hosted by that developer, not by the gateway's operator — has no Firebase project, so
the gateway sends `request_invalidation` on its behalf. The message is byte for byte the one the
sidecar sends above, because a phone matches the payload whole and must not have to tell the two
senders apart.

**Who sends.** The gateway, with this deployment's own credential. The developer's server holds a
scoped relay credential that can do exactly one thing: ask that a device which has already
authorized it be told to go and read that server.

**Who is addressed.** One device, by the `fid` a phone registered with this gateway — the same
opaque Firebase installation the sidecar's own sender addresses, and verified against the installed
Admin SDK rather than assumed: `fid`, `token`, `topic` and `condition` are four alternative target
fields of one v1 message, and whichever is set is passed through to `messages:send` unchanged. A
relay handle is **not** one of them. It addresses an authorization at this gateway, not a device,
and it is never placed in a target field.

**What is sent.** Exactly [SAW-056's payload](#invalidation-delivery-saw-056), under the same
collapse key, with the same five-minute expiry. Nothing in the relay's request can reach it: the
caller names a handle and one of two words, and the gateway builds the rest from constants.

### Configure it

Nothing new. The relay's device dispatch is the same credential, the same project and the same
access-token cache as [the feed relay above](#the-broadcast-relay-and-feed-topics-see-92) — one
Firebase project per deployment, and two token caches would be one grant minted twice. Setting
`BROADCAST_PUSH_CREDENTIALS` turns both on.

What is configurable is what it may cost:

```bash
# Sends per second per registered server, and the burst above it.
BROADCAST_RELAY_SERVER_RATE=2
BROADCAST_RELAY_SERVER_BURST=20
# Sends per second per device authorization. This one is the coalescing rather than an abuse
# bound: above it a caller is told the device is already being woken.
BROADCAST_RELAY_DEVICE_RATE=0.5
BROADCAST_RELAY_DEVICE_BURST=5
# The whole deployment's ceiling.
BROADCAST_RELAY_GLOBAL_RATE=50
BROADCAST_RELAY_GLOBAL_BURST=200
# Enrollment calls per second per caller address. Enrolling needs no credential, so this is what
# bounds it.
BROADCAST_RELAY_ENROLL_RATE=1
BROADCAST_RELAY_ENROLL_BURST=10
# How long one authorization lasts before a phone renews it, how long an installation may go
# unauthenticated before it is forgotten, and how long an enrollment that authorized nothing is
# kept. All three are what stop an abandoned grant from being permanent.
BROADCAST_RELAY_BINDING_HOURS=720
BROADCAST_RELAY_IDLE_HOURS=1440
BROADCAST_RELAY_UNBOUND_HOURS=24
```

Every one has a modest default and none has to be set. An installation must outlive the
authorizations it holds, so `BROADCAST_RELAY_IDLE_HOURS` below `BROADCAST_RELAY_BINDING_HOURS` is
refused at startup rather than discovered as devices that quietly stop being woken.

A deployment with no Firebase credential still serves the phone-facing half: a phone may enroll and
authorize, because neither sends anything, and a server that then asks for a wake-up is told the
relay cannot send right now — which is true, and is worth retrying.

### Where each half lives

The relay is two routes on two listeners, because its two callers are different parties:

| | Listener | Who calls it |
| --- | --- | --- |
| `POST /relay/v1/installations`, and the routes under it | The **read** listener, which phones already read feeds from | The app |
| `POST /relay/v1/notify` | The **publisher** listener, which developers already publish to | A developer's backend |

Neither route exists on the other's listener, so a routing mistake cannot let a phone send an
invalidation or a server enroll an installation. A deployment that keeps the publisher listener off
the internet keeps both halves of its publishing surface and both halves of its relay's send.

### Configure Android for it

The app registers with **one** relay: the origin its build was given.

```sh
apps/android/gradlew -p apps/android :app:assembleDebug \
  -Pseekervault.relayUrl=https://feeds.example.com
```

Empty by default, and empty means the app enrolls with no relay at all — every other push path is
unchanged. A server may *advertise* a relay over its authenticated connection, and the app ignores
the advertisement unless it names exactly this origin. That rule is what stops an advertisement
from being a way to collect device registrations: a server naming an address of its own gets
nothing, because nothing is sent there.

### What the gateway learns, and what it does not

It holds an installation identity it minted, the SHA-256 of the secret that proves a device is that
installation, that device's current FCM registration, and which registered servers the device
authorized. Delivery needs the registration, so it is the one value here that cannot be a hash — it
is never returned by any read, never rendered on an admin page and never written to a log.

It holds no request, approval, signature, result, wallet, amount or anything an owner decided, and
there is no column that could carry one; a boundary test pins the whole schema so a new one would
have to be argued for by name. What it learns from a send is that a registered server had something
for one of the devices that authorized it, and when — not what it was.

### Ownership, again, and why it is a secret

[Registration ownership](#registration-ownership-and-cleanup) above is about a paired sidecar. The
relay has the same problem and answers it the same way, for a reason worth stating: a device's FCM
registration is **not** a secret. The server it paired with holds one, and so does anyone who ever
saw one. So knowing a registration grants nothing here — enrolling mints a secret the gateway keeps
only as a hash, and every call that can change where a device's wake-ups go proves ownership with
it, inside the transaction that writes.

A registration the endpoint rejects as permanently invalid is cleared, and only while it is still
the one that failed: a phone that rotated while a send was in flight has already registered the new
one, and clearing unconditionally would unregister a device that had just registered. The
authorization survives — what is gone is somewhere to send — and the phone registers again on its
own.

The operator's walkthrough for a developer is
[`docs/guides/server-development.md#17-waking-a-phone-from-a-server-you-host-yourself`](server-development.md#17-waking-a-phone-from-a-server-you-host-yourself).

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
- [Topic messaging on Android](https://firebase.google.com/docs/cloud-messaging/android/topic-messaging)
- [Send messages to topics (server)](https://firebase.google.com/docs/cloud-messaging/send-message#send-messages-to-topics)
- [The FCM HTTP v1 send API](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send)
- [OAuth 2.0 for service accounts](https://developers.google.com/identity/protocols/oauth2/service-account)
