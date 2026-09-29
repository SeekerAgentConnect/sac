# The load run

`pnpm test:load` is SEE-99's measurement of the broadcast transport: the real gateway, the pinned
broker, real Redis and synthetic publishers, with simulated phones on the same stream a phone
listens on. It publishes, measures what arrives and when, breaks things on purpose, and prints
every scenario as **PASS**, **FAIL** or **NOT RUN**.

```sh
pnpm test:load                          # every scenario this machine can run
pnpm test:load -- --list                # the scenarios and the profiles
pnpm test:load -- --scenario drain      # one of them
pnpm test:load -- --report out.json     # the evidence, as JSON
pnpm check:loadtest                     # the harness's own tests; needs no broker
```

The report from the run these numbers were taken from is
[`../testing/see-99.md`](../testing/see-99.md).

## What it needs

**Go**, which builds the gateway, `feed-gatewayctl` and the harness. And, for anything that streams,
two services that are not vendored:

```sh
SEEKERVAULT_CENTRIFUGO=/path/to/centrifugo \
SEEKERVAULT_REDIS=/path/to/redis-server \
  pnpm test:load
```

- **`SEEKERVAULT_CENTRIFUGO`** is the pinned Centrifugo release — v6.9.6, the version
  `compose/feed/compose.yaml` in `do-deploy` runs and `packages/protocol/third_party/centrifugo/README.md` pins the client schema to.
  Verify the download against the release's own `centrifugo_6.9.6_checksums.txt` before using it.
  Without it, every scenario that streams is NOT RUN.
- **`SEEKERVAULT_REDIS`** is `redis-server`, matching the `redis:8.2` of `compose/feed/compose.yaml` in `do-deploy`.
  Without it the harness runs one broker node on the memory engine, and the two-node scenarios are
  NOT RUN: Redis is what makes two nodes one broker.

Nothing else is required. No network, no Docker daemon, no credential, no funds: both publishers run
as sandbox, the documents are synthetic, and the push relay — when a profile turns it on — talks to
a controlled stand-in on loopback.

## What a number means

**Publish-to-receive latency is measured on one clock.** The publisher and every listener are
goroutines in one process, so the send time is taken just before the publish call and the arrival
time when the bytes are decoded: one machine, one monotonic clock, one subtraction. No clock
synchronisation is assumed anywhere, and nothing is compared across hosts.

What that number **includes**, because it is not only the transport:

| Part of it | Where it is |
| --- | --- |
| The publish call, including the gateway's own commit | `services/gateway/internal/gateway/publisher.go` |
| The outbox drainer's pass — it wakes on a publication and sends up to 64 notices | `services/gateway/internal/dispatch` |
| The broker accepting the publication and fanning it out | `services/gateway/internal/stream`, Centrifugo |
| This process decoding the `FeedEvent` | `tools/loadtest/internal/listen` |

It is what a phone would see **minus the phone**. What a device and a mobile network add is not
measured here and is not guessed at either; neither is push latency, which is Firebase's and the
operating system's and is measured in seconds rather than milliseconds. Transport capacity, mobile
push latency and a trading provider's capacity are three different questions, and this run answers
the first.

Quantiles are exact rather than estimated: every observation is kept and the quantile is the
observation at that rank, up to two million of them, past which the series keeps a uniform sample
and the report says it did.

## What is in the path, and what is not

Everything in the path is the shipped thing. The gateway is the `broadcast` binary on its own SQLite
file; the broker nodes run `services/gateway/centrifugo.yaml` unchanged; Redis runs the settings
`compose/feed/compose.yaml` in `do-deploy` gives it; a publisher exists only because `feed-gatewayctl register` made one.

Three things are not:

- **Caddy.** `compose/ingress/feed/Caddyfile` in `do-deploy` is in front of all of this in a public deployment, and there is no
  Docker daemon on the machine these runs were made on. The proxy hop is therefore named as excluded
  rather than folded into a latency number. Point the harness at your own deployment (below) to
  include it.
- **Firebase.** SEE-99 requires a fake or controlled push sender, so the relay — the real one, in
  the gateway, minting a real RS256 assertion — talks to a loopback server that counts hints. That
  seam is the gateway's own: `BROADCAST_PUSH_ENDPOINT` names where the API is, and a credential's
  `token_uri` may be a loopback HTTP one for development.
