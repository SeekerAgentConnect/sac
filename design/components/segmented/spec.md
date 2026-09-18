# segmented

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `SegmentedControl`
- **Allowed dependencies:** none

Mutually exclusive two- or three-option selector.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class SegmentedCount { Two, Three }
enum class SegmentedUsage { Standard, RuleMode }

@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    count: SegmentedCount,
    usage: SegmentedUsage = SegmentedUsage.Standard,
    modifier: Modifier = Modifier,
)
```

`count` maps to `SegmentedCount`; the bare `mode` flag maps to `SegmentedUsage.RuleMode`. `selected` is bounded data, not an enum.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `count=2 mode` | [HTML](./count-2-mode.html) | [PNG](./count-2-mode.png) |
| `count=2 selected=0` | [HTML](./count-2-selected-0.html) | [PNG](./count-2-selected-0.png) |
| `count=3 selected=1` | [HTML](./count-3-selected-1.html) | [PNG](./count-3-selected-1.png) |

## Builder note

> seg(on, pos) with pos l / m / r. Two or three options only.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- `count=2 mode` omits a `selected` label although the fixture selects Override; resolve this label/API inconsistency before implementation.
