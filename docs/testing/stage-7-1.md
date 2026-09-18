# Stage 7.1 — the broadcast gateway, its stream, its hints, the two Jupiter plugins, and the two publisher templates

What was verified for SEE-90 to SEE-96, what was verified by hand, and what is left for the owner to
run on the phone. The automated checks are `pnpm check`, `pnpm check:broadcast`,
`pnpm check:publisher` and `pnpm check:android`; this page is about the rest.

## What no machine here could run

- **Docker.** No daemon is reachable on the machine these checks ran on (`docs/testing/stage-7.md`
  records the same limit). `docker compose config` accepts every stack — the gateway's base file and
  its overlays, the publisher's base file and public overlay, and the prediction stack — `caddy
  validate` and `caddy fmt` accept every Caddyfile, and `centrifugo checkconfig` accepts
  `broadcast/centrifugo.yaml`. Then every binary was **run natively** instead: the gateway, two
  broker nodes, Redis, and both publisher templates.
- **A physical Seeker.** The device run below is the owner's. That includes the phone's half of
  SEE-97: whether a sandbox rehearsal really opens no wallet on a real device, and whether the
  result is unmistakable to a person, are questions only a person with a phone can answer. The rest
  of that contract — the manifest, the gateway's refusal, both templates — was run here, below.
- **A market closing on cue (SEE-96).** The live provider's markets close when they close, so the
  closure path — a tracked market leaving the listing, being asked about directly, and being
  withdrawn — was run against a **local stand-in** serving the provider's own answer shapes, with
  the real gateway and the real template binaries either side of it. Everything else in that run
  used the live keyless provider.
- **Firebase (SEE-92).** No project or service-account credential was available, so the real send
  leg is an **opt-in** test that skipped: `internal/relay/firebase_test.go` sends one hint to a real
  project when `SEEKERVAULT_FCM_CREDENTIALS` and `SEEKERVAULT_FCM_SERVER` are set. Everything up to
  the moment Google is called was run instead, against a stand-in endpoint that checks the bearer
  token — including a native end-to-end run of the real gateway binary.

- **A real swap (SEE-93).** No mainnet funds and no wallet on this machine, so **nothing was ever
  signed or sent**. What was run against the live provider instead is the whole path up to the
  signature: real quotes, real builds, and the phone's own review of the real bytes — see the table
  below. The one thing left is the transaction itself, in step 6 of the device run.
- **A real prediction order (SEE-94).** The same, and for the same reason: an order needs a wallet
  holding the stake. What *was* run is everything up to it — a live market read, a real order built
  for a wallet holding nothing and refused by the provider as such, and a real order captured
  earlier whose address lookup tables were read from a real RPC and whose every field the phone read
  back. Step 7 of the device run is the order itself.

## Verified by hand, against the pinned broker

Centrifugo v6.9.6 (SHA-256 verified against the release checksums) and Redis 8.10.1 (built from the
release tarball). Everything in this section was established by running them, and the design
decisions that came out of it are in
[`wiki/broadcast-gateway.md#the-transport-and-what-it-cannot-do`](../wiki/broadcast-gateway.md#the-transport-and-what-it-cannot-do).

