# Jupiter, as this app uses it (SEE-93, SEE-94, SEE-96, SEE-145)

One bundled execution provider gets its data from Jupiter, directly from the owner's phone: `jupiter`, serving two provider-neutral actions — `swap`, for which it fetches a route and a transaction, and `prediction.buy`, for which it fetches a market and an order. This page is what was verified about those APIs, what the app sends them, and what happens when they say no. What the app does with the answers is [wiki/jupiter-swap.md](../wiki/jupiter-swap.md) and [wiki/jupiter-prediction.md](../wiki/jupiter-prediction.md).

**Everything on this page is behind the adapter.** Since SEE-145 the boundary in the app is a Solana execution-provider interface rather than a plugin named after this venue ([wiki/execution-providers.md](../wiki/execution-providers.md)): the endpoints, the request shapes, the program layouts, the error codes, the platform link, the stake tokens and the minimum order all live in [`jupiter/`](../../android/app/src/main/java/io/github/brrenat/seekervault/jupiter) and nowhere else. The actions themselves, their payload schemas and the shared Solana machinery name no venue at all. Nothing on this page changed in SEE-145; where it is reached from did.

One thing here is not the phone's: the Prediction publisher template reads the same API's **listing**, from a server, to discover markets to publish (SEE-96). It is a different half of the same API — public information about which markets exist, rather than an order for somebody — and it is [its own section below](#prediction-discovery-see-96).

## The endpoints, pinned

Two calls, on the keyless host:

| Call | Request | What is read from the answer |
| --- | --- | --- |
| `GET https://lite-api.jup.ag/swap/v1/quote` | `inputMint`, `outputMint`, `amount`, `slippageBps`, `swapMode=ExactIn`, `onlyDirectRoutes=true`, `asLegacyTransaction=true` | `inAmount`, `outAmount`, `otherAmountThreshold`, `swapMode`, `slippageBps`, `platformFee`, `routePlan` |
| `POST https://lite-api.jup.ag/swap/v1/swap` | The quote back verbatim, `userPublicKey`, `asLegacyTransaction`, `wrapAndUnwrapSol`, `useSharedAccounts`, `dynamicComputeUnitLimit` — all true | `swapTransaction`, `simulationError`, `addressesByLookupTableAddress`, `lastValidBlockHeight` |

The host is a constant in the adapter's own file (`JupiterProvider.kt`) and a parameter everywhere else, which is what lets a test point it at a local server and what keeps a hostname out of the rest of the app. `StageBoundaryTest` fails if it appears anywhere else.

**Why v1 and not v2.** `api.jup.ag/swap/v2/order` exists and answers keyless too, but it is a combined quote-and-build tied to a `requestId` and Jupiter's own `/execute`, and this app's wallet submits its own transactions. The two-step v1 flow is what the review needs — an offer to show the owner, then bytes built from exactly that offer — and it is what was verified. Both were probed on 2026-09-17: v1 answers on `lite-api.jup.ag` and on `api.jup.ag`; `/swap/v2/order` answers only on `api.jup.ag`, where keyless requests get a much smaller allowance.

## Prediction orders

Three calls, on the same keyless host, all verified on 2026-09-17:

| Call | Request | What is read from the answer |
| --- | --- | --- |
| `GET /prediction/v1/events` | `filter`, `end` — the phone never calls this; the publisher template does ([below](#prediction-discovery-see-96)) | The events, their markets, and each market's status and pricing |
| `GET /prediction/v1/markets/{marketId}` | — | `marketId`, `eventId`, `provider`, `title`, `status`, `result`, `pricing.buyYesPriceUsd`, `pricing.buyNoPriceUsd`, `rulesPrimary`, `closeTime` |
| `POST /prediction/v1/orders` | `ownerPubkey`, `marketId`, `isYes`, `isBuy` (always true), `depositAmount`, `depositMint` | `transaction`, `requiredSigners`, and `order.*`: the order and position accounts, the external order ID, the market hash, the contracts, the price ceiling, the cost, the payout, the fees, the slippage |

**The transaction is versioned and uses address lookup tables, always.** `asLegacyTransaction`, `legacyTransaction`, `useLookupTables: false`, `asLegacy`, `transactionVersion: "legacy"` and `maxAccounts` were each tried and are all ignored — every answer comes back as version 0. That single fact is what makes the prediction side read the chain; the reasoning is in [wiki/jupiter-prediction.md](../wiki/jupiter-prediction.md#why-the-phone-reads-the-chain).

**It is partially signed.** Two signature slots arrive, and the protocol's own is already filled — so `requiredSigners` lists only the owner, and the review's rule is "the only signature still missing is theirs" rather than "nothing else signs".

**The minimum order is five dollars** (`5000000` base units). Since SEE-145 it is declared rather than hidden in a reader: it is `ActionCapability.leastDeposit` on the venue's `prediction.buy` capability, beside the two mints it settles in (`ActionCapability.depositAssets` — Jupiter's own dollar token and USDC). Both are facts about this venue rather than rules of the action, so a publisher naming a token Jupiter will not take is refused when the signal is read, and an amount below the floor is refused before anything is asked of anybody.

**The documentation says these endpoints take an `x-api-key`.** The keyless host serves them without one, which is what this build uses and what `JupiterLiveTest` re-checks: it reads a live market and then asks for an order for a wallet holding nothing, which the provider refuses as `INSUFFICIENT_FUNDS`. That proves the arrangement still works without placing anything.

### The chain, for lookup tables only

One more endpoint, and it is not Jupiter's: a Solana RPC, `getMultipleAccounts`, used only to read the address lookup tables a prediction order's transaction names. It is the **application's** endpoint, configured at build time (`-Pseekervault.solanaRpc=…`) and empty by default, so a checkout reaches no cluster; no publisher, manifest or provider answer can set it. What it is asked is a list of table addresses — public accounts, and asking about one says nothing about who asked.

## Prediction discovery (SEE-96)

The publisher template in [`demo-prediction/`](../../demo-prediction) reads two of these endpoints from a server, to find markets worth publishing. It is the only part of this repository that calls Jupiter from anything but a phone, and what it asks for is public: which markets exist, and what one market currently is. The provider's endpoints for orders, positions, history and profiles are not compiled into that module at all — the phone places the order, and the template never learns that one was placed.

| Call | Request | What is read from the answer |
| --- | --- | --- |
| `GET /prediction/v1/events` | `provider`, `category`, `filter`, `start`, `end`, `includeMarkets=true` | `data[]`: `eventId`, `isActive`, `isLive`, `category`, `subcategory`, `tags[]`, `metadata.title`, `metadata.slug`, and each `markets[]` entry; `pagination.{start,end,hasNext}` |
| `GET /prediction/v1/markets/{marketId}` | — | `marketId`, `eventId`, `provider`, `title`, `status`, `result`, `openTime`, `closeTime`, and Forecast's `tradable` and `lifecycleStatus` |

**Pagination is an offset and an exclusive bound**, not a page number: `start=0&end=25` then `start=25&end=50`, with `hasNext` saying whether to continue. A range of more than 100 items is refused (`"Range cannot exceed 100 items"`), and a `start` past the end answers `{"data":[],"pagination":{…,"hasNext":false}}` — which is how a walk finishes rather than an error.

**The live API accepts more categories than the published schema lists.** The schema names eight; `category=nonsense` is refused with a message naming twelve: `all`, `crypto`, `sports`, `politics`, `esports`, `culture`, `economics`, `tech`, `finance`, `climate & science`, `weather`, `mentions`. The template's own list is that refusal, and [the refusal is committed as a fixture](../../demo-prediction/internal/jupiter/testdata/events-bad-parameter.json) so a test reads it rather than trusting this paragraph.

**The named filters** are `new` (created in the last 24 hours), `live` (begun), `trending` (recent trade activity) and `upcoming` (not begun), and the venues are `polymarket` (the default), `kalshi` and `bisonfi` (Jupiter Forecast). The template always sends the venue explicitly, because a default that changed under it would change what it publishes.

**There is a keyword search, and the template does not use it.** `GET /prediction/v1/events/search?query=…` matches event **titles** only, is capped at 20 results, and cannot be combined with the bucket, the named filter or the pagination — so keyword filtering happens on the retrieved records instead, over the title, the bucket, the subcategory, the tags and the market's own title ([wiki/prediction-template.md](../wiki/prediction-template.md#the-filters-and-what-they-mean)). A `search` parameter on `/events` itself is ignored: it answers the unfiltered listing.

**Errors have a shape**, and its `code` is what tells two 400s apart: `{"type","message","code","param","request_id"}`, where `type` is one of `invalid_request_error`, `authentication_error`, `permission_error`, `idempotency_error`, `rate_limit_error`, `api_error`. A market that does not exist is `404` with `code: market_not_found`, which is the difference between "this market has gone" and any other refusal — and the difference between withdrawing a proposal and leaving it alone.

**There is no stream.** The published OpenAPI document has no websocket, no webhook and no subscription: the only "live" things in it are the `live` filter and the score endpoints. So discovery is bounded polling, paced inside the keyless allowance, and a rate limit is an answer rather than a reason to try harder. Checked on 2026-09-17.

**The API is in beta**, by its own documentation: "The Prediction Market API is currently in beta and subject to breaking changes." What the template does about that is skip a market it cannot read, with the market named in the log, rather than crash or publish half an answer. Seven real answers are committed under [`demo-prediction/internal/jupiter/testdata`](../../demo-prediction/internal/jupiter/testdata) — two pages, an empty page, a refused parameter, an open market, a settled one and a missing one — captured by `node scripts/capture-jupiter.mjs --events`, with the request that produced each recorded beside it. An opt-in test (`SEEKERVAULT_JUPITER=1`) reads the live provider and checks that the fields discovery depends on are all still there.

**A key is optional here too.** The keyless host serves these endpoints without one, which is what the template defaults to; `PREDICTION_API_KEY` raises the allowance on the keyed host. It goes in one `x-api-key` header and nowhere else — never in a manifest, a document, a log line or an answer — and [a test presents one and searches every answer and the whole log for it](../../demo-prediction/internal/api/discovery_test.go).

## Authentication: none, deliberately

Nothing the **app** sends is authenticated. Jupiter's keyless tier is what it uses, so **there is no secret in the APK to extract** and no proxy of the owner's requests through anything of ours — the two things the stage explicitly rules out. An operator who wants higher limits would supply their own key to their own build; that is a build's business and not this app's, and the endpoint is already a parameter.

A publisher's server is the other case, and it is not the same one. The Prediction template may hold a key (`PREDICTION_API_KEY`), because a server polling a listing is exactly what a rate allowance is about — and it is that server's own secret, held by its own deployment, never in anything it publishes. No phone is ever asked to carry it, and no owner's request goes through it.

## Rate limits

Jupiter's documented figures, in a 60-second sliding window, and they cover both actions:

| Tier | Requests per second | Per minute | API key |
| --- | --- | --- | --- |
| Keyless | 0.5 | 30 | No |
| Free | 1 | 60 | Yes |

That is ample for a person deciding about one signal — two calls to prepare a swap, three to prepare an order — and it is not ample for polling, so **nothing in the adapter polls** — including the boundary's own status query, which this provider answers `Unsupported` and which nothing in the app calls. `lite-api.jup.ag` returns no rate-limit headers, so the adapter treats HTTP 429 as the signal and reports it as itself: the owner is told to wait a moment and prepare again, and nothing retries in a loop.

The publisher template does poll, and the allowance is the reason its defaults look the way they do: at most one call every 2.1 seconds, at most 24 calls in a cycle, one cycle every five minutes — about five calls a minute at the busiest. The gap is enforced inside its provider client rather than in its callers, because the way to exceed an allowance is to have two places that each think they are the only one calling. A 429 there stops the walk and makes the cycle partial; nothing retries in a loop on this side either (SEE-96).

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

If that happens, the adapter **stops preparing**: the answer is refused as unusable, the owner is told nothing could be prepared, and no unreadable transaction is put in front of anyone. That is the safe direction and it is the only one available without either an RPC endpoint or a change of principle, both of which are decisions for a later stage rather than something to slip in.

`JupiterLiveTest` is the early-warning test for exactly this. It is opt-in — a check that needs the internet is not a check — and it makes one real quote and one real build and asserts the review still verifies the result:

```
android/gradlew -p android :app:testDebugUnitTest \
  --tests '*JupiterLiveTest' -Dseekervault.jupiter=https://lite-api.jup.ag
```

It spends nothing: a quote is a public read, a build returns unsigned bytes, and nothing in a unit test holds a key or opens a wallet.

## The fixtures

Four real swap transactions are committed under `fixtures/jupiter/swaps.json` — SOL in, SOL out, an output account that does not exist yet, and the non-shared routing variant — and one real prediction order under `fixtures/jupiter/orders.json`, **with the contents of the address lookup tables it names**, read through the same `getMultipleAccounts` call the app uses. Without those tables the order's account indexes mean nothing, so the fixture would not exercise what it exists for.

They are what makes the claim honest: a transaction the tests built themselves would be readable by construction.

The publisher template's own fixtures are separate, and they are answers rather than transactions: seven of them under `demo-prediction/internal/jupiter/testdata`, each with the request that produced it, because what they are for is the shape of a listing and the shape of a refusal ([above](#prediction-discovery-see-96)).

```
node scripts/capture-jupiter.mjs            # recapture the swaps
node scripts/capture-jupiter.mjs --orders   # recapture the order and its tables
node scripts/capture-jupiter.mjs --events   # recapture the listing answers (SEE-96)
node scripts/capture-jupiter.mjs --check    # decode what is committed
```

The capture script has its own reader, in another language, and records what *it* made of each instruction. `JupiterFixturesTest` asserts the phone's reader agrees. Two implementations of one wire format agreeing is worth more than either one's say-so, and a provider that changes the shape of a swap fails a test that names the instruction that moved.

The blockhashes in them expired within the minute they were captured, and the market in the order fixture will settle eventually. Both are deliberately irrelevant: what the review establishes is what a transaction *does*, which does not depend on when anyone built it — and the table contents are captured beside it, so the resolution is reproducible offline for ever.
