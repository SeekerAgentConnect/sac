# Policies

The global defaults and connection overrides the owner sets, and how a request is assessed against their effective rules. The rules live on the phone; the assessment is something the owner reads. Neither reaches an agent, and neither decides anything on its own.

Stage 5 built the per-connection system in five steps: SAW-025 defined it, SAW-026 evaluated it, SAW-027 added the editor, SAW-028 put the assessment in review, and SAW-029 exercised the whole path. Stage 5.1 extends that system rather than adding another policy engine. **SAW-043 added the global and override documents, their migration, and the pure effective-policy resolver. SAW-044 feeds those effective rules and both daily scopes into evaluation. SAW-045 exposes both persisted scopes through the owner-facing editor without collapsing inheritance. SAW-046 carries effective sources and daily scopes into review, binds warning consent to the exact rules and preparation, and preserves that context in Activity without copying a policy. SAW-047 exercises the combined path and records the owner walkthrough and its hardware status.**

## What a policy is

A note the owner writes to themselves about what every connection may ask for by default, and where one connection differs. It is not a permission system.

- **It lives only on the phone,** in `policy/storage/` (`PolicyStore`): one global file and at most one override file per connection. The sidecar is never sent the rules, and never sent the assessment. An agent cannot read them, and cannot change them.
- **Global rules are defaults; connection rules are explicit overrides.** A connection with no override document inherits. Removing a connection removes only its overrides. Removing the global document preserves every connection's overrides.
- **It approves nothing.** `ALLOWED` means the parameters matched the rules. The owner still approves by hand, in the app and again in their wallet.
- **It blocks nothing.** There is no `BLOCKED`. A request outside the rules is shown with its reasons, and the owner may go ahead anyway.

## Schema

Stage 5's `ConnectionPolicy` (`policy/Policy.kt`) remains the flat compatibility value used for version 1 migration and the original Stage 5 tests:

| Field | Type | What it restricts |
| --- | --- | --- |
| `connectionId` | The sidecar's connection UUID | Which connection the rules belong to |
| `actions` | `Allowlist<PolicyAction>?` | The kinds of action this connection may ask for: `ack`, `sign_message`, `transfer`, `swap` |
| `assets` | `Allowlist<PolicyAsset>?` | Which assets may move |
| `recipients` | `Allowlist<String>?` | Which wallets may receive funds — the owner of the funds, never a token account |
| `programs` | `Allowlist<String>?` | Which programs the transaction may call |
| `limits` | `Map<PolicyAsset, AssetLimits>` | What may move, per asset: `perOperation` and `daily` |
| `updatedAt` | `Instant` | When the owner last saved |

SAW-043 adds the two persisted Stage 5.1 models:

| Model | Fields | Meaning |
| --- | --- | --- |
| `GlobalPolicy` | The same four nullable allowlists, per-asset `AssetLimits`, and `updatedAt` | Defaults for every connection, including connections paired later |
| `ConnectionPolicyOverrides` | `connectionId`; four `RuleOverride<Allowlist<…>>` sections; per-asset `ConnectionAssetLimits`; `updatedAt` | Only what one connection changes from the global document |

`RuleOverride` keeps three states that cannot be collapsed:

| State | Meaning |
| --- | --- |
| `Inherit` | Use the global value; if no global value exists, no check is configured |
| `NoCheck` | Deliberately replace the global value with no check |
| `Replace(value)` | Replace the whole global value. For an allowlist, `Replace([])` is a configured check that allows nothing |

`ConnectionAssetLimits.perOperation` uses those same three states per asset and network. Its `daily` value is different: it is a separate connection-scoped daily check, not an override of the global daily check.

### Effective resolution

`resolveEffectivePolicy` (`policy/EffectivePolicy.kt`) is pure: the same global document and connection override document produce the same `EffectivePolicy`, without storage, a clock, a request, or a side effect. Every effective value includes `RuleSource.Global`, `RuleSource.ConnectionOverride`, or `RuleSource.NotConfigured`.

Allowlist sections resolve independently and replace in full:

| Global section | Connection section | Effective section | Source |
| --- | --- | --- | --- |
| list | `Inherit` | global list | Global |
| absent | `Inherit` | no check | Not configured |
| any | `NoCheck` | no check | Connection override |
| any | `Replace([])` | configured empty list; nothing matches | Connection override |
| any | `Replace(list)` | connection list only | Connection override |

There is no union mode. Global programs plus local recipients produce both checks; global programs plus local programs produce only the local program check. One section never changes how another resolves.

Per-request thresholds resolve the same way, independently for each `PolicyAsset`, and independently from the asset allowlist section. Replacing the asset list does not delete an inherited threshold.

Daily thresholds are the exception to override resolution. An `EffectiveAssetLimits` carries `globalDaily` and `connectionDaily` separately:

