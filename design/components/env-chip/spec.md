# env-chip

## Contract

- **Tier:** `atom`
- **Kotlin name:** `EnvironmentChip`
- **Allowed dependencies:** none

Makes production versus sandbox execution explicit on each item.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class EnvironmentChipEnvironment { Production, Sandbox }
enum class EnvironmentChipVerbosity { Full, Short }

@Composable
fun EnvironmentChip(
    environment: EnvironmentChipEnvironment,
    verbosity: EnvironmentChipVerbosity = EnvironmentChipVerbosity.Full,
    modifier: Modifier = Modifier,
)
```

`env` maps to `EnvironmentChipEnvironment`; the bare `short` flag maps to `EnvironmentChipVerbosity.Short`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `env=production` | [HTML](./env-production.html) | [PNG](./env-production.png) |
| `env=sandbox` | [HTML](./env-sandbox.html) | [PNG](./env-sandbox.png) |
| `env=sandbox short` | [HTML](./env-sandbox-short.html) | [PNG](./env-sandbox-short.png) |

## Builder note

> envChip(env). Sandbox borrows the tertiary container so it never reads as a normal state.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- Specify which surfaces permit the short sandbox label; production has no short specimen.
