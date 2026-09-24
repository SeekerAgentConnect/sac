# SEE-151 — emulator end-to-end against the deployed servers, without a wallet

Executed 2026-09-24, 22:45–23:27 UTC, on the headless emulator against the live DigitalOcean
deployment. No wallet was connected, nothing was signed, and no funds moved at any point.

The general runbook is [`emulator-e2e.md`](emulator-e2e.md); this file records what this run
actually did, what it found, and what it could not reach. Screenshots are in
[`../evidence/see-151/`](../evidence/see-151/).

## 1. Tested build and environment

| Thing | Value |
| --- | --- |
| Branch / commit | `feat/see-151` at `4b6797a` (the SEE-150 commit) |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`, `io.github.brrenat.seekervault` 0.1.0 (versionCode 1) |
| Built with | `scripts/emulator.sh build` → `-Pseekervault.relayUrl=https://seeker-gateway-sg8g3.ondigitalocean.app` |
| minSdk / targetSdk / compileSdk | 31 / 37 / 37 |
| `FIREBASE_CONFIGURED` | **false** — `android/app/google-services.json` is absent (see §5) |
| Emulator image | `system-images;android-35;google_apis;x86_64`, Pixel 6 profile, 1080×2400 @ 420 dpi |
| Guest | Android 15, API 35, `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` |
| Google Play services | present — `com.google.android.gms` and `com.android.vending` are installed |
| Device state | wiped (`-wipe-data`) before the run; the app installed clean after `adb uninstall` |

### Deployed services exercised

| Role | Origin | DO app | Active deployment |
| --- | --- | --- | --- |
| Feed gateway | `https://seeker-gateway-sg8g3.ondigitalocean.app` | `accce337-…` | 2026-09-24 14:06 UTC, image `gateway-0.1.7` |
| Direct MCP (seeker-mcp) | `https://seeker-mcp-r9qqm.ondigitalocean.app` | `70848a26-…` | 2026-09-24 14:47 UTC |
| SKR staking MCP | `https://seeker-skr-staking-mcp-oq7eh.ondigitalocean.app` | `28910149-…` | 2026-09-24 19:19 UTC, image `skr-staking-mcp-0.1.2` |
| CopyTrading demo feed | `https://signals-demo-fzs2q.ondigitalocean.app` | `a7aba189-…` | 2026-09-24 19:55 UTC, image `copytrading-0.1.4` |
| Prediction demo feed ("Polymarket") | `https://prediction-demo-quni7.ondigitalocean.app` | `326326e6-…` | 2026-09-24 19:55 UTC |

Server IDs paired in this run: seeker-mcp `c1e1b23b-d70f-4181-8dc1-53954d5bc274`, staking
`9d7d1ff7-97ac-4ec3-91ba-d211df46e5c2`, CopyTrading feed `b1ffc4d0-f20f-4f31-875b-4a0e0c9694ef`,
Prediction feed `e8d74180-b796-4570-9230-d0e888f6927d`.

**The deployed servers are older than the app under test.** Two checks establish it, and both matter
for what could be verified:

- `FeedService.GetFeedStatus`, added by SEE-150, answers **404** on the deployed gateway, while the
  control RPC `GetServerManifest` answers 200. SEE-150's feed-presence path is therefore not
  deployed.
