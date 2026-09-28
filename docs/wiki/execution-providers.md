# Execution providers, and the actions they carry out (SEE-145)

SEE-86 gave this app a place to put the part of an operation it does not know how to do, and
SEE-93 and SEE-94 filled it with `jupiter.swap` and `jupiter.prediction`. Those two names each did
two jobs at once: they said **what the owner wants to do** and **who would prepare it**, in one
string, with a dot in the middle.

That was fine while there was one of them per operation and stopped being defensible the moment a
second Solana venue became plausible. This is the boundary as it stands now: an **action** is
provider-neutral and versioned; an **execution provider** is named separately and explicitly; and
core knows about the first and nothing about the second.

## The four things a request involves, kept apart

| | What it is | Where it lives |
| --- | --- | --- |
| Source | The connected server that published the request or signal | `Proposal.key.serverId`, the connection |
| Action | What is being done, at the protocol's own level, with a schema version | `ActionId` — `swap`, `prediction.buy` |
| Execution provider | Who prepares the operation | `ExecutionProviderId` — `jupiter` |
| Market provider | The venue a prediction market belongs to | `PredictionPayload.marketProvider` — Kalshi, Polymarket |
| Instrument | Exactly which market or asset pair | `Instrument(marketProvider, id)` |

The last two are not the same thing and the distinction is load-bearing. Jupiter is who builds an
order; Kalshi is whose market it is about. **A prediction market from one venue is not
interchangeable with a similarly named market from another** — the sources they settle on, the
times they close, what they pay out — so an instrument carries the venue that defines it beside the
identifier it defines, and the pair is pinned to the review. There is no routing between providers
anywhere in this app, no automatic fallback, and nothing that treats two markets as the same market
because they have the same words in their titles.

## The interface

