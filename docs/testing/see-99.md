# SEE-99 — load, isolation and failover, measured

What the broadcast transport did when it was driven: the numbers, the topology they were taken on,
where the climb stopped, what stopped it, and what this run could not answer. Nothing here is a
capacity claim — it is a record of one machine, and `pnpm test:load` is how to take it again on
another.

## The revision tested

| | |
| --- | --- |
| Branch | `superset/feat/see-85`, on top of `37496bd` |
| Tree | the working tree that this change and its documentation make up. The harness prints the commit it was run at, and for this run it printed `37496bd6…` **with uncommitted changes** — those changes being this one |
| Stage 7.1 in the tree | SEE-90 to SEE-98 |
| Date run | 2026-09-18 |
| Command | `SEEKERVAULT_CENTRIFUGO=… SEEKERVAULT_REDIS=… pnpm test:load -- --report see-99.json` — one run, fourteen scenarios, in the order below |
| Result | **14 of 14 PASS.** One stage of the ramp is deliberately UNHEALTHY: that is where the climb stopped |

### The machine

| | |
| --- | --- |
| Hardware | Apple M1 Max, 10 processors, 32 GiB of memory |
| System | macOS (Darwin 25.5.0), `ulimit -n` 1048576, ephemeral ports 49152–65535 held 30 s (`net.inet.tcp.msl` 15000) |
| Docker | **none reachable**, as in every Stage 7 record. Everything was run natively |

### The binaries

| | Version | How it was obtained |
| --- | --- | --- |
| The gateway and `broadcastctl` | this tree | built by `pnpm test:load` from `broadcast/` |
| The harness | this tree | built by `pnpm test:load` from `loadtest/` |
| Go | 1.27.1 | the official `darwin-arm64` archive, SHA-256 verified against `https://go.dev/dl/?mode=json` |
| Centrifugo | **v6.9.6** (`Go version: go1.26.8`) | the release archive, SHA-256 verified against `centrifugo_6.9.6_checksums.txt`. It is the version `broadcast/compose.yaml` runs and the one `third_party/centrifugo` holds the client schema of |
| Redis | **8.2.10** | built from `https://download.redis.io/releases/redis-8.2.10.tar.gz` (`make MALLOC=libc`). `broadcast/compose.yaml` pins `redis:8.2-alpine`; this is that line's current patch |

### The configuration measured

