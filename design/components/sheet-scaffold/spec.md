# sheet-scaffold

## Contract

- **Tier:** `organism`
- **Kotlin name:** `SheetScaffold`
- **Allowed dependencies:** `button`; `icon-button` is captured but omitted from SEE-111

Standard bottom-sheet structure with pinned header/actions and nested-sheet backdrop treatment.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class SheetScaffoldVariant { Plain, StackedOverBlurred }

@Composable
fun SheetScaffold(
    title: String,
    variant: SheetScaffoldVariant,
    onClose: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
)
```

`variant` maps one-for-one to `SheetScaffoldVariant`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `variant=plain` | [HTML](./variant-plain.html) | [PNG](./variant-plain.png) |
| `variant=stacked-over-blurred` | [HTML](./variant-stacked-over-blurred.html) | [PNG](./variant-stacked-over-blurred.png) |

## Builder note

> Every bottom sheet is this: grabber, a title row with one close, a scrolling body, and an action row pinned to the bottom. Sheets stack rather than replace, so the one underneath stays legible as a dimmed, blurred edge.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Close uses the omitted `icon-button` contract.
- Define nested-sheet focus/accessibility behavior and verify the captured blur/scale treatment is feasible in Compose.