[`plugins/ExecutionProvider.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/ExecutionProvider.kt).

| | What it does |
| --- | --- |
| `capabilities` | Who it is, which actions it serves, at which schema versions, on which Solana clusters, in which environments, in which assets and within which limits |
| `inputs` | What the owner has to choose. Reaches nothing and cannot fail, so a review opens with its fields already on it |
| `resolve` | What the provider says about the action *now* — a market's state, tightened constraints. A read, and nothing else. It suspends, so it may be superseded: see [below](#a-read-answers-the-review-that-asked) |
| `prepare` | Builds the operation for the owner's explicit choice, and returns the exact bytes that would be signed |
| `inspect` | Reads those bytes back and says what they establish, as typed facts |
| `destinations` | Where the owner may carry on outside the app, if anywhere truthful exists |
| `status` | Optional, and answered honestly. See [below](#the-status-query-nothing-polls) |

What a provider is **not** given is the point of the interface. It receives an `ActionOperation`:
the action, its own identity, the environment, the cluster the owner's wallet is selected for, the
typed payload, and the wallet's public address. No credential, no wallet authorization token, no
way to reach a sidecar, and no means of approving or sending anything. It hands back bytes and a
reading of them, and the owner's hand on the wallet is still the only thing that executes either.

Nothing here downloads code. A provider is compiled into the APK and chosen at build time; a server
can name one it requires, and one this build does not carry is reported as missing rather than
fetched.

## The action, and the provider

A publisher's terms used to be handed to a plugin as a bag of strings for it to parse. They are now
read **once, by core**, against the action's own schema, before any provider is consulted
([`plugins/actions/`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/actions)).

That is not tidiness. A publisher is a stranger, every provider of an action has to refuse the same
malformed document in the same way, and two providers parsing the same terms their own way is two
chances to disagree about what the owner is looking at. Doing it once is the only way that stays
true when there is more than one of them — and it is what lets an owner be told that a signal is
unreadable without a provider having been reached at all.

What stayed with the provider is everything that is genuinely the venue's:

- **Which stake tokens it settles in**, and **the smallest order it accepts.** Jupiter's two dollar
  tokens and its five-dollar minimum used to live inside the prediction payload reader, which
  quietly made them part of what the *action* meant. They are `ActionCapability.depositAssets` and
  `ActionCapability.leastDeposit` now, and a publisher naming a token this venue will not take is
  refused with `AssetUnsupported` when the signal is read rather than when the order comes back.
- Its API's shapes, its program layouts, its errors, and its platform link.

The shared Solana machinery — transaction decoding, address lookup tables, base58 — stays
provider-neutral in `solana/` and `transactions/`, where it already was.

## Resolving, and the seven ways it can fail

`ProviderRegistry.resolve(provider, action, schemaVersion, network, environment, payload)`. The
provider is **named**; `null` means the document named none this build could identify, and that is
a refusal rather than a licence to pick one.

| Reason | What to tell the owner |
| --- | --- |
| `NoProvider` | This build carries no provider with that name |
| `ContractUnsupported` | It carries it, at a version of this boundary it cannot call |
| `ActionUnsupported` | That provider does not do this at all |
| `SchemaUnsupported` | Not at this version of the action's payload |
| `NetworkUnsupported` | Not on the cluster the owner's wallet is selected for |
| `EnvironmentUnsupported` | Not in the environment this connection keeps |
| `AssetUnsupported` | Not in the asset the document names |
| `NameMismatch` | It names a legacy capability that was published for something else |

Each is a different thing to be told, because the owner would do a different thing about each:
update the app, connect a wallet on another cluster, switch the connection's environment, go back
to the publisher about a signal that disagrees with itself, or nothing at all. Every one of them happens **before anything is prepared**, and long before anything
is signed.

An owner with no wallet connected has no cluster yet, so `NETWORK_UNSPECIFIED` skips the cluster
check: "you have not connected a wallet" is not "this venue does not serve your network". Preparing
then fails with `no_wallet`, which is the plain way to say it, and the binding re-checks the cluster
against the selected wallet on the far side of the wait.

## An environment is not a network

A shared interface must not imply that a provider has a testnet, so the two are separate
declarations and separate refusals:

- `ProviderCapabilities.environments` is Sandbox and Production — **which promise is kept when the
  owner approves** (SEE-97, [environments.md](environments.md)). A provider does the same work in
  both; what stops in sandbox is core, at the wallet.
- `ActionCapability.networks` is the set of Solana clusters. Jupiter declares mainnet and only
  mainnet, in both environments, because there is no devnet Jupiter and inventing one would be
  worse than saying so.

A provider with no devnet is not a provider with no sandbox, and telling an owner the wrong one of
those would send them to change the wrong setting.

## What a review is bound to

Before the wallet is opened, an `ExecutionBinding` pins the exact prepared bytes' hash, the
proposal revision, the owner's choice, the wallet, the cluster, the environment, the boundary
contract, the preparation's own version — and, since SEE-145, **the execution provider, the action,
the action's schema version and the instrument**. Any of them changing means the owner would be
signing something other than what they reviewed, so each is refused by name:
`OtherProvider`, `OtherAction`, `OtherSchema`, `OtherInstrument`, `UnreadableTerms`.

The provider is looked up again at the moment of acting rather than taken from the binding, so an
app updated between the review and the wallet does not sign for a venue it no longer carries.

## Compatibility

Every document, manifest and stored row written before SEE-145 says the old thing, and all of them
keep working — through **one explicit table** and nothing else
([`plugins/Compatibility.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/Compatibility.kt)).

`jupiter.swap` is **looked up, never parsed**. It is not read as "the provider `jupiter` doing the
action `swap`", because a name that happens to contain a dot is not a structure; it means what
`LEGACY_CAPABILITIES` says it means:

| Legacy name | Legacy operation | Provider | Action | Schema | Published contract |
| --- | --- | --- | --- | --- | --- |
| `jupiter.swap` | `swap` | `jupiter` | `swap` | 1 | 1 |
| `jupiter.prediction` | `prediction` | `jupiter` | `prediction.buy` | 1 | 1 |

Four things follow from it.

