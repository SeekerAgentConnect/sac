#!/usr/bin/env bash
# Headless Android emulator for agent sessions: toolchain, build, boot, install, drive.
# Runbook: docs/development/emulator-e2e.md
#
#   scripts/emulator.sh setup                 # JDK 21 + Android SDK into $HOME (no sudo)
#   scripts/emulator.sh build [gradle args]   # debug APK against the DO gateway
#   scripts/emulator.sh start                 # boot the AVD (Docker+KVM unless in the kvm group)
#   scripts/emulator.sh install               # install the debug APK and launch it
#   scripts/emulator.sh ui                    # visible texts/descriptions with tap centres
#   scripts/emulator.sh tap-text "Add feed"   # tap the first element whose text/desc matches
#   scripts/emulator.sh open-uri <uri>        # hand a seekervault:// URI to the app
#   scripts/emulator.sh shot [file.png]       # screenshot
#   scripts/emulator.sh logs                  # the app's logcat
#   scripts/emulator.sh stop
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
JDK_DIR="${SEEKER_JDK_DIR:-$HOME/.local/jdk}"
AVD="${SEEKER_AVD:-sac}"
IMAGE="${SEEKER_SYSTEM_IMAGE:-system-images;android-35;google_apis;x86_64}"
CONTAINER="${SEEKER_EMULATOR_CONTAINER:-sac-emu}"
RELAY_URL="${SEEKER_RELAY_URL:-https://seeker-gateway-sg8g3.ondigitalocean.app}"
PACKAGE="io.github.brrenat.seekervault"
ADB="$ANDROID_HOME/platform-tools/adb"
# Pinned: newer cmdline-tools replace sdkmanager with a downloader that fails over IPv6.
CMDLINE_TOOLS="commandlinetools-linux-13114758_latest.zip"

java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then echo "$JAVA_HOME"; return; fi
  local found
  found="$(find "$JDK_DIR" -maxdepth 1 -mindepth 1 -type d -name 'jdk-21*' 2>/dev/null | sort | tail -1)"
  [[ -n "$found" ]] || { echo "no JDK 21; run: scripts/emulator.sh setup" >&2; exit 1; }
  echo "$found"
}

setup() {
  mkdir -p "$JDK_DIR" "$ANDROID_HOME"
  if ! find "$JDK_DIR" -maxdepth 1 -name 'jdk-21*' | grep -q .; then
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" \
      | tar xz -C "$JDK_DIR"
  fi
  export JAVA_HOME; JAVA_HOME="$(java_home)"
  if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    local tmp; tmp="$(mktemp -d)"
    # dl.google.com answers 404 over IPv6 from some hosts; -4 is deliberate.
    curl -4 -fsSL -o "$tmp/ct.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS"
    unzip -q "$tmp/ct.zip" -d "$tmp"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  export SDKMANAGER_OPTS="-Djava.net.preferIPv4Stack=true"
  local sdk="$ANDROID_HOME/cmdline-tools/latest/bin"
  yes | "$sdk/sdkmanager" --licenses >/dev/null 2>&1 || true
  "$sdk/sdkmanager" "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "emulator" "$IMAGE" \
    | grep -v '^\[' || true
  if [[ ! -d "$HOME/.android/avd/$AVD.avd" ]]; then
    echo no | "$sdk/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_6 --force >/dev/null
  fi
  echo "ready: JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME AVD=$AVD"
}

build() {
  export JAVA_HOME; JAVA_HOME="$(java_home)"
  export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
  "$ROOT/apps/android/gradlew" -p "$ROOT/apps/android" :app:assembleDebug \
    "-Pseekervault.relayUrl=$RELAY_URL" "$@"
}

wait_boot() {
  timeout 300 "$ADB" wait-for-device
  for _ in $(seq 90); do
    [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && { echo booted; return; }
    sleep 3
  done
  echo "emulator did not finish booting" >&2; exit 1
}

start() {
  "$ADB" start-server >/dev/null 2>&1
  if "$ADB" get-state >/dev/null 2>&1; then echo "a device is already attached"; return; fi
  local args=(-avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on)
  if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    nohup "$ANDROID_HOME/emulator/emulator" "${args[@]}" >"${TMPDIR:-/tmp}/seeker-emulator.log" 2>&1 &
  else
    # Not in the kvm group: the Docker daemon can still hand /dev/kvm to a container.
    docker image inspect sac-emu >/dev/null 2>&1 || docker build -q -t sac-emu - <<'EOF'
FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 libasound2t64 libgl1 libegl1 \
    libxkbfile1 libdrm2 libgbm1 libxdamage1 libxrandr2 libxkbcommon0 libxkbcommon-x11-0 \
    libx11-xcb1 libxcb-cursor0 libbsd0 libdbus-1-3 libfontconfig1 libc++1 ca-certificates \
  && rm -rf /var/lib/apt/lists/*
EOF
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$CONTAINER" --network host --device /dev/kvm \
      --group-add "$(getent group kvm | cut -d: -f3)" -u "$(id -u):$(id -g)" \
      -e HOME="$HOME" -e ANDROID_HOME="$ANDROID_HOME" -e ANDROID_SDK_ROOT="$ANDROID_HOME" \
      -v "$ANDROID_HOME:$ANDROID_HOME" -v "$HOME/.android:$HOME/.android" \
      sac-emu "$ANDROID_HOME/emulator/emulator" "${args[@]}" >/dev/null
  fi
  wait_boot
}

install_app() {
  local apk
  apk="$(ls "$ROOT"/apps/android/app/build/outputs/apk/debug/*.apk | head -1)"
  "$ADB" install -r "$apk"
  "$ADB" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
}

# One line per element with text or content-desc: `"label" x y` (x y = tap centre).
ui() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null
  "$ADB" shell cat /sdcard/ui.xml | tr '>' '\n' \
    | grep -oE '(text|content-desc)="[^"]+"[^/]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/^(text|content-desc)="([^"]*)".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\2|\3|\4|\5|\6/' \
    | awk -F'|' '{ printf "\"%s\" %d %d\n", $1, ($2+$4)/2, ($3+$5)/2 }' \
    | sed 's/&amp;/\&/g; s/&quot;/"/g; s/&#10;/ /g'
}

tap_text() {
  local line
  line="$(ui | grep -F "\"$1" | head -1)"
  [[ -n "$line" ]] || { echo "no element starting with: $1" >&2; exit 1; }
  # shellcheck disable=SC2086
  "$ADB" shell input tap ${line##*\" }
}

case "${1:-}" in
  setup) setup ;;
  build) shift; build "$@" ;;
  start) start ;;
  install) install_app ;;
  ui) ui ;;
  tap-text) tap_text "$2" ;;
  open-uri) "$ADB" shell am start -a android.intent.action.VIEW -d "\"$2\"" "$PACKAGE" >/dev/null ;;
  shot) "$ADB" exec-out screencap -p >"${2:-screen.png}"; echo "${2:-screen.png}" ;;
  logs) "$ADB" logcat -d --pid="$("$ADB" shell pidof "$PACKAGE" | tr -d '\r')" ;;
  stop) docker rm -f "$CONTAINER" >/dev/null 2>&1 || "$ADB" emu kill ;;
  *) sed -n '2,15p' "$0"; exit 2 ;;
esac
