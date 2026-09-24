# Emulator end-to-end against the DigitalOcean deployment

This runbook is for agent sessions that debug the Android app, fix bugs and test them without a
person at the phone. The app runs on a headless emulator, is built against the deployed gateway, and
is paired with the deployed MCP servers and demo feeds. Everything below was run end to end on
2026-09-24.

An emulator result is evidence for a bug fix. It is **not** the physical-Seeker check that some
stages require (`docs/development/android.md` § Tests). Seed Vault Wallet and Mobile Wallet Adapter
are absent, so nothing can be signed.

## What is deployed

| Role | Origin | DO app ID |
| --- | --- | --- |
| Feed gateway | `https://seeker-gateway-sg8g3.ondigitalocean.app` | `accce337-aab1-42ed-9768-3fc6c1ddc412` |
| Direct MCP server (seeker-mcp) | `https://seeker-mcp-r9qqm.ondigitalocean.app` | `70848a26-9237-4e17-991d-845fb9f3665f` |
| SKR staking MCP server | `https://seeker-skr-staking-mcp-oq7eh.ondigitalocean.app` | `28910149-9dfc-4683-87cf-e2223ec4c6af` |
| CopyTrading demo feed (signals) | `https://signals-demo-fzs2q.ondigitalocean.app` (admin at `/trader`) | `a7aba189-c59f-4e0b-a0b0-0ccd8442036b` |
| Prediction demo feed | `https://prediction-demo-quni7.ondigitalocean.app` | `326326e6-43c2-4aa1-893c-a3607b77e6ef` |

The specs are in `deploy/*.yaml`, and the `doctl apps update` commands are in `deploy/README.md`
§ 9. `doctl` is authenticated for `apps` only; `doctl account get` answers 403, and that is normal.

Credentials are in `~/.env`, never in the repository. Load them with `set -a; source ~/.env; set +a`,
and never print them:

| Variable | Used for |
| --- | --- |
| `MCP_SEEKER_VAULT_API_KEY` | Bearer token for seeker-mcp's `/mcp` |
| `MCP_SKR_API_KEY` | Bearer token for the SKR staking server's `/mcp` |
| `DEMO_ADMIN_USER`, `DEMO_ADMIN_PWD` | The demo feeds' admin login (`/trader` on the signals demo) |

## Host quirks, already handled by the scripts

- **IPv6 gets 404 from `dl.google.com`.** SDK manifests and downloads work only over IPv4.
  `scripts/emulator.sh` passes `-4` and `-Djava.net.preferIPv4Stack=true`. For the same reason
  cmdline-tools is pinned to `13114758`: newer releases replace `sdkmanager` with a downloader that
  goes over IPv6 and fails.
- **No KVM group.** The session user can't open `/dev/kvm` and has no sudo, but it is in the
  `docker` group. `start` therefore runs the emulator in a small Ubuntu container (`sac-emu`) with
  `--device /dev/kvm` and `--network host`, so the host's `adb` sees it as `emulator-5554`.
  If `docker ps` answers `permission denied` even though `/etc/group` lists the user in `docker`,
  the login session's supplementary groups are stale; run the script through the group instead:
  `sg docker -c "scripts/emulator.sh start"`. Don't reach for `-accel off` as a fallback — Android's
  watchdog kills `system_server` mid-boot under software emulation (`Blocked in handler on main
  thread for 68s`) and the guest never finishes booting.
- **No system JDK or SDK.** `setup` installs JDK 21 in `~/.local/jdk` and the SDK in
  `~/android-sdk`. Neither needs root.

## Bring-up

```sh
scripts/emulator.sh setup     # once per host; several GB; idempotent
scripts/emulator.sh build     # :app:assembleDebug with -Pseekervault.relayUrl=<gateway>
scripts/emulator.sh start     # boots AVD "sac" and waits for sys.boot_completed
scripts/emulator.sh install   # installs and launches the debug APK
scripts/emulator.sh ui        # what is on screen now
```

A cold build takes about 5 minutes and a boot about 1 minute. After changing app code, run `build`
and `install` again; `install -r` keeps the app's data and pairings. To start from nothing, run
`adb uninstall io.github.brrenat.seekervault`.

## Driving the UI

`adb` is `~/android-sdk/platform-tools/adb`.

- `scripts/emulator.sh ui` prints each labelled element as `"label" x y`, where `x y` is its tap
  centre. Prefer it to screenshots, because it is text and cheap.
- `scripts/emulator.sh tap-text "Add connection"` taps the first element whose label starts with
  that text.
- `scripts/emulator.sh shot /path/file.png`, then read the PNG, when layout or colour matters.
- `scripts/emulator.sh open-uri 'seekervault://pair?…'` hands a pairing URI to the app, which is
  what scanning its QR code does.
- While a bottom sheet is open (connection details, request review), the bottom navigation isn't
  in the UI tree. Press `adb shell input keyevent 4` (Back) first.
- `adb shell input text` silently truncates long strings containing `&` and `%`. Type long values
  in chunks and escape `&` as `\&`, or use `open-uri` when the app accepts a deep link.
- `scripts/emulator.sh logs` prints the app's logcat. Sync and stream failures are **not** logged,
  so add temporary `Log.d` calls when debugging them.

## Connecting the app

### Direct MCP servers (pairing)

Each server keeps **one** active phone. Pairing the emulator disconnects whichever phone was paired
before and cancels its pending requests. The owner has approved this for the emulator on both
servers. The tool's `replaces` and `warning` fields show when it will happen.