- **Both spellings of an action mean the same action.** A publisher that says `prediction` and one
  that says `prediction.buy` are asking for the same order, and the phone prepares the same one
  from either. What the phone *re-emits* is the legacy spelling, so a client written before SEE-145
  reads what it always read.
- **The wire did not change.** No field was added to any proto. A provider reaches the phone
  through the existing `ActionCapability.plugin_id` compatibility claim, and the provider-neutral
  versioned action through the existing `capability_id` and `capability_version` that SEE-108
  already defined. Adding an explicit `execution_provider` field is the natural next step and is
  deliberately not part of this change.
- **A legacy name authorizes exactly the capability it was published for.** `jupiter.prediction`
  never meant "Jupiter, and separately whatever this document asks for" — it meant Jupiter's
  prediction order. So a document pairing it with the `swap` action, or `jupiter.swap` with a
  prediction, is not naming a provider: it is disagreeing with itself, and it resolves to nobody
  (`NameMismatch`). The build before SEE-145 refused the same document, because the operation
  resolved to a plugin that was not the one claimed. Checking the *pair* is what keeps that
  refusal: asking only "does Jupiter do swaps?" answers yes and lets the contradiction through.
  Naming the provider outright does not rescue it either — a stated provider settles *which*
  provider, never *whether*. A name with no row in the table constrains nothing, because it never
  carried an action to contradict.
- **A new provider needs no row here.** The table exists only for the two names published before
  there was a way to name a provider at all. Anything registered later declares its own
  `legacyPlugins`, and `ProviderRegistry.byLegacyPlugin` matches them.
- **Stored rows survive in both directions.** `ProposalStore` stays at version **3**: it writes the
  action and the provider *beside* the legacy operation and plugin names, never instead of them, so
  the format is purely additive and rows written before are read through the same table. The number
  stays put deliberately. A version-3 build's `decode` refuses anything outside `1..3` before it
  reads a single field, so raising it to 4 would have been the one change that broke the downgrade
  it was meant to protect: every row this build had rewritten would vanish on the way back, taking
  the owner's review and the record of the one attempt with it, and a refreshed proposal would look
  unexecuted and be actionable again. Writing keys an older reader ignores is what "additive"
  means; renumbering is not. A pending item, a completed record and the one-attempt rule all come
  back exactly as written, in both directions.

### Two numbers are called "contract"

They are different and conflating them would have invalidated every manifest already published.

- `PROVIDER_CONTRACT` is **2**. It is the version of the interface *inside the APK*, and SEE-145 is
  the first thing to raise it: the identity, the capabilities, the methods and the shape of what is
  handed over all changed, and a contract-1 plugin cannot be called through any of it.
- The number a **server manifest** names for `jupiter.swap` is still **1**. That is a statement
  about the agreement between a server and a client — what may go in a proposal, and what a client
  will do with it — and none of that changed. Restructuring the code behind a name is not the
  server's business. It is `LegacyCapability.contract`, and it is what `serverSupport` matches a
  manifest's `min_contract..max_contract` range against.

A provider with no legacy row has no published number to be about, so a manifest requiring it is
matched against the provider's own contract.

## A read answers the review that asked

`resolve` is the only call on the interface that reaches the network, so it is the only one that can
still be out when the owner moves. They may open another feed's proposal that happens to carry the
same publisher-minted ID, close this one and open it again, or have the publisher raise the revision
underneath them.

Core scopes the answer for you. Every open review carries its own number; a read that started under
one is cancelled when it is superseded, and if it had already left, its result is discarded rather
than applied. A provider does not have to be idempotent about this and does not have to carry a
request identity — but it must not treat cancellation as an error worth reporting, because being
stopped is the normal end of a read nobody is waiting for any more.

A revision that moves is a new review in every respect: the terms are parsed again, `inputs` and
`destinations` are asked again for the new record, `resolve` is asked again, and what the owner had
chosen about the terms that were replaced is not carried onto terms they never saw. Preparing and
approving both check the revision they were opened under, so the latest revision can never be
prepared against the previous one's limits or instrument.

