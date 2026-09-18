# owner-input-card

## Contract

- **Tier:** `organism`
- **Kotlin name:** `OwnerInputCard`
- **Allowed dependencies:** `button`

Shows which signal parameters belong to the owner and whether they have been chosen.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class OwnerInputCardState { Chosen, Unchosen }
enum class OwnerInputCardKind { Swap, Prediction }

@Composable
fun OwnerInputCard(
    kind: OwnerInputCardKind,
    state: OwnerInputCardState,
    summary: String?,
    onChooseOrEdit: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`state` and `kind` map one-for-one to the enums above.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=chosen kind=prediction` | [HTML](./state-chosen-kind-prediction.html) | [PNG](./state-chosen-kind-prediction.png) |
| `state=chosen kind=swap` | [HTML](./state-chosen-kind-swap.html) | [PNG](./state-chosen-kind-swap.png) |
| `state=unchosen kind=swap` | [HTML](./state-unchosen-kind-swap.html) | [PNG](./state-unchosen-kind-swap.png) |

## Builder note

> A signal carries terms, never an amount. Until the owner chooses, the final action stays disabled.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The export does not include `state=unchosen kind=prediction`; add it before claiming the full cross-product is covered.
