# SEE-155 — verification addendum on the integrated build

Executed 2026-09-25, 11:27–12:44 UTC, on the headless emulator and on this host. This is an
addendum: the historical run it follows, [`emulator-e2e-see151.md`](emulator-e2e-see151.md), tested
an earlier build against older servers and is left exactly as it was. The general runbook is
[`emulator-e2e.md`](emulator-e2e.md). Screenshots and logs are in
[`../evidence/see-155/`](../evidence/see-155/).

No wallet was connected, nothing was signed, and no funds moved. No shared deployed service was
stopped, restarted or reconfigured.

Legend: **PASS** executed and correct · **FAIL** executed and wrong · **BLOCKED** could not be
executed, reason given · **NOT RUN** could have been, and was not.

## 1. What was tested

| Thing | Value |
| --- | --- |
| Source | `develop` at `ef3b3f6` (the reviewed PR #66 head) plus the SEE-155 changes, uncommitted at run time |
| APK | `scripts/emulator.sh build` → `-Pseekervault.relayUrl=https://seeker-gateway-sg8g3.ondigitalocean.app`; `app-debug.apk` sha256 `4c7e5e958948eb5d…`, installed with `install -r` at 12:00:35 UTC over the SEE-151 install, so its four pairings were kept |
| `FIREBASE_CONFIGURED` | **false** — `android/app/google-services.json` is still absent |
| Emulator | AVD `sac`, `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925`, API 35, 1080×2400 |
| Go | 1.27.1 |

### Deployed services, and what they can and cannot exercise

| Role | Image | Relevant here |
| --- | --- | --- |
| Feed gateway | `gateway-0.1.7` | **Still predates SEE-150.** `POST /seekervault.gateway.v1.FeedService/GetFeedStatus` answers `404 page not found`. (An unknown path answers 200 from a catch-all, so a probe has to name the real procedure to mean anything.) |
| Direct MCP (seeker-mcp) | `mcp-0.1.7` | Advertises live updates; used for the recovery runs |
| SKR staking MCP | `skr-staking-mcp-0.1.2` | Advertises **no** live updates and has no relay configured, so it is the real-world case for finding 4 — and it cannot exercise staking streaming or push |
| CopyTrading demo | `copytrading-0.1.4` | Not stopped: it is shared |
| Prediction demo | `prediction-0.1.7` | Not used |

Because the deployed gateway cannot answer presence, feed presence was verified against a **local
gateway and two local publishers built from this branch** (§3), reached from the emulator through
`adb reverse`. That is what the ticket asks for — "a gateway and publishers built from the reviewed
code … a dedicated test publisher while its gateway stays up" — and it is also the only way to stop
a publisher without touching a shared one.

## 2. Automated checks

| Check | Command | Result |
| --- | --- | --- |
| Publisher library | `cd publisher-support && go vet ./... && go test ./...` | PASS |
| Gateway | `cd feed-gateway && go test ./...` | PASS |
| Formatting | `gofmt -l publisher-support feed-gateway` | clean |
| Android | `android/gradlew -p android --continue spotlessCheck checkDesignSystemLiterals :designsystem:verifyRoborazziDebug :app:verifyRoborazziDebug :designsystem:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` (the `check:android` task list) | spotless, literals, both lint tasks and both assembles PASS. `:app:testDebugUnitTest` 1602 tests, **40 failed**; `:designsystem` 164 tests, 1 failed |

**The 40 + 1 failures are identical on the PR base.** The same task list was run in a clean
worktree at `ef3b3f6`: 1583 tests, 40 failed, plus the same designsystem failure, and the sorted
lists of failing test names are identical. They are this host's environment, not this change:

- 38 are the real-sidecar and interop suites (`ConnectConnectionGatewayTest`, `InboxRealSidecarTest`,
  `Stage2/52/53AcceptanceTest`, `TwoSidecarsTest`, `UniStreamInteropTest`, `GrpcBidiInteropTest`,
  `ConnectLiveCommandTransportTest`, and the Robolectric tests that start a sidecar). `pnpm install`
  has been run, but the workspace packages are not built, so the sidecar exits with
  `Cannot find package '@seeker-vault/server-sdk'` before it listens.
- 2 are screenshot comparisons in code this change does not touch —
  `screens/wallet-handoff.png` and `sheet-scaffold/variant-stacked-over-blurred.png` — which fail
  the same way on the base.

The 19 tests the branch had at that point all pass. One more was added after the full run —
`aNewerReadThatFailedDoesNotDropAnOlderAnswer`, from a final review of the read ordering (a refresh
that failed outright used to claim the gateway's stamp and drop the periodic answer still in
flight). It and the three SEE-155 Android test classes were rerun afterwards and pass, with
`spotlessCheck` and `:app:lintDebug` green.

### The regressions fail before the fix

| Test | Against `ef3b3f6` |
| --- | --- |
| 7 new `FeedStatusManagerTest` cases (32/33/65 feeds, a failed batch, two gateways, an overtaken read, a feed removed mid-read, two refreshes) | 7 FAIL |
| `aNewerReadThatFailedDoesNotDropAnOlderAnswer` | FAIL against the branch with only its one-line guard removed (the ordering it tests does not exist on the base) |
| 2 `FeedPresenceRefreshTest` cases through the real `ConnectionsViewModel` | 2 FAIL |
| 3 `ConnectionLiveUpdatesUnsupportedTest` cases (not configured / upgrade required / incompatible) | 3 FAIL |
| `TestAGatewayThatDoesNotAnswerCheckInsIsAskedAgainMuchLater`, `TestAnUpgradedGatewayResumesCheckInsWithoutARestart`, `TestCancellationDuringTheUnsupportedWaitStopsTheLoop` | 3 FAIL (`the loop asked 1 time(s)`, twice; `the loop waited []`) |

The Go cases were run on the base with the two references to the new API
(`PresencePlan.Unsupported`, `UnsupportedBackoff`) removed so the file compiles; everything else
was unchanged. The remaining new tests pin behaviour the base already had and pass on both sides,
which is their job: `TestPresenceAnswersExactlyTheMostChannelsItAllows` (the gateway answers exactly
32 channels, the size the phone now batches to), a network failure and a revocation still reading
as unreachable and as "pair again", and a direct connection's refresh asking nothing about
presence.

## 3. Scenario matrix

### Finding 1 — publisher check-ins resume after a gateway upgrade

Run by the earlier session of this ticket (11:27–11:31 UTC) with a gateway built from this branch,
a small proxy in front of its publisher API that answered `Heartbeat` as `unimplemented` until it
was told to forward it, and a CopyTrading publisher ("pubC") built from this branch. Logs:
[`old-gateway-proxy.log`](../evidence/see-155/old-gateway-proxy.log),
[`publisher-c-checkins.log`](../evidence/see-155/publisher-c-checkins.log),
[`presence-upgrade-watch.log`](../evidence/see-155/presence-upgrade-watch.log).

| # | Check | Result |
| --- | --- | --- |
| 1.1 | Against an "old" gateway the publisher keeps asking on the slow backoff, not in a tight loop | PASS — refused at 11:27:29.998 and 11:28:30.046: a minute, then two |
| 1.2 | One log line per episode, not one per attempt | PASS — one line at 11:27:30 |
| 1.3 | The gateway is "upgraded" with the publisher still running and unpublished-to; check-ins resume in the same process | PASS — upgraded at 11:29:50; the next retry at 11:30:30.060 was forwarded; the publisher logged "answers check-ins again" once |
| 1.4 | Phones see the quiet feed come back | PASS — the gateway's `GetFeedStatus` read OFFLINE from 11:29:05 (the window lapsed while check-ins were refused) and ONLINE at 11:30:35, with no publication in between |

### Finding 2 — more than 32 feeds

| # | Check | Result |
| --- | --- | --- |
| 2.1 | 32, 33 and 65 feeds, mixed answers, one failed batch, two gateways, a feed removed mid-read | PASS in unit tests (§2). **NOT RUN** on the device: adding 33 feeds by hand on the emulator would measure `adb input`, not batching. |

### Finding 3 — manual refresh reads presence

Local rig: gateway (`BROADCAST_HEARTBEAT_SECONDS=5`, so a feed goes offline 15 s after its last
check-in), publishers "SEE-155 feed A" and "SEE-155 feed B", and a logging proxy between
`adb reverse tcp:8090` and the gateway that timestamps every `GetFeedStatus` the phone makes
([`presence-reads-proxy.log`](../evidence/see-155/presence-reads-proxy.log)).

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 3.1 | Stop feed A's publisher, gateway up: A goes offline, B stays online, A's signal stays readable | PASS — publisher A stopped 12:37:46; Home read **"Feed offline · 1 pending"** for A at 12:38:09 (the first poll after the 15 s window, 12:38:05) while B read "Connected · 0 pending"; A's signal stayed in the Inbox | `13-feed-a-offline-b-online.png` |
| 3.2 | Restart A, refresh: availability updates at once, not at the next poll | PASS — a periodic read at 12:39:04.346 still saw A offline; A restarted; its sheet was opened (the refresh action) at 12:39:08.7; the proxy logged an extra read at 12:39:09.166; the sheet said "Feed available." at 12:39:10 and Home read "Connected · 1 pending" at 12:39:14 | `14-feed-a-refreshed-online.png` |
| 3.3 | The refresh does not advance or reset the periodic timer | PASS — the next periodic read was 12:39:30.519, 26.2 s after the one before the refresh: inside the poll's 22.5–37.5 s jitter band measured from *that* read, not from the refresh |
| 3.4 | Foregrounding reads at once | PASS — foregrounded 12:35:27.957, read at 12:35:28.500 |

### Finding 4 — Home no longer calls a streaming-less server unreachable

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 4.1 | Reachable server, unary refresh working, no live updates advertised | PASS — the deployed staking server's row reads **"No live updates · 0 pending"**, with no Retry and no re-pair prompt. On the SEE-151 build the same server read "Couldn't reach the server". | `01-home-staking-no-live-updates.png` |
| 4.2 | Consistent with the connection's own screen | PASS — its sheet: "Live updates are not configured. Refresh still works." | `02-staking-detail-live-updates-not-configured.png` |
| 4.3 | A genuine network failure still reads as one | PASS — in airplane mode the same row became "Couldn't reach the server" with **Retry**, and returned to "No live updates" when the network did | `07-offline-retry-visible.png` |
| 4.4 | Revocation still asks to pair again | PASS — the seeker-mcp connection revoked during SEE-151 reads "Disconnected · pair again to reconnect" | `01-…` |
| 4.5 | Upgrade-required and incompatible capabilities | PASS in unit tests only — no deployed server is in either state |

### Recovery (direct MCP, seeker-mcp, connection `1d551dc2-e343-4aa4-9029-740c0111a5cf`)

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| R.1 | Airplane mode, publish while offline, restore: delivered without re-pairing | PASS, three times. Requests `da9f8321-…`, `1fafdc73-…`, `5a9215dd-…` were stored during outages and all three arrived after the network returned. Each stayed `PENDING` server-side (nothing cancelled or lost), none was duplicated, and the review sheet shows the same connection id. | `03-…`, `05-…`, `10-review-sheet-three-button-nav.png` |
| R.2 | Recovery time | Measured, see below | server log |
| R.3 | Force-stop, then explicit relaunch with persisted pairing | PASS — force-stopped 12:19:03, relaunched 12:19:06; the server logged the stream reopened for the same connection at 12:19:12; request `698819bf-…`, stored at 12:19:21, was delivered live | `06-after-force-stop-relaunch.png` |
| R.4 | **Retry** | PASS, weakly — the row recovered 11 s after the network returned. Retry was tapped 2 s after, before the network had fully come up (the row's check time moved to that minute and still failed), so this run does not separate Retry from the automatic backoff. It shows that Retry does no harm, not that it is faster. |
| R.5 | Staking stream recovery | **BLOCKED** — the deployed staking image advertises no live updates (4.1) |

Recovery times, from the network being restored to the server logging `update stream opened`
for the same connection, and to the row changing:

| Outage | Stream reopened | Row back / item shown |
| --- | --- | --- |
| 12:02:15–12:02:53 (38 s) | 12:03:15 (+22 s) | not measured (a polling mistake; delivery confirmed afterwards) |
| 12:07:32–12:08:20 (48 s) | 12:08:30 (+10 s) | +13 s, item visible |
| 12:08:59–12:12:00 (3 min, backoff at its cap) | 12:12:15 (+15 s) | +15 s "Connected", +18 s the new request counted |

So the SEE-152 changelog's "within one bounded retry delay (≤30 s) of the network returning" held
in every run here, but it is not a bound: the retry that finds the network can itself wait out
the 30 s handshake timeout if the first stream after the outage never answers, and snapshot work
and a further retry come after that. That changelog entry is corrected in place.

One observation, not a failure: at +15 s in the 3-minute run the row read "Connected · 0 pending"
for about three seconds before "Connected · 3 pending", while the reconnect's snapshot loaded.

### Feed removal and re-adding (local rig)

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| F.1 | Removing one of two feeds clears its Home count and Inbox items at once | PASS — Home 8 → 7, feed B's signal gone from the Inbox, feed A's still there | `15-…`, `16-…`, `17-…` |
| F.2 | Activity history kept | PASS as far as it goes — Activity was byte-identical before and after. Feed B had no Activity record of its own to lose: answering a swap needs a wallet. |
| F.3 | An open review of the removed feed closes | **NOT RUN** on the device — removal is on the connection sheet, so a review cannot be open at the same moment by hand. SEE-154's own regression covers it. |
| F.4 | Re-adding does not duplicate items | PASS — 7 → 8, feed B's one signal listed once | `18-…` |

### UI

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| U.1 | In-app notification top spacing | PASS — on a live arrival the banner starts at y=160; the status bar and cutout end at y=128 | `12-in-app-banner-top-spacing.png` |
| U.2 | Library-backed sheet (connection details), scrolled to its last action | PASS both ways — "Disconnect" ends at y=2253 with the navigation bar from y=2337 (gesture), and at y=2139 with the bar from y=2274 (three-button) | `08-…`, `09-…` |
| U.3 | Host-chromed sheet (request review), scrolled to its last action | PASS both ways — "Acknowledge" ends at y=2203 (gesture) and y=2140 (three-button), each above its bar | `10-…`, `11-…` |

### Real push delivery

**BLOCKED in full, for the same reason as SEE-151.** On this build:

```
FirebaseApp: Default FirebaseApp failed to initialize because no default options were found.
FirebaseInitProvider: FirebaseApp initialization unsuccessful
dumpsys notification → AppSettings: io.github.brrenat.seekervault importance=NONE userSet=false
```

| # | Check | Result |
| --- | --- | --- |
| P.1 | Regular MCP, staking MCP and feed push, in background and after process death / task removal, vs force-stop | BLOCKED — no `android/app/google-services.json` on this host, and no Firebase credential or CLI to make one |
| P.2 | Notification appearance, warm and cold tap navigation | BLOCKED — same |
| P.3 | Permission denial and re-enabling | BLOCKED — the app never asks for `POST_NOTIFICATIONS` without a Firebase configuration |
| P.4 | The same event by push and by stream is one item and one notification | BLOCKED — only the stream path exists in this build; the stream alone produced no duplicates (R.1, F.4) |
| P.5 | Staking relay | BLOCKED twice — the phone cannot register, and the deployed staking app sets none of `SKR_STAKING_RELAY_URL` / `_SERVER_ID` / `_CREDENTIAL` |

The wallet-free scenarios stayed wallet-free, and the staking prerequisites were not weakened.

## 4. What remains, precisely

1. **An Android Firebase configuration** for `io.github.brrenat.seekervault` in the project whose
   service account the gateway and seeker-mcp already hold, placed at
   `android/app/google-services.json` (gitignored; `docs/guides/firebase.md`). Every push scenario
   waits on this alone on the phone side.
2. **Current server images.** The gateway (`gateway-0.1.7`) has no `GetFeedStatus`, and the staking
   server (`skr-staking-mcp-0.1.2`) advertises no live updates. Both need rebuilding from a commit
   containing SEE-150 and pushing; the deployments pull pinned Docker Hub tags whose credentials are
   DO-encrypted secrets that this host does not have.
3. **A staking relay registration** (`SKR_STAKING_RELAY_URL`, `_SERVER_ID`, `_CREDENTIAL` on the
   staking app), then an authorised staking test setup with a wallet for the staking item itself.
   No signing or movement of funds is needed for SEE-155's own checks.

## 5. Observations outside the four findings

- **The first presence read after a cold start is one interval late.** Force-stopped and relaunched
  at 12:36:22.7, the first `GetFeedStatus` arrived at 12:37:01.5 (and 75 s late in an earlier
  relaunch), while a foreground from the background read within 0.5 s. The likely cause, not
  verified here, is that the poll's first pass runs before stored connections have loaded, asks
  about nothing, and then sleeps a full interval. It errs toward "not offline" and is SEE-150
  behaviour, so it is recorded rather than changed.
- For about a second after a navigation-mode switch, the staking row read "Connected" before
  discovery re-established "No live updates" — the discovery-not-finished state, which Home shows
  as ordinary.
- The confirmation for removing a **feed** says "The server no longer accepts this phone, so
  there's nothing left to revoke", which is copy written for direct servers; a feed never had a
  credential.

## 6. Clean-up

- Requests `da9f8321`, `1fafdc73`, `5a9215dd`, `698819bf`, `a3e43518` and `39e2cf22` on seeker-mcp
  were cancelled (`vault_cancel_request` → `CANCELLED`).
- The two local feeds were removed from the phone; the local gateway, publishers and proxy were
  stopped; the `adb reverse` mappings for 8090 and 8091 were removed; navigation is back to gesture.
- The emulator keeps its SEE-151 pairings (seeker-mcp, staking, CopyTrading, Prediction). The
  local rig's database and publisher credentials lived only in the session scratch directory.

No credential, pairing token or device token appears in this report or in the evidence files.
