# filter-bar

## Contract

- **Tier:** `organism`
- **Kotlin name:** `SourceFilterBar`
- **Allowed dependencies:** `source-chip`, `button`

Persistent source filter summary with a one-tap clear action.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
@Composable
fun SourceFilterBar(
    sourceName: String,
    visibleCount: Int,
    totalCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`src=studio-mac` is fixture data; there is no genuine visual enum in the current export.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `src=studio-mac` | [HTML](./src-studio-mac.html) | [PNG](./src-studio-mac.png) |

## Builder note

> The filter that Connection details opens the inbox with. Always visible, always one tap to clear.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Do not create an enum from the concrete source name.
