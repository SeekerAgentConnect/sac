# tab-bar

## Contract

- **Tier:** `organism`
- **Kotlin name:** `InboxTabBar`
- **Allowed dependencies:** none

Full-width Pending/History selector for the Inbox destination.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class InboxTab { Pending, History }

@Composable
fun InboxTabBar(
    selected: InboxTab,
    onSelect: (InboxTab) -> Unit,
    modifier: Modifier = Modifier,
)
```

`selected` maps to `InboxTab`; only `pending` is captured.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `selected=pending` | [HTML](./selected-pending.html) | [PNG](./selected-pending.png) |

## Builder note

> tab(on). Two tabs, full width of the screen, the selected one underlined in primary.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Add a `selected=history` specimen before accepting the History rendering as covered.
