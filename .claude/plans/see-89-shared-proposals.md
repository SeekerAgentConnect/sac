# SEE-89 — Shared proposals with device-local parameters, decisions, and results

Stage 7.1, the fourth fed child of SEE-85. It follows SEE-88, which made the kind of server part of
a connection's record: a `gateway_feed` connection is a publisher's broadcast, read through the
shared gateway, holding no credential and never calling the publisher.

This task adds the document that broadcast carries — a **proposal** — and everything the phone keeps
about one, without turning the existing personal `ActionRequest` into a shared mutable request.

## What the ticket asks for

One proposal is published for all subscribers; every device owns its decisions and execution
records. So:

- a versioned common proposal: server/channel identity, a stable proposal ID, a revision, the
  operation and the plugin it needs, bounded operation data, created/updated times, an absolute
  expiry, and the publisher's cancellation state;
- proposal data is common intent — never a subscriber's wallet, amount, or prepared transaction;
- availability (the publisher's) kept apart from dismissal, review, preparation and submission
  (this device's), so one user's action never marks the publication completed for another;
- local state keyed by server/channel/proposal identity, preserving the **exact reviewed revision**,
  with replays, duplicate pushes and snapshot reloads unable to re-open a dismissal or execute
  twice;
- before signing, a binding over the selected parameters, the current wallet and network, the plugin
  version and the exact prepared bytes; a changed proposal, amount or quote needs fresh review;
  expiry or cancellation prevents a new execution;
- pending/unknown/submitted records preserved across process death, with no inferred failure and no
  automatic retry;
- for a feed: no `PublishWallet`, `PrepareRequest` or `SubmitResult` on the publisher or the gateway,
  and no local outcome uploaded through generic sync;
- the direct workflow unchanged.

## What this build can and cannot do

Honest scope, the same shape as SEE-88's:

- The gateway that delivers a proposal is **SEE-90/SEE-91**, so `ProposalFeed` is the one seam a
  proposal arrives through and this build supplies no implementation. `ProposalRepository.refresh`
  answers `NoFeed` rather than pretending.
- The plugins that read a proposal's data, collect the owner's parameters and prepare bytes are
  **SEE-93/SEE-94**, and `PluginRegistry.bundled()` is still empty.
- So **this build receives no proposal and executes none**, and there is no proposals screen. What
  lands is the contract, the validated model, the local state and its store, the pure rules, the
  repository that applies them, the Activity record, the guards, and the tests that hold all of it
  against fakes.

## Plan

### The contract

- [x] `proto/seekervault/proposal/v1/proposal.proto`: `Proposal`, `ProposalStatus`, `ProposalValue`.
      Its own package, like `seekervault.server.v1`: a proposal is published by a developer's Go
      publisher through the gateway, and neither speaks `RequestService`.
- [x] No per-proposal protocol version: the contract a publisher speaks is stated once, in its
      manifest (`ServerManifest.protocol_version`), and a feed exists only because that manifest was
      validated. Record the decision.
- [x] `pnpm generate`, and commit the generated TypeScript and Kotlin.
- [x] Fixtures `proto/fixtures/seekervault/proposal/v1/Proposal/{open,cancelled,foreign_channel,max_revision}.json`.

### The phone's model (`android/.../proposals/`, data and pure functions only)

- [x] `Proposal.kt`: `ProposalKey` (server, channel, proposal — with channel ownership held in the
      key's own invariant), `Proposal`, `ProposalStatus`, `ProposalValue`, and the bounds.
- [x] `ProposalValidation.kt`: `ProposalExpectation`, one `ProposalProblem` per rule, `proposalFrom`
      in a fixed order.
- [x] `ProposalState.kt`: `ProposalRecord` (the publisher's half and this device's half, separate —
      the dismissal, the review, the execution, and a refusal), `ProposalDismissal`,
      `ProposalReview`, `ProposalExecution`, `ProposalOutcome`, and the derived
      `proposalAvailability` / `proposalStanding` / `executable` — derived, never stored.
- [x] `ProposalBinding.kt`: `ExecutionBinding`, one `BindingProblem` per rule, `bindingProblem` —
      the whole gate between a review and the wallet, as one pure function — and `proposalPlugin`,
      which checks the publisher's plugin name against the one this build resolves.

### The impure half (`connections/`)

- [x] `ProposalFeed.kt`: the seam, with no implementation in this build.
- [x] `storage/ProposalStore.kt`: one JSON file per proposal under its connection, written
      atomically, identity re-checked on read.
- [x] `ProposalRepository.kt`: `load`, `refresh`, `apply`, `dismiss`, `review`, `beginExecution`,
      `recordOutcome` — idempotent against replay, one execution per proposal, and an execution left
      open by process death settled as unresolved rather than as failure. Removal stays in
      `ConnectionRepository`, where a connection's local state already goes when it does.
- [x] `ConnectionRepository`: a removed connection's proposals go with it.

### The owner's own record (`activity/`)

- [x] `ActivityKind.Operation`, `ReviewedOperation`, `ReviewedValue`; identity and
      `signatureIsTransaction` extended to it; `ActivityLog.record(ActivityRecord)`; additive store
      fields with no version bump.
- [x] The words for it, and the details screen's rows.

### Guards

- [x] `StageBoundaryTest`: a new check that a proposal is bounded data and the package that reads
      one acts on nothing — the exact import list, no `suspend`, no wallet/transport/store/approval,
      and the proto's field set and forbidden words.
- [x] The plugin-importer allow-list: `proposals/` joins the owner packages, and the two new
      importers are named.

### Tests

- [x] `proposals/`: `Proposals.kt` (wire builders), `ProposalValidationTest`, `ProposalStateTest`,
      `ProposalBindingTest`, `ProposalFixturesTest`.
- [x] `connections/`: `ProposalStoreTest`, `ProposalRepositoryTest` (replay, stale revision, cancel,
      expiry, changed parameters, double tap, restart, dismissal, removal, no feed),
      `ProposalIsolationTest` (two devices, and nothing outbound).
- [x] `activity/`: the operation record, its outcomes, and its round trip.
- [x] `sidecar/src/proposals.test.ts`: the cross-runtime fixtures.

### Documentation

- [x] `docs/wiki/shared-proposals.md`, and the sections in `docs/protocol.md`,
      `docs/architecture.md`, `docs/security.md`, `docs/development/android.md`.
- [x] `AGENTS.md`, `CODEBASE.md`, `docs/changelog/2026-09-17.md`.

### Verification

- [x] `pnpm check`, `pnpm check:android`, `pnpm check:generated`, `pnpm test:hello`,
      `pnpm test:queue`, `pnpm test:transfer`, `pnpm test:updates`, `pnpm test:push`.
- [x] Deliberate breaks for each new guard, each restored and `cmp`-verified.

## Acceptance

- [x] Two devices receive identical proposal content and independently select different
      amounts/outcomes.
- [x] Dismissal or submission on one device does not alter the other's proposal.
- [x] Captured gateway/publisher traffic contains no user wallet, selected amount, approval or
      execution result.
- [x] Replay, stale revisions, cancel/expiry, changed parameters, double-tap and process restart are
      covered.
- [x] Local Activity uses accurate submission/uncertainty states and survives feed removal according
      to documented retention behaviour.
- [x] Existing direct approval binding and result-retry tests continue passing.

## Review

Written after the work landed.

### The six decisions worth recording

**1. A proposal carries no protocol version of its own.** The contract a publisher speaks is stated
once, in its manifest, and a feed connection cannot exist without a validated manifest
(`Connection`'s own invariant, SEE-88). A second version field on the document would be a second
place for the same fact to be wrong, and the cache rule already covers the case it would catch: a
publisher that changes contract changes its manifest, which moves its settings revision, which the
phone must read again. The document's own versioning is `revision`, and the package is `v1`.

**2. A dismissal is final for the proposal's identity.** A replayed event must not re-open a
dismissed item, and neither must a republished revision: otherwise a publisher could push a
dismissed proposal back in front of the owner by bumping a number. The record keeps
`dismissedRevision` so it says *which* revision they dismissed, and a later revision updates the
publisher's half of the record while the dismissal stands. A publisher that wants to ask again
publishes a new proposal, which is a new identity.

**3. One execution per proposal identity per device, ever.** The execution record is written before
the wallet is opened and is never replaced, so a double tap finds it and stops — the same mechanic
as an approved transfer's stored bytes (SAW-021). It is not relaxed for a failed or declined
outcome, and not for a new revision: a proposal that spent money once must not be made spendable
again by the publisher's own republication. A second operation is a second proposal.

**4. Availability is the publisher's, standing is this device's, and both are derived.** Nothing
writes "expired" or "cancelled" to disk as a local fact: `proposalAvailability` reads the status and
the expiry the publisher published, and `proposalStanding` adds what this device did. A stored
verdict would be wrong the moment the clock moved, exactly as a stored support verdict would be
wrong the moment a build changed (SEE-88).

**5. The whole gate between a review and the wallet is one pure function.** `bindingProblem` takes
the record, the binding the caller is about to sign, the wallet selected now, the server's support
and the clock, and answers with the one rule that stops it. It asks `proposalStanding` for the first
five of those answers rather than deciding again, so what the owner is shown and what the gate
allows cannot drift apart, and its exhaustive `when` makes a standing added later a decision here.

**6. A contradicted proposal is marked, not deleted.** SEE-88 could record a refused manifest by
replacing the record, because the record was only the server's. A proposal's record is half this
device's — the dismissal, the review, the execution — and throwing that away over the publisher's
mistake would lose the owner's own account of what their phone did. So a revision that arrives with
different terms leaves the validated terms in place and sets `ProposalRecord.refused`, which makes
the standing `Refused` and nothing further executable; a higher revision clears it, because that is
the publisher saying something new. This one was found while writing the cache rule, not while
planning it.

### The breaks that prove the guards

| Break | What failed |
| --- | --- |
| `proposals/` reaching for `ConnectionRepository` | the new boundary check |
| a `wallet` field in `proposal.proto` | the new boundary check's field set and word list |
| accepting a channel its publisher doesn't own | `ProposalValidationTest`, `ProposalFixturesTest` |
| letting a second execution begin | the double tap, and the restart with an operation still open |
| dropping the stale-revision rule | `ProposalValidationTest` and `ProposalRepositoryTest` |
| letting a new revision clear a dismissal | the case that says a dismissal is final for the proposal |

Each was time-limited, restored from a copy, and `cmp`-verified.

### Caveats

- **This build receives no proposal.** `ProposalFeed` is the seam; the gateway is SEE-90 and the
  live stream SEE-91, and `refresh` reports `NoFeed`. There is no proposals screen, because there is
  nothing for it to list.
- **This build executes none.** The plugins that collect parameters and prepare bytes are SEE-93 and
  SEE-94, and the bundled registry is still empty. The local path — apply, review, bind, execute,
  record — is held whole by tests against a fake feed and a test plugin.
- **Physical-device checks: NOT RUN.** Nothing owner-visible changes in a build that can receive no
  proposal; the Activity record for an operation is unreachable without a plugin.
- No container was built or run: no Docker daemon is reachable here.
