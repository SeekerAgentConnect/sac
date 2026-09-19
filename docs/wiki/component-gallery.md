# Debug component gallery

The debug component gallery is the on-device index for Stage 7.2's Compose baseline. It renders
the same functions that Roborazzi scans, so the gallery and the committed PNGs cannot quietly use
different fixture data.

## Open the gallery

Install a debug APK, long-press the **Seeker Agent Connect** app icon, and choose **Component
gallery**. Select any component/variant row to render that live fixture. Use the back arrow to
return to the catalog and the close button to leave the gallery.

The catalog contains all 118 captured variants across the 34 specified component groups. It also
includes the five review-sheet fixtures, the four diagnostic probes, and the four-item navigation
assembly, for 128 live fixtures total. These additional entries make the physical acceptance walk
possible from the same surface; they do not turn diagnostics into public component APIs.

The activity, shortcut resource, manifest declaration, and gallery composable all live under
Android `src/debug`. A release build has none of them. The gallery is deliberately outside the
production typed navigation graph and has no access to repositories, ViewModels, connections,
wallets, or approval actions.

## Keep it complete

Each gallery entry calls an existing `@Preview` function; no specimen is re-created in the debug
app. `DesignPreviewNamingTest` compares the gallery's component/variant paths with the complete
scanner-discovered `@DesignRef` set. Adding a preview without a gallery entry, duplicating a path,
or renaming only one side fails the Android unit tests.

Approved images are committed in each module's `src/test/snapshots/images/` directory. See
[`design/README.md`](../../design/README.md#complete-design-to-golden-workflow) for the record,
review, verify, and commit sequence.
