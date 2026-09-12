# Policies

The rules the owner sets for one connection, and how a request is assessed against them. The rules live on the phone; the assessment is something the owner reads. Neither reaches an agent, and neither decides anything on its own.

Stage 5 builds this in four steps. **SAW-025 defined the model and the semantics, SAW-026 made the phone apply them — a real request is assessed against a stored policy, and the day's spending is counted from the app's own records — and SAW-027 gave the owner the editor to write one in** ([`guides/policies.md`](guides/policies.md)). The request-review screen that shows the assessment is SAW-028. Until it lands a verdict is computed and never displayed, so the review screen still says "Not evaluated" as it has since Stage 4.

## What a policy is

A note the owner writes to themselves about one connection: what this agent is expected to ask for. It is not a permission system.

- **It lives only on the phone,** in `policy/storage/` (`PolicyStore`), one file per connection. The sidecar is never sent the rules, and never sent the assessment. An agent cannot read them, and cannot change them.
- **It is per connection.** One connection's rules are in one file and nowhere else, so they can never be read for another connection, and removing one connection's rules leaves every other connection's as they were.
- **It approves nothing.** `ALLOWED` means the parameters matched the rules. The owner still approves by hand, in the app and again in their wallet.
- **It blocks nothing.** There is no `BLOCKED`. A request outside the rules is shown with its reasons, and the owner may go ahead anyway.

## Schema

`ConnectionPolicy` (`policy/Policy.kt`):

| Field | Type | What it restricts |
| --- | --- | --- |
| `connectionId` | The sidecar's connection UUID | Which connection the rules belong to |
| `actions` | `Allowlist<PolicyAction>?` | The kinds of action this connection may ask for: `ack`, `sign_message`, `transfer`, `swap` |
| `assets` | `Allowlist<PolicyAsset>?` | Which assets may move |
| `recipients` | `Allowlist<String>?` | Which wallets may receive funds — the owner of the funds, never a token account |
| `programs` | `Allowlist<String>?` | Which programs the transaction may call |
| `limits` | `Map<PolicyAsset, AssetLimits>` | What may move, per asset: `perOperation` and `daily` |
| `updatedAt` | `Instant` | When the owner last saved |

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
| `DailyBelowPerOperation` | The per-operation limit could never bind, so one of the two numbers is a mistake |
| `LimitForUnlistedAsset` | The asset check fails first, so the threshold could never be reached and the owner would be reading a number that means nothing |

The editor can't type most of these. It validates an address before it is added to a list, it hangs thresholds off the asset rows so a limit for an unlisted asset can't be written at all, and it refuses to save an amount it couldn't read. `policyProblems` stays the store's own guard rather than the screen's: what the app refuses to write, it refuses to read back.

## Defaults

A connection with no policy, and a policy with nothing configured, come to the same thing: **`UNDER_RESTRICTIONS`, with the reason `no_policy_configured`.**

Nothing configured is not a match. A policy that asks nothing of a request has said nothing about it, and saying nothing must never read as approval. This is the default state of every connection, and it will stay the default until the owner writes a rule.

## What is evaluated

A policy is applied to facts the phone established for itself, and to nothing else. The facts are a `RequestFacts` (`policy/RequestFacts.kt`), and every field in it has a source:

| Fact | Read from |
| --- | --- |
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

So the evaluator is handed records that can be *absent*, not merely empty. Until the history has been read — and again after a read that fails — there is no day's total, the daily check is `daily_total_unverified`, and the request is UNDER_RESTRICTIONS. A history that was read and holds nothing is a day with nothing in it, and passes on its own terms.

This is the same rule as everywhere else here: a fact the phone couldn't establish is null, and null never passes a check.

### Re-evaluation

There is no stored verdict. `PolicyEvaluator` (`policy/PolicyEvaluation.kt`) re-reads the connection's rules from disk and the app's own records on every call, so asking again immediately before the owner proceeds is the whole of re-evaluating — and a verdict read a minute ago is never the one acted on, because there is nothing kept to act on.

That matters at three moments: the policy may have been edited since the review opened, the day's counters may have moved, and a transfer is re-prepared as its blockhash expires. Each new preparation is new bytes and is read again from scratch.

