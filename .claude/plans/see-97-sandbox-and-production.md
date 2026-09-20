# SEE-97 — explicit Sandbox and Production modes

One environment contract across the manifest, the two templates, the gateway, the two client
plugins and this phone's own records. Sandbox is a safe demonstration of the real flow, not an
invented Jupiter test network.

## The decisions this rests on

1. **The environment is a connection's, and the owner's.** Not the app's: flipping a phone-wide
   switch would make the owner's own sidecar unexecutable, which is a regression in the private
   workflow for no safety gain. A manifest says which environments a server *serves*; the
   connection records which one it *keeps*. A manifest can therefore never upgrade anything.
2. **A server that serves both starts as Sandbox.** Production is only ever reached by the owner
   choosing it, which is the "explicit user review" the ticket asks for.
3. **Sandbox does everything production does except sign.** Live reads, the same quote, the same
   market, the same bytes, the same inspection — and then no wallet, no signature, no explorer
   link, and a record that says so. A sandbox that asked the provider nothing (SEE-93's rule, which
   named this ticket as its successor) demonstrated nothing; a sandbox that fabricated a signature
   would be worse than either.
4. **The environment is core's fact, never a plugin's claim.** `ActionPlugin` builds bytes; whether
   they are signed is core's business, exactly as the cluster already is
   (`docs/wiki/client-plugins.md`: "The chain is core's"). So the plugins stop reading the
   environment and no plugin-boundary contract version changes.
5. **The guard sits where every other execution guard sits.** `ExecutionBinding` is pinned before
   the wallet opens and checked inside the wallet's own lock; the environment joins it. A stale
   preparation or a switched mode is refused there, not by a screen.
6. **A rehearsal is only possible where nobody is waiting for the answer.** A broadcast proposal
   can be rehearsed. A paired sidecar's request cannot: an agent asking for a signature cannot be
   answered with a simulation, and fabricating one is out of the question. So a direct connection
   is production, and a direct server that serves only sandbox stays unexecutable — exactly as
   today.
7. **A server's environment is part of its identity.** The gateway refuses a manifest that changes
   it, mirroring the publisher's own database stamp ("the publisher and the environment may not
   move"). A second environment is a second deployment.
8. **The push topic's environment stays what it is.** It is one label in a topic name, chosen by
   the deployment's operator, and the gateway is deliberately not in the promise business. The two
   concepts stay separate and the docs keep saying so.

## Protocol

- [x] `proto/seekervault/gateway/v1/problem.proto`: `GATEWAY_PROBLEM_OTHER_ENVIRONMENT = 34`, in
      the `OTHER_*` family it belongs to (not `BAD_ENVIRONMENT`, which means malformed).
- [x] `pnpm generate`; `pnpm check:generated` clean.

## The gateway (`broadcast/`)

- [x] `internal/rules/manifest.go`: `AdvanceManifest` refuses a higher revision whose
      `environments` differ from the held ones.
- [x] The Connect code for it is `failed_precondition`, with the held revision, like the other
      "true only while the gateway holds what it holds" refusals.
- [x] Tests: the change refused, the same set at a higher revision accepted, and an unchanged
      republication still `Unchanged`.

## The templates (`publisher/`)

- [x] New `internal/environment`: `Environment`, `Production`, `Sandbox`, `Parse`, `Wire`, `String`.
      One shared type instead of four places comparing string literals. (No `Simulates`: nothing in
      this module executes anything, so a method about that would have had no caller.)
- [x] `internal/config`: `Config.Environment` is that type; the problem text stays.
- [x] `internal/manifest`: `Document` no longer fails open to production for an unknown string.
- [x] `internal/api`: `/v1/manifest` renders `environments` from the document it already built, not
      from the raw setting.