The **shipped** one. `broadcast/centrifugo.yaml` unchanged for every broker node, the settings
`broadcast/compose.yaml` gives Redis, the gateway's own defaults except where a profile widens them
(and every profile's numbers are echoed into the report). A publisher exists only because
`broadcastctl register` created one.

One setting is turned on that a deployment does not: **`prometheus.enabled`** on each node. The
broker's server API answers its counters from an aggregate it refreshes every sixty seconds, so a
fifteen-second window reads zero for all of them; the metrics endpoint answers live. It is on the
API port, which is private in every deployment here, and it adds no transport and changes no
channel rule.

## What every number means

**Publish-to-receive is one subtraction on one clock.** The publisher and every listener are
goroutines in one process: the send time is taken just before the publish call, the arrival time
when the bytes are decoded. No clock synchronisation is assumed and nothing is compared across
machines. What the number includes is the gateway's commit, the outbox drainer's pass, the broker's
fan-out and this process's own decode — a phone's latency minus the phone.

**The quantiles are observations, not estimates.** Every sample is kept and the quantile is the
sample at that rank (past two million, a uniform sample of the whole, and the report says so).

**"Delivered" is counted where the delivery happens** — an arrival on the stream, whatever the
apply rule then does with it. A share above 1.0 means publications from before the window arrived
inside it; a share below means a listener was still connecting, or its continuity broke and it read
the authoritative snapshot instead. Both are stated per scenario rather than averaged away.

**The client is the app's client.** `loadtest/internal/listen/policy.go` is a port of
`android/.../feeds/FeedRecovery.kt`: the same disconnect-code ranges, the same test for whether
continuity was proven, the same 1 s → 30 s jittered backoff, with the Kotlin's own test cases ported
beside it. A harness with its own reconnect policy would have measured a client nobody ships.

## What was not in the path

- **The proxy.** `broadcast/Caddyfile` is in front of all of this in a deployment; there is no
  Docker daemon here, so the hop is excluded and named rather than folded into a number. TLS
  termination, HTTP/2 proxying of the stream and Caddy's own limits are unmeasured.
- **Firebase.** SEE-99 requires a controlled push sender, so the **real** relay in the gateway —
  minting a real RS256 assertion — talked to a loopback stand-in that counts hints. How long a hint
  takes to reach a phone is Firebase's and the device's, is measured in seconds rather than
  milliseconds, and is **not** in any number here.
- **A real phone, a real network, a real provider, a wallet.** No device, no mobile network, no
  Jupiter traffic, no signature, no funds. Every document published was synthetic and every
  publisher promised `SERVER_ENVIRONMENT_SANDBOX`.

## Scenario by scenario

Every listener count is simultaneous. "Converged" means each listener's applied set was compared,
document by document, with what the gateway holds — the gateway being the authority — after the
window and the settle.

| Scenario | Workload | Delivered | p50 / p95 / p99 | Converged | What it establishes |
| --- | --- | --- | --- | --- | --- |
| `steady` | 200 listeners, one channel, 1 KiB every 200 ms | 16000 / 15800 (1.013) | 6 ms / 10 ms / 24 ms | 200 / 200 | The baseline: one broker node fans a hot channel out to two hundred phones in single-digit milliseconds |
| `spread` | 240 listeners over 8 channels, two feeds each, 2 KiB | 15360 / 15360 (1.000) | 12 ms / 23 ms / 30 ms | 240 / 240 | Many publishers cost linearly and nothing crosses between them |
| `mixed` | 120 listeners, 4 publishers, 4 KiB, revisions + withdrawals + manifests + 8 readers + the relay | 10590 / 10560 (1.003) | 7 ms / 14 ms / 21 ms | 120 / 120 | The whole document lifecycle under load, with **28 hints on 4 topics** through the stand-in and **one** token exchange |
| `two-nodes` | the `steady` workload over two nodes on one Redis | 16000 / 16000 (1.000) | 5 ms / 9 ms / 11 ms | 200 / 200 | 100 clients each; node 1 accepted all 80 publications and **both nodes fanned out 80 and 79** — which is the whole of what Redis is there for |
| `drain` | node 2 `SIGTERM`ed 3 s in, started at 8 s | 16000 / 16000 (1.000) | 5 ms / 5.6 s / 7.1 s | 200 / 200 | `3001 shutdown` on all 100 of its listeners, every one back, **nothing lost**. The p95 is the recovery, not the transport |
| `kill` | the same node `SIGKILL`ed | 16000 / 16000 (1.000) | 5 ms / 5.4 s / 6.9 s | 200 / 200 | No disconnect frame at all — the connection simply ends — and the same outcome |
| `redis` | Redis stopped 3 s in, started at 8 s | 10014 / 16000 (0.626) | 5 ms / 18 ms / 7.2 s | 200 / 200 | All 200 closed with `3010 insufficient state`, the history was replaced 200 times, every listener read the authoritative snapshot (`epoch changed` ×200) and **held everything**. A third of the window's deliveries arrived as a read rather than a publication, which is what a recovery cache being a cache means |
| `gateway` | the gateway stopped 3 s in, restarted at 6 s on the same database | 12800 / 12800 (1.000) | 6 ms / 9 ms / 11 ms | 200 / 200 | 16 publications and 2 reads **refused with `unavailable`** rather than silently dropped; the documents survived the restart and the streams did not even break |
| `slow` | 8 of 40 listeners stop reading, 17 KiB every 20 ms | 23613 / 24040 (0.982) | 2 ms / 3.2 s / 5.4 s | 40 / 40 | The broker closed the stalled ones with **`3008 slow`** (14 in the window, 20 in the run) and the 32 that kept reading lost nothing. The stalled ones caught up from history afterwards |
| `flood` | one publisher at 5 ms against the gateway's **default** 2/s | 5460 / 5400 (1.011) | 2 ms / 6.3 s / 7.8 s | 240 / 240 | **11294 refusals with `too_many_requests`**, and its neighbours kept publishing and converging. 5142 more were refused `unavailable`: a flood also costs the gateway's accept queue, which is worth knowing |
| `isolation` | a publisher tries three ways into another's channel | 15420 / 15360 (1.004) | 10 ms / 20 ms / 26 ms | 240 / 240 | Their channel with my grant → `other_server`. My channel with a credential that never existed → `unauthenticated`. Their channel with their grant → **accepted**, which is the honest statement of what a credential is. No listener ever received a publication on a channel it was not granted |
| `reconnect` | every stream cut at once by restarting the only node | 16000 / 15800 (1.013) | 5 ms / 100 ms / 760 ms | 200 / 200 | 200 closes with `3001`, all 200 back, **p99 760 ms** — the storm is bounded by the client's own jittered backoff and cost the gateway nothing |
| `shared-address` | 400 listeners presenting **one** address | 4629 / 12000 (0.386) | 6 ms / 13 ms / 15 ms | 303 / 400 | The finding below: **1260 ticket requests and 194 snapshot reads refused**, 187 listeners on the stream at the end of a 15-second window |
| `ramp` | one channel, climbing to 10 000 | see below | see below | 10000 / 10000 | **Reached 5 000** |

Resource use, at the peak of each window, on this machine:

| Scenario | Broker | Gateway | Redis |
| --- | --- | --- | --- |
| `steady` (200) | 103 MiB, 830 ms CPU, 219 descriptors | 52 MiB, 300 ms | 6 MiB |
| `spread` (240, 8 channels) | 117 MiB, 850 ms, 259 descriptors | 76 MiB, 600 ms | 7 MiB |
| `mixed` (120, 4 KiB) | 101 MiB, 980 ms, 139 descriptors | 88 MiB, 1.17 s | 8 MiB |
| `two-nodes` (100 + 100) | 88 MiB + 85 MiB | 55 MiB, 240 ms | 6 MiB |
| `slow` (40 × 17 KiB every 20 ms) | 114 MiB, 1.75 s | 49 MiB, 1.22 s | 27 MiB |
| `ramp` (10 000) | **1.4 GiB, 6.14 s, 9887 descriptors** | 166 MiB, 2.02 s | 16 MiB |

## The climb

One channel, 1 KiB every 500 ms, listeners kept from the stage before — a phone that is already
connected stays connected, which is what growth looks like.

| Listeners | Published | Delivered | p50 | p95 | p99 | |
| --- | --- | --- | --- | --- | --- | --- |
| 250 | 21 | 5 250 / 5 250 (1.000) | 5 ms | 9 ms | 18 ms | healthy |
| 500 | 21 | 10 500 / 10 500 (1.000) | 6 ms | 11 ms | 15 ms | healthy |
| 1 000 | 21 | 21 000 / 21 000 (1.000) | 10 ms | 24 ms | 32 ms | healthy |
| 2 500 | 21 | 52 500 / 52 500 (1.000) | 25 ms | 74 ms | 81 ms | healthy |
| 5 000 | 21 | 105 000 / 105 000 (1.000) | 34 ms | 146 ms | 156 ms | healthy |
| 10 000 | 16 | 121 428 / 160 000 (0.759) | 180 ms | 8.1 s | 16.4 s | **stopped here** |

**Reached: 5 000 simultaneous listeners on one broker node**, with every publication reaching every
one of them and a p99 of 156 ms. The rule that stopped the next stage was the profile's own: a p99
over 3 s and a delivered share under 0.995.

**The first bottleneck was the gateway's unary read API, not the fan-out.** At 10 000, every stream
ticket in the window took **at least 5.08 s** (p50 9.15 s, max 20.9 s) — ten thousand listeners ask
for a grant at once, and `GetStreamTicket` reads the store. The gateway's read pool is **four SQLite
connections** (`broadcast/internal/store/store.go`), it used only 2 s of processor time and 166 MiB,
and its descriptor count never left double figures: it was queueing, not working. Raising that pool,
or answering a ticket without touching the store, is where the next order of magnitude is.

**The second was the broker's memory.** 1.4 GiB resident for 7 228 attached clients — roughly 200
KiB a connection — with 9 887 descriptors, and 5 128 listeners closed with `3010 insufficient state`
plus 2 009 with `3004 internal server error` as it shed what it could not hold. The 2 772 listeners
that never got on are the gap between 10 000 asked for and 7 228 attached.

**And the run still converged.** All 10 000 listeners ended the run holding every one of the
gateway's documents, by stream where the stream worked and by snapshot read (4 727 of them) where it
did not. Losing capacity did not lose state, which is the property the whole design rests on.

## What the transport did, under load

Confirmed by driving it, not by reading about it. The hand-verified table in
[`stage-7-1.md`](stage-7-1.md) established these one at a time; this is the same behaviour with
hundreds or thousands of listeners attached.

| What happened | What the broker did |
| --- | --- |
| A node drained with `SIGTERM` | `3001 shutdown` to each of its listeners, every one treated as reconnect-and-recover |
| A node killed with `SIGKILL` | Nothing at all: the stream ends and the client finds out from the transport (`listener.stream.failed` ×100) |
| Redis stopped under it | `3010 insufficient state` to every listener, and a **new epoch** when it came back |
| A listener that stopped reading, 17 KiB every 20 ms | `3008 slow`, and only for the ones that stopped |
| A node restarted with every listener attached | 200 closes and 200 returns, p99 of the whole disturbance 760 ms |
| One node accepting every publication, two nodes attached | Both fanned out every one (80 accepted on node 1; 80 and 79 fanned out) |
| The same run's `num_nodes` | 2 on both, so they really were one broker |

## Six deliberate breaks

Each one was made, the check was run, and the file was restored and compared byte for byte.

| The break | What failed |
| --- | --- |
| The epoch reset in `measure.Channel.Opened` reverted, so an offset is compared across a replaced history | `TestAChangedEpochResetsThePosition`: "the cursor is e2/50, and the old history's offset means nothing in the new one" |
| A real host name (`https://fcm.googleapis.com`) put into a shipped file | The boundary test, three ways: the host, the `fcm.` prefix and the `https://` scheme |
| Every **withdrawal** thrown away on arrival — the last document an identity ever hears | The convergence check, on every listener: "holds 59d62452… at 3, not 4" |
| The trespassing publisher aimed at its own channel instead of another's | `isolation.accepted.their_channel_my_grant: 1` |
| The slow scenario stalling nobody | "no listener ever stopped reading, so nothing was measured" |
| The flood publishing once every two seconds instead of every five milliseconds | "nothing was refused at 2s per publication, so the publish limit did not apply" |

**One break did its real job by passing.** Throwing away every *third revision* changed nothing: the
convergence check compares the **end state**, and a dropped document that a later revision of the
same proposal covers is invisible to it — correctly, because what converged is what the phone would
hold. Per-publication delivery is the `Delivered` share's business, not the convergence check's, and
the two are reported separately for that reason. Only after the break was aimed at the last document
an identity ever hears did the check fail.

## The limits a deployment should know about

1. **The read rate limit is per address, and a proxy decides what that address is.** 400 listeners
   behind one address got **1260 ticket refusals and 194 refused snapshot reads**, and 187 of them
   were on the stream after fifteen seconds; the rest were still backing off and retrying. On
   distinct addresses the same gateway served 5 000. A deployment serving an office, a campus or a
   carrier NAT needs `BROADCAST_READ_RATE`/`BROADCAST_READ_BURST` raised, or the limit keyed
   differently. The limiter also remembers at most 16 384 callers
   (`internal/gateway.MostKeys`), which is a bound worth knowing before it is the one that bites.
2. **A stream ticket costs a store read.** See the climb above: it is the first thing to queue.
3. **A channel holds at most 200 proposals** (`BROADCAST_MAX_PROPOSALS`), and a withdrawn proposal
   still occupies one until retention sweeps it. The `flood` scenario left 258 documents on eight
   channels in fifteen seconds; a publisher that cycles identities fast will meet that bound.
4. **A flood costs more than the publish limit refuses.** Eight publishers at 5 ms produced 11 294
   `too_many_requests` **and** 5 142 `unavailable`: the refusals are cheap, but the connections
   arriving to be refused are not free. The gateway spent 3.2 s of processor time on that window.
5. **Each listener is a connection on a broker node, at roughly 200 KiB.** Redis makes nodes
   interchangeable, not free.
6. **Ten thousand listeners is twenty thousand sockets.** On a loopback ephemeral range of about
   sixteen thousand ports, held thirty seconds after they close, a suite started too soon after a
   ramp cannot *connect* to anything (`can't assign requested address`). That is the machine, not
   the deployment; the harness now waits for the range to drain and retries, and the ramp is best
   run on its own.

## Unresolved failures and limitations

- **Nothing was run in Docker**, so the compose stacks, the Caddy hop, TLS and the container
  networking are all unmeasured here, as in every Stage 7 record.
- **Push latency is not measured and cannot be from here.** The relay's own behaviour is (it sent 28
  hints on 4 topics in the `mixed` window, with one token exchange, and nothing it sent named
  anything but a topic), but what happens after Firebase is Firebase's and the device's. Transport
  capacity and mobile push latency are different questions and this run answers only the first.
