# Troubleshooting

Fixes for problems in the [MacBook → Seeker quickstart](macbook-seeker-quickstart.md), grouped by where they show up. The messages in code blocks are real outputs captured on 2026-09-11, unless a section says it wasn't reproduced.

Quick checks, in this order:

```bash
node --version                         # v24.21.0
adb devices -l                         # one line ending in "device"
curl -s http://127.0.0.1:8080/healthz  # {"status":"ok"}
adb reverse --list                     # includes the tcp:8080 mapping
```

## USB and adb

### `adb devices -l` lists nothing

```text
List of devices attached

```

Work through these causes in order:

1. **A charge-only cable.** Many USB-C cables carry power but no data, and they look the same as data cables. Use a cable known to transfer data, plugged straight into the Mac rather than through a hub. To see whether the Mac detects the phone at all, run `system_profiler SPUSBHostDataType` and look for the phone. If it's missing there, the cable or the port is the problem, not adb.
2. **macOS blocked the phone.** If you dismissed **Allow accessory to connect?**, unplug the phone and plug it back in to get the prompt again. The setting is in **System Settings → Privacy & Security → Allow accessories to connect**.
3. **USB debugging is off.** Check **Developer options → USB debugging** on the phone.
4. **adb is stuck.** Restart it with `adb kill-server`, then run `adb devices -l` again.

### The phone shows as `unauthorized`

The phone hasn't accepted the Mac's key yet. Unlock the phone and look for **Allow USB debugging?**. If the prompt doesn't appear:

1. On the phone, open **Developer options** and tap **Revoke USB debugging authorizations**.
2. Unplug the cable, and run `adb kill-server` on the Mac.
3. Plug the cable back in and unlock the phone.
4. Accept the prompt with **Always allow from this computer** checked.

### The phone shows as `offline`

adb sees the phone but can't talk to it.

1. Unplug the cable, plug it back in, and unlock the phone.
2. Run `adb kill-server`, then `adb devices -l`.
3. Switch **USB debugging** off and on again.
4. Update **Android SDK Platform-Tools** in the SDK Manager. Version 37.0.1 was tested.

### `adb: no devices/emulators found`

```text
adb: no devices/emulators found
```

`adb install`, `adb reverse`, and `adb shell` need a connected, authorized phone. Fix `adb devices -l` first. Gradle's install task fails the same way:

```text
> com.android.builder.testing.api.DeviceException: No connected devices!
```

### `adb: more than one device/emulator`

Pick one device: add `-s <serial>` to the `adb` command, or run `export ANDROID_SERIAL=<serial>` in the terminal. The serial is the first column of `adb devices -l`. This case wasn't reproduced; only one device was ever listed.

### `command not found: adb`

Platform-Tools aren't on your `PATH`. Add the `ANDROID_HOME` and `PATH` lines from step 5 of the quickstart to `~/.zshrc`, and open a new terminal.

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

A copy of Seeker Vault signed with a different debug key is already installed, for example one built on another Mac. Each Mac signs debug builds with its own key, in `~/.android/debug.keystore`. Remove the old copy, then install again:

```bash
adb uninstall io.github.brrenat.seekervault
```

This case wasn't reproduced.

## Java and the Android SDK

### `JAVA_HOME is set to an invalid directory`

```text
ERROR: JAVA_HOME is set to an invalid directory: /Library/Java/NoSuchJdk

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation.
```

Point `JAVA_HOME` at an installed JDK, version 17 or newer. Android Studio's bundled JDK works:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
"$JAVA_HOME/bin/java" -version
```

### The wrong JDK

- **Too old to start Gradle.** Gradle 9.7 needs Java 17 or newer to start. With an older JDK in `JAVA_HOME`, the wrapper stops and asks for a newer Java version. Point `JAVA_HOME` at Android Studio's JDK or at Temurin 21. This case wasn't reproduced, because the verification Mac has no JDK older than 17.
- **A different JDK for the build.** Any JDK 17 or newer can start Gradle. The build itself always runs on the pinned Temurin 21 (`android/gradle/gradle-daemon-jvm.properties`), which Gradle downloads on the first build. If that download fails, for example when you're offline, install it yourself with `brew install --cask temurin@21`, and Gradle finds it. Android Studio Panda 1 and newer follow the same pin.

### `SDK location not found`

The real output, with the repository path shortened to `<repo>`:

```text
* What went wrong:
Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file at '<repo>/android/local.properties'.
```

Use any one of these fixes:

- Add `export ANDROID_HOME="$HOME/Library/Android/sdk"` to `~/.zshrc` (quickstart step 5) and open a new terminal.
- Open the `android` folder in Android Studio once. Studio writes `android/local.properties` for you.
- Create `android/local.properties` with the line `sdk.dir=/Users/<you>/Library/Android/sdk`. Git ignores this file; don't commit it.

After `ANDROID_HOME` changes, Gradle prints `configuration cache cannot be reused because environment variable 'ANDROID_HOME' has changed`. That's expected, and harmless.

### A missing SDK platform or unaccepted licenses

The build needs SDK Platform 37.0 and Build-Tools 36.0.0, with their licenses accepted. Install them in the SDK Manager (quickstart step 4), or with the command-line tools:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

This case wasn't reproduced.

## Node.js and pnpm

### `ERR_PNPM_BAD_RUNTIME_VERSION`

```text
Error: ERR_PNPM_BAD_RUNTIME_VERSION
  × This project requires Node.js 24.21.0. Your current Node.js is v24.20.0
