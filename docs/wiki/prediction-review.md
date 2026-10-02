# Prediction review sheet

SEE-158 moves the review of a `prediction.buy` signal off the older single-column
`ProposalReviewScreen` and onto the design's review sheet (`SAC v4 Screen.dc.html`, state
`reviewPrediction`; repository reference `design/screens/sheet-prediction.png`). Swap signals and
direct requests are unchanged.

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
4. Chips — `Sandbox` (only when sandboxed) and the network (mainnet neutral, devnet orange).
5. Sandbox card, only in sandbox.
6. Status blocks, only when something went differently: not open any more, unsupported, or why an
   approval stopped. What stopped a quote is not here (see 9).
7. Your part · side and amount — summary only, with **Choose side and amount** / **Change**.
8. Verdict card — only when it has something true to say (see [Verdict](#verdict)).
9. One state card where the quote would be, when there is no usable quote:
   - **Refresh quote** — the prepared quote has run out;
   - **Get quote** — side and amount are chosen and nothing is prepared for them (the inputs or the
     feed's wallet changed);
   - the preparation error with **Try again** — e.g. `Insufficient funds` / "Could not prepare the 5
     USDC order. The provider reported insufficient funds." Only what the provider said: no token,
     balance or shortfall is inferred. Its code, its explanation and its own words are under
     Technical details;
   - **This transaction can't be approved** with the inspection's findings and **Get a new quote** —
     bytes this phone could not account for. No tick gets past it.
10. Quote — what you spend, contracts, cost, possible payout ("Paid out if this side wins"), provider
    fee and market status. Supporting figures the provider marks `PluginFact.technical` (the most
    one contract may cost, instruction and lookup-table counts) are under Technical details. Before
    a quote: "Choose a side and an amount to get a quote."; while preparing: "Getting a quote and
    checking the transaction…".
11. Wallet — the feed's wallet, or the payer read out of the bytes once there are any (SEE-174).
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
      unknown publisher term, recipient and references, supporting figures, a preparation error's
      code and words, the market link, proposal, publisher and revision. Identifiers copy in full.
13. `Expires Oct 2, 2026, 10:05 PM.`

Footer: the warning checkbox only for a current, approvable quote with an orange verdict, then
**Approve and trade** (sandbox: **Simulate the trade**) and a tonal **Dismiss**, then "Dismissing
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
| Allowed | lime "Within the rules you set" | same |
| No rules configured | orange "Outside rules · no rules set", **Connection rule** chip | same |
| `RequestUnverified` only, or checks unverified for want of an amount or asset | no card (`ReviewVerdict.Pending`); tile shows no warnings | orange, one row with a **Transaction check** chip (`WarningOrigin.Verification`, `ScopeChipSource.Verification`) |
| A failed check, unreadable rules, an unknown day's total | orange, each row with its real source (**Global rule** / **Connection rule**) | same |

A verification finding is never attributed to a rule document: `RequestUnverified` used to default
to `RuleSource.Global`, which showed a fabricated **Global rule** chip.

The sheet asks for "I have read the warning and want to approve anyway" only in the Ready stage —
prepared, read in full (`ActionInspection.approvable`), not expired — when the card is orange,
including the no-rules case, and **Approve** stays disabled until it is ticked. Before a quote,
after a failed preparation and for a blocked transaction there is no tick: there is nothing it
could approve. The view model's own gates are unchanged (approve requires an approvable inspection,
re-assesses, requires consent to the current reasons, and binds the wallet the bytes were prepared
for).

The sheet's stages are explicit (`PredictionStage`): Unchosen → Preparing → Failed | Blocked | Ready
→ Expired, plus NeedsQuote for chosen inputs with nothing prepared. Changing the inputs or the
feed's wallet drops the preparation and the consent (unchanged view-model behaviour); **Try again**,
**Get quote**, **Get a new quote** and **Refresh quote** all prepare again from the current inputs
for the bound wallet and never sign or send.

Rules on the verdict opens the connection's rules stacked over the review (`[review, rules]`).
When stored rules change, the open review is assessed again.

## Side and stake

**Choose side and stake** / **Change** pushes `AppSheet.OwnerInput` (`[review, params]`). The sheet
has a Yes/No segmented control, the amount field ("Amount to stake", 2dp primary underline while
focused), the market's bounds beside it ("Minimum 5 USDC. In this asset's own units; nothing is
rounded.", or "From … to …" when there is a most; SEE-180), and the privacy note as its
footer. **Use these** checks the amount against the market's bounds ("At least 5 USDC."), writes the
choice, prepares again at once — that is the re-quote — and closes the sheet.

## Tests

`operations/PredictionReviewTest` covers the acceptance checklist on the mapper and the rendered
screen (pinned footer on an 800dp screen, approve gated by the tick, the parameters sheet handing
back exact base units) and, for SEE-180, every stage: no warning or tick before a quote, a genuine
global-rule warning kept under its source, insufficient funds with **Try again**, an unreadable
transaction as a blocker attributed to the transaction check, sections that open in place and stay
open across a quote update. `PredictionOperationTest` drives insufficient funds → try again →
success through the view model (consent cleared, nothing sent) and an unaccountable transaction
that no tick gets past. `PredictionReviewScreensTest` saves the sheets in
[`docs/testing/see-180`](../testing/see-180.md) with `-Dseekervault.screenshots=<dir>`. `AppNavigationTest` covers `[review, params]` and `[review, rules]`, and
`ConnectionsScreenTest` covers the tile's sub-line and verdict.
