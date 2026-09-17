# Jupiter, as this app uses it (SEE-93, SEE-94)

Two plugins get their execution data from Jupiter, directly from the owner's phone: `jupiter.swap` a route and a transaction, `jupiter.prediction` a market and an order. This page is what was verified about those APIs, what the app sends them, and what happens when they say no. What the plugins do with the answers is [wiki/jupiter-swap.md](../wiki/jupiter-swap.md) and [wiki/jupiter-prediction.md](../wiki/jupiter-prediction.md).

## The endpoints, pinned

Two calls, on the keyless host:

| Call | Request | What is read from the answer |
| --- | --- | --- |
| `GET https://lite-api.jup.ag/swap/v1/quote` | `inputMint`, `outputMint`, `amount`, `slippageBps`, `swapMode=ExactIn`, `onlyDirectRoutes=true`, `asLegacyTransaction=true` | `inAmount`, `outAmount`, `otherAmountThreshold`, `swapMode`, `slippageBps`, `platformFee`, `routePlan` |
| `POST https://lite-api.jup.ag/swap/v1/swap` | The quote back verbatim, `userPublicKey`, `asLegacyTransaction`, `wrapAndUnwrapSol`, `useSharedAccounts`, `dynamicComputeUnitLimit` — all true | `swapTransaction`, `simulationError`, `addressesByLookupTableAddress`, `lastValidBlockHeight` |

The host is a constant in the plugin's own file and a parameter everywhere else, which is what lets a test point it at a local server and what keeps a hostname out of the rest of the app. `StageBoundaryTest` fails if it appears anywhere else.

**Why v1 and not v2.** `api.jup.ag/swap/v2/order` exists and answers keyless too, but it is a combined quote-and-build tied to a `requestId` and Jupiter's own `/execute`, and this app's wallet submits its own transactions. The two-step v1 flow is what the review needs — an offer to show the owner, then bytes built from exactly that offer — and it is what was verified. Both were probed on 2026-09-17: v1 answers on `lite-api.jup.ag` and on `api.jup.ag`; `/swap/v2/order` answers only on `api.jup.ag`, where keyless requests get a much smaller allowance.

## Prediction orders

Three calls, on the same keyless host, all verified on 2026-09-17:

| Call | Request | What is read from the answer |
| --- | --- | --- |
| `GET /prediction/v1/events` | `filter`, `end` — used only to find an open market when capturing fixtures | The events, their markets, and each market's status and pricing |
| `GET /prediction/v1/markets/{marketId}` | — | `marketId`, `eventId`, `provider`, `title`, `status`, `result`, `pricing.buyYesPriceUsd`, `pricing.buyNoPriceUsd`, `rulesPrimary`, `closeTime` |
| `POST /prediction/v1/orders` | `ownerPubkey`, `marketId`, `isYes`, `isBuy` (always true), `depositAmount`, `depositMint` | `transaction`, `requiredSigners`, and `order.*`: the order and position accounts, the external order ID, the market hash, the contracts, the price ceiling, the cost, the payout, the fees, the slippage |

