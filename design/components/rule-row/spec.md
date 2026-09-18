# rule-row

## Contract

- **Tier:** `organism`
- **Kotlin name:** `RuleRow`
- **Allowed dependencies:** `icon-button` is captured but omitted from SEE-111

One action, asset, recipient, or program entry inside a rule section.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class RuleRowKind { Action, Asset, Program, Recipient }
enum class RuleRowState { Default, Checked, Unchecked, ReadOnly }

data class RuleRowModel(
    val title: String,
    val supportingText: String,
    val iconName: String,
)

@Composable
fun RuleRow(
    model: RuleRowModel,
    kind: RuleRowKind,
    state: RuleRowState = RuleRowState.Default,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

`kind` and `state` map one-for-one to the enums above; absent `state` maps to `Default`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `kind=action state=checked` | [HTML](./kind-action-state-checked.html) | [PNG](./kind-action-state-checked.png) |
| `kind=action state=unchecked` | [HTML](./kind-action-state-unchecked.html) | [PNG](./kind-action-state-unchecked.png) |
| `kind=asset` | [HTML](./kind-asset.html) | [PNG](./kind-asset.png) |
| `kind=asset state=readonly` | [HTML](./kind-asset-state-readonly.html) | [PNG](./kind-asset-state-readonly.png) |
| `kind=program` | [HTML](./kind-program.html) | [PNG](./kind-program.png) |
| `kind=recipient` | [HTML](./kind-recipient.html) | [PNG](./kind-recipient.png) |

## Builder note

> rowWrap(tappable). One row for each thing a rule section can list; the delete affordance disappears when the section is inherited.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Only Action captures checked states and only Asset captures read-only. Treat other combinations as unsupported until captured.
- Deletion uses the omitted `icon-button` contract.
