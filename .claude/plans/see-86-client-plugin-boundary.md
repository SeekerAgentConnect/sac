# SEE-86 — Prepare the existing core for client plugins and future SDK extraction

Stage 7.1, the first child of SEE-85. Branch `superset/feat/see-85`, base `superset/feat/see-84`.

## What this task is, and what it is not

It adds one boundary to the working app: a place a bundled *client plugin* can be registered, so
that SEE-93 (`jupiter.swap`) and SEE-94 (`jupiter.prediction`) can be written without changing core
transport, policy, wallet, or storage code. It is not an SDK, not a second app, not a new financial
operation, and not a migration. The existing message, transfer, sync and notification paths keep
behaving exactly as they do today.

## Where the existing boundaries already are

Read before designing; all of it is reused rather than replaced.

| Concern | Owner today |
| --- | --- |
| Connections, pairing, credentials, answers | `connections/ConnectionRepository.kt`, `connections/storage/` |
| Transport to a sidecar | `connections/ConnectConnectionGateway.kt`, `sync/ConnectUpdateTransport.kt`, `live/ConnectLiveCommandTransport.kt` |
| Foreground/background/push sync | `sync/`, `push/` |
| Policy model and evaluation | `policy/` — can't act, can't speak; `StageBoundaryTest` holds an exact import list |
| Typed facts a policy is applied to | `policy/RequestFacts.kt` |
| Reading a transaction's own bytes | `transactions/` — a parser, holds no key, reaches no network |
| Wallet, one interaction at a time | `wallet/WalletRepository.kt` (`withWallet` holds the one lock), `wallet/WalletAdapter.kt` |
| Manual approval, wallet serialization, local records | `inbox/InboxViewModel.kt`, `activity/ActivityLog.kt` |
| Composition | `SeekerVaultApplication.kt`, `MainActivity.kt` |

## Design

A new package `plugins/`, holding data and pure functions only.

- `ActionPlugin.kt` — the boundary. A plugin declares a `PluginDescriptor`: a stable `PluginId`, a
  contract version, the `OperationId`s it serves, and the `PluginEnvironment`s it serves them in. It
  supplies three behaviors and no others: `parameters` (what the user chooses locally), `prepare`
  (fetch execution data and produce exact bytes), `inspect` (validate those bytes into typed facts).
- `ActionSubject` — what core hands a plugin: connection ID, operation, environment, the structured
  request, and the selected wallet (a public address and network). No credential, no wallet
  authorization token, no gateway handle, no approval, no way to send.
- `PluginRegistry.kt` — the build-time selection. Registration rejects duplicate IDs; resolution
  answers `Supported` or `Unsupported(NoPlugin | ContractUnsupported | EnvironmentUnsupported)`.
- `ActionInspection.kt` — typed inspection facts, reusing `transactions.Verdict` and mapping into
  the existing `policy.RequestFacts`. An unsupported or invalid operation establishes nothing, so it
  maps to `RequestFacts.unread`: `movesValue`, nothing verified, and never `ALLOWED`.
- `operationOf(request)` names the operation at the protocol's own level (`swap`), so core never
  names a provider. A plugin claims `swap`; that Jupiter serves it is the plugin's business.

Core keeps everything it has: policy evaluation, the owner's manual approval, the one wallet lock,
and the local execution record. A plugin returns prepared bytes and an inspection; the approval and
the wallet stay in `InboxViewModel` and `WalletRepository`.

Wiring is the minimum that makes the seam live: `InboxViewModel.factsFor` keeps calling
`policyFacts` for the actions core owns (ack, sign_message, transfer) and asks the registry for
anything else. With the bundled list empty, a swap resolves to `NoPlugin` and produces exactly the
unread facts `policyFacts` produces today — identical behavior, and SEE-93 changes one list.

## Items

- [x] Read SEE-86 and the existing boundaries; record them above
- [x] `plugins/ActionPlugin.kt`: descriptor, ids, environments, subject, parameter form, preparation
- [x] `plugins/PluginRegistry.kt`: registration, duplicate rejection, resolution with reasons
- [x] `plugins/ActionInspection.kt`: typed facts and the `RequestFacts` mapping
- [x] `plugins/PluginOperations.kt`: protocol action → operation, and what core owns
- [x] Compose the registry in `SeekerVaultApplication` and pass it to `InboxViewModel`
- [x] `StageBoundaryTest`: the plugin package can't reach a wallet or a sidecar; core transport
      names no provider and no plugin of its own
- [x] Unit tests: registry, facts mapping, a test plugin registered and rejected, an unsupported
      operation that stays unverified under a policy that allows everything
- [x] `docs/wiki/client-plugins.md`: implemented boundaries versus future extraction work
- [x] `docs/architecture.md`, `AGENTS.md`, `CODEBASE.md`, `docs/changelog/2026-09-17.md`
- [x] `pnpm check:android`, and the acceptance suites the change could touch

