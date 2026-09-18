# wallet-handoff

## Contract

- **Tier:** `organism`
- **Kotlin name:** `WalletHandoff`
- **Allowed dependencies:** `button`

Reference for the external wallet step and its three distinct exits.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class WalletHandoffWallet { SeedVault }
enum class WalletHandoffKind { Transfer }

@Composable
fun WalletHandoff(
    summary: String,
    wallet: WalletHandoffWallet,
    kind: WalletHandoffKind,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onLeaveWithoutAnswering: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`wallet` and `kind` map to their current one-value enums so future exports cannot silently reuse this layout.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `wallet=seed-vault kind=transfer` | [HTML](./wallet-seed-vault-kind-transfer.html) | [PNG](./wallet-seed-vault-kind-transfer.png) |

## Builder note

> The other app, shown for what it is. Three ways out: sign, decline, or leave without answering — the last of which is the one the phone cannot tell apart from a crash.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Confirm whether this is only a reference for the wallet hand-off sheet or UI the app owns; it must never impersonate Seed Vault Wallet.
- No signature hand-off specimen exists.
