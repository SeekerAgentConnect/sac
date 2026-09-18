# source-chip

## Contract

- **Tier:** `atom`
- **Kotlin name:** `SourceChip`
- **Allowed dependencies:** none

Source identity chip whose colour remains stable for the same source everywhere.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class SourceChipSize { Standard, Compact }
enum class SourceChipWidth { Natural, Truncated }

@Composable
fun SourceChip(
    sourceName: String,
    size: SourceChipSize = SourceChipSize.Standard,
    width: SourceChipWidth = SourceChipWidth.Natural,
    modifier: Modifier = Modifier,
)
```

`size=13` maps to `Compact`; the default maps to `Standard`. `longest` and concrete `src` values are fixture data. `truncating at 120px` maps to `SourceChipWidth.Truncated` and must use the tokenized fixed width from the reference, not a `Dp` API parameter.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `src=feed size=13` | [HTML](./src-feed-size-13.html) | [PNG](./src-feed-size-13.png) |
| `src=feed size=13 longest` | [HTML](./src-feed-size-13-longest.html) | [PNG](./src-feed-size-13-longest.png) |
| `src=hermes-box` | [HTML](./src-hermes-box.html) | [PNG](./src-hermes-box.png) |
| `src=runner-node` | [HTML](./src-runner-node.html) | [PNG](./src-runner-node.png) |
| `src=studio-mac` | [HTML](./src-studio-mac.html) | [PNG](./src-studio-mac.png) |
| `truncating at 120px` | [HTML](./truncating-at-120px.html) | [PNG](./truncating-at-120px.png) |

## Builder note

> srcChip(name, size). Colour comes from srvOf(name), so one source keeps one colour everywhere.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Define deterministic source-colour assignment and collision behaviour before implementation.
- Confirm whether feed and direct sources share one colour allocator.
