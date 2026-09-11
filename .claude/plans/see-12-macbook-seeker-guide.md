# SEE-12 / SAW-006 — MacBook → Seeker build and run guide

Linear: https://linear.app/seekeragentwallet/issue/SEE-12 · Branch: `develop`

## Checklist

- [x] Script the Mac-side walkthrough in a fresh clone and record the real outputs, including each missing prerequisite
- [x] `docs/guides/macbook-seeker-quickstart.md`:
  - [x] tools: nvm, Android Studio, the SDK, `JAVA_HOME`, `ANDROID_HOME`
  - [x] clone, Node and pnpm, install, terminal build, Android Studio sync
  - [x] Seeker: Developer options, USB debugging, cable, authorization, `adb devices -l`
  - [x] install (adb, Gradle, Studio), device serial, launch, filtered logcat
  - [x] `.env`, sidecar, `adb reverse`, token, hello world, disconnect and reconnect, cleanup
  - [x] verification record
- [x] `docs/guides/troubleshooting.md`: ADB (nothing listed, unauthorized, offline), charge-only cable, JDK, SDK path, Node version, port conflict, missing reverse mapping, cleartext (no TLS bypass), app messages
- [x] App: a failed first connection names the URL and `adb reverse`, with ViewModel, screen, and transport tests
- [x] Node version enforced by pnpm (`devEngines.runtime`)
- [x] Docs: README quickstart link and stage row; pointers in toolchain, `android.md`, and `hello-world.md`; `CODEBASE.md`; changelog; decisions
- [x] Verify: `pnpm check`, `pnpm check:android` (42/42), `pnpm check:generated`, `pnpm build`
- [ ] Commit, push, and confirm CI is green
- [ ] Linear: tick the done items, leave the Seeker, Android Studio, and USB-reconnect checks open (NOT RUN), and move the issue to In Review

## Review

**What changed:**

- **Two guides.**
  - `docs/guides/macbook-seeker-quickstart.md` takes the owner from a fresh MacBook to an acknowledged "Hello Seeker" in 32 numbered steps.
  - `docs/guides/troubleshooting.md` lists fixes by symptom.
  - The README links to both.
- **An app improvement found while writing the troubleshooting page.** When the first connection fails, the screen now names the URL and the `adb reverse` command, instead of "Connection lost: …".
- **A toolchain fix found by the walkthrough.** pnpm 12 ignored `engines`, so the Node version is now enforced through `devEngines.runtime`.

**How it was verified:**

- A script followed the guide on this Mac in a fresh clone of `develop`, and the guide quotes its real outputs.
- Each missing prerequisite was provoked, to capture its real error message.
- The app change is covered by ViewModel, Compose, and transport tests.

The full record is at the bottom of the quickstart.

**Caveats:**

- **The Seeker steps, the Android Studio sync and Run, and the USB reconnect are NOT RUN,** because no device was attached and Android Studio isn't installed. Those Linear items stay open.
- **A JDK older than 17 wasn't reproduced,** because none is installed on this Mac. The troubleshooting page says so.