## Status queries and positions

`ExecutionProvider.status` defaults to `ActionStatus.Unsupported`. Since SEE-172 Jupiter declares
`statusQueries = true` and answers a prediction order's fills by the order's own account — the
provider's evidence, reported as `Reported(code)`; a swap, which has no order, stays `Unsupported`.
"Submitted" still never becomes "filled" on a chain confirmation alone.

A provider may also expose `positions: PositionManagement` — reading a held position, reading an
order, and building and reading a whole-position sale (`prediction.sell`). It is not a publishable
action: nothing a signal carries can reach it, and core decides eligibility, stores what happened and
drives the wallet ([prediction positions](prediction-positions.md)).

## Adding a bundled provider

1. **Write the class.** Implement `ExecutionProvider` in its own package. Everything specific to the
   venue — its API client, its instruction readers, its errors, its links — belongs there and
   nowhere else.
2. **Declare what it promises.** A `ProviderCapabilities` with your `ExecutionProviderId`,
   `PROVIDER_CONTRACT`, one `ActionCapability` per action you serve (schema versions, Solana
   clusters, accepted assets, limits), the environments you serve, `statusQueries`, and any
   bundled-plugin names you want manifests to be able to require. Declare only what is true: an
   overstated cluster or asset becomes a refusal from an API halfway through an order instead of a
   sentence on a screen.
3. **Serve an action that already exists, or add one.** An existing action means implementing
   `inputs`/`prepare`/`inspect` against the payload core already reads
   (`plugins/actions/SwapAction.kt`, `PredictionAction.kt`). A new one means a payload type, a
   reader, its problems and their string resources in `plugins/actions/`, an `ActionId` and a schema
   version in `plugins/Identities.kt`, a branch in `actionPayloadFrom`, and a rule vocabulary in
   `policyActionFor` if the owner should be able to write rules about it.
4. **Inspect the transaction.** `inspect` reads the bytes and reports typed facts and findings.
   What your API said it built is a claim about the bytes and never evidence about them, and an
   instruction you cannot account for is a gap in the review rather than a byte that turned out to
   be safe — `ActionInspection.approvable` is false unless every instruction was read. Refuse
   unknown instructions, mismatched accounts and unresolvable lookup tables; there is no
   blind-signing fallback to fall back to.
5. **Register it.** Add it to `SeekerVaultApplication.providers`. That list is the whole of how a
   build selects providers, and `BundledProvidersTest` holds it to what is meant to ship.
6. **Test it.** `ProviderRegistryTest` for resolution and every refusal;
   `AlternateProviderTest` for a provider driven end to end through the real review, binding, wallet
   and record path; `CompatibilityTest` for any legacy name you answer to. Default tests place no
   orders and spend no funds: stand in for the API and the chain, never reach either.

A test-only provider lives in `src/test` and is therefore compiled into no APK — not the release
one and not the debug one. That is how "it cannot be selected in a production build" is kept true:
there is no flag to get wrong and nothing to strip.

## Where the rules for this live

- [`plugins/ExecutionProvider.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/ExecutionProvider.kt) — the interface and what is handed over
- [`plugins/ProviderRegistry.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/ProviderRegistry.kt) — resolution and its reasons
- [`plugins/Compatibility.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/Compatibility.kt) — the legacy table
- [`plugins/actions/`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/actions) — the action payloads and the owner's inputs
- [`jupiter/JupiterExecutionProvider.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/jupiter/JupiterExecutionProvider.kt) — the first bundled adapter
- [`proposals/ProposalBinding.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/proposals/ProposalBinding.kt) — what a review is bound to
- [client-plugins.md](client-plugins.md), [jupiter-swap.md](jupiter-swap.md),
  [jupiter-prediction.md](jupiter-prediction.md), [environments.md](environments.md),
  [common-requests.md](common-requests.md), [../integrations/jupiter.md](../integrations/jupiter.md)
