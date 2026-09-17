# Approved Seeker Agent Connect v4 design

These files are the approved SEE-64 visual reference, checked into the repository by SEE-82 so UI work never depends on an expiring Linear attachment URL:

| File | Original Linear filename | SHA-256 |
| --- | --- | --- |
| [`Seeker Agent Connect v4 (offline).html`](Seeker%20Agent%20Connect%20v4%20%28offline%29.html) | `Seeker Agent Connect v4 (offline).html` | `6a28b70db62692282474ca5245871a52e0ddedc99c82b1247746e2125fae6f7f` |
| [`Seeker Agent Connect v4 - Flow Map (offline).html`](Seeker%20Agent%20Connect%20v4%20-%20Flow%20Map%20%28offline%29.html) | `Seeker Agent Connect v4 - Flow Map (offline).html` | `fecee2a7d2dbd1968556e58c0078405ab97c46e46fd3f8b81c988fd87a8d393b` |

Source: [SEE-82](https://linear.app/seekeragentwallet/issue/SEE-82/restore-approved-v4-theme-audit-ui-regression-and-prevent-design), which preserves the approved [SEE-64](https://linear.app/seekeragentwallet/issue/SEE-64/seeker-agent-connect-v4-material-3-restyle) attachments. They were retrieved through Linear MCP on 2026-09-15. Filenames and bytes are unchanged; no assets or paths were adjusted. Each file contains its own bundled runtime and assets.

To inspect them locally, serve this directory and open both links above through that server, for example:

```bash
python3 -m http.server 8000 --directory docs/design
```

The main reference renders live dark and light phones side by side. The flow map shows the approved screen hierarchy and tap paths. Both were opened from a local server after import with no console errors.

## Screens added since the approved reference

The v4 flow map is the approved hierarchy, and a stage that adds a destination adds one to it. What
has been added, with the components it is built from and the shape it copies:

| Screen | Stage | Built from |
| --- | --- | --- |
| Signals, under a feed connection's details | SEE-93 | The same list and rows as Pending requests: `SeekerCard`, the v4 `primaryContainer` circle and `ChevronRight` a details row already uses, and a `LinearProgressIndicator` while a feed is read |
| A signal's review | SEE-93 | The same column as Request details: the publisher's words, the facts, `PolicyReview` unchanged, `TextField` with `seekerTextFieldColors()`, and `SeekerButton` for Prepare, Approve and Hide |

No token, palette, typeface, shape or component was added or changed for either; they compose the
approved ones. Nothing in the reference HTML was edited.

These files govern the v4 theme and component presentation. Read them together with `SeekerVaultTheme` and the existing screen code before changing Android UI or resolving a UI merge conflict. Keep the HTML bytes intact: an intentional design revision belongs in a new version with its source and differences documented here. Never edit the reference to make current application output appear correct.