- The deployed staking server's startup log has no `live updates are served …` / `live updates are
  not served …` line, which SEE-150 added unconditionally. Its start line is only
  `listening on https://seeker-skr-staking-mcp-oq7eh.ondigitalocean.app; MCP at /mcp, pairing page
  at /pair, phone API on the same listener`.

Rebuilding and pushing those images was not possible here: the deployments pull pinned Docker Hub
tags and the registry credentials are DO-encrypted secrets.

## 2. Host bring-up (differs from the general runbook)

`scripts/emulator.sh start` prefers direct KVM and falls back to Docker. On this host the session
user has **no `/dev/kvm` access**, and `docker ps` first answered `permission denied` even though
`/etc/group` lists `superset` in the `docker` group — the login session's supplementary groups were
stale. Running Docker through `sg` picks the group up:

```sh
sg docker -c "scripts/emulator.sh start"
```

Software emulation (`-accel off`) was tried first and is **not viable** for this image: Android's
watchdog kills `system_server` mid-boot (`*** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on
main thread (main) for 68s`) and the guest never finishes booting. With KVM through the container it
boots in under a minute.

To reproduce this run from a clean device:

```sh
sg docker -c "docker rm -f sac-emu"
rm -f ~/.android/avd/sac.avd/hardware-qemu.ini.lock ~/.android/avd/sac.avd/multiinstance.lock
rm -rf ~/.android/avd/running
sg docker -c "docker run -d --name sac-emu --network host --device /dev/kvm \
  --group-add $(getent group kvm | cut -d: -f3) -u $(id -u):$(id -g) \
  -e HOME=$HOME -e ANDROID_HOME=$HOME/android-sdk -e ANDROID_SDK_ROOT=$HOME/android-sdk \
  -v $HOME/android-sdk:$HOME/android-sdk -v $HOME/.android:$HOME/.android \
  sac-emu $HOME/android-sdk/emulator/emulator -avd sac -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -wipe-data -accel on"
scripts/emulator.sh build
adb uninstall io.github.brrenat.seekervault
scripts/emulator.sh install
```

## 3. Commands used to drive the run

```sh
set -a; source ~/.env; set +a

# Pair a direct server (deep link). The code is single-use and lives 10 minutes.
node scripts/mcp-call.mjs https://seeker-mcp-r9qqm.ondigitalocean.app \
  MCP_SEEKER_VAULT_API_KEY call vault_create_pairing_link > /tmp/pair.json
scripts/emulator.sh open-uri "$(node -p 'require("/tmp/pair.json").pairing_uri')"
# then tap the Pair button by coordinate: tap-text "Pair" matches "Pair with this server?" first.

# Add a public feed (manual entry; feeds have no deep link). adb input truncates long strings,
# so the reference goes in three chunks with & escaped.
adb shell input text 'seekervault://feed?v=1'
adb shell input text '\&gateway=https%3A%2F%2Fseeker-gateway-sg8g3.ondigitalocean.app'
adb shell input text '\&server=b1ffc4d0-f20f-4f31-875b-4a0e0c9694ef'

# Real events
node scripts/mcp-call.mjs https://seeker-mcp-r9qqm.ondigitalocean.app MCP_SEEKER_VAULT_API_KEY \
  call vault_request_ack '{"text":"SEE-151 ack A","idempotency_key":"see151-ack-a"}'
scripts/demo-signal.sh create "SEE-151 live arrival A" usdc-sol 1h

# Connectivity and navigation mode
adb shell cmd connectivity airplane-mode enable | disable
adb shell cmd overlay enable  com.android.internal.systemui.navbar.threebutton
adb shell cmd overlay disable com.android.internal.systemui.navbar.gestural

