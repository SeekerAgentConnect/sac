# nav-item

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `NavigationItem`
- **Allowed dependencies:** none

One destination in the four-item bottom navigation.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class NavigationItemState { Rest, Selected }

@Composable
fun NavigationItem(
    label: String,
    icon: @Composable () -> Unit,
    state: NavigationItemState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`state` maps one-for-one to `NavigationItemState`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=rest` | [HTML](./state-rest.html) | [PNG](./state-rest.png) |
| `state=selected` | [HTML](./state-selected.html) | [PNG](./state-selected.png) |

## Builder note

> nav(on) + ind(on). One of four in the bottom bar; the pill behind the icon is the only selected state.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Icon and label are caller content, not additional variants.
