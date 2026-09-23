# Common requests (SEE-108)

`seekervault.request.v2.Request` is the source-authored envelope used by both private requests and
public feed signals. `Signal` is presentation: a small category label plus the feed's local name.
It is not another lifecycle, action type, result channel, or store.

## The envelope

Every request has eight explicit parts:

| Part | Meaning |
| --- | --- |
| `contract_version` | The common envelope version. This release writes and executes version 1 only |
| `identity` | Source, source-owned scope, and a request ID stable across source revisions |
| `lifecycle` | Ordered revision, source status, creation/update time, and absolute expiry |
| `presentation` | Unverified title and description, plus `request` or `signal` |
| `action` | A versioned capability, the bundled plugin compatibility claim, and bounded typed source parameters |
| `owner_inputs` | Declarations for controls compiled into the app, never an owner's answers |
| `audience` | One authenticated private recipient or one public feed channel |
| `result_handling` | Return to the authenticated origin, or device-local forever |

The protobuf deliberately has no field for an owner's answer, selected wallet, decision, prepared
bytes, signature, execution outcome, subscriber, credential, code, or URL. A plugin ID is checked
against code already bundled in the app; it cannot select or download code.

## How the phone reads `action` (SEE-145)

SEE-145 separated what the owner wants to do from who prepares it, and **the wire did not change to
say so**. No field was added to this envelope or to any other proto. The three parts of `action`
this release already had carry the whole of it:

- **`capability_id` and `capability_version` are the action, provider-neutrally.** The phone reads
  them into an `ActionId` — `swap`, `prediction.buy` — and the version of that action's own payload
  schema. Nothing about either names a venue, which is the point: core says which action a request
  is for, and a provider claims the ones it can serve.
- **Both spellings of the prediction action are accepted**, and they mean the same thing. A
  publisher that says `prediction` and one that says `prediction.buy` are asking for the same order,
  and the phone prepares the same one from either. What the phone *re-emits* — in a stored row and
  in the legacy `Proposal.operation` field — is the legacy spelling, so a client written before
  SEE-145 reads what it always read.
- **`plugin_id` is the provider's compatibility claim.** Nothing on this wire names an execution
  provider directly, so the provider is the one that answers to the bundled plugin the document
  claims. It is **looked up, never parsed**: `jupiter.swap` is not read as "the provider `jupiter`
  doing the action `swap`", because a name that happens to contain a dot is not a structure. One
  explicit table carries the two names published before there was a way to name a provider at all,
  and a provider registered later is matched by the legacy names it declares for itself. A claim
  this build resolves to nothing is a refusal, never a licence to pick whatever provider the build
  happens to have for the action.

**Mixed versions behave as they always did, in both directions.** A capability version this build
does not read stays readable and dismissible, and is refused where providers are resolved —
`SchemaUnsupported`, told apart from an action nothing serves and from a provider this build does
not carry — rather than being mistaken for a version it does read. A publisher that has not heard of
SEE-145 publishes exactly what it published before and is served exactly as before; a phone that has
not heard of it reads rows this one writes, because they still carry the old names beside the new.
Adding an explicit execution-provider field to the envelope is the natural next step and is
deliberately not part of this change ([`execution-providers.md`](execution-providers.md#compatibility)).

## Two adapters, one review collection

The direct sidecar adapter accepts a private request draft and assigns its connection-scoped
identity and durable lifecycle through the existing `RequestStore`. Its result policy is
`RETURN_TO_ORIGIN`; the existing authenticated `PrepareRequest` and `SubmitResult` workflow is
unchanged. The MCP tool names and their public contracts remain compatible.

The publisher adapter builds the same envelope with a feed audience, `Signal` presentation and
`DEVICE_LOCAL` result handling. `PublisherService.PublishRequest`/`CancelRequest` and
`FeedService.ListRequests`/`GetRequest` are the primary gateway methods. The Stage 7.1 proposal
methods and `/v1/signals` publisher routes remain compatibility adapters over the same rows,
revision rules and outbox; they are not a second workflow.

On Android, legacy direct `ActionRequest` records and feed records normalize into the v2 envelope
before they enter the shared review collection. Home shows one chronological carousel and the feed's
**Signals** entry is a connection filter over it. Direct actions retain the direct lifecycle and
return declared results; feed actions use the execution provider their document names and their
exact execution binding, and keep their outcomes locally.

## State and migration

The common envelope is the source's half. It does not replace the state that only one owner may
change:

- direct pending results and activity keep their existing stores and retry semantics;
- feed dismissal is final across revisions;
- one execution attempt is recorded before a wallet opens and remains final for that proposal
  identity;
- a feed binding still pins source revision, owner choices, wallet, boundary contract and prepared
  bytes together, and since SEE-145 the execution provider, the action, its schema version and the
  instrument beside them;
- a feed action never enters the direct result outbox, and no local feed state is uploaded to the
  publisher or gateway.

`ProposalStore` version 4 records the execution provider and the versioned action **beside** the
legacy operation and plugin names, which it keeps writing: a row this build writes is still readable
by one that only knows version 3, and a row written before this build is read through the same
compatibility table the wire uses. Version 3 adds the common presentation and owner-input
declarations. Versions 1 and 2 read as contract 1, derive the title from the operation, retain no
declarations they never had, and preserve every dismissal, review, execution and outcome. The
gateway continues to use its
existing `proposal` table and reads old proposal bytes through the compatibility adapter, so
publisher identities, revisions and retained documents survive the deployment.

## Developer surface

Publisher templates document `POST /v1/requests`, and the small Go client exposes
`sdk.Client.CreateRequest`. A template supplies its registered capability, feed audience and local
input declarations; a caller supplies expiry, description and operation parameters. The strict JSON
decoder rejects unknown fields, including anything that tries to send a wallet, amount choice,
decision, signature or result. `Idempotency-Key` retains its existing meaning.

The publisher write API and public-feed read API are separate listeners at the gateway. A public
read port has no mutation handler. A publisher credential grants only that server's public
documents.

## Execution invariants

- Sandbox stops at simulation and never asks a wallet to sign or send.
- Production always requires manual approval.
- An approval is for the exact revision and prepared bytes the owner reviewed.
- Unknown envelope or capability versions, a provider this build does not carry, unreadable bytes
  and unresolved lookup tables block signing; none falls back to a blind signature, and none falls
  back to another provider. A capability version this build does not interpret stays readable and
  dismissible.
- Broadcast cancellation changes the source document for everyone, but one subscriber's action
  changes only that device's local record.
- `RETURN_TO_ORIGIN` remains the direct-server result policy; the shared gateway has no result API.