```sh
set -a; source ~/.env; set +a
node scripts/mcp-call.mjs https://seeker-mcp-r9qqm.ondigitalocean.app \
  MCP_SEEKER_VAULT_API_KEY call vault_create_pairing_link > /tmp/pair.json
scripts/emulator.sh open-uri "$(node -p 'require("/tmp/pair.json").pairing_uri')"
scripts/emulator.sh tap-text Pair
```

For the staking server, use `https://seeker-skr-staking-mcp-oq7eh.ondigitalocean.app`,
`MCP_SKR_API_KEY` and `skr_create_pairing_link`. A code is valid for 10 minutes and is used once.
`node scripts/mcp-call.mjs <origin> <TOKEN_VAR> list` shows each server's tools.

### Public feeds (no credential)

A feed reference contains no secret. Each demo prints its own reference at startup:

```sh
doctl apps logs a7aba189-c59f-4e0b-a0b0-0ccd8442036b --type run --tail 3000 | grep seekervault://feed
```

The CopyTrading reference is
`seekervault://feed?v=1&gateway=https%3A%2F%2Fseeker-gateway-sg8g3.ondigitalocean.app&server=b1ffc4d0-f20f-4f31-875b-4a0e0c9694ef`.
Feeds have no deep link. Enter the reference under **Add connection** → **Pairing code**, then tap
**Continue** and **Add feed**.

## Firing test events

**Direct request (seeker-mcp).** The acknowledgement is a harmless round trip. Reuse an
`idempotency_key` only when retrying the same request.

```sh
node scripts/mcp-call.mjs https://seeker-mcp-r9qqm.ondigitalocean.app MCP_SEEKER_VAULT_API_KEY \
  call vault_request_ack '{"text":"agent test","idempotency_key":"agent-test-001"}'
# … approve or reject it in the app, then read the result:
node scripts/mcp-call.mjs https://seeker-mcp-r9qqm.ondigitalocean.app MCP_SEEKER_VAULT_API_KEY \
  call vault_get_request '{"request_id":"<id>"}'
```

`vault_sign_message` and `vault_transfer` reach the app, but the emulator can't complete them
because it has no wallet. Test them only up to the review screen. The server's Solana RPC is devnet.

**Feed signal (CopyTrading demo).** The environment is sandbox, so no funds can move:

```sh
scripts/demo-signal.sh create "agent test" usdc-sol 1h   # prints the new signal's row
scripts/demo-signal.sh list                               # id | state | publication | expiry
scripts/demo-signal.sh cancel <signal-id>                 # withdraw it; the phone should follow
```

Cancel signals once a test is done so the feed doesn't fill up with them. The Prediction demo's
admin flow hasn't been exercised from this runbook yet.

**Server-side evidence** that an event was stored and delivered:

```sh
doctl apps logs 70848a26-9237-4e17-991d-845fb9f3665f agent-connect --type run --tail 200   # seeker-mcp
doctl apps logs a7aba189-c59f-4e0b-a0b0-0ccd8442036b copytrading --type run --tail 50      # signals demo
```

## Known issues (last checked 2026-09-24 23:27 UTC, SEE-151)

The earlier note that live push never stays up no longer holds. On the build at `4b6797a` a direct
MCP request appeared within about ten seconds of being stored, and the CopyTrading feed's sheet read
"Live updates connected." throughout. What is true:

- **A direct connection never recovers its stream after a network interruption** ([SEE-152]).
  The row sticks at "Couldn't reach the server" while unary calls from the same phone still reach
  that server; **Retry** and app restarts don't clear it, and only re-pairing does, which cancels
  whatever requests the server was holding. Cycle airplane mode and you will reproduce it.
- **An opened sheet's last action sits under the three-button navigation bar** ([SEE-153]).
  Gesture navigation is fine.
- **Removing a connection leaves its items in the Inbox and in Home's count** ([SEE-154]).
- **Firebase isn't configured in this debug build** (`Default FirebaseApp failed to initialize`),
  so there is no background push, no notification channel, and no `POST_NOTIFICATIONS` prompt.
  Adding `android/app/google-services.json` (`docs/guides/firebase.md`) is the whole fix; the
  gateway and seeker-mcp already hold their sending credentials.
- **The deployed servers are behind the repository.** The gateway answers 404 to
  `FeedService.GetFeedStatus`, and the staking server's startup log has no `live updates are …`
  line, so SEE-150's feed presence and staking live updates can't be exercised until those images
  are rebuilt from a commit that contains them.
- **Every staking tool needs a wallet**, including the read-only `get_staking_status`; a wallet-less
  phone gets `WALLET_NOT_CONNECTED`, so no staking item can be made to reach the app on the emulator.

The full run behind these, with commands, a scenario matrix and screenshots, is in
[`emulator-e2e-see151.md`](emulator-e2e-see151.md).

[SEE-152]: https://linear.app/seekeragentwallet/issue/SEE-152
[SEE-153]: https://linear.app/seekeragentwallet/issue/SEE-153
[SEE-154]: https://linear.app/seekeragentwallet/issue/SEE-154

## A debug → fix → verify loop

1. Read the ticket first (`docs/development/tickets.md`). No ticket, no change.
2. Reproduce on the emulator: fire the event, drive the UI, and record `ui` output or a screenshot
   and the server logs.
3. Fix it with a unit or Robolectric test that fails first (`docs/development/android.md` § Tests),
   then run `pnpm check:android`.
4. `scripts/emulator.sh build && scripts/emulator.sh install`, then repeat the reproduction.
5. Record the emulator evidence in the ticket's test notes and label it as emulator, not Seeker.
6. Clean up: cancel test signals, and answer or cancel test requests
   (`vault_cancel_request '{"request_id":"…"}'`).

`scripts/emulator.sh stop` removes the emulator container. The AVD and its data stay in
`~/.android/avd`.
