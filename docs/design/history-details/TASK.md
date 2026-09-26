# Task: History item details

**Source of truth:** `SAC v4 History Details.dc.html` (board) and `SAC History Detail.dc.html` (the screen component, one record per render).
**Scope:** Inbox → History → item details, and back. The pending review flow (`SAC v4 Screen.dc.html`, review sheet) is unchanged.
**Problem today:** History rows are tappable in the app but open inappropriate content: the live review sheet, current rules and live quotes. Replace that with a read-only record.

Images:
- `screens/00-history-list.png`: the History tab with source filter.
- `screens/01..10-*.png`: the first viewport of each variant. Every variant scrolls; the board shows the full length.
- `atoms/NN-*.png`: one element per image at 2×, referenced below.

Tokens (dark), the same as the rest of the app:
- Surfaces: surf `#121212`, surf1 `#1c1c1c` (cards), surf3 `#2e2e2e` (chips, inner boxes, tonal buttons).
- Text: ink `#ffffff`, inkv `#cacaca`.
- Primary: pri `#e7fc6e`, pric `#c2e60f`.
- Tertiary: terc `#ff7a1a` with on-colour `#2e1200`, button `#8a3c00`.
- Error: err `#f83959`, errc `#4d0011`, on-errc `#ffd9de`.
- Server label colours, from the app's palette: Personal MCP Sky `#7ec8ff`, Staking MCP Violet `#c9a7ff`, Trader Signals Sand `#e3cf95`, Prediction Signals Teal `#4fdcc0`. In the app, use whatever colour the user assigned.

Shapes and type:
- Card radius 16, padding 16, gap between cards 12. Inner boxes radius 12, padding 12.
- Chips height 24, radius 8, 12px/500. Source label height 28, radius 12, 13px/500.
- Roboto throughout. Roboto Mono only for addresses, signatures, message bodies and IDs.
- Body padding 4 16, with a bottom padding of 58 (24 plus the 34 safe area). Home indicator stays clear of content.

---

## 1 · Navigation

1. Tapping a row in Inbox → History pushes the details as a full page, not a sheet. Hide the bottom navigation on it.
2. The app bar has Back (`arrow_back`, 48×48) and the title "History". There are no other actions. Image: `atoms/03-appbar.png`.
3. Back pops to the History tab with:
   - the same scroll offset (save it on tap, restore it after the list is laid out);
   - the source filter unchanged;
   - the History tab still selected.
   System back does the same.
4. History rows: `atoms/02-history-row.png`. Row = kind icon, title (1 line, ellipsis), `Signal` chip for feed items, source label, then a status line with icon + text. Add a trailing `chevron_right` so the row reads as tappable. The whole row is the hit target.
5. Source filter: `atoms/01-filters.png`. Chips scroll horizontally. The selected chip is filled pric; the others are outlined. The prototype's chip row stands in for the app's existing filter card. Keep whichever the app ships; only its persistence across Back matters here.
6. A deep link or notification into a closed item opens this same page, and Back goes to History.

## 2 · Page order (top → bottom)

The order is fixed. A section with no data is left out entirely, with no placeholder.