**The transaction is versioned and uses address lookup tables, always.** `asLegacyTransaction`, `legacyTransaction`, `useLookupTables: false`, `asLegacy`, `transactionVersion: "legacy"` and `maxAccounts` were each tried and are all ignored — every answer comes back as version 0. That single fact is what makes this plugin read the chain; the reasoning is in [wiki/jupiter-prediction.md](../wiki/jupiter-prediction.md#why-the-phone-reads-the-chain).

**It is partially signed.** Two signature slots arrive, and the protocol's own is already filled — so `requiredSigners` lists only the owner, and the review's rule is "the only signature still missing is theirs" rather than "nothing else signs".

**The minimum order is five dollars** (`5000000` base units), which the plugin enforces before asking for anything.

**The documentation says these endpoints take an `x-api-key`.** The keyless host serves them without one, which is what this build uses and what `JupiterLiveTest` re-checks: it reads a live market and then asks for an order for a wallet holding nothing, which the provider refuses as `INSUFFICIENT_FUNDS`. That proves the arrangement still works without placing anything.

### The chain, for lookup tables only

One more endpoint, and it is not Jupiter's: a Solana RPC, `getMultipleAccounts`, used only to read the address lookup tables a prediction order's transaction names. It is the **application's** endpoint, configured at build time (`-Pseekervault.solanaRpc=…`) and empty by default, so a checkout reaches no cluster; no publisher, manifest or provider answer can set it. What it is asked is a list of table addresses — public accounts, and asking about one says nothing about who asked.

## Authentication: none, deliberately

Nothing here is authenticated. Jupiter's keyless tier is what the app uses, so **there is no secret in the APK to extract** and no proxy of the owner's requests through anything of ours — the two things the stage explicitly rules out. An operator who wants higher limits would supply their own key to their own build; that is a build's business and not this app's, and the endpoint is already a parameter.

## Rate limits

Jupiter's documented figures, in a 60-second sliding window, and they cover both plugins:

| Tier | Requests per second | Per minute | API key |
| --- | --- | --- | --- |
| Keyless | 0.5 | 30 | No |
| Free | 1 | 60 | Yes |

That is ample for a person deciding about one signal — two calls to prepare a swap, three to prepare an order — and it is not ample for polling, so nothing in either plugin polls. `lite-api.jup.ag` returns no rate-limit headers, so the plugin treats HTTP 429 as the signal and reports it as itself: the owner is told to wait a moment and prepare again, and nothing retries in a loop.

## What goes to Jupiter, and what does not

- **The quote** carries two mint addresses and an amount. Nothing about the owner: a price is a public fact.
- **A market read** carries a market identifier, and nothing else at all.
- **The build** — a swap's or an order's — additionally carries the owner's public address, because a transaction has to be built for the account that will sign it.
- **Nothing else, ever.** Not which publisher proposed it, not the proposal's ID, not the signature afterwards, and no result of any kind — there is nothing to report to anybody.
- **And nothing at all to the publisher or the shared gateway.** `OperationPrivacyTest` captures every request to both and searches it; `PredictionOperationTest` does the same for an order.

## When it says no

| What happened | How it is reported | What the owner sees |
| --- | --- | --- |
| Network unreachable, or no answer | `provider_unreachable` | The provider could not be reached; nothing was prepared |
| HTTP 429 | `provider_rate_limited` | Wait a moment and prepare again |
| No route for the pair at this size | `no_route` | There is no direct route right now |
| Any other refusal | `provider_refused`, with the status and never the body | The provider refused |
| An answer that cannot be used — a missing field, an amount that is not base units, a quote about another pair, another amount or another slippage, a platform fee somebody added, more hops than were asked for, a transaction that needs a lookup table, bytes that are not base64 | `provider_unusable` | The answer could not be used |
| `simulationError` — most often no funds | `would_fail`, carrying the provider's own words | The provider tried it and it failed |

For an order, two of its refusals are told apart from the rest because the owner should hear them as themselves: `INSUFFICIENT_FUNDS` (the provider checks the balance, which is how a phone that reaches no chain can honestly report one) and a market that closed between the read and the order. A `404` is "the provider has no such market", which a publisher naming one that never existed will produce.

None of these produces an approximate preparation. A failure is a failure, and the owner's next step is the same for most of them, which is why the unusable cases are one code rather than seven: the difference is in the log, not in the decision.

The simulation is worth calling out, because it is the one honest answer to "does this owner have the funds". The phone reaches no chain and cannot know a balance; `dynamicComputeUnitLimit` makes Jupiter simulate the transaction it just built, and a failure there is reported with Jupiter's own sentence rather than guessed at.

## The standing risk

`asLegacyTransaction` is the one thing this integration depends on that could be taken away. Without it, every route transaction is a versioned message with address lookup tables, and a phone that reaches no chain cannot establish what such a message touches without believing the builder about it — which this app does not do for anybody.

If that happens, the plugin **stops preparing**: the answer is refused as unusable, the owner is told nothing could be prepared, and no unreadable transaction is put in front of anyone. That is the safe direction and it is the only one available without either an RPC endpoint or a change of principle, both of which are decisions for a later stage rather than something to slip in.

`JupiterLiveTest` is the early-warning test for exactly this. It is opt-in — a check that needs the internet is not a check — and it makes one real quote and one real build and asserts the review still verifies the result:

```
android/gradlew -p android :app:testDebugUnitTest \
  --tests '*JupiterLiveTest' -Dseekervault.jupiter=https://lite-api.jup.ag
```

It spends nothing: a quote is a public read, a build returns unsigned bytes, and nothing in a unit test holds a key or opens a wallet.

## The fixtures

Four real swap transactions are committed under `fixtures/jupiter/swaps.json` — SOL in, SOL out, an output account that does not exist yet, and the non-shared routing variant — and one real prediction order under `fixtures/jupiter/orders.json`, **with the contents of the address lookup tables it names**, read through the same `getMultipleAccounts` call the app uses. Without those tables the order's account indexes mean nothing, so the fixture would not exercise what it exists for.

They are what makes the claim honest: a transaction the tests built themselves would be readable by construction.

```
node scripts/capture-jupiter.mjs            # recapture the swaps
node scripts/capture-jupiter.mjs --orders   # recapture the order and its tables
node scripts/capture-jupiter.mjs --check    # decode what is committed
```

The capture script has its own reader, in another language, and records what *it* made of each instruction. `JupiterFixturesTest` asserts the phone's reader agrees. Two implementations of one wire format agreeing is worth more than either one's say-so, and a provider that changes the shape of a swap fails a test that names the instruction that moved.

The blockhashes in them expired within the minute they were captured, and the market in the order fixture will settle eventually. Both are deliberately irrelevant: what the review establishes is what a transaction *does*, which does not depend on when anyone built it — and the table contents are captured beside it, so the resolution is reproducible offline for ever.
