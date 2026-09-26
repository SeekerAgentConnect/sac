# Following sent transactions to the chain, on the phone (SEE-165)

Before SEE-165 the phone stopped at "Sent to the network". A direct server could settle a transfer
from the chain (SAW-022, `server-sdk/src/requests/confirmation.ts`), but only while that server was
reachable and asked; a feed operation — a swap, a prediction order — had nobody to ask at all.

Now the phone follows every transaction its own wallet sent, from the phone, with nothing but a
read-only Solana endpoint. No server, gateway, agent poll, push message or second wallet interaction
is involved. It still needs internet access and an endpoint for the transaction's cluster.

It changes status tracking only. Nothing here prepares, approves, signs, submits or resubmits a
transaction; the tracker has no way to reach a wallet and no RPC method that sends
(`StageBoundaryTest.theConfirmationReaderOnlyReads`).

## What is followed

| Flow | Followed | Why / why not |
| --- | --- | --- |
| Direct transfer (SOL, SPL) | Yes | A transaction the wallet sent. |
| Direct SKR staking: stake, unstake, cancel unstake, withdraw | Yes | Same. Staking records now keep their wallet and cluster (`ReviewedStaking`), so they get an explorer link and are checked on the right cluster. |
| Feed operation: swap, prediction buy, any bundled provider's transaction | Yes | Same, whatever the provider: Jupiter's `status` stays `Unsupported` and is not needed. |
| Acknowledgement, message signature | No | Nothing reached a chain. |
| Sandbox rehearsal | No | Nothing was signed or sent. |
| Declined / failed in the wallet | No | Nothing was sent; the capture is dropped. |
| Wallet answer lost (no signature) | No lookup | There is no signature to look up. History says so and points at the wallet's own history. |

## Durable tracking

`confirmations/` holds the tracker; `confirmations/storage/TrackingStore` keeps one JSON file per
request under `files/confirmations/<connection>/<request>.json`, written atomically.

A tracking record pins everything a later check needs, so nothing is read from the phone's current
wallet, network or connection afterwards:

- the request key, and where it came from (direct or operation);
- the **cluster the transaction was bound to**, the wallet that signed;
- the **approved message bytes** (what the signatures cover) and the recent blockhash in them;
- when it was captured and submitted, the signature, and the last check.

When it is written:

- **Feed operation** — `ProposalRepository.beginExecution` captures the exact bytes under the same
  lock that binds the execution, *before* the wallet is opened. The signature is added when the
  outcome is recorded, before anything else happens.
- **Direct request** — the approved transaction is already stored before the wallet is opened
  (`LocalResult.approvedTransaction`, SAW-021); `ConnectionRepository.save` hands it to the tracker
  when the sidecar accepts the approval, and the signature when the wallet answers — after the
  answer is on disk and before it is delivered.

A wallet or network switch cannot redirect an old check. Removing a connection leaves its tracking
and its History row, so a retained record stays checkable. **Clearing History** clears tracking too,
and an answer in flight at that moment writes nothing back: `ActivityLog.confirm` never creates a
row, and `ActivityStore` remembers when it was cleared so no late writer — a server reply, a sync, a
check — can recreate a row the owner deleted.

### Backfill

On app start the tracker brings older submissions in where the phone still holds enough to check
them honestly: a direct answer that still carries the approved transaction and its signature, and
whose server hasn't already settled it. A History row with a transaction signature and nothing to
compare it with (for example, a feed operation executed before this build, whose bytes were never
kept) is marked **Unresolved · missing context** instead. Its status alone would say that
*something* landed under the signature, not that it was the approved transaction.

## What counts as an answer

The trust standard is the direct server's (SAW-022), applied on the phone:

1. **`getSignatureStatuses`**, batched per cluster (≤256 per call). `processed` is not a result.
2. At `confirmed` or `finalized`, **`getTransaction`** (base64, `maxSupportedTransactionVersion: 0`)
   and compare its message bytes with the approved message **byte for byte**. Only a match settles
   anything:
   - no error → **Confirmed**, with the level observed (`confirmed` or `finalized`) and the slot;
   - an error → **Failed on chain**, with the chain's own error;
   - a different message → **Unresolved · mismatch**. Never a success, never a failure.
   - no body served yet → inconclusive, retried.
