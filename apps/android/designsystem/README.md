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
use the dark `SeekerTheme`, native Robolectric graphics, and a
`w390dp-h1500dp-xxhdpi` maximum measurement window at 3×. The taller ceiling lets the five
unrolled review sheets record at natural height; wrap-content component previews keep their own
dimensions. No emulator is involved. Each preview also has a
`@DesignRef`; its component and variant map to the same `<component>/<variant-slug>.png` path as
the design export. See [`design/README.md`](../../design/README.md) for the commands and comparison
contract.

`:app` carries the same scanner dependencies and tester configuration. SEE-121 enabled its scanner
for the five exact-copy full-screen previews.

Approved captures are committed below each module's `src/test/snapshots/images/` directory.
`recordRoborazziDebug` is an explicit maintainer action after visual review;
`verifyRoborazziDebug` is the CI regression guard and never updates a baseline. The separate
`build/outputs/roborazzi-comparison/` directories hold disposable failure artifacts.

Debug builds also expose a live component gallery. Long-press the debug app icon, choose
**Component gallery**, and select a component/variant. Its catalog calls the exact preview fixture
functions used by the scanner, and a unit test requires the catalog paths to equal the complete
`@DesignRef` corpus. The activity, shortcut, and gallery implementation live only in debug source
sets and are absent from release builds.

## Atom library

SEE-117 adds the shared chips, verdict/signal marks, source avatar, button, FAB, and accessible
switch/check/radio rows. Their public state axes and visual verification record are documented in
[`docs/wiki/design-system-atoms.md`](../../docs/wiki/design-system-atoms.md). The same internal
deterministic source palette drives `SourceChip` and `SourceAvatar`.

Every captured atom variant has one exact-text `@Preview` and `@DesignRef`. The committed
[`reference | actual` review](../../docs/reviews/see-117/README.md) is the PR evidence set. The
icon-button remains deferred because its required hand-written component spec is absent.

## Molecule library

SEE-118 adds fact and daily-limit rows, segmented and Inbox tab selectors, navigation items,
section headers, text fields, notice cards, empty states, and source-filter bars. Their public APIs,
state axes, ticket/spec resolutions, and visual verification record are documented in
[`docs/wiki/design-system-molecules.md`](../../docs/wiki/design-system-molecules.md).

Each of the 22 captured component variants has one exact-text `@Preview` and `@DesignRef`. The
committed [`reference | actual` review](../../docs/reviews/see-118/README.md) is the PR evidence set;
an additional 390dp preview composes four `NavItem` instances into the required bottom bar.

## Organism library

SEE-119 adds request tiles and their snapping carousel, merged Inbox and Activity-history rows,
server and rule rows, verdict and owner-input cards, terms summaries, wallet banners and handoff,
and the reusable sheet scaffold. The public APIs, composition boundaries, missing-specimen notes,
and ticket/spec resolutions are documented in
[`docs/wiki/design-system-organisms.md`](../../docs/wiki/design-system-organisms.md).

All 43 generated organism variants have exact-copy `@Preview` and `@DesignRef` coverage. The
committed [`reference | actual` review](../../docs/reviews/see-119/README.md) contains every pair.
The carousel follows SEE-119's 358dp viewport and centred highlight while retaining SEE-81's
start/centre/end snapping policy.

## Review-sheet template

SEE-120 adds one natural-height `ReviewSheet` and a UI-only `ReviewSheetState` for Transfer, Swap,
Prediction, Signature, and Acknowledge. Requests and signals differ only in mapped data. The
template composes the existing design-system tiers, owns warning confirmation, and applies the
caller's action gate after requiring that confirmation. Five exact-copy fixtures record against
the canonical unrolled `design/screens/sheet-*.png` references; see the
[`reference | actual` review](../../docs/reviews/see-120/README.md).

## Guardrail

`checkDesignSystemLiterals` rejects `Color(0x...)`, raw `.dp`, and raw `.sp` literals in production
Kotlin outside this module's `theme` package. Both `./gradlew check` and `pnpm check:android` run the
guard, preventing screens from recreating local token sets.