- **A phone.** The harness's client makes the app's decisions — `tools/loadtest/internal/listen/policy.go`
  is a port of `feeds/FeedRecovery.kt`, with the same disconnect-code ranges, the same continuity
  test and the same 1 s → 30 s jittered backoff — but it is not the app. The app's own runtime is
  `pnpm check:android`, and its two-node broker test is
  `feeds/CentrifugoStreamIntegrationTest`.

One setting is overridden that a deployment does not set: **`prometheus.enabled`** on each broker
node. The broker's server API answers its counters from an aggregate it refreshes every sixty
seconds, so a fifteen-second window reads zero for all of them; the metrics endpoint answers live.
It is on the API port, which is private in every deployment this repository ships, and it adds no
transport and changes no channel rule. What a node reports about *itself* — clients, channels,
subscriptions — is still as of its own last aggregation, so it lags a few seconds behind the
harness's own listener count; both numbers are printed, and a difference between them at the end of
a short window is that lag rather than a lost connection.

**The synthetic publishers declare no Solana network** (SEE-174): their manifests' `feed.supported_networks` is empty, which a phone reads as "no networks declared" and never signs for ([supported networks](../wiki/server-manifests.md#supported-networks)). Nothing a run publishes is meant to reach a wallet, and `TestEveryPublisherItCreatesDeclaresNoNetwork` in `internal/drive/boundary_test.go` keeps it that way.

## The profiles

`tools/loadtest/profiles.json` is data rather than code, so the numbers behind a report can be read
without reading Go and a variant is an edit rather than a rebuild. A misspelled field is refused
rather than silently defaulted.

| Field | What it is | Default |
| --- | --- | --- |
| `publishers` | How many publishers, each owning one channel | 1 |
| `listeners` | How many simulated phones | 0 |
| `channelsPerListener` | How many feeds one phone holds; the gateway grants at most 32 | 1 |
| `stages` | The listener counts a ramp climbs through | one stage, at `listeners` |
| `payloadBytes` | The document size to aim for, before protobuf framing | 1024 |
| `publishEvery` | How often each publisher acts | `1s` |
| `proposals` / `revisions` | How many proposal identities each publisher cycles, and how many revisions of each | 8 / 3 |
| `cancel` | Whether a proposal is withdrawn after its last revision | false |
| `manifestEvery` | Bump the manifest every *n* publications | never |
| `readers` / `readEvery` / `pageSize` | Snapshot readers walking pages while the feed moves | 0 / `2s` / 50 |
| `warmUp` / `measure` | How long before measuring, and how long to measure | none / `10s` |
| `brokerNodes` | One, or two on Redis | 1 |
| `sharedAddress` | Whether every listener presents the same address to the gateway | false |
| `push` | Whether the relay runs against the stand-in | false |
| `publishRate` / `publishBurst` / `readRate` / `readBurst` / `maxProposals` | The gateway's own bounds, when a profile needs them wider than a shared service's defaults | the gateway's |
| `health` | `mostP99`, `leastDelivered`, `mostLost`: what a stage must hold for a ramp to climb past it | nothing checked |

A simulated phone presents its own forwarded address by default, in RFC 2544's benchmarking range,
because in a deployment the proxy sets one and the gateway's read limiter keys on it. The
`shared-address` profile turns that off, which is not a mistake being measured: it is what a
building full of phones behind one address really costs.

## The scenarios

A profile is the workload; a scenario is what is done to it and what must still be true afterwards.

| Scenario | What it does | What it asserts |
| --- | --- | --- |
| `steady` | One hot channel, nothing interrupted | Every listener holds every document the gateway holds |
| `spread` | Eight publishers, listeners holding two feeds each | The same |
| `mixed` | Revisions, withdrawals, manifest changes, snapshot walks and hints | The same, and that no hint named a device |
| `two-nodes` | The same workload over two broker nodes on one Redis | Listeners landed on both, and both fanned out every publication |
| `drain` | One node drained with `SIGTERM` mid-window, then started again | `3001 shutdown`, bounded reconnect, nothing lost |
| `kill` | The same node with `SIGKILL` | Nothing lost |
| `redis` | Redis stopped under a two-node broker, then started | Listeners fell back to the authoritative snapshot, and nothing was lost |
| `gateway` | The gateway restarted mid-window on the same database | Publications were refused rather than silently dropped, and nothing was lost |
| `slow` | A fifth of the listeners stop reading | The ones that kept reading lost nothing |
| `flood` | One publisher publishes far over its rate | It was refused with `too_many_requests`, and its neighbours were unaffected |
| `isolation` | A publisher writes another's channel, with and without its grant | Every attempt refused, and no listener saw anything it was not granted |
| `reconnect` | Every stream cut at once | The storm is bounded by the phone's own backoff |
| `shared-address` | Four hundred listeners behind one address | The read limiter refused, which is the number worth having |
| `ramp` | The hot channel, climbing toward ten thousand listeners | It reports the stage reached and what stopped it |