3. A confirmed result keeps being checked for a short while until `finalized` is observed.

**Expiry is proven, never assumed.** A missing status proves nothing (signatures drop out of the
status cache). A transaction is **Expired · never landed** only when all three hold:

- it was submitted more than three minutes ago (well past both the blockhash window and the lag
  between the confirmed and finalized chain);
- `isBlockhashValid` at `finalized` says its blockhash can no longer be used;
- a `searchTransactionHistory` lookup has no record of the signature.

A request's or proposal's own expiry is not a transaction's expiry and is never used here. A record
whose blockhash can't be read is never concluded expired.

**Inconclusive answers** — timeouts (15 s per call), offline, HTTP 429 or JSON-RPC 429, other
refusals, malformed bodies, a single null status, an endpoint on the wrong cluster — change nothing
but the "last checked" line and the reason shown. They are retried with backoff (2 s, 4 s, 8 s …
up to 6 h). Automatic checks stop after 40 inconclusive attempts or seven days, and the record says
**automatic checks stopped**; the owner can still tap **Check status**, which doesn't use up the
automatic budget.

What is stored about an endpoint is its **host** only; a configured URL can carry an API key.

## Endpoints

The phone's own, from the build, never a server's or a publisher's:

```
android/gradlew -p android :app:assembleDebug \
  -Pseekervault.solanaRpc.mainnet=https://… \
  -Pseekervault.solanaRpc.devnet=https://… \
  -Pseekervault.solanaRpc.testnet=https://…
```

All empty by default. The general `-Pseekervault.solanaRpc` (SEE-94) is also used, but for a
cluster only once its **genesis hash** proves it serves that cluster. A per-cluster endpoint that
serves a *different known* cluster is refused (**wrong cluster**). A genesis hash no cluster has — a
local `solana-test-validator` — is accepted only for an explicitly configured devnet or testnet
endpoint in a **debug** build. With no endpoint for a cluster the record says so and waits; a later
build that has one picks it up on start.

## When checks run

One coordinator (`ConfirmationTracker`), three callers, one mutex:

- **Foreground** — `MainActivity.onStart` loads, backfills, and starts a loop that runs each check
  when it is due, two seconds after a new signature and on the tracker's backoff after that. A
  restored network makes every check that was only waiting out an unreachable endpoint due at once
  (`connectivityRestored`) and wakes the loop; any other backoff, and the attempt count, are kept.
  Transitions are ordered: if the app is hidden again while the foreground is still loading, the
  loop is not started, and the background hand-off stands.
- **Background** — `MainActivity.onStop` hands anything unfinished to WorkManager as one unique,
  one-time request (`chain-confirmations`) with `NetworkType.CONNECTED` and an initial delay equal to
  the next due check. Each pass schedules its successor while something is unfinished. It is not
  gated on a usable connection and doesn't wait for the fifteen-minute sync.
- **Check status** — the owner's own tap, on History details (both direct requests and signals) and
  on an Activity record. For a direct request it also asks the server, as before.

An automatic run that finds another running skips; a manual check waits for it. Writes re-read the
record first, and a verified result is never replaced by an unverified one.

Background timing is Android's: WorkManager defers under Doze and App Standby, and **Force stop**
prevents all of it until the app is opened again, when the foreground path catches up. Nothing here
promises continuous or immediate confirmation while the app is closed.

## Status model

Four separate facts, kept separately:

| Fact | Where |
| --- | --- |
| The owner's decision | `LocalResult.answer`, `ProposalRecord.execution` |
| What the wallet did | `SigningOutcome`, `ProposalOutcome` |
| What the chain showed, as this phone verified it | `ChainTracking.check`, copied to `ActivityRecord.chain` |
| Whether the server was told | `LocalResult.delivery` |

A server outage doesn't hide a locally verified confirmation, and a local confirmation doesn't claim
the server received anything: a response still waiting to be delivered still shows the delivery
card.

