# Lessons Learned

## Branching

- **Auto-named worktree branches:** workspace tooling may check out a prefixed branch (e.g. `superset/develop`). Ignore it — switch to the plain branch the user names (`git switch -c develop --track origin/develop`), commit and push only there, and delete the prefixed local branch. Never push the prefixed branch.

## Tooling

- **Report the check's own exit code.** In `check; tail log`, the status is `tail`'s, and it made a failed Gradle build look like exit 0 (SEE-16). Capture `code=$?` right after the check, end with `exit $code`, and read the log's `BUILD` line before recording a PASS.
- **A pushed commit isn't done until its CI is green.** SEE-16's emulator job failed on the runner's disk space, and nobody noticed until the next ticket. After each push, watch the run (`gh run watch <id> --exit-status`) before moving the ticket on. Don't rerun an older run on the same branch: `cancel-in-progress` would cancel the newer run.
- **Compare a failing CI run with a passing one before changing the workflow.** The first fix for the emulator job, `disk-size`, rested on a guess, and it didn't work (SEE-17). The real cause took two steps to find. First, the log's own "need 7372.80 MB" line. Then a comparison with the passing runs, which had the same image and emulator but no Gradle cache restored. Read the failing step's full message and diff the job logs first.
- **Worktrees have no `android/local.properties`.** Export `ANDROID_HOME=~/Library/Android/sdk` for `pnpm check:android` rather than creating the ignored file. Put Node 24.21.0 first on `PATH` (`~/.nvm/versions/node/v24.21.0/bin`), because pnpm refuses any other version.
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

## Scope

- **A status update is not a task.** "I'm deploying to my hermes instance with Tailscale Funnel" meant the owner had already done it. Treating it as a request led to SSHing into the production droplet and probing it uninvited (2026-09-18). When the owner names their own server, ask what they want, or answer with information; never connect to, inspect, or change a remote host unless they ask for exactly that.