| What was asked | What happened |
| --- | --- |
| A connect request naming channels in `subs` | A connection with **no subscriptions at all** — the map is a recovery position, not a subscribe |
| A connection token with a `channels` claim | Subscribed and receiving, **with anonymous subscribe turned off** |
| A token naming a channel outside every namespace | The connection is closed (`3004`); inside the namespace, an unknown channel is simply empty |
| `channel_regex` against a token-granted channel | No effect — it guards client-initiated subscribes, which this transport has none of |
| Recovery with the current epoch and an offset inside history | `recovered: true`, the missed publications in the connect answer, and the *requested* offset echoed back |
| Recovery with a wrong epoch, and with an offset older than history | `recovered: false` both times, with the channel's current position |
| An idle stream for 11 seconds with `ping_interval: 3s` | Nothing at all: no application pings on this transport |
| A graceful `SIGTERM` to a node with a listener attached | `3001 shutdown`, with `reconnect: false` — which is why the code range is what the client reads |
| An expired token | `3005 connection expired` |
| The same `idempotency_key` published to node A and then node B | The same offset, one entry in history — dedup works across nodes on the Redis engine |
| A publication on node A with listeners on A and B | Both received it at the same offset in the same millisecond |
| A client that stops reading its socket, `queue_max_size: 16 KiB` | Closed with `reason: "slow"`, while a healthy client on the same node received all 60 publications |
| The same client with `queue_max_size: 256 KiB` | **Survived ≥ 8 MB** — the transport's own window buffers it long before the broker's queue grows, which is why the shipped value is 64 KiB and why the wiki says what this bound does and does not protect |
| `POST /api/history` on a channel with binary payloads | `500` — the JSON API cannot render a protobuf document. With `"limit": 0` it answers the epoch and offset, which is the supported way to ask whether a channel moved |
| `centrifugo checkconfig` with `ping_interval` shorter than `pong_timeout` | Accepted; the server then refused to start. A configuration is validated by starting it |

A native end-to-end run of the whole thing, with the real gateway binary, two broker nodes and
Redis: register a publisher, publish a manifest and a proposal, watch both arrive on two listeners,
stop a node, see the surviving one keep serving, and withdraw the proposal.

## Verified by hand, for the hints (SEE-92)

The relay's own tests pin the message; these are the things that needed the real binaries.

| What was asked | What happened |
| --- | --- |
| The real gateway binary with a relay configured, and a publication | One POST to `/v1/projects/<project>/messages:send` with `Bearer <token>`, and a body of exactly `{"message":{"topic":"feed.sandbox.<uuid>","data":{"kind":"feed_invalidation","version":"1"},"android":{"collapse_key":"seeker-vault-feed-invalidation-v1","priority":"HIGH","ttl":"300s"}}}` |
| The token the gateway presented | A real RS256 assertion, exchanged at the endpoint named in the credential file, verified against the key's public half by the stand-in |
| The same publication with the broker also configured | Both went out: the event on the broker at its next offset, the hint to the topic. Neither waited for the other |
| The stand-in answering `500`, then `401`, then nothing at all | The notice was cleared each time and the document stayed published — a hint never defers the outbox. The `401` was retried once with a freshly minted token |
| Two publications a second apart, with the shipped quota | One hint. The second was logged as coalesced, and the document was still stored, still streamed and still readable |
| A gateway started with `BROADCAST_PUSH_CREDENTIALS` pointing at a file that is not a credential | The process refused to start, naming the field and quoting nothing |
| `GetFeedTopics` against a gateway with no relay | `501 unimplemented` with `no_push`, while the same gateway still granted a stream ticket and answered every read |

## Verified by hand, against the live provider (SEE-93)

Nothing here spends anything: a quote is a public read, a build returns unsigned bytes, and no key
was anywhere near it. Recorded on **2026-09-17** against `https://lite-api.jup.ag`.

| What was asked | What happened |
| --- | --- |
| `GET /swap/v1/quote` keyless, with `onlyDirectRoutes` and `asLegacyTransaction` | `200`, a one-hop route, `swapMode: ExactIn`, `platformFee: null` |
| `POST /swap/v1/swap` for the same quote, four shapes: SOL in, SOL out, an output account that does not exist yet, and `useSharedAccounts=false` | `200` each time, a **legacy** transaction each time, `addressesByLookupTableAddress: null` each time. Committed as `fixtures/jupiter/swaps.json` |
| The phone's own reader over those four | Every instruction accounted for, nothing left over, and `Verdict.Verified` for all four. The route's source and destination are the owner's own derived token accounts; the floor the instruction enforces equals the provider's stated threshold to the base unit in all four |
| The capture script's independent reader over the same bytes | The same instruction for the same instruction, in another language: `budget, budget, wrap, sync, shared_route, unwrap` and the three other shapes |
| `JupiterLiveTest` with `-Dseekervault.jupiter=https://lite-api.jup.ag` | **PASS** — one real quote, one real build, and the review verified the result |
| A build for a wallet holding nothing, with `dynamicComputeUnitLimit` | The provider's own simulation failed and said why ("Attempt to debit an account but found no record of a prior credit"), so nothing was prepared. This is how "insufficient balance" is detected by a phone that reaches no chain |
| `api.jup.ag/swap/v2/order` keyless | `200`, so v2 exists — and it is a combined quote-and-build tied to Jupiter's own `/execute`. v1 is what this app pins, and why is in `docs/integrations/jupiter.md` |
| A signed transaction, on chain | **NOT RUN.** No funds and no wallet here; step 6 below |

