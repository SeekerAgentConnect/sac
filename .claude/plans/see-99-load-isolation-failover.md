# SEE-99 — measure the broadcast transport: load, isolation and failover

Evidence instead of adjectives. One command that drives the shipped gateway, the pinned broker and
real Redis with synthetic publishers and simulated phones, measures what arrives and when, breaks
things on purpose, and writes a report that says where it stopped and why.

## The decisions this rests on

1. **The harness is a fourth consumer of the protocol, in its own Go module.** It has to hold three
   things no shipped component may hold together: the publisher API, the client API, and the
   broker's own client schema. `loadtest/` is therefore a module of its own — the gateway's
   dependency list stays at three (`broadcast/go.mod`), and `buf.gen.loadtest.yaml` is where both
   sides of a feed are compiled into one binary.
2. **The client is the phone's client, rule for rule.** `listen/policy.go` is a port of
   `feeds/FeedRecovery.kt`: the same disconnect-code ranges, the same "was continuity proven"
   decision, the same 1 s → 30 s jittered backoff. A harness with its own reconnect policy measures
   a client nobody ships, and its recovery numbers would be about the harness.
3. **The transport is the deployed one, unproxied, and the report says so.** Listeners consume
   Centrifugo's unidirectional gRPC on the shipped `broadcast/centrifugo.yaml`; the gateway is the
   shipped binary on its own SQLite file. What is *not* in the path is Caddy — there is no daemon on
   this machine (`docs/testing/stage-7.md`), so the proxy hop is named as excluded rather than
   folded into a number.
4. **Latency is measured on one clock.** The publisher and every listener are goroutines in one
   process, so publish-to-receive is a single monotonic subtraction: the send time is recorded
   against the document's identity (channel, kind, proposal, revision — the outbox's own
   idempotency key) and the listener looks it up when the bytes arrive. Nothing is compared across
   machines, and no NTP assumption is needed. What the number therefore includes is stated: the
   commit, the outbox drainer's pass, the broker's fan-out and the client's own decode.
5. **Ten thousand clients is an experiment, not a claim.** The ramp profile climbs and stops at the
   first stage that fails its own health rule, and the report names the stage reached and the first
   bottleneck observed. A number the machine could not sustain is a NOT REACHED line, never a pass.
6. **A simulated phone is a distinct caller, because in a deployment it is one.** The gateway's read
   limiter keys on the address, honouring `X-Forwarded-For` from a loopback peer — which is what the
   proxy in front of it sets. Each listener therefore sends its own forwarded address. The run
   without it is kept as its own measurement, because a whole office behind one NAT address really
   does share one bucket, and that is worth a number rather than a shrug.
7. **Nothing real is touched.** Synthetic proposals published straight to the publisher API; no
   provider, so no Jupiter traffic; a controlled push stand-in on loopback for the relay, which the
   gateway's own rules already allow for development; both deployments sandbox; no wallet, no
   signature, no funds. `drive/boundary_test.go` fails if any of that changes.

## The module

- [x] `loadtest/go.mod`: connect and protobuf, nothing else. `buf.gen.loadtest.yaml` generates the
      publisher client, the feed client, the stream envelope and the vendored broker schema.
- [x] `deploy/`: Redis, one or two broker nodes, the gateway, and the push stand-in as real
      processes, with restarts, kills and drains. Publishers are registered with `broadcastctl`,
      because that is the only way one exists.
- [x] `listen/`: one listener per simulated phone — ticket, connect request, pushes, per-channel
      cursor, gaps, duplicates, reconnects, and the reason a snapshot read was needed.
- [x] `measure/`: exact quantiles from kept samples, counters, and a sampler for each process's CPU,
      resident memory and connection count.
- [x] `drive/`: the profiles, one run, and the named scenarios.
- [x] `report/`: the JSON a report is written from, and the summary a person reads.

## The workload profiles (`loadtest/profiles.json`)

- [x] `hot`: one channel, every listener on it, steady publications — the shared-fan-out case.
- [x] `spread`: many publishers, one channel each, listeners divided between them.
- [x] `mixed`: proposals, manifest revisions, cancellations and expiries in one run, plus snapshot
      readers walking pages while the feed moves.
- [x] `ramp`: the same as `hot`, climbing toward 10,000 listeners, stopping at the first stage that
      cannot hold its own health rule.
- [x] Every profile states payload size, publish rate, client count, warm-up and measured duration,
      and the report echoes them back.

## The scenarios

- [x] `drain`: SIGTERM one broker node with listeners attached. Bounded reconnect, recovery where
      the epoch held, snapshot where it did not, and no publication missing from any client's
      applied set.
- [x] `kill`: SIGKILL the same node. The same invariant, harsher, and the recovery cost measured
      rather than assumed.
- [x] `redis`: stop Redis under a two-node broker, publish through it, restart it. What is lost is
      recovery cache, never a committed document — the snapshot read is the proof.
- [x] `gateway`: restart the gateway mid-run on the same database. Committed documents survive,
      pending notices drain, and no client executes anything twice.
- [x] `slow`: a share of listeners stop reading. They are closed (`reason: slow`) and the healthy
      ones on the same node keep every publication.
- [x] `flood`: one publisher publishes far over its rate. It is refused, and a second publisher's
      latency and delivery are unchanged.
