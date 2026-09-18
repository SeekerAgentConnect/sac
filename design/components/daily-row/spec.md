# daily-row

## Contract

- **Tier:** `organism`
- **Kotlin name:** `DailyLimitRow`
- **Allowed dependencies:** `scope-chip`

Projected effect of this approval against the applicable daily limit.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class DailyLimitRowState { Within, Over, NoLimit }
enum class DailyLimitRowScope { Global, Connection }

@Composable
fun DailyLimitRow(
    headline: String,
    supportingText: String,
    state: DailyLimitRowState,
    scope: DailyLimitRowScope,
    modifier: Modifier = Modifier,
)
```

`state` and `scope` map one-for-one to the enums above.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=nolimit scope=global` | [HTML](./state-nolimit-scope-global.html) | [PNG](./state-nolimit-scope-global.png) |
| `state=over scope=connection` | [HTML](./state-over-scope-connection.html) | [PNG](./state-over-scope-connection.png) |
| `state=within scope=global` | [HTML](./state-within-scope-global.html) | [PNG](./state-within-scope-global.png) |

## Builder note

> What an approval would cost against a daily limit. In sandbox the card above it is titled as hypothetical, because nothing is consumed.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The export captures only three combinations; amounts and arithmetic are model data, and uncaptured combinations must not invent styling.
