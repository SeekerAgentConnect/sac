# The integration run

`pnpm test:integration` is Stage 7.1's cross-component check (SEE-98): the real broadcast gateway,
both real publisher templates, two subscribers, the real sidecar and a real agent, in one command.

```sh
pnpm test:integration                 # every leg this machine can run
pnpm test:integration --no-android    # the Node legs only, for a machine with no SDK
```

It needs **Go** (it builds five binaries) and Node. It needs no network, no Docker daemon, no
credential and no funds: both publisher deployments run as `sandbox`, the provider is this
repository's own committed captures served back on loopback, and the wallet is a throwaway key
pair. Nothing in it is ever signed by a real wallet or sent to a cluster.

## What it runs

| Leg | What it is |
| --- | --- |
| The cross-component run | `test-agent/src/stage71.acceptance.ts` against the five built binaries: the gateway, `broadcastctl`, both templates and `publishctl`, plus the sidecar as its own process and an MCP agent |
| The direct-mode acceptance suites | `test-agent/src/stage2.acceptance.ts` and `stage4.acceptance.ts`, unchanged, because "the private workflow still works" is a claim about the suites that already prove it |
| The stream | The same cross-component run, with a real Centrifugo in front of the gateway. Opt-in: see below |
| The phone's cross-component cases | A filtered `:app:testDebugUnitTest` — the feed transport, shared proposals, manifests, plugins, the wallet's binding, and the direct-mode suites that must still pass with all of it in the tree |

Every leg is reported at the end as **PASS**, **FAIL** or **NOT RUN**, with the reason. A leg that
could not run on this machine is never quietly a pass, which is the whole point of printing the
summary: `docs/testing/see-98.md` is written from it.

## The opt-in legs

```sh
# The pinned broker (and, for the phone's own stream test, Redis).
SEEKERVAULT_CENTRIFUGO=/path/to/centrifugo \
SEEKERVAULT_REDIS=/path/to/redis-server \
  pnpm test:integration
```

- **`SEEKERVAULT_CENTRIFUGO`** runs the gateway with a real broker in front of it, on the shipped
  `broadcast/centrifugo.yaml` with the memory engine. The run then checks that a listener is granted
  the channels this gateway hosts and that the publications actually reached the broker's channel.
  Without it the run checks the other half of the same contract — a gateway with no broker answers
  `no_stream` and says so in its log — and reports the stream leg as NOT RUN.
- **`SEEKERVAULT_REDIS`**, together with the above, also enables the phone's own two-node stream
  test (`feeds/CentrifugoStreamIntegrationTest`), which is where recovery, epochs and failover are
  checked. Neither binary is vendored: verify them against `third_party/centrifugo/SHA256SUMS` and
  the Redis release checksums, as `docs/testing/stage-7-1.md` records.

## What is stood in for, and why

- **The prediction provider** (`test-agent/src/integration/provider.ts`) serves the seven real
  answers in `publisher/internal/jupiter/testdata/` — the ones the template's own client is already
  tested against — to the template **binary**, over `PREDICTION_PROVIDER_URL`. Two things are
  rewritten and both are about time rather than shape: every timestamp moves forward by the age of
  the capture, because a market's close time decides whether it is published at all and a process
  reads the real clock; and a closed market answers under the identity of the market that was asked
  about, because a withdrawal is only meaningful for a market somebody is following.
- **The chain and the wallet** are `sidecar/src/testing/chain.ts` and `wallet.ts`, as in
  `pnpm test:transfer`: a chain the test controls, and a key pair that produces the same Ed25519
  signature a wallet app would.
- **The phone** is not stood in for at all. Its own runtime is checked by `pnpm check:android`, and
  the cross-component subset above; what a person has to do on a real device is the checklist in
  `docs/testing/see-98.md`.

## Writing a new case

The harness is in `test-agent/src/integration/`:

| File | What it holds |
| --- | --- |
| `processes.ts` | The gateway, the templates, `broadcastctl` and `publishctl`, each as a real process, with restarts |
| `feed.ts` | A subscriber: one device reading the gateway's client API, and a record of every request it sent |
| `provider.ts` | The deterministic provider, and what it was asked for |
| `broker.ts` | The pinned Centrifugo, and a channel's position |
| `sweep.ts` | The privacy sweep: needles, haystacks, and the positive control |

Two rules worth knowing before adding to it:

- **Never call a template's CLI synchronously.** `publishctl poll` makes the template call the
  provider, and the provider is served by the test process itself; a `spawnSync` there blocks the
  event loop that has to answer it, and the template times out against a server it can see
  listening. Everything in `processes.ts` that runs a command is asynchronous for that reason.
- **A subscriber's own traffic is asserted.** `device.sent()` is what the privacy sweep reads, so a
  probe that deliberately knocks on the wrong door (a publisher procedure on the read port, say)
  belongs to a third device rather than to one of the two subscribers.
