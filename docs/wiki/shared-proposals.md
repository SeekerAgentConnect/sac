# Shared proposals, and decisions that stay on the phone (SEE-89)

SEE-88 made the kind of server part of a connection's record. A `gateway_feed` connection is a
developer's publisher, read through the shared gateway, with no credential and no call to the
publisher at all. This is the document that broadcast carries — a **proposal** — and everything the
phone keeps about one.

The shape of the problem is new in this stage. A private request is addressed to one phone: the
owner's own sidecar stores it, the owner answers it, and the answer goes back to the agent that
asked. A proposal is addressed to nobody in particular. One publisher publishes it once; everyone
subscribed receives the same bytes; and each owner decides for themselves, with their own wallet,
for their own amount. **The proposal is shared. Every decision about it is not.**

That line is the whole design, and the rest of this page is how it is kept.

## What a proposal is not

It is not `ActionRequest` with more subscribers. The temptation is obvious — a queue of things to
approve already exists — and taking it would have made the personal request shared and mutable:
whose approval moves it, whose amount is in it, what "completed" means when one of a thousand
subscribers acted. So `seekervault.proposal.v1` is its own package, and nothing in this change
touches the private workflow. A request still returns its result to the server that asked.

## The document

[`packages/protocol/proto/seekervault/proposal/v1/proposal.proto`](../../packages/protocol/proto/seekervault/proposal/v1/proposal.proto),
published by the Go templates (SEE-95, SEE-96) through the gateway (SEE-90).

| Field | What it is |
| --- | --- |
| `server_id` | The publisher's lasting ID, the one in its own manifest |
| `channel` | `server/<server_id>`, the one channel that publisher owns |
| `proposal_id` | The publisher's own ID for this proposal, stable for as long as it exists |
| `revision` | Changes whenever anything else does, and never goes backwards |
| `operation` | What is proposed, at the protocol's level: `swap`, not the provider |
| `plugin_id` | The bundled plugin it was written for — checked, never followed |
| `status` | `open` or `cancelled`. Never absent, and a missing one is not read as open |
| `created_at`, `updated_at` | The publisher's clock |
| `expires_at` | Absolute, and required: nothing is open for ever by saying nothing |
| `publisher_note` | The publisher's own prose. Unverified, shown apart, bounded |
| `values` | The operation's common terms: at most 32 named texts, each ≤ 512 bytes |