- [x] `isolation`: a publisher writes to another's channel, with another's credential, and after a
      flood. Every attempt refused, no permission moved, and no listener on the other channel sees
      anything.
- [x] `reconnect`: every listener's stream cut at once. The retry storm is bounded by the phone's
      own backoff, and the snapshot reads it causes are counted.

## Tests

- [x] Unit: the quantile digest, the gap and duplicate tracker, profile validation, the ported
      policy against the Kotlin's own cases, the push stand-in's token exchange, the report.
- [x] End to end: one tiny run of the whole thing — gateway, broker, listeners, publisher — skipped
      with a reason when the machine has no broker binary.
- [x] `drive/boundary_test.go`: no real push endpoint, no provider, no wallet, no mainnet, and no
      generated handler mounted anywhere in the harness.
- [x] Deliberate breaks: each new check fails for the reason it exists.

## The commands

- [x] `pnpm check:loadtest`: formatting, `go vet`, unit tests. Needs Go and nothing else.
- [x] `pnpm test:load`: builds the gateway, `broadcastctl` and the harness, then runs the profiles
      and scenarios this machine can, reporting each as PASS, FAIL or NOT RUN.

## Documentation

- [x] `docs/development/load.md`: the command, the profiles, the scenarios, what each number
      includes, and how to run it against a deployment of your own.
- [x] `docs/testing/see-99.md`: the report — hardware, topology, versions, every profile and
      scenario with its numbers, the bottleneck found, and the limits.
- [x] `docs/wiki/feed-gateway.md`: the measured numbers, where it says what the transport can
      do.
- [x] `README.md` commands and status; `AGENTS.md` checks; `CODEBASE.md` files and commands;
      `docs/development/toolchain.md` for the broker and Redis versions used.
- [x] `docs/changelog/2026-09-18.md`.
- [x] `.claude/tasks/lessons.md`, if anything here was learned the hard way.

## Verification

- [x] `pnpm check:loadtest`, `pnpm test:load` with and without the broker binaries.
- [x] `pnpm check`, `pnpm check:generated`, `pnpm check:format`, `pnpm check:lint`.
- [x] `pnpm check:broadcast`, `pnpm check:publisher`.
- [x] `pnpm test:integration --no-android`, to show the harness added nothing that moved Stage 7.1.

## Review

Done, and the numbers are in [`docs/testing/see-99.md`](../../docs/testing/see-99.md): fourteen
scenarios, all PASS, **5 000 simultaneous listeners reached** on one broker node with every
publication reaching every one of them at a p99 of 156 ms, and every scenario — including the
10 000-listener stage that broke — converging with not one document lost.

Four things are worth recording because they were not obvious when the plan was written.

**The first bottleneck was not the one the plan expected.** The plan watched for descriptors, the
limiter's key bound and the broker's memory. The broker's memory was indeed the second wall (1.4 GiB
for 7 228 connections), but the *first* was the gateway's own unary API: ten thousand listeners ask
for a stream ticket at once, `GetStreamTicket` reads the store, and the read pool is four SQLite
connections — so every ticket in that window took at least five seconds while the gateway used two
seconds of processor time and stayed in double-figure descriptors. It was queueing, not working.
That is an actionable finding rather than a limit, and it is the one line of this report a
deployment should read first.

**The harness's own bugs all looked like product failures, which is the argument for the
convergence check.** Padding whose term keys the gateway refused (`bad_value` on every document); a
stall scheduled during the warm-up, so the measured window contained no stall; background readers
started per stage and cancelled once, which hung a ramp for twenty minutes; a publisher whose tick
count restarted with its goroutine, so a whole stage republished revision 1 and was refused; and the
instructive one — an offset compared across a *replaced* history, which reported two hundred
listeners short of a withdrawal that had in fact been delivered. Each of them is in
`.claude/tasks/lessons.md`, and each was found because the run compares what a listener holds with
what the gateway holds rather than trusting a counter.

**A health rule belongs to the scenario, not to the workload.** A window with a node failure in it
has no steady-state p99 — the publications a listener missed arrive when it comes back — so judging
`drain` by `hot`'s two seconds reported the design as a fault. The failover scenarios bound recovery
by the client's own backoff ceiling instead, `flood` and `isolation` assert refusals rather than
latency, and completeness is the one rule that never moves.

**Two limits deserve to be in the wiki rather than only in a report.** The read rate limit is per
address, and a proxy decides what that address is: four hundred listeners behind one address
produced 1 260 refused ticket requests and 187 streams in fifteen seconds, where the same gateway
served five thousand on distinct ones. And losing Redis moves deliveries from the stream to the
authoritative snapshot — a third of a window's publications arrived as a read — which is exactly
what "a recovery cache and not the source of truth" means, measured.

**Stated limits.** No Docker, so the Caddy hop and TLS are unmeasured. Push latency is Firebase's
and a device's and is not in any number here; the relay's own behaviour is (28 hints, one token
exchange, nothing addressed to a device). One machine, one architecture, everything on loopback —
a floor for the software, not a figure for a deployment. The two-node comparison is of two
endpoints rather than of a load balancer: a listener keeps the node it was given, which makes
`drain` and `kill` the worst case. Nothing ran longer than a few minutes. And the six device steps
in the report are NOT RUN.
