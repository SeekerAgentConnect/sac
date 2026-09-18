# source-avatar

## Contract

- **Tier:** `atom`
- **Kotlin name:** `SourceAvatar`
- **Allowed dependencies:** none

Two-letter source avatar using the same source-owned colour as `source-chip`.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
@Composable
fun SourceAvatar(
    sourceName: String,
    initials: String,
    modifier: Modifier = Modifier,
)
```

Concrete `src` labels are fixture data, not enum variants; every specimen uses the same visual state.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `src=hermes-box` | [HTML](./src-hermes-box.html) | [PNG](./src-hermes-box.png) |
| `src=runner-node` | [HTML](./src-runner-node.html) | [PNG](./src-runner-node.png) |
| `src=studio-mac` | [HTML](./src-studio-mac.html) | [PNG](./src-studio-mac.png) |

## Builder note

> avatar(name). Two letters on the source’s own colour; the same colour as its chip.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Define initials derivation and collision handling before implementation.
- Share the colour allocator with `SourceChip`; do not duplicate it.
