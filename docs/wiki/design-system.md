# Nocturne: the app's design system

Where every colour, shape, surface and control in the Android app comes from, and the rules that
keep them in one place. Introduced by SEE-57, which restyled every screen at once.

The code is in `android/app/src/main/java/io/github/brrenat/seekervault/ui/`. Nothing outside that
package invents a value, and `StageBoundaryTest.theDesignSystemDrawsAndDoesNothingElse` holds the
package itself to drawing: it imports the app's own strings and nothing else from this app, and it
holds no repository, gateway, wallet, store or view model.

## The idea

A dark, **lit** ground with translucent chrome floating over it. The ground is three radial washes
over `--color-bg`, with a faint star field; every surface above it is the ground seen through a
percentage of the ink. That is why the chrome reads as glass: there is something behind it worth
seeing.

It is not a light theme with a dark variant. A light ground would not be a recolouring of this
design but a different one, so the app does not pretend to have one: `SeekerVaultTheme` is the same
in every system setting.

## Tokens

`Tokens.kt` holds them, once.

| Group | Where it lives | Notes |
| ----- | -------------- | ----- |
| Colour | `Nocturne` | `Bg`, `Surface`, `Text`, `Accent`, a nine-step neutral ramp and a nine-step accent ramp |
| Glass | `Nocturne.text(alpha)` | Fills 4–15%, borders 8–13%, top highlight 16–20%, accent fills 14–22%, accent borders 38–50% |
| Radius | `Radius` | Frame 44, sheet 30, panel/dialog 28, tile 24, card 22, row 20, inner 16–18, chip 11–13, pill 999 |
| Spacing | `Space` | The 0.7-density scale, plus `Edge` (14), `Inset` (16), `Gap` (12) and the tab bar's clearance |
| Blur | `Blur` | 20 for a card up to 64 for the wallet sheet |
| Type | `TypeScale`, `nocturneTypography` | 34 headline down to 11 pill, tight tracking on display sizes |
| Motion | `Motion` | One easing, `cubic-bezier(.2,.8,.2,1)`; 300ms panel and sheet, 240ms tiles and veils, 220ms toggles |
| Gestures | `Gesture` | The distances a finger has to travel before anything means something |

Two of these carry a decision rather than a value:

- **`Nocturne.Accent` is not final.** A palette study is out for review. When a candidate is chosen,
  that one line changes and nothing else does.
- **`Nocturne.Danger` is the only colour outside the two ramps**, and it exists for one reason. A
  warning the rules raise is carried by a dashed border and by words, because it is advisory. *"Do
  not approve: the transaction does not match this request"* is not advisory, and must not read as
  ordinary muted text.

## Glass

`Glass.kt` builds every surface: a fill (a vertical gradient of ink percentages), a hairline
border, a highlight along the top inside edge, and optionally a shadow. `Glass.card()`,
`Glass.row()`, `Glass.chrome()`, `Glass.tileActive()`, `Glass.panel()`, `Glass.dialog()` and
`Glass.sheet()` name the seven surfaces the design has.

**On blur.** The design specifies a backdrop blur for each surface. Compose has no backdrop blur
that works across the versions this app supports, so the fill is flat translucent at the same
value — which is the fallback the design calls for. What is never dropped is the **layering**: a
sheet still sits in front of a panel, and a panel in front of the screen that opened it. Every
`Glass` carries the blur radius the design asks for even though nothing reads it yet, so the day
the platform offers one it is wired in one place instead of twenty.

## Chrome

`Chrome.kt`. A floating header (a 36dp back button or the app's mark, a two-line title, an optional
network tag) and a floating tab bar with four roots — Home, Requests, Wallet, Activity — whose
Requests tab carries a dot when something is waiting. `GlassScreen` and `GlassListScreen` put the
two around a scrolling body; the body's bottom padding clears the tab bar.

## Controls

`Controls.kt` and `Dialogs.kt`. Pills instead of buttons (`PillButton`, four tones), `TextLink`,
`Tag`, the 46 × 28 `GlassSwitch`, the 22dp `GlassCheck` and `GlassRadio`, `IconChip`,
`InitialsChip`, `MonoText`, `GlassField` and `GlassDialog`.

Three rules they all keep:

1. **Nothing is said by colour alone.** "Under restrictions" is a *dashed* border and a warning
   mark and words; a field with a problem is a dashed border and a line of words. A reader who sees
   no colour at all, or hears the screen rather than seeing it, is told the same things.
2. **A control that is off says so.** Every pill and link writes `disabled()` into its semantics
   rather than only dimming.
3. **A label and its value are one thing.** Fact rows merge their semantics, so a screen reader
   announces "Server, http://127.0.0.1:8080" and not two unrelated fragments.

## Identifiers

An address, a mint, a server ID, a blockhash or a signature is set in a monospace face wherever it
appears, and shortened only in the middle — `FyfWsS…SpEA`. Never at one end: half an address that
still looks like a whole one is worse than one that is obviously shortened. `truncateMiddle` is the
only implementation.

## Icons

The prototype draws Phosphor Icons and says to substitute the codebase's own set. This codebase had
none, so Material's outlined set is that substitute, mapped once in `Glyph.kt` by what each icon
*means* — a screen asks for `Glyph.Transfer`, not for a coin — so swapping the set later is one
file.

## Typography

Inter is the design's face. No font binary is committed, so the scale, weights and tracking are
reproduced on the platform's own sans; substituting the face changes one value in
`nocturneTypography`.
