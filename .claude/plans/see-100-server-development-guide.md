# SEE-100 — the server development guide

One English page that takes a developer from a fresh checkout to a feed two phones are reading,
without our Firebase credentials, without touching the app, and without holding anybody's keys.

## The decisions this rests on

1. **It is a guide, not a second copy of the reference.** Everything a developer needs already
   exists somewhere — `publisher/.env.example` documents every setting, `docs/integrations/signal-api.md`
   is the API's contract, `docs/wiki/feed-gateway.md` is the gateway's. What does not exist is
   the path *through* them in order, with the one command at each step. So this page is a spine with
   links, and where it repeats something it repeats it because a step needs it in the reader's hand.
2. **It lives in `docs/guides/`.** That directory is where a numbered walkthrough goes
   (`self-hosting.md` is the model), and this reader is a person following steps rather than a
   contributor reading a component's reference. `docs/development/publisher.md` keeps the component
   reference it already is, and the two link to each other.
3. **Every command in it was run.** The record is `docs/testing/see-100.md`: the exact commands, the
   real answers, and PASS / FAIL / NOT RUN per step against a named revision. A guide whose commands
   were written from the source rather than run is a guide with a wrong flag in it.
4. **Stage 7's packaging is extended, never re-documented.** `broadcast/compose.yaml`,
   `broadcast/compose.public.yaml`, `broadcast/compose.push.yaml`, `publisher/compose.yaml` and the
   two Caddyfiles already are the deployment. The guide names them, says which one to run when, and
   points at the self-hosting guide for the parts that are the same as they always were.
5. **The states are the ones the code spells.** There is no `compatible` / `view-only` /
   `unsupported` triple in this app: there is `ServerSupport` with eight cases and a derived
   `executable`. The guide uses the real names, and says which of them a phone still *shows*.
6. **No SDK is claimed.** `docs/wiki/client-plugins.md` already says there is not one and what a
   later extraction (SEE-102) would have to do. The guide repeats the limit rather than softening it.

## The guide (`docs/guides/server-development.md`)

- [x] The architecture first: who creates a proposal, who distributes it, who executes it, and the
      two connection modes with what each one lets a server learn.
- [x] Prerequisites, and the three roles — gateway operator, publisher developer, phone owner.
- [x] Getting a gateway: somebody else's, or your own from Stage 7's compose files with TLS.
- [x] Being registered: `broadcastctl register`, what it prints, what to do with each line.
- [x] Copying a template out, configuring it, and the six settings that have no default.
- [x] Starting it, and the two things it prints: the manifest's publication and the feed reference.
- [x] Connecting the app from that reference, on two devices, with no app change.
- [x] Publishing: the CLI and the API, with the exact request and answer.
- [x] The Prediction template: filters, a cycle now, and what it will not publish.
- [x] The lifecycle: stable IDs, revisions, expiry, updates, withdrawal, idempotent retry, an outage
      at the source, and the authoritative snapshot a phone falls back to.
- [x] Topic push: what the developer authorizes, what the relay sends, and what they never receive.
- [x] A build without the plugin: the states, what is still shown, and no dynamic installation.
- [x] Sandbox and production: separate deployments, and no devnet claim.
- [x] Where a result lives, and the Jupiter handoff.
- [x] Privacy, storage and retention — what is yours to answer for.
- [x] Rate limits and bounds, as numbers.
- [x] Troubleshooting: the refusals, by the code the gateway or the template actually returns.
- [x] What is not here: the SDK, embedding, and the order lifecycle.

## The record (`docs/testing/see-100.md`)

- [x] The revision, the machine, and the versions of everything that ran.
- [x] Every step of the walk, with the command and the answer, marked PASS / FAIL / NOT RUN.
- [x] The six device steps `docs/testing/see-98.md` deferred here, as NOT RUN with what to do.
- [x] What is not covered: Docker (no daemon), a real Firebase delivery, a production promotion.

## Links in