A failover window has no steady-state p99: a publication issued while a listener's node was away is
delivered when the listener comes back, so the number measures **recovery**, and those scenarios
bound it by the phone's own backoff ceiling instead of by a steady-state latency. What does not move
is completeness — every delivery is still expected to arrive.

## The ramp

`ramp` climbs through its stages and stops at the first one that cannot hold its health rule. The
report says the stage it reached and the rule that stopped it; a stage that was never reached is a
line saying so, never a pass. Keeping the listeners from the previous stage is deliberate: a phone
that is already connected stays connected, which is what growth looks like.

The first thing to run out is usually not the transport. Watch, in this order: the descriptor limit
(`ulimit -n`; a listener is one socket and the harness raises nothing), the read limiter's own key
bound (`MostKeys`, 16384 callers), and the memory of the process holding ten thousand HTTP/2
connections — which on these runs was the broker rather than the gateway.

**Run the ramp on its own.** Ten thousand listeners is twenty thousand sockets, and a loopback
ephemeral range is about sixteen thousand ports held for thirty seconds after they close
(`sysctl net.inet.ip.portrange net.inet.tcp.msl`). A suite started too soon after a ramp fails to
*connect* to anything, with `can't assign requested address` — which says nothing about the
deployment. The harness waits for the range to drain and tries again, and it pauses `--settle`
(five seconds by default) between scenarios, but the honest arrangement on one machine is
`--scenario ramp` as its own run.

## Against a deployment of your own

The harness is a client. Point it at a running deployment and it will drive that instead — including
the proxy hop, and TLS if the origin has it:

```sh
loadtest --scenario steady --broker-config /path/to/centrifugo.yaml
```

Reproducing a report needs the revision, the profile file and the binaries: the report names the
commit it was run at, echoes the resolved profile back, and asks each service what version it is
rather than printing what was pinned.

## Writing a new case

The harness is `tools/loadtest/`:

| File | What it holds |
| --- | --- |
| `internal/deploy/deploy.go` | Redis, the broker nodes and the gateway as real processes, with restarts, drains and kills |
| `internal/deploy/push.go` | The controlled push stand-in: the token exchange, the send, and what was sent |
| `internal/deploy/observe.go` | What a node says about itself, and what the operating system says about a process |
| `internal/listen/listen.go` | One simulated phone on the broker's unidirectional gRPC stream |
| `internal/listen/policy.go` | The app's own decisions, ported: when to come back, and whether continuity was proven |
| `internal/drive/profile.go` | The profiles, and the refusals that keep a report off a default nobody saw |
| `internal/drive/synthetic.go` | The documents, deterministic down to the padding |
| `internal/drive/run.go` | One run: publishers, listeners, readers, the window, and the convergence check |
| `internal/drive/scenario.go` | The experiments and their assertions |
| `internal/report/report.go` | The JSON a report is written from, and the summary a person reads |

Four rules worth knowing before adding to it:

- **A term key is an operation name.** `loadtest.publish.0` is not one — a segment cannot start with
  a digit (`rules.IsOperation`) — and the run that found that out had every single document refused
  with `bad_value`. `TestSyntheticFitsTheRules` checks the padding against the rule without needing a
  gateway.
- **Anything a scenario does mid-window has to be an `Interrupt`.** The first version of the slow
  scenario stalled its listeners on their first connection, which happened during the warm-up, so the
  measured window contained no stall at all and the counter that proved it had been reset.
- **The broker's counters are cumulative for the life of the process.** A stage's own numbers are a
  difference, and a node that was restarted mid-window has counters that went backwards — which the
  harness treats as the restart's own numbers rather than subtracting into a negative.
- **A gap in the offsets is not a lost publication.** A gap the broker proved it replayed is recovery
  working, and a gap followed by a snapshot read is the documented fallback. What the run asserts is
  the applied set against the gateway's own state, not the offset sequence.
