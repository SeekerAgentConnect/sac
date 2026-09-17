# Jupiter, as this app uses it (SEE-93)

The `jupiter.swap` plugin gets a route and a transaction from Jupiter, directly from the owner's phone. This page is what was verified about that API, what the app sends it, and what happens when it says no. What the plugin does with the answer is [wiki/jupiter-swap.md](../wiki/jupiter-swap.md).

## The endpoints, pinned

Two calls, on the keyless host:

| Call | Request | What is read from the answer |
| --- | --- | --- |
| `GET https://lite-api.jup.ag/swap/v1/quote` | `inputMint`, `outputMint`, `amount`, `slippageBps`, `swapMode=ExactIn`, `onlyDirectRoutes=true`, `asLegacyTransaction=true` | `inAmount`, `outAmount`, `otherAmountThreshold`, `swapMode`, `slippageBps`, `platformFee`, `routePlan` |
| `POST https://lite-api.jup.ag/swap/v1/swap` | The quote back verbatim, `userPublicKey`, `asLegacyTransaction`, `wrapAndUnwrapSol`, `useSharedAccounts`, `dynamicComputeUnitLimit` — all true | `swapTransaction`, `simulationError`, `addressesByLookupTableAddress`, `lastValidBlockHeight` |

The host is a constant in the plugin's own file and a parameter everywhere else, which is what lets a test point it at a local server and what keeps a hostname out of the rest of the app. `StageBoundaryTest` fails if it appears anywhere else.

**Why v1 and not v2.** `api.jup.ag/swap/v2/order` exists and answers keyless too, but it is a combined quote-and-build tied to a `requestId` and Jupiter's own `/execute`, and this app's wallet submits its own transactions. The two-step v1 flow is what the review needs — an offer to show the owner, then bytes built from exactly that offer — and it is what was verified. Both were probed on 2026-09-17: v1 answers on `lite-api.jup.ag` and on `api.jup.ag`; `/swap/v2/order` answers only on `api.jup.ag`, where keyless requests get a much smaller allowance.

## Authentication: none, deliberately

Nothing here is authenticated. Jupiter's keyless tier is what the app uses, so **there is no secret in the APK to extract** and no proxy of the owner's requests through anything of ours — the two things the stage explicitly rules out. An operator who wants higher limits would supply their own key to their own build; that is a build's business and not this app's, and the endpoint is already a parameter.

## Rate limits

Jupiter's documented figures, in a 60-second sliding window:

| Tier | Requests per second | Per minute | API key |
| --- | --- | --- | --- |
| Keyless | 0.5 | 30 | No |
| Free | 1 | 60 | Yes |

That is ample for a person deciding about one signal — two calls per preparation — and it is not ample for polling, so nothing in the plugin polls. `lite-api.jup.ag` returns no rate-limit headers, so the plugin treats HTTP 429 as the signal and reports it as itself: the owner is told to wait a moment and prepare again, and nothing retries in a loop.

## What goes to Jupiter, and what does not

- **The quote** carries two mint addresses and an amount. Nothing about the owner: a price is a public fact.
- **The build** additionally carries the owner's public address, because a transaction has to be built for the account that will sign it.
- **Nothing else, ever.** Not which publisher proposed the swap, not the proposal's ID, not the signature afterwards, and no result of any kind — there is nothing to report to anybody.
- **And nothing at all to the publisher or the shared gateway.** `OperationPrivacyTest` captures every request to both and searches it.

## When it says no

| What happened | How it is reported | What the owner sees |
| --- | --- | --- |
| Network unreachable, or no answer | `provider_unreachable` | The provider could not be reached; nothing was prepared |
| HTTP 429 | `provider_rate_limited` | Wait a moment and prepare again |
| No route for the pair at this size | `no_route` | There is no direct route right now |
| Any other refusal | `provider_refused`, with the status and never the body | The provider refused |
| An answer that cannot be used — a missing field, an amount that is not base units, a quote about another pair, another amount or another slippage, a platform fee somebody added, more hops than were asked for, a transaction that needs a lookup table, bytes that are not base64 | `provider_unusable` | The answer could not be used |
| `simulationError` — most often no funds | `would_fail`, carrying the provider's own words | The provider tried it and it failed |

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

Four real transactions, captured from the live API, are committed under `fixtures/jupiter/swaps.json` and are what the reader is tested against — SOL in, SOL out, an output account that does not exist yet, and the non-shared routing variant. They are what makes the claim honest: a transaction the tests built themselves would be readable by construction.

```
node scripts/capture-jupiter.mjs            # recapture
node scripts/capture-jupiter.mjs --check    # decode what is committed
```

The capture script has its own reader, in another language, and records what *it* made of each instruction. `JupiterFixturesTest` asserts the phone's reader agrees. Two implementations of one wire format agreeing is worth more than either one's say-so, and a provider that changes the shape of a swap fails a test that names the instruction that moved.

The blockhashes in them expired within the minute they were captured, and that is deliberately irrelevant: what the review establishes is what a transaction *does*, which does not depend on when anyone built it.
