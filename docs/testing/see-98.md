# SEE-98 — the direct and gateway flows, data privacy, and existing MCP compatibility

Stage 7.1's acceptance: what was run, what it observed, what it could not answer, and what is left
for the owner to do on a phone. It is not a second implementation of the stage — the components it
exercises are the shipped ones — and nothing here claims a test that did not run.

## The revision tested

| | |
| --- | --- |
| Branch | `superset/feat/see-85`, on base `superset/feat/see-84` |
| Baseline integrated first | `602a269` (the base branch's Render image and `deploy/server/`) and `2bdb1a3` (the SEE-45 review fixes), merged before anything was added — SEE-98's own Baseline section asks for the tree that will ship |
| Stage 7.1 in the tree | SEE-90 to SEE-97, through `4978429` |
| Date run | 2026-09-18 |
| Machine | macOS (Darwin 25.5.0), Apple silicon. **No Docker daemon** was reachable, as in every Stage 7 record |

| Toolchain | Version |
| --- | --- |
| Go (both modules) | 1.27.1 |
| Node | 24.21.0, pnpm 12.3.4 |
| Gradle / JDK for the daemon | 9.7.1 / Temurin 21 |
| Centrifugo (opt-in leg) | v6.9.6, the pinned release, verified against `third_party/centrifugo/SHA256SUMS` |
| Redis (the phone's own stream test) | 8.10.1, built from the release tarball |

**Configurations exercised.** The gateway with a broker and without one; four registered publishers
— two running deployments and two whose whole purpose is to be refused; both templates as
`sandbox`, one of them then started as `production` against the sandbox database; the sidecar with
its MCP adapter on (the default) and a paired phone; two subscribers on the same channels.

## The one command

```sh
pnpm test:integration                                    # what CI can run
SEEKERVAULT_CENTRIFUGO=… SEEKERVAULT_REDIS=… pnpm test:integration   # and the broker legs
```

| Leg | Result | What it was |
| --- | --- | --- |
| The cross-component run | **PASS** — 27 cases in 8 suites, ~22 s | The real gateway, both real template binaries, `broadcastctl` and `publishctl`, two subscribers, the sidecar as its own process and a real MCP agent (`test-agent/src/stage71.acceptance.ts`) |
| The direct-mode acceptance suites | **PASS** — Stage 2's scenario and Stage 4's transfer scenario, unchanged | The existing proof that the private workflow works, re-run with all of Stage 7.1 in the tree |
| The stream, against the pinned broker | **PASS** | The same cross-component run with a real Centrifugo in front of the gateway, on the shipped `broadcast/centrifugo.yaml`: a listener is granted the channels this gateway hosts, and the run's publications reached the broker's channel. Its engine is memory, because one node accepting a publication is what this leg is about; the **two-node Redis path** — recovery, epochs, a slow listener, a node shutting down — is the phone's own `CentrifugoStreamIntegrationTest`, which runs in the leg below when `SEEKERVAULT_REDIS` is given, and did |
| The phone's cross-component cases | **PASS** — 414 cases in 43 classes, 0 skipped | A filtered `:app:testDebugUnitTest`: the feed transport, shared proposals, manifests and plugin compatibility, the wallet's binding, and the direct-mode suites that must still pass |

`pnpm test:integration --no-android` was run as well, and so was the command on a shell with no Go
on its `PATH`: it says which version to install and where the toolchain is documented, and exits 1
rather than reporting a leg it could not build.

Whole-repository checks on the same tree, all passing: `pnpm check` (**497 sidecar cases**, **39
test-agent cases**), `pnpm check:generated`, `pnpm check:format`, `pnpm check:lint`,
`pnpm check:broadcast` (**249 cases in 128 functions**, 3 skipped without the broker binary or a
Firebase credential), `pnpm check:publisher` (**471 in 182**, 4 skipped without the gateway binary
or the live provider), `pnpm check:android` (**1312 cases in 130 classes**, 0 failures, 9 skipped),
and `pnpm test:hello`, `test:queue`, `test:transfer`, `test:updates`, `test:push` and `test:swap`.
No Go source or test changed in this ticket; the three new cases are the phone's.

## Scenario by scenario

The ticket's required scenarios, and what each of them is proved by. "Device" means the checklist
at the end of this page: a laptop cannot answer it, and nothing here pretends otherwise.

| Scenario | What proves it |
| --- | --- |
| **Mixed mode** — a direct connection kept while two gateway feeds are live; private requests still answer their agent, public decisions answer nobody | The cross-component run: an agent asks for a signature over MCP, the phone lists it, approves exactly those bytes and returns the signature, and the agent reads it back — while both feeds are being read. Every request the two subscribers made is a `FeedService` read. Phone side: `ProposalIsolationTest.aPairedSidecarAnswersItsAgentWhileAFeedAnswersNobody` (new), `aFeedIsExcludedFromEveryCallThePhoneMakesToAServer`, `ConnectionManifestTest.aDirectConnectionAndAFeedAreIndependentOfEachOther` |
| **Manifests** — missing plugin, incompatible version or protocol, changed configuration, removed server, attempted endpoint or credential redirection | Cross-component: a manifest naming another gateway is refused (`other_gateway`) and that publisher stays unreadable; no credential, an unknown one and a revoked one are refused identically, while a live one gets past authentication; one publisher publishing on another's channel is refused; `broadcastctl forget` takes the feed and its documents with it; a changed display name moves the settings revision and republishes nothing else. Phone side: `ServerSupportTest` (missing and incompatible plugins, protocol, environment), `ServerManifestTest` (redirection, channel ownership, revisions), `ConnectionManifestTest` (caching and refusal) |
| **Shared content** — the same proposal on two devices, different local parameters, independent dismissals and history across replay and restart | `ProposalIsolationTest.twoDevicesReceiveTheSameTermsAndChooseDifferently`, `whatOneOwnerDoesIsInvisibleToTheOther`, and `twoDevicesKeepTheirOwnDecisionsThroughAReplayAndARestart` (new: the same document arrives again, both processes restart on their own files, and each device still holds only its own half). Cross-component: two subscribers read byte-identical documents and keep their own cursors |
| **Transport** — snapshot, foreground stream, background unary recovery, push tap, delayed, duplicate and dropped events, gateway or publisher restart, expired and cancelled proposals | Cross-component: the snapshot, `known_snapshot_sequence` answering `unchanged`, a stable page walk, one proposal by ID, a duplicate publication that does not move the channel, a withdrawal, an expiry still readable as a fact in the document, a gateway restart on its own database and a publisher restart that republishes nothing. Broker leg: the ticket and the channel's position. Phone side: `ForegroundFeedManagerTest`, `FeedRecoveryTest`, `FeedSynchronizationTest`, `CentrifugoStreamIntegrationTest` (two real nodes: replay, epochs, a slow listener, a node shutting down), `push/Feed*Test` and `FeedNotificationsTest` (the hint, the topic, the alert and its immutable route). The **tap itself** is device step 5 |
| **Wallet** — amount, quote or revision changes require review; a wrong account, network or different bytes cannot reuse an approval; an interrupted signing stays uncertain and is never repeated | Cross-component: an approval whose content hash is over other bytes is refused before any wallet is opened, and the one over the reviewed bytes completes. Direct-mode suites: Stage 4's transfer scenario. Phone side: `ProposalBindingTest`, `OperationViewModelTest`, `PredictionOperationTest`, `MwaWalletAdapterSessionTest`, `WalletRepositoryTest` — including the three ways an interrupted wallet answer stays `Unresolved` rather than being retried |
| **Prediction** — evidence-supported submission status plus transaction and Jupiter links; no order-fill, settlement or payout polling | Cross-component: the provider was asked for a listing and for markets by their identifier, and for nothing else — there is no order, fill, settlement or position route on the stand-in at all, and a request for one would have been recorded. Phone side: `PredictionOperationTest.nothingFollowsTheOrderAfterTheWalletHasSentIt` (new: ten minutes pass and the provider, the chain and the feed are asked nothing further), `theOwnerIsSentToTheMarketAndNeverToAnInventedPosition`, `ExplorerTest`, and `ProposalOutcome` having no confirmed state to show |
| **Privacy** — network captures, gateway and template stores and logs | The sweep below |
| **Environments** — sandbox and default flows spend nothing; production is opt-in and deliberate | Cross-component: both manifests carry exactly `SERVER_ENVIRONMENT_SANDBOX`, both templates' own APIs say `sandbox`, and a production process on the sandbox database is refused by name. Phone side: `OperationViewModelTest.aSandboxFeedRehearsesEverythingAndOpensNoWallet` (no signing, no sending), `DailySpendingTest.aRehearsalInASandboxSpendsNothing`. Production with real funds is device step 10 and step 6 to 9 of `stage-7-1.md`, recorded separately |

## Observed data flows

Every hop this run actually produced, as the run recorded it — not as the code claims.

| From | To | What went | What did not |
| --- | --- | --- | --- |
| The agent (MCP) | The sidecar | The tool call: the owner's wallet address, the message to sign, a note and an idempotency key | — |
| The sidecar | The agent | The request, and afterwards its signature | — |
| The phone | The sidecar | `ListPending`, `SubmitResult` (an approval naming the exact bytes, then the signature) | — |
| A subscriber | The gateway | `GetServerManifest` with a server ID and a revision it already holds; `ListProposals` with a channel, a page size and a snapshot sequence; `GetProposal` with a channel and a proposal ID; `GetStreamTicket` with a channel | No wallet, no amount, no decision, no signature, no device identity, no account — there is no field for any of them, and the recorded traffic has none |
| A subscriber | A publisher | **Nothing.** A subscriber never contacts one: it holds no address for it, and the manifest's reference is the gateway's | — |
| The gateway | The broker | The channel and the serialized `FeedEvent`, with an idempotency key | Nothing about who read it: the gateway has no subscriber to tell it about |
| A template | The gateway | Its manifest and its proposals, with its credential as a bearer token | — |
| A template | The provider | `GET /prediction/v1/events?…` with its own filters, and `GET /prediction/v1/markets/{id}` | No owner, no wallet, no publisher credential, no API key (the keyless path), and no request for an order, a fill, a position or a settlement |

**The sweep.** After the whole run, every file under the gateway's directory and both publishers'
directories — the SQLite databases and every write-ahead log beside them — plus the gateway's log,
both templates' logs, the logs of the three templates that were started only to be refused, the log
of the one that was replaced when its configuration changed, and every request the two subscribers
sent, were searched for: the owner's wallet address, the message
the agent asked to have signed, and the signature the wallet produced. **Nothing was found.**

The same search over the owner's own side — the sidecar's files and the answer the agent read —
finds all three. That positive control is the point: a sweep nobody has watched succeed is not
evidence, and this one fails if the search stops working.

## Unavoidable metadata, recorded separately

None of this is a defect, and all of it is what somebody else can observe anyway.

- **The provider learns what a publisher is interested in.** Its listing request carries the
  template's filters (a venue, a category, a page) and its market requests carry market IDs. That is
  the publisher's own interest, from the publisher's own server, and no subscriber ever touched it.
  It also learns that server's network address, as any HTTP server does.
- **The provider learns what a *phone* is preparing.** For a swap, a quote carries two mints and an
  amount, and a build carries the owner's address because a transaction has to be built for the
  account that signs it (`OperationPrivacyTest` asserts exactly that, and nothing more). This run
  did not exercise the phone's plugin path, which is Kotlin's.
- **A Solana endpoint learns which accounts a review reads**, for a prediction order's lookup
  tables (SEE-94, `docs/security.md#resolving-a-lookup-table`). The build ships no endpoint at all.
- **The gateway learns that somebody read a channel**, with the caller's address for rate limiting,
  and writes none of it down: `privacy_test.go`'s `TestReadingAFeedWritesNothingDown`, and the
  sweep above.
- **Firebase learns a topic was subscribed to**, and the gateway is not told who: topic membership
  is Firebase's, which is why the relay's hint carries a kind and a version and nothing else.

## Unresolved failures and limitations

- **The phone's two-node broker test is timing-sensitive under load.** In the first full run of
  `pnpm test:integration` with both binaries supplied,
  `CentrifugoStreamIntegrationTest.aGapLongerThanTheBrokersHistoryIsAdmitted` failed on its own 20 s
  health deadline — a node it restarts did not come up in time while the Node legs' processes were
  still settling. It passed when the class was re-run on its own, and passed again in the final
  full run of the command, where all seven of its cases and all 414 of the leg's ran green. It is
  recorded here as a **flake under load**, which is neither a pass to be claimed nor a failure of
  the behaviour it checks.
- **No Docker daemon.** Every container check in this repository is NOT RUN, as in
  `docs/testing/stage-7.md` and `stage-7-1.md`. The binaries were run natively instead, which is
  what this command does by design.
- **No Firebase project.** The relay's real send leg stays the opt-in
  `internal/relay/firebase_test.go`, and the push tap on a device is step 5 of the device run.
- **Nothing was signed by a real wallet and nothing was sent to a cluster.** Both publisher
  deployments are sandbox, the wallet is a throwaway key pair, and the chain is a fake. A real swap
  and a real order are steps 6 and 7 of `stage-7-1.md`.
- **The provider's captured answers have their timestamps moved forward** by the age of the capture,
  because a market's close time decides whether it is published at all and a process reads the real
  clock. The shapes, the identifiers and the prices are the provider's own.
- **There is no automated test that taps a feed notification into the app's UI.** What is covered is
  the alert, its exact immutable route, and the hint that schedules one read and passes on only its
  topic; opening the feed from the tap is device step 5. A Robolectric Activity test would not be a
  device pass either way.
- **Jupiter has no test network.** Sandbox is a simulation on the phone, and this repository does
  not claim devnet or testnet parity for either plugin (`docs/wiki/environments.md`).

## The device checklist (for the owner)

Revision-tagged, and separate from every automated result above. None of it can be inferred from a
JVM test, an APK build or this laptop: mark each line **PASS** or **FAIL** when it is run, with the
date, the build and the wallet used, and leave it **NOT RUN** until then.

Tested at revision: the SEE-98 commits on `superset/feat/see-85` — `5da7cc4` and the two that
follow it, this page's own revision tag and the harness's signal cleanup. When the run happens,
replace it with the commit that was actually installed, because a checklist without one is about no
particular build.

| # | What to do | Result |
| --- | --- | --- |
| 1 | Add a paired sidecar **and** two gateway feeds on one phone. Ask the agent for something (`pnpm agent sign …`) and answer it: the agent gets its result | NOT RUN |
| 2 | With both feeds present, act on a feed proposal. The agent hears nothing about it, and `adb logcat` shows the phone calling only the gateway and the provider | NOT RUN |
| 3 | Remove the feeds. The paired sidecar keeps working throughout — the two transports share nothing | NOT RUN |
| 4 | Install a build **without** the swap plugin (or point the feed at a publisher requiring a plugin this build lacks): the feed is readable and says which part is missing, and offers nothing to prepare | NOT RUN |
| 5 | With the push overlay running and the app closed, publish a proposal: the alert arrives, **tapping it opens that feed**, and nothing is prepared, signed or sent by opening it | NOT RUN |
| 6 | Two phones, one signal, two different amounts, as `stage-7-1.md` step 8 describes — then check that neither phone shows anything about the other, and that the publisher's log and database hold neither wallet, neither amount and neither signature | NOT RUN |
| 7 | Force-stop the app and publish again: nothing arrives, which is the documented limitation. Opening the app catches up | NOT RUN |
| 8 | Flight mode for a minute during a feed read, then back: the feed is current again and the app is not stuck | NOT RUN |
| 9 | A **sandbox** feed's rehearsal: the banner, `Simulate`, **no wallet opening**, a `Simulated` record with no signature and no explorer link, and a daily threshold untouched (`stage-7-1.md` step 10) | NOT RUN |
| 10 | **Production, with real funds and a deliberately small amount**: one real swap and one real order (`stage-7-1.md` steps 6 and 7). Record the date, the build, the endpoint, the publisher's server ID and environment, the pair and amount, the market and stake, and both signatures | NOT RUN |
| 11 | Real Firebase: a token refresh, a channel's presentation, and a reboot, as `docs/testing/stage-5-3.md` records them for the direct path | NOT RUN |

## What is not here

- Load, isolation and failover at size: SEE-99.
- The server development guide these steps will live in: SEE-100.
