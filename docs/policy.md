# Policies

The rules the owner sets for one connection, and how a request is assessed against them. The rules live on the phone; the assessment is something the owner reads. Neither reaches an agent, and neither decides anything on its own.

Stage 5 builds this in four steps. **SAW-025, the model and the evaluation semantics, is what this page describes and what exists today.** The evaluation of a real request and its daily counters is SAW-026, the editor is SAW-027, and the request-review screen is SAW-028. Until those land, no policy can be created on the phone, and the review screen says "Not evaluated" as it has since Stage 4.

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

## Defaults

A connection with no policy, and a policy with nothing configured, come to the same thing: **`UNDER_RESTRICTIONS`, with the reason `no_policy_configured`.**

Nothing configured is not a match. A policy that asks nothing of a request has said nothing about it, and saying nothing must never read as approval. This is the default state of every connection, and it will stay the default until the owner writes a rule.

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

### Precedence

There is one rule, and it runs in one direction.

1. **Input validation decides what is executable.** A prepared transaction whose bytes disagree with the request, or that the phone can't read whole, has no Approve button at all ([`security.md`](security.md#inspecting-a-transfer)). This is judged before any policy is consulted.
2. **The policy decides what the owner is told.** It is applied to a request that already passed step 1, and it can only add reasons to read.

A policy never softens step 1: no rule can make a malformed or mismatched preparation approvable, and a malformed preparation is never relabelled as a threshold warning. Nor does a policy harden it: `UNDER_RESTRICTIONS` leaves the request exactly as executable as it was.

### Advisory thresholds

Every rule in the MVP is advisory, thresholds included.

- A warning can be overridden by the owner, deliberately, in the app (SAW-028 gives it its own step).
- A counter is a record of what went through **this app**, not a spending cap. It sees nothing the owner did in their wallet directly, and nothing any other app did with the same wallet.
- Nothing here is enforced on chain. The wallet and the network do not know these rules exist.

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

## Where the code is

| File | What it holds |
| --- | --- |
| `policy/Policy.kt` | `ConnectionPolicy`, `Allowlist`, `PolicyAsset`, `AssetLimits`, `PolicyAction`, and `policyProblems` |
| `policy/PolicyDecision.kt` | `PolicyAssessment`, `PolicyCheck`, `PolicyCheckStatus`, `PolicyReason`, `PolicyDecision`, `assess`, and `noPolicy` |
| `policy/storage/PolicyStore.kt` | The document, its versions, and `StoredPolicy` |

Tests: `policy/PolicyTest`, `policy/PolicyDecisionTest`, and `policy/storage/PolicyStoreTest`. `StageBoundaryTest` keeps the package unable to act: it may reach the connection ID rule, the protocol's networks, and the address rule, and nothing that opens a wallet, a connection, or a socket.
