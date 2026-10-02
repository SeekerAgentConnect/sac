# Lessons Learned

## Branching

- **Auto-named worktree branches:** workspace tooling may check out a prefixed branch (e.g. `superset/develop`). Ignore it — switch to the plain branch the user names (`git switch -c develop --track origin/develop`), commit and push only there, and delete the prefixed local branch. Never push the prefixed branch.

## Tooling

- **Report the check's own exit code.** In `check; tail log`, the status is `tail`'s, and it made a failed Gradle build look like exit 0 (SEE-16). Capture `code=$?` right after the check, end with `exit $code`, and read the log's `BUILD` line before recording a PASS.
- **A pushed commit isn't done until its CI is green.** SEE-16's emulator job failed on the runner's disk space, and nobody noticed until the next ticket. After each push, watch the run (`gh run watch <id> --exit-status`) before moving the ticket on. Don't rerun an older run on the same branch: `cancel-in-progress` would cancel the newer run.
- **Compare a failing CI run with a passing one before changing the workflow.** The first fix for the emulator job, `disk-size`, rested on a guess, and it didn't work (SEE-17). The real cause took two steps to find. First, the log's own "need 7372.80 MB" line. Then a comparison with the passing runs, which had the same image and emulator but no Gradle cache restored. Read the failing step's full message and diff the job logs first.
- **Worktrees have no `apps/android/local.properties`.** Export `ANDROID_HOME=~/Library/Android/sdk` for `pnpm check:android` rather than creating the ignored file. Put Node 24.21.0 first on `PATH` (`~/.nvm/versions/node/v24.21.0/bin`), because pnpm refuses any other version.
- **Give deliberate breaks a time limit, one break at a time.** SEE-20's break run had no limit, hung in `InboxTest`, and the owner had to stop it. Run each break under a limit (`perl -e 'alarm shift; exec @ARGV' 600 …`), and restore the file from a copy, checked with `cmp`. Record a break that times out as NOT RUN.
  - **Back up every file the run will touch, including the new ones, before the first break.** In SEE-92 a file added by the same task was broken before it had a copy, and `git checkout` cannot restore an untracked file — the edit had to be reversed by hand. Take the copies in one pass up front, from a tree whose checks are green, and `cmp` each file afterwards.
- **A time limit through `perl -e 'alarm shift; exec @ARGV'` can't run a shell function, and a failed `exec` exits 0.** The PR #3 review's first Android breaks called a zsh function that way. Gradle never started, and every break looked like "not caught". Pass a real executable, and write `exec @ARGV or die`. A break whose log is empty didn't run.
- **A test that fails under load has a race: find it, don't retry it.** During the PR #3 review, `ConnectionsActivityTest` failed under load locally, then in CI.
  - The cause: it ran the repository on `Dispatchers.IO`, which the Compose rule doesn't wait for, so an assertion could run before a disconnect reached the screen.
  - Ten `yes > /dev/null` processes reproduced it in 2 of 6 runs.
  - A tree dump on failure, `printToString(Int.MAX_VALUE)`, showed the screen settling just after the assertion ran.
  - Activity tests now run the repository on `Dispatchers.Unconfined`. The fix passed 10 of 10 runs under the same load.
- **Guard every writer, not only the one that was reported.**
  - The PR #3 review's fetch-after-removal fix missed the `SubmitResult` replies, which wrote a captured answer back after `remove`.
  - When one writer needs a guard against a removal, list every write of that state first: here `settle`, the retry handler, and `markRevoked`.
  - Reread existence under the same lock right before each write.
  - Write from the stored state, never from a copy captured before a suspension.
- **Tool inputs turn `\uXXXX` into the character itself.** To keep an escape in a source file, write `\u{301}` in TypeScript or build the string from code points in Kotlin (`String(intArrayOf(...), 0, n)`), then check the code points with a script. That matters wherever NFC and NFD must differ.

## Review findings

- **"Independently verified" has to name what enforces the fact, not what implies it.** The PR #4
  review caught the phone treating a derived associated-token-account address as proof of who owns
  that account. Address derivation is a *necessary* condition; authority can be changed afterwards
  with `SetAuthority`. When a claim can't be checked where it is made — the phone reaches no chain
  by design — the fix is to move the check to something that does enforce it at execution time, not
  to loosen the claim. Ask, for each fact a review presents: which program, or which byte, makes
  this false if it isn't true?
- **State that outlives the process must be re-bound to its environment on every restart.** The
  database survives a restart and `SOLANA_RPC_URL` does not, so stored transfers could be settled
  against a cluster they were never sent to — and another cluster's silence plus its block height
  reads exactly like "expired, nothing spent". Wherever a stored record names a network, a chain, a
  tenant, or an account, re-verify that binding before reading anything from the environment as
  evidence about the record.
