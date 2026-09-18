# fab

## Contract

- **Tier:** `atom`
- **Kotlin name:** `PrimaryFab`
- **Allowed dependencies:** none

The single large primary action a screen may offer.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class PrimaryFabVariant { Filled }
enum class PrimaryFabWidth { Wrap, Full }

@Composable
fun PrimaryFab(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    variant: PrimaryFabVariant = PrimaryFabVariant.Filled,
    width: PrimaryFabWidth = PrimaryFabWidth.Wrap,
    modifier: Modifier = Modifier,
)
```

`variant=filled` maps to `PrimaryFabVariant.Filled`; `width=full` maps to `PrimaryFabWidth.Full`. `icon=add` is fixture content, not an enum.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `variant=filled icon=add` | [HTML](./variant-filled-icon-add.html) | [PNG](./variant-filled-icon-add.png) |
| `variant=filled width=full` | [HTML](./variant-filled-width-full.html) | [PNG](./variant-filled-width-full.png) |

## Builder note

> The one large action a screen can offer. 56 high, radius 16, icon then label.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The two specimens couple full width and icon choice; the Kotlin API must keep those independent.