# Measuring insets and a control's real bounds
adb shell dumpsys window | grep "type=navigationBars frame"
adb shell uiautomator dump /sdcard/u.xml && adb shell cat /sdcard/u.xml
```

No credential, pairing token or device token appears in this report or in the evidence images.

## 4. Scenario matrix

Legend: **PASS** executed and correct · **FAIL** executed and wrong · **BLOCKED** could not be
executed, reason given · **N/A** not applicable here.

### 1. Fresh install, login, session lifecycle

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 1.1 | Clean install launches and completes onboarding without a wallet | PASS — no onboarding gate; Home opens directly showing "No wallet connected." | `01-fresh-install-home.png` |
| 1.2 | Login / logout / session restoration | **N/A** — the app has no user account or login. Authentication is per connection: a pairing code issued by the server, exchanged once for a stored phone credential. The only password login in the flow is the demo publisher's own `/trader` admin page, used from the host by `scripts/demo-signal.sh`, not from the phone. | — |
| 1.3 | Wallet absence does not prevent connecting, receiving, reviewing, or non-signing actions | PASS — all four connections paired, events received, one ack completed end to end | §4.3, §4.4 |
| 1.4 | Restart preserves sessions and connections | PASS — after `force-stop` + relaunch all four connections and their pending items persisted | `07-restart-persisted-carousel.png` |

### 2. Pair real servers

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 2.1 | Real pairing links from the supported flow | PASS — `vault_create_pairing_link` and `skr_create_pairing_link`, both carrying `replaces` and the replacement warning | — |
| 2.2 | Pair a regular MCP server | PASS — `c1e1b23b-…`, row "Connected · 0 pending" | `06-four-connections-home.png` |
| 2.3 | Pair the staking MCP server | PASS (pairing itself) — `9d7d1ff7-…`; server logged `phone paired: connection 3425d7a0-…; revoked connection 611499f7-…` | `06-…` |
| 2.4 | Pair a feed connection through the gateway | PASS — CopyTrading and the Prediction feed, both by manual entry | `05-add-feed-confirm.png` |
| 2.5 | Deep link path | PASS — `seekervault://pair?…` opens the confirmation screen | `02-pair-identity-confirm.png` |
| 2.6 | Manual entry path | PASS — feed references typed into **Pairing code** → **Continue** → **Add feed** | `05-…` |
| 2.7 | QR scanning | **BLOCKED** — the **Scan QR code** button needs the camera; `CAMERA` is `granted=false` and the emulator has no usable scene camera in this headless container. The equivalent payload was exercised through the deep link and manual entry instead. | — |
| 2.8 | Server identity and environment shown before confirming | PASS — server URL, server ID, and for feeds the gateway URL and the "public broadcast feed" notice | `02-…`, `05-…` |
| 2.9 | Cancellation | PASS — **Cancel** returns to Add connection and creates nothing ("No paired servers" unchanged) | — |
| 2.10 | Invalid / expired invitation | PASS — reusing a consumed code gives "The server refused the code: it was already used, it expired, or a newer code replaced it." and the Pair button is withdrawn | `04-pair-used-code-error.png` |
| 2.11 | Repeated pairing, no duplicate connection | PASS with a caveat — re-pairing warns "You already have a connection to this server… the server revokes the old one", and afterwards the old connection stays listed as **"Disconnected · pair again to reconnect"** beside the new one. Two rows, one clearly dead; not a silent duplicate. | `22-after-repair-connection-list.png` |
| 2.12 | Connections survive an app restart | PASS | `07-…` |

### 3. Run demos and publish real events

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 3.1 | Non-signing MCP request | PASS — `vault_request_ack` → `83398c6d-a2b1-49de-9658-d84f0eaecd8b` | §4.4 |
| 3.2 | Feed demo events | PASS — four CopyTrading signals published through the demo's own `/trader` admin flow | — |
| 3.3 | Staking MCP request | **BLOCKED** — every staking tool, including the read-only `get_staking_status`, answers `WALLET_NOT_CONNECTED: the owner has no wallet connected on their phone`. The server will not mint a request for a wallet-less phone, so no staking item can reach the app. This is the ticket's own no-wallet constraint meeting the server's precondition, not an infrastructure failure. | — |
| 3.4 | Sandbox swap demo with editable owner inputs and simulation | PASS — the swap review exposes "Amount to swap" and "Most the price may move (basis points)" and a "Get a quote and prepare" step | `13-swap-review-owner-inputs.png` |
| 3.5 | Prediction demo | PASS (read path) — three real Polymarket prediction signals arrived and were reviewable. Its admin publish flow was not exercised; the three markets already published were sufficient as a second live source. | `11-inbox-multi-source.png` |
| 3.6 | Several identifiable events from multiple sources | PASS — CopyTrading, Prediction and seeker-mcp items in one inbox | `11-…` |
| 3.7 | Publish while viewing an existing carousel item | PASS — see 4.6 | — |
| 3.8 | Trace each event from creation to receipt | PASS — e.g. proposal `4df477d7-af4f-41f5-9f46-b14d0a17d805` printed by `demo-signal.sh create` at 22:56:37Z appears verbatim in the app's review sheet under **Proposal**, with publisher `b1ffc4d0-…` | `13-…` |

