# The Jupiter Prediction plugin (SEE-94)

`jupiter.prediction` is the app's second client plugin. A publisher broadcasts a market; each owner picks a side and a stake on their own phone; the order is prepared through Jupiter's own API, resolved and read by the phone itself, and signed once by the owner's wallet. Then the app **stops** and hands the owner a link.

It is written against the same boundary as the swap ([client plugins](client-plugins.md)) and reaches the same provider, plus one thing the swap needs and does not use: a read-only account reader for the chain.

## The publisher names a market, and nothing else

| Term | Meaning | Rule |
| --- | --- | --- |
| `market_id` | The provider's identifier for the market | A bounded identifier, never a URL |
| `event_id` | The event it belongs to, optionally | Cross-checked against the event the provider names |
| `provider` | Its source, optionally | Cross-checked the same way |
| `deposit_mint` | The token a stake is deposited in | One of the two the provider takes: its own dollar token, or USDC |
| `deposit_decimals` | Its base units per whole token | 0–18, display only |
| `least_deposit`, `most_deposit` | Optional bounds, in base units | The provider's five-dollar minimum is a floor under both |
| `deposit_symbol` | An optional label | At most 16 characters, unverified |

**Everything else about the market comes from the provider, at the moment the owner looks**: whether it is open, what the two sides cost, what the rules say, when it settles, whether it has already resolved. A publisher's prose is prose — a signal saying "this is nearly certain" is shown as the publisher's opinion beside the market's own state, and no field can stand in for a fact the provider would have given. That division is what lets a publisher be a stranger.

The market is read **before** an order is requested, which is how a signal that has gone stale becomes "this market is no longer open" rather than an order that fails.

## What is the owner's

The side and the stake. There is deliberately no default side: a market has two answers and no third, and suggesting one would be the app expressing an opinion about a market it has no basis for. The stake has no suggestion for the same reason a swap's amount does not.

Neither number reaches the publisher or the gateway, and there is nowhere for it to: a feed connection has no outbox, no result upload, no per-subscriber state. `PredictionOperationTest` runs the whole path and reads back what the gateway was told — a channel and a sequence.

## Why the phone reads the chain

This is the one real addition SEE-94 makes to the app, and it was the owner's call.

Jupiter's Prediction API returns **only** a versioned transaction whose accounts come from address lookup tables. There is no legacy option — six plausible parameter spellings were tried against the live API and all are ignored — so the escape the swap uses is not available here. In a real captured order, 35 of the aggregator's 55 accounts and 4 of the prediction program's 12 sat behind those tables: the phone could read the order's numbers but not see which accounts move funds.

Three ways out were possible: sign it anyway on a parameter-only review, refuse the format and ship a plugin that never prepares, or **read the tables**. The owner chose the third, with these terms:

- resolution lives in a shared Solana component that neither couples core to a provider nor is configured by a publisher;
- the tables are discovered from each transaction rather than hardcoded;
- ownership, format, indices and the rebuilt account list are all validated;
- the instructions are then checked against the reviewed action exactly as before, because resolving a table proves only which accounts the runtime will use and **nothing** about whether they are the right ones;
- and a table that cannot be fetched or validated **blocks signing with a stated reason** — never a parameter-only review, never a blind signature.

`solana/` is that component: one read method (`getMultipleAccounts`), a table parser, and the rebuild. It names no provider, so both plugins may use it; the swap asks for a format that needs no resolution and so never does, which also means the resolver is already in place if `asLegacyTransaction` is ever withdrawn.

