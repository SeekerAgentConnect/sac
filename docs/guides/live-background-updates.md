# Live and background updates on a Seeker

This is the repeatable MacBook → physical Seeker runbook for Stage 5.2. It starts the sidecar's real production `UpdateService`, pairs the phone, creates durable requests through Hermes, and checks foreground streaming and eventual WorkManager synchronization. The Stage 1 **Live test** is a separate diagnostic and is not evidence for this runbook.

Stage 5.2 has no push transport. Foreground changes normally arrive at once over one authenticated gRPC/HTTP/2 stream per usable sidecar. In the background, Android may eventually run a network-constrained periodic unary sync. The 15-minute setting is a minimum interval, not a deadline: Doze, standby, battery policy, network state, and Force stop can delay or suppress it. SAW-054 opened [Stage 5.3](https://linear.app/seekeragentwallet/issue/SEE-73) with optional Firebase configuration, SAW-055 added authenticated registration, SAW-056 added a best-effort content-free invalidation, SAW-057 gives receipt a bounded WorkManager handoff, and SAW-058 can present a newly synchronized request through one private notification channel. Push and periodic work leave healthy foreground streams active and share the same per-connection Sync coordinator. A hint is collapsible, can be delayed or dropped, and cannot bypass Force stop. Notification denial changes presentation only, and every tap fetches current sidecar state before showing review controls. See the [Firebase setup, delivery, and notification guide](firebase.md#notifications-and-tap-to-open-saw-058).

## 1. Build and install

Complete Parts 1–4 of the [MacBook → Seeker quickstart](macbook-seeker-quickstart.md): install the pinned tools, clone the repository, run `pnpm install --frozen-lockfile`, enable USB debugging, build, and install the debug APK. Verify the target before continuing:

```bash
node --version
pnpm --version
adb devices -l
adb shell getprop ro.product.brand
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Record the git revision with `git rev-parse HEAD`. A device row is a Seeker pass only when the brand/model identify the physical Seeker; an emulator, another phone, or a successful APK build does not count.

## 2. Configure the two development listeners

Create the ignored development configuration if it does not exist:

```bash
cp .env.example .env
```

Set different random values for `MCP_TOKEN` and `PHONE_TOKEN`, leave `SIDECAR_HOST=127.0.0.1` and `SIDECAR_PORT=8080`, and set:

```dotenv
SIDECAR_UPDATE_PORT=8081
```

`8080` remains the HTTP/1.1 MCP, pairing, and request endpoint. `8081` is the loopback-only h2c listener for production updates. Do not expose `8081` to a LAN or the internet. A remote deployment instead uses one publicly trusted TLS origin with the PEM settings in the [sidecar guide](../development/mcp-server.md#production-update-listener); that listener negotiates HTTP/2 and HTTP/1.1 with ALPN.

Forward both loopback ports over USB. Re-run these commands whenever adb or the cable reconnects:

```bash
adb reverse tcp:8080 tcp:8080
adb reverse tcp:8081 tcp:8081
adb reverse --list
```

Start the sidecar in a terminal and leave it open:

```bash
pnpm dev:mcp-server
```

Its startup must name both endpoints, including:

```text
[sidecar] listening on http://127.0.0.1:8080: ...
[sidecar] production updates are served as gRPC over HTTP/2 at http://127.0.0.1:8081
```

If it says `production updates are not configured`, stop it, set `SIDECAR_UPDATE_PORT`, and restart. `curl -s http://127.0.0.1:8080/healthz` checks only the main listener; it does not prove the HTTP/2 stream.

## 3. Pair and establish the foreground stream

Run `pnpm pair`, then pair from **Connections → Add connection** by scanning the QR code or entering the complete `seekervault://pair?...` line. The full pairing procedure is in [Pairing](pairing.md).

Leave the app visible on **Connections**. The connection should move through **Connecting for live updates…** to **Live updates connected.** and show **Last synced** separately. The sidecar prints:

```text
[sidecar] update stream opened for connection <connection-id>
```

Only one such stream should be open for that connection. Move between Home, Requests, Activity, and connection details, then rotate several times. Those screen operations must not add another open stream. Press Home and expect one `update stream closed` line; return to the app and expect one new open line after reconciliation.

For a TLS deployment, confirm the endpoint negotiates HTTP/2 from the Mac without printing any credential:

```bash
openssl s_client -connect vault.example.com:8443 -servername vault.example.com -alpn h2 </dev/null 2>/dev/null | rg 'ALPN protocol'
```

The result must be `ALPN protocol: h2`. Never pass the phone credential to `curl`, `openssl`, a log command, or a shell history entry.

## 4. Create real foreground updates with Hermes

Configure Hermes through the [Hermes guide](../integrations/hermes.md), enable verbose tool output, and start a fresh session. With the app still visible, ask Hermes to call `vault_request_ack` with a unique idempotency key. Record the real `request_id` from the tool output.

The request should appear on Home and Requests without navigation or **Refresh**, and the connection's pending count should increase. Open it, then have Hermes call `vault_cancel_request` for that ID; the row and count should update without Refresh. Create another request, answer it on the phone, and have Hermes read the terminal result with `vault_get_request`. Repeat once while Activity is open and verify the existing record advances rather than duplicating.

To test two sidecars, run the second sidecar with a different database and port pair, for example 8180/8181; reverse both, pair it, and give Hermes a second MCP server entry. Stop sidecar A. Sidecar B must continue to show **Live updates connected.** Its new request must appear under B only. Restart A; it must reconcile its missed changes without duplicate rows or older state replacing newer state.

## 5. Exercise lifecycle and cleanup

With one pending request visible:

1. Rotate, switch among root screens, open request details, and return. The sidecar still has one logical stream.
2. Press Home and return repeatedly. Each true background transition closes the stream; each return reconciles and opens one replacement.
3. Disable and enable the active network, or disconnect and restore both `adb reverse` mappings. The app shows reconnecting/unreachable for that sidecar while another remains live. Retry delay is exponential and capped at 30 seconds.
4. Start a real wallet approval and return from the wallet. The foreground stream is lifecycle state, not a wallet executor: the wallet is asked once, and return only reconciles the already-recorded outcome.
5. Remove one connection. Its stream closes and does not reconnect; its sync cache is deleted. Other connections remain live and Activity keeps the owner's record.

The sidecar's `update stream opened` and `update stream closed` lines are safe to count: they contain connection IDs, never tokens or request text.

## 6. Observe scheduled background synchronization

Create a durable request, press Home, turn the screen off, and leave the USB connection and network available. Do not claim a 15-minute deadline. Record both the request's creation time and the first time the persisted result becomes visible after reopening.

Useful read-only inspection commands are:

```bash
adb shell dumpsys jobscheduler | rg -C 12 'io.github.brrenat.seekervault|SystemJobService'
adb shell dumpsys deviceidle | rg -i 'mState|mLightState|whitelist'
adb shell dumpsys package io.github.brrenat.seekervault | rg -i 'stopped=|enabled='
adb logcat -v time | rg 'WorkManager|WM-|seekervault'
```

Android Studio's **App Inspection → Background Task Inspector** can show the debug app's WorkManager jobs and, for an explicit diagnostic run, start one without pretending that Android scheduled it at that moment. The actual-delay check must still wait for the OS-scheduled worker.

Run these cases separately and record the observed delay:

- screen off with the process alive;
- normal process death (remove the app from recents or let Android kill it—do not use Force stop);
- reboot, unlock, restore the USB reverse mappings if the sidecar is on the Mac, and wait for Android to make the persisted job eligible;
- Android Settings → Apps → Seeker Agent Connect → **Force stop**.

For Force stop, no worker should run. Reopen the app explicitly; foreground reconciliation should catch up, and the unique schedule should be retained/re-established. Neither WorkManager nor FCM bypasses Android Force stop.

For a submitted transfer, advance the controlled test-chain status while the app is backgrounded. A later unary sync may perform only the existing bounded, read-only confirmation check. On reopen, Activity should advance without tapping **Check status** and without the wallet opening. Never use mainnet for this check.

## 7. Diagnose failures

Start at the boundary that failed:

- Main endpoint: `curl -s http://127.0.0.1:8080/healthz` and `adb reverse --list`.
- Update endpoint: confirm the sidecar advertised the expected HTTP/2 URL, reverse its port too, and look for an open/closed stream pair. An HTTP/1-only proxy cannot carry `Subscribe`.
- Authentication/revocation: `pnpm pair status`; a revoked connection must be paired again.
- Version mismatch: upgrade the sidecar and leave manual **Refresh** available. Do not relabel an unsupported protocol as a network outage.
- Scheduling: inspect JobScheduler, device-idle state, package stopped state, and WorkManager log lines above. A deferred eligible run is normal Android behavior.
- Persisted result: reopen the app and compare **Last synced**, request state, and Activity. A newer request must never roll back or duplicate after catch-up.

The broader message-by-message fixes are in [Troubleshooting](troubleshooting.md#live-and-background-updates).

## 8. Finish and record

Run the repository checks and the focused Stage 5.2 suite:

```bash
pnpm test:updates
pnpm check
pnpm test:hello
pnpm test:queue
pnpm check:android
pnpm check:generated
```

Record the command, tool versions, `git rev-parse HEAD`, actual scheduling delays, and PASS, FAIL, or NOT RUN for every physical-device row in the [Stage 5.2 checklist](../testing/stage-5-2.md#physical-seeker-checklist-saw-053). A mock, Robolectric, emulator, or successful build is automated evidence only.
