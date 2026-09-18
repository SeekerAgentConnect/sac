# inbox-row

## Contract

- **Tier:** `organism`
- **Kotlin name:** `InboxRow`
- **Allowed dependencies:** `source-chip`, `signal-label`, `verdict-pill`, `env-chip`, `network-chip`, `button`

Merged pending request/signal row with one Review action.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class InboxRowOrigin { Request, Signal }
enum class InboxRowVerdict { Ok, Warning }
enum class InboxRowTitleLines { One, Two }

data class InboxRowModel(
    val title: String,
    val supportingText: String,
    val sourceName: String,
    val timestampAndExpiryText: String,
    val environmentText: String,
    val networkText: String?,
    val warningCount: Int,
)

@Composable
fun InboxRow(
    model: InboxRowModel,
    origin: InboxRowOrigin,
    verdict: InboxRowVerdict,
    titleLines: InboxRowTitleLines = InboxRowTitleLines.One,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`origin`, `verdict`, and `title=two-line` map to the enums above. Warning count, environment, and nullable network are model data even when encoded in fixture labels.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `network=none env=production` | [HTML](./network-none-env-production.html) | [PNG](./network-none-env-production.png) |
| `origin=request verdict=ok` | [HTML](./origin-request-verdict-ok.html) | [PNG](./origin-request-verdict-ok.png) |
| `origin=request verdict=warning` | [HTML](./origin-request-verdict-warning.html) | [PNG](./origin-request-verdict-warning.png) |
| `origin=signal count=3 title=two-line` | [HTML](./origin-signal-count-3-title-two-line.html) | [PNG](./origin-signal-count-3-title-two-line.png) |
| `origin=signal verdict=warning` | [HTML](./origin-signal-verdict-warning.html) | [PNG](./origin-signal-verdict-warning.png) |

## Builder note

> The merged inbox row. One Review button: approval and simulation happen inside the sheet, after the owner’s inputs and the warnings.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The labels encode several independent edge cases rather than a complete cross-product; keep those as model data and use only captured combinations in previews.