- **A transport failure is not an answer.** Deleting local state because a call threw treats
  "unreachable" as "refused". Split failures into *the peer answered*, *the call never left here*,
  and *nobody knows* — and for the third, keep the state and reconcile it with a **read**, never by
  repeating the write.
- **A check and the act it guards must be inside the same lock.** The sidecar checked a blockhash
  window when it accepted an approval, and the phone then waited on its wallet lock before calling
  the wallet. Whenever a guard and the guarded action are separated by a wait, the guard has to be
  re-applied on the far side of it — and the recovery you want decides which side the lock goes on.
- **An existing script is not a running check.** `pnpm test:transfer` existed for a whole stage
  without any CI job invoking it. When a task adds a command, add it to the workflow in the same
  change, and read the workflow to confirm rather than assuming the suite name is picked up.
- **An external component's answer is not evidence until this side checks it.** `MwaWalletAdapter` took 64 bytes from the right account as a signature. The sidecar verifies, so a wallet that answered with anything left a request PROCESSING for ever: an outcome the far side refuses, stored where the first outcome stands, is a permanent stuck state, not a transient error. Wherever one side validates what the other stores immutably, apply the same validation before storing — and ask what happens on the *third* retry, not the first.
- **A rule stated for both paths has to be read by both paths.** `AGENTS.md` and the lifecycle notes both said the wallet is reached only after the sidecar accepts the approval; the transfer path enforced it with a commit, the message path checked only that its answer was still on its way. When a rule names two call sites, grep for both and compare them line by line — the one written second usually has the guard, and the one written first usually has the prose.
- **Two designs can both be documented and still disagree: find which one the tests encode.** Generalizing "an approval the sidecar didn't take is deleted" from transfers to messages broke three tests whose names *state* the opposite intent for messages (the stored-first outbox retries them). The prose rule was about opening the wallet, not about deleting the answer. Before widening an invariant, run the suite for the paths it would now cover, and read the failures as the design's own account of itself.
- **A rename that strengthens a claim has to be re-answered at every call site, not just at the declaration.** `createsRecipientAccount` became `ensuresRecipientAccount` — from "an associated-account instruction is present" to "the chain vouches for the recipient's account for the mint the request names" — and one fixture kept the old `true`, which turned red on master, in the one case where the two questions have different answers. When a field's meaning narrows, grep every producer of it and ask the new question of each; better, derive the fact from the same value the related facts come from, so a caller can't answer the old question by hand.
- **A token the other side may replace has to be read from the same call.** `MwaWalletAdapter.signMessage` threw away the `AuthorizationResult` that Mobile Wallet Adapter hands the `transact` block, so a wallet that reauthorized this app kept a token the phone never stored. Whenever a protocol can hand back a fresh credential, capture it where the call gives it — inside the session, before the operation that may fail — rather than from the success path alone, which loses it on a decline.
- **A classification by hand-written ranges is a list of what came to mind.** `isHidden` listed the invisible characters someone thought of, and U+061C and every tag character walked straight past it. Ask the platform's own tables (`Character.getType`) and iterate code points, not chars, so a surrogate pair stays one character and a supplementary one can't slip through as two halves.
- **Anything a remote side validates, bound before storing it.** A wallet's own error message went into a result detail with no limit, and a detail over the protocol's 1024 bytes would have been refused for ever, since a stored signing outcome is never replaced. Clamp text you didn't write to the contract's limit at the point it enters your own state.
- **Publish on the event, not on the next lifecycle callback.** The Wallet screen told a newly paired sidecar about the wallet only after the app was hidden and shown again, although pairing happens in the foreground. When state has "who has been told" bookkeeping, drive it from the change itself.

- **A Kotlin property and a method with the same name make an accidental recursion that looks like a
  hang** (SEE-93). A fake provider had `var quote: (…) -> JupiterQuote` beside
  `override suspend fun quote(…)`, and `return quote(terms, amount, slippage)` inside the override
  resolved to the *member*, not the property: the test spun, the state never advanced, and the
  symptom was "nothing was prepared" rather than a stack trace. Name a stand-in's answer field
  something the method is not — `answersQuote`, `answersBuild` — rather than relying on resolution
  order.
- **A view model's own scope has to be driven in a test, and how depends on what it waits for**
  (SEE-93). `Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))` is enough when everything in
  the launched coroutine is synchronous or on `Dispatchers.Unconfined`. It is *not* enough when the
  coroutine makes a real call — a socket, a real HTTP request — because the suspension is not the
  scheduler's to advance: poll the state the app publishes, with a timeout, instead of assuming the
  step finished. A test that carried on regardless asserted about traffic that had not happened yet.
