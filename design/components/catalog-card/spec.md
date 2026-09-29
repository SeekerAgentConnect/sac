# catalog-card

Hand-written for SEE-176. The Stage 7.2 export has no Discover screen, so this component has no
generated `<variant>.html` / `<variant>.png` yet; it is built only from captured atoms and theme
tokens, and its references follow the next re-export ([UPDATING.md](../../UPDATING.md)).

## Contract

- **Tier:** `molecule`
- **Kotlin name:** `CatalogCard`
- **Allowed dependencies:** `source-avatar`, `network-chip`, `button`

One feed in the Discover tab's catalog: what it is called, what its operator says about it, who may
read it, which Solana networks it runs on, and where this phone stands with it — with the one action
that fits. The card itself opens the feed's catalog detail; the action button does the action.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above
is exhaustive for other design components.

## Kotlin API

```kotlin
enum class CatalogCardAccess { Public, Restricted }

enum class CatalogCardStatus { Available, Working, Waiting, Connected, Stopped }

data class CatalogCardModel(
    val name: String,
    val initials: String,
    val description: String,
    val access: CatalogCardAccess,
    val accessLabel: String,
    val networks: List<NetworkChipNetwork>,
    /** Where this phone stands, or null when nothing has been added. */
    val statusText: String?,
    val actionLabel: String,
)

@Composable
fun CatalogCard(
    model: CatalogCardModel,
    status: CatalogCardStatus,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
)
```

## Layout

- Surface `surface1`, radius `lg`, padding `xl`, children spaced `md`; full width.
- Header row: `SourceAvatar` (the server-row avatar), then the name in `bodyLarge` (one line,
  ellipsized) and, under it, the access label in `labelMedium` — `onSurfaceVariant` for Public,
  `primary` for Restricted, so a restricted feed reads as such before its description.
- Description: `bodyMedium`, `onSurfaceVariant`, at most three lines, ellipsized. The detail sheet
  shows the whole text.
- Networks: one `NetworkChip` per declared network, spaced `xs`. A feed that declares none shows
  none.
- Footer row: the status text in `bodyMedium` (`onSurfaceVariant`), weighted to fill, then the
  action as a `SeekerButton` of size `Sm`.

## Status → action button

| `CatalogCardStatus` | Meaning | Button variant |
| --- | --- | --- |
| `Available` | Nothing added: **Connect** (public) or **Request access** (restricted) | `Filled` |
| `Working` | An add or an access request is in flight | `Disabled` |
| `Waiting` | Added, restricted, waiting for the publisher's decision | `Neutral` |
| `Connected` | Added and readable: **Open** | `Tonal` |
| `Stopped` | Added, but rejected, revoked, expired or otherwise not readable | `Neutral` |

The status text and the action label are caller content: the app says what the persisted access
state is, and never says "Connected" for a restricted feed that is not.

## Detail sheet

`CatalogDetailSheet(state: CatalogDetailSheetState, onClose, onAction)` is the same feed in a
`SheetScaffold` (`Plain`): the avatar with the access label and status text, the whole description
in `bodyLarge`, the network chips, a one-sentence access explanation, `FactRow`s (networks, required
plugins, gateway and server ID in `MonoWrap`), a closing `bodySmall` caption, and two `Lg` buttons —
**Close** (`Neutral`) and the card's action (`Filled` while available, `Disabled` while working,
`Tonal` otherwise). Allowed dependencies add `sheet-scaffold` and `fact-row`.

## Open questions

- Visual references are pending a Claude Design re-export that adds the Discover screen and this
  card. Until then the Roborazzi goldens under `app/src/test/snapshots/images/screens/discover*.png`
  are the approved rendering.