- **The provider side is not measured either.** An external trading provider's capacity is its own;
  no scenario here touches one, by requirement.
- **One machine, one architecture.** Every number above is an Apple M1 Max with everything on
  loopback, which flatters latency and punishes nothing over the network. It is a floor for the
  software and not a figure for a deployment.
- **The two-node comparison is of two endpoints, not of a load balancer.** A listener keeps the node
  it was given, because the thing that would move it is the proxy that is not in this path. The
  `drain` and `kill` scenarios are therefore the worst case: the drained node's listeners wait for
  it rather than being sent to its neighbour.
- **No scenario ran for longer than a few minutes.** Anything that only appears after hours — memory
  growth, retention sweeps, certificate renewal, a history that fills — is out of scope of this run.

## The device checklist (for the owner)

Everything above ran here. These are the questions a laptop cannot answer, and they are **NOT RUN**:

| | Step | PASS / FAIL / NOT RUN |
| --- | --- | --- |
| 1 | With the app open on a feed, publish from a deployment and see the document appear without a refresh | NOT RUN |
| 2 | Put the phone on a real mobile network, lock it for ten minutes, unlock it and confirm the feed catches up | NOT RUN |
| 3 | Switch from Wi-Fi to mobile data mid-stream and confirm one reconnect, not a storm | NOT RUN |
| 4 | Leave the app on the feed for an hour and confirm the ticket renewal is invisible | NOT RUN |
| 5 | With a real Firebase project, confirm a hint wakes a backgrounded phone and how long it takes | NOT RUN |
| 6 | Two phones on the same feed, both up to date, and neither able to tell the other is there | NOT RUN |

Tested at revision: the SEE-99 change on `superset/feat/see-85`, on top of `37496bd` — the harness
in `loadtest/`, the profiles in `loadtest/profiles.json`, and the two commands in
[`../development/load.md`](../development/load.md).

## What is not here

No capacity guarantee, no "highload-ready" claim, no custom broker, no Kubernetes, and no
production or third-party system attacked or load-tested. The run reaches nothing outside the
machine it is on: `loadtest/internal/drive/boundary_test.go` fails if a host name, a key, a
production environment or a second HTTP server ever appears in it.