- **A finding that was unreachable is worth fixing when a later stage makes it the important one**
  (SEE-93). The decoder read a lookup table's *count* and then required the bytes to be exhausted,
  so a real versioned message with a table came back `Malformed` and only a truncated one produced
  `AddressTableLookup`. The outcome was right either way, which is why it had gone unnoticed — and
  then SEE-93 made a lookup table the one limit the owner most needs named accurately. Reading each
  entry was ten lines.

- **A comment stripper that treats `//` as a comment start eats every URL, and can make a check
  vacuous** (SEE-94). `StageBoundaryTest.withoutComments` truncated `"https://explorer.solana.com"`
  to `"https:"`, so the new "no URL is ever persisted" assertion had nothing left to match and could
  not fail. It was found by the deliberate break, not by review. Two rules from it: when a text
  check strips anything, write the break that proves it still sees what it claims to see; and a
  lexer written as one regex has to exclude the sequences that are not what they look like —
  `(?<![:/])//` rather than `//`.
- **A step a caller must not skip does not belong in a second public method** (SEE-94). Resolving a
  versioned transaction's accounts first appeared as `resolve(subject, choice)` beside `prepare` and
  `inspect`: the shared caller would have had to learn a step that exists for one plugin, and
  forgetting it yielded an inspection of unresolved bytes with no sign anything was missing. Fold a
  mandatory step into the call that already owns the phase it belongs to — the network read into
  `prepare` — and key what it produces to the exact bytes, so the later pure step cannot be run
  against something else.
- **Failing to verify is a refusal, not a finding** (SEE-94). Turning an unreadable chain or an
  invalid lookup table into findings let `prepare` *succeed* and hand back a review whose verdict
  said "unverified" — which reads, on screen, like a transaction that was read and found wanting.
  When the difference is "I read this and it is wrong" versus "I could not read this", the second
  has to stop the operation with its reason, not appear as an item in a list of observations.
- **`JSONObject.optString` on an explicit JSON null returns the string `"null"`** (SEE-94). A market
  with `"result": null` read as settled, so a live market looked closed. Use `isNull(name)` first,
  or a helper that does — and make the test's own body write `JSONObject.NULL` rather than omitting
  the field, because an absent field and a null field are different inputs and only one of them was
  what the provider actually sent.
- **A private top-level declaration still collides inside its package** (SEE-94). A second
  `private class Layout` in the same Kotlin package as SEE-93's failed to compile. Name file-private
  helpers after what they parse — `OrderLayout` — rather than after their role.
- **A test that constructs a client directly can hide the setting the binary needs** (SEE-95). The
  opt-in test against the real gateway built `publish.New` with the publisher listener's port, so it
  passed while the *binary* published to the read origin and got a 404 from a listener with no write
  handler at all. The gap appeared in the first native run, not in any test. When a service has two
  addresses, ask which of them each piece of code is for — where a document is read from and where
  it is sent are different questions — and make the end-to-end run part of the acceptance rather
  than an illustration, because a test that skips the wiring skips the mistake.
- **One mistake with no error message anywhere deserves a line at startup** (SEE-95). The gateway
  cannot report a misconfigured publish address, because the address that answered was not its
  publisher API; the phone cannot report it either, because it only sees a feed with no manifest. So
  the template checks its own manifest's publication state after the first pass and, if it was
  refused, logs what it means and names the two variables. A failure that nothing downstream can
  explain has to be explained where it is detected.
- **`http.ServeMux` answers 404 and 405 in plain text** (SEE-95). An API whose every other answer is
  JSON had two that were not, which a client parsing answers has to special-case. A small
  `ResponseWriter` wrapper rewrites exactly those two, and it tells them apart from a handler's own
  404 by the content type already set — "there is no such signal" and "there is no such route" are
  different answers and both are 404.
- **A repeated protobuf field's order is part of the bytes, so an unordered map is a conflict
  waiting to happen** (SEE-95). The gateway compares a republication with what it holds field by
  field; a template that built its `values` list by iterating a Go map would send a different
  document every time and turn its own retry into a revision conflict. Sort once, where the document
  is built, and write the test that fails if the sort goes.
- **Emulating only what is needed is right, until a break shows which rule was needed** (SEE-95). The
  test gateways emulated "the same document again is unchanged" and nothing else, which was a
  deliberate choice — and it let a break that published a withdrawn document instead of withdrawing
  it pass two tests, because the real gateway refuses a cancelled status and the fakes stored it. A
  fake should carry the rules the tests' own claims depend on, and the way to find out which those
  are is to break the code and see which tests notice.
- **Absence is not evidence** (SEE-96). A market missing from a filtered listing has not necessarily
  closed: it may have left the filter, stopped trending, or fallen off the pages a cycle read. The
  first design withdrew on absence, which would have taken back statements for reasons no subscriber
  could see — and would have made a provider outage look like every market closing at once. The rule
  that came out of it: when a poll is *filtered*, the listing's silence says nothing, so ask the
  source about the thing itself and let only its own answer be a conclusion. A temporary failure is
  then not a small version of a closure; it is not a closure at all.