- the global value is checked against spending across connections;
- the connection value is checked against spending for this connection;
- when both exist, they remain two checks and both must pass for a match;
- inheritance, `NoCheck`, an asset-list replacement, a per-request replacement, or deleting/resetting the connection document cannot remove the global daily value.

The two scopes are validated independently. A global daily threshold of 5 and a local per-request threshold of 10 is meaningful — either warning may bind first — and is not the malformed same-document case `DailyBelowPerOperation` describes.

**`PolicyAsset` is a network and a mint,** and native SOL is a mint of none. The network is part of the asset's identity because the same mint on devnet and on mainnet are not the same thing to spend, and a rule written for one must never be read as covering the other.

**`AssetLimits` are in the asset's own base units** — lamports for SOL, the mint's own units for a token. Base units are the number the transaction carries, so a threshold written in them is compared with what the chain will see: no decimal count read from anywhere, no rounding, and no floating-point type between the rule and the amount. A limit may be as large as `18446744073709551615`, which is the largest amount a transfer can carry.

### Absent is not empty

This is the distinction the whole model turns on.

| The owner has | Stored as | The check |
| --- | --- | --- |
| configured no such list | the key is absent | doesn't run. Nothing is claimed about that parameter, and the review says it wasn't covered |
| configured a list and put nothing in it | `[]` | runs, and every value fails it |

The same holds for an amount: no `perOperation` is no threshold, and it is not a threshold of zero. A limit of zero is refused when it is saved, because a rule that permits nothing is written as an empty list.

### What a policy may not say

`policyProblems` refuses a policy before it is stored, and the editor (SAW-027) shows the same list inline:

| Problem | Why |
| --- | --- |
| `NotAConnection` | The rules must belong to a connection the sidecar assigned |
| `NotAnAddress`, `NotAMint` | An address is base58 for 32 bytes, exactly as the sidecar reads one |
| `NoNetwork` | An asset with no network names no chain, so there is nothing the rule is about |
| `ZeroLimit` | See above: that is an empty list, written confusingly |
| `DailyBelowPerOperation` | Within one document and scope, the per-operation limit could never bind, so one of the two numbers is a mistake |
| `LimitForUnlistedAsset` | The asset check fails first, so the threshold could never be reached and the owner would be reading a number that means nothing |

The editor can't type most of these. It validates an address before it is added to a list, it hangs thresholds off the asset rows so a limit for an unlisted asset can't be written at all, and it refuses to save an amount it couldn't read. `policyProblems` stays the store's own guard rather than the screen's: what the app refuses to write, it refuses to read back.

## Defaults and reset semantics

A connection with no override document inherits every global section and per-request threshold. This is also how a newly paired connection starts; pairing does not copy or create a policy document.

Deleting or resetting a connection removes only `<connection ID>.json`, returning every section and per-request threshold to inheritance. It never changes `global.json`. Deleting the global rules removes only `global.json`; explicit local replacements and explicit local no-check states remain.

With no global rules and no local configured values, the effective policy checks nothing: **`UNDER_RESTRICTIONS`, with the reason `no_policy_configured`.** This is also the result of every migrated Stage 5 document that configured nothing.

Nothing configured is not a match. A policy that asks nothing of a request has said nothing about it, and saying nothing must never read as approval.

## What is evaluated

A policy is applied to facts the phone established for itself, and to nothing else. The facts are a `RequestFacts` (`policy/RequestFacts.kt`), and every field in it has a source:

| Fact | Read from |
| --- | --- |
| the request identity used to exclude an existing attempt | the structured request reference, qualified by its connection |
| the kind of action | the structured request: `ack`, `sign_message`, `transfer`, `swap` |
| the asset, the amount, the recipient, the programs called | the prepared transaction's own bytes, decoded on the phone (SAW-020) |
| the chain | the wallet the owner connected on this phone |
| whether the whole transaction was read | how many instructions the phone accounted for |

**Nothing an agent wrote is an input.** Not the description, not the memo, not a ticker in a note, and not the sidecar's account of what it built. An agent that renames a transfer changes nothing about how it is assessed, because none of the words reach the assessment.

A fact the phone could not establish is absent, and an absent fact never passes a check. The clearest case is a token transfer's recipient: an address the tokens are sent to is not yet a wallet that receives them, and only a transaction that has the chain vouch for the destination account establishes one. Without that, the recipient is unknown, and a recipient rule comes back `recipient_unverified` rather than matching an address.

### An action that moves nothing

An acknowledgement and a message signature move no asset, reach no recipient, and call no program. They satisfy the rules about what moves by doing nothing with any of them: those checks pass, with `nothing moves` recorded as what they read. The rules that *are* about them — the action list — apply as usual.