## Verified by hand, against the live prediction API (SEE-94)

Nothing here places an order: a market read is public, an order build returns unsigned bytes, and the
one order requested was for a wallet holding nothing. Recorded on **2026-09-17** against
`https://lite-api.jup.ag`, with lookup tables read from `https://api.mainnet-beta.solana.com`.

| What was asked | What happened |
| --- | --- |
| `GET /prediction/v1/events` and `/markets/{id}` keyless | `200` both, with real markets, statuses, prices and rules. No API key anywhere |
| `POST /prediction/v1/orders` keyless, for a wallet with a balance | `200`, with an unsigned transaction, the order and position accounts, and the order's own numbers |
| The transaction's shape | **Versioned (v0) with address lookup tables, every time.** `asLegacyTransaction`, `legacyTransaction`, `useLookupTables:false`, `asLegacy`, `transactionVersion:"legacy"` and `maxAccounts` were each tried and all are ignored — which is the finding that decided this ticket's design |
| Its signatures | Two slots, and the protocol's own **already filled**: `requiredSigners` lists the owner alone |
| `getMultipleAccounts` for the tables the message names | `200`, each owned by `AddressLookupTab1e1111111111111111111111111`, each a whole number of 32-byte addresses after a 56-byte header |
| The rebuilt account list | 16 static accounts plus 4 tables resolved to 42; every instruction's every index landed inside it, program included |
| The order instruction's fields against the provider's own JSON | Every one matched exactly: the external order ID, the market hash, the side, the contracts, the price ceiling, the cost and the slippage. Committed as `fixtures/jupiter/orders.json` with its tables |
| Whose accounts they are | The payer and the order's owner are the wallet; the stake leaves the wallet's own derived account for the deposit mint and lands in the very account the order spends from; the contracts go to the order's own account |
| `POST /prediction/v1/orders` for a wallet holding nothing | `400` with `INSUFFICIENT_FUNDS` — the provider checks balances, which is how a phone that reaches no chain can honestly report one |
| Whether the market hash can be derived from the market ID | **No.** md5, sha1, sha256 and blake2s were all checked against a real pair; none matches. Recorded as a limit rather than glossed over |
| Whether a market page can be verified | **No.** `https://jup.ag/prediction/<marketId>` is a real route and echoes the market ID into its page, but the site answers `200` for a market that does not exist. So the handoff is the market, for an ID the provider answered about, and no position URL is invented |
| `JupiterLiveTest` with `-Dseekervault.jupiter=https://lite-api.jup.ag` | **PASS**, both cases: the swap path, and a live market plus an order refused for want of funds |
| A signed order, on chain | **NOT RUN.** No funds and no wallet here; step 7 below |

## Verified by hand, with the real gateway and the real template (SEE-95)

The publisher template is a service, so its acceptance is a run rather than a description: two real
binaries, two databases, a credential the gateway's own tool issued, and the feed read back the way
a phone reads it. Everything below happened on this machine on 2026-09-17
([`docs/changelog/2026-09-17.md`](../changelog/2026-09-17.md) has the commands).

