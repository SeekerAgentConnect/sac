# button

## Contract

- **Tier:** `atom`
- **Kotlin name:** `DesignButton`
- **Allowed dependencies:** none

Shared text action with design-owned geometry and visual tone.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class DesignButtonVariant {
    Filled, Tonal, Neutral, Error, ErrorStrong, Tertiary, Disabled
}
enum class DesignButtonSize { Sm, Md, Lg }

@Composable
fun DesignButton(
    label: String,
    onClick: () -> Unit,
    variant: DesignButtonVariant,
    size: DesignButtonSize,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
)
```

`variant` maps one-for-one to `DesignButtonVariant`; `size` maps one-for-one to `DesignButtonSize`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `variant=disabled size=lg` | [HTML](./variant-disabled-size-lg.html) | [PNG](./variant-disabled-size-lg.png) |
| `variant=disabled size=md` | [HTML](./variant-disabled-size-md.html) | [PNG](./variant-disabled-size-md.png) |
| `variant=disabled size=sm` | [HTML](./variant-disabled-size-sm.html) | [PNG](./variant-disabled-size-sm.png) |
| `variant=error size=lg` | [HTML](./variant-error-size-lg.html) | [PNG](./variant-error-size-lg.png) |
| `variant=error size=md` | [HTML](./variant-error-size-md.html) | [PNG](./variant-error-size-md.png) |
| `variant=error size=sm` | [HTML](./variant-error-size-sm.html) | [PNG](./variant-error-size-sm.png) |
| `variant=errorStrong size=lg` | [HTML](./variant-errorstrong-size-lg.html) | [PNG](./variant-errorstrong-size-lg.png) |
| `variant=errorStrong size=md` | [HTML](./variant-errorstrong-size-md.html) | [PNG](./variant-errorstrong-size-md.png) |
| `variant=errorStrong size=sm` | [HTML](./variant-errorstrong-size-sm.html) | [PNG](./variant-errorstrong-size-sm.png) |
| `variant=filled size=lg` | [HTML](./variant-filled-size-lg.html) | [PNG](./variant-filled-size-lg.png) |
| `variant=filled size=md` | [HTML](./variant-filled-size-md.html) | [PNG](./variant-filled-size-md.png) |
| `variant=filled size=sm` | [HTML](./variant-filled-size-sm.html) | [PNG](./variant-filled-size-sm.png) |
| `variant=neutral size=lg` | [HTML](./variant-neutral-size-lg.html) | [PNG](./variant-neutral-size-lg.png) |
| `variant=neutral size=md` | [HTML](./variant-neutral-size-md.html) | [PNG](./variant-neutral-size-md.png) |
| `variant=neutral size=sm` | [HTML](./variant-neutral-size-sm.html) | [PNG](./variant-neutral-size-sm.png) |
| `variant=tertiary size=lg` | [HTML](./variant-tertiary-size-lg.html) | [PNG](./variant-tertiary-size-lg.png) |
| `variant=tertiary size=md` | [HTML](./variant-tertiary-size-md.html) | [PNG](./variant-tertiary-size-md.png) |
| `variant=tertiary size=sm` | [HTML](./variant-tertiary-size-sm.html) | [PNG](./variant-tertiary-size-sm.png) |
| `variant=tonal size=lg` | [HTML](./variant-tonal-size-lg.html) | [PNG](./variant-tonal-size-lg.png) |
| `variant=tonal size=md` | [HTML](./variant-tonal-size-md.html) | [PNG](./variant-tonal-size-md.png) |
| `variant=tonal size=sm` | [HTML](./variant-tonal-size-sm.html) | [PNG](./variant-tonal-size-sm.png) |

## Builder note

> btn(variant, size). Geometry comes from the size, ink from the variant; a screen only adds flex or alignment.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The export models disabled as a visual `variant=disabled`; decide whether the implementation also requires `enabled=false` while preserving the exact enum name.
- The builder supports a leading icon, but no button specimen exercises it.
