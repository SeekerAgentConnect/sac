# Stage 7.1 — the broadcast gateway, its stream, its hints, and the two Jupiter plugins

What was verified for SEE-90 to SEE-94, what was verified by hand, and what is left for the owner to
run on the phone. The automated checks are `pnpm check`, `pnpm check:broadcast` and
`pnpm check:android`; this page is about the rest.

## What no machine here could run

- **Docker.** No daemon is reachable on the machine these checks ran on (`docs/testing/stage-7.md`
  records the same limit). `docker compose config` accepts the base file and the public overlay,
  `caddy validate` and `caddy fmt` accept both Caddyfiles, and `centrifugo checkconfig` accepts
  `broadcast/centrifugo.yaml` — and then every binary was **run natively** instead: the gateway, two
  broker nodes and Redis.
- **A physical Seeker.** The device run below is the owner's.
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

2. **Publish something** with the credential that printed: a manifest, then a proposal. (A Go
   publisher template is SEE-95; until then, any Connect client will do.)

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

Record the date, the app build, the Solana endpoint used, the gateway, broker and Firebase project,
the pair and amount swapped, the market and stake ordered, and both transactions' signatures — as
`docs/testing/stage-5-3.md` does for the direct path.

## Not covered here

- Load, isolation and failover at size: SEE-99.
- The direct-mode and gateway-mode comparison, and MCP compatibility: SEE-98.
- The server development guide the steps above will eventually live in: SEE-100.
