# SEE-93 — the Jupiter Swap client plugin

`jupiter.swap`, the first real client plugin, plus the path a shared proposal actually travels on
this phone: a publisher broadcasts a signal, the owner picks their own amount, the plugin gets the
route from Jupiter, the phone reads the bytes back itself, the owner approves, and the wallet signs.

## What the shape of this is decided by

**The phone reaches no chain, so a swap is approvable only if its bytes are readable offline.** A
Jupiter v0 route transaction loads its accounts from address lookup tables; offline, the phone
cannot know which accounts those indexes name, and the existing decoder refuses such a message
outright (`DecodeFailure.AddressTableLookup`). Jupiter's answer carries
`addressesByLookupTableAddress`, but that is *Jupiter's account of the bytes*, and this app has
never treated a builder's description as evidence about what it built. So the plugin asks for
`asLegacyTransaction=true`, and what comes back is a message whose every account is in the message.
Verified against the live API: SOL→USDC, USDC→SOL, USDC→JUP for a wallet with no output account,
and the non-shared `route` variant — every one legacy, `addressesByLookupTableAddress: null`, every
byte accounted for.

**What a review of a swap can honestly claim.** Six instruction kinds appear, all readable: two
compute-budget settings, a System transfer that wraps SOL into the owner's own WSOL account, token
`SyncNative`, token `CloseAccount` that unwraps it back to the owner, an idempotent
associated-account creation for the owner, and Jupiter's `route`/`sharedAccountsRoute`. Inside the
route instruction the phone reads the fields that bound the owner's outcome — the authority, the
source and destination token accounts, the exact input amount, the quoted output, the slippage, the
platform fee — and does **not** read the route plan, which names the pools the aggregator hops
through. That is stated rather than hidden: the route plan cannot change any of the six things
above, because the Jupiter program checks them itself and fails the whole transaction if the output
falls below the bound. The phone verifies the bound; the chain enforces it.

**The publisher and the gateway learn nothing.** The amount, the wallet, the transaction and the
signature go to Jupiter and to the wallet, and nowhere else. A feed has no upload path at all
(SEE-89), and a test captures every byte the phone sends the gateway to prove it.

## Decisions taken deliberately

- **`PLUGIN_CONTRACT` stays 1.** Nothing has ever been written against it: the app has carried no
  plugin, no publisher exists yet (SEE-95/96), and the manifests that name `jupiter.swap` at
  `1..1` are this stage's own fixtures. SEE-93 *defines* contract 1 rather than changing it. The
  next stage to touch `ActionPlugin` after a publisher exists bumps it.
- **`ActionSubject` gains a proposal's terms, and its request becomes nullable.** A broadcast
  proposal is addressed to nobody and carries no `ActionRequest`; handing a plugin an empty one
  would be a request that isn't one.
- **The build's plugin list moves to the composition root.** `PluginRegistry.bundled()` could only
  ever list plugins that need nothing, and a real plugin needs an HTTP client. `plugins/` stays
  data and pure functions; `SeekerVaultApplication` names the plugins, as it already names every
  other dependency.
- **Direct routes only** (`onlyDirectRoutes=true`), and the route plan's single leg is *verified*
  in the bytes, not merely requested. It narrows which pairs work and sometimes costs a better
  price; a transaction the phone can read whole is worth more.
- **Keyless `lite-api.jup.ag/swap/v1`** — no secret in the APK, no proxy of the owner's data. The
  documented keyless allowance is 0.5 req/s, 30 per minute, in a 60-second sliding window.
- **A direct-mode swap request stays unexecutable.** Bundling the plugin changes nothing for
  `ActionRequest`: core never prepares a swap, so `pluginFacts` still establishes nothing and the
  verdict can never be `ALLOWED`. Stage 6 is not resurrected.
- **Sandbox asks the provider nothing.** It refuses to prepare, before any network call, so no
  environment that says it performs no purchase can make one.

## Plan

### The contract (additive)
- [ ] `plugins/ActionPlugin.kt`: `ActionSubject.request: ActionRequest?` and `terms: Map<String, String>`; `PluginFailure` with a stable code, a string resource and optional provider detail; `ParameterKind.Amount.least`, `ParameterKind.Count.initial`; why the contract stays 1.
- [ ] `plugins/PluginRegistry.kt`: drop `bundled()`, and say where the selection lives now.

### The plugin (`jupiter/`, a new package)
- [ ] `jupiter/SwapTerms.kt` — the supported spot-swap payload, one `SwapTermProblem` per rule: exact base58 mints (never a ticker, never "native BTC"), decimals, optional unverified symbols, the publisher's slippage ceiling, optional input bounds.
- [ ] `jupiter/JupiterProvider.kt` — `quote`/`build`, the answers as data, `ProviderProblem` per failure; `HttpJupiterProvider` over the shared OkHttp client, `org.json`, no new dependency.
- [ ] `jupiter/SwapInstructions.kt` — the Jupiter route readers (both discriminators, both account layouts, the trailing Borsh args) and the two token instructions the transfer path doesn't read.
- [ ] `jupiter/SwapInspection.kt` — `inspectSwap`: every check, one `SwapFinding` each, facts read from the bytes alone.
- [ ] `jupiter/JupiterSwapPlugin.kt` — the descriptor, `parameters`, `prepare` (quote → build → refuse a simulated failure), `inspect`.
- [ ] `res/values/strings_jupiter.xml`.