### 4. Foreground live updates and unified inbox

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 4.1 | Regular MCP events arrive with no manual refresh | PASS — 0 → 1 pending within ~10 s of `vault_request_ack` at 22:55:42Z | — |
| 4.2 | Feed events arrive with no manual refresh | PASS — CopyTrading 0 → 1 within ~12 s; the sheet reads "Live updates connected." | `10-live-arrival-multisource.png` |
| 4.3 | Staking MCP events arrive | **BLOCKED** — no staking request can be created (3.3). Separately, the deployed staking image advertises no update capability, and the app says so honestly: "Live updates are not configured. Refresh still works." | `08-staking-sheet-live-updates-not-configured.png` |
| 4.4 | Pending counts, source labels, ordering, environment indicators | PASS — one list, newest first, each item labelled with its source and "Sandbox · no funds will move" or "Production" | `11-…` |
| 4.5 | Connection-specific filtering | PASS — **Its inbox** on the CopyTrading sheet showed only its two signals | `18-connection-filtered-inbox.png` |
| 4.6 | New events do not reset the carousel, blink, or duplicate | PASS — with the second card centred, publishing a new signal moved the count 4 → 5 and left the centred card in place; the inbox held five distinct items and no duplicates | `15-inbox-bottom-no-duplicates.png` |
| 4.7 | History | PASS — the acknowledged request appears as "Acknowledged · 10:57 PM" at the top; expired feed signals below it | `16-history-acknowledged.png` |
| 4.8 | Non-signing action, state verified at the owning server | PASS — acknowledged in-app at 22:57:41; `vault_get_request` returns `status: COMPLETED`, `updated_at 2026-09-24T22:57:41.965Z` | `12-review-sheet-ack-bottom.png` |
| 4.9 | Feed dismissal is local, server state untouched | PASS — "You hid this" removed it from Pending while `demo-signal.sh list` still showed the signal `open` on the server | — |
| 4.10 | Wallet-required boundary | PASS — "Get a quote and prepare" stops at "Nothing was prepared / Connect a wallet before preparing a swap." Server side, `vault_get_capabilities` reports `wallet_connected: false` and `vault_get_address` answers `WALLET_NOT_CONNECTED`; `vault_sign_message` and `vault_transfer` cannot even be constructed, since they require a `wallet` argument the phone has never published. | `14-wallet-boundary-nothing-prepared.png` |
| 4.11 | Manual refresh remains functional | PASS — opening a connection's sheet performs an on-demand check and updates its "Checked …" timestamp; the row's **Retry** control is present whenever a connection is unreachable | `08-…` |

### 5. Real FCM delivery and notification navigation

**BLOCKED in full.** The tested build has no Firebase project file, so Firebase Messaging cannot
obtain a token and the app registers no notification channel. On-device proof:

```
FirebaseApp: Default FirebaseApp failed to initialize because no default options were found.
FirebaseInitProvider: FirebaseApp initialization unsuccessful
```

```
dumpsys notification → AppSettings: io.github.brrenat.seekervault importance=NONE userSet=false
dumpsys package      → android.permission.POST_NOTIFICATIONS: granted=false
```

`android/app/google-services.json` is gitignored by design (`docs/guides/firebase.md`) and is not
present on this host; there is no Firebase credential, no `gcloud` and no `firebase` CLI here, so the
file cannot be produced. The emulator image itself is not the obstacle — Google Play services and
the Play Store are both installed.

| # | Check | Result |
| --- | --- | --- |
| 5.1 | Background FCM delivery, notification presentation, inbox sync | BLOCKED — no Firebase configuration in the build |
| 5.2 | Delivery after process death / task removal, vs force-stop | BLOCKED — same |
| 5.3 | Feed events via the gateway relay | BLOCKED client-side. The gateway *is* configured to send: `BROADCAST_PUSH_ENDPOINT=https://fcm.googleapis.com`, `BROADCAST_PUSH_ENVIRONMENT=sandbox`, `BROADCAST_PUSH_CREDENTIALS` set. The phone cannot register a target. |
| 5.4 | Direct MCP push via the relay | BLOCKED client-side. seeker-mcp has `RELAY_URL`, `RELAY_SERVER_ID` and `RELAY_CREDENTIAL` set. |
| 5.5 | Staking MCP push via the relay | BLOCKED twice over — the phone cannot register, *and* the deployed staking app sets none of `SKR_STAKING_RELAY_URL` / `_SERVER_ID` / `_CREDENTIAL`, so the relay is not wired there at all. |
| 5.6 | Tap a notification from warm and cold states | BLOCKED — no notification can be posted |
| 5.7 | Permission denial then enabling | BLOCKED — the app never requests `POST_NOTIFICATIONS`, because the channel is only created when the project file was present at build time |
| 5.8 | Same event via push and live updates yields one item, one notification | BLOCKED — only the live path exists in this build. The live path alone produced no duplicates (4.6). |

