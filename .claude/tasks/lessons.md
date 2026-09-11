# Lessons Learned

## Branching

- **Auto-named worktree branches:** workspace tooling may check out a prefixed branch (e.g. `superset/develop`). Ignore it — switch to the plain branch the user names (`git switch -c develop --track origin/develop`), commit and push only there, and delete the prefixed local branch. Never push the prefixed branch.

## Tooling

- **Report the check's own exit code.** In `check; tail log`, the status is `tail`'s, and it made a failed Gradle build look like exit 0 (SEE-16). Capture `code=$?` right after the check, end with `exit $code`, and read the log's `BUILD` line before recording a PASS.
- **A pushed commit isn't done until its CI is green.** SEE-16's emulator job failed on the runner's disk space, and nobody noticed until the next ticket. After each push, watch the run (`gh run watch <id> --exit-status`) before moving the ticket on. Don't rerun an older run on the same branch: `cancel-in-progress` would cancel the newer run.
- **Compare a failing CI run with a passing one before changing the workflow.** The first fix for the emulator job, `disk-size`, rested on a guess, and it didn't work (SEE-17). The real cause took two steps to find. First, the log's own "need 7372.80 MB" line. Then a comparison with the passing runs, which had the same image and emulator but no Gradle cache restored. Read the failing step's full message and diff the job logs first.
- **Worktrees have no `android/local.properties`.** Export `ANDROID_HOME=~/Library/Android/sdk` for `pnpm check:android` rather than creating the ignored file. Put Node 24.21.0 first on `PATH` (`~/.nvm/versions/node/v24.21.0/bin`), because pnpm refuses any other version.
- **Give deliberate breaks a time limit, one break at a time.** SEE-20's break run had no limit, hung in `InboxTest`, and the owner had to stop it. Run each break under a limit (`perl -e 'alarm shift; exec @ARGV' 600 …`), and restore the file from a copy, checked with `cmp`. Record a break that times out as NOT RUN.
- **A time limit through `perl -e 'alarm shift; exec @ARGV'` can't run a shell function, and a failed `exec` exits 0.** The PR #3 review's first Android breaks called a zsh function that way. Gradle never started, and every break looked like "not caught". Pass a real executable, and write `exec @ARGV or die`. A break whose log is empty didn't run.
- **A test that fails under load has a race: find it, don't retry it.** During the PR #3 review, `ConnectionsActivityTest` failed under load locally, then in CI.
  - The cause: it ran the repository on `Dispatchers.IO`, which the Compose rule doesn't wait for, so an assertion could run before a disconnect reached the screen.
  - Ten `yes > /dev/null` processes reproduced it in 2 of 6 runs.
  - A tree dump on failure, `printToString(Int.MAX_VALUE)`, showed the screen settling just after the assertion ran.
  - Activity tests now run the repository on `Dispatchers.Unconfined`. The fix passed 10 of 10 runs under the same load.
- **Tool inputs turn `\uXXXX` into the character itself.** To keep an escape in a source file, write `\u{301}` in TypeScript or build the string from code points in Kotlin (`String(intArrayOf(...), 0, n)`), then check the code points with a script. That matters wherever NFC and NFD must differ.
