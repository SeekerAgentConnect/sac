# The Jupiter Swap plugin (SEE-93)

`jupiter.swap` is the first client plugin this app carries. A publisher broadcasts a spot-swap signal to everyone subscribed to its feed; each owner chooses their own amount on their own phone, the plugin gets a route and a transaction from Jupiter, the phone reads those bytes back for itself, and the owner's wallet signs — once, by hand.

It is written against the boundary [SEE-86](client-plugins.md) landed and takes nothing else: no credential, no wallet authorization token, no store, no approval, and neither of the app's own transports. It reaches one host of its own, which is the whole of what a plugin is allowed to do that core cannot.

## What a signal has to say

A proposal carries at most 32 named terms and core interprets none of them ([shared proposals](shared-proposals.md)). These are the ones this plugin reads:

| Term | Meaning | Rule |
| --- | --- | --- |
| `input_mint` | The mint the owner spends | An exact base58 mint address. Native SOL is the wrapped mint, spelled out |
| `input_decimals` | Its base units per whole token | 0–18, for display only |
| `output_mint` | The mint the owner receives | As above, and never the same as the input |
| `output_decimals` | | |
| `max_slippage_bps` | The most the publisher will have its signal acted on with | 1–10000 basis points |
| `least_input`, `most_input` | Optional bounds on the amount, in the input mint's base units | Whole numbers; a floor above the ceiling is refused |
| `input_symbol`, `output_symbol` | Optional labels | At most 16 characters, shown as the publisher's word and never believed |

**An asset is a mint, never a ticker.** "BTC" names a dozen things on Solana and nothing at all off it, so there is deliberately no way to express "buy Bitcoin": a signal about Bitcoin is a signal about one specific wrapped mint on this chain, and a publisher that will not name it has not said what it is proposing. A term this plugin does not know is ignored rather than refused — a publisher may say more than this reads — and is never consulted, so nothing in one can change what is prepared.

**Direction is the pair, and the pair is ordered.** There is no separate side or direction field, because a field that could disagree with the pair is a field that will.

## What is the owner's

The amount, and the slippage they will tolerate within the publisher's ceiling. Both are collected in base units and whole basis points, because that is what the transaction carries and what a rule is written in. The amount is never suggested — how much of their own money to spend is the one thing the app has no opinion about — and the slippage starts at half a percent, or at the publisher's ceiling when that is tighter.

Neither number ever leaves the phone for the publisher or the gateway. That is not a promise; it is the shape of the thing: a feed connection has no outbox, no result upload and no per-subscriber state anywhere. `OperationPrivacyTest` runs the whole path against two real HTTP servers and reads back every byte the phone sent each of them.

## Why a legacy transaction

The phone reaches no chain, on purpose, so it can only approve bytes it can read on its own. A versioned Solana message loads most of its accounts from **address lookup tables**, and resolving one needs the chain: offline, the phone cannot know which accounts those indexes name, and the decoder refuses such a message outright. Jupiter will state what its tables contain, and that answer is exactly the kind of thing this app has never accepted — a builder's account of its own bytes, which is not evidence about them (see [security.md](../security.md#inspecting-a-swap)).

So the plugin asks for `asLegacyTransaction=true` and every account is in the message. It also asks for `onlyDirectRoutes=true`, which narrows which pairs can be swapped and occasionally costs a better price; what it buys is a transaction of a fixed, small shape whose single hop the phone then *verifies in the bytes* rather than merely having asked for. If the provider answers with something that needs a lookup table anyway, nothing is prepared.

This is the plugin's standing risk and it is written down as one: **if `asLegacyTransaction` ever stops being served, this plugin stops preparing rather than starts signing what it cannot read.** [integrations/jupiter.md](../integrations/jupiter.md#the-standing-risk) has the detail.

## What a swap is made of

Six instruction kinds appear, and all six are read:

| Instruction | Why it is there | What is checked |
| --- | --- | --- |
| Compute budget: unit limit, unit price | What the owner pays to be picked up | Read, and shown as a fee in SOL |
| System transfer | Wrapping native SOL | From the owner, to the owner's own wrapped-SOL account, no more than the amount they are spending |
| Token `SyncNative` | Crediting that account | It is the owner's own wrapped-SOL account |
| Token `CloseAccount` | Unwrapping what is left | The owner's own account, closed **to the owner** |
| Associated token account, idempotent create | The output needs somewhere to land | For the owner, paid by the owner, for one of the two mints, at the address that derives from both |
| Jupiter `route` / `sharedAccountsRoute` | The swap | Everything in the next section |

Anything else is a finding rather than an assumption: a plain token transfer alongside the swap, an `Approve` that would hand a delegate the account, an aggregator instruction this plugin was not written for, an instruction from somewhere else entirely. None of them is approvable.

## What the review covers

Inside the routing instruction, the phone reads the accounts and the four numbers that bound the owner's outcome:

