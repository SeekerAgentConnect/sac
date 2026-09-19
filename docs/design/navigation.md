# Navigation

The app has one typed navigation graph. Home, Inbox, Wallet, and Activity are peer tabs; every
sheet is pushed over its exact parent, so Back or Close reveals that parent instead of routing to
Home.

| Destination | Type | Entry points | Exit behavior |
| --- | --- | --- | --- |
| Home | Tab | App launch; Home nav item | Nav items replace Home with another tab. |
| Inbox | Tab | Inbox nav item | Nav items replace Inbox with another tab. |
| Wallet | Tab | Wallet nav item | Nav items replace Wallet with another tab. |
| Activity | Tab | Activity nav item | Nav items replace Activity with another tab. Rows are read-only. |
| Add connection | Screen | Home FAB | Back or Close returns Home; successful pairing returns Home. |
| Request review | Sheet | Home carousel tile; Inbox **Review** row; request notification | Back or Close removes Review and reveals its Home or Inbox base. A wallet-backed approval pushes Wallet hand-off; answering finishes Review. |
| Wallet hand-off | Stacked sheet over Request review | Approve a wallet-backed request | Approve or Decline finishes both sheets. Back, Close, or **Leave without answering** removes only Wallet hand-off and reveals the still-pending Review. A refused approval also returns to Review with its error. |
| Connection detail | Sheet | Paired-connection row on Home | Back or Close returns Home; **Rules** pushes Rules for this connection. |
| Rules for this connection | Stacked sheet over Connection detail | **Rules** row in Connection detail | Back or Close reveals Connection detail; **Global** / **Edit** pushes Global rules; asset and address actions push their editors. |
| Global rules | Sheet from Home; stacked sheet from connection rules | Home **Rules** row; **Global** / **Edit** in Rules for this connection | Back or Close reveals Home or Rules for this connection, whichever is underneath. |
| Add / edit asset | Stacked sheet over connection rules | Asset row; **Add asset** in either asset section | Back or successful Save reveals Rules for this connection. The route retains whether it edits the allowlist or only a spending limit. |
| Add address | Stacked sheet over connection rules | **Add** under Recipients or Programs | Back or successful Add reveals Rules for this connection. |

The Home carousel is browse-only. Swiping changes the visible request; tapping a tile opens Request
review, and Home itself never answers a request.