## Evaluation semantics

One conjunction of the checks the owner configured. There is no scripting language, no expression tree, no node canvas, and no order of precedence to learn.

| Check | Configured by |
| --- | --- |
| `action` | `actions` |
| `asset` | `assets` |
| `recipient` | `recipients` |
| `program` | `programs` |
| `per_operation_limit` | `limits[asset].perOperation` |
| `daily_limit` | `limits[asset].daily` |

Under the effective model, `daily_limit` has two explicit scope codes and two retained results:

| Scope | Configured by | What it counts |
| --- | --- | --- |
| `global` | `GlobalPolicy.limits[asset].daily` | Every retained connection for this wallet, asset and network |
| `connection` | `ConnectionPolicyOverrides.limits[asset].daily` | Only this connection for the same wallet, asset and network |

The global result comes first, then the connection result. Both remain in `PolicyDecision.dailyChecks` even when the first fails, and each keeps its threshold, confirmed total, unresolved total, current amount, projected amount, status, reason and rule source. The existing `daily_limit` check row is their conjunction for Stage 5 compatibility; the request review renders the two scoped rows directly.

Each comes back as one of four things:

| Status | Meaning | Counts as |
| --- | --- | --- |
| `Passed` | Configured, and the request matched | a match |
| `Failed` | Configured, and the request is outside it | not a match, with a reason |
| `Unverified` | Configured, and the phone couldn't establish the fact it needs | not a match, with a reason |
| `NotConfigured` | The owner configured no such check | nothing; it didn't run |

And the verdict:

- **`ALLOWED`** — at least one check ran, and every check that ran passed.
- **`UNDER_RESTRICTIONS`** — everything else: a failed check, an unverified one, or no check at all.

`assess` takes every check, exactly once, in a fixed order, so a check can never be left out of an assessment by being left out of a list. The reasons come back in that same order, each with a stable code (`recipient_not_allowed`, `over_daily_limit`, …). The code is what a stored or displayed assessment carries; the screen turns it into the owner's language rather than storing that language.

### Coverage is not compliance

Two things are reported beside the verdict and never folded into it:

- **`unverified`** — checks that were configured and could not be applied, because the phone couldn't read enough of the request. An instruction it can't account for, or a recipient the bytes don't establish. These fail the conjunction; an unread instruction is a gap in the review, and a review with a gap in it is not a review.
- **`notChecked`** — checks nobody configured. `ALLOWED` is never to be read as a statement about a parameter no rule was written for, so the review says which parameters the assessment doesn't cover.

An assessment that checked nothing is never a safe one, and the two ways of having no rules to apply are told apart rather than shown as a blank:

| Reason | When |
| --- | --- |
| `no_policy_configured` | The owner has written no rules for this connection |
| `policy_unreadable` | Rules are stored and this build could not read them |

### A verdict withheld

There is one thing that outranks a match. **A transaction the phone could not account for whole is never `ALLOWED`**, however well the part it did read matched the rules. The verdict is withheld, and the reason is `request_unverified`.

This holds for any arrangement of rules, including rules written to match that exact transfer down to the base unit, and including a policy whose only rule is about the kind of action. The argument is short: no rule was written about what is in the gap, so matching everything outside the gap establishes nothing about it. A request that is under restrictions already keeps the reasons it already has; `request_unverified` exists to withhold a match, not to pile on.

