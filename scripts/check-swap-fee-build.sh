#!/usr/bin/env bash
# Checks the SAC swap fee build configuration (SEE-173, docs/development/swap-fee-config.md).
#
#   bash scripts/check-swap-fee-build.sh
#
# A default build must generate a zero fee; a complete fee configuration — given as Gradle
# properties or as environment variables — must reach BuildConfig exactly; and every malformed or
# incomplete configuration must fail the build at configuration time. Only BuildConfig is generated,
# so this runs in seconds and spends nothing.
#
# The addresses are synthetic (32 repeated bytes: 0x42, 0x43, 0x44). They belong to no operator.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
gradle=("$root/apps/android/gradlew" -p "$root/apps/android" -q --console=plain)
config="$root/apps/android/app/build/generated/source/buildConfig/debug/io/github/brrenat/seekervault/BuildConfig.java"

OWNER=5TeWSsjg2gbxCyWVniXeCmwM7UtHTCK7svzJr5xYJzHf
USDC_ACCOUNT=5XZobBCgcyuBM4m1E1rZVei7Me6V8FzwSLexuU194KcN
WSOL_ACCOUNT=5bV6jUfhDHCQVA1WfKBUnXUsboJgoKgkzkKcxr3joew5
USDC=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v
WSOL=So11111111111111111111111111111111111111112
RPC=https://rpc.example.invalid

# Nothing inherited from the caller's environment may change what is being checked.
unset SEEKERVAULT_SWAP_FEE_BPS SEEKERVAULT_SWAP_FEE_OWNER SEEKERVAULT_SWAP_FEE_ACCOUNTS

generate() { "${gradle[@]}" :app:generateDebugBuildConfig "$@"; }

expect_field() {
  local name="$1" value="$2"
  if ! grep -qF "$name = $value;" "$config"; then
    echo "FAIL: BuildConfig.$name is not $value:" >&2
    grep -F "$name" "$config" >&2 || true
    exit 1
  fi
}

expect_refused() {
  local what="$1"
  shift
  local log
  if log="$("$@" 2>&1)"; then
    echo "FAIL: accepted $what" >&2
    exit 1
  fi
  if ! grep -q "Invalid SAC swap fee configuration" <<<"$log"; then
    echo "FAIL: $what failed for another reason:" >&2
    echo "$log" | tail -20 >&2
    exit 1
  fi
  echo "ok: refuses $what"
}

generate
expect_field SWAP_FEE_BPS 0
expect_field SWAP_FEE_OWNER '""'
expect_field SWAP_FEE_ACCOUNTS '""'
echo "ok: a default build charges nothing"

generate -Pseekervault.solanaRpc="$RPC" -Pseekervault.swapFee.bps=20 \
  -Pseekervault.swapFee.owner="$OWNER" \
  -Pseekervault.swapFee.accounts="$USDC=$USDC_ACCOUNT, $WSOL=$WSOL_ACCOUNT"
expect_field SWAP_FEE_BPS 20
expect_field SWAP_FEE_OWNER "\"$OWNER\""
expect_field SWAP_FEE_ACCOUNTS "\"$USDC=$USDC_ACCOUNT,$WSOL=$WSOL_ACCOUNT\""
echo "ok: a fee build from Gradle properties"

SEEKERVAULT_SWAP_FEE_BPS=15 SEEKERVAULT_SWAP_FEE_OWNER="$OWNER" \
  SEEKERVAULT_SWAP_FEE_ACCOUNTS="$USDC=$USDC_ACCOUNT" \
  generate -Pseekervault.solanaRpc="$RPC"
expect_field SWAP_FEE_BPS 15
expect_field SWAP_FEE_ACCOUNTS "\"$USDC=$USDC_ACCOUNT\""
echo "ok: a fee build from environment variables"

# A property wins over its environment variable.
SEEKERVAULT_SWAP_FEE_BPS=15 SEEKERVAULT_SWAP_FEE_OWNER="$OWNER" \
  SEEKERVAULT_SWAP_FEE_ACCOUNTS="$USDC=$USDC_ACCOUNT" \
  generate -Pseekervault.solanaRpc="$RPC" -Pseekervault.swapFee.bps=0
expect_field SWAP_FEE_BPS 0
expect_field SWAP_FEE_ACCOUNTS '""'
echo "ok: a property overrides the environment"

full=(-Pseekervault.solanaRpc="$RPC" -Pseekervault.swapFee.owner="$OWNER"
  -Pseekervault.swapFee.accounts="$USDC=$USDC_ACCOUNT")
expect_refused "a rate above 100 bps" generate "${full[@]}" -Pseekervault.swapFee.bps=101
expect_refused "a fractional rate" generate "${full[@]}" -Pseekervault.swapFee.bps=2.5
expect_refused "a negative rate" generate "${full[@]}" -Pseekervault.swapFee.bps=-1
expect_refused "a rate with no owner" generate -Pseekervault.solanaRpc="$RPC" \
  -Pseekervault.swapFee.bps=20 -Pseekervault.swapFee.accounts="$USDC=$USDC_ACCOUNT"
expect_refused "a rate with no accounts" generate -Pseekervault.solanaRpc="$RPC" \
  -Pseekervault.swapFee.bps=20 -Pseekervault.swapFee.owner="$OWNER"
expect_refused "a rate with no Solana endpoint" generate -Pseekervault.swapFee.bps=20 \
  -Pseekervault.swapFee.owner="$OWNER" -Pseekervault.swapFee.accounts="$USDC=$USDC_ACCOUNT"
expect_refused "an owner that is not an address" generate "${full[@]}" \
  -Pseekervault.swapFee.bps=20 -Pseekervault.swapFee.owner=not-an-address
expect_refused "an entry without an account" generate "${full[@]}" \
  -Pseekervault.swapFee.bps=20 -Pseekervault.swapFee.accounts="$USDC"
expect_refused "the same mint twice" generate "${full[@]}" -Pseekervault.swapFee.bps=20 \
  -Pseekervault.swapFee.accounts="$USDC=$USDC_ACCOUNT,$USDC=$WSOL_ACCOUNT"
expect_refused "the owner wallet as a token account" generate "${full[@]}" \
  -Pseekervault.swapFee.bps=20 -Pseekervault.swapFee.accounts="$USDC=$OWNER"
expect_refused "a malformed entry even at zero bps" generate -Pseekervault.swapFee.bps=0 \
  -Pseekervault.swapFee.accounts="$USDC=nope"

echo "swap fee build configuration: all checks passed"
