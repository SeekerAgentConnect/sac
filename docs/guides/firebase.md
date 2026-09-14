# Optional Firebase Cloud Messaging setup

SAW-054 prepares the Android Firebase client and the self-hosted sidecar's Firebase Admin sender.
Both are optional. This ticket does **not** register a phone token, send an invalidation, define an
app message handler, request runtime notification permission, or show a notification; those are
the following Stage 5.3 tickets. Adding or removing this setup therefore does not change the Stage 5.2 foreground
stream, manual **Refresh**, unary Sync, or periodic WorkManager recovery.

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
credentials for that project. Connection-scoped token ownership and rotation arrive in SAW-055;
SAW-054 stores no device token anywhere.

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

The source manifest also sets `firebase_messaging_auto_init_enabled=false`. Leave that safeguard in
place for SAW-054; SAW-055 owns enabling token creation together with authenticated registration,
rotation, revocation, and deletion.

The Firebase Messaging library contributes its normal receiver, service, provider, and permission
entries—including `POST_NOTIFICATIONS`—to the merged APK manifest whether or not a project file is
present. SAW-054 defines no app handler and makes no runtime notification-permission request;
Messaging auto-init remains false and there is no token lifecycle. These dormant library entries
are dependency plumbing, not a claim that notification delivery exists in this revision.

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

## Off, unavailable, and incorrectly configured

| Condition | Behavior in SAW-054 |
| --- | --- |
| No Android `google-services.json` | The Google Services plugin is not applied. The app and every Stage 5.2 path still build and run. |
| No sidecar `FCM_PROJECT_ID` | No Firebase Admin app or sender is constructed. The sidecar serves its existing MCP, phone, stream, Sync, and WorkManager-facing paths unchanged. |
| Invalid `FCM_PROJECT_ID` | Configuration fails with the variable name and format requirement, never a credential value. Unset the variable to return to the fully functional Firebase-off mode. |
| Missing, expired, or unauthorized ADC; FCM outage; quota/throttling response | The first future send will fail. SAW-054 has no send caller yet. Later push dispatch must remain best-effort; the durable request and Stage 5.2 recovery paths remain authoritative. |
| Android and sidecar use different Firebase projects | Future sends will be rejected for the target rather than changing request state. Correct both deployment configurations; never copy a token into logs while diagnosing it. |

Turning FCM off never requires deleting a paired connection or the Stage 5.2 cache. Remove the
Android project file for the next APK, unset `FCM_PROJECT_ID` for the sidecar, and restart/rebuild as
appropriate. A phone on an unconfigured build continues using foreground streams, manual Refresh,
and the periodic worker. WorkManager remains eventual, and neither it nor later FCM delivery can
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
