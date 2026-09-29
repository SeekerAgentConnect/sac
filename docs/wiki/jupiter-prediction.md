# Jupiter's `prediction.buy` action (SEE-94, SEE-145, SEE-173)

`prediction.buy` is a provider-neutral, versioned action, and `jupiter` is the bundled execution provider that serves it — the second of the two actions that one provider carries out. A publisher broadcasts a market; each owner picks a side and a stake on their own phone; the order is prepared through Jupiter's own API, resolved and read by the phone itself, and signed once by the owner's wallet. What the order then came to, the position it opened and selling that position are followed from the purchase's own History item ([prediction positions](prediction-positions.md), SEE-172).

It was `jupiter.prediction`, one bundled plugin, until SEE-145 separated the action from whoever executes it. Nothing about what happens to the owner changed. A manifest that requires `jupiter.prediction` at contract `1..1` still resolves as it did, a publisher may spell the action `prediction` or `prediction.buy` and gets the same order from either, and the action is `prediction.buy` rather than `prediction` because buying a side is one thing that can be done to a market and not the only one — selling out (`prediction.sell`, SEE-172) and claiming a settled payout are others ([execution providers](execution-providers.md)).

It is written against the same boundary as the swap ([client plugins](client-plugins.md)) and reaches the same API, plus one thing the swap needs and does not use: a read-only account reader for the chain.

## What the owner is told before committing (SEE-173)

The review names the venue as **Jupiter Prediction · Powered by Jupiter** — a "Prediction market" row, and the title of the disclosures — kept apart from the feed that published the signal (the "From" row) and from the market's own source (the "Provider" row). Before the owner approves, the sheet says, in the provider's own words (`PREDICTION_ABOUT` in [`jupiter/JupiterAttribution.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/jupiter/JupiterAttribution.kt)):

- orders are placed on **Solana mainnet with real funds**; there is no test network for it;
- Jupiter charges its own trading fee, **included in the quoted cost**, and the network charges for the transaction; **SAC adds no fee** to prediction orders — the order schema has no integrator-fee parameter and none is invented; the minimum order is currently $5;
- Jupiter Prediction is **not available everywhere** — Jupiter currently restricts some regions, including the United States and South Korea;
- **dismissing a signal spends nothing; approving submits an order**, which may fill fully, partly or not at all; a filled position **cannot be cancelled for a refund** — while its market is open it can be sold at the current bid (possibly at a loss), or held until the market settles;
- neither Jupiter nor the publisher endorses the app, and the connected wallet — not the app — signs.

Links to Jupiter's own [how it works](https://docs.jup.ag/user-docs/trade/predict/how-it-works), [terms](https://developers.jup.ag/docs/legal/terms-of-use) and [privacy policy](https://developers.jup.ag/docs/legal/privacy-policy) sit beside the market link. The order's record keeps the venue and "no SAC service fee" in its execution binding, so History says the same after the fact.

## The market provider is not the execution provider

Two different things are called a provider here, and SEE-145 keeps the words apart deliberately.

- The **execution provider** is `jupiter`: who builds the order, prepares the bytes and reads them back.
- The **market provider** is Kalshi, Polymarket or Jupiter's own Forecast: whose market it is about. It is the publisher's `provider` term, it is `PredictionPayload.marketProvider`, and it never selects any code.

They are not interchangeable, and the reason is the ticket's own: **a prediction market from one venue is not interchangeable with a similarly named market from another.** Two venues can both list "will it rain in Chicago", settle on different sources, close at different times and pay out differently. So the market provider and the market identifier together are the operation's `Instrument`, the instrument is pinned to the review beside the bytes, and a document whose market moved under an open review invalidates what was prepared (`BindingProblem.OtherInstrument`) rather than quietly buying a different thing.

## The publisher names a market, and nothing else

The payload lives in [`plugins/actions/PredictionAction.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/actions/PredictionAction.kt) and is read by core, once, before any provider is consulted — it is the action's schema rather than Jupiter's, because every provider of `prediction.buy` has to mean the same thing by `market_id` and refuse the same malformed document in the same way.

| Term | Meaning | Rule |
| --- | --- | --- |
| `market_id` | The market provider's identifier for the market | A bounded identifier, never a URL |
| `event_id` | The event it belongs to, optionally | Cross-checked against the event the execution provider names for it |
| `provider` | The **market's** venue — Kalshi, Polymarket — optionally | Cross-checked the same way; never the execution provider |
| `deposit_mint` | The token a stake is deposited in | An exact base58 mint. Which mints are acceptable is the venue's own rule, not this reader's |
| `deposit_decimals` | Its base units per whole token | 0–18, display only |
| `least_deposit`, `most_deposit` | Optional bounds, in base units | Whole numbers; a floor above the ceiling is refused |
| `deposit_symbol` | An optional label | At most 16 characters, unverified |

**Everything else about the market comes from the execution provider, at the moment the owner looks**: whether it is open, what the two sides cost, what the rules say, when it settles, whether it has already resolved. A publisher's prose is prose — a signal saying "this is nearly certain" is shown as the publisher's opinion beside the market's own state, and no field can stand in for a fact the provider would have given. That division is what lets a publisher be a stranger.

### The two stake tokens and the five dollars are the venue's, not the action's

They used to be constants inside this payload reader, which quietly made "the mints Jupiter settles in" and "the smallest order Jupiter accepts" part of what the *action* meant. SEE-145 moved them to where they are true: `PREDICTION_BUY_CAPABILITY` in [`jupiter/JupiterExecutionProvider.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/jupiter/JupiterExecutionProvider.kt), as `depositAssets` — Jupiter's own dollar token and USDC — and `leastDeposit`, five dollars in base units.

The difference is visible to the owner. A publisher that names a stake token this venue will not take is now refused as `AssetUnsupported` **when the signal is read**, by the registry, before anything is prepared and before the provider's API is reached at all — rather than by an order coming back refused halfway through. And the five-dollar floor is folded into the amount field beside the publisher's own, so the bound the owner is shown is the one that actually applies; both are enforced again when the choice is read back. A second provider of the same action would declare its own two facts and neither would change what `prediction.buy` means.

## What is the owner's

The side and the stake ([`plugins/actions/PredictionInputs.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/actions/PredictionInputs.kt)). There is deliberately no default side: a market has two answers and no third, and suggesting one would be the app expressing an opinion about a market it has no basis for. The stake has no suggestion for the same reason a swap's amount does not.

