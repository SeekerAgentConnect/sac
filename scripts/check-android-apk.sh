#!/usr/bin/env bash
# Checks a release APK against release/components.json (SEE-182).
#
#   bash scripts/check-android-apk.sh <apk>                          # unsigned candidate
#   bash scripts/check-android-apk.sh <apk> --signed <cert sha-256>   # the signed release
#
# Always: the package is the manifest's applicationId and the APK carries the manifest's
# versionName and versionCode — so the bytes about to be published are the version being released.
# Unsigned: says plainly that the signature was not verified. Signed: apksigner must verify it, and
# the signing certificate must be exactly the pinned one, or an update would be refused by every
# phone that has the app installed. Prints the certificate's SHA-256 either way it can.
#
# Needs Android SDK Build Tools (aapt2, apksigner) under $ANDROID_HOME or $ANDROID_SDK_ROOT.
set -euo pipefail

apk="${1:?usage: check-android-apk.sh <apk> [--signed <cert sha-256>]}"
mode="${2:-}"
pinned="${3:-}"
root="$(cd "$(dirname "$0")/.." && pwd)"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] || [ ! -d "$sdk/build-tools" ]; then
  echo "::error::no Android SDK build tools: set ANDROID_HOME" >&2
  exit 1
fi
tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"

read -r expected_id expected_name expected_code < <(node -e '
  const manifest = require(process.argv[1]);
  const app = manifest.components.find((entry) => entry.id === "android");
  console.log(app.android.applicationId, app.version, app.android.versionCode);
' "$root/release/components.json")

badging="$("$tools/aapt2" dump badging "$apk")"
badging="$(head -n 1 <<<"$badging")"
package="$(sed -n "s/.* name='\([^']*\)'.*/\1/p" <<<"$badging")"
code="$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
name="$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")"

fail=0
[ "$package" = "$expected_id" ] || { echo "::error::package is $package, not $expected_id"; fail=1; }
[ "$name" = "$expected_name" ] || { echo "::error::versionName is $name, not $expected_name"; fail=1; }
[ "$code" = "$expected_code" ] || { echo "::error::versionCode is $code, not $expected_code"; fail=1; }
[ "$fail" = 0 ] || exit 1
echo "package=$package versionName=$name versionCode=$code"

normalize() { tr -d ': ' | tr '[:upper:]' '[:lower:]'; }

if [ "$mode" = "--signed" ]; then
  if [ -z "$pinned" ]; then
    echo "::error::no pinned certificate SHA-256 to compare the signature with"
    exit 1
  fi
  certs="$("$tools/apksigner" verify --verbose --print-certs "$apk")"
  # minSdk 31 means apksigner signs with scheme v3 (v2 when an older minSdk needs it); either is a
  # full-APK signature. v1 alone would not be.
  grep -Eq '^Verified using v(2|3|3\.1) scheme .*: true$' <<<"$certs" || {
    echo "::error::the APK has no APK Signature Scheme v2/v3 signature"
    exit 1
  }
  digests="$(sed -n 's/^.*[Ss]igner.*certificate SHA-256 digest: //p' <<<"$certs" | normalize | sort -u)"
  if [ "$(grep -c . <<<"$digests")" != 1 ]; then
    echo "::error::expected exactly one signing certificate, found: $digests"
    exit 1
  fi
  actual="$digests"
  if [ "$actual" != "$(normalize <<<"$pinned")" ]; then
    echo "::error::signed with certificate $actual, not the pinned $(normalize <<<"$pinned")"
    exit 1
  fi
  echo "certificate_sha256=$actual"
  echo "signature: verified, certificate matches the pin"
else
  if "$tools/apksigner" verify "$apk" > /dev/null 2>&1; then
    echo "::error::expected an unsigned candidate, but the APK verifies as signed"
    exit 1
  fi
  echo "signature: NOT VERIFIED — unsigned release candidate, not installable as the app"
fi