**There is no version field.** Which contract a publisher speaks is stated once, in its
`ServerManifest`, and a phone cannot hold a feed at all without having validated that manifest
first (`Connection`'s own invariant, SEE-88). A second version on this document would be a second
place for one fact to be wrong, and it would catch nothing the cache rule doesn't: a publisher that
changes contract changes its manifest, which moves its settings revision, which the phone must then
read again.

**What is not in it is the point.** There is no field for a subscriber's address, the quantity one
of them chose, or anything prepared for one of them to sign — because the publisher has no
subscriber to describe. It never learns that a given phone received a proposal, and a
`StageBoundaryTest` check reads the proto and fails if the field set changes, so a field that could
carry any of that has to be added to the check first.

## The two halves of a record

[`proposals/ProposalState.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/proposals/ProposalState.kt).
One file per proposal holds both, and they belong to different people:

| The publisher's half | This device's half |
| --- | --- |
| The validated document, replaced whole on a new revision | The dismissal, and the revision it was made at |
| `status`, `expires_at` — whether it still stands | The review: the exact revision read, and what the owner chose |
| | The execution: what was bound, and what the wallet did |

Nothing here says "completed". Availability is the publisher's — `proposalAvailability` reads its
status and its expiry — and standing is this device's. One owner acting changes nothing for anyone
else, and not because of a rule: there is no per-subscriber state on the publishing server for it to
change, and nothing on this side is ever sent.

Both are **derived on every read**, like support (SEE-88): a stored "expired" would be a fact about
when it was written, and would be wrong the moment the clock moved.

| Standing | Meaning | Executable |
| --- | --- | --- |
| `Executed` | This device already acted. Said first, ahead of anything the publisher did afterwards | no |
| `Dismissed` | The owner hid it here | no |
| `Refused` | The publisher contradicted itself about the terms | no |
| `Cancelled` | The publisher withdrew it | no |
| `Expired` | Its own absolute expiry passed | no |
| `Unsupported` | This build doesn't support the publisher's server (SEE-88) | no |
| `Open` | The publisher stands behind it and this device has done nothing | yes |

## Delivery is not trustworthy about repetition

`ProposalRepository.apply` is the one path a proposal reaches the phone by — a snapshot read, a
stream event, a push that arrived twice — and it is idempotent by construction rather than by the
transport being careful:

- **the same revision, same terms**: nothing is written at all, so a replay cannot touch a
  dismissal, a review, or an execution;
- **a lower revision**: refused. A replayed older document must not restore terms the publisher has
  moved past;
- **a higher revision**: the publisher's half is replaced and this device's half stays exactly where
  it was — which is what makes a review of the older terms *detectably* stale rather than silently
  applied to the new ones;
- **the same revision with different terms**: a contradiction, because a revision is the publisher's
  promise about its content. The phone keeps the terms it validated (this device's own record must
  not be thrown away over the publisher's mistake) and executes nothing further from the proposal
  until a higher revision says something new.

A gateway that couldn't be reached leaves every record exactly as it was.

### Dismissal

A dismissal is final for the proposal's **identity**, not just for its revision. A replayed delivery
cannot bring it back, and neither can a republication: a publisher that could re-open a dismissal by
changing a number would have a way to keep putting the same proposal in front of someone who said
no. The record keeps which revision was dismissed, so it says what the owner was looking at. A
publisher with something else to propose publishes another proposal, which is another identity.

## What a signature is bound to

[`proposals/ProposalBinding.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/proposals/ProposalBinding.kt).
Because a proposal is common, the thing an owner executes never is. It is the terms *as they stood*,
with the parameters *this* owner chose, from the wallet *they* had selected, prepared by the plugin
*this build* carries, as one particular set of bytes. All five are pinned in an `ExecutionBinding`
before the wallet is opened, and `bindingProblem` is the whole gate:

| Problem | What moved |
| --- | --- |
| `already_executed`, `dismissed`, `proposal_refused`, `proposal_cancelled`, `proposal_expired`, `server_unsupported` | The standing — asked for, not decided again, so what the owner sees and what the gate allows cannot drift apart |
| `not_reviewed` | Nothing was reviewed here |
| `proposal_changed` | The binding, or the review, is for another revision |
| `choice_changed` | The parameters aren't the ones the owner reviewed |
| `other_plugin`, `other_contract` | Another plugin prepared the bytes, or one at a boundary version this build doesn't call |
| `nothing_prepared` | No preparation, or no content hash to bind to |
| `preparation_expired` | What was prepared can no longer be included |
| `no_wallet`, `other_wallet`, `other_network` | The wallet selected now isn't the one bound |
| `other_environment` | The promise moved: the owner switched the feed between sandbox and production while this was in hand (SEE-97, [environments.md](environments.md)) |

Every rule is in that one function rather than spread between a repository and a screen, because a
rule in two places is a rule that gets forgotten in one of them. None of them is a warning to be
overruled: an operation that isn't executable has no wallet interaction waiting behind a second tap.

**A publisher's `plugin_id` is checked, never followed.** A document cannot select code. The phone
resolves the operation against its own compiled registry and refuses a proposal whose publisher
named a different plugin than the one that resolved (`proposalPlugin`).

**A preparation is not stored until it is bound.** A plugin's bytes are fetched when the owner is
looking at the proposal and held in memory until they go ahead, exactly as a transfer's prepared
transaction is (SAW-019). Nothing on disk ever says an operation is half-done: what is written is
the binding, and it is written because the wallet is about to be opened.

## One execution, ever

`ProposalExecution` is written **before** the wallet is opened, under the same lock that reads it, so
a second tap finds it and answers `already_executed`. It is the mechanic an approved transfer already
uses, for the same reason (SAW-021).

There is never a second execution for a proposal's identity — not after a failure, not after a
decline, and not on a new revision. A proposal that has already put an operation to this owner's
wallet must not become spendable again because the publisher republished it. A publisher that wants
a second operation publishes a second proposal.

An execution the app closed on is settled as **unresolved**, and never as a failure: the wallet may
well have sent the transaction. The wallet is not asked again either way, and nothing retries
signing or submission automatically anywhere in this path.

## Nothing goes the other way

For a feed the phone calls `PublishWallet`, `PrepareRequest` and `SubmitResult` **on nothing at
all**. That is not a list of exceptions: `Connection.usable` requires the direct mode (SEE-88), and
it is the condition every one of those paths was already gated on, generic synchronization included.
A feed holds no credential and never did.

A subscription tells the gateway which channel it is interested in. That is the whole of what goes
out. The owner's address, the quantity they chose, whether they went ahead, and what came of it are
written to this phone's own storage and read by this phone alone. `ProposalIsolationTest` is where
that is held: two devices, one document, different choices, and an assertion over everything the
gateway was ever asked.

## The owner's own record

An execution is written to **Activity** as it happens, as `ActivityKind.Operation` with a
`ReviewedOperation`: the operation and the plugin as codes, the proposal revision, the wallet and
cluster, which promise it was bound under (SEE-97), the preparation version, and the parameters this
owner chose. It is the binding, written down — so recording it records what was executed rather than
an account of it.

The outcomes are the transfer path's own, because the facts are the same shape:

| The wallet | Activity says |
| --- | --- |
| nothing yet | `Waiting` |
| signed and sent | `Sent` — sent is not paid |
| declined | `DeclinedInWallet` |
| failed, and said so | `NotSigned` |
| never answered | `Unknown` |
| **not asked at all** | `Simulated` — the feed is a sandbox, so nothing was signed and nothing was sent (SEE-97) |

A simulated execution spends the proposal exactly as a declined one does: one execution per proposal
per device, whatever came of it. It carries no signature, and so it offers no explorer link — a
rehearsal has nothing to look up, and this app invents neither
([environments.md](environments.md)).

Since SEE-165 the phone follows a proposal's transaction to the chain itself, from the bytes it
captured before the wallet was opened, and records `Confirmed`, `ChainFailed` or an honest
unresolved state beside the execution ([chain-confirmation.md](chain-confirmation.md)). The
execution itself stays exactly as it was: confirmation never reopens a proposal. A signed message's
signature is still never called a payment, and an operation's
is, because its bytes were a transaction — which is also why it gets an explorer link for its own
cluster.

### Retention

The proposals of a feed go when the feed does, in the same place its answers and its rules already
do (`ConnectionRepository.remove`). A removal that happened while the app was closed is cleaned up
on the next `ProposalRepository.load`. A removal the owner is watching takes effect at once, without
waiting for either: what the screens read is the held proposals against the connections as they are
now (`OperationViewModel`), so a removed feed's signals stop being counted as waiting on Home and
stop being listed in the Inbox under a source that is gone, and a review of one of them closes
(SEE-154). **The Activity records outlive both**: what this phone did is worth keeping after the
connection that proposed it is gone.

## Where it is kept

`ProposalStore`, at `filesDir/proposals/<connection ID>/<proposal ID>.json`, written atomically. One
directory per feed, which is what makes removal a directory removal. Nothing here is encrypted: a
proposal is a broadcast anyone subscribed can read, and this device's half is its own record of
public facts — an address, base-unit quantities, and a signature that is public the moment the
wallet makes it. The wallet's authorization token is not here and never was (SEE-84).

Every rule the model holds itself to is applied again to what comes off the disk. A file whose
channel its publisher doesn't own, or that was moved to another feed's directory, is not that feed's
proposal whatever directory it is in.

## What this build does and doesn't do

Both halves are joined up as of SEE-93. Proposals arrive through
[`ProposalFeed`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/connections/ProposalFeed.kt)
— the gateway is SEE-90, the live stream SEE-91, and a hint that wakes the app SEE-92 — and
`jupiter.swap` is what reads a swap signal's terms, collects the owner's amount and prepares its
bytes ([`jupiter-swap.md`](jupiter-swap.md)).

**Where the owner does it.** A feed connection's details offer its **signals** where a direct
connection offers its pending requests, and one of them opens a review of the same shape as a
transfer's: the publisher's words as theirs, the facts this phone read out of the bytes, then what
the owner's rules make of those facts. `operations/` holds the path and names no provider, so the
next plugin is shown by the same screens.

What is held whole by tests is that path, end to end: apply, review, prepare, bind, execute, record
— with the real plugin, a real store, a real wallet order, and two phones choosing differently from
one document. The traffic to the gateway is captured and searched, and it carries a channel and a
sequence.

- Not a per-subscriber copy on the publishing server. There is nothing there about any phone.
- Not multi-device history sync, and not a central financial record. Each device's decisions are its
  own, and there is no place they are collected.
- Not an automatic approval. A proposal is something to read; the owner's hand on the wallet is the
  only thing that executes one, exactly as for a private request.
- Not a visual redesign. Activity gained one kind and two rows in its existing components
  (`docs/design/README.md`).
