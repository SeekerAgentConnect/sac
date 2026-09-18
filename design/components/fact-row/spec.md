# fact-row

## Contract

- **Tier:** `organism`
- **Kotlin name:** `FactRow`
- **Allowed dependencies:** none

Label/value fact row that keeps labels readable and wraps identifiers safely.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class FactRowValueStyle { Plain, Mono, MonoWrap }

@Composable
fun FactRow(
    label: String,
    value: String,
    valueStyle: FactRowValueStyle,
    modifier: Modifier = Modifier,
)
```

`value=short` maps to `Plain`; `value=mono` maps to `Mono`; `value=longest wraps` and `value=full-address` map to `MonoWrap` stress fixtures.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `value=full-address` | [HTML](./value-full-address.html) | [PNG](./value-full-address.png) |
| `value=longest wraps` | [HTML](./value-longest-wraps.html) | [PNG](./value-longest-wraps.png) |
| `value=mono` | [HTML](./value-mono.html) | [PNG](./value-mono.png) |
| `value=short` | [HTML](./value-short.html) | [PNG](./value-short.png) |

## Builder note

> Label left, value right. A value longer than the row breaks by character in mono rather than shrinking the label.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- `longest wraps` and `full-address` are content stress cases, not separate production enum values.
