# Prediction positions and selling them (SEE-172)

A prediction purchase made from a feed signal is followed, after it is sent, inside the same History
item it came from: what the order came to, what the wallet holds now, and — when the position can be
sold — a sale reviewed and signed in the app like any other operation. There is no separate trading
screen.

Research, API evidence and the smoke procedure: [`docs/research/2026-09-28-prediction-positions.md`](../research/2026-09-28-prediction-positions.md).

## Four facts, kept apart

| Fact | Where it lives | Changes? |
| --- | --- | --- |
| The purchase: signal, choice, binding, signature | The proposal record and its Activity row | Never rewritten |
| Each order's fill | `HoldingRecord.orders`, from `/orders/status` | Until the provider finishes the order |
| The position now: the wallet's whole holding of that side of that market | `HoldingRecord.snapshot` with `observedAt`, `attemptedAt`, `problem` | Replaced only by a newer successful read |
| Each sale attempt: its review, its signature, its outcome | `SaleRecord` | Only its stage and outcome move |

A chain-confirmed transaction is not a fill, a sent sale is not a sale, and a position the provider
does not know (404) is neither sold, lost nor zero: it is *Not found yet*.

## Linking a purchase to its position

`purchasesOf` takes production `prediction.buy` records whose execution reached the wallet and whose
Activity row keeps the references the review read out of the bytes (`order_account`,
`position_account`, `market_id`). The side, wallet and network come from the approved binding. A
record without those references — older ones, or one whose History was cleared — stays readable and
says *Tracking unavailable*, with the Jupiter link. Purchases of the same side of the same market by
the same wallet share one position account, so two signals show one aggregate position, read once.

A position is bound for good to its provider, owner, network, market, side and account. A wallet or
network switch never retargets it: reading stays about the owner, and Sell is offered only when the
selected wallet is that owner on that network.

## The History item

After the chain result, a **Position now** card shows:

- the scope — *your wallet's whole position in this outcome* (and how many purchases on this phone it
  includes);
- **Your order**: *Waiting to fill*, *Partly filled*, *Filled*, *Partly filled, rest returned*, *Not
  filled*, or *Not reported yet*;
- the position: market, outcome, contracts, value now, best bid, cost basis, average entry, P&L, market
  status, last read — each the provider's figure, *Not quoted* where it sends none;
- the state chip: *Live*, *Last known · time* (a failed or old read keeps the last good figures),
  *Not found yet*, *Couldn't read*, *Sold*, *Nothing held*, *Settled*;
- **Refresh**, **Sell position** (or the precise reason it is unavailable), and *Your positions on
  Jupiter* / *This market on Jupiter*;
- each sale attempt with its own state and, only once the provider establishes them, sold contracts,
  proceeds and fees. A sale's transaction joins the item's Transactions list as *Sell position*, with
  the phone's own chain result and explorer link.

A settled or claimable position is *Settled*: it is claimed in Jupiter and never offered for sale.

### When the feed is gone

Managing a position does not depend on the signal's feed staying connected. Removing a connection
removes the proposals it read (`ProposalRepository.load` drops them on the next start), but not the
holding linked to the purchase, its sales, or the owner's Activity record of the purchase. Both the
History page and the sale sheet find the position through the holding's own link to the purchase
(`PositionsState.holdingFor(identity)`), never through the feed's record.

`history/RetainedPurchases.kt` lists the tracked purchases that no longer have a feed record
(`retainedPurchases`, computed only once both the proposals and the positions have been read). Each
one stays in Inbox History under its original signal identity. Opening it shows a page rebuilt by
`retainedHistoryDetail` from what the phone kept: the Activity record's source name and signature,
the side and market from the holding, the purchase time, and the phone's own chain result. The
publisher's own text went with the feed, and the page says so rather than reconstructing it. Below
it are the same Position now card, sale history, Refresh, Jupiter links and Sell as before.

## Selling

1. **Sell position** opens the review sheet (`AppSheet.PositionSale`, only over its own item). Opening
   it reads the position again and asks the provider for a close of the **whole** position.
2. The provider reads the transaction's bytes (`inspectPredictionSale`, [security](../security.md#inspecting-a-sale)).
   The sheet shows the contracts sold, the floor, the least gross proceeds (from the bytes), the
   estimated proceeds and fees (the provider's, labelled as estimates), where the proceeds go, the
   wallet and the network, and that it sells the entire position including purchases elsewhere.
3. **Sell in wallet** is enabled only for a verified, unexpired review with no notice. Tapping it:
   re-checks the review is current and the selected wallet is the owner; reads the position once more
   and sends the owner back to review if the contracts changed; writes the attempt down and arms
   chain tracking; and only then, under the wallet's own lock, asks the wallet to sign and send
   exactly those bytes — once. Waiting for that lock can take any time, so once it is held the
   review's identity and expiry are checked again, and the position re-read if the last read is more
   than 5 s old; any change goes back to review. A second tap is refused as busy.
4. The wallet's answer is recorded: *Submitted* with a signature, *Declined* (nothing sent, retry
   deliberately), *Failed* (never reached the wallet), or *Unresolved* (may have been sent).

