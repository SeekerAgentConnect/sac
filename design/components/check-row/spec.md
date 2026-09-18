# check-row

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `CheckRow`
- **Allowed dependencies:** none

Accessible labelled checkbox for expected actions and explicit warning acknowledgement.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class CheckRowState { Checked, Unchecked }
enum class CheckRowContentKind { Standard, WarningAcknowledgement }

@Composable
fun CheckRow(
    label: String,
    state: CheckRowState,
    onStateChange: (CheckRowState) -> Unit,
    contentKind: CheckRowContentKind = CheckRowContentKind.Standard,
    modifier: Modifier = Modifier,
)
```

`state` maps to `CheckRowState`; `label=warning-ack` maps to `CheckRowContentKind.WarningAcknowledgement`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=checked` | [HTML](./state-checked.html) | [PNG](./state-checked.png) |
| `state=unchecked` | [HTML](./state-unchecked.html) | [PNG](./state-unchecked.png) |
| `state=unchecked label=warning-ack` | [HTML](./state-unchecked-label-warning-ack.html) | [PNG](./state-unchecked-label-warning-ack.png) |

## Builder note

> Used for expected actions and for the read-the-warning tick in a review.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The warning acknowledgement wraps to a taller row; height must follow content rather than be forced to the standard specimen.
