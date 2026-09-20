# MacBook → Seeker quickstart

This guide starts from a MacBook with nothing installed and ends with the Stage 1 hello world: the test agent on the Mac sends "Hello Seeker", the Seeker shows it, you tap **OK**, and the agent prints the acknowledgement. It assumes no Android development experience. The phone needs only USB debugging: no bootloader unlock, no root, and no wallet setup.

For the durable Stage 5.2 gRPC/HTTP2 stream, its second USB port mapping, WorkManager behavior, and scheduling/connection inspection, complete the setup here and then follow [Live and background updates on a Seeker](live-background-updates.md). Passing this Stage 1 diagnostic does not count as a Stage 5.2 update pass.

```text
Mac: pnpm agent ──MCP──▶ sidecar on 127.0.0.1:8080 ◀──USB, adb reverse── Seeker: Seeker Agent Connect
```

> **What has been tested.** Every Mac-side command below was run as written on 2026-09-11, and the outputs shown are the real ones. On the same day, the owner followed the Seeker steps on their Seeker (Android 16) and reported them working. The Android Studio steps haven't been recorded; their expected results come from the Android documentation. See the [verification record](#verification-record-saw-006).

If a step fails, look it up in [`troubleshooting.md`](troubleshooting.md).

## Part 1: Install the tools (once)

1. **Command-line developer tools**, which provide `git`:

   ```bash
   xcode-select --install
   ```

   If they're already installed, the command says so.

