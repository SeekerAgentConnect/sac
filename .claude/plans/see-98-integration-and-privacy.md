# SEE-98 — validate the direct and gateway flows, the privacy claim, and MCP compatibility

Stage 7.1's acceptance, not a second implementation of it: one command that runs the real
components against each other, a privacy sweep of what that run actually wrote, and a report that
says what a laptop could not answer.

## The decisions this rests on

1. **The baseline is the tree that will ship.** SEE-98's own Baseline section says to integrate the
   owner's pushed work rather than validate what this branch happened to fork from. Two commits
   were merged before anything else: the base branch's Render image and `deploy/server/`, and the
   SEE-45 review fixes — `9b644e8` and `2feee27` on `master`, which the branch was later rebased
   onto. Every number in the report is against a tree that carries both, and the report names the
   revision.
2. **One command, three legs, and it says which ones ran.** `pnpm test:integration` builds the five
   Go binaries and runs the cross-component suite; the broker leg is opt-in on the pinned Centrifugo
   and Redis binaries; the phone's own suite stays `pnpm check:android`, and this command runs the
   cross-component subset of it. A command that quietly skips the interesting half is how
   "integration tested" turns into a claim.
3. **The harness's subscriber is a plain HTTP client, deliberately.** Nothing in this repository
   compiles a feed client outside the phone, and `publisher/internal/publish/gateway_test.go`
   already reads a feed by posting JSON to the Connect path for exactly that reason. A generated
   client here would be a fourth implementation of the contract to keep in step.
4. **Deterministic provider data is the committed captures, served back.** The seven real answers in
   `publisher/internal/jupiter/testdata/` are what the template's own client is tested against;
   serving them to the template *binary* over `PREDICTION_PROVIDER_URL` is the one step missing from
   a binary-level deterministic run. No new mock trading platform and no invented fills — the ticket
   excludes both.
5. **Deterministic wallet fixtures are the direct path's own.** A throwaway Ed25519 key pair and the
   fake JSON-RPC in `sidecar/src/testing/` produce the same signature a wallet app would over a
   chain the test controls. Nothing reaches a cluster, and the feed path's wallet stays the phone's.
6. **Privacy is proven by sweeping what the run wrote, not by reading the code.** Every database and
   every log line the gateway and both templates produced, searched for the address, the amount, the
   decision and the signature the run used — plus the harness's own record of every request it sent,
   so the claim is about observed traffic. Unavoidable provider and RPC metadata is recorded
   separately rather than defended.
7. **A report that cannot claim an unrun test passed.** The device half is a revision-tagged
   checklist of PASS / FAIL / NOT RUN, in its own section, and every automated line names the check
   that produced it.

## The baseline

- [x] Merge the base branch's tip (`origin/superset/feat/see-84`, now `9b644e8` on `master`),
      resolving the append-only documents.
- [x] Merge the SEE-45 review fixes (`origin/superset/feat/see-45`, now `2feee27` on `master`).
- [x] `pnpm check`, `pnpm check:broadcast` and `pnpm check:publisher` pass on the merge before
      anything is added.
- [x] The report records the revision, the toolchain versions and every configuration tested.

## The command

- [x] `scripts/test-integration.mjs`, and `pnpm test:integration`: build `broadcast`,
      `broadcastctl`, `copytrading`, `prediction` and `publishctl` into a temporary directory, run
      the cross-component suite against them, and print which legs ran and which were skipped.
- [x] The broker leg runs when `SEEKERVAULT_CENTRIFUGO` (and, for the phone's own two-node test,
      `SEEKERVAULT_REDIS`) name the pinned binaries, and says so when they do not.
- [x] The phone's cross-component cases run through a gradle filter, and `--no-android` skips them
      for a machine with no SDK. A machine with no Android SDK at all is reported NOT RUN rather
      than failed, because `pnpm check:android` is where that toolchain is required.
- [x] It needs no network, no Docker daemon, no credential and no funds.

## The cross-component suite

`test-agent/src/stage71.acceptance.ts`, with the real gateway, both real template binaries, a
deterministic provider, two subscribers, the real sidecar and the real agent CLI.

- [x] **Mixed mode.** A paired sidecar and two gateway feeds at once: the agent's private request
      still returns its result to the agent, and a subscriber's read of a feed reaches no publisher.
      The read listener serves no publisher procedure.
- [x] **Manifests.** A manifest naming another gateway, a changed environment set, a wrong
      credential, a revoked credential, a forgotten server, and one publisher publishing on
      another's channel.
- [x] **Transport.** The initial snapshot, `known_snapshot_sequence` answering `unchanged`, a stable
      page walk, one proposal by ID, a duplicate publication, a cancellation, an expiry, a gateway
      restart on the same database and a publisher restart on the same database.