- the **authority** is the owner, so nothing else lets the input leave;
- the **source** is the owner's own token account for the input mint, derived from their key and that mint;
- the **destination** is the owner's own token account for the output mint;
- the **input amount** is exactly what the owner entered;
- the **quoted output** and the **slippage** are the ones the offer stated, and the floor derived from them is the provider's own stated threshold to the base unit;
- the **platform fee** is absent, in both the account and the basis points — nobody takes a share;
- the **route plan has one hop**, read from the instruction rather than trusted from the request.

What it does **not** read is the route plan itself, which names the pools the aggregator will hop through and is a different shape for each of the hundred-odd venues it supports. That is a real limit, and it is not a hole in the review, because the route plan cannot change any of the things above: the program takes the input from that account, puts the output in that account, and fails the whole transaction unless the output is at least the quoted amount less the slippage. **The phone verifies the bounds; the chain enforces them.** So the worst case the owner is agreeing to is exactly the worst case they were shown — this much leaves, at least that much arrives, or nothing happens at all.

Reading those four numbers from the end of the instruction's data is exact rather than approximate: Borsh writes fields one after another with no padding, so the last nineteen bytes are those four fields and nothing else. And if a later version of the program moved them, they would stop agreeing with the owner's own choice and with the quote, and the review would refuse the bytes rather than misread them.

### The owner receives

`recipient` in the facts is the owner's own address, and the difference from a transfer is worth stating. In a transfer the destination is an address *somebody else named*, so an address that merely derives correctly proves nothing — a token account's authority can be handed to someone else after its address was derived — and the transaction is made to have the chain confirm it. In a swap the destination is derived from the owner's own key and the mint the publisher named; there is no third party's claim about it to check. If the owner has previously given their own token account away, that was their own earlier signature, and it is not something this review can or should undo.

## The order things happen in

1. The owner opens a signal from the feed's list and enters an amount. Nothing has been asked of anybody.
2. **Prepare** writes down what they chose, gets a quote, gets a transaction, and reads it back. A build the provider's own simulation rejected — most often for want of funds — is reported as that and not offered.
3. The review shows the publisher's words, then the facts out of the bytes, then what the owner's rules make of those facts, in that order and never instead of each other.
4. **Approve and swap** re-reads the rules and compares the verdict with the one they were shown, takes the wallet lock, and only then binds the operation and writes it down — which is where the expiry, the revision, the choice, the plugin and the wallet are all checked at once, on the far side of the wait.
5. The wallet is asked once. The answer is recorded once; the first word stands.

Changing any parameter throws away what was prepared for the old one, because bytes for an old number sitting on screen beside a new one is how somebody comes to approve a transaction they are not looking at. A preparation stands for **one minute** — a quote is a price from a moment ago and a legacy blockhash lasts about that long — and past it the owner prepares again and reviews again.

## Which promise, and which network

**This plugin does not read the environment, and that is the point (SEE-97).** Sandbox and production get the same work: the same quote, the same build, the same bytes, the same inspection. What differs is what happens afterwards, and afterwards is not the plugin's — core holds the wallet, so core is what signs or rehearses, exactly as core and never a plugin decides which cluster a transaction is for. So what a sandbox owner reviews is the route this plugin actually built at a live price, and the one thing that does not happen is the signature ([`docs/wiki/environments.md`](environments.md)).

**It is mainnet or nothing, in both environments.** Jupiter routes liquidity that exists on one network; there is no devnet Jupiter to point at, and pretending otherwise would be worse than saying so. A wallet selected for another network is refused before anything is asked — a rehearsal does not relax that, because the bytes being built are mainnet bytes either way — and the app's existing devnet transfer and message tests are unaffected because they are about a different thing.

## Limits, honestly

- **Direct routes only.** Some pairs have no direct route at some sizes, and the answer is "there is no route for this right now" rather than a worse transaction.
- **One minute of freshness.** The wallet round trip has to start within it.
- **The keyless allowance is 0.5 requests a second, 30 a minute.** Ample for a person deciding about a signal; not ample for polling, and nothing here polls.
- **Nothing follows the transaction to the chain.** A submitted swap is submitted, not confirmed, and the record says exactly that.
- **No screen lists a position.** What the owner gets is their own record of what this phone did.
- **A direct-mode swap request is still not executable.** Bundling this plugin changed nothing for an `ActionRequest`: core prepares no swap, so the facts stay unread and the verdict can never be `ALLOWED`. Stage 6 was not resurrected, and `InboxViewModelTest` holds that.

## Where the rules for this live

- The boundary and what a plugin may not reach: [`client-plugins.md`](client-plugins.md), enforced by [`StageBoundaryTest`](../../android/app/src/test/java/io/github/brrenat/seekervault/StageBoundaryTest.kt).
- The provider, its endpoints, its limits and its failures: [`integrations/jupiter.md`](../integrations/jupiter.md).
- What is bound before a wallet opens: [`shared-proposals.md`](shared-proposals.md#what-a-signature-is-bound-to).
- Why validation is judged before a rule, and never softened by one: [`security.md`](../security.md#verification-versus-advisory-rules).
- The owner's rules, and what they are applied to: [`policy.md`](../policy.md#what-is-evaluated).