- [x] `README.md`: a section beside Self-hosting, and the guide in the structure table.
- [x] `docs/protocol.md`: from the gateway section.
- [x] `docs/guides/self-hosting.md`: the other kind of server, named where a reader would look.
- [x] `docs/wiki/feed-gateway.md`, `docs/development/publisher.md`,
      `docs/integrations/signal-api.md`, `docs/wiki/copytrading-template.md`,
      `docs/wiki/prediction-template.md`, `docs/wiki/server-manifests.md`.
- [x] `CODEBASE.md` and `AGENTS.md`.
- [x] `docs/changelog/2026-09-18.md`.

## Stale claims to correct while linking them

- [x] `docs/wiki/server-manifests.md`: "this build resolves no feed" (SEE-91 shipped it), and
      `ConnectionStore` at version 2 (it is 3).
- [x] `docs/development/android.md`: the same feed claim, `PluginRegistry.bundled()` (gone in
      SEE-93), and `inspect(subject, prepared)` (it takes the owner's choice too).
- [x] `docs/architecture.md`: the same store version.

## Verification

- [x] The whole walk, end to end, on the real gateway, the pinned broker and real Redis.
- [x] `pnpm check:format`, `pnpm check:lint`, `pnpm check`.
- [x] `pnpm check:publisher`, `pnpm check:broadcast`, `pnpm check:loadtest`.
- [x] `pnpm test:integration --no-android`.

## Review

Done. [`docs/guides/server-development.md`](../../docs/guides/server-development.md) is the guide and
[`docs/testing/see-100.md`](../../docs/testing/see-100.md) is it walked — thirty steps, twenty-six of
them PASS on the real gateway, the pinned broker and real Redis.

Four things are worth recording because they were not obvious when the plan was written.

**The app has no screen for adding a feed, and writing the guide is what found it.** Step 5 was
drafted from the data path, which is complete: `addFeed` resolves a manifest through SEE-91's live
`ConnectFeedGateway`, and the snapshot, stream, signals list, review, execution, record, environment
switch and notification tap route are all there and all covered. Then a grep for `addFeed`'s call
sites outside its own file and its tests returned nothing, and `AndroidManifest.xml` declares no deep
link for `seekervault://feed`. `docs/testing/stage-7-1.md` step 3 and `docs/testing/see-98.md`'s
checklist step 1 both already say "add the feed on the phone". No child of SEE-85 owns the screen. So
the guide says it plainly where a reader would plan around it, and every device step in the record is
NOT RUN **and blocked** rather than merely unrun.

**Every command in the guide was run, and four of them were wrong as first written.**
`publishctl --url … status` — flags come after the command. `POST /v1/signals` with
`idempotency_key` in the body — the key is a header, and the API says so by name. A `Cancel` call
with a `channel` field — there is no such field, because the channel comes from the credential.
`pnpm test:integration -- --no-android` — pnpm passes the literal `--` and `parseArgs` refuses it;
the documented form without it works. Each was plausible from the source and none of them worked.

**A guide inherits the claims of the pages it links.** Four corrections landed in pages this one
points at, each true when written and overtaken by a later ticket in the same stage: "this build
resolves no feed" in two places, the connection store's version in two more, `PluginRegistry.bundled()`
after SEE-93 replaced it, and `inspect(subject, prepared)` missing the choice contract 1 passes. Two
dangling KDoc references to `bundled` remain in Kotlin this ticket does not otherwise touch; they are
named in the changelog for the next change in that package.

**The read API is the best development tool a publisher has, and the guide now says so.**
`FeedService` takes no credential and speaks Connect JSON, so one `curl` shows exactly what a phone
will hold — which turned the lifecycle section from a description of the code into a record of what
was observed, including the withdrawal that keeps its terms and the `no_push` a gateway without a
relay answers.

**Stated limits.** No Docker daemon, so the Compose commands, TLS and the proxy hop are NOT RUN here
and are named rather than folded into a pass. The relay's own behaviour was measured against a
controlled loopback endpoint; Firebase's part and a phone's part were not. Everything run was
sandbox. And the guide is not an SDK guide, because there is no SDK — it says that twice, at the top
and at the bottom.