- **A derived expiry must not be derived from the clock** (SEE-96). The expiry of a discovered
  proposal looked like a natural `now + lifetime`, and that would have moved the document on every
  cycle — a revision every five minutes, and every subscribed phone woken by it. It is the market's
  own close time, and for a market with none it is counted from a *stored* instant (when the template
  first saw it). Anything a document is built from has to be as stable as the document is supposed to
  be, and the clock is the least stable thing in the process.
- **A test that runs inside one second cannot see a clock bug** (SEE-96). The break above was caught
  by the test that checks the expiry directly and by the one that reads a real listing — but *not* by
  "a second cycle publishes nothing", because both of its cycles happen in the same second and the
  expiry truncates to the same instant. A property that depends on time needs a test that owns the
  clock, not one that merely runs twice.
- **A key that must survive a crash should be derived, not minted** (SEE-96). A reconciler that
  minted an idempotency key per cycle would publish a market twice if it were interrupted between
  reading the listing and storing the signal. Deriving the key from the thing itself —
  `market:<venue>:<id>:<generation>` — makes the next cycle reach the same conclusion, and the
  generation is what lets a re-opened market get a new proposal without reusing a withdrawn one's
  identity. The same fact said twice, as a `UNIQUE` index, is cheaper than a convention.
- **Two authors for one document is a bug with a three-day fuse** (SEE-96). The discovering template
  shares an API with the one whose signals are written by callers, and leaving the writing endpoints
  routed would have let somebody post a signal that the next cycle silently undid. Naming the
  difference in the type system (`api.Authorship`) and answering 403 with the reason — rather than
  404, or nothing — turns "my signal disappeared" into "this template writes its own, and here are
  its filters".
- **A filter's semantics are a promise, so write them down before the code** (SEE-96). Whether a
  keyword is a substring or a word, whether a tag matches half of one, whether a market with no close
  time passes a window, and whether a market that stops matching is withdrawn are all things an
  operator will assume one way or the other. Each of them is now a row in a table in the wiki page
  and a case in a test named after the reason — and the reasons are *counted per cycle*, so "my
  filters match nothing" is answered by the API rather than by reading the code.
- **When a fake stands in for the thing under test, the test can stop being about the code**
  (SEE-96). The opt-in test against the real gateway built its prediction statement by hand, so it
  proved that *a* document is accepted rather than that the template's own document is — and a break
  that put a provider's URL in the note left it green. It now runs the reconciler and publishes what
  a cycle produced. The rule: in an end-to-end test, the only things that may be stand-ins are the
  things at the ends.

## SEE-97 — the environment contract

- **A cross-cutting "mode" belongs to the thing it is a promise about, not to the app.** The first
  instinct for SEE-97 was an app-wide sandbox switch, which is where the four
  `PluginEnvironment.Production` defaults pointed. It would have made the owner's own sidecar
  unexecutable whenever the app was in sandbox — a regression in the shipped private workflow for no
  safety gain. Putting it on the *connection* cost the same amount of code and broke nothing.
- **Ask what the safe direction of a missing answer is, every time.** `startingEnvironment` returns
  sandbox when a publisher serves both; `Wire()` returns *unspecified* rather than production for a
  word nobody validated; a stored environment this build cannot read drops the record rather than
  being resolved. Each of those is one line, and each of them is the difference between a bug and an
  incident.
- **A guard that a reviewer can see is worth more than a guard that merely works.** The sandbox
  branch of an approval is lexically outside `withWallet`, so there is no session in scope to sign
  with. "It checks a flag before signing" and "it cannot sign, because it has nothing to sign with"
  are the same behaviour and not the same assurance.
- **Prefer invalidation by comparison over invalidation by deletion.** A mode switch had to
  invalidate what was prepared. Instead of remembering to clear things in `setEnvironment` — a list
  that grows and gets forgotten — the review, the preparation and the binding each say which
  environment they were made in, so they stop counting by themselves.
- **When a boundary test would just get longer, ask whether it should get sharper.** Six core files
  importing `PluginEnvironment` would have grown `StageBoundaryTest`'s "who knows plugins exist"
  list by six and weakened what it meant. Splitting it into "knows a promise" and "knows the
  registry" kept the original rule strict and stated the new distinction out loud.
- **Two breaks that fail nothing are two tests worth fixing, so keep breaking past the first pass.**
  `setEnvironment` losing its "the server does not serve that" check failed nothing, because the
  test only ever switched to a served environment. The manifest answer taken from the raw setting
  failed nothing, because the assertion tested the helper rather than the handler. Both were found by
  breaking the code after the tests were green, and neither would have been found by adding more
  tests to a passing suite.
