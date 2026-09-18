# server-row

## Contract

- **Tier:** `organism`
- **Kotlin name:** `ServerRow`
- **Allowed dependencies:** `source-avatar`; `icon-button` is captured but omitted from SEE-111

Paired server summary with the one trailing action its current state permits.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class ServerRowState { Connected, Unreachable, Disconnected }

data class ServerRowModel(
    val sourceName: String,
    val initials: String,
    val statusText: String,
)

@Composable
fun ServerRow(
    model: ServerRowModel,
    state: ServerRowState,
    onOpen: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

`state` maps one-for-one to `ServerRowState`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=connected` | [HTML](./state-connected.html) | [PNG](./state-connected.png) |
| `state=disconnected` | [HTML](./state-disconnected.html) | [PNG](./state-disconnected.png) |
| `state=unreachable` | [HTML](./state-unreachable.html) | [PNG](./state-unreachable.png) |

## Builder note

> A paired server on Home. The trailing control is what the state allows: open it, retry it, or nothing.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The row consumes `iconBtn`, but `icon-button` is a captured data-component omitted from SEE-111's spec list. Add its atom contract or explicitly authorize a stock token-styled icon action before implementation.
