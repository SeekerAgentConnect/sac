# verdict-pill

## Contract

- **Tier:** `atom`
- **Kotlin name:** `VerdictPill`
- **Allowed dependencies:** none

Compact advisory outcome shown on tiles and rows.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class VerdictPillVerdict { Ok, Warning }
enum class VerdictPillContext { Standard, OnTile }

@Composable
fun VerdictPill(
    verdict: VerdictPillVerdict,
    warningCount: Int? = null,
    context: VerdictPillContext = VerdictPillContext.Standard,
    modifier: Modifier = Modifier,
)
```

`verdict` maps to `VerdictPillVerdict`; the bare `onTile` flag maps to `VerdictPillContext.OnTile`. `count` is dynamic data, not an enum.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `verdict=ok` | [HTML](./verdict-ok.html) | [PNG](./verdict-ok.png) |
| `verdict=ok onTile` | [HTML](./verdict-ok-ontile.html) | [PNG](./verdict-ok-ontile.png) |
| `verdict=warning` | [HTML](./verdict-warning.html) | [PNG](./verdict-warning.png) |
| `verdict=warning count=3` | [HTML](./verdict-warning-count-3.html) | [PNG](./verdict-warning-count-3.png) |

## Builder note

> pill(ok, onTile). The rail tile passes onTile so the pill reads on the lime ground.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The captured warning without `count` implies one warning; confirm whether `null` should render singular text or be rejected.