To unblock: register an Android app for package `io.github.brrenat.seekervault` in the Firebase
project whose service account the gateway already holds, put its `google-services.json` at
`android/app/google-services.json`, rebuild, and re-run scenario 5.

What *was* verified without FCM: the **in-app** notification banner is presented on a live arrival
and clears the status bar (see 7.1).

### 6. Recovery, connectivity, persistence

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 6.1 | Networking off reports accurate state | PASS — all four rows became "Couldn't reach the server" | `19-offline-all-unreachable.png` |
| 6.2 | Automatic stream recovery after networking returns | **FAIL** for direct MCP connections, PASS for feeds. Filed as **SEE-152**. | §5 below |
| 6.3 | Events published during the interruption synchronise on reconnect | **PARTIAL** — the feed signal `ffb15ac3-…` published at 23:07:53Z arrived after reconnect with no duplicate. The MCP request `440de538-…` published at 23:07:52Z never arrived and remained `PENDING`; it was ultimately `CANCELLED` by the re-pair that was the only way to recover the connection. Covered by SEE-152. | — |
| 6.4 | Gateway-online / feed-offline distinction | **BLOCKED** — SEE-150's `FeedService.GetFeedStatus` answers 404 on the deployed gateway (§1), so the app can never read a feed's availability against this deployment. Per SEE-150's own design an unreadable availability is *unknown, never online*, and the row keeps the ordinary line — which is what was observed, so the app side behaves as specified. Verifying the "Feed offline · N pending" line needs a gateway built from SEE-150. Stopping a shared demo publisher to force it was out of bounds. | — |
| 6.5 | Restart keeps pending items, history and connection state consistent | PASS | `07-…` |
| 6.6 | Unpair stops new items | PASS — after removing CopyTrading, signal `c51f86b0-…` published at 23:21:14Z did not arrive | — |
| 6.7 | Pair again successfully | PASS — re-adding the feed synced the signal published while it was unpaired | `23-inbox-final.png` |
| 6.8 | Removal leaves no stale state | **FAIL** — the removed connection's already-received items stayed in the Inbox and in Home's "Waiting for you" count. Filed as **SEE-154** (low confidence: may be deliberate). | — |

### 7. UI checks, including the SEE-150 regressions

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 7.1 | In-app notification top spacing (SEE-150) | PASS — the banner is inset below the status bar with a gap, and clears the cutout area | `07-restart-persisted-carousel.png` |
| 7.2 | Opened-sheet bottom spacing, gesture navigation (SEE-150) | PASS — navigationBars inset starts at y=2337; the sheet's last action ends at y=2306, clear by 31 px | `09-sheet-bottom-spacing-gesture-nav.png` |
| 7.3 | Opened-sheet bottom spacing, three-button navigation (SEE-150) | **FAIL** — the inset starts at y=2274 while the same action still ends at y=2306, so 32 px of it sit under the bar. The control's bounds are byte-identical in both modes: the padding does not follow the inset. Filed as **SEE-153**. | `20-…`, `21-…` |
| 7.4 | Long content, scrolling, final actions reachable | PASS under gesture navigation — long review sheets (a swap proposal with a dozen fields) scroll to their end and the final action is reachable. Under three-button navigation the final action is partly obscured (7.3). | `13-…` |
| 7.5 | Feed online/offline vs gateway (SEE-150) | BLOCKED — see 6.4 | — |
| 7.6 | Staking live updates / FCM (SEE-150) | BLOCKED — see 3.3, 4.3, 5.5 | — |

