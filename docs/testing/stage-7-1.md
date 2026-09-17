# Stage 7.1 — the broadcast gateway, its stream, and its hints

What was verified for SEE-90, SEE-91 and SEE-92, what was verified by hand, and what is left for the
owner to run on the phone. The automated checks are `pnpm check`, `pnpm check:broadcast` and
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

Record the date, the app build, the gateway, broker and Firebase project, and what each step did —
as `docs/testing/stage-5-3.md` does for the direct path.

## Not covered here

- Load, isolation and failover at size: SEE-99.
- The direct-mode and gateway-mode comparison, and MCP compatibility: SEE-98.
- The server development guide the steps above will eventually live in: SEE-100.