The review reads again when the request is opened, whenever a preparation has been read, when the app comes back to the front, and once more at the moment the owner answers ([the review](#read-again-before-the-answer-not-after)).

## The editor

`PolicyEditorScreen` (`policy/PolicyEditorScreen.kt`), reached from a connection's details. Stock Material 3 — switches, checkboxes, radio buttons, chips, text fields, lists, Save and Cancel — and nothing else. There is no expression builder and no node canvas, because the model behind it is one conjunction of allowlists and thresholds, and pretending otherwise would be showing the owner a language they don't have.

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

Every check is named with what it read and what became of it — matched, outside the rules, could not be checked, or no rule set — and the checks nothing covered are named too, so `ALLOWED` is never read as a statement about a parameter nobody wrote a rule for. Under every verdict is the line that never changes: both verdicts still need the owner's hand on the wallet.

Nothing is said by colour alone. A reader who sees no colour, or who hears the screen rather than seeing it, is told the same things in the same words.

### Going ahead anyway

A warning the owner can tap straight past is a warning that teaches them to tap past warnings. So an affirmative answer to a request the assessment warns about takes a deliberate step: a checkbox saying they have read the warnings and want to go ahead anyway, next to the button that does it, and the button says what it would be doing. Rejecting never asks for anything — saying no is the safe answer.

**What they agree to is the assessment, not the request.** The tick is bound to a `Consent` — the exact `PolicyDecision` it was given for *and* the `RequestFacts` it was about. Edited rules or a moved counter change the decision; a transaction prepared again changes the facts. Either is a different thing to agree to: the tick goes, and the reasons are there to be read again.

**Both halves, because the two don't always change together.** A rule the owner edits can leave this request's every check exactly as it was, and a transaction prepared again can carry another blockhash, another version, or another priority fee while what the rules make of it is word for word the same — and a raised priority fee is real value leaving the wallet that no threshold counts ([known limits](#known-limits)). Comparing decisions alone would carry a tick given for one preparation over to another. What the owner said yes to is *this assessment of this preparation*.

The moment an assessment was made is deliberately not part of consent. The same reasons about the same bytes, read again a second later, are the same reasons.

**Having no rules at all is not a warning.** Every request on a phone whose owner has written no rules is `UNDER_RESTRICTIONS` for want of any, and asking them to tick past that on every request would make the tick a ritual. `PolicyDecision.warns` is `UNDER_RESTRICTIONS` for any other reason — rules this build can't read included, because there the owner did write something and this build can't say what.

### Read again before the answer, not after

The screen's assessment is a snapshot of a reading. The answer does not act on it: `InboxViewModel` reads the rules and the records again at the moment the owner answers, compares what comes back with what they were shown, and stops if it differs — nothing is answered, no wallet is opened, and the review on screen is replaced by the one that stands now. That is what makes a stale review unusable rather than merely unlikely.

The advisory check comes second, always. A preparation that failed this phone's own inspection was refused before any of it ran ([`security.md`](security.md#verification-versus-advisory-rules)).

### The stored snapshot

The assessment the owner read is kept with the record of what they did (`activity/ActivityRecord.kt`, `ReviewedPolicy`): the verdict's code, the reason codes, the codes of the checks nothing covered, when it was made, and whether they went ahead with a warning in front of them.

**It is codes, and never rules.** No threshold, no address, and no list is written into the history: the rules are stored once, in the one place they belong, and a snapshot that copied them would be a second copy to keep in step and a second thing to leak. Codes also mean what a code means can be said better later without the record having to be rewritten, and a code a later version invented is left out of the reading rather than shown as itself.

Nothing reads it back to decide anything. It is written when the owner answers, carried forward unchanged when the record is written again — a status checked ten times later does not know what the review said, and must not take it away — and shown on Activity details.

None of it reaches the sidecar. `StageBoundaryTest` holds the files that speak to one to having never heard of a policy.

## Counters

What this app has moved today, counted from the owner's own Activity records (`activity/`, SAW-023) — the record of what this phone did, which outlives the answer the sidecar was owed.

### What a counter is counted for

One counter is one `SpendScope`: **a connection, a wallet, an asset, and the chain the asset is on.** All four matter, and separating them is not a detail:

- **Two connections using one wallet count apart.** A daily threshold is a rule about one agent, not about the wallet; one agent using up the day's allowance must not silently spend another's.
- **Two wallets spending one mint count apart.** The money comes out of different places.
- **One mint on two chains is two things to spend**, so devnet play money is never counted against a mainnet threshold.

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

A movement is counted once, by **the transaction's signature when there is one, and otherwise by the request it belongs to**. So a request prepared three times, answered, re-sent after a failed delivery, and status-checked ten times is one payment; and two records that carry one signature are one payment. Where two records disagree, the one that knows the most wins: the chain's word settles what the phone's guess couldn't.

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

## Storage

One JSON file per connection, `<filesDir>/policies/<connection ID>.json`, written atomically. Nothing here is encrypted, because a policy holds no credential and no key: public addresses and the owner's own thresholds. Nothing on the phone is backed up.

```json
{
  "version": 1,
  "connectionId": "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11",
  "updatedAt": "2026-09-12T10:00:00Z",
  "actions": ["transfer"],
  "assets": [{ "network": "NETWORK_MAINNET" }],
  "recipients": [],
  "limits": [
    {
      "asset": { "network": "NETWORK_MAINNET" },
      "perOperation": "1000000",
      "daily": "5000000"
    }
  ]
}
```

An absent key is a check with no list; `[]` is a list that allows nothing. Amounts are decimal strings, because a 64-bit base-unit amount doesn't survive a JSON number. `"programs"` is absent above, so programs are not checked.

### Versions and migration

`version` is the document format, and version 1 is the first this app ever wrote.

- **A newer version is refused,** as `NewerVersion`. Reading a document this build only half understands would show the owner fewer rules than they set, and saving it back would delete the rest.
- **An older version is upgraded,** never refused. There is no older version yet; when version 2 arrives, its reader upgrades a version 1 document rather than turning it away, and an upgrade step is kept for every version this app has written.
- **A rule this build has no name for makes the whole document unreadable,** as `UnknownRule` — an action kind or a network it doesn't know. Reading a shorter list would be safe on its own, since these are allowlists and dropping an entry only makes them stricter. It is refused because of what happens next: the owner opens the editor, sees a policy missing a rule they wrote, saves it, and the rule is gone.
- **Anything else that doesn't read back is `Damaged`,** including a file that names another connection, a malformed timestamp, an amount that isn't a whole number of base units, and a policy this app would have refused to write.

A connection whose rules can't be read is never treated as a connection with none. `StoredPolicy` says which of the three it is — `None`, `Policy`, or `Unreadable` — and an unreadable one assesses as `UNDER_RESTRICTIONS` with `policy_unreadable`.

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

## Where the code is

| File | What it holds |
| --- | --- |
| `policy/Policy.kt` | `ConnectionPolicy`, `Allowlist`, `PolicyAsset`, `AssetLimits`, `PolicyAction`, and `policyProblems` |
| `policy/PolicyDecision.kt` | `PolicyAssessment`, `PolicyCheck`, `PolicyCheckStatus`, `PolicyReason`, `PolicyDecision`, `assess`, and `noPolicy` |
| `policy/RequestFacts.kt` | `RequestFacts`, and `policyFacts`, which reads them off a request and its inspection |
| `policy/PolicyEvaluation.kt` | `evaluate`, the six checks, and `PolicyEvaluator` |
| `policy/DailySpending.kt` | `SpendScope`, `SpendStatus`, `Spend`, `DailyTotal`, `spendsOf`, and `dailyTotal` |
| `policy/PolicyDraft.kt` | `PolicyDraft`, `AssetDraft`, `readAmount`, and `review` |
| `policy/PolicyEditorViewModel.kt` | `PolicyUiState`, and load, edit, save, remove, start over |
| `policy/PolicyEditorScreen.kt` | The editor itself |
| `policy/PolicyText.kt` | `PolicyTags`, the owner's words for each rule and each verdict, reason and check, and the plain-language summary |
| `policy/storage/PolicyStore.kt` | The document, its versions, and `StoredPolicy` |
| `inbox/PolicyReview.kt` | The assessment on Request details, and the step before going ahead anyway |
| `inbox/InboxViewModel.kt` | `RequestAssessment`, when an assessment is made, and the re-read before an answer |
| `activity/ActivityRecord.kt` | `ReviewedPolicy`, the snapshot kept with the record |

Tests: `policy/PolicyTest`, `policy/PolicyDecisionTest`, `policy/RequestFactsTest`, `policy/PolicyEvaluationTest`, `policy/DailySpendingTest`, `policy/PolicyEvaluatorTest`, `policy/PolicyFixturesTest`, `policy/PolicyScenarioTest`, `policy/PolicyWordingTest`, `policy/PolicyDraftTest`, `policy/PolicyEditorViewModelTest`, `policy/PolicyEditorScreenTest`, `policy/storage/PolicyStoreTest`, and `PolicyActivityTest` — the editor in the real activity, with the app's own storage. The review has its own: `inbox/PolicyReviewScreenTest`, `inbox/TransferReviewScreenTest`, `inbox/InboxViewModelTest`, `activity/ActivityLogTest`, `activity/storage/ActivityStoreTest`, and `activity/ActivityDetailsScreenTest`.

`StageBoundaryTest` keeps the package unable to act — the editor included. Everything it may reach into is a read: the connection ID rule, the protocol's requests and networks, what the phone read out of a transaction's bytes, the owner's own activity records, the address rule, and the app's own strings, back button, and date format. It may reach nothing that opens a wallet, a connection, or a socket.