### Totals

23 PASS · 4 FAIL · 13 BLOCKED · 1 N/A.

## 5. Defects filed

| Ticket | Severity | Summary |
| --- | --- | --- |
| [SEE-152](https://linear.app/seekeragentwallet/issue/SEE-152) | High | A direct MCP connection never recovers its update stream after a network interruption. The row stays "Couldn't reach the server" while unary calls from the same phone still succeed; **Retry** and app restarts do not help; only re-pairing recovers it, and that cancels the requests the server was holding. |
| [SEE-153](https://linear.app/seekeragentwallet/issue/SEE-153) | Medium | An opened sheet's last action sits under the three-button navigation bar. SEE-150's bottom-spacing fix clears the gesture inset only. |
| [SEE-154](https://linear.app/seekeragentwallet/issue/SEE-154) | Low | Removing a connection leaves its pending items counted on Home and listed in the Inbox. |

No existing ticket covered any of the three; the Linear backlog was searched first.

## 6. Correlated event log

Times are UTC. Every identifier below is a server-side object, not a credential.

| Time | Event | Where it came from | Where it landed |
| --- | --- | --- | --- |
| 22:47:56 | phone paired, seeker-mcp | `vault_create_pairing_link` | connection `41d81faf-…`, replacing `d089cb88-…` |
| 22:49:38 | phone paired, staking | `skr_create_pairing_link` | connection `3425d7a0-…`, replacing `611499f7-…` |
| 22:55:42.636 | request `83398c6d-…` stored (ack) | `vault_request_ack` | Home 0 → 1 pending within ~10 s |
| 22:56:37 | proposal `4df477d7-…` published | `demo-signal.sh create` | CopyTrading 0 → 1 pending within ~12 s |
| 22:57:41.965 | request `83398c6d-…` → `COMPLETED` | **Acknowledge** in the app | `vault_get_request` confirms |
| 23:00:26 | proposal `0e30e40c-…` published | `demo-signal.sh create` | count 4 → 5, carousel position unchanged |
| 23:06:22 | update stream closed for `41d81faf-…` | airplane mode on at 23:06:29 | all rows → unreachable |
| 23:07:52.720 | request `440de538-…` stored (ack) | published during the outage | **never delivered**; `PENDING` |
| 23:07:53 | proposal `ffb15ac3-…` published | published during the outage | delivered after reconnect |
| 23:07:57 | airplane mode off | — | feeds recover in ~35 s; direct MCP does not |
| 23:13:32 | manual **Retry** on the MCP row | app | still unreachable |
| 23:14:17 | app force-stopped and relaunched | app | still unreachable |
| 23:14:22 | "the phone has no wallet connected" for `41d81faf-…` | phone → server | proves the server was reachable throughout |
| 23:20:43 | CopyTrading connection removed | app | stops receiving; items retained (SEE-154) |
| 23:21:14 | proposal `c51f86b0-…` published | while unpaired | correctly not delivered |
| 23:23:05 | CopyTrading feed re-added | app | `c51f86b0-…` synced |
| 23:24:37.563 | phone re-paired, seeker-mcp | `vault_create_pairing_link` | connection `1d551dc2-…`; `41d81faf-…` revoked and `440de538-…` → `CANCELLED` |
| 23:25:40.670 | request `6fa66897-…` stored (ack) | `vault_request_ack` | delivered; direct path working again |

## 7. Clean-up

All test-created resources were released:

- Requests: `83398c6d-…` answered (COMPLETED), `6fa66897-…` cancelled, `440de538-…` cancelled by the
  server during the re-pair.
- Signals: `4df477d7`, `0e30e40c`, `ffb15ac3`, `c51f86b0` cancelled, plus the two stale signals
  `d4a217af` and `3985937e` left by an earlier session.
- No shared deployed service was stopped, restarted or reconfigured at any point.

Pairing the emulator did replace the phone previously paired on both direct servers, which
`emulator-e2e.md` records as approved by the owner for the emulator; both tools reported it in
advance through their `replaces` and `warning` fields.