| What was asked | What happened |
| --- | --- |
| `broadcastctl register --server <uuid>` | One credential, printed once, for `server/3f1b2c4d-…` |
| The template started with it | Manifest published — `stored`, revision 1 — and the feed reference printed on stdout: `seekervault://feed?v=1&gateway=http%3A%2F%2F127.0.0.1%3A8090&server=3f1b2c4d-…` |
| The template pointed at the **read** origin instead of the publisher API | Refused at startup: `unimplemented`, `404`, with the line naming `PUBLISHER_PUBLISH_URL`. **This is why that setting exists**; it was found by running the thing rather than by reading it |
| `publishctl create` (CLI), a SOL → USDC signal with seven terms | `201`, revision 1, `publication: published` |
| The same create again with the same `Idempotency-Key` | `200`, `idempotent: true`, the same proposal ID, and **no second document at the gateway** |
| The same key with a different statement | `409 key_reused`, and nothing stored |
| `curl POST /v1/signals` (API), a JUP → USDC signal | `201`. Both signals in the feed, read with no credential at all |
| A wallet address in the body | `400`: "there is no field \"wallet\" in a signal…" |
| No token | `401` |
| `publishctl update` with a different slippage | `200`, revision 3, published |
| Re-posting the **current** statement unchanged | `200`, `changed: false`, no publication — the template's log shows four publications in total for two creates and two real updates |
| Restarting the template | Nothing republished: `pending: 0`, and the channel's `snapshotSequence` stayed where it was. A restart is not an event on anybody's phone |
| `publishctl cancel`, then again | Revision 4 `cancelled` in the feed; the second withdrawal changed nothing; updating a withdrawn signal was `409` |
| The gateway **stopped**, then a signal published | `202`, `publication: pending`, `attempts: 1`, with the next attempt's time. `GET /v1/status` said `pending: 1` |
| The gateway brought back | The signal appeared in the feed on its own, `snapshotSequence` +1 — one publication, no duplicate, nothing asked of the caller |
| `caddy validate` on both Caddyfiles, `docker compose config` on the base and the public overlay | Valid, all four, with no daemon |

What is left is the part that needs two phones, which is step 8 below.

## Verified by hand, with the real prediction template (SEE-96)

The second template discovers what to publish, so its acceptance needs a real listing as well as a
real gateway. Everything below happened on this machine on 2026-09-17 and 2026-09-18: the real
gateway binary on two loopback ports, the real template binary, and **the live keyless provider**
for everything except the closure path — which needs a market to close on cue, and so was run
against a local stand-in serving the provider's own answer shapes.