### The path the owner walks (`operations/`, a new package)
- [ ] `operations/OperationViewModel.kt` — the feed's proposals, the owner's parameters, prepare and inspect, the owner's rules over the plugin's facts, the binding, the one wallet call, the outcome.
- [ ] `operations/ProposalsScreen.kt` and `operations/ProposalReviewScreen.kt` — the same shape as Pending requests and Request details, reusing `PolicyReview`.
- [ ] `operations/OperationText.kt`, `res/values/strings_operations.xml`.
- [ ] `ConnectionDetailsScreen.kt` — a feed connection's entry to its signals.
- [ ] `SeekerVaultApp.kt`, `MainActivity.kt`, `SeekerVaultApplication.kt` — the routes, the ViewModel, the plugin and the provider; a notification tap opens the proposal it is about.

### Evidence
- [ ] `scripts/capture-jupiter.mjs` + `fixtures/jupiter/swaps.json` — the four real transactions, recapturable.
- [ ] `jupiter/` tests: the terms, the instruction readers against the captured bytes, every tampering the inspection must refuse, the plugin over a fake provider, the HTTP adapter over MockWebServer, and one opt-in live quote that spends nothing.
- [ ] `operations/` tests: two devices and two amounts from one document, a stale quote, a rejected signing, post-dispatch uncertainty, one execution ever, and the captured gateway traffic.
- [ ] `StageBoundaryTest` — `jupiter/` is the only place the provider is named, and it reaches no wallet, store or approval either.

### Documentation
- [ ] `docs/wiki/jupiter-swap.md`, `docs/integrations/jupiter.md` (new).
- [ ] `docs/wiki/client-plugins.md`, `docs/wiki/shared-proposals.md`, `docs/security.md`, `docs/policy.md`, `docs/protocol.md`, `docs/architecture.md`, `docs/development/android.md`, `docs/testing/stage-7-1.md`, `docs/changelog/2026-09-17.md`.
- [ ] `AGENTS.md`, `CODEBASE.md`, `README.md`, `package.json`.

### Verification
- [ ] `pnpm check:android`, `pnpm check`, `pnpm check:broadcast`, `check:generated`, `check:format`, `check:lint`, every acceptance suite.
- [ ] Deliberate breaks, each failing the check it is meant to.

## Review

### Changes against the plan

1. **`inspect` takes the owner's choice.** The plan listed the subject's terms and `PluginFailure`;
   while writing the inspection it became clear that "do these bytes do what was chosen" cannot be
   answered without the choice, and that the choice should be *core's* copy rather than the
   plugin's memory of it — otherwise a plugin that asked its provider for the wrong thing would be
   trusted about it. One more parameter, and the part of contract 1 that matters most.
2. **`ParameterForm` can say why there is nothing to collect.** An empty form and an unreadable
   document are opposite situations, and the owner is owed which term was the trouble.
3. **`ActionInspection.details`.** The least a swap pays out has no field in `RequestFacts` and must
   not have one — it is not something a rule should be applied to. Labelled values let the plugin
   show it and keep the screens provider-agnostic, which is what SEE-94 will need.
4. **`operations/` takes the connection list, not the repository.** Written that way for the tests
   at first, kept because it is the more honest statement: a feed has no server-facing half.
5. **The decoder now reads a lookup table's entries** (see the lesson). Not planned, ten lines, and
   it makes the message the owner sees accurate for SEE-93's own documented limit.
6. **`ActivityLog.record(ActivityRecord)` consults the `reviewed` note**, so an operation's record
   carries the assessment the owner read. The alternative was duplicating SAW-028's mapping, and
   the mapping is now one function with two callers.

### What went wrong on the way

1. **The fake provider recursed on itself** and looked like a hang. Lesson recorded.
2. **The view model's scope needed driving**, and then needed *polling* for the test that makes real
   HTTP calls. Lesson recorded.
3. **`minimumOut` was wrong at first** — I scaled the quoted amount instead of subtracting the
   floored slippage. Four real captures disagreed with it to the base unit, which is exactly why
   the comparison against the provider's own threshold is worth making rather than skipping.
4. **The screens' assertions failed on visibility**, not on content: most of a long review starts
   below the fold, so every assertion scrolls first.

### Caveats, honestly

1. **One real swap is NOT RUN.** No mainnet funds and no wallet here. Everything up to the
   signature ran against the live provider; step 6 of `docs/testing/stage-7-1.md` is the rest.
2. **`asLegacyTransaction` is a dependency that could be withdrawn.** The failure direction is safe
   — the plugin stops preparing — and `JupiterLiveTest` is the early warning, but there is no plan
   B that does not need either an RPC endpoint or a change of principle.
3. **The route plan is not read.** Argued for rather than hidden: the program checks the five things
   that bound the owner's outcome, and the phone checks those five. It is still the one place where
   the review's coverage is narrower than the transfer path's.
4. **Direct routes only**, so some pairs will have no route at some sizes.
5. **Nothing follows a swap to the chain.** Submitted is not confirmed, and no screen pretends
   otherwise.
