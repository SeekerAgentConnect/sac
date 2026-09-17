# SEE-94 — the Jupiter Prediction client plugin

`jupiter.prediction`: a publisher broadcasts a market, the owner picks YES or NO and how much to
stake on their own phone, the order is prepared through Jupiter's own API, the phone resolves and
reads the transaction itself, the wallet signs once, and the owner continues in Jupiter. The app
stops at submission plus links.

## The decision this ticket turns on, and the owner's answer

Jupiter's Prediction order API returns **only** a versioned transaction drawing its accounts from
address lookup tables — verified against the live API, with six plausible parameter spellings for a
legacy transaction all ignored. In the transaction I captured, 35 of the aggregator's 55 accounts
and 4 of the prediction program's 12 sat behind tables, so the phone could read the order's
parameters but not see which accounts move funds.

The owner chose to **resolve the tables through a read-only RPC on the client**, and set the terms:
resolution lives in a shared Solana component that neither couples core to Jupiter nor is
configured by a publisher; tables are discovered from each transaction rather than hardcoded;
ownership, format, indices and the rebuilt account list are all validated; the instructions are then
checked against the reviewed action as before, because resolving a table proves nothing about
intent; and a table that cannot be fetched or validated **blocks signing with a stated reason** —
never a parameter-only review and never a blind signature. The dependency on the chosen RPC's
accuracy is documented rather than described as trustless.

## What the bytes turn out to say, once resolved

Verified against a real order (5 USDC into a live Polymarket-sourced market):

| Instruction | What it is | What the review binds |
| --- | --- | --- |
| Compute budget ×2 | The fee the owner pays to be picked up | Read, shown in SOL |
| Associated token account, idempotent | The owner's own JupUSD account | Payer, owner and mint are the owner's; the address derives from both |
| Jupiter `sharedAccountsRoute` | Funding the deposit by swap | **SEE-93's reader, reused unchanged** once accounts resolve: authority, the owner's own source and destination accounts, both mints, no platform fee, and `in_amount` equal to the owner's chosen deposit |
| The prediction program's order | The order itself | From the instruction's own Borsh data: the market hash, `isBuy`, `isYes`, contracts, the maximum price, the cost, the slippage — cross-checked against the response and against what the owner chose |

Two signature slots, and the second is **already filled**: Jupiter pre-signs with the protocol's own
account, which appears in the order instruction. So the rule is not "nothing else signs" but "the
only empty slot is the owner's, and the owner is the fee payer".

## What is honestly out of reach, and stated as such

- **The market hash is not a plain digest of the market ID** (md5, sha1, sha256 and blake2s all
  disagree), so the market can be *cross-checked* between the response and the bytes but not proved
  from the ID alone. Named as a limit rather than glossed.
- **There is no per-position URL.** `https://jup.ag/prediction/<marketId>` is the platform's own
  route and echoes the market ID into the page, but a market page cannot be verified by HTTP — the
  site answers 200 for a market that does not exist. So the handoff is the market on Jupiter, for a
  market ID that came from Jupiter's own API, and no position URL is invented.
- **The review depends on the configured RPC being honest** about a table's contents. Stated in the
  security page and in the plugin's own words.
- **The app performs no monitoring.** No fill, no position, no settlement, no payout, no P&L.

## Plan

### The shared Solana component (new package `solana/`)
- [x] `solana/SolanaAccounts.kt` — a read-only account reader: one method, `getMultipleAccounts`, over the app's shared HTTP client. No send, no simulate, no subscribe. Configured by the application, never by a publisher.
- [x] `solana/AddressLookupTables.kt` — parse and validate a table account (owner program, state discriminator, the 56-byte header, a whole number of addresses), and `resolve` a decoded message into every account in order: static, then each table's writable, then each table's readonly. One `LookupProblem` per failure.
- [x] `transactions/SolanaTransaction.kt` — the decoder reads a message's table entries and exposes them, behind an explicit opt-in so every existing caller keeps refusing a message it cannot resolve.