Keeper-filled orders are submitted by the wallet like a buy. A build for Jupiter Forecast's atomic
swap model would have to go through Jupiter's `/execute` instead, and is refused before review with a
link to Jupiter; the app never submits through both.

## Reconciling, never repeating

`settleSale` turns what was read into an outcome: a filled order is *Closed* when nothing remains and
*Residual* when contracts remain; a finished partial fill is *Residual*; a failed order, a chain
failure or an expired transaction is *Not executed*. The position read that decides *Closed* or
*Residual* is taken again after the fill is seen, since the one read before it may predate the fill.
A *Residual* beside an order that filled in full stays revisable: a later read that finds the
position empty corrects it to *Closed* (the remainder was the provider's index catching up).

An attempt whose wallet never reported back stays *Unresolved* until an order appears or the chain
proves it never landed: its recent blockhash (kept on the record) has expired on the finalized chain,
**and** the chain has no transaction naming the sale's order account over a period the endpoint's
ledger covers. Only then is it *Lapsed*. Time passing and a provider 404 are not that proof, and an
order that first shows up long after the wallet went quiet still reconciles. A record written before
the blockhash was kept never lapses. Nothing ever rebuilds, signs or sends a sale again on its own,
and while an attempt is unresolved no new one can be prepared.

## Refresh and recovery

- On opening the item, on every return to the app (from the wallet or from Jupiter), and on Refresh.
- While an order or sale is unresolved, the open item polls with a doubling pause (5 s → 60 s) for at
  most 15 minutes. A stable open position is not polled.
- On foreground, and in the chain-confirmations background work while anything is unresolved, the
  tracker reconciles by reading only. Buy orders are watched for 24 hours after purchase.
- One read per position at a time, whatever the number of screens; one shared gate for every call
  (2.1 s apart, a 30 s pause after a 429).
- Stale data is shown as stale with its time; errors never become zeros or successes.

## Storage, privacy and deletion

`positions/storage/PositionStore` keeps `holdings/<account>.json`, `sales/<id>.json` and a
`cleared-at` tombstone under `filesDir/positions`, versioned additively like the other stores. Public
facts only — no URL, no transaction bytes (a sale keeps the SHA-256 of what was approved; the
confirmation tracker keeps the message). Stake, position, sale and P&L data stay on the phone apart
from the calls to Jupiter and Solana; nothing is sent to publishers or the SAC gateway. Clearing
History clears positions and sales too, and a late writer cannot bring either back.

## Accounting

`prediction.sell` is its own action (`PREDICTION_SELL_ACTION`), reached only through the provider's
`PositionManagement`, never from a signal. It spends nothing of the owner's (`movesValue = false`) and
is not an Activity `Transfer`, so no daily limit counts it and the purchase it closes is not counted
again.

## Where the code is

- `plugins/Positions.kt` — the provider-neutral boundary: `PositionManagement`, `HeldPosition`,
  readings, `PreparedSale`, `SaleTerms`, `saleBlockOf`.
- `jupiter/JupiterPrediction.kt` — the three new reads; `jupiter/PredictionSale.kt` — the sale
  inspection and `JupiterPositions`.
- `positions/` — `Positions.kt` (records, `settleSale`), `PositionTracker.kt` (the coordinator, `purchasesOf`,
  `ReadGate`), `PositionsViewModel.kt`, `storage/PositionStore.kt`.
- `history/PositionDetailMapping.kt`, `history/PositionSaleRoute.kt`, `HistoryDetailRoute.kt`,
  `history/RetainedPurchases.kt` (purchases whose feed is gone).
- Design system: `HistoryDetailPosition` in `HistoryDetail.kt`, `PositionSaleSheet.kt`, previews in
  `PositionPreviews.kt`.

## Limits

- Whole-position sales only; no partial or bulk sale; no in-app payout claim; no cancel (Jupiter
  exposes none).
- Mainnet only. The real-wallet sale has not been exercised by this change — see the smoke procedure.
- No Claude Design export exists for the position block or the sale sheet; both are composed from the
  guide's existing pieces and have implementation goldens, not references.