- **A forward reference in a comment is a promise to come back.** SEE-93 and SEE-94 both wrote
  "SEE-97 owns what sandbox grows into", and the survey of those sentences was the checklist for this
  ticket. Leaving them in place would have left the tree contradicting itself.

## SEE-98 — the integration harness

- **A harness that serves a fake server must never block its own event loop.** `publishctl poll`
  makes the template run a cycle, the cycle calls the provider, and the provider in this run is a
  Node server inside the test process: `spawnSync` there deadlocked all three, and the failure
  arrived as "context deadline exceeded while awaiting headers" from a server that was visibly
  listening. Every command the harness runs is asynchronous now, and the comment says why.
- **Node closes an idle keep-alive socket after five seconds.** A Go client writing its next
  request into that exact moment waits for headers that never come, which looks like a provider
  outage minutes after the last call. A test server that answers `Connection: close` has no such
  race, and it costs nothing at this scale.
- **A test that waits for a process to stop has to bound the wait.** Removing the publisher
  database's environment stamp left a production process happily running on a sandbox database —
  and the check that says it must refuse *hung the suite for five minutes* instead of failing.
  A timeout, and an assertion that it did not fire, turned the same break into a 15-second failure
  that names the problem.
- **A privacy sweep reads files, not tables — and so must its positive control.** The sidecar's
  request was in the write-ahead log rather than in the database when the sweep ran, so the control
  ("the search finds these needles where they are") failed while the sweep itself was right. Reading
  every file in the directory fixed both halves at once.
- **A generated JSON field name is the proto's, not the model's.** `mode`, not `connectionMode`;
  `feed`, not `gatewayFeed`; `min_contract`, not the phone's `leastContract`. Three assertions were
  written against the Kotlin names and passed `undefined` to `assertEquals` until the proto was
  read.
- **A fixture's calendar cannot be pinned in a binary.** A captured listing's close times decide
  whether anything is published at all, and a process reads the real clock rather than a test's. The
  stand-in moves every timestamp forward by the age of the capture, which keeps the spacing the
  filters actually judge and leaves the shapes alone.

## SEE-99 — the load harness

- **A profile's own padding has to pass the gateway's rules, and a test can say so without a
  gateway.** Term keys are operation names (`rules.IsOperation`), and a segment cannot begin with a
  digit — so `loadtest.publish.0` is not one. The run that found that out had **every single
  document refused** with `bad_value` and looked like a broken deployment. The keys are `…pad0`
  now, and `TestSyntheticFitsTheRules` checks the padding against the rule itself.
- **Anything a scenario does mid-window has to be scheduled inside the window.** The slow-consumer
  scenario stalled its listeners on their first connection, which happens during the warm-up — so
  the measured window contained no stall at all, and the counter that would have proved it had been
  reset with everything else. A run's interruptions are an explicit list with an offset into the
  window now.
- **A background load that is started per stage must be cancelled per stage.** A ramp started a new
  set of snapshot readers at every stage and overwrote the cancel for the previous set, so the run's
  own `Stop` waited on goroutines whose context nothing would ever cancel. A two-minute ramp hung
  for twenty. The readers are started once; only the publishers restart, because only they have to
  stop for the settle.
- **A generator whose state lives in the goroutine restarts with the goroutine.** The same ramp's
  publishers began their tick count again at every stage and republished revision 1 of proposals the
  gateway already held — `revision_conflict` for a whole stage, which the report faithfully recorded
  as "published 0". A publisher is one publisher for the run, so its progress is on the run.
- **A long run cannot publish a bounded amount of work.** Eight identities times three revisions is
  sixteen seconds of a hundred-second climb. A profile now either revises its proposals for ever or
  publishes generations of them and withdraws each one — and the second accumulates documents, which
  a channel bounds at 200.
- **A load report's health rule belongs to the scenario, not to the workload.** A window with a node
  failure in it has no steady-state p99: the publications a listener missed arrive when it comes
  back, so the number measures recovery and is bounded by the client's backoff ceiling. Judging it by
  the steady profile's two seconds reported the design as a fault. Completeness does not move — every
  delivery is still expected to arrive.
- **"Delivered" has to be counted where the delivery happens.** Counting it where the document was
  *applied* under-counted every arrival a listener already held from a snapshot, and would have made
  a healthy stage look lossy. Arrivals and applications are two counters now, and the difference is
  the at-least-once contract working.
- **A convergence check is about the end state, and a break has to aim at the end.** Throwing away
  every third revision of a proposal changed nothing: a later revision covered it, which is correct
  — what converged is what a phone would hold. The break that found the check works threw away every
  *withdrawal*, because a withdrawal is the last thing an identity ever hears. Per-publication
  delivery is a different measurement (the delivered share), and conflating the two would have left
  a check that only looks strict.