**What it costs, said plainly:** the review is then only as accurate as the configured endpoint. This is not offline verification and is not trustless — see [security.md](../security.md#resolving-a-lookup-table). The endpoint is the application's or its host's (`-Pseekervault.solanaRpc=…`), is **empty by default** so a checkout reaches no cluster, and a build without one prepares no order and says so.

## What an order is made of

Verified against a real order captured from the live API (`fixtures/jupiter/orders.json`):

| Instruction | Why it is there | What the review binds |
| --- | --- | --- |
| Compute budget ×2 | What the owner pays to be picked up | Read, and shown |
| Associated token account, idempotent | The owner's own account for the provider's token | For the owner, paid by the owner, at the address that derives from both |
| Jupiter `route` / `sharedAccountsRoute` | Funding the stake by swap | **SEE-93's reader, unchanged**: the owner authorizes it, the input leaves their own account for the mint they chose, no more than their stake, and it lands in the very account the order spends from |
| The prediction program's order | The order | Everything in the next section |

An order staked directly in the provider's own token has no route at all, and then the stake has to be exactly what the order costs. Both shapes are supported; anything else is a finding.

## What the review covers

The order instruction carries all of it, as its own Borsh fields, and the review reads each out of the bytes and compares it with what the owner chose and what the provider said: the market's hash, the request's identifier, **which side**, how many contracts, the most a contract may cost, what the order costs, the slippage, and the order and position accounts. Plus, from the message: the owner pays, the order is the owner's, and **the only signature still missing is theirs**.

That last one deserves a note. The provider **co-signs**: an order arrives with two signature slots and the protocol's own already filled. So "nothing else signs" would be the wrong rule here; the right one is "one signature is missing, it is the owner's, and the owner is the fee payer". Both ways of breaking it are refused.

## What is out of reach

- **The market hash is not a plain digest of the market ID.** md5, sha1, sha256 and blake2s were all checked against a real pair and none matches. So the market is *cross-checked* — the hash in the bytes is the hash the provider stated for the market it answered about — and not proved from the identifier. A provider that claimed market X and built for market Y could not be caught by this check alone; what does catch it is that the market was read first, and its identity, event and source all had to agree.
- **The review depends on the configured endpoint** being honest about a table's contents.
- **A closed or settled market** is the provider's word. The phone has no other source for it.

## Where the owner continues

The app submits and stops. There is no fill monitoring, no positions screen, no settlement, no payout claim and no profit or loss anywhere in it — all of that is explicitly out of scope, and none of it is implied by anything on screen.

What is offered instead, once an order has been submitted: the transaction on the block explorer, built from the signature the wallet returned, and **the market on Jupiter** — `https://jup.ag/prediction/<marketId>`, for a market identifier that came from the provider's own API.

There is deliberately **no position link**. The platform has no per-position address: `https://jup.ag/prediction/<marketId>` is a real route and echoes the market ID into its page, but the site answers `200` even for a market that does not exist, so a specific page cannot be verified by HTTP. Inventing a position URL would be the one dishonest thing on offer here, so it is not invented — which is what the ticket asks for.

**No URL is stored.** The record keeps the order account, the position account and the market identifier; every link is built at the moment it is shown, from compiled code. A link read back off disk is a link something else could have written.

## Language

"Order submitted" means the wallet reported that it signed and sent a transaction. It does not mean the order filled, that the prediction is right, or that a position will pay out — and the screen says so under the links. An answer the phone never received is recorded as unresolved, never as a failure, and **a possibly dispatched order is never repeated**: an order placed twice is a position twice the size.

## Limits, honestly

- **Mainnet or nothing.** There is no devnet prediction market to point at.
- **Sandbox gets the same work**, and stops before the wallet: the market is read, the order is built and reviewed, and nothing is signed or sent (SEE-97, [`docs/wiki/environments.md`](environments.md)). The plugin never reads the environment — whether bytes are signed is core's.
- **One minute of freshness**, as for a swap: a market's price moves and the transaction carries a recent blockhash.
- **The keyless allowance is 0.5 requests a second, 30 a minute** — three calls per order (market, order, tables), which is ample for a person and not for polling. Nothing polls.
- **Buying only.** Selling a position is managing one, and this app does not.

## Where the rules for this live

- The boundary: [`client-plugins.md`](client-plugins.md). The other plugin: [`jupiter-swap.md`](jupiter-swap.md).
- The provider, its endpoints and its failures: [`integrations/jupiter.md`](../integrations/jupiter.md).
- Resolving a lookup table, and what trusting an endpoint means: [`security.md`](../security.md#resolving-a-lookup-table).
- What is bound before a wallet opens: [`shared-proposals.md`](shared-proposals.md#what-a-signature-is-bound-to).
