# terms-card

## Contract

- **Tier:** `organism`
- **Kotlin name:** `TermsCard`
- **Allowed dependencies:** none

Complete quoted operation facts: payment, expected return, enforced floor, slippage, and route.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class TermsCardKind { Swap }
enum class TermsCardState { Quoted }

data class TermsCardRow(
    val label: String,
    val value: String,
)

@Composable
fun TermsCard(
    rows: List<TermsCardRow>,
    kind: TermsCardKind = TermsCardKind.Swap,
    state: TermsCardState = TermsCardState.Quoted,
    modifier: Modifier = Modifier,
)
```

`kind` and `state` map to their current one-value enums so later exported variants cannot silently reuse this appearance.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `kind=swap state=quoted` | [HTML](./kind-swap-state-quoted.html) | [PNG](./kind-swap-state-quoted.png) |

## Builder note

> The quoted operation: what the owner pays, what comes back, and the slippage it was checked against.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Decide from a future design whether prediction terms extend this component or require a separate data-component.
