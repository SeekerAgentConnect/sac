# SAC swap service fee: build configuration (SEE-173)

An APK can add an integrator fee to swaps routed through Metis (Jupiter's v1 Swap API), credited to
public token accounts the app's owner controls. **The default build charges no service fee and
needs no configuration.** This page is the operator's reference: the settings, their bounds, how to
prepare the receiving accounts, how to build, and how to check a fee-bearing swap on mainnet. What
the phone does with the setting is in [integrations/jupiter.md](../integrations/jupiter.md#the-sac-service-fee-see-173).

## What an APK carries

A rate, public addresses, and the complete Solana RPC URL used to read those addresses — all
extractable client configuration. Anyone with the APK can recover them. Use only a public endpoint
or a provider key deliberately intended for distribution in a client, with appropriate quota and
abuse limits. If the provider requires a key to remain secret, do not embed that endpoint in the
APK. Never put a seed phrase, private signing key or secret Jupiter/RPC credential in these
settings. The fee is credited by the swap program to token accounts you created beforehand;
neither the app nor the owner's users can create or fund them, and the user is never charged for
setting them up.

Feed publishers, direct servers, QR codes and Jupiter's own answers cannot change the rate or the
recipient. The phone reads them from `BuildConfig` only.

## Settings

| Gradle property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `seekervault.swapFee.bps` | `SEEKERVAULT_SWAP_FEE_BPS` | `0` | Whole basis points of the swap's output: `0`–`100` (1%). `20` = 0.2%. `0` turns the fee off. |
| `seekervault.swapFee.owner` | `SEEKERVAULT_SWAP_FEE_OWNER` | empty | The public wallet address that owns every receiving token account. |
| `seekervault.swapFee.accounts` | `SEEKERVAULT_SWAP_FEE_ACCOUNTS` | empty | Comma-separated `<mint>=<token account>` pairs: the account that receives the fee **in that mint**. |
| `seekervault.solanaRpc` | — | empty | The app's read-only Solana endpoint. **Required when the rate is not zero**: the phone reads each fee account through it before using it. The full URL is embedded in and extractable from the APK; use only a public endpoint or an intentionally client-distributed, appropriately limited key. |

**Precedence.** A Gradle property (`-P…`, `gradle.properties`, or `ORG_GRADLE_PROJECT_…`) wins over
its environment variable; an empty value counts as unset.

**Bounds and validation, at configuration time.** The build fails with `Invalid SAC swap fee
configuration: …` when the rate is not a whole number from 0 to 100; when a nonzero rate has no
owner, no accounts or no `seekervault.solanaRpc`; when any address is not a base58 public key; when
an entry is not `mint=account`; when a mint is listed twice; or when an account is the owner wallet
itself or the mint itself. Anything set is validated even at rate 0, so a half-finished setting is
caught early. The program's own field would accept up to 255 bps; the app's bound is 100.

**Mint/account policy.** The fee is taken in the swap's **output** mint, and only when that mint has
an entry. A single wallet address is never treated as a token account for every pair. Pairs whose
output mint has no entry swap with no fee, and the review says "not charged on this pair". Configure
the output tokens your users receive most — typically USDC and wrapped SOL (`So1111…112`, which
also covers swaps into native SOL).

Supported: classic SPL Token accounts only. A Token-2022 account, or a pair whose accounts the
swap inspector does not read, carries no fee.

## Preparing the receiving accounts

Once, before shipping a fee build, from the fee wallet (with the Solana CLI and `spl-token`, or any
wallet that creates token accounts):

```bash
# The associated token account of the fee wallet for each mint you list.
spl-token create-account EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v --owner <FEE_WALLET>   # USDC
spl-token create-account So11111111111111111111111111111111111111112 --owner <FEE_WALLET>    # wrapped SOL
spl-token address --token EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v --owner <FEE_WALLET> --verbose
```

Each account must exist, be owned by the Token program, hold the listed mint, belong to
`seekervault.swapFee.owner`, and be initialized and not frozen. The phone checks exactly that
before every fee-bearing quote; if any of it fails, or the endpoint cannot answer, the swap is
prepared **without** a fee and the review says the fee account could not be verified. Do not close
the accounts while fee builds are in use.

## Building

A default APK (no fee):

```bash
apps/android/gradlew -p apps/android :app:assembleRelease
```

A fee-enabled APK (placeholders — substitute your own public values):

```bash
apps/android/gradlew -p apps/android :app:assembleRelease \
  -Pseekervault.solanaRpc=https://<your-mainnet-rpc> \
  -Pseekervault.swapFee.bps=20 \
  -Pseekervault.swapFee.owner=<FEE_WALLET> \
  -Pseekervault.swapFee.accounts=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v=<FEE_USDC_ACCOUNT>,So11111111111111111111111111111111111111112=<FEE_WSOL_ACCOUNT>
```

The same with environment variables (CI):

```bash
export SEEKERVAULT_SWAP_FEE_BPS=20
export SEEKERVAULT_SWAP_FEE_OWNER=<FEE_WALLET>
export SEEKERVAULT_SWAP_FEE_ACCOUNTS=<MINT>=<ACCOUNT>,<MINT>=<ACCOUNT>
apps/android/gradlew -p apps/android :app:assembleRelease -Pseekervault.solanaRpc=https://<your-mainnet-rpc>
```

An explicit zero-fee build, overriding anything in the environment:

```bash
apps/android/gradlew -p apps/android :app:assembleRelease -Pseekervault.swapFee.bps=0
```

`BuildConfig.SWAP_FEE_BPS`, `SWAP_FEE_OWNER` and `SWAP_FEE_ACCOUNTS` carry the validated, normalized
values (owner and accounts are empty whenever the rate is 0). `BuildConfig.SOLANA_RPC` carries the
complete RPC URL, including any query or path key, so treating that URL as a CI secret does not keep
it secret from APK recipients.

**CI.** `.github/workflows/release.yml` builds the official APK for an `android-vX.Y.Z` tag. It
reads `SEEKERVAULT_SWAP_FEE_BPS`, `SEEKERVAULT_SWAP_FEE_OWNER` and `SEEKERVAULT_SWAP_FEE_ACCOUNTS`
from **repository variables** (public values, not secrets; unset means no fee) and the Solana
endpoint from the `SEEKERVAULT_SOLANA_RPC` **repository variable** (with the per-network
`SEEKERVAULT_SOLANA_RPC_MAINNET`/`_DEVNET`/`_TESTNET` beside it). Like the rest of the client
configuration, that endpoint is extractable from the APK: set it only to a public endpoint or a
client key that is intended for distribution and has suitable limits. Do not put a provider key
that must remain secret there. The workflow records the fee configuration in the run summary,
zipaligns and signs the APK with the release key from the protected release environment, checks
its certificate against `SAC_ANDROID_SIGNING_CERT_SHA256`, and publishes it as the `sac-X.Y.Z.apk`
asset of the GitHub Release. A manual dispatch with `dry_run` builds only the unsigned candidate,
uploads it as a workflow artifact and reports signing as not verified; it publishes nothing.
[releases.md](releases.md) is the procedure and lists every variable and secret.

`pnpm check:swap-fee-build` (run in CI's Android job) proves the default, both ways of configuring a
fee, the precedence, and every refusal above, by generating `BuildConfig` only.

## Sign, verify and install the release APK

Official releases are signed by CI, as above; this section is for a build made locally or the
unsigned candidate of a dry run. `assembleRelease` deliberately produces an **unsigned** APK. Use
the same protected release keystore for every update to an installed app. Do not commit the
keystore or its passwords. With Android SDK Build Tools and `adb` installed, sign the locally built artifact
like this; `apksigner` prompts for the keystore/key passwords rather than exposing them in the
command line:

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
export SAC_RELEASE_KEYSTORE=/path/to/sac-release.jks
export SAC_RELEASE_KEY_ALIAS=sac-release

BUILD_TOOLS="$(find "$ANDROID_SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
UNSIGNED=apps/android/app/build/outputs/apk/release/app-release-unsigned.apk
ALIGNED=apps/android/app/build/outputs/apk/release/app-release-aligned.apk
SIGNED=apps/android/app/build/outputs/apk/release/app-release-signed.apk

"$BUILD_TOOLS/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$SAC_RELEASE_KEYSTORE" \
  --ks-key-alias "$SAC_RELEASE_KEY_ALIAS" \
  --out "$SIGNED" \
  "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED"
adb install --replace "$SIGNED"
```

For a downloaded dry-run artifact, set `UNSIGNED` to the downloaded `.apk`; the remaining steps
are identical. An unsigned release artifact is not installable as the normal app and must not be
called a distributable release.

## What the user sees

Before signing: **Swap routing: Metis · Powered by Jupiter**; the SAC service fee rate "taken from
what you receive"; the provider's estimate in the output token; the recipient token account; the
priority fee separately; and the minimum received, net of every fee. A zero-fee build shows the
service fee as 0% with "network and pool costs still apply", never "free". The wallet hand-off
repeats the routing and the fee; History keeps the approved rate, estimate, token and recipient,
labelled **estimated at review**.

## Manual mainnet verification (opt-in, spends real funds)

Automated checks never spend anything. To confirm a fee is actually credited, the owner can:

1. Build a fee APK as above for a small rate (e.g. `20`) with a USDC account, then complete the
   signing, signature-verification and `adb install` steps above.
2. Note the fee account's balance: `spl-token balance --address <FEE_USDC_ACCOUNT>`.
3. From a separate wallet holding a little SOL, act on a SOL → USDC swap signal for a small amount.
   On the review, record the rate, the estimated fee and the recipient; approve and sign.
4. Once History shows **Confirmed on the network**, open the transaction on the explorer and check
   the token balance changes: the fee account's USDC increases by about the estimated fee (the chain
   takes the rate of the actual route output, so it can differ slightly from the estimate).
5. Re-read the balance from step 2 to confirm the difference.

A build, a fixture or an unsigned transaction is not evidence of a collected fee; only this is.
