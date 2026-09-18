# request-tile

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `RequestTile`
- **Allowed dependencies:** `source-chip`, `signal-label`, `verdict-pill`

One pending item in the Home rail. Rail and centred presentations are states of this single component.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class RequestTileKind {
    Acknowledgement, PredictionSignal, SwapSignal, SignatureRequest, Transfer
}
enum class RequestTileRailState { InRail, Centred }

data class RequestTileModel(
    val title: String,
    val sourceName: String,
    val supportingText: String,
    val warningCount: Int,
    val signatureByteCount: Int? = null,
    val assetSymbol: String? = null,
)

@Composable
fun RequestTile(
    model: RequestTileModel,
    kind: RequestTileKind,
    railState: RequestTileRailState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`kind` maps `ack/pred/sig/sign/tx` to the unambiguous enum values above; `state` maps to `RequestTileRailState`. There is one component and one source helper for both states.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `kind=ack state=centred` | [HTML](./kind-ack-state-centred.html) | [PNG](./kind-ack-state-centred.png) |
| `kind=ack state=in-rail` | [HTML](./kind-ack-state-in-rail.html) | [PNG](./kind-ack-state-in-rail.png) |
| `kind=pred state=centred` | [HTML](./kind-pred-state-centred.html) | [PNG](./kind-pred-state-centred.png) |
| `kind=pred state=in-rail` | [HTML](./kind-pred-state-in-rail.html) | [PNG](./kind-pred-state-in-rail.png) |
| `kind=sig state=centred` | [HTML](./kind-sig-state-centred.html) | [PNG](./kind-sig-state-centred.png) |
| `kind=sig state=in-rail` | [HTML](./kind-sig-state-in-rail.html) | [PNG](./kind-sig-state-in-rail.png) |
| `kind=sign state=centred` | [HTML](./kind-sign-state-centred.html) | [PNG](./kind-sign-state-centred.png) |
| `kind=sign state=in-rail` | [HTML](./kind-sign-state-in-rail.html) | [PNG](./kind-sign-state-in-rail.png) |
| `kind=tx state=centred` | [HTML](./kind-tx-state-centred.html) | [PNG](./kind-tx-state-centred.png) |
| `kind=tx state=in-rail` | [HTML](./kind-tx-state-in-rail.html) | [PNG](./kind-tx-state-in-rail.png) |

## Builder note

> mkCard(key, centred). One tile for every kind of pending item; a signal differs by the Signal label and the feed-coloured source chip. 214×200 of content plus 16px of padding on each side, so the box measures 246×232. Only ever inside the Home rail.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The raw key `sig` means swap signal while `sign` means signature request; never carry those ambiguous abbreviations into Kotlin.
