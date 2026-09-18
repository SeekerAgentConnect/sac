# network-chip

## Contract

- **Tier:** `atom`
- **Kotlin name:** `NetworkChip`
- **Allowed dependencies:** none

Shows the Solana network for one item; it is never a global app badge.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class NetworkChipNetwork { Devnet, Mainnet }

@Composable
fun NetworkChip(
    network: NetworkChipNetwork,
    modifier: Modifier = Modifier,
)
```

`network` maps to `NetworkChipNetwork`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `network=devnet` | [HTML](./network-devnet.html) | [PNG](./network-devnet.png) |
| `network=mainnet` | [HTML](./network-mainnet.html) | [PNG](./network-mainnet.png) |

## Builder note

> netChipSm. Per item, never global: an item with no chain shows none.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The design component accepts Testnet, but no `network=testnet` specimen exists. Add that specimen before adding a Kotlin enum value.