| What was asked | What happened |
| --- | --- |
| The template started with `PREDICTION_CATEGORIES=economics`, `PREDICTION_KEYWORDS=fed`, `PREDICTION_MOST_OPEN=3` | Manifest published — `stored`, revision 1 — the feed reference printed on stdout, and the filters in a log line of their own |
| Its first cycle, against the live provider | Four pages, 100 events, **684 markets considered, 36 matched, 3 published, 33 skipped** for the ceiling — and the reasons counted: `no_keyword` 476, `closes_too_late` 126, `not_tradeable` 46 |
| The documents the gateway then held | Three proposals, `operation: prediction`, `pluginId: jupiter.prediction`, each with the seven terms and an expiry equal to **the market's own close time** (`2026-09-30T12:00:00Z`) |
| The note in one of them | `Markets I follow. Not advice.` (the operator's line) then `Listed on Jupiter Prediction as "How low will 5-year Treasury yield get in September? — Below 4.20%" (economics), closing 2026-09-30T12:00:00Z. Its state, prices and rules are read on your own phone; which side to take, and how much, is yours.` |
| The feed read **twice, with no credential**, by two independent clients | **Byte-identical**, 3017 bytes each, `snapshotSequence: 4` |
| `publishctl create` — a caller trying to publish a signal | `403 written_by_discovery`, nothing stored, and the detail naming `GET /v1/discovery` |
| `publishctl discovery` | The filters, the last cycle with its reasons, and the three markets with their state, close time, generation and **the link to the provider's own page** — which is in no published document |
| `publishctl poll` | A cycle now: the same 684 considered, the same 36 matched, `created: 0`, `updated: 0`. `snapshotSequence` stayed at 4 — **a cycle that finds the same markets wakes nobody** |
| A second `poll` while one was running | `409 busy` |
| Restarting the template | `markets: 3`, `pending: 0`, the next cycle `created: 0`, and the manifest not republished at all. `snapshotSequence` stayed at 4 |
| The provider made **unreachable** (`PREDICTION_PROVIDER_URL` pointed at a closed port) | The cycle was `failed` with `provider_unreachable` and the dial error, `cancelled: 0`, and all three signals still `open` and `published`. **An outage withdraws nothing** |
| A tracked market **gone from the listing**, still open when asked directly | `checked: 1`, `cancelled: 0`, the signal still open at revision 1, and the row's `last_checked_at` set. **Absence is not closure** |
| The same market gone from the listing and **closed** when asked directly | `cancelled: 1`: revision 2, `PROPOSAL_STATUS_CANCELLED` in the feed |
| Another cycle with it still closed | `cancelled: 0`, nothing republished — a withdrawal happens once |
| The market **listed again, open** | A **new** proposal at revision 1 with a new ID, generation 2 on the row, and the withdrawn one still readable as cancelled. A withdrawal is final for every phone that saw it |
| `caddy validate` on `Caddyfile.prediction`, `docker compose config` on `compose.prediction.yaml` | Valid, with no daemon |
| `SEEKERVAULT_JUPITER=1 go test ./internal/jupiter/ -run Live` | **PASS** — a live listing and a live market read, with every field discovery depends on present |

What is left is the part that needs two phones, which is step 9 below.

## Verified by hand, for the two promises (SEE-97)

The environment contract spans the manifest, the templates, the gateway and the phone. Everything
except the phone's half can be run here, and was, on **2026-09-18**: the real gateway binary on two
loopback ports and the real CopyTrading template binary against it.

| What was asked | What happened |
| --- | --- |
| The template started with `PUBLISHER_ENVIRONMENT=sandbox` | `publishing as this server` with `"environment":"sandbox"`, the manifest published at revision 1 |
| What a phone would read: `FeedService/GetServerManifest` | `"environments": ["SERVER_ENVIRONMENT_SANDBOX"]` — one value, and the one the deployment was configured with |
| `publishctl status`, and `GET /v1/manifest` | `"environment": "sandbox"`, and `"environments": ["sandbox"]` rendered from the document that will be published rather than from the setting beside it |
| Restarting it with `PUBLISHER_ENVIRONMENT=production` against **the same database** | Refused at startup: `the database was created for the other environment: it is a sandbox database, and PUBLISHER_ENVIRONMENT is production` |
| A **fresh** database, the same server ID and the same credential, as production — a publisher trying to promote itself at the gateway | The gateway refused: `other_environment`, `permanent: true`, field `environments`, and the template said `nobody can subscribe to this publisher until its manifest is published` |
| What a subscriber read after that refusal | Still `SERVER_ENVIRONMENT_SANDBOX` at revision 1, with the old display name: **nothing was written** |
| A signal published in sandbox, then read back through the feed | Twelve fields, and the words `sandbox`, `production` and `environment` appear **nowhere** in the gateway's answer: a proposal carries no environment, because its publisher's manifest already says which one it is |
| `grep PUBLISHER_ENVIRONMENT publisher/.env.example publisher/.env.prediction.example` | Both `sandbox`: a copied example is a demonstration, and production is a deliberate edit |

The phone's half — a rehearsal with no wallet, an unmistakable result, and a switch that invalidates
what was prepared — is covered by the automated suite (`OperationViewModelTest`,
`ProposalBindingTest`, `ConnectionManifestTest`, `ConnectionStoreTest`, `ProposalScreensTest`,
`ConnectionDetailsScreenTest`) and by step 10 below, which is the part only a person with a phone
and a wallet can do.

## The device run (for the owner)

The automated tests cover the phone's client against real gRPC framing over TLS and HTTP/2, and
against two real broker nodes on a laptop. What only a phone can answer is whether all of that holds
over a real certificate, a real network and the app's own lifecycle.

1. **Deploy the public stack** on a host whose domain resolves to it:

   ```sh
   cd broadcast
   cp .env.example .env         # fill in BROADCAST_DOMAIN, ACME_EMAIL, and the two broker keys
   docker compose -f compose.yaml -f compose.public.yaml up -d --build
   docker compose run --rm ctl register --server <uuid> --label "copy trading"
   ```

2. **Publish something** with the credential that printed. The Go template is the way to do it
   (SEE-95, [`docs/development/publisher.md`](../development/publisher.md)); any Connect client
   would also do:

   ```sh
   cd ../publisher
   cp .env.example .env         # PUBLISHER_SERVER_ID, PUBLISHER_GATEWAY_URL, PUBLISHER_ENVIRONMENT,
                                # BROADCAST_CREDENTIAL, and a PUBLISHER_API_TOKEN of your own
   docker compose up -d --build
   docker compose run --rm ctl reference     # the line the phone scans or pastes
   docker compose run --rm ctl create --in 2h --note "trimming SOL into USDC" \
     --term input_mint=So11111111111111111111111111111111111111112 --term input_decimals=9 \
     --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v --term output_decimals=6 \
     --term max_slippage_bps=50
   ```

3. **Add the feed on the phone** from `seekervault://feed?v=1&gateway=https://<domain>&server=<uuid>`
   and check, with the app in the foreground:
   - the feed's settings and its proposals appear — those are unary reads;
   - publishing a new revision from the server shows up **without touching the phone**;
   - withdrawing it shows up the same way;
   - locking the screen and coming back leaves the feed current;
   - flight mode for a minute, then back: the feed is current again, and the app is not stuck;
   - removing the feed stops it, and a paired sidecar connection (if you have one) keeps working
     throughout — the two transports share nothing.

4. **Check what the certificate does.** Point the same reference at a host whose certificate the
   phone does not trust: the feed must refuse to resolve, and the refusal must say so rather than
   look like an outage.

5. **Check the hints (SEE-92).** This needs the APK built with the same Firebase project's
   `google-services.json` that the relay's credential belongs to, and the push overlay running
   (`docs/guides/firebase.md#configure-the-relay`):

   ```sh
   docker compose -f compose.yaml -f compose.public.yaml -f compose.push.yaml up -d --build
   ```

   With the feed added and the app **closed** (not force-stopped), publish a proposal from the
   server and check:
   - a notification appears on the **Proposals waiting for review** channel, naming the feed and
     nothing about the proposal;
   - tapping it opens that feed, and nothing is prepared, signed or sent by opening it;
   - withdrawing the proposal from the server, or dismissing it on the phone, removes the alert on
     the next read;
   - publishing twice within ten seconds produces one wake-up, not two — the quota is doing its job,
     and both documents are there when the feed is read;
   - **two devices** on the same feed both get it, and neither ever sent anything to the publisher:
     the only calls the phone makes are to the gateway, which is what `adb logcat` and the gateway's
     own access log show;
   - removing the feed stops the alerts, and a hint that arrives afterwards changes nothing and
     unsubscribes the stale topic;
   - **force-stop** the app from Android Settings and publish again: nothing arrives, which is the
     documented limitation rather than a failure. Opening the app reads the feed and catches up.

6. **Make one real swap (SEE-93).** This is the only step that spends anything, and it is the only
   thing the automated checks cannot reach: everything up to the signature is covered against the
   live provider, and the signature itself needs a wallet with funds on mainnet.

   Publish a swap signal whose terms name two mints the owner actually holds — a small amount of a
   liquid pair, USDC to SOL or back — then on the phone:
   - open the feed's **Signals**, open the signal, and read what the publisher said;
   - enter a **small** amount, and check that the review shows the least you would receive, the
     quote, the price movement allowed and the priority fee, all read out of the transaction;
   - check the **facts** name your own wallet as both payer and receiver;
   - wait more than a minute and tap **Approve and swap**: it must refuse with "the quote and the
     transaction have expired" and nothing may reach the wallet;
   - prepare again and approve within the minute: the wallet opens with that transaction, and
     approving it there sends it. Record the signature;
   - check Activity: one **operation** record, `Sent`, with the plugin, the revision, the wallet and
     the amount you chose — and no explorer claim that it succeeded, because this app does not
     follow a swap to the chain;
   - open the signal again: it says you acted on it, and there is **no second Approve**;
   - on a **second device** with a different wallet, act on the same signal with a *different*
     amount, and check that neither phone shows anything about the other;
   - check the publisher's own logs and the gateway's access log: neither has your address, your
     amount or your signature anywhere in it.

   Also try the refusals, which cost nothing: an amount larger than the wallet holds (the provider's
   simulation should refuse it before the wallet opens), a wallet selected for devnet (refused
   before anything is asked), and a signal whose `input_mint` is a ticker rather than a mint (read
   in full, with no Prepare offered).

7. **Place one real prediction order (SEE-94).** This needs a build with a Solana endpoint
   configured, because the phone cannot review an order without resolving its lookup tables:

   ```sh
   android/gradlew -p android :app:assembleDebug \
     -Pseekervault.solanaRpc=https://<your-endpoint>
   ```

   First check the refusals, which cost nothing:
   - install a build with **no** endpoint configured and prepare an order: it must refuse with "this
     build has no Solana endpoint configured" and offer nothing to sign;
   - point the endpoint at something unreachable and prepare again: it must refuse with the
     endpoint's own reason, and still offer nothing;
   - publish a signal naming a market that does not exist: "the provider has no such market";
   - publish one naming a market that has settled: "this market is no longer open";
   - stake more than the wallet holds: the provider's own refusal, before the wallet opens.

   Then, with an endpoint configured and a market that is open:
   - open the feed's **Signals**, open the market signal, and read what the publisher said;
   - pick a side and a **small** stake, at least the five-dollar minimum, and prepare;
   - check the review shows the contracts, what the order costs, what it pays out if that side
     wins, and the price ceiling — and that "accounts read from lookup tables" is not zero, which is
     the resolution having happened;
   - check the facts name your own wallet as payer and the **order's own account** as what receives;
   - tap **Approve and swap**, approve in the wallet, and record the signature;
   - check **Where to look now**: the transaction opens on the explorer, and "This market on
     Jupiter" opens the market. There must be **no** position link and no claim that the order
     filled;
   - check Activity: one **prediction** record, `Sent`, with the side and stake you chose and the
     order and position accounts — and confirm those are still there after force-stopping and
     reopening the app;
   - open the signal again: it says you acted on it, and there is **no second Approve**;
   - and confirm the app tells you nothing further over the following minutes: no fill, no position
     value, no settlement, no payout. Continuing happens in Jupiter, which is the whole design.

8. **Two phones, one signal, two amounts (SEE-95).** This is the acceptance criterion no machine
   here can run, and it is the whole point of the stage:
   - add the same feed reference on **two** phones, with two different wallets;
   - publish one signal from the template, and check that both phones show the **same** terms, the
     same note and the same expiry — the document is one document;
   - on each phone, enter a **different** amount, within the publisher's bounds, and act on it;
   - check that neither phone shows anything about the other's amount, decision or result, and that
     the signal's own screen on each phone reflects only that phone's own action;
   - then check the template: `publishctl show <id>` reports what **it** published and what the
     gateway confirmed, and nothing about either owner. `publishctl status` counts signals, not
     subscribers;
   - grep the template's log and its database for either wallet address, either amount and either
     signature. There must be nothing: `sqlite3 publisher.db .schema` has no column that could hold
     one.

9. **Two phones, one discovered market, two sides (SEE-96).** The same acceptance as step 8, for the
   template that finds its own signals — and the difference is what each phone does with one
   document:

   ```sh
   cd publisher
   cp .env.prediction.example .env.prediction   # its own server ID, its own credential
   docker compose --env-file .env.prediction -f compose.prediction.yaml up -d --build
   docker compose --env-file .env.prediction -f compose.prediction.yaml run --rm ctl poll
   docker compose --env-file .env.prediction -f compose.prediction.yaml run --rm ctl discovery
   ```

   - add **this** publisher's reference on both phones (it is a second publisher, so it is a second
     connection);
   - check that both phones show the **same** market, the same note and the same expiry, and that
     each of them shows the market's *current* state — the price of each side, the rules, the close
     time — which each phone read from the provider itself, not from the document;
   - on one phone take **YES**, on the other take **NO**, with different stakes, and act on both;
   - check that neither phone shows anything about the other's side, stake or result;
   - then check the template: `ctl discovery` reports the markets it is tracking and what it
     published, and nothing about either owner; `ctl status` counts markets and signals, not
     subscribers;
   - grep the template's log and its database for either wallet address, either side, either stake
     and either signature. There must be nothing, and `.schema` has no column that could hold one;
   - wait for a cycle (or run `ctl poll`) and check that **nothing on either phone changes**: a
     cycle that finds the same market again is not an event;
   - finally, the closure: when that market closes for real — or, to see it on cue, with
     `PREDICTION_STATE` left at `open` and the market's event over — check that the next cycle
     withdraws the proposal, that both phones show it as withdrawn, and that **neither offers to
     place a new order from it**, while what each of them already did stays in its own Activity.

