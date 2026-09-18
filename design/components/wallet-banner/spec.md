# wallet-banner

## Contract

- **Tier:** `organism`
- **Kotlin name:** `WalletBanner`
- **Allowed dependencies:** `icon-button` is captured but omitted from SEE-111

Connected-wallet summary in compact Home and expanded Wallet-screen forms.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class WalletBannerVariant { Compact, Expanded }

@Composable
fun WalletBanner(
    walletName: String,
    address: String,
    statusText: String?,
    variant: WalletBannerVariant,
    onCopyAddress: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`variant` maps one-for-one to `WalletBannerVariant`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `variant=compact` | [HTML](./variant-compact.html) | [PNG](./variant-compact.png) |
| `variant=expanded` | [HTML](./variant-expanded.html) | [PNG](./variant-expanded.png) |

## Builder note

> The wallet, in the two places it appears. Primary container both times; the address is mono, shortened on Home and whole on the Wallet screen.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Copy uses the omitted `icon-button` contract.
- Compact address shortening must be deterministic and accessible without replacing the full copied value.
