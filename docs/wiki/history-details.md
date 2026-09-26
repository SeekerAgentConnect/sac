# History item details (SEE-161)

Tapping a row in **Inbox → History** opens that item's record: a read-only, full-height page that
says what happened, drawn only from what this phone stored when it happened. Before SEE-161 the row
opened the live review sheet, which showed today's rules, a fresh quote and the review's own
controls for something already decided.

The design source of truth is the brief and its references in
[`docs/design/history-details/`](../design/history-details/README.md).

## Where it opens from, and where Back goes

| Entry | What happens |
| --- | --- |
| A History row | `AppScreen.HistoryDetail(identity)` replaces the Inbox. The row has a trailing chevron and a status icon, and the whole row is the hit target. |
| A request notification for something answered here and closed since | `InboxViewModel.openFromNotification` settles `NotificationOpenStatus.Closed`, and the review that was opening is replaced by the record. |
| A signal notification for something no longer open | The tap routes straight to the record, once the feed's records have loaded. |

The page has an app bar with Back and the title **History**, and **no bottom navigation**. Back,
from the app bar or the system, returns to the Inbox with the History tab selected. The tab, the
source filter and the History scroll offset are held by `InboxViewState`, which the app keeps
rather than the Inbox, so they are exactly as they were when the row was tapped. The state is saved
with the rest of the navigation state, so it also survives the process being recreated. Choosing
the Inbox tab from the navigation bar starts a fresh visit.

The source filter is the app's existing filter card (`FilterBar`). **Its inbox** on a connection's
details opens the Inbox narrowed to that connection, with the card and its **Clear**.

## The page

The order is fixed. An optional section with nothing to show is left out, with no placeholder.

1. **Title and source**: the Signal chip for a feed's item, the source in the colour the owner gave
   it, the title as the inbox shows it, then the environment and the network. A sandbox reads
   **Sandbox · no funds moved**, in the past tense.
2. **Final status**: Approved, Declined, Dismissed, Expired or Cancelled by server, each with its own
   icon and one line saying who acted and where. A server's cancellation reason appears when it gave
   one. Approved never means the transaction succeeded; that is the execution result's job.
3. **Your response**: only what the owner actually submitted, with the time to the second.
   Otherwise it shows **No response sent** with a line saying that isn't a decline.
4. **Delivery problem**: only while the server hasn't acknowledged a stored answer. **Send again**
   re-sends the stored answer (`InboxViewModel.sendAgain`) and never changes it. A connection the
   server no longer accepts gets the explanation and no button.
5. **Execution result**: only when something ran: confirmed, waiting, failed (with a readable
   reason), signed, or simulated.
6. **Original request or signal**, *as received*: the agent's note or the publisher's, and the
   operation's values. Addresses and messages are mono blocks that wrap and are never cut.
7. **Transactions**: one card per transaction, with a status chip, the shortened signature, a copy
   button that copies it whole, and an explorer link for the transaction's own cluster.
8. **Timeline**: only the events that were recorded, each at the time it was recorded.
9. **Identifiers**: request or signal ID, market ID, signature, and any raw error.
10. **Footnote**: the record doesn't change with today's rules or prices. For a sandbox, it notes
    that nothing was signed or sent.

The only actions on the page are Back, copy, the explorer link, and Send again. Nothing on it can
approve, decline, edit or simulate.

## Built from stored records only

`history/HistoryDetailMapping.kt` builds the page model from:

- a private request's `LocalResult`: the answer, when it was given, delivery, the wallet's outcome,
  and the sidecar's latest copy of the request with its outcome and confirmation; or
- a signal's `ProposalRecord`: the publisher's terms, the dismissal, and the execution with its
  binding and outcome.

It calls no rules engine, no quote service, no provider read and no setting. The two facts it takes
from the connection are the owner's own name and colour for it. For a signal nothing was ever
executed from, it also takes the environment the feed keeps, because a record with no binding has
no environment of its own. A direct connection is always production
([environments](environments.md)).

The owner's choice on a signal (side, amount, slippage) is put into words with the labels,
options and decimals of the provider's compiled form (`OperationViewModel.parameterForm`). That
form is resolved for the provider, environment and chain the choice was bound under, not for
today's wallet. A key the form doesn't name is shown as stored.

What the records don't hold is left out rather than guessed:

- A private answer carries no typed message and no owner-entered values, so neither is shown.
- The phone doesn't record when an acknowledgement or a failed delivery attempt happened, so the
  delivery card doesn't name a time.
- The phone records no wallet name, so the status line says "your wallet".
- An event with no recorded time is left off the timeline.

## Pending execution updates in place

While a private transfer or stake is still waiting on the chain (`LocalResult.awaitingChain`), the
open page asks the sidecar what it has learned every 15 seconds (`InboxViewModel.checkStatus`). This
opens no wallet and sends nothing. The page reads the same `StateFlow` the rest of the app does, so
when the sidecar reports the outcome, the execution card, the transaction chip and the timeline
change together.

A signal's transaction is never followed on chain in this build, and no provider answers status
queries ([execution providers](execution-providers.md)). Its record says it was sent and points to
the explorer. It doesn't claim a check that will never come.

## Limits

- **Unanswered private requests have no record.** A request that expired or was cancelled before
  the owner answered it never produced a `LocalResult`, so it has no History row and no record. The
  page supports the expired and cancelled variants for anything that does have a record, which
  today means feed signals and answers the server superseded. Keeping a record of unanswered private
  requests is a data change of its own.
- The record offers no provider destination, so SEE-157's **Open order** for a placed prediction
  is not reachable from History: the brief allows no action beyond Back, copy, the explorer link and
  Send again. The explorer link and the identifiers, including the market ID, remain.
- Answers are pruned a week after they settle (`ConnectionRepository`), and removing a connection
  removes its records. A record that has gone shows a short "no longer on this phone" state.

## Code

| Where | What |
| --- | --- |
| `designsystem/…/HistoryDetail.kt` | `HistoryDetailScreen` and the typed model, atoms A1–A10 |
| `designsystem/…/HistoryDetailFixtures.kt`, `HistoryDetailPreviews.kt` | the ten variant screens and two full bodies, captured by Roborazzi |
| `designsystem/…/HistoryRow.kt` | the optional kind icon, status icon and chevron |
| `designsystem/…/ScreenScaffold.kt` | `DetailScreenScaffold`, and `ScreenScrollBody(state)` |
| `app/…/history/HistoryDetailMapping.kt` | stored record → page model |
| `app/…/operations/OperationText.kt` | `choiceRows`, kept behind the plugin boundary (`StageBoundaryTest`) |
| `app/…/history/HistoryDetailRoute.kt` | state, polling, copy, explorer and Send again |
| `app/…/inbox/InboxScreen.kt` | `InboxViewState`, the source filter, row icons |
| `app/…/AppNavigation.kt` | `AppScreen.HistoryDetail`, `openHistoryDetail`, Back and saved state |
