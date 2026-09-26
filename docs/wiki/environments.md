# Sandbox and production (SEE-97)

A publisher's feed is somebody else's server proposing that you spend your own money. Before that is worth doing at all, it has to be possible to watch the whole thing happen without spending any — and the demonstration has to be the real thing, or it demonstrates nothing.

That is what an **environment** is here: which promise is being kept when the owner approves.

| | **Production** | **Sandbox** |
| --- | --- | --- |
| The market data | Real, live, from the provider | **The same.** Real, live, from the provider |
| The transaction | Built by the execution provider, from this phone | **The same bytes**, built the same way |
| The review | Every finding, every fact, read out of the bytes | **The same review** |
| The owner's rules | Applied | **Applied** |
| The wallet | Opened once, with exactly those bytes | **Never opened** |
| What is sent | The signed transaction, to the network | **Nothing** |
| Afterwards | A signature, and an explorer link for its cluster | **No signature, and so no link** |
| The record says | `Sent`, with the signature | `Simulated`, with no signature |

The last four rows are the whole difference. Sandbox is not a smaller version of the operation and not a mock of it: it is the operation, carried out as far as an environment that performs nothing can carry it, and then stopped and labelled.

## It is not a network, and it does not choose one

**An environment is not a Solana cluster.** The cluster is the network the owner's wallet is selected for, it is checked separately and always, and no environment changes it: a provider that refuses a wallet selected for devnet refuses it in sandbox too, because the bytes it builds are mainnet bytes either way (`docs/wiki/jupiter-swap.md`, `docs/wiki/jupiter-prediction.md`).

**A shared interface must not imply that a provider has a testnet**, so SEE-145 made the two declarations separate and kept them that way:

- **`ActionCapability.networks`** is the set of Solana clusters a provider serves an action on. Jupiter declares mainnet and only mainnet, in both environments.
- **`ProviderCapabilities.environments`** is which of sandbox and production it serves the action in at all. A provider that serves both does the same work in each; the field exists for one that genuinely cannot serve one.

