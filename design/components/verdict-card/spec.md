# verdict-card

## Contract

- **Tier:** `organism`
- **Kotlin name:** `VerdictCard`
- **Allowed dependencies:** `scope-chip`, `button`

The sole advisory rule assessment surface, including provenance for each warning.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class VerdictCardVerdict { Ok, Warning }

data class VerdictWarning(
    val message: String,
    val scopeLabel: String,
)

@Composable
fun VerdictCard(
    verdict: VerdictCardVerdict,
    warnings: List<VerdictWarning>,
    onRulesClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`verdict` maps to `VerdictCardVerdict`; `count` is derived from `warnings`, not an enum.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `verdict=ok` | [HTML](./verdict-ok.html) | [PNG](./verdict-ok.png) |
| `verdict=warning count=1` | [HTML](./verdict-warning-count-1.html) | [PNG](./verdict-warning-count-1.png) |
| `verdict=warning count=3` | [HTML](./verdict-warning-count-3.html) | [PNG](./verdict-warning-count-3.png) |

## Builder note

> Advisory only, and the one place a rule ever shows itself. Lime when nothing is flagged, tertiary when something is; one line per warning, each tagged with the rule set it came from.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Define an explicit content model for skipped or unconfigured checks before rendering them; do not infer them from warning count.