## Acceptance, from the ticket

- [x] Existing message, transfer, synchronization and notification tests still pass
- [x] A test plugin can be registered and rejected as unsupported without adding Jupiter-specific
      conditions to core transport code
- [x] Wallet access stays behind the SEE-84 abstraction, one approved interaction at a time
- [x] The app starts on the same persisted data; no new migration
- [x] A short architecture document separates implemented boundaries from future extraction work

## Review

**What landed.** A `plugins/` package of four files, all data and pure functions: the `ActionPlugin`
boundary with its descriptor, subject, typed parameter form and preparation; a `PluginRegistry`
whose resolution names *why* a plugin is missing; typed inspection facts that map into the existing
`RequestFacts`; and the protocol-level operation names. `SeekerVaultApplication` composes
`PluginRegistry.bundled()` — empty at this stage — and `InboxViewModel` asks it for any action core
does not carry out itself.

**Behavior is unchanged.** With no plugin registered, a swap resolves to `NoPlugin` and produces the
same unread facts `policyFacts` produced before, so it is still never `ALLOWED`. Ack, message and
transfer requests never reach the registry at all. No storage format, no proto, no screen, and no
string resource changed, so the app starts on the same persisted data and the approved v4
presentation is untouched.

**What the guards now hold.** `StageBoundaryTest.theClientPluginBoundaryCantReachAWalletOrASidecar`
reads the package's imports against an exact list and fails if its code (comments removed) names a
wallet interaction, a wallet token, a transport, an HTTP client, a store, or an approval.
`coreNamesNoProviderAndOnlyCompositionAndReviewKnowPluginsExist` fails if a provider's name appears
in `connections/`, `sync/`, `live/`, `push/`, `policy/`, `transactions/` or `activity/`, or if any
file outside `plugins/` other than `SeekerVaultApplication.kt` and `InboxViewModel.kt` imports the
package.

**What is deliberately not here.** No plugin: `jupiter.swap` is SEE-93 and `jupiter.prediction` is
SEE-94. No manifest or per-connection mode (SEE-88), no shared proposal (SEE-89), no environment
selection — `PluginEnvironment.Production` is a constructor default in `InboxViewModel` until
SEE-97 makes it the owner's choice — and no UI: a missing plugin has a resolution with a reason, and
no screen reads it yet, because SEE-88 owns the compatibility states the owner sees. No packaging:
`docs/wiki/client-plugins.md` lists the extraction work SEE-102 would do and does none of it, and
the host-app entry point is documented rather than built (SEE-104 owns the floating button).

**Two design calls worth recording.**
1. *Operations are named at the protocol's level, not after a provider.* Core says `swap`; a plugin
   claims `swap`. Naming the operation `jupiter.swap` in core would have put a vendor into
   `actionOwner`, which is exactly what the acceptance forbids.
2. *A plugin reports a mint, not an asset.* The chain is core's — the network the owner's wallet is
   selected for — so `pluginFacts` builds the `PolicyAsset` itself. A plugin cannot name a network,
   which keeps the "a rule is about the chain the owner is on" invariant in one place.

**Verification.** `pnpm check:android` passes: Kotlin formatting, 911 Android unit tests, 0
failures, Android lint, and the debug and instrumentation APKs. `pnpm check`, `pnpm check:generated`,
`pnpm test:hello`, `pnpm test:queue`, `pnpm test:updates` and `pnpm test:push` all pass; no sidecar,
proto, or generated file was touched. The baseline needed `pnpm install --frozen-lockfile` first —
without `node_modules` the real-sidecar tests fail with "run pnpm install first", which is an
environment gap and not a code failure.

Three deliberate breaks, each under a time limit, each file restored from a copy and compared:

| Break | Failed |
| --- | --- |
| `plugins/PluginRegistry.kt` importing and holding a `WalletRepository` | `theClientPluginBoundaryCantReachAWalletOrASidecar` |
| A `jupiterFeed()` function added to `sync/PushSynchronization.kt` | `coreNamesNoProviderAndOnlyCompositionAndReviewKnowPluginsExist` |
| `pluginFacts` reporting `movesNothing` for an unserved operation | `PluginFactsTest` (2 cases) and the new `InboxViewModelTest` case |

**Caveats.** `PluginRegistry.bundled()` returns an empty list, which is honest but untested against
a real plugin; `plugins/TestPlugin.kt` in the test sources is what exercises the boundary until
SEE-93 lands one. Preparation and inspection are declared and unit-tested through that fake, but no
production path calls them yet — `InboxViewModel` consults the registry for facts only, because
approving a plugin-prepared transaction is SEE-93's work. **Physical-device evidence: NOT RUN**, and
there is none to run: the change adds no screen and no operation and alters nothing the phone shows.
The Stage 5.3 device record stands as it was.
