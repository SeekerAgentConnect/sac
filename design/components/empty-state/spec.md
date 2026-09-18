# empty-state

## Contract

- **Tier:** `organism`
- **Kotlin name:** `EmptyState`
- **Allowed dependencies:** none

Explains an empty list and the available recovery action or consequence.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class EmptyStateScreen { Inbox, Rules }

@Composable
fun EmptyState(
    screen: EmptyStateScreen,
    title: String?,
    body: String,
    modifier: Modifier = Modifier,
)
```

`screen` maps one-for-one to `EmptyStateScreen`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `screen=inbox` | [HTML](./screen-inbox.html) | [PNG](./screen-inbox.png) |
| `screen=rules` | [HTML](./screen-rules.html) | [PNG](./screen-rules.png) |

## Builder note

> What a list says when there is nothing in it, and what to do about it.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Confirm whether screen-specific copy belongs to the caller or is fixed by the enum; the component itself must not hardcode product strings.