Neither is derived from the other, and they are **two separate refusals**: `NetworkUnsupported` when the wallet is selected for a cluster the provider does not serve, `EnvironmentUnsupported` when the connection keeps a promise it cannot keep. Both happen before anything is prepared, and telling an owner the wrong one of them would send them to change the wrong setting — a provider with no devnet is not a provider with no sandbox ([`docs/wiki/execution-providers.md`](execution-providers.md#an-environment-is-not-a-network)).

There is no Jupiter devnet or testnet to point sandbox at. Neither the swap aggregator nor the prediction markets exist on another cluster, and this repository does not pretend otherwise — a sandbox that claimed to trade on a test network would be inventing a service the provider does not run. The app's existing devnet checks, for transfers and message signing, are about a different thing entirely and are unaffected (`README.md`, `docs/guides/transfers.md`).

**Nothing about a simulation is fabricated.** No transaction signature, no explorer confirmation, no fill, no profit, no position. A rehearsal has nothing to look up afterwards, and the app offers nothing: an explorer link needs a signature, and there is none.

## Where it is decided

**A manifest says which environments a server *serves*. A connection records which one it *keeps*.** Those are different facts, held in different places, and that is what makes a promotion to production something a person did.

- A publisher's deployment serves exactly one (`packages/publisher-support/environment`). It is configured with `PUBLISHER_ENVIRONMENT`, published in its manifest, stamped into its database, and in every answer its own API gives. Two environments are two deployments, with their own server IDs, credentials and databases.
- The shared gateway **refuses a manifest that changes the environments a server ID published before** (`GATEWAY_PROBLEM_OTHER_ENVIRONMENT`). A promise a higher revision can raise is not a promise: every subscribed phone caches a manifest by revision, so one document would move them all from a demonstration to real money without anybody looking at it.
- The phone stores the environment **on the connection** (`Connection.environment`). A feed starts in sandbox whenever its publisher serves one, and only the owner moves it. A manifest that stops naming the environment a connection keeps makes that server *unsupported* — readable, and executing nothing — rather than moving the connection to the other one.
- A **direct** connection is always production. A sandbox rehearsal is possible only where nobody is waiting for the answer: an agent that asked a paired sidecar for a signature can be told no, but it cannot be handed a simulation, and this app will not invent one for it.

The owner's own sidecar is therefore unaffected by any of this, in both directions: it declares production, it keeps production, and no feed's setting reaches it.

## What the owner sees

| Where | What it says |
| --- | --- |
| A feed's details | The promise it keeps, in a word, with a sentence saying what that means — and, where its publisher serves both, the switch between them |
| A proposal's review | A banner before anything else about the proposal, and an action that says `Simulate` rather than `Approve and swap` |
| Afterwards | `Simulated. This feed is a sandbox, so nothing was signed and nothing was sent.` |
| Activity | `Simulated`, beside the network it was bound to, with no signature and no explorer link |

## Switching, and what it invalidates

A feed can be moved between the environments its publisher serves, and only by the owner. Switching invalidates what was in hand:

- **The preparation is dropped.** What is on screen was prepared for the other promise, and bytes prepared as a rehearsal are not bytes anybody reviewed as a purchase. The owner prepares again, which fetches a fresh quote, and acknowledges again.
- **A binding made under the other promise is refused**, on the far side of the wait for the wallet, where the preparation's expiry and the wallet selection are also checked (`BindingProblem.OtherEnvironment`). A switch that lands while an approval is in flight refuses rather than slips through.
- **Nothing is deleted, and nothing has to be remembered.** The review, the preparation and the binding each say which environment they were made in, so they stop counting by themselves. An invalidation that had to be remembered in the switch is one that could be forgotten there.

A rehearsal **spends the proposal**, exactly as declining in the wallet does: one execution per proposal per device, whatever came of it. The owner asked for what this connection promises, and they got it; a phone that then let the same proposal be executed for real would be treating what they asked for as not having counted.

## Where the decision lives in the code

| Layer | File | What it holds |
| --- | --- | --- |
| Protocol | `packages/protocol/proto/seekervault/server/v1/manifest.proto` | `ServerEnvironment`, and the `environments` a manifest names |
| Gateway | `services/gateway/internal/rules/manifest.go` | The environments may not change once published |
| Publisher | `packages/publisher-support/environment/environment.go` | One type, one pair of words, shared by both templates |
| Phone: the promise | `apps/android/.../plugins/ExecutionProvider.kt` | `PluginEnvironment`, `ProviderCapabilities.environments` (which promises a provider can keep) and `ActionCapability.networks` (which clusters it serves), declared apart |
| Phone: the refusal | `apps/android/.../plugins/ProviderRegistry.kt` | `EnvironmentUnsupported` and `NetworkUnsupported`, told apart and both answered before anything is prepared |
| Phone: the connection | `apps/android/.../connections/Connection.kt` | Which one this connection keeps, and the direct-is-production invariant |
| Phone: the gate | `apps/android/.../proposals/ProposalBinding.kt` | The promise pinned in a binding, checked inside the wallet's own lock |
| Phone: the act | `apps/android/.../operations/OperationViewModel.kt` | The one branch: a rehearsal has no wallet session in scope to sign with |
| Phone: the record | `apps/android/.../activity/ActivityRecord.kt` | What the operation was bound under, beside the cluster |

**An execution provider does not read the environment to decide anything.** It prepares the same way in both, because whether bytes are signed is core's business — core holds the wallet — exactly as which cluster they are for is core's. A provider that decided for itself would be a second place for the answer to be wrong, and the demonstration would stop being a demonstration of the real thing. What a provider *does* declare is which environments it can serve at all, and `ProviderRegistry.resolve` reports one that cannot as unsupported rather than calling it for something it said it could not do — separately from the cluster check, which it answers in its own words.

## Two other things called "environment"

Neither is this.

- **`BROADCAST_PUSH_ENVIRONMENT`** is one label inside a push topic name — `feed.<environment>.<server_id>` — chosen by the operator of a gateway deployment so that one Firebase project can serve two deployments without a sandbox publication waking a production subscriber. The gateway decides nothing about what a server promises, and holds no opinion about it (`docs/wiki/feed-gateway.md`).
- **A Solana cluster** — mainnet, devnet, testnet — is which chain a signature belongs to. See above: separate question, separate field, and it is on every record next to this one.

## Running it

Both templates' examples are sandbox deployments, so that copying one and running it demonstrates the whole path without anybody's money. Production is a deliberate edit of one line, and the code has no default at all — a deployment that says nothing does not start.

```sh
# The examples: sandbox, because a demonstration is what an example should be
grep PUBLISHER_ENVIRONMENT examples/demo-signals/.env.example examples/demo-prediction/.env.example
```

What a sandbox deployment publishes is real: a CopyTrading signal names real mints, and the Prediction template discovers real live markets from the provider's own API. Nothing about the publisher is simulated, because nothing about a publisher executes anything in the first place — what a publisher's environment changes is what every subscribed phone is told to expect when its owner approves (`docs/wiki/copytrading-template.md`, `docs/wiki/prediction-template.md`).

Going to production is the owner's own deliberate check, with real funds, and it is recorded separately: `docs/testing/stage-7-1.md` has the device run, including what to look for in a sandbox rehearsal and what a first real operation costs.
