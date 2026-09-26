# Set global rules and connection overrides

Open **Connections → Global rules** to set phone-wide defaults. Connections remains the dashboard. To make one agent differ, open **Connections → the connection → Rules**; every allowlist section and every per-request threshold starts on **Use global**.

If there is no global value, **Use global** visibly means **Not configured** — it does not invent a check. A connection override replaces its whole section instead of adding items to a global list. The effective summary names the value and its source as **Global**, **Connection override**, or **Not configured** before you save.

Before this, pair the phone ([`pairing.md`](pairing.md)). Rules are about requests, so they are most useful once an agent is actually asking for something ([`transfers.md`](transfers.md)).

## What rules are, and what they are not

- **They are your own note to yourself.** They live on this phone, in one global file and separate connection override files, and nowhere else. The sidecar is never sent them. The agent cannot read them and cannot change them.
- **They approve nothing.** When everything matches, the review says *allowed* — which means the request matched what you wrote down, not that anything has been approved. You still approve by hand in the app and again in your wallet.
- **They refuse nothing.** A request outside the rules is shown to you with the reasons, and you may go ahead anyway. There is no setting that makes the app turn a request down on its own.
- **A threshold can only count what this app did.** Until this app has read its own history off the phone — and if that read fails — there is no day's total at all, and **Most per day** says *could not be checked* rather than passing as though nothing had been spent.
- **A threshold is not a spending cap.** The day's counters are a record of what went through *this app*. They see nothing you did in your wallet directly, nothing another app did with the same wallet, and nothing on chain. No number here stops a transaction. [What a counter cannot see](#what-a-counter-cannot-see) says exactly what that leaves out.

What *does* stop a request is different and comes first: a prepared transaction whose bytes disagree with the request, or that the phone can't read whole, has no Approve button at all ([`transfers.md`](transfers.md)). No rule can soften that, and no rule makes it stricter.

## Stage 5.1 owner flow and states

This is the shortest walkthrough of the inheritance model. It is also the state map for somebody designing or reviewing this flow:

1. From **Connections**, open **Global rules**. The editor is phone-wide and has no connection name. Save a Programs list and, if wanted, **Most across all connections per day**.
2. Open one connection, then **Rules**. Inherited sections say **Use global** and the summary names **Global**. Set Recipients to **Override**: the effective summary now shows the local recipient as **Connection override** while Programs stays **Global**.
3. Set Programs to **Override**. The local list replaces the global list whole; the screen never describes it as an addition or merge.
4. Use **Reset connection overrides**, review the draft, and save. The local file is removed and all sections return to inheritance; the global file is unchanged. A newly paired connection begins in this same inherited state without copying a policy file.
5. Put a global daily threshold and an additional connection daily threshold on the same asset and network. Request review shows two rows. Either can warn while the other matches, both can warn, and equality matches. A higher or blank connection value never disables the global row.
6. A warning leaves **Reject** available immediately. The affirmative button waits for **I have read the warnings…**, then still opens the wallet; the rules never approve or send. For a no-spend walkthrough, cancel in the wallet. A real devnet transfer is a separate, explicit choice under the [transfer guide](transfers.md#your-first-transfer-step-by-step).
7. Edit Global rules while Request details is open, or let retained Activity change on another connection. On return—or on the final tap—the review reloads. A stale affirmative action stops, the consent tick clears, and no wallet or sidecar is asked on the stale assessment.
8. Force-stop and reopen the app. Global rules, connection overrides, Activity-derived totals, and source labels read back from disk. Nothing was copied to a sidecar.

Editor states are explicit: unchanged, dirty, saving, saved, save failed, or unreadable. A failed save leaves the draft on screen for retry. Edits typed while a save is running remain dirty because only the submitted snapshot counts as saved. An unreadable document opens a recovery choice, not a blank editor. Request review likewise distinguishes loading, current, warning awaiting deliberate consent, and stale review replaced by a fresh one.

## The one thing worth reading twice

**Use global, an override with its switch off, and an override with an empty list are three different things.**

| Connection choice | Inner switch/list | What it means |
| --- | --- |
| Use global | — | Use the global section. With no global section, no check is configured. |
| Override | Off | Explicitly replace the global section with no check. |
| Override | On, with things listed | Replace the entire global list with this list. Only these values pass. |
| Override | On, with nothing listed | Replace the global list with a configured empty list. **Nothing passes.** |

There is no union option. For example, global programs with a local recipient produces both checks because they are different sections. Global programs with a local program override uses only the local program list.

## Actions

Which kinds of request every connection may make by default, or which kinds this connection may make after replacing the global section: acknowledge text, sign a message, transfer funds, SKR staking, swap, prediction order. Every kind is listed, so a publisher's swap or prediction signal can be expected like any other request (SEE-160).

Start here. It is the one rule that applies to every request, including the ones that move nothing. An agent that should only ever ask you to sign a message gets a rule you can write in two taps.

## Assets and thresholds

One list of assets, each with the two thresholds that are about it.

- **Add an asset** and pick native SOL or a token by its mint, and the chain it is on. The chain is part of the asset: the same mint on devnet and on mainnet are not the same thing to spend, so a rule written for one is never read as covering the other.
- **Only these assets may move** turns the list into a check. In a connection override, this replaces the whole global asset list; it does not add an asset to it.
- **Most per request** is the largest amount one request may move. A connection chooses **Use global** or **Override** independently for each asset and network, regardless of whether its asset allowlist is inherited or replaced. A blank local override explicitly configures no per-request check for that asset.
- **Most across all connections per day** appears only in **Global rules**. It is counted across retained Activity records from every connection for the same wallet, asset, and chain.
- **Additional most for this connection per day** appears in a connection's Rules screen. The global daily value is shown there as read-only context with a route back to **Global rules**. The local field adds a second check; it never disables or replaces the global check. Clearing or resetting it leaves the global threshold in force.

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

Before a global save, the app explains that the change affects every connection inheriting an affected section. Overridden sections keep their local values, and local daily thresholds stay separate.

**Reset connection overrides** returns every section and per-request threshold to inheritance and removes only the connection daily thresholds. Saving that draft removes the connection override file. It does not remove a global list or threshold, so deleting local settings never reads as deleting inherited rules.

Rules are stored in `filesDir/policies/global.json` and `filesDir/policies/<connection ID>.json`. Removing a connection removes its overrides and leaves global rules alone. Nothing on this phone is backed up, so rules do not travel to a new device.

The form remains responsive while saving, but only the exact draft handed to storage is marked saved. Text entered while the write is in flight stays dirty. Opening or rotating a screen never writes a policy. When a global edit is opened from a connection screen, the unsaved local draft stays underneath; returning refreshes inherited values while overridden and unsaved local values remain unchanged.

## If the rules can't be read

If this app finds either document unreadable — a file written by a later version, a rule it has no name for, or a damaged file — it does **not** open a blank form over that document. An unreadable global file is not treated as absent on a connection screen: inherited effective values are withheld, while the owner may still edit the connection without overwriting global rules.

**Start over from no rules** replaces what is stored. Nothing here can show you what you are replacing, which is exactly why it is a button you press on purpose rather than something that happens by opening the screen.

## What the summary tells you

The summary at the top is the whole policy read back in plain language, not a highlight of it:

- every list that is a check, and what is in it;
- every threshold, in the units it was typed in;
- **which checks nothing covers.** *Allowed* is never a statement about a parameter no rule was written for, so the summary names the ones the assessment leaves out;
- and the line that never changes: **allowed and under restrictions both need your hand on the wallet.** Neither is an approval, and neither stops anything.

## What a rule does when a request arrives

Open the request, and under everything the phone read for itself is **What your rules make of this**.

It says one of two things at the top — *Matches your rules*, or *Outside your rules* — and then every check, one line each, with what it read:

| The line | What it means |
| --- | --- |
| **Matched** | That check was configured, and the request is inside it. |
| **Outside the rules** | That check was configured, and the request isn't inside it. |
| **Could not be checked** | That check was configured, and the phone couldn't establish the fact it needs — the bytes don't say who receives the funds, the amount couldn't be read, today's total didn't read back. It does not pass. |
| **No rule set** | You wrote no such rule, so nothing was checked and nothing is claimed. |

Each line also says where its effective value came from: **Global**, **Connection override**, or **Not configured**. For a transfer, **Global daily** and **Connection daily** are separate lines because one may match while the other warns. Each daily line shows three amounts in the asset's units: **Confirmed**, **Not yet settled**, and **Projected with this request**. An unreadable rules message likewise names whether the Global document, the Connection override document, or both could not be read.

Under the checks, the ones nothing covered are named again in a line of their own. *Matches your rules* is never a statement about a parameter you didn't write a rule for, and the screen says which ones those are rather than leaving you to work it out.

Under all of it, every time, is the line that doesn't change: **whatever this says, it approves nothing and stops nothing.** You still approve here, and your wallet still asks you again.

Nothing on this screen is said by colour alone. Everything it means, it says.

### Going ahead anyway

When something is outside your rules, or couldn't be checked, the Approve button waits. Above it is a box to tick — *I have read the warnings above and want to go ahead anyway* — and the button says what it would be doing: **Approve despite warnings**.

**Reject never waits for anything.** Saying no is always available in one tap.

What you tick is for the reasons in front of you, the exact effective rules that produced them, both daily readings, and the transaction they are about. If the transaction is read again, you edit a global value, reset a connection override to inheritance, or either day's total moves, that is a different thing to have agreed to — the tick clears, even when the words on screen happen to look the same. A transaction prepared again clears it even when the reasons read exactly the same: the new one can carry a different priority fee, which is real money leaving your wallet that no threshold here counts.

**A connection with no rules never asks you to tick.** Every request under it is *Outside your rules* for want of any, which is not a warning about anything; a phone that asked you to tick past that on every request would be teaching you to tick without reading. Rules that are stored and can't be read do ask, because there you did write something and this app can't tell you what.

### What the rules can't do

They can't make a transfer approvable. If the prepared transaction disagrees with the request, or this phone couldn't account for all of it, **there is no Approve button at all**, and nothing you tick brings one back — that is a different check, made on the bytes, before any rule is consulted ([`transfers.md`](transfers.md)). *Matches your rules* next to it changes nothing.

They can't refuse one either. A request outside your rules is exactly as approvable as it was.

### What is checked again, and when

Nothing is remembered. Both rule documents and the day's records are read from disk when you open the request, again every time the transaction is read, again when you come back to the app, and once more the moment you answer. Returning to the app first records what became of an earlier wallet visit, so a newly confirmed, unsettled, or failed transfer is included. A transfer does its final read only after it has waited for any other wallet interaction to finish.

If they changed while you were reading — you edited them, or a transfer settled — **the answer stops** rather than going ahead on what you read. Nothing is answered, no wallet is opened, and the review on screen is replaced by the one that stands now.

## What the history keeps

When you answer, the assessment you read is kept with the record in **Activity**: the verdict, the reasons, which checks nothing covered, each effective check's Global or Connection source, each daily check's scope and result, any unreadable document scopes, and whether you went ahead anyway. Older records without the new source details remain readable.

It keeps what you were told, never what you wrote. No threshold, daily total, address, or list from your rules is copied into the history — those are stored once, where you set them. Nothing about either reaches the server.

## Worked examples

One connection, no global document, one set of connection overrides, and six requests against them. These are the scenarios the tests run (`PolicyScenarioTest`, [`docs/policy.md`](../policy.md#scenarios)), against transactions the sidecar really builds, so what is below is what the app does — not a sketch of it.

### The rules

On the connection's **Rules** screen, choose **Override** for each allowlist section:

| | |
| --- | --- |
| **Actions** | On. **Transfer funds** ticked, nothing else. |
| **Assets** | On. Native SOL on devnet. Override **Most per request** with `5` SOL and set the connection's additional daily threshold to `10` SOL. The fields read back `5000000000` and `10000000000` base units as you type them. |
| **Recipients** | On. One address: `2VDW9dFE1ZXz4zWAbaBDQFynNVdRpQ73HyfSHMzBSL6Z`. |
| **Programs** | On. One: `11111111111111111111111111111111`, the system program, which is what a plain SOL transfer calls. |

The summary at the top reads them back and ends with the line that never changes. **Save.**

### 1. A transfer inside every rule

The agent asks to send 2.5 SOL to that address. Under the transaction's own details, **What your rules make of this** says:

> **Matches your rules**
>
> Action — Matched — transfer
> Asset — Matched — SOL on devnet
> Recipient — Matched — 2VDW9dFE1ZXz4zWAbaBDQFynNVdRpQ73HyfSHMzBSL6Z
> Programs — Matched — 11111111111111111111111111111111
> Most per request — Matched — 2.5 of 5
> Most per day — Matched — 2.5 of 10 today — 0 confirmed, 0 not yet settled, 2.5 now
>
> Whatever this says, it approves nothing and stops nothing. Both verdicts need your approval here, and your wallet will ask you again.

Nothing is left uncovered, so there is no uncovered line. There is no box to tick: **Approve and send** is one tap, and it is your tap. *Matches your rules* did not approve anything, and the wallet will ask you again.

### 2. Over the per-request threshold — going ahead anyway

Same request, with **Most per request** set to `1` SOL instead:

> **Outside your rules**
>
> Most per request — Outside the rules — 2.5 of 1

The other five lines still say *Matched*, and the one that didn't says what it read and what it was compared against. The button now reads **Approve and send despite warnings** and is unavailable until you tick:

> ☐ I have read the warnings above and want to go ahead anyway

Tick it and the button becomes available. Nothing else changed: the same transaction, to the same address, for the same amount. What you agreed to is the reasons in front of you, so if the transaction is read again or you edit the rules, the tick clears and the reasons are there to read again.

### 3. Over the daily threshold

**Most per request** `3` SOL, **Most per day** `3` SOL, and this app already sent 1 SOL to somebody today from the same wallet:

> Most per day — Outside the rules — 3.5 of 3 today — 1 confirmed, 0 not yet settled, 2.5 now

The line shows the whole sum, including the part that is only projected. **The 1 SOL is what *this app* moved.** Anything you sent from your wallet app directly is not in that number and cannot be — see [what a counter cannot see](#what-a-counter-cannot-see) below.

### 4. A recipient you never wrote down — rejecting

The agent asks to send to an address that is not on your list:

> **Outside your rules**
>
> Recipient — Outside the rules — 3yS1JFVT284y8z1LC9MRoWxZjzFrdoD5axKsZiyMsfC7

The whole address is there, wrapped rather than shortened, so you can see it is not the one you wrote down rather than being shown a prefix that looks like it.

**Reject** is one tap and never waits for a tick. You do not have to explain yourself to the screen, and nothing is ticked on the way out.

Either way, the assessment you read goes into **Activity** with the record, and it is there that each reason is spelled out in a sentence — *That recipient is not on your list.* — rather than only as the line you read here.

### 5. A note that disagrees with the transaction

The agent's note says *Sending 0.25 SOL for the test run*. The instruction carries 2.5 SOL — ten times that. With **Most per request** at `1` SOL:

> Most per request — Outside the rules — 2.5 of 1

**The note changed nothing, because nothing reads it.** Every number on the review — the amount at the top, the base units, the threshold comparison, the day's total — comes from the transaction's own bytes. The note is shown where an agent's words are shown, as the agent's words. The same is true of a ticker: a note saying *USDC* beside a mint address that isn't USDC's changes nothing either, because the review names the mint and never a ticker.

### 6. Every rule matched, and no Approve button

A token transfer that calls only programs on your list, to a recipient on your list, for an asset on your list — and the transaction also carries an instruction handing your token account to a delegate:

> **Outside your rules**
>
> *This phone could not account for the whole transaction. What the rest of it matched says nothing about the part that was not read.*
>
> Action — Matched — transfer
> Asset — Matched — 3EKkiwNLWqoUbzFkPrmKbtUB4EweE6f4STzevYUmezeL on devnet
> Recipient — Matched — 2VDW9dFE1ZXz4zWAbaBDQFynNVdRpQ73HyfSHMzBSL6Z
> Programs — Matched — ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL, TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA
> Most per request — No rule set
> Most per day — No rule set
>
> Nothing was checked for: Most per request, Most per day. Nothing above says anything about those.

Every check the owner configured matched, program list included, **and there is no Approve button at all** — above the rules, the transaction's own details say the phone could not read all of it. There is no tick that brings the button back. A program's name is not permission for every instruction that program offers, and a rule that named it is not a statement about the instruction nobody read.

This pairs with example 2, and the pair is the whole point: a request the rules warn about is exactly as approvable as it was, and a request that matches every rule you wrote can be unapprovable. The rules and the verification are different things, and only one of them decides.

## What a counter cannot see

**Most per day** counts what went through this app and nothing else. It cannot see:

- anything you sent from your wallet app directly, or from any other app using the same wallet;
- anything before this app was installed, or after **Activity** was cleared;
- network and priority fees, which are not counted against an asset's threshold;
- the chain. No number here is enforced anywhere, and none of it stops a transaction.

So the number is a floor on the day's spending, never a ceiling. A day that reads *0.5 of 10 today* means this app moved half a SOL — not that half a SOL left the wallet.

It errs in one direction on purpose. A transfer handed to your wallet that this app never learned the outcome of is counted as *not yet settled* and stays in the day's projection. It may already be spent, and it may have been dropped; over-reporting what is at stake warns you, and under-reporting it misleads you.

## See also

- [`docs/testing/stage-5.md`](../testing/stage-5.md) — what the tests cover, and the device checks
- [`docs/testing/stage-5-1.md`](../testing/stage-5-1.md) — Stage 5.1 automated evidence and physical checks 79–100
- [`docs/policy.md`](../policy.md) — the model, the evaluation semantics, the counters, and the stored document
- [`docs/security.md`](../security.md#verification-versus-advisory-rules) — what stops a request, and what only warns about one
- [`transfers.md`](transfers.md) — what a transfer request is, and what your approval binds
- [`pairing.md`](pairing.md) — pairing a connection in the first place
