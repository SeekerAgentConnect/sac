# switch-row

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `SwitchRow`
- **Allowed dependencies:** none

Accessible labelled switch used only to enable or disable a rule section.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class SwitchRowState { On, Off }

@Composable
fun SwitchRow(
    label: String,
    state: SwitchRowState,
    onStateChange: (SwitchRowState) -> Unit,
    modifier: Modifier = Modifier,
)
```

`state` maps one-for-one to `SwitchRowState`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=off` | [HTML](./state-off.html) | [PNG](./state-off.png) |
| `state=on` | [HTML](./state-on.html) | [PNG](./state-on.png) |

## Builder note

> swTrack(on) + swKnob(on). Only ever used to turn a rule section on or off.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Use native switch semantics while matching the exported track and knob.
