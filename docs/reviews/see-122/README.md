# SEE-122 visual review

The six labelled images in this directory place the generated design reference on the left and the
Roborazzi `:app` fixture on the right. Both halves are 390×844 dp at 3× density. Roborazzi reserves
the design's status area but does not draw operating-system status glyphs.

| Sheet | Evidence | Difference list |
| --- | --- | --- |
| Wallet hand-off | [wallet-handoff.png](wallet-handoff.png) | Empty |
| Connection detail | [connection.png](connection.png) | Empty |
| Rules for this connection | [rules-connection.png](rules-connection.png) | Empty |
| Global rules | [rules-global.png](rules-global.png) | Empty |
| Add/edit asset | [asset-edit.png](asset-edit.png) | Empty |
| Add address | [add-address.png](add-address.png) | Empty |

The audit covers each drag handle, title row, close action, intro text, body rhythm, and visible
action. Wallet hand-off, asset editing, and address entry also preserve and blur their underlying
sheet. The asset and address actions stay in the pinned title row; full-height rules and detail
sheets scroll their body without moving the header or footer actions. Font and stock Material icon
rasterization differ slightly between Chromium and Compose and are not layout or spacing
differences.

`./gradlew designCompare --rerun-tasks` pairs all six `screens/*` paths and 135 paths across the
repository. Its inventory also reports four existing diagnostics without HTML references and 57
generated icon/token references without Android previews; none is a SEE-122 screen path. Physical
Seeker verification is **NOT RUN** and remains separate in
[`docs/testing/stage-7-2.md`](../../testing/stage-7-2.md).