- [x] `internal/store`: the stamp keeps its string column and is written from the type.
- [x] Both mains: unchanged in shape; the startup line keeps saying which environment it is.
- [x] A contract test: the phone's `PluginEnvironment` codes are exactly these two words.
- [x] `.env.example`, `.env.prediction.example`: `PUBLISHER_ENVIRONMENT=sandbox`, so a copied
      example runs as a demonstration and production is a deliberate edit.

## The phone

### What a connection keeps

- [x] `connections/Connection.kt`: `environment: PluginEnvironment`, with the direct-is-production
      invariant stated where the other two invariants already are.
- [x] `connections/storage/ConnectionStore.kt`: version 3; a version-2 record decodes as
      production, which is exactly what this build did until now.
- [x] Creation: a feed takes the only environment its manifest names, or Sandbox when it names
      both; a direct connection is production.
- [x] `ConnectionRepository.setEnvironment`: only to an environment the server names, and never on a
      direct connection. It writes the connection and nothing else — what was prepared stops
      counting because it says which environment it was made in (see the Review).

### What decides

- [x] Remove the four `PluginEnvironment.Production` constructor defaults
      (`ConnectionsViewModel`, `InboxViewModel`, `ProposalRepository`, `OperationViewModel`) and
      read the connection's own instead.
- [x] `proposals/ProposalBinding.kt`: `ExecutionBinding.environment`, and
      `BindingProblem.OtherEnvironment` when it is not the connection's current one — checked
      inside the wallet lock by `beginExecution`.
- [x] `operations/OperationViewModel.kt`: the environment decides once, and the sandbox branch is
      lexically outside `wallet.withWallet`, so there is no session in scope to sign with.
- [x] `OperationViewModel` invalidates a preparation when the connection's environment moves, as it
      already does when the proposal's revision moves.

### What is recorded

- [x] `ProposalOutcome.Simulated` — no signature, and settled.
- [x] `ActivityOutcome.Simulated`, and `ReviewedOperation.environment` beside the cluster it
      already keeps.
- [x] `ProposalStore` and `ActivityStore` encode both; a record without one reads as production.
- [x] No explorer link for a simulated record, which falls out of the existing rule (no signature,
      no link) and is asserted rather than assumed.

### The plugins

- [x] `JupiterSwapPlugin`, `JupiterPredictionPlugin`: drop the sandbox refusal and the two string
      resources behind it. They prepare the same way in both environments; what differs is what
      core does next.
- [x] `plugins/ActionPlugin.kt`: `PluginEnvironment` says what each environment now means; the
      contract stays 1 and the comment says why.

### What the owner sees

- [x] The connection's environment on its details, switchable only when the server serves both.
- [x] An unmistakable sandbox marker on the connection and on the review, and a review whose action
      says it simulates.
- [x] A simulated record reads as simulated in Activity, with no signature and no link.
- [x] Strings in `strings.xml`; the approved v4 components, no new screen.

## Tests

- [x] A sandbox proposal prepares through the real plugin over a stood-in provider, reaches no
      wallet (`adapter.sendings`/`signings` empty), settles as `Simulated`, and records the
      environment. The live provider is the opt-in test's, as it was for SEE-93 and SEE-96.
- [x] The same proposal in production is unchanged.
- [x] Switching a connection's environment invalidates the preparation; a binding made in the other
      environment is refused inside the lock.
- [x] A manifest revision that flips sandbox to production leaves the connection in sandbox and
      unexecutable.
- [x] `ConnectionStore`: the environment round-trips; a version-2 record reads as production; a
      direct record is normalized to production.
- [x] The two Jupiter plugin tests that asserted sandbox asks nothing become tests that preparing
      does not read the environment at all.
- [x] Gateway: the environment-change refusal. Publisher: the manifest mapping and the refusal of
      an unknown value.

## Documentation

- [x] New `docs/wiki/environments.md`: the one page for the contract — what each environment
      promises, where it is decided, what sandbox does and does not do, the mode switch, and the two
      things called "environment" that are not the same thing.
