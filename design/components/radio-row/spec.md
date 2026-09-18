# radio-row

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `RadioRow`
- **Allowed dependencies:** none

Accessible labelled radio choice used for asset kind.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class RadioRowState { On, Off }

@Composable
fun RadioRow(
    label: String,
    state: RadioRowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`state` maps one-for-one to `RadioRowState`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=off` | [HTML](./state-off.html) | [PNG](./state-off.png) |
| `state=on` | [HTML](./state-on.html) | [PNG](./state-on.png) |

## Builder note

> radio(on) + dot(on). Asset kind in the rule editor.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The parent must provide radio-group collection semantics.
