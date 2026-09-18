# request-carousel

## Contract

- **Tier:** `organism`
- **Kotlin name:** `RequestCarousel`
- **Allowed dependencies:** `request-tile`

Newest-first snapping Home rail that browses pending items without answering them.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class RequestCarouselState { Rest }

data class RequestCarouselItem(
    val id: String,
    val tile: RequestTileModel,
    val kind: RequestTileKind,
)

@Composable
fun RequestCarousel(
    items: List<RequestCarouselItem>,
    centredIndex: Int,
    state: RequestCarouselState = RequestCarouselState.Rest,
    onItemClick: (RequestCarouselItem) -> Unit,
    modifier: Modifier = Modifier,
)
```

`RequestTileModel` and `RequestTileKind` are the public contracts of the allowed `request-tile` dependency. `state=rest` maps to `RequestCarouselState.Rest`; `centred` is an index, not an enum.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `state=rest centred=0` | [HTML](./state-rest-centred-0.html) | [PNG](./state-rest-centred-0.png) |

## Builder note

> The Home rail: every pending item, newest first, snapping one tile at a time. It only browses — nothing is answered here.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The specimen says `centred=0`, but its source assigns cards built with `centred=false`; clarify which tile should visually use `RequestTileRailState.Centred` before implementation.