Such a preparation has no Approve button either ([precedence](#precedence)), so this is the assessment agreeing with the refusal rather than the thing that causes it.

### Precedence

There is one rule, and it runs in one direction.

1. **Input validation decides what is executable.** A prepared transaction whose bytes disagree with the request, or that the phone can't read whole, has no Approve button at all ([`security.md`](security.md#inspecting-a-transfer)). This is judged before any policy is consulted.
2. **The policy decides what the owner is told.** It is applied to a request that already passed step 1, and it can only add reasons to read.

A policy never softens step 1: no rule can make a malformed or mismatched preparation approvable, and a malformed preparation is never relabelled as a threshold warning. Nor does a policy harden it: `UNDER_RESTRICTIONS` leaves the request exactly as executable as it was.

### Advisory thresholds

Every rule in the MVP is advisory, thresholds included.

- A warning can be overridden by the owner, deliberately, in the app: the review gives it [its own step](#going-ahead-anyway).
- A counter is a record of what went through **this app**, not a spending cap. It sees nothing the owner did in their wallet directly, and nothing any other app did with the same wallet.
- Nothing here is enforced on chain. The wallet and the network do not know these rules exist.

### A day nobody has read

A counter is derived from the owner's own Activity records, which are read off the disk asynchronously and may fail to be read at all. **An empty list of records and a history nobody has read are not the same thing**, and the difference decides whether a daily threshold means anything: measured against an unread day, every request would read as though nothing had been spent, and every daily threshold would pass.

So the evaluator is handed records that can be *absent*, not merely empty. Until the history has been read — and again after a read that fails — there is no day's total, the daily check is `daily_total_unverified`, and the request is UNDER_RESTRICTIONS. A partial read is not treated as success either: Activity keeps the readable rows for the owner but reports how many files did not decode, and every required daily total is unverified because the missing row's scope cannot be guessed. A history that was read and holds nothing is a day with nothing in it, and passes on its own terms.

This is the same rule as everywhere else here: a fact the phone couldn't establish is null, and null never passes a check.

### Re-evaluation

There is no stored verdict. A review reloads Activity from disk, then `PolicyEvaluator` (`policy/PolicyEvaluation.kt`) re-reads the global document and the connection override document and resolves and evaluates them against that complete history snapshot. An unreadable global document never falls back to local rules, and an unreadable connection document never falls back to inheritance; the review names Global, Connection override, or both as unreadable. Assessments are serialized, and a preparation that changes during a disk read causes another complete read instead of letting an older result replace the newer one.

That matters at three moments: the policy may have been edited since the review opened, the day's counters may have moved, and a transfer is re-prepared as its blockhash expires. Each new preparation is new bytes and is read again from scratch.

The review reads again when the request is opened, whenever a preparation has been read, when the app comes back to the front, and once more at the moment the owner answers ([the review](#read-again-before-the-answer-not-after)). Foreground work first resolves any wallet interaction that ended while the app was away, so a newly confirmed, unresolved, or failed record is part of the assessment that follows. A transfer's last read happens after it obtains the one wallet-interaction lock — where another wallet visit may have made it wait — and before either the sidecar or wallet is asked.

## The editor

`PolicyEditorScreen` (`policy/PolicyEditorScreen.kt`), reached from a connection's details. Material 3 switches, checkboxes, radio buttons, chips, text fields, lists, Save and Cancel use the approved v4 theme; the presentation adds no policy capability. There is no expression builder and no node canvas, because the model behind it is one conjunction of allowlists and thresholds, and pretending otherwise would be showing the owner a language they don't have.

`PolicyDraft` (`policy/PolicyDraft.kt`) is the form: text as it is being typed, including text that isn't a number yet. `review` is the one place a draft becomes a `ConnectionPolicy`, and it produces one only when the store would accept it.

### Absent is not empty, on screen

Each list has a switch of its own, and the section says in words which of the three states it is in — not checked, checked with a list, or checked with nothing in it. This is the distinction the whole model turns on, so it is never left to the shape of a blank control.

### Thresholds hang off the asset

There is one list of assets, and each asset row carries its own per-operation and daily fields. The "only these assets may move" switch decides whether that same list also restricts. Written this way, `LimitForUnlistedAsset` is structurally impossible rather than an error to report.

### Units

**SOL is typed in SOL**, whose nine decimal places this app knows for certain. **A token is typed in that mint's own base units**, because the phone cannot establish a mint's decimal count without a transaction that carries it and must not invent one: a guess three places out is a threshold out by a factor of a thousand. Erring this way errs strict — an owner who types `10` meaning ten tokens gets a threshold far below what they meant, which produces a warning they didn't expect rather than a payment they didn't want.

Every field shows the exact base-unit number it will store, under the field, as it is typed. The conversion shifts digits and never multiplies, so no floating-point type comes between what was typed and what the chain will see.

### Saving

A draft that configures nothing **removes** the connection's rules rather than storing a document that says nothing: the two are assessed identically, so there is nothing to keep. A connection's rules are also removed when the connection is.

### Rules that can't be read

The editor does not open a blank form over a `StoredPolicy.Unreadable`. It says what was found and offers **Start over from no rules**, which the owner presses on purpose — an empty form saved on top would delete rules they set and never saw.

## The review

`PolicyReview` (`inbox/PolicyReview.kt`), on Request details, under everything the phone established for itself and above the button that answers.

The order on that screen is the order of trust, and the assessment is last on purpose. The facts above it come from the transaction's own bytes. The assessment is the owner's own note to themselves about what they expected this agent to ask for, and it is the weakest thing on the screen: it cannot make anything executable, and it cannot stop anything.

Every effective check is named with its source — Global, Connection override, or Not configured — what it read, and what became of it: matched, outside the rules, could not be checked, or no rule set. Global daily and Connection daily are separate rows with their own source and result, and each shows the confirmed amount, unresolved amount, and total projected with this request. The checks nothing covered are named too, so `ALLOWED` is never read as a statement about a parameter nobody wrote a rule for. Under every verdict is the line that never changes: both verdicts still need the owner's hand on the wallet.

Nothing is said by colour alone. A reader who sees no colour, or who hears the screen rather than seeing it, is told the same things in the same words.

### Going ahead anyway

A warning the owner can tap straight past is a warning that teaches them to tap past warnings. So an affirmative answer to a request the assessment warns about takes a deliberate step: a checkbox saying they have read the warnings and want to go ahead anyway, next to the button that does it, and the button says what it would be doing. Rejecting never asks for anything — saying no is the safe answer.

**What they agree to is the assessment, not the request.** The tick is bound to a `Consent`: the exact `PolicyDecision`, including both daily totals and results; the effective rules applicable to this request; the `RequestFacts`; and the exact prepared transaction, including its bytes, version and content hash. These effective rules live only in the open review and are neither stored nor sent. An edit that produces the same displayed result, a local reset to inheritance, a moved counter, or a transaction prepared again is still a different thing to agree to: the tick goes, and the reasons are there to be read again.

**Both halves, because the two don't always change together.** A rule the owner edits can leave this request's every check exactly as it was, and a transaction prepared again can carry another blockhash, another version, or another priority fee while what the rules make of it is word for word the same — and a raised priority fee is real value leaving the wallet that no threshold counts ([known limits](#known-limits)). Comparing decisions alone would carry a tick given for one preparation over to another. What the owner said yes to is *this assessment of this preparation*.

The moment an assessment was made is deliberately not part of consent. The same reasons about the same bytes, read again a second later, are the same reasons.

**Having no rules at all is not a warning.** Every request on a phone whose owner has written no rules is `UNDER_RESTRICTIONS` for want of any, and asking them to tick past that on every request would make the tick a ritual. `PolicyDecision.warns` is `UNDER_RESTRICTIONS` for any other reason — rules this build can't read included, because there the owner did write something and this build can't say what.

### Read again before the answer, not after

The screen's assessment is a snapshot of a reading. The answer does not act on it: `InboxViewModel` reloads Activity and both rule documents at the moment the owner answers and compares the decision, applicable effective rules, facts, and preparation with what they were shown. A difference stops before an answer or wallet interaction, clears warning consent, and replaces the review on screen. For a transfer this final comparison runs after waiting for the wallet lock, which closes the interval in which another request's wallet visit can change Activity. That is what makes a stale review unusable rather than merely unlikely.

The advisory check comes second, always. A preparation that failed this phone's own inspection was refused before any of it ran ([`security.md`](security.md#verification-versus-advisory-rules)).

### The stored snapshot

The assessment the owner read is kept with the record of what they did (`activity/ActivityRecord.kt`, `ReviewedPolicy`): the verdict's code, the reason codes, the codes of the checks nothing covered, the source code for each effective check, each daily check's scope/source/status/reason codes, the scope codes of unreadable documents, when it was made, and whether they went ahead with a warning in front of them. The fields are additive: a Stage 5 Activity record that has none of this source metadata remains readable and keeps the assessment it already held.

**It is codes, and never rules or counter values.** No threshold, amount total, address, and no list is written into the history: the rules are stored once, in the one place they belong, and a snapshot that copied them would be a second copy to keep in step and a second thing to leak. Codes also mean what a code means can be said better later without the record having to be rewritten, and a code a later version invented is left out of the reading rather than shown as itself.

Nothing reads it back to decide anything. It is written when the owner answers, carried forward unchanged when the record is written again — a status checked ten times later does not know what the review said, and must not take it away — and shown on Activity details.

None of it reaches the sidecar. `StageBoundaryTest` holds the files that speak to one to having never heard of a policy.

## Counters

What this app has moved today, counted from the owner's own Activity records (`activity/`, SAW-023) — the record of what this phone did, which outlives the answer the sidecar was owed.

### What a counter is counted for

There are two scopes, both still separated by wallet, asset and the chain the asset is on:

- **A connection scope** is one `SpendScope`: a connection, wallet and `PolicyAsset`. Two connections using one wallet count apart for the connection daily check.
- **A global scope** is one `GlobalSpendScope`: a wallet and `PolicyAsset`, across every retained Activity record. It includes connections that were removed or paired again under a new ID; removing a connection does not erase the owner's record of what went through it.

Within either scope:

- **Two wallets spending one mint count apart.** The money comes out of different places.
- **One mint on two chains is two things to spend**, so devnet play money is never counted against a mainnet threshold.

For example, with a global limit of 10 SOL and connection A already confirmed at 6, a 5 SOL request on connection B projects 11 globally even when B's own 8 SOL connection limit projects only 5. The global check warns and the connection check passes. With no earlier spending, a 4 SOL request under global 10 and connection 3 does the reverse. A local threshold above the global one never suppresses the global result.

### Confirmed, and not yet settled

Two numbers, kept apart, and never added together and called spending:

| Number | What it holds |
| --- | --- |
| `confirmed` | The chain confirmed the transaction. The money moved. |
| `unresolved` | The wallet was handed the transaction and nobody has established what came of it: in flight, landed, or dropped. |

The threshold warning is made from `projected` — the two of them plus the request in hand — and the reason it shows says which part is which: `11 of 10 today — 4 confirmed, 6 not yet settled, 1 now`. Including the unresolved part is deliberate: it may already be spent, and telling the owner they have room they may not have is the one answer that costs them money. Naming it separately is equally deliberate: it may not be spent, and calling it spending would be a claim the app can't support.

`projected` saturates at the largest base-unit amount there is rather than wrapping round to a small one. A total that saturated is fit to show and not to compare with, so a threshold is checked by how much room is left instead of by the sum.

### What counts, and what doesn't

| Outcome | Counts as |
| --- | --- |
| confirmed | `confirmed` |
| sent, unknown | `unresolved` |
| the owner answered and the wallet's reply hasn't come back | `unresolved` |
| the answer never reached the server, or the request had moved on, **and the wallet signed** | `unresolved` |
| rejected, declined in the wallet, the wallet couldn't sign | nothing. A rejection is not a transfer |
| the chain ran the transaction and it failed | nothing. The fee was paid; the transfer didn't happen |
| an acknowledgement, a message signature | nothing. They move no asset |

### Counted once

A movement is counted once, by **the transaction's signature when there is one, and otherwise by the connection-qualified request it belongs to**. So a request prepared three times, answered, re-sent after a failed delivery, and status-checked ten times is one payment. Two unsigned requests with the same request ID under different connections remain distinct. In a global scope, two records under different connections that carry one transaction signature on the same network are one payment. Where duplicate records disagree, a confirmed or failed chain result settles unresolved exposure.

When the request currently being reviewed already has an Activity attempt, that request — and any duplicate carrying its signature — is removed from the historical total before the current amount is projected. The amount therefore appears exactly once.

### The day a counter counts

**A day is a local day: midnight to midnight where the phone is**, because that is the day the owner means when they set a daily threshold.

The day a movement falls in is worked out when the counters are read, from the instant the owner answered. Two consequences, both intended:

- **A record is never rewritten to move it between days,** and a status checked the next morning doesn't walk yesterday's payment into today. The moment the owner answered is the moment that counts, and it doesn't move.
- **A phone carried into another time zone reads its own history in the zone it is in now.** A payment can therefore move into the previous or the next day. The counters are a question about where the phone is, and answering it in a zone the owner left would be stranger than the shift.

### Known limits

A counter is a floor on the day's spending, never a ceiling. It does not see:

- anything the owner did in their wallet app directly, or in any other app using the same wallet;
- anything that happened before this app was installed, or after its records were cleared;
- fees — network fees and priority fees are not counted against an asset's threshold, only the amount the transfer moves;
- the chain. Nothing here is enforced anywhere but on this screen, and no number here stops a transaction.

And one movement can be counted as exposure that never happened: an `unresolved` amount whose transaction was dropped stays in the day's projection until something settles it. That direction is chosen on purpose — over-reporting exposure warns, under-reporting it misleads.

The word **global** changes which retained records are grouped; it does not make the history complete. Removed and re-paired connections remain included only because Activity outlives them. Clearing Activity removes that evidence, uninstalling or changing phones leaves no history to carry over, and no wallet-only, other-app, fee, or external-chain movement is reconstructed.

## Storage

`PolicyStore` keeps one global document at `<filesDir>/policies/global.json` and at most one override document per connection at `<filesDir>/policies/<connection ID>.json`. Every document is written whole with Android's `AtomicFile`. Nothing here is encrypted, because a policy holds no credential and no key: public addresses and the owner's own thresholds. Nothing on the phone is backed up.

A version 2 global document has ordinary nullable rules because there is nothing above it to inherit from:

```json
{
  "version": 2,
  "scope": "global",
  "updatedAt": "2026-09-13T10:00:00Z",
  "actions": ["transfer"],
  "programs": ["11111111111111111111111111111111"],
  "limits": [
    {
      "asset": { "network": "NETWORK_MAINNET" },
      "perOperation": "1000000",
      "daily": "5000000"
    }
  ]
}
```

An absent global list or threshold is no check. An empty global array is a configured check that allows nothing. Amounts remain decimal strings because an unsigned 64-bit base-unit amount does not survive a JSON number.

A version 2 connection document stores only its overrides:

```json
{
  "version": 2,
  "scope": "connection",
  "connectionId": "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11",
  "updatedAt": "2026-09-13T11:00:00Z",
  "actions": { "mode": "no_check" },
  "recipients": { "mode": "replace", "values": [] },
  "limits": [
    {
      "asset": { "network": "NETWORK_MAINNET" },
      "perOperation": { "mode": "replace", "amount": "2000000" },
      "daily": "6000000"
    }
  ]
}
```

An absent connection section or `perOperation` inherits. `no_check` is an explicit override to configure no check. `replace` supplies the whole local value, and an empty `values` array still allows nothing. A connection `daily` is a separate local daily check; it has no mode that can disable the global one. `"programs"` and `"assets"` are absent above, so both inherit.

### Versions and migration

`version` is the document format. Stage 5 wrote version 1 connection documents; SAW-043 writes version 2 global and connection documents.

- **A newer version is refused,** as `NewerVersion`. Reading a document this build only half understands would show the owner fewer rules than they set, and saving it back would delete the rest.
- **A version 1 connection document is migrated atomically and idempotently.** Every configured list becomes `Replace`, including an empty list. An absent list becomes `Inherit`. Every existing per-operation threshold becomes a per-asset `Replace`, and every existing daily threshold stays connection-scoped. `updatedAt` and all configured values are preserved.
- **Migration never creates `global.json`.** With no global document, the migrated effective values are the values Stage 5 evaluated, so upgrading alone changes no assessment.
- **An interrupted migration keeps the previous complete document.** A failed atomic replacement is retried the next time the rules are read; opening over a leftover partial `.new` file recovers the last complete base file. Re-reading a migrated file does not rewrite it.
- **A rule this build has no name for makes the whole document unreadable,** as `UnknownRule` — an action kind or a network it doesn't know. Reading a shorter list would be safe on its own, since these are allowlists and dropping an entry only makes them stricter. It is refused because of what happens next: the owner opens the editor, sees a policy missing a rule they wrote, saves it, and the rule is gone.
- **Anything else that doesn't read back is `Damaged`,** including a document with the wrong scope, a file that names another connection, a malformed timestamp, a duplicate asset entry, an amount that isn't a whole number of base units, and a policy this app would have refused to write.

Missing and unreadable remain different for both scopes. `StoredGlobalPolicy` and `StoredConnectionOverrides` each say `None`, `Policy`, or `Unreadable`; an unreadable global file does not become no global rules, and an unreadable connection file does not become inheritance. Reading either one never overwrites it. Stage 5's `StoredPolicy` remains only as the compatibility view used by the existing editor and legacy unit fixtures; `PolicyEvaluator` reads both stored scopes and evaluates `EffectivePolicy`.

## The protocol's `PolicyEvaluation`

`seekervault/request/v1/request.proto` carries a `PolicyEvaluation` message with an assessment, reason strings, and the prepared version it was computed from. It is the shape of an assessment, and it stays on the phone: no RPC sends one, and the sidecar has no field to put one in ([`protocol.md`](protocol.md#lifecycle)). The reason strings are the codes above.

## Test fixtures

`android/app/src/test/java/.../policy/PolicyFixtures.kt` holds the table every assessment is held to. Each case is a policy, the facts of one request, the day's counters, and the verdict with every reason code it must carry, and `PolicyFixturesTest` runs all of them. A rule that only exists in a test's prose can be argued with; a rule in that table either holds for every case or fails one. The table also asserts that every reason code a request can produce appears in at least one case, so a new reason has to be given a case before it can be returned.

The facts themselves are checked against real transactions rather than invented ones: `RequestFactsTest` reads `fixtures/transactions/cases.json` — the transfers the sidecar actually builds ([transaction fixtures](testing/transaction-fixtures.md)) — inspects them the way the review screen does, and assesses what comes out.

## Scenarios

`PolicyScenarioTest` is the whole path in one place (SAW-029): bytes the sidecar really built, inspected the way the review screen inspects them, against rules read off a real store on disk, with the day counted from the owner's own Activity records. Each scenario asserts four things, and a scenario that stops doing what it is named for fails rather than changing quietly:

| | |
| --- | --- |
| The classification | ALLOWED or UNDER_RESTRICTIONS |
| The exact reason codes | In order. A verdict with the wrong reasons is a wrong verdict, and the codes are what a stored assessment carries |
| The checks it doesn't cover | So a match is never read as a statement about a parameter nobody wrote a rule for |
| What no rule decides | Whether input validation leaves the transfer approvable at all, and whether the review asks the owner to go past a warning on purpose |

The scenarios are a transfer inside every rule; one request over the per-request threshold; today's total plus this request over the daily one; a recipient the owner never wrote down; an instruction nobody read beside a transfer that matches; and three where the agent's own words are the only thing wrong — a note saying a tenth of what the instruction carries, a familiar ticker on an unrelated mint, and an allowed program carrying an operation that isn't the transfer.

The last two of those are the pair worth reading together. A transfer the rules warn about is exactly as approvable as it was; a transfer that matches every rule the owner wrote — the program list included — has no Approve button at all, because a program's name is not permission for every instruction it offers. The suite asserts that both exist, so the demonstration can't be lost.

Each scenario is also run through a second connection that has written no rules, and again off a new store over the same directory, which is what the next launch has. And `PolicyWordingTest` holds every policy-facing string to what a verdict may claim: nothing here is called safe, secure, automatic, blocked, or guaranteed.

`inbox/Stage51PolicyScenarioTest` is the Stage 5.1 acceptance layer (SAW-047). It starts from the existing sidecar-built SOL fixture, runs the phone's transaction inspection, reads real versioned global and connection files and real Activity files, and carries the resulting `RequestAssessment` into Request details. It asserts the verdict, exact reason order, source of every effective rule, both daily scopes and totals, input-verification precedence, and whether Approve, the deliberate warning step, and Reject are available.

Its scenarios combine global programs with a connection recipient, prove a connection program list replaces rather than unions, reset the connection and evaluate a newly paired connection through inheritance, and exercise global-only, connection-only, dual-warning, equality, higher-local, and absent-local daily cases. Separate records cover two connections on one wallet, another wallet, another network, and a removed connection whose Activity remains. A Stage 5 version 1 file is migrated and read again after restart. Damaged global, connection, and Activity files remain unreadable rather than empty. Finally, same-looking global edits and cross-connection spending produce different consent, while the `InboxViewModelTest` lifecycle cases prove those differences stop an affirmative action before the sidecar or wallet.

## Where the code is

| File | What it holds |
| --- | --- |
| `policy/Policy.kt` | The flat Stage 5 policy; `GlobalPolicy`; `ConnectionPolicyOverrides`; `RuleOverride`; assets, limits, validation, and migration conversions |
| `policy/EffectivePolicy.kt` | `EffectivePolicy`, source metadata, separate daily scopes, and the pure resolver |
| `policy/PolicyDecision.kt` | The assessment, effective-rule sources, scoped daily results, checks, reasons, `assess`, and `noPolicy` |
| `policy/RequestFacts.kt` | `RequestFacts`, including the request identity needed to exclude its existing attempt, and `policyFacts` |
| `policy/PolicyEvaluation.kt` | Flat-policy compatibility evaluation, effective evaluation, both daily checks, and `PolicyEvaluator` |
| `policy/DailySpending.kt` | Connection and global scopes, spend identity/status, overflow-safe totals, deduplication, and current-request exclusion |
| `policy/PolicyDraft.kt` | `PolicyDraft`, `AssetDraft`, `readAmount`, and `review` |
| `policy/PolicyEditorViewModel.kt` | `PolicyUiState`, and load, edit, save, remove, start over |
| `policy/PolicyEditorScreen.kt` | The editor itself |
| `policy/PolicyText.kt` | `PolicyTags`, the owner's words for each rule and each verdict, reason and check, and the plain-language summary |
| `policy/storage/PolicyStore.kt` | Global and connection documents, version 1 migration, atomic writes, and the three stored states for each scope |
| `inbox/PolicyReview.kt` | The assessment on Request details, and the step before going ahead anyway |
| `inbox/InboxViewModel.kt` | `RequestAssessment`, when an assessment is made, and the re-read before an answer |
| `activity/ActivityRecord.kt` | `ReviewedPolicy`, the snapshot kept with the record |

Tests: `policy/PolicyTest`, `policy/EffectivePolicyTest`, `policy/EffectivePolicyEvaluationTest`, `policy/PolicyDecisionTest`, `policy/RequestFactsTest`, `policy/PolicyEvaluationTest`, `policy/DailySpendingTest`, `policy/PolicyEvaluatorTest`, `policy/PolicyFixturesTest`, `policy/PolicyScenarioTest`, `policy/PolicyWordingTest`, `policy/PolicyDraftTest`, `policy/PolicyEditorViewModelTest`, `policy/PolicyEditorScreenTest`, `policy/storage/PolicyStoreTest`, `policy/storage/PolicyStoreV2Test`, and `PolicyActivityTest` — the editor in the real activity, with the app's own storage. The review has its own: `inbox/PolicyReviewScreenTest`, `inbox/TransferReviewScreenTest`, `inbox/InboxViewModelTest`, `inbox/Stage51PolicyScenarioTest`, `activity/ActivityLogTest`, `activity/storage/ActivityStoreTest`, and `activity/ActivityDetailsScreenTest`.

`StageBoundaryTest` keeps the package unable to act — the editor included. Everything it may reach into is a read: the connection ID rule, the protocol's requests and networks, what the phone read out of a transaction's bytes, the owner's own activity records, the address rule, and the app's own strings, back button, and date format. It may reach nothing that opens a wallet, a connection, or a socket.
