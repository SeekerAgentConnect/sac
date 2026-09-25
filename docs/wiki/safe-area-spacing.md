# Safe-area spacing for overlays (SEE-150)

Two things sat on the edges of the screen: an in-app notification hard under the status bar, and the
last line of an opened sheet on the gesture home indicator. Both are now placed against the system's
own insets rather than against the display's edge.

They are one page because they are one mistake made twice — a component positioned relative to the
window when what it has to clear is whatever the platform put there.

## The banner

`InAppNotifications` now owns its own placement. Its caller aligns it and nothing more:

```kotlin
// SeekerVaultApp
InAppNotifications(…, modifier = Modifier.align(Alignment.TopCenter).zIndex(InAppNotificationZIndex))
```

```kotlin
// notifications/InAppNotificationHost.kt
windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    .padding(start = spacing.md, top = spacing.lg, end = spacing.md)
```

`safeDrawing` rather than `statusBars`, because a display cutout can reach further down than the
status bar and — on a phone held sideways — into the sides. Whichever reaches further is what the
banner clears, and then `spacing.lg` is the gap, so it reads as floating over the content rather than
hanging from the top edge.

It moved out of the caller for the ordinary reason: a component that is wrong wherever it is mounted
should be fixed once. `InAppNotifications` is mounted in one place today, and the next place would
have had to remember the insets.

The padding is outside the banner's own gesture area, so the strip above it stays the content's to
touch — the banner still consumes only its own pointer events.

Held by `InAppNotificationHostTest`: with a 24dp status bar under a deeper 30dp cutout the banner
starts at 42dp (30 + 12) and is inset 8dp from the side; with no system bar at all it still keeps its
12dp gap from the top edge.

## The sheets

Every sheet the app pushes goes through one layer, and that layer is where the inset belongs —
inside the surface, not around it.

```kotlin
// ui/SeekerComponents.kt, SheetLayer
val navigationBarInset = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
if (chrome) {
    Surface(...) {
        Column(Modifier.windowInsetsPadding(navigationBarInset).padding(bottom = spacing.xl)) { ... }
    }
} else {
    Box {
        Box(Modifier.windowInsetsPadding(navigationBarInset)) { content() }
        Spacer(
            Modifier.align(Alignment.BottomCenter)
                .background(sheetSurface)
                .windowInsetsPadding(navigationBarInset)
        )
    }
}
```

`:designsystem`'s `SheetScaffold` is deliberately left alone, for the reason the banner's placement
moved to its host: a sheet component is window-agnostic presentation, and one that measured itself
against a window would put a simulated navigation bar into its own Roborazzi reference — which is
exactly what happened when it was tried, and is not what a design golden should record.
`windowInsetsPadding` consumes what it applies, so nothing nested inside can pad for the same bar a
second time.

Some review sheets ask the host to draw their chrome; library sheets such as Connection details use
`SheetScaffold` and draw their own. SEE-150 put the inset inside the host-chrome branch, so it
covered the first kind but silently skipped the second. `SheetLayer` now applies the same live inset
to both paths. Host-chromed sheets keep the host's 16dp gap; library-chromed sheets keep their own
existing bottom padding and append an inset-height spacer in the same sheet colour. Applying the
inset at the host's measurement boundary reduces the height available to the library content, so a
tall scroll-capped sheet cannot consume the safe area first. The surface still reaches behind the
system bar.

The surface still runs to the bottom edge — a sheet that stopped above the navigation bar would show
a strip of the screen beneath it — and what is *on* the surface stops above the gesture area or the
three buttons. Each sheet's existing bottom padding is then the gap, measured from the top of the
system's area rather than from the display's edge. No padding value changed; what changed is what it
is measured from.

`navigationBars` rather than `safeDrawing`: the bottom inset is the only one a sheet is anchored
against, and `safeDrawing` at the bottom also carries the IME, which would make a sheet jump when a
keyboard opened behind it.

Held by `SeekerSheetInsetsTest`, which dispatches a real `WindowInsets` and measures the distance
from the sheet's last line to the bottom of the screen:

| Navigation | Inset | Gap below the last line |
| --- | --- | --- |
| Gesture | 24dp | 40dp (24 + 16) |
| Three-button | 48dp | 64dp (48 + 16) |

The regression test also mounts a `SheetScaffold` the way Connection details is mounted, changes its
inset from 24dp to 48dp while it is open, and observes its existing 24dp body gap grow from 48dp to
72dp. Nothing reads the *mode*, only the live inset, so a third height works without being
enumerated.

## Why no reference image moved

A Roborazzi capture has no system bars: the insets are zero, so every measurement is what it was, and
`:app:verifyRoborazziDebug` and `:designsystem:verifyRoborazziDebug` stay green without re-recording.
That is also the limit of what the goldens can tell you here, which is why the tests dispatch insets
directly rather than relying on a capture.
