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
before they enter the shared pending collection. Home shows one chronological carousel, Pending
shows one list, and `request/private/...` and `request/feed/...` share one review dispatcher. A
feed's **Signals** entry is only a connection filter over that list. Existing specialized review
content remains: direct actions still use the direct lifecycle, while a feed action still uses its
bundled plugin and device-local binding.

## State and migration

The common envelope is the source's half. It does not replace the state that only one owner may
change:

- private pending results and activity keep their existing stores and retry semantics;
- feed dismissal is final across revisions;
- one execution attempt is recorded before a wallet opens and remains final for that proposal
  identity;
- a feed binding still pins source revision, owner choices, wallet, plugin contract and prepared
  bytes together;
- a feed action never enters the direct result outbox, and no local feed state is uploaded to the
  publisher or gateway.

`ProposalStore` version 3 adds the common presentation and owner-input declarations. Versions 1
and 2 read as contract 1, derive the title from the operation, retain no declarations they never
had, and preserve every dismissal, review, execution and outcome. The gateway continues to use its
existing `proposal` table and reads old proposal bytes through the compatibility adapter, so
publisher identities, revisions and retained documents survive the deployment.

## Developer surface

Publisher templates document `POST /v1/requests`, and the small Go client exposes
`sdk.Client.CreateRequest`. A template supplies its registered capability, feed audience and local
input declarations; a caller supplies expiry, description and operation parameters. The strict JSON
decoder rejects unknown fields, including anything that tries to send a wallet, amount choice,
decision, signature or result. `Idempotency-Key` retains its existing meaning.

The publisher write API and subscriber read API remain separate listeners at the gateway. A read
port has no mutation handler; a publisher credential grants no subscriber identity because the
gateway stores none.

## Execution invariants

- Sandbox stops at simulation and never asks a wallet to sign or send.
- Production always requires manual approval.
- An approval is for the exact revision and prepared bytes the owner reviewed.
- Unknown envelope or capability versions, missing plugins, unreadable bytes and unresolved lookup
  tables block signing; none falls back to a blind signature. A capability version this build does
  not interpret stays readable and dismissible.
- Broadcast cancellation changes the source document for everyone, but one subscriber's action
  changes only that device's local record.