- [x] **Two devices.** Two independent clients read byte-identical documents and keep their own
      cursors; neither read is visible in the other's answers, in the template, or in the gateway.
- [x] **Wallet.** The direct path's approval is bound to its exact bytes: a changed amount, a
      changed account and a changed network cannot use it, and an interrupted submission stays
      uncertain rather than being repeated.
- [x] **Prediction.** What the template published carries no fill, settlement or payout, and the
      provider was asked only for a listing and for a market by its identifier.
- [x] **Privacy.** The sweep, over both stores, every log and the harness's own request record.
- [x] **Environments.** Both deployments run as sandbox, a production restart on a sandbox database
      is refused, and nothing in the run reaches a wallet send path.

## Tests

- [x] Every scenario above is an assertion, not a printed line.
- [x] Gaps found in the existing suites are filled where they belong — the phone's in Kotlin, the
      sidecar's in TypeScript — rather than only in the new harness.
- [x] Deliberate breaks: each new check fails for the reason it exists, with every file restored
      byte for byte.

## Documentation

- [x] `docs/testing/see-98.md`: the acceptance report — what ran, the observed data flows, the
      device checklist, and the limitations.
- [x] `docs/development/integration.md`: the command, what it builds, what is opt-in, and how to run
      the broker leg.
- [x] `docs/testing/stage-7-1.md`: SEE-98 is no longer only "not covered here".
- [x] `README.md` commands and status; `AGENTS.md` checks; `CODEBASE.md` files and commands.
- [x] `docs/changelog/2026-09-18.md`.
- [x] `.claude/tasks/lessons.md`, if anything here was learned the hard way.

## Verification

- [x] `pnpm test:integration`, with and without the broker binaries.
- [x] `pnpm check`, `pnpm check:generated`, `pnpm check:format`, `pnpm check:lint`.
- [x] `pnpm check:broadcast`, `pnpm check:publisher`, `pnpm check:android`.
- [x] `pnpm test:hello`, `test:queue`, `test:transfer`, `test:updates`, `test:push`, `test:swap` —
      the direct-mode suites the ticket requires to still pass.

## Review

Done, and the shape held. Three things are worth recording because they were not obvious when the
plan was written.

**The baseline was real work, and it came first.** Two commits existed upstream that this branch did
not have: the base branch's own tip (the Render image and `deploy/server/`) and the SEE-45 review
fixes — `9b644e8` and `2feee27` on `master`, which the branch was later rebased onto. Validating a
tree nobody will merge into would have been the wrong tree, so both were merged before anything was
added, every conflict was in an append-only document, and `CODEBASE.md`'s two contested rows were
resolved by taking each side's own edit rather than either side whole. Every number in the report is
against a tree that carries both.

**The harness's two hard-won rules are about the harness being a server.** `publishctl poll` makes
the template run a cycle, the cycle calls the provider, and the provider is a Node server inside the
test process: the first version used `spawnSync` and deadlocked all three, reported as a provider
timeout against a server that was visibly listening. And a Node server closes an idle keep-alive
socket after five seconds, which a Go client can write into and then wait for headers that never
come — so the provider answers `Connection: close`. Both are now comments in the code, because the
next person to add a case will otherwise hit them again.

**What the breaks found.** Nine breaks, and seven failed a named check of this ticket's immediately.
The other two were the point of running them:

- Removing the publisher database's environment stamp left a production process happily running on a
  sandbox database — and the check that says it must refuse **hung the suite for five minutes**
  instead of failing. `run()` now takes a bound, and the test asserts the bound did not fire; the
  same break then failed in fifteen seconds with the right message.
- Making the app read the feed again immediately after the wallet answered failed the neighbouring
  older test but **not** the new "nothing follows the order" one, because that one snapshotted its
  counts after the approval rather than before it. It now brackets the approval as well as the ten
  minutes after it, and the break fails it.

One break was my own mistake rather than a weak test, and it is worth the line: a break in a shared
Go package has to be rebuilt into **every** binary that embeds it. Patching `store.Forget` and
rebuilding only `broadcast` left `broadcastctl` — the binary that actually runs `forget` — untouched,
so the run passed and looked like a hole in the test. Rebuilt properly, it failed.

**Stated limits.** The phone's own two-node broker test flaked once under load on its 20-second
health deadline and passed on its own; it is recorded as a flake, not as a pass. Every Docker-daemon
check is NOT RUN, as in SEE-95 to SEE-97. No Firebase project was available. Nothing was signed by a
real wallet or sent to a cluster. And there is no automated test that taps a feed notification into
the app's UI — the alert, its exact route and the hint that schedules one read are covered, and the
tap itself is step 5 of the device checklist in `docs/testing/see-98.md`.