**Conflict rule** (`ActivityLog.merged`, `history/ChainStatus.chainView`): the phone's own
verification of the approved bytes wins; a server's settled word (CONFIRMED / FAILED) counts where
the phone has none; "sent" never overwrites either. A verified chain result may replace only
chain-only outcomes (`Sent`, `Unknown`, `Confirmed`, `ChainFailed`) — never the owner's decision, a
delivery outcome, or a simulation. One row per request, so nothing is counted twice by daily
spending; an expired transaction counts as nothing spent.

Nothing about confirmation reopens a proposal: an executed proposal stays executed whatever the chain
says, and a publisher's republication or a retry can't make it executable again.

### What a confirmation does not prove

- A confirmed **prediction order** proves the order's transaction succeeded. It does not prove the
  order filled or the market settled; History says so, and never says "Filled".
- A confirmed **unstake** means the unstake started, not that its cooldown ended.
- Provider/order lifecycle remains outside the app; `ExecutionProvider.status` stays unsupported.

## Screens

History (Inbox → History, and its details) and Activity read the same durable state:

- **Rows** — "Confirmed on the network", "Failed on the network", "Expired · never landed",
  "Network status unresolved", "Sent · status check delayed", or "Sent to the network".
- **Details** — the execution card says who verified it (this phone, via which host, at which
  commitment and slot) or that the server reported it; while unsettled, why (processed only, not
  visible yet, endpoint unreachable, rate limited, wrong cluster, no endpoint), when it was last
  checked and when the next check is due. **Check status** appears while a check can still change
  something. The transaction chip and timeline follow the same answer, and the explorer link uses
  the record's own cluster — including staking.
- **Activity** — the record's outcome, and a separate **Network check** line with the same
  information, plus **Check status**.

## Verification

Automated (JVM, Robolectric), all with a scripted chain — no public cluster, no funds:

- `confirmations/ConfirmationTrackerTest` — delayed confirmation followed to finalized; processed
  only; finalized success; chain error; mismatched transaction; status without body; timeout / 429 /
  malformed then recovery with growing backoff; the automatic budget running out and a manual check
  still settling it; provable versus inconclusive expiry (too soon, blockhash still valid, unreadable
  blockhash); a ledger search finding an old signature; wrong cluster; no endpoint; clearing History
  while an answer is in flight; late server updates that are behind or disagree; overlapping
  foreground, worker and manual runs; abandon/first-signature rules; backfill with and without
  context; the message-region reader.
- `sync/ConfirmationWorkTest` — nothing unfinished schedules nothing; one unique
  network-constrained request due with the next check; worker success and retry.
- `history/HistoryDetailMappingTest` — local verified result over a lagging server; local failure
  over a server's CONFIRMED; proven expiry; unsettled check text and **Check status**; a confirmed
  prediction order never labelled "Filled".
- `activity/ActivityLogTest`, `activity/storage/ActivityStoreTest`, `activity/ExplorerTest` — staking
  context and its explorer link, a lost staking answer as Unknown, the chain field and the cleared
  marker round-tripping.
- `StageBoundaryTest.theConfirmationReaderOnlyReads` — the four JSON-RPC methods, no send or
  wallet, endpoints from `BuildConfig` in the composition root.

Device / emulator:

- **NOT RUN** — emulator run against a controlled `solana-test-validator`, process death, reboot,
  background WorkManager scheduling under Doze, and force-stop catch-up. The environment this
  ticket was implemented in had no KVM access for the emulator and no Solana validator installed.
  To run it: start `solana-test-validator`, `adb reverse tcp:8899 tcp:8899`, build with
  `-Pseekervault.solanaRpc.devnet=http://127.0.0.1:8899` (a debug build: only it allows loopback
  cleartext, and only it accepts the validator's unknown genesis), pair a direct server whose `SOLANA_RPC_URL` is the same
  validator, approve a devnet transfer, stop the server and gateway, and watch History settle.
  Record the observed background timing rather than assuming one.
- Flows that can't run on a test cluster — SKR staking (mainnet-only program) and Jupiter swap /
  prediction (mainnet providers) — are covered by the scripted chain above rather than by spending
  mainnet funds.
