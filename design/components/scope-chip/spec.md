# scope-chip

## Contract

- **Tier:** `atom`
- **Kotlin name:** `ScopeChip`
- **Allowed dependencies:** none

Names the rule set that produced a verdict or daily-limit line.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class ScopeChipSource { Global, Connection, None }

@Composable
fun ScopeChip(
    source: ScopeChipSource,
    modifier: Modifier = Modifier,
)
```

`from` maps to `ScopeChipSource`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `from=connection` | [HTML](./from-connection.html) | [PNG](./from-connection.png) |
| `from=global` | [HTML](./from-global.html) | [PNG](./from-global.png) |
| `from=none` | [HTML](./from-none.html) | [PNG](./from-none.png) |

## Builder note

> chips[from]. Says which rule set produced a verdict line.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- None in the current export.
