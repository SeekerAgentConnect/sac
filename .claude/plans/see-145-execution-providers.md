# SEE-145 — a common Solana execution-provider interface, with Jupiter as the first bundled adapter

Ticket: https://linear.app/seekeragentwallet/issue/SEE-145
Branch: `feat/see-145` · base `master`

## What this changes, in one line

`jupiter.swap` and `jupiter.prediction` stop being two plugins that happen to be Jupiter's and
become one **execution provider** (`jupiter`) serving two **provider-neutral, versioned actions**
(`swap` v1, `prediction.buy` v1), behind a boundary another compatible Solana provider can be
registered against without core review, rules, wallet or history code changing.

## The four identities the ticket asks to be kept apart

| Identity | Where it lives after this |
| --- | --- |
| Source | `Proposal.key.serverId` / the connection. Unchanged. |
| Execution provider | `ExecutionProviderId` — `jupiter`. New, explicit, bound to the review. |
| Market provider | `PredictionPayload.marketProvider` (Kalshi, Polymarket). Already there; now surfaced as part of the instrument. |
| Instrument | `Instrument(marketProvider, id)` — the ordered mint pair for a swap, the market for a prediction. New, bound to the review. |

## Plan

### 1. The common contract — `plugins/execution/`
- [x] `ExecutionProviderId`, `ActionId`, `PROVIDER_CONTRACT`, `ProviderCapabilities`,
      `ActionCapability` (action, schema versions, Solana networks, deposit assets, limits).
- [x] `ActionOperation` — what core hands a provider: connection, action + schema version, provider,
      environment, network, wallet, request, and a **typed** `ActionPayload`.
- [x] `ActionPayload` sealed: `Swap`, `PredictionBuy`; each exposes an `Instrument`.
- [x] `ExecutionProvider`: `capabilities`, `inputs`, `resolve` (suspend, live info + constraints),
      `prepare`, `inspect`, `destinations`, optional `status`.
- [x] `ProviderRegistry.resolve(provider, action, schemaVersion, network, environment)` with one
      unsupported reason per way it can fail.
- [x] `Compatibility.kt`: the explicit legacy table `jupiter.swap ↔ (jupiter, swap, 1)` and
      `jupiter.prediction ↔ (jupiter, prediction.buy, 1)`, plus the legacy operation names.

### 2. Provider-neutral action payloads — `plugins/actions/`
- [x] Move `SwapTerms`/`PredictionTerms` parsing out of `jupiter/` (they are the action's schema,
      not Jupiter's API) and make the messages neutral string resources.
- [x] Deposit-mint set and minimum order move from the payload parser into Jupiter's **capabilities**,
      so an unsupported deposit asset is a provider answer rather than a schema rule.

### 3. Jupiter adapter — `jupiter/JupiterProviderAdapter.kt`
- [x] One `ExecutionProvider` serving both actions; swap and prediction preparation, provider API
      access, instruction readers and the handoff link stay inside it.

### 4. Core
- [x] `ExecutionBinding` gains `provider`, `action`, `schemaVersion`, `instrument`; `bindingProblem`
      refuses each of them.
- [x] `Proposal` carries `action` + `provider` (+ the publisher's legacy plugin claim).
- [x] `ProposalStore` v4 writes both forms and reads v1–v3 rows through the compatibility table.
- [x] `ActionCapability.execution_provider` added to `request/v2` for explicit new-format emission.

### 5. Tests
- [x] Registry: unsupported provider / action / version / network / environment / contract.
- [x] Compatibility: legacy and new-format proposals resolve to the same Jupiter behaviour.
- [x] Binding: provider, market, side, amount, owner, network, environment, bytes each invalidate.
- [x] A **test-only** alternate provider registered beside Jupiter, driving the same core flow;
      an assertion that the bundled production registry carries Jupiter alone.
- [x] Upgrade: legacy store rows keep their pending items, records and one-attempt behaviour.

### 6. Docs
- [x] `docs/wiki/execution-providers.md` (the extension contract + how to add a provider).
- [x] Update `client-plugins.md`, `jupiter-swap.md`, `jupiter-prediction.md`, `common-requests.md`,
      `integrations/jupiter.md`, `docs/development/android.md`, changelog, `CODEBASE.md`.

## Review

Landed as planned, with three decisions worth recording.

**The wire was not changed.** The plan assumed an `execution_provider` field on
`request/v2.ActionCapability`. Regenerating the bindings needs network access to buf's remote
plugins, which this environment blocks (`tls: failed to verify certificate` from `buf generate`), so
adding the field would have left the generated Kotlin, TypeScript and Go stale and
`pnpm check:generated` failing. The ticket says to touch contracts "only where necessary", and it
is not necessary: provider identity reaches the phone through the `plugin_id` compatibility claim
that was already there, and the provider-neutral versioned action through SEE-108's existing
`capability_id` + `capability_version`. Documented as the natural next step in
`docs/wiki/execution-providers.md#compatibility`.

**Two numbers are called "contract" and they had to be separated.** Raising `PROVIDER_CONTRACT` to
2 — which SEE-145 genuinely is, since every part of the interface changed — would have made every
published manifest requiring `jupiter.swap` at `1..1` report `PluginIncompatible`. The number a
*server* names is a statement about the agreement with a client, and that did not change, so it
lives in the compatibility table as `LegacyCapability.contract` and stays at 1.

**A new provider needs no row in the compatibility table.** The first cut derived the provider from
a static table only, which would have meant a second provider could not be named by any document
without editing core. `ProviderRegistry.byLegacyPlugin` matches the names a registered provider
declares, so the table is only ever about the two names published before SEE-145 —
`AlternateProviderTest` is the proof, since `example.swap` appears in no table anywhere.

Also worth noting: an owner with no wallet connected has no cluster, so `NETWORK_UNSPECIFIED` skips
the cluster check rather than telling them a venue does not serve their network. And `resolve` now
reads a prediction market when the review opens, so the prediction API is read twice on that path —
once for the owner's benefit, once as `prepare`'s own guard on the bytes.
