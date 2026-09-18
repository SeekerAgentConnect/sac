# text-field

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `DesignTextField`
- **Allowed dependencies:** none

Labelled text input whose validation message sits below the field.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class DesignTextFieldState { Rest, Error }

@Composable
fun DesignTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    state: DesignTextFieldState,
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
)
```

`state` maps one-for-one to `DesignTextFieldState`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=error` | [HTML](./state-error.html) | [PNG](./state-error.png) |
| `state=rest` | [HTML](./state-rest.html) | [PNG](./state-rest.png) |

## Builder note

> field(bad) + fieldInput. The error line sits under the field, never inside it.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Keyboard type, masking, and validation are caller concerns; do not create visual variants for them.
