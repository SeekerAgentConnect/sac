# SEE-121 visual review

The five labelled images in this directory place the generated design reference on the left and
the Roborazzi `:app` preview on the right. Both halves are 390×844 dp at 3× density; Roborazzi does
not draw the operating-system status glyphs, so its reserved 28 dp status area is intentionally
blank.

| Flow screen | Evidence | Layout | Spacing | Scroll edge under navigation |
| --- | --- | --- | --- | --- |
| Home | [home.png](home.png) | No differences | No differences | Matches |
| Inbox | [inbox.png](inbox.png) | No differences | No differences | Matches |
| Wallet | [wallet.png](wallet.png) | No differences | No differences | Matches |
| Activity | [activity.png](activity.png) | No differences | No differences | Matches |
| Add connection | [add-connection.png](add-connection.png) | No differences | No differences | Matches |

The visual audit includes the fixed app bar and overlaid 80 dp bottom navigation, the 104 dp
trailing scroll clearance, Home's partially visible carousel item, and the partially visible next
row at the Inbox and Home viewport edges. The per-screen difference list is empty. Font and stock
Material icon rasterization differ slightly between Chromium and Compose and are not layout or
spacing differences.

`./gradlew designCompare --rerun-tasks` paired all five `screens/*` paths. Its repository-wide
inventory also reports the pre-existing environment probes that have no HTML reference and token
specimens that have no Compose preview; none is a SEE-121 screen path.