- [x] Every statement that says sandbox refuses before asking, or that SEE-97 owns what it grows
      into: `docs/wiki/jupiter-swap.md`, `docs/wiki/jupiter-prediction.md`,
      `docs/wiki/client-plugins.md`, `docs/wiki/server-manifests.md`,
      `docs/wiki/copytrading-template.md`, `docs/wiki/prediction-template.md`,
      `docs/wiki/feed-gateway.md`, `docs/wiki/shared-proposals.md`, `docs/architecture.md`,
      `docs/protocol.md`, `docs/security.md`, `docs/development/android.md`,
      `docs/development/publisher.md`, `docs/development/feed-gateway.md`,
      `docs/integrations/signal-api.md`, `CODEBASE.md`, `AGENTS.md`.
- [x] "Execution environment is separate from cluster selection", said outright rather than by
      adjacency, and no promise of Jupiter devnet parity anywhere.
- [x] `docs/testing/stage-7-1.md`: a by-hand section for SEE-97 and a device step, with the
      production validation named as the owner's own deliberate check.
- [x] `docs/changelog/2026-09-18.md`: appended.
- [x] `.claude/tasks/lessons.md` if anything here was learned the hard way.

## Verification

- [x] `pnpm check`, `pnpm check:generated`, `pnpm check:format`, `pnpm check:lint`
- [x] `pnpm check:broadcast`, `pnpm check:publisher`, `pnpm check:android`
- [x] A run by hand: a sandbox template publishing live-data proposals, a phone-side rehearsal, and
      the production path still reaching the wallet exactly once.
- [x] Deliberate breaks, each failing its own named check, every file restored byte for byte.

## Review

Done, and the shape held: the decisions at the top are the ones that shipped. Two things are worth
recording because they were not obvious when the plan was written.

**The environment is a connection's, and that was the pivotal call.** The four
`PluginEnvironment.Production` defaults in the ViewModels pointed at an app-wide switch, and an
app-wide switch would have made the owner's own sidecar unexecutable in sandbox — a regression in the
shipped private workflow, for no safety gain. Per-connection cost the same amount of code, kept the
direct path untouched, and gave "switching mode" a precise meaning. The direct-is-production
invariant fell out of it rather than being added to it: a rehearsal is possible only where nobody is
waiting for the answer.

**The persisted review keeps its choice, and nothing is deleted on a switch.** The plan said the
switch would invalidate what was prepared, and it does — but by *comparison* rather than by
deletion. The review, the preparation and the binding each say which environment they were made in,
so a switch makes them stop counting by themselves. An invalidation that had to be remembered in
`setEnvironment` is one that could be forgotten there, and the owner's chosen amount survives as a
starting point while the preparation, the acknowledgement and the approval do not.

**No contract bump.** `PLUGIN_CONTRACT` is still 1, which was a deliberate check rather than an
omission: nothing about `ActionPlugin` changed. The environment was always in `ActionSubject`, every
plugin already declared which environments it serves, and what changed is what *core* does with a
preparation. Raising it would have invalidated both templates' published `1..1` requirements for no
behavioural reason.

**What the breaks found.** Fourteen breaks, twelve of which failed immediately. The two that did not
were the point of running them: `setEnvironment` losing its "not served" refusal failed nothing
because the test only switched to a served environment, and the manifest answer taken from the raw
setting failed nothing because the assertion tested the helper rather than the handler. Both tests
were rewritten, and both breaks then failed.

**Stated limits.** The phone's half is step 10 of the owner's device run — whether a rehearsal really
opens no wallet on a real device, and whether a person could mistake the result for a purchase, are
not questions this machine can answer. Every Docker-daemon check is NOT RUN, as in SEE-95 and
SEE-96. And the gateway's push-topic environment was deliberately left alone: deriving a topic from
a publisher's manifest instead of the deployment's setting would have been a small improvement in
isolation and a change to a documented contract that is explicitly *not* this ticket's model.
