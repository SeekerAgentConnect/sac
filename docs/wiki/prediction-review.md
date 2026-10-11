# Prediction review sheet

SEE-158 moves the review of a `prediction.buy` signal off the older single-column
`ProposalReviewScreen` and onto the design's review sheet (`SAC v4 Screen.dc.html`, state
`reviewPrediction`; repository reference `design/screens/sheet-prediction.png`). Direct requests
are unchanged. A swap signal is reviewed in the same sheet through the same code: `SignalStage`, the
verdict, the checks and the four sections are shared, and only the lines that name the operation
differ (`SignalSheetWording`; `operations/SwapReview.kt`, see
[shared proposals](shared-proposals.md#the-owners-part-of-a-swap)).

## Where it lives

| Piece | File |
| --- | --- |
| Mapping `OperationReview` → `ReviewSheetState` | `app/.../operations/PredictionReview.kt` |
| The sheet and its stacked side-and-stake sheet | `app/.../operations/PredictionReviewScreen.kt` |
| One reading of a policy decision for sheet and tile | `app/.../reviews/ReviewVerdict.kt` |
| Stacked side-and-stake sheet (design system) | `designsystem/.../OwnerParametersSheet.kt` |
| Copy | `app/src/main/res/values/strings_prediction_review.xml` |

`SeekerVaultApp` routes a signal review to the prediction sheet when its held record is a
prediction nobody has acted on yet (`ProposalRecord.reviewedAsPrediction`). An executed prediction
keeps the older review, which carries the explorer and provider links for "where to look now".

## Layout

`ReviewSheet(layout = ReviewSheetLayout.Pinned)`: the grabber and title bar are fixed at the top,
the decision footer is fixed at the bottom, and only the body between them scrolls. Previews keep
`Unrolled`, the comparison canvas.

Body order, top to bottom (SEE-180 made it compact: the decision is open, everything behind it is
in four collapsed sections):

1. Source row — `Signal` chip and the connection's name in its own colour.
2. Headline — the market's name. The owner's side and amount are on the Your part card.
3. Sub-line — where the order is placed, under the provider's required name, and when the market
   closes: `Jupiter Prediction · Powered by Jupiter · closes Oct 2, 10:05 PM`. The market's ID is
   metadata and sits under Technical details.
4. Chips — the network the signal declares (mainnet neutral, devnet orange), never the wallet's
   (SEE-185).
5. Previewed notice, only right after **Preview without sending**: nothing was signed or sent, the
   signal is still open, and placing the order means approving it, which fetches a fresh quote
   first.
6. Status blocks, only when something went differently: not open any more, unsupported, or why an
   approval stopped. What stopped a quote is not here (see 9).
7. Your part · side and amount — summary only ("5 USDC on Yes · Main wallet", unchosen until a
   wallet is chosen too), with **Choose side and amount** / **Change**.
8. Verdict card — only when it has something true to say (see [Verdict](#verdict)).
9. One state card where the quote would be, when there is no usable quote. A quote that has run
   out gets none: approving it prepares it again first (see [Approving a quote that ran
   out](#approving-a-quote-that-ran-out)).

   - **Get quote** — side and amount are chosen and nothing is prepared for them (the inputs or the
     feed's wallet changed);
   - the preparation error with **Try again** — e.g. `Insufficient funds` / "Could not prepare the 5
     USDC order. The provider reported insufficient funds." Only what the provider said: no token,
     balance or shortfall is inferred. Its code, its explanation and its own words are under
     Technical details — the code as a row, the explanation and the provider's words as wrapping
     blocks, so a long provider message is readable in full at any font size;
   - **This transaction can't be approved** with the inspection's findings and **Get a new quote** —
     bytes this phone could not account for. No tick gets past it.
10. No quote card. The quote's figures — contracts, cost, possible payout, provider fee, market
    status — and the provider's supporting ones are rows under Technical details. A refresh
    (an approval of an expired quote, **Get a new quote**) keeps the old bytes in the view model until the
    provider answers, but the sheet shows none of them meanwhile: no old figures, verdict, blocker,
    payer or accounts.
11. No wallet row. The wallet is on the Your part card and is chosen and changed in the side and
    stake sheet (see [Side and stake](#side-and-stake)); the payer read out of the bytes (SEE-174),
    "Wallet · paid and signed by", is under Technical details.
12. Four collapsed sections (`ReviewSheetSection`, each a button that says Expanded/Collapsed, at
    least a touch target tall, whose open state survives recomposition and quote updates):
    - **Limits and checks** — summary such as `Daily limits not set · transaction read in full`;
      inside, the daily rows (or one "No daily limit is set…" line when neither is configured),
      the transaction's state, and what this phone reads itself (worded as future until a quote).
    - **About this order and risks** — the publisher's note · not verified, the provider's notes
      marked `ProviderNoteTopic.Order` (fills, cancellation, selling) and who signs.
    - **Jupiter and terms** (`<provider> and terms`) — the provider's other notes (real funds on
      mainnet, fees, regions, non-endorsement), From / Prediction market / Market source, and the
      provider's how-it-works, terms and privacy links.
    - **Technical details** — action, event, market, asset, mint, decimals, least/most deposit, any
      unknown publisher term, the payer, recipient and references, the quote's and supporting figures, a preparation error's
      code and words, the market link, proposal, publisher and revision. Identifiers copy in full.
13. `Expires Oct 2, 2026, 10:05 PM.`

Footer: the warning checkbox only for a current, approvable quote with an orange verdict, then
**Approve and trade**, a neutral **Preview without sending** under it when the provider serves a
preview (SEE-185, [environments.md](environments.md)), and a tonal **Dismiss**, then "Dismissing
keeps the decision on this phone. CopyTrading is never told either way."

## Display rules

- No wire key is shown as a label. Known terms have their own labels; anything else is shown once,
  under a label made from its name (`order_account` → "Order account").
- No base units: `least_deposit` `5000000` is shown as `5 USDC`. Every money value carries the stake
  token's symbol, or the shortened mint when the publisher gave no symbol
  (`PredictionPayload.depositUnit()`); the Jupiter inspection now formats its cost, payout, price
  ceiling and fee with the unit too.
- Addresses and identifiers are mono and cut at the middle, eight characters each side. Asset mint,
  Proposal and Publisher copy their full value on tap.
- A value is shown once. The transaction's `market_id` reference is dropped when it equals the
  market already listed; "Received by" and "Order account" are both kept because they are two roles.

## Verdict

`PolicyDecision.reviewVerdict(prepared)` is the single reading used by both the sheet and the Home
tile. The decision itself is unchanged and stays conservative: with no prepared transaction the
facts are unread, so it is never ALLOWED and carries `RequestUnverified`. What changes is the
reading (SEE-180):

| Decision | Before a transaction (`prepared = false`, and every tile) | With a transaction |
| --- | --- | --- |
| Allowed (only an assessment left over from earlier bytes) | no card (`ReviewVerdict.Pending`) | lime "Configured checks passed", with "Not checked: …" naming every check no rule covers (SEE-181) |
| No rules configured | no card; a neutral "No rules configured · not checked" block, no tick (SEE-181) | same |
| `RequestUnverified` only, or checks unverified for want of an amount or asset | no card (`ReviewVerdict.Pending`); tile shows no status badge (`RequestTileModel.unchecked`), never "In rules" | orange, one row with a **Transaction check** chip (`WarningOrigin.Verification`, `ScopeChipSource.Verification`) |
| A failed check, unreadable rules, an unknown day's total | orange, each row with its real source (**Global rule** / **Connection rule**) | same |

An exceeded threshold is one line with its amounts in the deposit's unit — "Over the global daily
limit: 120 USDC of 100 USDC today.", "Over the per-request limit: 20 of 10 USDC." — and the
confirmed / not yet settled / this order breakdown stays in **Limits and checks** (SEE-181). An
unknown day's total says why, e.g. "1 of today's operations were recorded without what they spent"
([`policy.md`](../policy.md#records-written-before-see-181)).

A signal tile on Home is never prepared, so it never reads "In rules": with no warnings it shows no
pill at all — with no rules configured, before its assessment arrives, and before an order is
prepared (SEE-181 removed the old orange "Outside rules" pill). A private request's tile reads "In
rules" only when its configured checks passed.

A verification finding is never attributed to a rule document: `RequestUnverified` used to default
to `RuleSource.Global`, which showed a fabricated **Global rule** chip.

The sheet asks for "I have read the warning and want to approve anyway" only in the Ready stage —
prepared, read in full (`ActionInspection.approvable`), not expired — when the card is orange, and
**Approve** stays disabled until it is ticked. No rules is not a warning and asks for no tick
(SEE-181). Before a quote,
after a failed preparation and for a blocked transaction there is no tick: there is nothing it
could approve. The view model's own gates are unchanged (approve requires an approvable inspection,
re-assesses, requires consent to the current reasons, and binds the wallet the bytes were prepared
for). Since SEE-181 it also re-reads the rules and Activity after waiting for the wallet lock: an
approval that queued behind another wallet interaction which spent in the meantime stops with
"rules changed", shows the new verdict, and needs a fresh tick if it now warns. The binding pins
what the inspected order spends (`ExecutionBinding.spending`), so the stake counts towards the
day's limits from before the wallet is opened.

The sheet's stages are explicit (`PredictionStage`): Unchosen → Preparing → Failed | Blocked | Ready
→ Expired, plus NeedsQuote for chosen inputs with nothing prepared. Changing the inputs or the
feed's wallet drops the preparation and the consent (unchanged view-model behaviour); **Try again**,
**Get quote**, **Get a new quote** and **Try again** all prepare again from the current inputs
for the bound wallet and never sign or send.

### When nobody is buying the chosen side

A position can be sold only to somebody bidding for it. When the market the order was prepared
against has no bid for the chosen side (`sellYesPriceUsd` / `sellNoPriceUsd` absent or 0 in
Jupiter's market answer), the plugin adds a **caution** to the inspection
(`ActionInspection.cautions`, code `no_exit`). The review then shows **You may not be able to sell
this** above everything else: once bought, the position most likely can't be sold, the trade can't
be closed early and the money can't be taken back; it pays only if that side wins when the market
settles. Approving needs the tick **I understand I most likely can't sell this position or get my
money back before the market settles** (combined with the warnings text when a rule warns too).
Without it the approval stops with "not acknowledged" and the wallet is not opened. A caution only
ever adds this step; it never makes anything approvable, and a refreshed quote whose cautions
differ asks for the tick again.

### Approving a quote that ran out

A quote's blockhash is good for under half a minute, so there is no **Refresh quote** step: the
Expired stage stays approvable, and **Approve** (or **Preview without sending**) with less than
15 seconds of the preparation's window left prepares it again in the background first
(`OperationViewModel.current`, `FRESH_ENOUGH`). The same amount, side or slippage and the same wallet
are prepared, the fresh bytes are inspected as the first ones were, and the rules are assessed
again. If the rules' verdict or the rules that applied differ from what the owner read, the
approval stops with "the rules changed" and shows the new verdict; nothing reaches the wallet. If
they are the same, the owner's acknowledgement of its warnings carries over and the wallet is opened
at once with the fresh bytes. Only the price can differ, within the slippage the owner chose.

Rules on the verdict opens the connection's rules stacked over the review (`[review, rules]`).
When stored rules change, the open review is assessed again.

## Side and stake

**Choose side and stake** / **Change** pushes `AppSheet.OwnerInput` (`[review, params]`). The sheet
starts with the wallet that signs on the signal's network ("Wallet on Mainnet", "None chosen yet",
**Choose** / **Change**), which opens the Operation wallet picker over it (`[review, params,
wallet]`, SEE-185) exactly as a swap's does; choosing another drops what was prepared for the old
one. Then a Yes/No segmented control, the amount field ("Amount to stake", 2dp primary underline while
focused), the market's bounds beside it ("Minimum 5 USDC. In this asset's own units; nothing is
rounded.", or "From … to …" when there is a most; SEE-180), and the privacy note as its
footer. **Use these** stays off until a wallet, a side and an amount are chosen; it checks the amount against the market's bounds ("At least 5 USDC."), writes the
choice, prepares again at once — that is the re-quote — and closes the sheet.

## Tests

`operations/PredictionReviewTest` covers the acceptance checklist on the mapper and the rendered
screen (pinned footer on an 800dp screen, approve gated by the tick, the parameters sheet handing
back exact base units) and, for SEE-180, every stage: no warning or tick before a quote, a genuine
global-rule warning kept under its source, insufficient funds with **Try again**, an unreadable
transaction as a blocker attributed to the transaction check, sections that open in place and stay
open across a quote update, and a refresh from an expired or a refused quote that shows only the
loading card. `PredictionOperationTest` drives insufficient funds → try again → success through the
view model (consent cleared, nothing sent), an unaccountable transaction that no tick gets past, and
reads the sheet from inside the provider call during a refresh from a ready and from a refused
quote. `PredictionReviewScreensTest` saves the sheets in
[`docs/testing/see-180`](../testing/see-180.md) with `-Dseekervault.screenshots=<dir>`, and checks
that a long provider message wraps in full under Technical details at normal and 1.3× font.
`AppNavigationTest` covers `[review, params]` and `[review, rules]`, and `ConnectionsScreenTest`
covers the tile's sub-line and verdict, including no pill for an unprepared signal under configured
rules.