- **A broker's counters are the process's, not the window's.** Every per-node number in a stage is a
  difference against a reading taken when the window opened; a node that was restarted mid-window has
  counters that went backwards, and the honest answer there is the restart's own numbers rather than a
  negative.

## SEE-100 — writing a guide for somebody else's machine

- **Before documenting a step a person takes in the app, find the code that lets them take it.** The
  guide's "add the feed from this reference" step was written from the data path — `addFeed` is
  implemented, wired to a live gateway and covered by tests — and then a grep for its call sites
  found none: no screen in the Add-connection flow calls it, and `AndroidManifest.xml` declares no
  deep link for `seekervault://feed`. Two device runbooks already assumed that screen exists. A
  user-facing instruction needs a call site, not just a repository method.
- **Run every command before putting it in a guide, including the flags.** `publishctl --url … status`
  fails: flags come *after* the command. `POST /v1/signals` with `idempotency_key` in the body fails:
  the key is a header. `pnpm test:integration -- --no-android` fails, and `pnpm test:integration
  --no-android` is the documented form that works. Every one of those was written plausibly from the
  source and was wrong, and the only thing that caught them was running them.
- **A guide that links a stale page inherits its claim.** Four corrections landed in pages this
  guide points at, each of which had been true when written and was overtaken by a later ticket in
  the same stage: "this build resolves no feed" (SEE-91 wired it), a store version (SEE-97 moved
  it), `PluginRegistry.bundled()` (SEE-93 replaced it), and a method signature (contract 1 passes
  the owner's choice). When a page is linked as the authority on something, read the code it
  describes before pointing a reader at it.
- **Curl the read API while developing a publisher.** `FeedService` takes no credential and speaks
  Connect JSON, so `curl -d '{"channel":"server/<id>"}' …/ListProposals` shows exactly what a phone
  will hold. It turned every claim in the guide's lifecycle section into something observed, and it
  is the fastest way to see that a term is misspelled.

## Scope

- **A status update is not a task.** "I'm deploying to my hermes instance with Tailscale Funnel" meant the owner had already done it. Treating it as a request led to SSHing into the production droplet and probing it uninvited (2026-09-18). When the owner names their own server, ask what they want, or answer with information; never connect to, inspect, or change a remote host unless they ask for exactly that.

## SEE-116 — parallel ticket ownership can move

- **Re-check a parallel owner's boundary before finalizing shared tooling.** SEE-116 found that the
  old capture deleted hand-written specs and implemented a local guard. SEE-111 briefly reported
  overlapping capture work, then its newest coordination comment returned that guard wholly to
  SEE-116. Treat the newest explicit ownership message as authoritative, record the superseding
  boundary, and align both code and documentation with it before opening either PR.

## Cross-runtime storage

- **`JSONObject.put(String, Object)` on Android does not wrap a collection** (SEE-144). A Kotlin
  `List` handed to it is serialized by `toString()`, so `["binding-1"]` is written as the *string*
  `"[binding-1]"` and `optJSONArray` reads it back as null. Nothing throws. The symptom was a
  revocation the phone owed a gateway being forgotten on the next launch — a security-relevant
  silent loss. Build a `JSONArray` explicitly, and write a round-trip test for every field of a
  state that is persisted whole, not just the one being added.
- **An event loop that swallows nothing dies of one failure** (SEE-144). A serialized
  `for (event in channel) handle(event)` inside a `SupervisorJob` scope stops for good the first
  time `handle` throws, and looks exactly like a feature nobody configured. Put the best-effort
  guard around the *handler*, not only around the individual calls inside it.

## Storage versions and compatibility tables

- **An additive storage change must not bump the version number** (SEE-145). Adding keys beside
  the ones an older reader knows is compatible in *both* directions; raising the version is not,
  because the old decoder's first act is to refuse anything outside the range it knows, before it
  looks at a single field it would have been able to read. The rows this build rewrites then
  vanish on a downgrade — and with a proposal store, what vanishes is the record of the one
  attempt, so a refreshed item looks unexecuted and becomes actionable again. Bump the number only
  when a field an old reader *needs* changed meaning or left.
- **Assert the gate, not the number.** The test that existed asserted `version == 4`, so it pinned
  the bug in place instead of catching it. A compatibility test should assert the condition the
  other side actually applies (`version in 1..3`), plus the consequence in plain terms.
- **A legacy name is a whole description, not half of one** (SEE-145). When splitting one
  identifier into two concepts (`jupiter.swap` → provider + action), mapping the old name to only
  the first concept silently drops the pairing: the surviving check asks "does this provider serve
  that action?", answers yes, and a document combining `jupiter.prediction` with `swap` gets
  authorized where the old code refused it. Validate the **pair**, and do it on every path that
  reads the old name — parse, stored-row decode, and the gate before signing. A registry lookup is
  not a substitute: it will answer for any name the provider declares.

## Window insets, and tests that measure them

- **Insets belong to the host, not to a design-system component** (SEE-150). Putting
  `windowInsetsPadding` in `:designsystem`'s `SheetScaffold` moved the stacked-sheet Roborazzi
  reference: the preview host reports a navigation bar, so the component wrote a *simulated* system
  bar into its own design golden — which then no longer matches the design export it is paired with.
  A presentation component should be window-agnostic; the one layer that hosts every instance is
  where the safe area is applied. `windowInsetsPadding` also consumes what it applies, so a host
  doing it means nothing nested can pad for the same bar twice.
- **A Roborazzi golden failing in a full run but passing alone is usually stale build state, not a
  regression.** Reverting the cause and re-running only the preview tests proves nothing, because
  that reproduces the isolated condition. Re-run the whole module with `--rerun-tasks` before
  concluding anything, and get a baseline from a clean `origin/master` worktree before calling a
  failure pre-existing *or* new — on this branch both answers were needed, and both were wrong the
  first time.
- **Compose installs its window-insets listener when something first reads insets** (SEE-150). A
  test that dispatches `WindowInsets` before the composable under test exists reaches nothing, and
  the subject then measures against zero. That is invisible when a sibling test asserts the
  zero-inset case: the broken test fails against the right number for the wrong reason. Dispatch
  after the subject is on screen, and use the platform `View.dispatchApplyWindowInsets` —
  `ViewCompat.dispatchApplyWindowInsets` did not reach Compose here.

## Presence, and answers that are true but not the question

- **"Can I reach the intermediary" is not "is the origin running"** (SEE-150). The gateway keeps
  serving what a publisher last published after that publisher's process is gone, so a phone that
  could reach the gateway was shown a dead feed as connected. Nothing failed; only a person looking
  at a phone could notice. When two facts are routinely used as one, give them two states, two
  reads and two lines of copy, and never derive one from the other — including in the failure
  direction: a gateway that cannot be reached must not be reported as a publisher that has stopped.
- **The absence of an answer is its own value, and it must not collapse into the good one.**
  `Unknown` is separate from `Offline` so nothing has to decide which "no answer" means — and
  emphatically separate from `Online`, because folding an unreadable enum value into "running" is
  the original bug arriving by another route. A wire enum's unspecified zero, and any value a build
  does not recognise, both land on unknown.
- **A presence window is a multiple of the interval, derived and not configured.** One lost check-in
  over somebody else's network must not flip a running feed offline on every phone reading it, and a
  separately configured window lets an operator set one shorter than the interval — which shows every
  feed offline for ever and looks exactly like a feature nobody turned on.
- **"Never happened" and "happened at the epoch" are different facts.** The new `last_seen_at_ms`
  column is nullable for that reason, the opposite of the choice the `host` column made where the
  two genuinely were one fact. Both read as offline today; only one of them would still be right if
  the window ever grew.

## Advertising a capability is not serving it

- **Serving a route and telling the client where it is are two separate pieces of work** (SEE-150).
  `skr-staking-server` served `UpdateService` and never advertised an update origin, so `Pair` came
  back with no update capability, the phone never subscribed, and requests sat unseen until a manual
  refresh — which worked, so nothing looked broken. When a client only acts on what it was told at
  handshake time, a test that the handler responds proves nothing; assert what the handshake
  *advertises*.

## An owner that can end is an owner that will

- **Something that owns a resource in a loop must not be endable by anything it could retry**
  (SEE-152). `ForegroundUpdateManager` launches one owner per connection from inside the collector
  on the connection *list*, so an owner that returns or throws while its connection stays paired is
  never replaced: the row freezes on whatever it published last, and only pairing again — which
  re-emits the list — brings it back. That is also why a second connection appeared to recover when
  an unrelated one was re-paired, which read at first like shared state and was not.
- **A `CancellationException` is not proof that the caller was cancelled.** `withTimeout` raises
  one, and so does awaiting a `CompletableDeferred` that somebody else's cancellation completed.
  The reflexive `catch (e: CancellationException) { throw e }` turns both into "we are shutting
  down". The test is `currentCoroutineContext().ensureActive()`: rethrow only when *this* coroutine
  is the one that was cancelled, and treat everything else as the failure it is. Prefer
  `withTimeoutOrNull` plus a real exception wherever a bounded wait is an ordinary outage.
- **Coalescing work between callers couples their lifetimes, and that has to be undone
  deliberately.** A follower joined to a leader inherits the leader's cancellation, so a Retry whose
  sheet closed could stop a process-scoped owner that was running perfectly. A follower whose run is
  abandoned should take the lead itself.
- **A status code is not a diagnosis.** The sidecar answers `FAILED_PRECONDITION` both for a
  protocol it will not speak and for a snapshot the phone must replace; one is terminal and one is
  a reconnect. The transport already classified it by the error detail, and the owner then threw
  that classification away and re-derived a worse answer from the raw code. When a layer has
  already decided, use its decision.
- **Prove a regression test is one.** Each of the six new cases was run against the unfixed
  sources first; all six failed, and the full module was run on both sides so the 39 failures this
  host has without `pnpm install` could be shown to be identical rather than assumed to be.

## A 200 is only evidence about the path you asked for

- **Probe a deployed route by its real procedure path, and check the status against a path that
  cannot exist** (SEE-155). The deployed gateway answers 200 to *any* unknown path from a
  catch-all, so a probe with a misspelled package name "proved" `GetFeedStatus` was deployed when
  the real path still answered 404. A control request to a nonsense path is what tells you whether
  a 200 means anything.
- **A "fails before the fix" claim is a measurement, not a summary.** The first draft of the
  SEE-155 changelog said twelve regressions across three modules fail on the base; the twelve were
  all Android, the Go ones had not been run on the base at all, and the gateway test passes there
  by design because it pins existing behaviour. Run each claimed regression on the base, count what
  actually failed, and name the tests that are pins rather than regressions.
- **A UI poll must read the element it means.** Twice in one run a `grep -A` on the uiautomator
  dump read the row's initials or the first matching row instead of the live one, and reported
  minutes of nothing. Key the read on the row's own label and check it once by hand before timing
  anything with it.

- **A fixture where two fields hold the same value proves nothing about their order** (SEE-172). The
  prediction order's `isYes, isBuy` flags were read swapped since SEE-94, and the one fixture — a YES
  buy, `1, 1` — could not tell. Every NO buy was refused in production. When a layout test compares
  fields against a capture, make sure the capture has distinct values in every field it pins; capture
  a second case (here a NO buy and a sale) when it does not.
- **Re-probe a provider's live shape before building on an old fixture** (SEE-172). Jupiter moved to
  gasless builds (a pre-signed relayer fee payer) after the SEE-94 capture, and the buy review refused
  every current order. A new capture found it in minutes; the old fixture never would.
- **A new protobuf field numbered after a oneof changes the bytes differently per runtime** (SEE-174).
  `supported_networks = 11` on `ServerManifest` sat after the `reference` oneof (8/9). protobuf-go and
  `buf convert` write oneof fields after every ordinary field, while protobuf-es and Java write in
  field-number order, so the cross-runtime byte fixtures stopped agreeing. Declaration order doesn't
  fix it. Before adding a field to a message with a oneof, check where the oneof is. Keep new
  top-level fields numbered below it, or put the field inside the oneof's messages, and run every
  runtime's fixture test before building on it.
- **When the Buf registry rate-limits `pnpm generate`, generate locally with the pinned versions**
  (SEE-174). Go: `protoc-gen-go` and `protoc-gen-connect-go` at the pinned versions, as
  `local:` plugins in a throwaway template. Java/Kotlin: the protoc release that matches the
  committed "Protobuf Java Version", via `protoc_builtin`, generated into a temp dir, copying back
  only the files that changed so connect-kotlin output isn't wiped. Then diff against a clean run
  when the registry is back.
- **Dedupe across a race must keep provenance** (SEE-175 review). "Already held → nothing" silently
  swallowed a live event whose item the racing snapshot had stored first. When two paths can deliver
  the same item, record which path stored it and let the other one upgrade it (once), instead of
  treating the second arrival as a plain duplicate. Test the overlap with the *same* ID on both
  paths, not disjoint IDs.
- **"Cleared on background" is not "owned by the foreground"** (SEE-175 review). App-scoped state
  that repositories write is also written by workers while the app is away. Gate writes on an
  explicit session (start/stop + a session token captured when a long operation begins).
- **Re-check suppression when something delayed is finally presented** (SEE-175 review). A check
  made on arrival goes stale during a cooldown or while queued behind another banner.

## Daily spending coverage (SEE-181)

- **A counter that filters by kind is a list of what came to mind, too.** `spendsOf` read only
  `ActivityKind.Transfer`, so every feed swap and prediction buy — added stages later — was silently
  zero against daily limits. When a new record kind can move value, give the counter a `when` over
  every kind with no `else`, so the next kind has to be given a counting rule before it compiles.
- **"Not recorded" must never decode as "nothing".** Operations written before the spending fact
  existed are *unknown* coverage (`DailyTotal.uncounted`), not zero, and an unreadable spending JSON
  decodes as null for the same reason.
- **Check which clock a shared test fixture uses before dating records.** `operations/Phone`'s
  `PolicyEvaluator` reads the real clock for "today", so a record dated at the fixture clock is a
  different day and silently counts nothing. Date such records `Instant.now()` or build an evaluator
  with the test clock.
