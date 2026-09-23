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
- [ ] `ExecutionProviderId`, `ActionId`, `PROVIDER_CONTRACT`, `ProviderCapabilities`,
      `ActionCapability` (action, schema versions, Solana networks, deposit assets, limits).
- [ ] `ActionOperation` — what core hands a provider: connection, action + schema version, provider,
      environment, network, wallet, request, and a **typed** `ActionPayload`.
- [ ] `ActionPayload` sealed: `Swap`, `PredictionBuy`; each exposes an `Instrument`.
- [ ] `ExecutionProvider`: `capabilities`, `inputs`, `resolve` (suspend, live info + constraints),
      `prepare`, `inspect`, `destinations`, optional `status`.
- [ ] `ProviderRegistry.resolve(provider, action, schemaVersion, network, environment)` with one
      unsupported reason per way it can fail.
- [ ] `Compatibility.kt`: the explicit legacy table `jupiter.swap ↔ (jupiter, swap, 1)` and
      `jupiter.prediction ↔ (jupiter, prediction.buy, 1)`, plus the legacy operation names.

### 2. Provider-neutral action payloads — `plugins/actions/`
- [ ] Move `SwapTerms`/`PredictionTerms` parsing out of `jupiter/` (they are the action's schema,
      not Jupiter's API) and make the messages neutral string resources.
- [ ] Deposit-mint set and minimum order move from the payload parser into Jupiter's **capabilities**,
      so an unsupported deposit asset is a provider answer rather than a schema rule.

### 3. Jupiter adapter — `jupiter/JupiterProviderAdapter.kt`
- [ ] One `ExecutionProvider` serving both actions; swap and prediction preparation, provider API
      access, instruction readers and the handoff link stay inside it.

### 4. Core
- [ ] `ExecutionBinding` gains `provider`, `action`, `schemaVersion`, `instrument`; `bindingProblem`
      refuses each of them.
- [ ] `Proposal` carries `action` + `provider` (+ the publisher's legacy plugin claim).
- [ ] `ProposalStore` v4 writes both forms and reads v1–v3 rows through the compatibility table.
- [ ] `ActionCapability.execution_provider` added to `request/v2` for explicit new-format emission.

### 5. Tests
- [ ] Registry: unsupported provider / action / version / network / environment / contract.
- [ ] Compatibility: legacy and new-format proposals resolve to the same Jupiter behaviour.
- [ ] Binding: provider, market, side, amount, owner, network, environment, bytes each invalidate.
- [ ] A **test-only** alternate provider registered beside Jupiter, driving the same core flow;
      an assertion that the bundled production registry carries Jupiter alone.
- [ ] Upgrade: legacy store rows keep their pending items, records and one-attempt behaviour.

### 6. Docs
- [ ] `docs/wiki/execution-providers.md` (the extension contract + how to add a provider).
- [ ] Update `client-plugins.md`, `jupiter-swap.md`, `jupiter-prediction.md`, `common-requests.md`,
      `integrations/jupiter.md`, `docs/development/android.md`, changelog, `CODEBASE.md`.

## Review

(filled in at the end)
