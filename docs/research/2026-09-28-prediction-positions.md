# Prediction positions and selling them — research note (SEE-172)

Dated 2026-09-28. Everything below was read from Jupiter's **keyless** host, `https://lite-api.jup.ag`,
which is the host and access mode the app uses. Nothing was signed or sent. The sanitized captures
are in [`fixtures/jupiter/positions.json`](../../fixtures/jupiter/positions.json), written by
`node scripts/capture-jupiter.mjs --positions`; the owner in them is a public trader taken from
Jupiter's own public trade feed (`GET /prediction/v1/trades`), so nothing in them is private.

## Access

| Call | Keyless answer | Used for |
| --- | --- | --- |
| `GET /prediction/v1/positions/{positionPubkey}` | 200 with the position; **404 `position_not_found`** for an unknown account | Live position block |
| `GET /prediction/v1/positions?ownerPubkey=…` | 200, paginated; 400 without `ownerPubkey` | Not used by the app (the purchase names its position account) |
| `GET /prediction/v1/orders/status/{orderPubkey}` | 200 with `status`, `latestEventType` and an event `history` carrying `fillInfo`; 404 when unindexed | Buy and sale order fills |
| `GET /prediction/v1/orders/{orderPubkey}` | **404 once an order is closed** | Not used: a finished order disappears here but stays in `/orders/status` |
| `DELETE /prediction/v1/positions/{positionPubkey}` body `{"ownerPubkey"}` | 200 with an unsigned transaction and the order it places | Building a whole-position sale |
| `POST /prediction/v1/execute` | Not called | See execution models |

The OpenAPI document (`developers.jup.ag/docs/openapi-spec/prediction/prediction.yaml`) declares
`x-api-key` for all of these, like it does for the buy endpoints; the keyless host serves them without
one. No key is in the APK and nothing goes through the SAC gateway. `api.jup.ag` returned
`x-ratelimit-remaining: 4` on a keyless probe; `lite-api` returns no rate-limit headers, so the app
keeps the documented 0.5 requests/second allowance with one shared gate (2.1 s apart, 30 s pause after
a 429).

## Findings that changed code

1. **The order instruction's two flags are `isYes, isBuy`, in that order.** The reader had them the
   other way round. A YES buy writes `01 01` and cannot tell; the captured NO buy writes `00 01` and
   the YES sale writes `01 00`. Every NO buy was therefore refused as "not buying". Fixed in
   `PredictionInstructions.kt`, proven by `PositionFixturesTest`.
2. **Builds are gasless now.** Both the close and a fresh NO buy come back with three signature
   slots: Jupiter's relayer (fee payer, already signed), the protocol (already signed) and the owner
   (empty) — `isGasless: true`. The owner's slot index varies (1 or 2). The buy review required the
   owner to be the fee payer and refused all of these. Both reviews now share one rule: *the only
   empty slot is the owner's, and a fee payer that is not the owner must already have signed*; that
   sponsor may fund the order's and the owner's proceeds account creation and nothing else.
3. **A sale is the same place-order instruction** with the direction flag clear. Its price field is
   the **floor** (`minSellPriceUsd`), its cost is 0, and the bytes carry slippage 0 / none even when
   the JSON reports `slippageBps: 50, maxSlippageBps: 2500`. The floor is the only enforceable price
   protection. In both captures the floor was 25 % under the best bid (270000 vs 350000 rounded up;
   739000 vs 985000).
4. **`/orders/status` reports partial fills.** A real buy asked for 65.66 contracts, filled 63.55
   (`PartiallyFilled`), then `order_closed` — the rest was returned. The app maps that to
   *partly filled, rest returned* and never to *filled*.

## Execution models

| Model (`executionModel` / `execution.context.type`) | Submission | App |
| --- | --- | --- |
| `null` / `create_order` — keeper-filled (Polymarket and Kalshi markets seen) | The signed transaction creates an order account on chain; a keeper fills it. The owner's wallet submits it, exactly like a buy. | **Supported** — sign-and-send through Mobile Wallet Adapter, never also through `/execute` |
| `atomic_swap` / `bisonfi_swap` — Jupiter Forecast | Must go through `POST /execute` with the opaque context | **Refused before review** with a precise reason and the Jupiter link; submitting it through the wallet would be the wrong path and submitting it through both would be a double submission |