Neither number reaches the publisher or the gateway, and there is nowhere for it to: a feed connection has no outbox, no result upload, no per-subscriber state. `PredictionOperationTest` runs the whole path and reads back what the gateway was told — a channel and a sequence.

## The market is read twice, for two different reasons

**When the review opens.** `resolve` asks Jupiter what the market currently is and returns the fields with what it found beside them: the market's status, its result if it has one, and — if it has already closed — a problem the owner is shown instead of a preparation. It is a read and nothing else: no order is placed, no wallet is touched, nothing is bound. This is the one action here with something live worth showing before anything is prepared, because a market that has already closed is not a preparation waiting to fail; it is a signal the owner should be told about while it is still only a screen. A provider that cannot be read at all raises its own failure, and the declared constraints stay as they are rather than being tightened by a guess.

**Again inside `prepare`.** The same check is made before an order is requested, and that one is the one that guards the bytes: the market's identity, its event and its market provider all have to agree with what the publisher named, and a market that has closed between the review and the tap stops the preparation with its own reason. The first read is for the owner's benefit and is never a substitute for the second.

## Why the phone reads the chain

This is the one real addition SEE-94 makes to the app, and it was the owner's call.

Jupiter's Prediction API returns **only** a versioned transaction whose accounts come from address lookup tables. There is no legacy option — six plausible parameter spellings were tried against the live API and all are ignored — so the escape the swap uses is not available here. In a real captured order, 35 of the aggregator's 55 accounts and 4 of the prediction program's 12 sat behind those tables: the phone could read the order's numbers but not see which accounts move funds.

Three ways out were possible: sign it anyway on a parameter-only review, refuse the format and ship an adapter that never prepares, or **read the tables**. The owner chose the third, with these terms:

- resolution lives in a shared Solana component that neither couples core to a provider nor is configured by a publisher;
- the tables are discovered from each transaction rather than hardcoded;
- ownership, format, indices and the rebuilt account list are all validated;
- the instructions are then checked against the reviewed action exactly as before, because resolving a table proves only which accounts the runtime will use and **nothing** about whether they are the right ones;
- and a table that cannot be fetched or validated **blocks signing with a stated reason** — never a parameter-only review, never a blind signature.

`solana/` is that component: one read method (`getMultipleAccounts`), a table parser, and the rebuild. It names no provider, so any provider may use it; the swap asks for a format that needs no resolution and so never does, which also means the resolver is already in place if `asLegacyTransaction` is ever withdrawn.