### The plugin (`jupiter/`)
- [x] `PredictionTerms.kt` — the market payload a publisher may broadcast: the provider's event and market identity, and nothing that could stand in for the market's own state.
- [x] `PredictionParameters.kt` — YES/NO as a choice, and the stake as an amount in the deposit mint's base units.
- [x] `JupiterPrediction.kt` — the provider adapter: the market's current status and pricing, and the order. Keyless, the same host as the swap.
- [x] `PredictionInstructions.kt` — the order instruction's reader: the discriminator and the Borsh layout.
- [x] `PredictionInspection.kt` — resolve, then check everything, with one finding each.
- [x] `JupiterPredictionPlugin.kt` — the descriptor, `parameters`, `prepare`, `inspect`, `destinations`.
- [x] `res/values/strings_jupiter.xml` — its words.

### Core, additively
- [x] `plugins/ActionPlugin.kt` — `destinations(subject)`, defaulted, so the swap need not implement it; `PluginDestination`.
- [x] `policy/` — `PolicyAction.Prediction`, so the owner's rules have a word for an operation the app can now carry out. Without it every prediction would warn for ever.
- [x] `activity/ActivityRecord.kt` — `ReviewedOperation.references`: the order and position accounts as public identifiers. **No URL is ever stored**; a link is always built at display time from compiled code.
- [x] `operations/` — the result, its explorer link, the identifiers, and the handoff.

### Evidence
- [x] `scripts/capture-jupiter.mjs` — capture a real prediction order and its four resolved tables beside the swaps, with the same independent reader.
- [x] Tests for the resolver: an RPC that fails, a missing table, a malformed one, a table owned by the wrong program, a deactivated one, an index out of range, and a rebuilt list in the wrong order.
- [x] Tests for the plugin: a closed market, a market the publisher named that the provider does not have, every mismatch between the prepared order and what the owner chose, a provider denial, a stale preparation, a wallet rejection, an uncertain dispatch, and sandbox asking nothing.
- [x] `StageBoundaryTest` — `solana/` may dial out and names no provider; nothing in core gains an endpoint; no URL is persisted; the app still schedules no monitoring.
- [x] Docs: a wiki page, the integration page, security, policy, protocol, architecture, android, the stage test record, the changelog, AGENTS.md, CODEBASE.md.
- [x] Deliberate breaks, each failing the check it is meant to.

## Review

Done as planned, with three departures worth naming.

**The resolution moved into `prepare`, not beside it.** My first cut gave the plugin a third public
method, `resolve(subject, choice)`, so a caller could read the chain and then inspect. That is wrong
twice over: the ViewModel would have had to learn a step that exists for one plugin, and a caller
who skipped it would get an inspection of unresolved bytes. So the chain read happens inside
`prepare`, which is already allowed a network, and `inspect` stays what the boundary says it is —
a reading of bytes, from a remembered resolution keyed by the exact bytes it was made for.

**A resolution that fails is a failure, not a finding.** The same first cut turned `SolanaException`
and `LookupException` into findings, which made `prepare` *succeed* while handing back a review
nobody could trust. `inspectPrediction` now lets both through and the plugin converts each into a
`PluginFailure` with the reason in it. That is the owner's stated failure behaviour, and it is the
difference between "here is a review you should not believe" and "this cannot be reviewed".

**The last deliberate break found a test that could not fail.** `StageBoundaryTest`'s new no-URL
rule searched source with comments stripped, and the helper doing the stripping treated `//` as a
comment start wherever it appeared — including inside `https://`. Every address in every file was
therefore truncated to `https:` before the assertion looked for one. Fixed in the helper, which is
shared, so the other checks that use it are now reading what they claimed to read too. The break
then failed the rule it was written for.

### What was proven, and what was not

A real order from the live API, with the real contents of the real lookup tables it names, resolves
offline from the committed fixture: 16 static accounts and 4 tables become 42, every index lands
inside the rebuilt list, and every field of the order instruction matches the provider's own JSON to
the base unit. Every refusal was exercised against data no chain would serve — including the rebuild
order, which has its own case because getting it wrong resolves silently to the wrong addresses.

The live provider was read and one real order requested for an empty wallet, which it refused as
`INSUFFICIENT_FUNDS`: that proves the keyless arrangement serves orders without placing one. Signing
a real order needs a funded wallet and a Seeker, so it stays step 7 of the owner's device run.

Six deliberate breaks, each restored and compared byte for byte; counts and commands are in
[`docs/changelog/2026-09-17.md`](../../docs/changelog/2026-09-17.md).
