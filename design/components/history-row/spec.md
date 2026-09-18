# history-row

## Contract

- **Tier:** `organism`
- **Kotlin name:** `HistoryRow`
- **Allowed dependencies:** `source-chip`, `signal-label`

One terminal or no-answer outcome in Inbox history.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class HistoryRowState {
    Sent, Simulated, Dismissed, Cancelled, Expired, Unknown
}

data class HistoryRowModel(
    val title: String,
    val sourceName: String,
    val outcomeText: String,
    val timestampText: String,
    val isSignal: Boolean,
)

@Composable
fun HistoryRow(
    model: HistoryRowModel,
    state: HistoryRowState,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

`state` maps one-for-one to `HistoryRowState`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=cancelled` | [HTML](./state-cancelled.html) | [PNG](./state-cancelled.png) |
| `state=dismissed` | [HTML](./state-dismissed.html) | [PNG](./state-dismissed.png) |
| `state=expired` | [HTML](./state-expired.html) | [PNG](./state-expired.png) |
| `state=sent` | [HTML](./state-sent.html) | [PNG](./state-sent.png) |
| `state=simulated` | [HTML](./state-simulated.html) | [PNG](./state-simulated.png) |
| `state=unknown` | [HTML](./state-unknown.html) | [PNG](./state-unknown.png) |

## Builder note

> Every outcome the inbox can end in, including the ones that need no answer.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The design source names additional uncaptured outcomes (`confirmed`, `signed`, `declined`, `rejected`, `acknowledged`, `wallet`). Add specimens before adding enum values.
