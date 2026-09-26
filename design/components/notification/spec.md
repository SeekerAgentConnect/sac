# notification

## Contract

- **Tier:** `organism`
- **Kotlin name:** `InAppNotification`
- **Allowed dependencies:** none

The banner shown over the app while it is in the foreground, for a request or signal that arrived
and for a paired server that ended the pairing.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class InAppNotificationKind { Request, Signal, Disconnected, Info }

@Composable
fun InAppNotification(
    kind: InAppNotificationKind,
    title: String,
    subtitle: String?,
    openActionLabel: String,
    dismissActionLabel: String,
    leaving: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`kind` maps one-for-one to the `kind=` axis. `subtitle` is null for `kind=disconnected`, which is a
title only. `leaving` is the caller saying "this one is going": the banner plays the exit the
gesture began, or the ordinary one when there was no gesture. Everything about *which* banner is
visible and *how long* it stays belongs to the caller
(`:app`'s `notifications/InAppNotificationQueue`).

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `kind=request` | SEE-147 attachment | SEE-147 attachment |
| `kind=signal` | SEE-147 attachment | SEE-147 attachment |
| `kind=disconnected` | SEE-147 attachment | SEE-147 attachment |
| `kind=info` | none yet (see below) | none yet (see below) |

The exact-value specification for all three is the `In-app Notification.dc.html` specimen attached
to SEE-147, with one PNG per kind beside it. They are not yet in `design/export/`, so
`design/components/notification/` holds no generated `<variant>.html` / `<variant>.png` pair and
`designCompare` reports the three Android captures as "missing reference". Adding them is an export
refresh, which [UPDATING.md](../../UPDATING.md) requires to be its own design-only change.

## Builder note

> The Home wallet banner's shape, floating. Same radius, padding, gap, 24dp icon and 16/13 type;
> primary container for a request or a signal and tertiary container for a disconnection; a shadow,
> because this one is over the content rather than in it. No close button and no action button —
> the whole banner is the target, and a swipe is how it goes away.

Measurements: radius 16dp, padding 16dp, row gap 12dp, column gap 2dp, icon 24dp, title 16sp/500,
sub-line 13sp/400 on one line with an ellipsis. Icons are `notifications_active`, `sensors` and
`link_off`. The shadow is `0 8 24 rgba(0,0,0,.45)` dark and `.16` light, carried as
`SeekerColors.overlayShadow` under one elevation.

The specimen's request and signal boxes are 374 × 68 CSS px and the disconnected one 374 × 56. The
Compose captures are 374 × 69 and 374 × 56: the named type scale carries the reference line height
explicitly (19.2sp over 15.6sp), where the browser's `line-height: normal` for Roboto is a shade
tighter. Matching the token scale is the guide's rule, so the 1dp is intended.

## `kind=info`

A service message — a connection added or renamed, a publisher's access decision, rules saved, a
link that would not open — in the same banner, on `surface3` with `onSurface` ink and the
`info` icon, rather than a snackbar at the bottom of the screen that raised it. It has no subtitle,
its title may wrap to three lines because it is a sentence rather than a headline, it is armed for
the same six seconds as a request, and a tap only dismisses it. The variant has no design export
yet; the Compose preview `notification/kind-info` stands in until one is added.

## Gesture, motion, and lifetime

These are runtime behaviour and are not visible in a static capture:

- Tap (pointer travelled under 6dp): `onOpen`.
- Drag: follows the finger on whichever axis has travelled further, upward only; opacity
  `max(0.3, 1 − |dx| / 300dp)` sideways and `max(0.3, 1 − 2·|dy| / 300dp)` upward.
- Release past 72dp sideways: flies out that way, 180ms; past 40dp up: the ordinary exit.
  Anything less, or a cancelled pointer: snaps back over 200ms.
- Enter 280ms `cubic-bezier(.2,0,0,1)`; exit 200ms `cubic-bezier(.3,0,.8,.15)`, both travelling the
  banner's own height plus 40dp. `InAppNotificationMotion` holds the numbers.
- Accessibility: role button, the label is `openActionLabel`, the arrival is announced politely,
  and a custom action named by `dismissActionLabel` stands in for the swipe.

## Open questions

- The generated reference pair is owed an export refresh (above).
- The specimen states one status-bar inset, 32dp. The app uses the device's real inset instead, so
  the banner sits under whatever status bar the phone has.
