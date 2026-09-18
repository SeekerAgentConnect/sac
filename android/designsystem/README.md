# Android design system

`:designsystem` owns Seeker Agent Connect's visual tokens and theme. It deliberately depends only
on Compose Material 3: app models, ViewModels, storage, transports, network clients, and wallet code
belong in `:app` and cannot be imported across this Gradle boundary.

The token values come from the rendered token specimens in
`design/export/components.html`: the complete light/dark palettes and source-chip colours, the
12–36 type scale, the 4–28 radius scale, and the 2–32 spacing scale. `SeekerTheme` installs those
tokens and also disables Material tonal elevation, surface tint, implicit minimum component size,
and default ripple colour.

## Fonts

The design export uses Roboto 400/500/700 and Roboto Mono for identifiers. Android bundles the
matching variable TrueType fonts from the official Google Fonts repositories under the OFL:

| Resource | Upstream | SHA-256 |
| --- | --- | --- |
| `roboto_variable.ttf` | `google/fonts/ofl/roboto/Roboto[wdth,wght].ttf` | `d7598e12c5dbef095ff8272cfc55da0250bd07fbdecbac8a530b9b277872a134` |
| `roboto_mono_variable.ttf` | `google/fonts/ofl/robotomono/RobotoMono[wght].ttf` | `66a80e79d17e4c7cabd162e2916578a4cc08fd19eef6e2a643305eae9c567b2b` |

The license texts are checked in under `licenses/`.

## Guardrail

`checkDesignSystemLiterals` rejects `Color(0x...)`, raw `.dp`, and raw `.sp` literals in production
Kotlin outside this module's `theme` package. Both `./gradlew check` and `pnpm check:android` run the
guard, preventing screens from recreating local token sets.
