# signal-label

## Contract

- **Tier:** `atom`
- **Kotlin name:** `SignalLabel`
- **Allowed dependencies:** none

The unique compact mark that distinguishes a public feed signal from a private request.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class SignalLabelContext { Standard, OnTile }

@Composable
fun SignalLabel(
    context: SignalLabelContext = SignalLabelContext.Standard,
    modifier: Modifier = Modifier,
)
```

`origin=signal` is invariant; the bare `onTile` flag maps to `SignalLabelContext.OnTile`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `origin=signal` | [HTML](./origin-signal.html) | [PNG](./origin-signal.png) |
| `origin=signal onTile` | [HTML](./origin-signal-ontile.html) | [PNG](./origin-signal-ontile.png) |

## Builder note

> chipBase on surf3. The only mark that separates a feed signal from a request.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Do not add a one-value origin enum unless a later export introduces another origin label.
