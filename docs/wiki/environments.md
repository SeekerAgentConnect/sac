# Preview or execute (SEE-97, SEE-185)

A publisher's feed is somebody else's server proposing that you spend your own money. Before that
is worth doing at all, it has to be possible to watch the whole thing happen without spending any,
and the demonstration has to be the real thing or it demonstrates nothing.

Since SEE-185 that is a **choice the owner makes on the phone, for one operation at a time**. A
feed signal's review offers **Preview without sending** beside its approval. Nothing about it is a
property of the publisher, its deployment, its manifest or the connection: there is no sandbox
deployment, no `environments` in a manifest, and no environment switch on a feed. One deployment
serves both, because the publisher's part is the same either way: it publishes a signal, and it
never executes anything.

| | **Execute** | **Preview without sending** |
| --- | --- | --- |
| The market data | Real, live, from the provider | **The same** |
| The transaction | Built by the execution provider, on this phone, for the chosen wallet | **The same bytes**, built the same way |
| The review | Every finding and fact, read out of the bytes | **The same review** |
| The owner's rules | Applied | **Applied** |
| The binding gate | Every check between the review and the binding | **The same gate** (`problemOf`) |
| The wallet | Opened once, with exactly those bytes | **Never opened**: no wallet session exists |
| What is sent | The signed transaction, to the signal's network | **Nothing** |
| Afterwards | A signature, and an explorer link for its cluster | **No signature, and so no link** |
| The signal | Consumed: one execution per proposal per device | **Still open**, and the preparation is dropped |

A preview is not a smaller version of the operation and not a mock of it. It is the operation,
carried out as far as possible without a wallet, then stopped and labelled.

## When it is offered

The action appears only when the provider that would execute the signal serves a preview
(`PluginEnvironment.Sandbox in ProviderCapabilities.environments`). Jupiter serves one for swaps and
prediction orders. A provider that does not is executed or declined, never previewed.

A preview needs everything an execution needs up to the wallet:

- **A wallet chosen for the signal's network.** The transaction is prepared for that address, so a
  preview without one would be a preview of nothing. The review's **Choose wallet** /
  **Connect a wallet on &lt;network&gt;** applies to both
  ([wallet-setup.md](../guides/wallet-setup.md#feed-operations)).
- **A usable client configuration** from the trusted gateway, because the preparation reads the
  chain and may carry the SAC fee ([client-config.md](client-config.md)).
- **A signal that declares a network** its feed supports. A signal without one is readable and is
  neither previewed nor executed.

## What a preview does, and doesn't

- **It is the same preparation.** The plugin reads the provider and builds the bytes, the phone
  inspects them, the owner's rules are assessed, warnings must be acknowledged, and the binding is
  bound with the wallet, the network, the fee terms and `environment = Sandbox`.
- **It passes the same gate.** `ProposalRepository.recordPreview` applies every rule
  `beginExecution` applies, under the same repository lock.
- **It opens no wallet.** The branch in `OperationViewModel.go` stops before `wallet.withWallet`,
  so there is no session in scope to sign with. That is why nothing can be signed, rather than a
  flag checked beside a session.
- **It is recorded beside the signal, not as its execution.** `ProposalRecord.preview` holds the
  latest `ProposalPreview` (binding and time); the next preview replaces it. `ProposalRecord.execution`
  is untouched.
- **It doesn't consume the signal.** Its standing stays `Open`. The preparation is dropped, so
  executing it afterwards starts from a fresh preparation (a fresh quote) and a fresh review.
- **Nothing is fabricated.** No signature, no explorer confirmation, no fill, no position, and
  nothing for the confirmation tracker to follow.

Preview and execute share the review's lock (`busy`), so the two are never in flight for the same
review at once, nor beside a new preparation. The binding pins which of the two the owner asked
for, so a preview can never be carried out as the real thing or the other way round. Duplicate
execution protection is unchanged: one execution per proposal per device.

## What the owner sees

| Where | What it says |
| --- | --- |
| The review | **Preview without sending** beside the approval, with a note that the wallet is not opened, nothing is signed or sent, and the signal stays open |
| After a preview | "Previewed. Nothing was signed and nothing was sent, and this signal is still open. To execute it, prepare it again." |
| Prediction review | "Preview without sending"; afterwards a notice to refresh the quote and approve to place the order |
| History row | "Previewed on this phone · nothing sent", while the signal is still open |
| History detail | Status "Previewed", and a **You previewed** timeline entry; an execution that follows gets its own entries |
| Activity | `Simulated`, beside the network it was bound to, with no signature, until an execution replaces it |
| Inbox | Every waiting item reads Production: whether it is previewed is decided when the owner acts |

A record written before SEE-185, when a sandbox feed spent its proposal on a rehearsal, holds
`ProposalOutcome.Simulated` as its execution. It still reads as what it was: nothing signed, nothing
sent, and the proposal finished on that device. Nothing writes that outcome any more.

## Direct connections

A **direct** connection always executes. An agent that asked a paired server for a signature can be
told no, but it cannot be handed a simulation, and this app will not invent one for it. A direct
manifest declares no environment ([server-manifests.md](server-manifests.md)).

## It is not a network

**A preview is not a Solana cluster.** The network is the one the signal declares
(`action.network`), and it is checked separately and always. A preview of a mainnet swap is a
preview of mainnet bytes. There is no Jupiter devnet or testnet, and nothing here pretends
otherwise.

The two provider declarations stay separate, as SEE-145 made them:

- **`ActionCapability.networks`**: the Solana clusters a provider serves an action on. Jupiter
  declares mainnet only.
- **`ProviderCapabilities.environments`**: whether the provider can execute it, preview it, or
  both. Jupiter declares both.

Neither is derived from the other ([execution-providers.md](execution-providers.md#an-environment-is-not-a-network)).

## What changed in SEE-185

| Before | Now |
| --- | --- |
| `ServerManifest.environments`, `ServerEnvironment`, `RecommendedFeed.environments` | Removed (field numbers reserved) |
| `GATEWAY_PROBLEM_BAD_ENVIRONMENT`, `GATEWAY_PROBLEM_OTHER_ENVIRONMENT` | Removed (reserved); the gateway drops `environments` from stored manifests |
| `PUBLISHER_ENVIRONMENT` and the publisher database's environment stamp | Retired: a deployment that still sets it starts and logs one warning |
| Separate sandbox and production deployments | One deployment; an operator who ran both keeps one |
| `Connection.environment`, the feed's environment switch, `BindingProblem.OtherEnvironment` | Removed |
| A sandbox rehearsal spent the proposal | A preview leaves it open |

## Where it lives in the code

| Layer | File | What it holds |
| --- | --- | --- |
| Phone: the act | `apps/android/.../operations/OperationViewModel.kt` | `preview` / `act` / `go`: the one branch, before the wallet, and the shared lock |
| Phone: the record | `apps/android/.../connections/ProposalRepository.kt` | `recordPreview`: the same gate as `beginExecution`, written as the preview |
| Phone: the state | `apps/android/.../proposals/ProposalState.kt` | `ProposalPreview`, `ProposalRecord.preview`, legacy `ProposalOutcome.Simulated` |
| Phone: the binding | `apps/android/.../proposals/ProposalBinding.kt` | `ExecutionBinding.environment`: execute or preview, pinned |
| Phone: the provider | `apps/android/.../plugins/ExecutionProvider.kt` | `PluginEnvironment`, `ProviderCapabilities.environments` |
| Phone: history | `apps/android/.../history/HistoryDetailMapping.kt`, `inbox/InboxScreen.kt` | "Previewed", "You previewed", the history row |

**An execution provider does not read the mode to decide anything.** It prepares the same way for
both, because whether bytes are signed is core's business: core holds the wallet.

## The other "environment"

**`BROADCAST_PUSH_ENVIRONMENT`** is only a label inside a gateway's push topic names,
`feed.<environment>.<server_id>`, so one Firebase project can serve two gateway deployments. It says
nothing about whether an operation is previewed or executed ([feed-gateway.md](feed-gateway.md)).