**What it costs, said plainly:** the review is then only as accurate as the configured endpoint. This is not offline verification and is not trustless — see [security.md](../security.md#resolving-a-lookup-table). The endpoint is the application's or its host's (`-Pseekervault.solanaRpc=…`), is **empty by default** so a checkout reaches no cluster, and a build without one prepares no order and says so.

## What an order is made of

Verified against a real order captured from the live API (`fixtures/jupiter/orders.json`):

| Instruction | Why it is there | What the review binds |
| --- | --- | --- |
| Compute budget ×2 | What the owner pays to be picked up | Read, and shown |
| Associated token account, idempotent | The owner's own account for the venue's token | For the owner, paid by the owner, at the address that derives from both |
| Jupiter `route` / `sharedAccountsRoute` | Funding the stake by swap | **SEE-93's reader, unchanged**: the owner authorizes it, the input leaves their own account for the mint they chose, no more than their stake, and it lands in the very account the order spends from |
| The prediction program's order | The order | Everything in the next section |

An order staked directly in the venue's own token has no route at all, and then the stake has to be exactly what the order costs. Both shapes are supported; anything else is a finding.

## What the review covers

The order instruction carries all of it, as its own Borsh fields, and the review reads each out of the bytes and compares it with what the owner chose and what the provider said: the market's hash, the request's identifier, **which side**, how many contracts, the most a contract may cost, what the order costs, the slippage, and the order and position accounts. Plus, from the message: the owner pays, the order is the owner's, and **the only signature still missing is theirs**.

That last one deserves a note. The venue **co-signs**: an order arrives with the protocol's own slot already filled, and since 2026-09 usually a third, Jupiter's relayer, which pays the fee and has already signed (a *gasless* build). So "nothing else signs" would be the wrong rule here; the right one is "one signature is missing, it is the owner's, and a fee payer that is not the owner has already signed" — and that sponsor may fund only the order's own accounts. A payer still waiting to sign is refused.

## What is out of reach

- **The market hash is not a plain digest of the market ID.** md5, sha1, sha256 and blake2s were all checked against a real pair and none matches. So the market is *cross-checked* — the hash in the bytes is the hash the provider stated for the market it answered about — and not proved from the identifier. A provider that claimed market X and built for market Y could not be caught by this check alone; what does catch it is that the market was read first, and its identity, event and market provider all had to agree.
- **The review depends on the configured endpoint** being honest about a table's contents.
- **A closed or settled market** is the execution provider's word. The phone has no other source for it.
- **What became of a submitted order** is now the provider's report, by the order's own account (`/orders/status`, SEE-172): pending, partly filled, filled, partly filled with the rest returned, or failed. A chain-confirmed transaction is still never called filled on its own.

## Where the owner continues

Since SEE-172 the purchase's History item follows the order's fill and the position it went into, and can sell that position in the app ([prediction positions](prediction-positions.md)). Settlement and payout claims stay on Jupiter.

What is offered instead: the transaction on the block explorer, built from the signature the wallet returned, **the market on Jupiter**, and — once an order has actually been placed — **the order**.

### In the app, not in a browser (SEE-157)

Each of those is opened by asking for an *app* first and falling back to the web only when no app takes it (`activity/Explorer.kt`). The mechanism is `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`: the launch succeeds only if something other than a browser claims the address, and throws as if nothing had handled it at all otherwise — at which point the web address is opened instead.

**Jupiter's deep link is a `jup.ag` address, and that is not a compromise.** [`https://jup.ag/.well-known/assetlinks.json`](https://jup.ag/.well-known/assetlinks.json) delegates `handle_all_urls` to `ag.jup.jupiter.android`, so a `jup.ag` address *is* the Jupiter app's own, verified by Android rather than asserted here. Jupiter publishes no private scheme, and none is invented: a `jupiter://` in this repository would be a URL nobody serves, dressed as nativeness. The boundary carries a deep link and a web address separately anyway, because the next venue's may genuinely differ.

### Where the market's address comes from

`https://jup.ag/prediction/<marketId>` is the address this adapter composes, and it is a guess: the real page is addressed by the **event's slug**, which is in the listing the publisher template reads and in nothing the phone can ask for. The site answers `200` for anything, so the guess cannot even be caught by fetching it.

So since SEE-157 the publisher may name the page — `provider_deep_link` and `provider_web_url` — and this adapter uses it **only when it is Jupiter's own**: `https`, on `jup.ag` or a subdomain of it, matched on the host and never on the end of the string. Anything else is ignored and the composed address stands. That is the whole of the trust boundary: a publisher can make the link land on the market instead of near it, and cannot make it land anywhere but Jupiter.

### The order, and why it is the portfolio

There is still **no per-order page**. Jupiter has none, so an address with an order account in it would be a page nobody serves. What exists is the owner's prediction portfolio — `https://jup.ag/prediction/portfolio` — which is where a placed order actually is, and that is what **Open order** opens.

It appears only when the record holds an order account, which means only after an order was really submitted. A destination there is nothing behind is not shown, for a prediction and for any other action: the boundary's answer to "where may the owner continue" is a list, and an empty list is a valid answer that removes the button.

**No URL is stored**, exactly as before. The record keeps the order account, the position account and the market identifier; every link is built at the moment it is shown, from compiled code, out of those identifiers and the publisher's terms as they are read afresh. A link read back off disk is a link something else could have written.

## Language

"Order submitted" means the wallet reported that it signed and sent a transaction. It does not mean the order filled, that the prediction is right, or that a position will pay out — and the screen says so under the links. An answer the phone never received is recorded as unresolved, never as a failure, and **a possibly dispatched order is never repeated**: an order placed twice is a position twice the size.

## Limits, honestly

- **Mainnet or nothing.** There is no devnet prediction market to point at, and the capability declares mainnet in both environments, so a wallet on another cluster is refused as `NetworkUnsupported` — a separate fact from an environment the provider does not serve.
- **Sandbox gets the same work**, and stops before the wallet: the market is read, the order is built and reviewed, and nothing is signed or sent (SEE-97, [`docs/wiki/environments.md`](environments.md)). The provider never reads the environment — whether bytes are signed is core's.
- **One minute of freshness**, as for a swap: a market's price moves and the transaction carries a recent blockhash.
- **The keyless allowance is 0.5 requests a second, 30 a minute** — four calls per order at most (the market when the review opens, the market again, the order, the tables), which is ample for a person and not for polling. Nothing polls.
- **Buying here; selling from History.** This action only buys. Selling the whole position is `prediction.sell`, reached from the purchase's History item and reviewed on its own bytes ([prediction positions](prediction-positions.md)).
- **The order instruction's flags are `isYes, isBuy`.** Until SEE-172 they were read the other way round, which only a YES buy (`1, 1`) survives: every NO buy was refused. Builds are also gasless since 2026-09 — Jupiter's relayer pays and pre-signs — and the review accepts that under one rule for buys and sales: the only missing signature is the owner's.

## Where the code is

| File | What is in it |
| --- | --- |
| [`plugins/actions/PredictionAction.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/actions/PredictionAction.kt) | The action's payload and every rule a publisher's terms are held to — provider-neutral, read by core |
| [`plugins/actions/PredictionInputs.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/actions/PredictionInputs.kt) | The side and the stake, with the publisher's bounds and the venue's floor folded together |
| [`jupiter/JupiterPredictionAction.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/jupiter/JupiterPredictionAction.kt) | Jupiter's half: `resolve`, the ordered checks in `prepare`, the chain read, the inspection, and the market and order destinations |
| [`plugins/actions/ProviderLink.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/plugins/actions/ProviderLink.kt) | The one rule for an address this app may hand to another app, and the host check an adapter makes about its own property (SEE-157) |
| [`activity/Explorer.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/activity/Explorer.kt) | `openDestination`: the provider's app first, the web only if no app took it |
| [`jupiter/JupiterExecutionProvider.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/jupiter/JupiterExecutionProvider.kt) | The provider itself, and `PREDICTION_BUY_CAPABILITY`: schema 1, mainnet, its two stake mints, its five-dollar floor |
| [`jupiter/JupiterPrediction.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/jupiter/JupiterPrediction.kt), [`jupiter/PredictionInstructions.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/jupiter/PredictionInstructions.kt), [`jupiter/PredictionInspection.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/jupiter/PredictionInspection.kt) | The market read and the order, the instruction's Borsh layout, and `inspectPrediction` |

## Where the rules for this live

- The boundary: [`client-plugins.md`](client-plugins.md). The extension contract in full: [`execution-providers.md`](execution-providers.md). The other action: [`jupiter-swap.md`](jupiter-swap.md).
- The API, its endpoints and its failures: [`integrations/jupiter.md`](../integrations/jupiter.md).
- Resolving a lookup table, and what trusting an endpoint means: [`security.md`](../security.md#resolving-a-lookup-table).
- What is bound before a wallet opens: [`shared-proposals.md`](shared-proposals.md#what-a-signature-is-bound-to).
