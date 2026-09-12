# Set rules for a connection

Every connection starts with no rules. Writing some tells the phone what you expect that agent to ask for, so that a request outside it is pointed out to you before you approve it. Open **Connections → the connection → Rules**.

Before this, pair the phone ([`pairing.md`](pairing.md)). Rules are about requests, so they are most useful once an agent is actually asking for something ([`transfers.md`](transfers.md)).

## What rules are, and what they are not

- **They are your own note to yourself.** They live on this phone, in this connection's own file, and nowhere else. The sidecar is never sent them. The agent cannot read them and cannot change them.
- **They approve nothing.** When everything matches, the review says *allowed* — which means the request matched what you wrote down, not that anything has been approved. You still approve by hand in the app and again in your wallet.
- **They refuse nothing.** A request outside the rules is shown to you with the reasons, and you may go ahead anyway. There is no setting that makes the app turn a request down on its own.
- **A threshold is not a spending cap.** The day's counters are a record of what went through *this app*. They see nothing you did in your wallet directly, nothing another app did with the same wallet, and nothing on chain. No number here stops a transaction.

What *does* stop a request is different and comes first: a prepared transaction whose bytes disagree with the request, or that the phone can't read whole, has no Approve button at all ([`transfers.md`](transfers.md)). No rule can soften that, and no rule makes it stricter.

## The one thing worth reading twice

**A switch that is off is not an empty list.**

| The switch | What it means |
| --- | --- |
| Off | That parameter is not checked at all. The agent may ask for anything, and the review says the parameter wasn't covered. |
| On, with things listed | Only what is listed passes. Anything else is outside the rules. |
| On, with nothing listed | **Nothing passes.** That is a real rule, not a blank form. |

The editor says which of the three you are in, under every switch, in words. The summary at the top says it again.

## Actions

Which kinds of request this agent may make: acknowledge text, sign a message, transfer funds, swap.

Start here. It is the one rule that applies to every request, including the ones that move nothing. An agent that should only ever ask you to sign a message gets a rule you can write in two taps.

## Assets and thresholds

One list of assets, each with the two thresholds that are about it.

- **Add an asset** and pick native SOL or a token by its mint, and the chain it is on. The chain is part of the asset: the same mint on devnet and on mainnet are not the same thing to spend, so a rule written for one is never read as covering the other.
- **Only these assets may move** turns that same list into a check. Leave it off and the list is just a place to hang thresholds; turn it on and an asset that isn't listed is outside the rules.
- **Most per request** is the largest amount one request may move.
- **Most per day** is the largest amount this app may move in a local day. It is counted from the app's own records ([`docs/policy.md`](../policy.md#counters)).

### Units

**SOL is typed in SOL.** 1.5 means one and a half SOL, and the field says, as you type, that it will be stored as `1500000000` base units. That number is the one the rule is actually compared against, so you can always see it.

**A token is typed in the mint's own base units.** This app cannot establish how many decimal places a mint has without a transaction that carries it, and it will not guess: a guess three places out is a threshold out by a factor of a thousand. If you mean 10 USDC and USDC has six decimals, write `10000000`. If you write `10` by mistake, the threshold is far stricter than you meant, which shows up as a warning you didn't expect rather than as a payment you didn't want.

### What the editor refuses

Each of these is said under the field it is about, in words, as you type:

| Refused | Why |
| --- | --- |
| `1,5`, `1 000`, `1e9`, `-1`, `one` | A threshold is a plain decimal number. Nothing here is read differently in another locale. |
| `0` | A threshold of zero isn't a rule. To allow nothing, leave the asset off the list. |
| More decimal places than the asset has | The amount couldn't be moved exactly. |
| More than `18446744073709551615` base units | Larger than the largest amount a transfer can carry. |
| A daily threshold below the per-request one | The per-request one could never be reached, so one of the two numbers is a mistake. |

**A blank threshold is no threshold**, and it is not a threshold of zero. The field says so while it is empty.

## Recipients

Which wallets may receive funds.

Write **the wallet that owns the funds, never a token account.** For a token transfer the phone works the owner out of the transaction itself, from an instruction that makes the chain vouch for the destination account; an address the tokens are sent to is not yet a wallet that receives them. If the transaction doesn't establish an owner, a recipient rule comes back *couldn't be checked* rather than matching an address.

Addresses are checked as you add them: base58 for 32 bytes, exactly as the sidecar reads one. An address that is already listed isn't added twice. The whole address is shown in the list, wrapped rather than shortened — half an address is worse than none, because it looks like the one you meant.

## Programs

Which programs the transaction may call.

**Every program the transaction calls is checked, including the compute budget program.** A rule naming the programs a transaction may call is a list of what it may call, not a list with exceptions. A plain SOL transfer calls the system program and the compute budget program; a token transfer that has the chain vouch for the destination also calls the associated-token and token programs.

## Saving

**Save** writes the rules to this phone. **Cancel** leaves them as they were, and asks first if you changed anything.

**Saving with every switch off and no threshold removes this connection's rules.** A stored policy that configures nothing and no policy at all come to exactly the same thing, so nothing is kept. The summary says so before you save.

Rules are stored per connection, in `filesDir/policies/<connection ID>.json`. Removing a connection removes its rules with it. Nothing on this phone is backed up, so rules do not travel to a new device.

## If the rules can't be read

If this app finds rules it can't read — a file written by a later version, a rule it has no name for, or a damaged file — it does **not** open a blank form over them. It says what it found, and every request from that connection is shown as under restrictions until you settle it.

**Start over from no rules** replaces what is stored. Nothing here can show you what you are replacing, which is exactly why it is a button you press on purpose rather than something that happens by opening the screen.

## What the summary tells you

The summary at the top is the whole policy read back in plain language, not a highlight of it:

- every list that is a check, and what is in it;
- every threshold, in the units it was typed in;
- **which checks nothing covers.** *Allowed* is never a statement about a parameter no rule was written for, so the summary names the ones the assessment leaves out;
- and the line that never changes: **allowed and under restrictions both need your hand on the wallet.** Neither is an approval, and neither stops anything.

## What a rule does when a request arrives

Right now: nothing visible. The phone already assesses requests against these rules and counts the day's spending, but the request-review screen doesn't show the result yet — that is the next task (SAW-028). Until it lands, what you write here is stored, checked by the app's own tests, and not yet on screen.

## See also

- [`docs/testing/stage-5.md`](../testing/stage-5.md) — what the tests cover, and the device checks
- [`docs/policy.md`](../policy.md) — the model, the evaluation semantics, the counters, and the stored document
- [`transfers.md`](transfers.md) — what a transfer request is, and what your approval binds
- [`pairing.md`](pairing.md) — pairing a connection in the first place
