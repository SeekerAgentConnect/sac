# Design-system atoms

SEE-117 adds the first reusable component tier to Android's visual-only `:designsystem` module.
Every public component is stateless, applies its `Modifier` to the outer node, and accepts content
and typed state rather than colour, size, or typography overrides.

## Components

| Design component | Compose API | State axes |
| --- | --- | --- |
| `verdict-pill` | `VerdictPill` | verdict, warning count, standard/on-tile context |
| `signal-label` | `SignalLabel` | standard/on-tile context |
| `source-chip` | `SourceChip` | source name, standard/compact size, natural/truncated width |
| `env-chip` | `EnvChip` | production/sandbox, full/short wording |
| `network-chip` | `NetworkChip` | devnet/mainnet; callers omit it for an item with no chain |
| `scope-chip` | `ScopeChip` | global/connection/not configured |
| `button` | `SeekerButton` | nine visual variants, sm/md/lg, optional leading icon |
| `source-avatar` | `SourceAvatar` | source name and caller-supplied initials |
| `switch-row` | `SwitchRow` | on/off |
| `check-row` | `CheckRow` | checked/unchecked and standard/warning acknowledgement |
| `radio-row` | `RadioRow` | on/off |
| `fab` | `SeekerFab` | filled, wrap/full width, caller-supplied icon |

`SourceChip` and `SourceAvatar` share one deterministic source-colour allocator: lowercase UTF-8
FNV-1a selects one of the six source palette pairs. A collision shares a colour, while the visible
source name or initials remains the identity. The same source string therefore keeps one colour
without persisting presentation state. A caller can pass a `SourceColour` instead, which is how a
paired server's stored marker replaces the hash (SEE-83). Captured specimens pass none, so they
stay on the colour the guide drew for that name.

The button heights are 32dp, 40dp, and 48dp; the FAB is 56dp high. Compact chip geometry remains
24dp, except the on-tile signal label at 22dp. `SeekerTheme` disables Material's implicit minimum
component size so these visible bounds stay exact; screens remain responsible for providing larger
touch areas where the layout needs them.

## Visual verification

There is one dark `@Preview` and `@DesignRef` for each of the 53 implemented guide variants. The
recorded Roborazzi output is compared at 3× with the corresponding HTML capture.

Height, padding, radius, type weight, line height, and token-backed colour match for every pair
except the tertiary button issue below. Hug-content widths differ by at most 3 image pixels
(1dp), within the guide's documented Chrome/Compose shaping allowance. Fixed-width components and
all explicit heights match exactly.

## Guide gaps

- `icon-button` is not implemented. SEE-112 added five HTML/PNG specimens, but the required
  `design/components/icon-button/spec.md` is absent. `design/README.md` forbids inferring a public
  API from generated references alone.
- The three dark tertiary-button references use `#8A3C00`, but `design/tokens.json` has no semantic
  token for that colour. The implementation uses the nearest named tertiary-container token and
  the visual evidence keeps the mismatch visible. Resolving it requires a separate design-guide
  refresh before Kotlin can consume an exact named token.
- `SeekerButtonVariant.OnVerdictOk` and `OnVerdictWarn` are part of the ticket API and use the
  inverse named verdict token pairs. The guide has no specimens for them, so no ungrounded
  `@DesignRef` previews were added.
