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

Body order, top to bottom:

1. Source row — `Signal` chip and the connection's name in its own colour.
2. Headline — the market's name until a side and stake are chosen, then `5 USDC on Yes`.
3. Sub-line — `Jupiter Prediction, market POLY-…, closing Oct 2, 10:05 PM. The side and the stake
   are yours.` The closing time is the signal's expiry, in the phone's zone.
4. Chips — `Sandbox` (only when sandboxed) and the network (mainnet neutral, devnet orange).
5. Sandbox card, only in sandbox.
6. Status blocks, only when something went differently: not open any more, unsupported, a market
   the provider says is closed, nothing prepared (with the provider's own words), findings in the
   transaction, or why an approval stopped.
7. Your part · side and stake — summary only, with **Choose side and stake** / **Change**.
8. Verdict card — directly after Your part.
9. Stale-quote card with **Refresh quote**, only when the prepared quote has run out.
10. "The whole operation, quoted here" — the market status, what you spend, contracts, cost,
    payout, the most one contract may cost, the provider fee, instruction and lookup-table counts.
    Before a quote exists it shows one line saying what a quote will show.
11. Daily spend, when the rules have daily checks.
12. The check statement.
13. Fact rows — every publisher term under a label, then the accounts read from the transaction,
    then the proposal's identity.
14. The publisher's note · not verified — the only place the publisher's own words appear, with any
    ISO timestamp in them rewritten in local time.
15. `Expires Oct 2, 2026, 10:05 PM.`

Footer: the warning checkbox when the verdict is orange, then **Approve and stake** (sandbox:
**Simulate the stake**) and a tonal **Dismiss**, then "Dismissing keeps the decision on this phone.
CopyTrading is never told either way."

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

`PolicyDecision.reviewVerdict()` is the single reading used by both the sheet and the Home tile:

| Decision | Sheet | Tile chip |
| --- | --- | --- |
| Allowed | lime "Within the rules you set" | "In rules" |
| No rules configured | orange "Outside rules · no rules set", one row with a **Connection rule** chip | orange "Outside rules" |
| Warnings | orange "Needs attention · N warnings", one row per warning with its rule source | "N warnings" |

The sheet asks for "I have read the warning and want to approve anyway" whenever the card is
orange, including the no-rules case, and **Approve** stays disabled until it is ticked. The
view model's own gate still applies to real warnings; this is an additional UI gate.

Rules on the verdict opens the connection's rules stacked over the review (`[review, rules]`).
When stored rules change, the open review is assessed again.

## Side and stake

**Choose side and stake** / **Change** pushes `AppSheet.OwnerInput` (`[review, params]`). The sheet
has a Yes/No segmented control, the amount field ("Amount to stake", 2dp primary underline while
focused), the helper "In this asset's own units. Nothing is rounded.", and the privacy note as its
footer. **Use these** checks the amount against the market's bounds ("At least 5 USDC."), writes the
choice, prepares again at once — that is the re-quote — and closes the sheet.

## Tests

`operations/PredictionReviewTest` covers the acceptance checklist on the mapper and the rendered
screen (pinned footer on an 800dp screen, approve gated by the tick, the parameters sheet handing
back exact base units). `AppNavigationTest` covers `[review, params]` and `[review, rules]`, and
`ConnectionsScreenTest` covers the tile's sub-line and verdict.