10. **A sandbox rehearsal, and then production on purpose (SEE-97).** The two promises, in the
    order anybody sane would take them. Do this one **first**, before steps 6 to 9: a sandbox feed
    is how the whole path can be watched before any of it costs anything.

    ```sh
    cd publisher
    cp .env.example .env            # it ships as PUBLISHER_ENVIRONMENT=sandbox
    docker compose up -d --build
    docker compose run --rm ctl reference   # the feed to add on the phone
    ```

    With that feed added:
    - check the feed's **details**: it says **Sandbox**, with the sentence saying what that means.
      A publisher that serves only sandbox offers no switch, because there is nothing to choose;
    - open a signal and prepare it. Everything must be there and be real: the live route or market,
      the exact transaction, the facts read out of the bytes, your own rules under them. This is the
      demonstration, and a thin version of it would demonstrate nothing;
    - check the banner above it, and that the button says **Simulate** rather than Approve;
    - tap it. **Your wallet must not open.** No signature, no explorer link, and the signal now
      says `Simulated. This feed is a sandbox, so nothing was signed and nothing was sent.`;
    - check Activity: one record, `Simulated`, with **What the feed did: Sandbox** beside the
      network, no signature and **no explorer link**. Confirm it is still that after force-stopping
      and reopening the app;
    - open the signal again: there is no second action. A rehearsal is an execution, and there is
      one per signal;
    - check that nothing was counted: a daily spending threshold you have set must be untouched.

    Then production, deliberately, and only when you mean it:
    - run a **second** deployment, with its own server ID, its own credential, its own database and
      `PUBLISHER_ENVIRONMENT=production`, and add **its** reference as a second connection. The same
      publisher cannot be promoted: its own database refuses, and the gateway refuses the manifest;
    - if you run a publisher that serves **both**, switch the connection on its details screen and
      check that anything already prepared is discarded and asks to be prepared again;
    - then do steps 6 to 9 on that production feed, with **real funds and a deliberately small
      amount**. That is the only run that spends anything, and it is recorded separately below.

Record the date, the app build, the Solana endpoint used, the gateway, broker and Firebase project,
the publisher's server ID and environment (**both** publishers, if you run the prediction template
too — and note which runs were sandbox rehearsals, which cost nothing, apart from the production
ones, which do), the pair and amount swapped, the market and stake ordered, and both transactions'
signatures — as `docs/testing/stage-5-3.md` does for the direct path.

## Not covered here

- Load, isolation and failover at size: SEE-99.
- The direct-mode and gateway-mode comparison, the privacy sweep and MCP compatibility: they are
  SEE-98's, and they are done — `pnpm test:integration` runs them in one command and
  [`see-98.md`](see-98.md) is the report, including its own device checklist for the mixed-mode and
  notification-tap steps.
- The server development guide the steps above will eventually live in: SEE-100.
