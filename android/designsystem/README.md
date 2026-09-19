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

The variable-font entries in `Type.kt` set the `wght` variation axis explicitly for Roboto
400/500/700 and Roboto Mono 400/500. `FontWeightProbe` exercises those exact resources under
Robolectric's native graphics renderer. The three Roboto weights render differently, the bundled
face differs from the platform default, and the Mono `i`/`M` samples keep equal advance, so no
static-TTF fallback is needed.

## JVM preview captures

Roborazzi and ComposablePreviewScanner render every `@Preview` in this module on the JVM. Captures
use the dark `SeekerTheme`, native Robolectric graphics, and
`w390dp-h844dp-xxhdpi` (390×844 dp at 3×). No emulator is involved. Each preview also has a
`@DesignRef`; its component and variant map to the same `<component>/<variant-slug>.png` path as
the design export. See [`design/README.md`](../../design/README.md) for the commands and comparison
contract.

`:app` carries the same scanner dependencies and tester configuration with generation disabled.
SEE-121 only needs to add its design references and enable that scanner when full-screen previews
move there.

## Atom library

SEE-117 adds the shared chips, verdict/signal marks, source avatar, button, FAB, and accessible
switch/check/radio rows. Their public state axes and visual verification record are documented in
[`docs/wiki/design-system-atoms.md`](../../docs/wiki/design-system-atoms.md). The same internal
deterministic source palette drives `SourceChip` and `SourceAvatar`.

Every captured atom variant has one exact-text `@Preview` and `@DesignRef`. The committed
[`reference | actual` review](../../docs/reviews/see-117/README.md) is the PR evidence set. The
icon-button remains deferred because its required hand-written component spec is absent.

## Guardrail

`checkDesignSystemLiterals` rejects `Color(0x...)`, raw `.dp`, and raw `.sp` literals in production
Kotlin outside this module's `theme` package. Both `./gradlew check` and `pnpm check:android` run the
guard, preventing screens from recreating local token sets.
