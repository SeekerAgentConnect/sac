# section-header

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `SectionHeader`
- **Allowed dependencies:** `button`

Section label with an optional small trailing action.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class SectionHeaderTrailing { None, Button }

@Composable
fun SectionHeader(
    title: String,
    trailing: SectionHeaderTrailing,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

`trailing` maps one-for-one to `SectionHeaderTrailing`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `trailing=button` | [HTML](./trailing-button.html) | [PNG](./trailing-button.png) |
| `trailing=none` | [HTML](./trailing-none.html) | [PNG](./trailing-none.png) |

## Builder note

> The label that opens a group on Home, with an optional small action on the right.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- When `trailing=button`, require both label and callback and render `DesignButton` with the captured small tonal contract.