## Aggregation

A position account is a PDA of owner + market + side: every purchase of that side of that market by
that wallet — from any signal, or from outside SAC — lands in the same account. So one History item's
"position" is the wallet's whole holding, and a close sells all of it (`newContracts: 0`). The app
links every purchase naming the same account to one holding, reads it once, shows it identically on
each item, and says in the sale review that the whole position is sold.

## Cancellation

The OpenAPI document has no order-cancellation operation (`/orders` is GET/POST, `/orders/{pubkey}`
GET, `/orders/status/{pubkey}` GET; the only DELETEs close positions). Keeper orders that don't fill
are closed by the keeper and their remainder returned (`order_closed`). **No Cancel button is shown.**

## Sale validation rules (what `inspectPredictionSale` enforces)

Decode whole; resolve lookup tables from the chain; the only empty signature is the owner's; a
non-owner fee payer has already signed; exactly one order instruction, of the prediction program,
**selling**, the held side, for the held position, owned by the owner, paid by the owner or the
sponsor; order account and external ID as the provider stated; market hash as stated; contracts equal
to what the stated close sells **and** to what the position held when read a moment before, and
`newContracts` 0; cost 0; floor equal to the stated floor, non-zero, and no more than 25 % under the
current best bid; byte-level slippage no more than 25 %; the mint is JupUSD and the proceeds land in
the owner's JupUSD associated account; the only other instructions are compute-budget settings and
the creation of that very account; an owner-paid priority fee at most 0.005 SOL, over the runtime's
default compute-unit limit when the bytes set none (PR #91 review); the provider's fee
estimate below the least the sale can gross. Adversarial fixtures for each rule are in
`PredictionSaleInspectionTest`.

## Links

The existing app-first mechanism (SEE-157) is reused: `https://jup.ag/prediction/portfolio` ("Your
positions on Jupiter") and the market page. `jup.ag` delegates its URLs to `ag.jup.jupiter.android`
via Digital Asset Links, so the same address opens the Jupiter app when it is installed and a browser
otherwise. No per-position page and no private scheme exist, and none is invented. **Not re-verified
on a device in this change** (see below).

## Test networks

There is no devnet or testnet prediction market; the capability is mainnet only, as for buys. No
sandbox dependency was added: a sandbox connection never reaches the sale, which is reached only from
a production purchase's History item.

## What was verified how

| Evidence | What |
| --- | --- |
| Live read-only API (2026-09-28) | Position read, 404 shape, order status with partial fill, close build, NO buy build, gasless signer layout |
| Captured transaction bytes | Flag order, sale layout, floor, gasless slots; both real builds pass the phone's own review (`PositionFixturesTest`) |
| Controlled tests | Adversarial sale fixtures, HTTP parsing, lifecycle (decline, expiry, changed position, repeated taps, process death, unknown answer, partial fill, chain failure, clear), store/migration, mapping, UI actions, navigation |
| Real wallet / device | **Not run.** No authorized mainnet test setup was available to this change, and the ticket does not authorize mainnet trading on its own. |

### User-assisted smoke procedure (remaining validation)

On a Seeker (or emulator with a wallet) holding a funded mainnet wallet and a SAC build from this
branch with `SOLANA_RPC` set:

1. Buy a small prediction (≥ $5) from a feed signal; approve in the wallet.
2. Open the item under Inbox → History. Expect "Position now" to show *Not found yet* or *Live*, and
   "Your order" to move from *Waiting to fill* to *Filled* / *Partly filled* on Refresh.
3. Tap **Sell position**. Check the review: contracts equal the position, the floor, "sells your
   entire current position", and the proceeds account is yours. Tap **Sell in wallet** and approve.
4. The item shows *Sale · Sent · waiting to fill* and a *Sell position* transaction; Refresh until it
   reads *Sold* (or *Partly sold*) with proceeds, and the position reads *Sold*.
5. Repeat step 3, decline in the wallet: the sale reads *Declined in wallet* and Sell is offered again.
6. Tap "Your positions on Jupiter" with and without the Jupiter app installed.

Record each step PASS / FAIL / NOT RUN.
