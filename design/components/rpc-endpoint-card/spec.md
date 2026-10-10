# rpc-endpoint-card

Hand-written for SEE-184. The Stage 7.2 export has no settings for Solana endpoints, so this
component has no generated `<variant>.html` / `<variant>.png` yet; it is built only from captured
atoms and theme tokens, and its references follow the next re-export ([UPDATING.md](../../UPDATING.md)).

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `RpcEndpointCard`
- **Allowed dependencies:** `network-chip`, `text-field`, `button`

One Solana network's endpoint on the **Solana RPC** sheet: which endpoint this phone asks about
that network now, where that setting comes from, what asking it last found, a field for the owner's
own endpoint, and the two actions — check and save it, or go back to the build's own. There is one
card per network (mainnet, devnet, testnet), all on one sheet: the networks are independent, and
nothing here chooses an "active" one.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above
is exhaustive for other design components.

## Kotlin API

```kotlin
enum class RpcEndpointStatus { Unknown, Checking, Serves, Problem }

data class RpcEndpointCardModel(
    val network: NetworkChipNetwork,
    /** The host in use and where it comes from, e.g. "rpc.example.com · your setting". */
    val inUse: String,
    /** What the last check found, in a sentence; null before any check. */
    val statusText: String?,
    val fieldLabel: String,
    val fieldValue: String,
    val fieldPlaceholder: String,
    /** Why the typed URL is refused, or why saving it failed; null when there is nothing to say. */
    val fieldError: String?,
    val saveLabel: String,
    val resetLabel: String,
    /** False while a check is running or there is nothing typed. */
    val canSave: Boolean,
    /** False when the owner has no setting of their own for this network. */
    val canReset: Boolean,
)

@Composable
fun RpcEndpointCard(
    model: RpcEndpointCardModel,
    status: RpcEndpointStatus,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
)
```

## Layout

- Surface `surface1`, radius `lg`, padding `xl`, children spaced `md`; full width.
- Header: the `NetworkChip` for the network (never flagged), then the in-use text in `bodyMedium`,
  `onSurface`, one line, ellipsized. An endpoint is only ever shown by its **host**; a build's URL
  can carry a key.
- Status line, `bodyMedium`: `onSurfaceVariant` for `Unknown` and `Checking`, `primaryText` for
  `Serves`, `errorText` for `Problem`. Hidden when `statusText` is null.
- `SeekerTextField` (`Error` state while `fieldError` is set, `Rest` otherwise), URI keyboard, no
  reserved error space.
- Actions row, spaced `xs`: **Check and save** (`SeekerButton`, `Sm`, `Filled` when `canSave`,
  `Disabled` otherwise) and **Use build default** (`Sm`, `Neutral` when `canReset`, `Disabled`
  otherwise).

## Sheet

`SolanaRpcSheet(title, explanation, cards, caption, closeLabel, …)` is the cards in a
`SheetScaffold` (`Plain`): an explanation in `bodyMedium` (`onSurfaceVariant`), one card per
network, a closing `bodySmall` caption, and a single **Close** action (`Neutral`, `Lg`). Allowed
dependencies add `sheet-scaffold`. It is opened from the Wallet tab (navigation.md).

## Open questions

- Visual references are pending a Claude Design re-export that adds this sheet. Until then the
  Roborazzi goldens under `designsystem/src/test/snapshots/images/rpc-endpoint-card/` are the
  approved rendering.