```

Every pnpm command in the repository stops like this on any other Node.js version. Run `nvm install` in the repository root; it installs and selects the version in `.nvmrc`. pnpm's message also describes a way to skip the check. Don't use it: the tools are tested together at these versions.

### `command not found: pnpm`

Corepack's `pnpm` command belongs to one Node.js installation. After nvm installs or switches to a new Node.js version, run `corepack enable pnpm` again.

## The sidecar and the agent

### `Invalid sidecar configuration`: variables not set

```text
../.env not found. Continuing without it.
Invalid sidecar configuration:
  - SIDECAR_HOST is not set.
  - SIDECAR_PORT is not set.
  - MCP_TOKEN is not set.
  - PHONE_TOKEN is not set.
  - LIVE_COMMAND_TIMEOUT_SECONDS is not set.
Copy .env.example to .env and fill it in, or set the variables in the environment.
```

There's no `.env` in the repository root. Create it with quickstart step 19.

### `Invalid sidecar configuration`: placeholder tokens

```text
Invalid sidecar configuration:
  - MCP_TOKEN still has the .env.example placeholder; set a random value (for example `openssl rand -hex 32`).
  - PHONE_TOKEN still has the .env.example placeholder; set a random value (for example `openssl rand -hex 32`).
Copy .env.example to .env and fill it in, or set the variables in the environment.
```

`.env` still has the placeholder tokens from `.env.example`. Run the `sed` line from quickstart step 19, or put the output of `openssl rand -hex 32` into each token by hand. The two tokens must be different.

### `EADDRINUSE`: port 8080 is in use

```text
[sidecar] could not start: listen EADDRINUSE: address already in use 127.0.0.1:8080
```

Another program is listening on port 8080, often a sidecar still running in another terminal. To find it:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

Stop that program. If it's an earlier sidecar, press Ctrl+C in its terminal. To use another port instead, change all four places together:

1. `SIDECAR_PORT` in `.env`
2. The port in `MCP_URL` in `.env`
3. `adb reverse tcp:<port> tcp:<port>`
4. The Server URL in the app: `http://127.0.0.1:<port>`

### The agent: `could not reach http://127.0.0.1:8080/mcp`

```text
could not reach http://127.0.0.1:8080/mcp: connect ECONNREFUSED 127.0.0.1:8080
[ELIFECYCLE] Command failed with exit code 3.
```

The sidecar isn't running, or `MCP_URL` in `.env` points somewhere else. Start the sidecar with `pnpm dev:sidecar`.

### The agent: `OFFLINE`

```text
OFFLINE: no phone is watching; open the live-test screen and connect
[ELIFECYCLE] Command failed with exit code 4.
```

No phone is connected. Open Seeker Vault and tap **Connect**, and check that the status reads "Connected". Every exit code is listed in [`test-agent/README.md`](../../test-agent/README.md).

## Messages in the app

### "Could not reach the sidecar at http://127.0.0.1:8080. Is it running (pnpm dev:sidecar), and did you run adb reverse tcp:8080 tcp:8080?"

The phone couldn't open a connection. The app shows this only when the first attempt fails; a connection that worked and then dropped says "Connection lost" instead. There are two usual causes:

- **The reverse mapping is missing.** This always happens after the USB cable is reconnected or adb restarts. Check with `adb reverse --list` and run `adb reverse tcp:8080 tcp:8080` again.
- **The sidecar isn't running.** Check with `curl -s http://127.0.0.1:8080/healthz` on the Mac, and start it with `pnpm dev:sidecar`.

Then tap **Connect** again.

### "This build allows plain HTTP only to 127.0.0.1. Run adb reverse tcp:8080 tcp:8080 and use http://127.0.0.1:8080."

Android refused plain HTTP (cleartext) to the address you entered. Typical examples are the Mac's Wi-Fi address, a hostname, or `10.0.2.2`, which only works in the emulator. The debug build allows plain HTTP only to `127.0.0.1` and `localhost`, and a release build allows none.

Fix it the supported way: run `adb reverse tcp:8080 tcp:8080` and use `http://127.0.0.1:8080`. Don't work around the check:

- Don't add other hosts to `network_security_config.xml`.
- Don't set `usesCleartextTraffic`.
- Never disable TLS certificate validation.

Remote access, such as a sidecar on a VPS, comes in later stages over TLS.

### "The sidecar rejected the phone token. Check PHONE_TOKEN in the sidecar's .env."

The token in the app isn't the sidecar's `PHONE_TOKEN`, and the sidecar logs `rejected WatchCommands: missing or wrong phone token`. There are two usual causes:

- The `MCP_TOKEN` was entered instead of the `PHONE_TOKEN`.
- `.env` changed after the sidecar started. The sidecar reads `.env` only at startup, so restart it.

### "Enter the sidecar URL, for example http://127.0.0.1:8080."

The Server URL needs `http://` and a host. Use `http://127.0.0.1:8080`.

### "Another connection to the sidecar replaced this one."

The sidecar serves one phone connection at a time, and the newest one wins. Something else connected with the same token, such as a second device or a test client. Close it and tap **Connect**.

### "Connection lost"

The sidecar stopped, or the USB cable was unplugged. The app clears the received text, because the sidecar has already cancelled the waiting command, and the agent got `CANCELLED`. Start the sidecar, run `adb reverse` again if you reconnected the cable, and tap **Connect**.

## Reporting a problem

Include these details, and never `.env` itself:

- The commit, from `git rev-parse --short HEAD`
- The output of `node --version`, `adb version`, and `adb devices -l`
- The Seeker's Android version, from `adb shell getprop ro.build.version.release`
- The sidecar's terminal output. It never contains tokens or command text.
- The output of `adb logcat -b crash -d`, if the app crashed