2. **nvm**, which installs Node.js ([nvm on GitHub](https://github.com/nvm-sh/nvm)):

   ```bash
   curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.7/install.sh | bash
   ```

   Close the terminal window and open a new one, so that `nvm` is available. You install Node.js itself in Part 2, from the repository.

3. **Android Studio**, Quail 4 or newer. Download the "Mac with Apple chip" build from [developer.android.com/studio](https://developer.android.com/studio), open the `.dmg`, and drag Android Studio into Applications. With Homebrew, `brew install --cask android-studio` does the same.

4. **The Android SDK.** Start Android Studio and finish the setup wizard with the **Standard** install type. It puts the SDK in `~/Library/Android/sdk`. Then open the SDK Manager: **Settings → Languages & Frameworks → Android SDK**, or **More Actions → SDK Manager** on the welcome screen. Install these, click **Apply**, and accept the licenses:
   - **SDK Platforms** tab: the platform for API level 37 (Android SDK Platform 37.0)
   - **SDK Tools** tab, with **Show Package Details** checked: Android SDK Build-Tools 36.0.0, and Android SDK Platform-Tools

5. **Shell environment.** Add these lines to `~/.zshrc`, then open a new terminal:

   ```bash
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   export PATH="$ANDROID_HOME/platform-tools:$PATH"
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   ```

   - `ANDROID_HOME` tells terminal builds where the SDK is. Android Studio finds it on its own.
   - `PATH` makes `adb`, the tool that talks to the phone, available.
   - `JAVA_HOME` points to the JDK that ships inside Android Studio. It only starts Gradle. On the first build, Gradle downloads the project's pinned JDK, Eclipse Temurin 21, into `~/.gradle/jdks` and builds with it, so you don't install a JDK by hand. To install Temurin 21 yourself instead, run `brew install --cask temurin@21` and use `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.

   Check the result:

   ```bash
   adb version                      # Android Debug Bridge version 1.0.41 or newer
   "$JAVA_HOME/bin/java" -version   # 17 or newer
   ```

## Part 2: Get the code and build the app

6. **Clone the repository** and enter it. The repository is private, so sign in to GitHub first. The GitHub CLI is the simplest way; get it from [cli.github.com](https://cli.github.com) or with `brew install gh`:

   ```bash
   gh auth login
   gh repo clone BrRenat/SeekerAgentWallet
   cd SeekerAgentWallet
   ```

   If git already has your GitHub credentials, `git clone https://github.com/BrRenat/SeekerAgentWallet.git` works too.

7. **Install Node.js and pnpm** at the versions the repository pins:

   ```bash
   nvm install            # installs and selects the version in .nvmrc
   corepack enable pnpm   # pnpm then switches to the version in package.json
   node --version         # v24.21.0
   pnpm --version         # 12.3.4
   ```

   nvm makes the first version you install its default. If you also use other Node.js versions, run `nvm use` in this directory in each new terminal. pnpm refuses to run on any other version.

8. **Install the dependencies:**

   ```bash
   pnpm install --frozen-lockfile
   ```

   The last line reads `Done in … using pnpm v12.3.4`.

9. **Build the app** from the terminal:

   ```bash
   (cd android && ./gradlew :app:assembleDebug)
   ```

   The first build downloads Gradle, the Temurin 21 JDK, and the Android libraries, so it takes several minutes. Later builds take seconds. The result is the debug APK, `android/app/build/outputs/apk/debug/app-debug.apk` (about 37 MB).

   Optionally, run the full checks too: `pnpm check` and `pnpm check:android`.

10. **Or open the project in Android Studio.** Choose **Open**, select the `android` folder inside the repository (not the repository root), and trust the project. Studio then syncs the project with Gradle. Wait until the sync finishes and the `app` run configuration appears in the toolbar.
    - Studio and the terminal use the same Gradle wrapper, the same Temurin 21, and the same caches, so you can switch between them freely.
    - If Studio offers to upgrade the Android Gradle Plugin or Gradle, decline. The versions are pinned.
    - Studio writes the SDK path to `android/local.properties`. Git ignores that file.

## Part 3: Prepare the Seeker (once)

11. **Turn on Developer options.** Open **Settings → About phone** and tap **Build number** seven times. Enter your PIN if asked. The phone confirms that you are now a developer.
12. **Turn on USB debugging.** Open **Settings → System → Developer options** and switch on **USB debugging**. If your menus look different, search Settings for "Build number" and "USB debugging".
13. **Connect the phone to the Mac** with a USB-C cable that carries data. Many cables are charge-only and look the same; if the phone doesn't show up in step 15, try another cable first. If the Mac asks **Allow accessory to connect?**, click **Allow**.
14. **Authorize the Mac.** Unlock the phone. It asks **Allow USB debugging?** and shows the Mac's key fingerprint. Check **Always allow from this computer** and tap **Allow**.
15. **Check the connection:**

    ```bash
    adb devices -l
    ```

    You should see exactly one line that ends with `device` and some details, like `<serial>  device usb:… product:… model:… transport_id:1`. If it says `unauthorized`, step 14 is still waiting on the phone. For `offline` or an empty list, see [troubleshooting](troubleshooting.md#usb-and-adb).

    If more than one phone or emulator is attached, add `-s <serial>` to every `adb` command, or run `export ANDROID_SERIAL=<serial>` once in that terminal. Gradle's install task uses `ANDROID_SERIAL` too.

## Part 4: Install and launch the app

16. **Install the APK** from the terminal:

    ```bash
    adb install -r android/app/build/outputs/apk/debug/app-debug.apk
    ```

    adb prints `Performing Streamed Install`, then `Success`. The `-r` flag replaces an installed copy. `(cd android && ./gradlew :app:installDebug)` builds and installs in one step.

    **Or from Android Studio:** choose the Seeker in the device menu on the toolbar and click **Run ▶**. Studio builds, installs, and launches the app.

17. **Launch Seeker Agent Connect** from the app drawer, or from the Mac:

    ```bash
    adb shell am start -n io.github.brrenat.seekervault/.MainActivity
    ```

    The app opens on **Connections**. Tap **Live test** at the top: the live-test screen opens. The Server URL is `http://127.0.0.1:8080`, the phone token is empty, and the status reads "Disconnected". (Pairing the phone with a sidecar, for the requests that later stages bring, is in [`pairing.md`](pairing.md). The hello world doesn't need it.)

18. **Know where the logs are.** The app writes no log messages of its own, so the sidecar's terminal is the first place to look. For crashes and system messages about the app:

    ```bash
    adb logcat --pid="$(adb shell pidof -s io.github.brrenat.seekervault)"   # while the app is running
    adb logcat -b crash -d                                                   # earlier crashes
    ```

    In Android Studio, open the **Logcat** window and filter with `package:mine`.

## Part 5: Start the sidecar and connect the phone

19. **Create the configuration** (once):

    ```bash
    cp .env.example .env
    sed -i '' "s/^MCP_TOKEN=.*/MCP_TOKEN=$(openssl rand -hex 32)/; s/^PHONE_TOKEN=.*/PHONE_TOKEN=$(openssl rand -hex 32)/" .env
    grep -c REPLACE_WITH .env   # 0: both placeholders are replaced
    ```

    This writes two different random tokens into `.env`, which git ignores. The agent authenticates with `MCP_TOKEN`, and the phone with `PHONE_TOKEN`.

20. **Start the sidecar** in its own terminal, and leave it running:

    ```bash
    pnpm dev:mcp-server
    ```

    ```text
    [sidecar] listening on http://127.0.0.1:8080: MCP at http://127.0.0.1:8080/mcp, phone API at http://127.0.0.1:8080/seekervault.live.v1.LiveCommandService
    ```

    In a second terminal, `curl -s http://127.0.0.1:8080/healthz` prints `{"status":"ok"}`.

21. **Forward the phone's port 8080 to the Mac:**

    ```bash
    adb reverse tcp:8080 tcp:8080
    adb reverse --list   # shows the tcp:8080 mapping
    ```

    Now `127.0.0.1:8080` on the phone reaches the sidecar on the Mac through the USB cable. The sidecar listens only on the Mac's loopback address, so nothing is exposed to your network.

22. **Enter the phone token.** In the app, tap the **Phone token** field. Type the `PHONE_TOKEN` value from `.env`, or let adb type it into the focused field:

    ```bash
    adb shell input text "$(grep '^PHONE_TOKEN=' .env | cut -d= -f2)"
    ```

    Leave the Server URL at `http://127.0.0.1:8080`.

23. **Tap Connect.** The status reads "Connected. Waiting for text from the agent.", and the sidecar logs `[sidecar] phone connected`.

## Part 6: Send hello world and tap OK

24. In the second terminal, send the text:

    ```bash
    pnpm --silent agent hello "Hello Seeker"
    ```

    ```text
    Showing the text on the phone; waiting up to 75 s for OK...
    ```

25. The phone shows **Hello Seeker** under "Received text" with "Tap OK to confirm you read it." Tap **OK**. The phone says "OK sent. The agent has your answer."
26. The agent prints the acknowledgement and exits with code 0:

    ```text
    {"id":"3208dee6-291c-41cc-ba70-1b9a95a4c64f","result":"OK"}
    ```

    The ID is new every time, and it matches the sidecar's log:

    ```text
    [sidecar] command 3208dee6-291c-41cc-ba70-1b9a95a4c64f sent (12 bytes)
    [sidecar] command 3208dee6-291c-41cc-ba70-1b9a95a4c64f acknowledged
    ```

That's the Stage 1 hello world. The agent waits only while the app is open and connected; there's no queue and no background delivery.

## Part 7: Disconnect and reconnect

27. **Disconnect.** Tap **Disconnect**, and the sidecar logs `[sidecar] phone disconnected`. The agent now gets OFFLINE and exits with code 4:

    ```text
    Showing the text on the phone; waiting up to 75 s for OK...
    OFFLINE: no phone is watching; open the live-test screen and connect
    [ELIFECYCLE] Command failed with exit code 4.
    ```

    Tap **Connect** again and repeat step 24.

28. **Leave the app.** Press Home. The app closes its connection and says "Disconnected while the app was in the background. It reconnects when you return." Reopening the app reconnects it. Text sent while the app is closed fails with OFFLINE and is never delivered later.
29. **Reconnect the USB cable.** Unplug the cable and plug it back in. Check `adb devices -l`, run `adb reverse tcp:8080 tcp:8080` again, and tap **Connect**. Run `adb reverse` after every reconnect; it does no harm if the mapping still exists.

## Part 8: Clean up

30. **Stop the sidecar** with Ctrl+C in its terminal:

    ```text
    [sidecar] SIGINT: shutting down; the in-flight command, if any, is cancelled
    [sidecar] stopped
    ```

31. **Remove the port mapping:** `adb reverse --remove tcp:8080`.
32. **Optionally, remove the app** with `adb uninstall io.github.brrenat.seekervault`, and switch off USB debugging in Developer options.

## Verification record: SAW-006

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`toolchain.md`](../development/toolchain.md) and Platform-Tools 37.0.1 (adb 1.0.41). A script ran the Mac-side steps above in a fresh clone of `develop` at `5965a76`, exactly as written, and recorded every output. The Node.js version check was run with this change's `devEngines.runtime` applied to the clone.

| Check | Result |
| --- | --- |
| Part 2: `nvm install`, `corepack enable pnpm`, `pnpm install --frozen-lockfile` | PASS: Node.js v24.21.0, pnpm 12.3.4 |
| Part 2: `./gradlew :app:assembleDebug` | PASS: `android/app/build/outputs/apk/debug/app-debug.apk`, 37,402,109 bytes. `aapt2` shows the package `io.github.brrenat.seekervault`, the launchable activity `MainActivity`, and target SDK 37. |
| Part 5: `.env` from the example, `pnpm dev:mcp-server`, `/healthz` | PASS: both placeholders replaced; the outputs are shown above |
| Part 6 with no phone connected | PASS: OFFLINE, exit code 4 |
| Part 6 with the sidecar's Connect test client standing in for the phone | PASS: exit code 0. The printed ID matched the command the client received and the sidecar's log. This covers the Mac side only and is not a device pass. |
| Part 8: Ctrl+C | PASS: the two lines shown above |
| Device commands with no phone attached | PASS: `adb devices -l` listed nothing, `adb install` and `adb reverse` said `adb: no devices/emulators found`, and `./gradlew :app:installDebug` said `No connected devices!` |
| Missing prerequisites | PASS. Each case failed with a message that names the problem; the messages are in [`troubleshooting.md`](troubleshooting.md). Cases: sidecar not running (exit code 3), no `.env`, placeholder tokens, port 8080 in use, no SDK path, `JAVA_HOME` pointing nowhere, Node.js 24.20.0. Before this change, pnpm ran on Node.js 24.20.0 without a word; it now stops with `ERR_PNPM_BAD_RUNTIME_VERSION`. |
| The app after a failed first connection | PASS in JVM tests. A closed port maps to Unreachable, and the screen names the URL and the `adb reverse` command. |
| Repository checks with this change | PASS: `pnpm check`, `pnpm check:android` (42/42 unit tests, lint with no issues, debug APK), `pnpm check:generated`, `pnpm build` |
| Parts 3 to 6 on the Seeker: Developer options, USB debugging, authorization, `adb install`, launch, logcat, `adb reverse`, Connect, and OK | PASS, reported by the owner on 2026-09-11, on their Seeker (Android 16, API 36). Not run during the scripted walkthrough, when no Seeker was attached. |
| Android Studio: open `android/`, sync, and Run | NOT RUN: Android Studio isn't installed on the verification Mac |
| Step 29, after reconnecting USB | NOT RUN. No Seeker was attached during the walkthrough, and the owner's run didn't record this step. |
| A JDK older than 17 | NOT RUN: no such JDK on the verification Mac |

To record a device run, note the commit, the Seeker's Android version (`adb shell getprop ro.build.version.release`), and PASS, FAIL, or NOT RUN for each step. A run with the Connect test client doesn't count as a device pass.
