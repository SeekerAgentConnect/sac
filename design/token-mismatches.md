# Design token cross-check

Compared [`tokens.json`](./tokens.json) with the existing Kotlin theme in `apps/android/designsystem/src/main/java/io/github/brrenat/seekervault/designsystem/theme/` (`Color.kt`, `Dimensions.kt`, `Shape.kt`, `Type.kt`, and `Theme.kt`). SEE-111 changes no Kotlin.

## Result

Every scalar value in `tokens.json` exists in Kotlin with the same value. There are no colour, font-size, radius, spacing, button-size, or icon-button-size value mismatches. The mismatches are structural: three design token groups have no first-class Kotlin type, several exact colours live only in `MaterialTheme.colorScheme`, and Kotlin still exposes a large pass-through dimension set that is not a design token scale.

## Exact colour mapping

| Design token | Value | Kotlin representation |
| --- | --- | --- |
| `--surf` | `#121212` | `surface0`; background and surface |
| `--surf1` | `#1c1c1c` | `surface1`; low/container |
| `--surf2` | `#232323` | `surface2`; surface variant/high |
| `--surf3` | `#2e2e2e` | `surface3`; highest/bright |
| `--ink` | `#ffffff` | `onBackground`, `onSurface` |
| `--inkv` | `#cacaca` | `onSurfaceVariant` |
| `--line` | `#6f6f6f` | `outline` |
| `--linev` | `#3a3a3a` | `outlineVariant` |
| `--dim` | `#0a0a0a` | `dim`; surface dim and scrim |
| `--pri` | `#e7fc6e` | `lime`; primary |
| `--onpri` | `#1b1b1b` | `onLime`; on-primary |
| `--pric` | `#c2e60f` | `limeContainer`; primary container |
| `--onpric` | `#1b1b1b` | `onLimeContainer`; on-primary-container |
| `--prit` | `#e7fc6e` | `primaryText` |
| `--ter` | `#ffb27a` | `orange`; tertiary |
| `--terc` | `#ff7a1a` | `orangeContainer`; tertiary container |
| `--onterc` | `#2e1200` | `onOrangeContainer`; on-tertiary-container |
| `--err` | `#f83959` | `errorText` / `destructive`; error |
| `--errc` | `#4d0011` | `destructiveContainer`; error container |
| `--onerrc` | `#ffd9de` | `onDestructiveContainer`; on-error-container |

`--ink`, `--inkv`, `--line`, and `--linev` are exact but exist only as `ColorScheme` roles, not fields on `SeekerColors`. `--prit` is renamed `primaryText`. These are placement/name mismatches, not value mismatches.

## Exact numeric mapping

- Type sizes `12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 36` sp all exist. The 15sp role is `buttonLarge`; 18sp is `amount`; 20sp is `screenTitle`; 13sp Mono is `identifier`.
- Radii `4, 8, 12, 16, 20, 24, 28` dp map to `xs, sm, md, lg, xl, xxl, sheet`.
- Spacing `2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32` dp maps to `xxs` through `jumbo`.
- `iconSize: 28` exists numerically as `SeekerDimensions.dp28`.
- Button metrics are exact through existing primitives: small `h32/r16/f13/p12`, medium `h40/r20/f14/p24`, large `h48/r24/f15/p24`.
- Icon-button metrics are exact through existing primitives: large `box48/glyph24`, medium `box40/glyph20`.

## Structural mismatches

The following generated groups have no first-class semantic Kotlin token type:

- `iconSize`
- `buttonSize`
- `iconButtonSize`

They are reconstructed from generic dimensions, radii, spacing, and typography. Small-button text also needs Roboto 13sp/500, while Kotlin has 13sp body/identifier styles but no named 13sp/500 button role.

`tokens.json` emits 28 in both `type` and `iconSize`. Kotlin exposes 28sp as `headlineLarge` and 28dp as a generic dimension; it does not distinguish the icon-glyph use as a semantic token.

## Kotlin-only values

- Named spacing adds `none = 0dp`.
- `SeekerDimensions` is a pass-through category absent from `tokens.json`: `0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 16, 18, 20, 22, 24, 28, 30, 32, 40, 48, 52, 56, 64, 68, 80, 86, 100, 192, 204, 240, 420` dp. Values that happen to equal a token are still not semantic tokens.
- Dark/source colours absent from the generated dark palette: strong destructive ink `#2b0008`; source pairs `#7ec8ff/#00243d`, `#c9a7ff/#241042`, `#4fdcc0/#00312a`, `#ff8fa8/#3d0014`, `#e3cf95/#322400`.
- The Kotlin light palette adds `#4f5c00`, `#cfcfd2`, `#c4142f`, `#f1ffa0`, `#e9ff7a`, `#2c3400`, `#8a3c00`, `#ffe0c2`, `#4a2600`, `#f7f7f7`, `#eeeeee`, `#e4e4e4`, `#ffe1e5`, `#5c0014`, `#45464a`, `#76767f`, and `#c6c6c9`. `tokens.json` is captured from the dark Components view, so it does not verify these.
- Kotlin also supplies `Color.Transparent` for surface tint and typography metadata not represented by `tokens.json`: Roboto/Roboto Mono families, weights 400/500/700, zero letter spacing, and 1.2× line heights.

There are no Kotlin-only radius values and no Kotlin-only font-size values.

## SEE-183 tokens pending the export

The rebuilt request tile (`docs/design/request-tile/request-tile.html`) names values the Stage 7.2
export does not contain. They are named Kotlin tokens, not literals, and join `tokens.json` at the
next refresh through [UPDATING.md](./UPDATING.md):

| Reference | Value | Kotlin |
| --- | --- | --- |
| `--status-ok` | `oklch(0.6 0.15 150)` = `#25984D` | `SeekerColors.statusOk` |
| `--on-status-ok` | `#ffffff` | `SeekerColors.onStatusOk` |
| `.tile__title--m` | 17px / 1.2 | `SeekerExtraTypography.tileTitle` (17sp / 20.4sp) |
| `.status` count | 13px / 600 | `SeekerExtraTypography.badgeCount` (13sp; adds the Roboto 600 variation) |

`DesignTokensTest` accepts 17 as the one Kotlin type size absent from `tokens.json`
(`PendingExportTypeSizes`) and fails if any other appears. The badge's 1px icon-to-count gap is
not reproduced: there is no 1dp spacing token.