1. Title and source (always)
2. Final status (always)
3. Your response (always; it shows "No response sent" when there is none)
4. Delivery problem (only when the response wasn't confirmed by the server)
5. Execution result (only when something was executed, signed or simulated)
6. Original request or signal (always)
7. Transactions (only when at least one exists)
8. Timeline (always, with only the events that happened)
9. Identifiers (when any exist)
10. Footnote (always)

Execution sits above the original request so that the decision and the outcome are both visible without scrolling on common items. Technical identifiers stay at the bottom.

---

## 3 · Atom by atom

### A1 · Title and source
Images: `atoms/04-title-request.png`, `atoms/05-title-signal.png`, `atoms/06-title-sandbox.png`
- Row 1: `Signal` chip (surf3 with `rss_feed` icon) only for feed items, then the source label in the server's colour. For a signal, the source label is the feed name.
- Title: the original request or signal title, as stored. 28px, line-height 1.15, wraps without truncating (see the long example).
- Row 3: environment chip, then network chip.
  - Production: surf3 background, ink text.
  - Sandbox: terc background with the text "Sandbox · no funds moved". Use past tense here, unlike "will move" in the pending flow.
  - Network chip: `public` icon + Mainnet / Devnet / Testnet.

### A2 · Final status
Images: `atoms/07-status-approved.png`, `08-status-declined.png`, `09-status-expired.png`, `10-status-cancelled.png`, `11-status-dismissed.png`
- surf1 card. A 40px circle icon, then the label "Final status" (12px inkv), the status word (22px) and one explanatory line (14px inkv).
- Every status has its own icon and word, so none relies on colour alone:

| Status | Icon | Circle | Word |
|---|---|---|---|
| approved | `check_circle` | pric / `#1b1b1b` | Approved |
| declined | `cancel` | err / `#2b0008` | Declined |
| dismissed | `do_not_disturb_on` | surf3 / ink | Dismissed |
| expired | `timer_off` | surf3 / ink | Expired |
| cancelled | `block` | surf3 / ink | Cancelled by server |

- The explanatory line names who acted and where, for example "You approved on this phone. Seed Vault Wallet signed it." or "Personal MCP withdrew it at 7:48 PM, before you answered."
- Cancelled: if the server sent a reason, show it in an inner box labelled "Reason given by {server}".
- Approved never means the transaction succeeded. That belongs in A5.

### A3 · Your response
Images: `atoms/12-response-approved.png`, `13-response-values-message.png`, `14-response-declined.png`, `15-response-none.png`, `16-response-dismissed.png`
- Header: "Your response" on the left, and the response timestamp on the right with seconds (`Sep 26, 9:00:41 PM`).
- Rows show label left (inkv) and value right. Show only what the user actually submitted:
  - Decision: Approved / Declined / Dismissed / Approved (simulation).
  - Values the user entered or picked: amount, side, slippage, maximum price.
  - Delivered to: `{server}, {time}` when the server acknowledged the response.
- Message the user typed: an inner box labelled "Message you sent".
- Note line (13px inkv) when needed, for example for signals: "Feeds aren't told when you dismiss a signal."
- **No response (expired or cancelled):** `remove_circle_outline` + "No response sent", then a line that says it is not a decline. Examples:
  - "You didn't answer before it expired. This isn't recorded as a decline."
  - "The server cancelled the request before you answered. Nothing was signed."
- Values come from the stored response, never from current settings or defaults.

### A4 · Delivery problem
Image: `atoms/17-delivery-unconfirmed.png`
- Shown only when a response exists but the server didn't acknowledge it (failed or still unconfirmed).
- terc card with the `sync_problem` icon, a title naming the server, and text saying the response is stored on the phone and when the last attempt was.
- One button, "Send again" (tertiary `#8a3c00`). It re-sends the stored response only and never changes the decision. It is the only button on the page apart from Back and copy.
- Keep this separate from A5: delivery to the server and execution on chain are independent.

### A5 · Execution result
Images: `atoms/18-exec-confirmed.png`, `19-exec-pending.png`, `20-exec-failed.png`, `21-exec-signed.png`, `22-exec-simulated.png`
- Shown only when something ran: a transaction, a signature or a simulation. Omit it for declined, dismissed, expired and cancelled items.
- Structure: small label, then icon + title (16/500), then one line of text, then optional rows (label left, value right).

| State | Card | Icon | Label | Title |
|---|---|---|---|---|
| confirmed | surf1 | `check_circle` pri | Execution result | Confirmed on {network} |
| pending | surf1 | `hourglass_top` ter `#ffb27a` | Execution result | Waiting for network confirmation |
| failed | errc, text on-errc | `error` | Execution result | Failed on the network |
| signed | surf1 | `draw` pri | Result | Signed · no transaction |
| simulated | terc, text on-terc | `science` | Simulated result | Simulated · No funds moved |

- Pending: state the send time and the last check time, and that approving doesn't mean it went through. A value you don't know yet shows "Known after confirmation", never a guess or a success.
- Failed: add an inner box labelled "Failure reason" (`#2b0008`) with a readable reason mapped from the error. Show the raw program error only under Identifiers.
- Sandbox: never show an on-chain state, signature or explorer link. The rows show the recorded simulation output ("Would have received …").

### A6 · Original request / signal
Images: `atoms/23-request-transfer.png`, `24-request-signing.png`, `25-request-signal-long.png`
- Header: "Original request" for requests, "Original signal" for signals. On the right, `lock` + "As received" (12px inkv), meaning read-only.
- Description paragraph as stored (14px, wraps).
- Operation rows as stored: amount, token, recipient, market, suggested side, the price when received, and so on.
- Long values (addresses, vote accounts, the message to sign) render as a block: label above, then an inner box in mono that wraps. Never cut an address in the middle.
- Nothing here is editable and nothing is re-quoted. Prices are labelled "…when received".

### A7 · Transactions
Images: `atoms/26-transaction-single.png`, `27-transactions-multi.png`, `28-transaction-pending.png`, `29-transaction-failed.png`
- Heading "Transaction", or "Transactions · N" when there are several. It has the same heading style as Timeline (14/500 pri).
- One card per transaction, in execution order:
  - Label (Transfer, Swap, Create USDC share account, Place order) with a status chip on the right:
    - Confirmed: surf3 with `check_circle`.
    - Pending: terc with `hourglass_top`.
    - Failed: errc with `error`.
  - Signature: mono, shortened to the first 6 + `…` + the last 6. A copy button (40×40, surf3) copies the full signature; its icon turns to `check` for 1.5 s.
  - "View on explorer · {network}" link with `open_in_new`. The URL is `https://explorer.solana.com/tx/{sig}` on Mainnet, with `?cluster=devnet` or `?cluster=testnet` added on the other networks.
- Leave the whole section out when there are no transactions: declined, expired, cancelled, dismissed, message signing and sandbox.

### A8 · Timeline
Image: `atoms/30-timeline.png`
- One card with compact rows: icon (18px inkv), event, and a time on the right (13px, tabular numbers).
- Events, only when recorded:
  - `move_to_inbox` Received
  - `check` You approved
  - `close` You declined
  - `do_not_disturb_on` You dismissed
  - `send` Sent to the network
  - `verified` Confirmed
  - `error` Failed
  - `draw` Signed
  - `science` Simulated
  - `mark_email_read` Delivered to {server}
  - `sync_problem` Delivery unconfirmed
  - `timer_off` Expired
  - `block` Cancelled by {server}

### A9 · Identifiers
Image: `atoms/31-identifiers.png`
- Request or signal ID, market ID, message signature, and any raw error. Label (12px inkv) above a full mono value that wraps.
- Show nothing technical above this section.

### A10 · Footnote
- Production: "Recorded on this phone when it happened. Current rules and market prices don't change what's shown here."
- Sandbox: "Recorded on this phone when it happened. A sandbox item was never signed or sent, so it has no transaction."

---

## 4 · Rules

- There are no Approve, Decline, Edit or Simulate actions anywhere on this page. Don't reuse the review sheet's action footer or its verdict and daily-spend cards.
- Render only from the stored history record. Don't call the rules engine, quote service or current settings to fill in the page.
- Missing data never reads as success. Leave out optional sections that have no data. A known-missing value inside a section gets a short "unavailable" state, for example "Known after confirmation".
- Pending execution keeps updating while the page is open (poll or subscribe). When it lands, A5, the transaction chip and the Timeline update in place.

## 5 · Suggested record shape

```ts
type HistoryItem = {
  id: string; kind: 'request' | 'signal'; source: string; feed?: string;
  title: string; description?: string; env: 'Production' | 'Sandbox'; network: 'Mainnet' | 'Devnet' | 'Testnet';
  status: 'approved' | 'declined' | 'dismissed' | 'expired' | 'cancelled';
  statusReason?: string;                       // server-supplied, cancelled only
  response?: { at: string; decision: string; values: {label: string; value: string}[]; message?: string; deliveredAt?: string };
  delivery?: { state: 'failed' | 'unconfirmed'; lastAttemptAt: string };
  execution?: { state: 'pending' | 'confirmed' | 'failed' | 'signed' | 'simulated'; at?: string; reason?: string; rows?: {label: string; value: string}[] };
  request: { label: string; value: string; mono?: boolean; block?: boolean }[];
  transactions: { label: string; signature: string; status: 'pending' | 'confirmed' | 'failed' }[];
  timeline: { event: string; at: string }[];
  ids: { label: string; value: string }[];
};
```

## 6 · Acceptance checklist (one per variant screen)

- [ ] `01-long-content`: the title wraps to 3+ lines; 4 response rows and a message; the delivery card; execution confirmed; 2 transactions; the last content clears the home indicator.
- [ ] `02-approved-pending`: pending execution, and the transaction chip reads Pending.
- [ ] `03-approved-confirmed`: confirmed, 1 transaction, and the explorer link opens mainnet.
- [ ] `04-approved-failed`: the status still says Approved; execution failed with a reason; the transaction chip reads Failed.
- [ ] `05-declined`: response message shown; no execution, no transactions.
- [ ] `06-expired`: "No response sent", not a decline; no execution, no transactions.
- [ ] `07-cancelled`: the server's reason shown; "No response sent".
- [ ] `08-dismissed-signal`: Signal chip + feed; the note that feeds aren't told.
- [ ] `09-signed-message`: "Signed · no transaction"; the message shown as a mono block; no Transactions section.
- [ ] `10-sandbox`: "Sandbox · no funds moved" chip; the orange simulated card; no transactions or explorer link.
- [ ] Back restores the scroll offset and filter from `00-history-list`.
