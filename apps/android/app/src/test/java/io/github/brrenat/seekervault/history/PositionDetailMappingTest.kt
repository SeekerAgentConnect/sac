package io.github.brrenat.seekervault.history

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedOperation
import io.github.brrenat.seekervault.activity.ReviewedValue
import io.github.brrenat.seekervault.designsystem.HistoryDetailLink
import io.github.brrenat.seekervault.designsystem.HistoryDetailPositionState
import io.github.brrenat.seekervault.designsystem.HistoryDetailSaleState
import io.github.brrenat.seekervault.designsystem.PositionSaleVerdict
import io.github.brrenat.seekervault.jupiter.HELD_CONTRACTS
import io.github.brrenat.seekervault.jupiter.MARKET_ID
import io.github.brrenat.seekervault.jupiter.ORDER_PUBKEY
import io.github.brrenat.seekervault.jupiter.OWNER
import io.github.brrenat.seekervault.jupiter.POSITION_PUBKEY
import io.github.brrenat.seekervault.jupiter.SALE_ORDER
import io.github.brrenat.seekervault.jupiter.held
import io.github.brrenat.seekervault.jupiter.predictionPosition
import io.github.brrenat.seekervault.jupiter.reading
import io.github.brrenat.seekervault.jupiter.wallet
import io.github.brrenat.seekervault.operations.predictionProposal
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.OrderFillState
import io.github.brrenat.seekervault.plugins.OrderReading
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.PreparedSale
import io.github.brrenat.seekervault.plugins.SaleTerms
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.positions.HoldingRecord
import io.github.brrenat.seekervault.positions.OrderSnapshot
import io.github.brrenat.seekervault.positions.PositionPurchase
import io.github.brrenat.seekervault.positions.PositionsState
import io.github.brrenat.seekervault.positions.RefreshProblem
import io.github.brrenat.seekervault.positions.SaleDraft
import io.github.brrenat.seekervault.positions.SaleRecord
import io.github.brrenat.seekervault.positions.SaleResult
import io.github.brrenat.seekervault.positions.SaleReviewState
import io.github.brrenat.seekervault.positions.SaleStage
import io.github.brrenat.seekervault.positions.purchasesOf
import io.github.brrenat.seekervault.proposals.ProposalExecution
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.binding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.Verdict
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live position block and the sale review, as the owner reads them (SEE-172, acceptance
 * criteria 1, 2, 4, 5, 8, 10 and 11).
 */
class PositionDetailMappingTest {
    private val clock = HistoryDetailClock(ZoneOffset.UTC, Locale.US)
    private val now = Instant.parse("2026-09-28T08:00:00Z")
    private val links =
        listOf(HistoryDetailLink("Your positions on Jupiter", "https://example.test"))

    private val side =
        ParameterChoice(
            mapOf(
                PredictionParameterNames.OUTCOME to ParameterValue.Selected(PredictionOutcomes.YES),
                PredictionParameterNames.DEPOSIT to ParameterValue.Amount(5_000_000UL),
            )
        )

    private fun record(
        proposalId: String = PROPOSAL,
        environment: PluginEnvironment = PluginEnvironment.Production,
        outcome: ProposalOutcome =
            ProposalOutcome.Submitted(ByteString.copyFrom(ByteArray(64) { 4 })),
    ): ProposalRecord {
        val base = ProposalRecord(CONNECTION, proposal(predictionProposal(proposalId = proposalId)))
        return base.copy(
            execution =
                ProposalExecution(
                    binding =
                        binding(base.proposal, side, environment = environment, wallet = OWNER),
                    startedAt = now.minusSeconds(600),
                    outcome = outcome,
                    settledAt = now.minusSeconds(590),
                )
        )
    }

    private fun activity(proposalId: String = PROPOSAL, references: List<ReviewedValue> = refs()) =
        ActivityRecord(
            connectionId = CONNECTION,
            requestId = proposalId,
            source = "Trader Signals",
            serverHost = "feeds.example",
            kind = ActivityKind.Operation,
            answeredAt = now.minusSeconds(600),
            recordedAt = now.minusSeconds(590),
            outcome = ActivityOutcome.Sent,
            operation =
                ReviewedOperation(
                    operation = "prediction",
                    plugin = "jupiter.prediction",
                    contract = 2,
                    revision = 1,
                    wallet = OWNER,
                    network = Network.NETWORK_MAINNET,
                    environment = PluginEnvironment.Production,
                    preparedVersion = 1,
                    references = references,
                ),
        )

    private fun refs(market: String = MARKET_ID) =
        listOf(
            ReviewedValue("order_account", ORDER_PUBKEY),
            ReviewedValue("position_account", POSITION_PUBKEY),
            ReviewedValue("market_id", market),
        )

    private fun holding(
        purchases: List<String> = listOf(PROPOSAL),
        snapshot: Boolean = true,
        observedAt: Instant? = now.minusSeconds(10),
        problem: RefreshProblem? = null,
        orders: Map<String, OrderSnapshot> = emptyMap(),
        position: io.github.brrenat.seekervault.plugins.PositionReading =
            predictionPosition().reading(),
    ) =
        HoldingRecord(
            held = held(),
            purchases =
                purchases.map {
                    PositionPurchase(
                        CONNECTION,
                        it,
                        ORDER_PUBKEY,
                        "sig",
                        now.minusSeconds(600),
                        5_000_000UL,
                    )
                },
            snapshot = position.takeIf { snapshot },
            observedAt = observedAt.takeIf { snapshot },
            attemptedAt = now.minusSeconds(5),
            problem = problem,
            orders = orders,
            linkedAt = now.minusSeconds(600),
        )

    private fun state(holding: HoldingRecord?, sales: List<SaleRecord> = emptyList()) =
        PositionsState(
            loaded = true,
            holdings = holding?.let { mapOf(it.held.account to it) }.orEmpty(),
            sales = sales,
        )

    private fun detail(
        state: PositionsState,
        record: ProposalRecord = record(),
        selected: io.github.brrenat.seekervault.wallet.SelectedWallet? = wallet(),
    ) = checkNotNull(positionDetail(record, state, selected, links, now, clock))

    @Test
    fun onlyProductionBuysThatNameTheirPositionAreLinked() {
        val linked = purchasesOf(listOf(record()), listOf(activity())).single()
        assertEquals(held(), linked.held)
        assertEquals(ORDER_PUBKEY, linked.purchase.orderAccount)
        assertEquals(5_000_000UL, linked.purchase.depositBaseUnits)

        assertTrue(
            purchasesOf(listOf(record(environment = PluginEnvironment.Sandbox)), listOf(activity()))
                .isEmpty()
        )
        assertTrue(
            purchasesOf(listOf(record(outcome = ProposalOutcome.Declined)), listOf(activity()))
                .isEmpty()
        )
        // A legacy record without references stays readable and is simply not tracked.
        assertTrue(
            purchasesOf(listOf(record()), listOf(activity(references = emptyList()))).isEmpty()
        )
        assertTrue(purchasesOf(listOf(record()), emptyList()).isEmpty())
        // A reference to another market than the one bound is not believed.
        assertTrue(
            purchasesOf(listOf(record()), listOf(activity(references = refs(market = "POLY-1"))))
                .isEmpty()
        )
    }

    @Test
    fun aRecordWithoutAPositionSaysTrackingIsUnavailableAndStillLinksToJupiter() {
        val model = detail(state(null))
        assertEquals(HistoryDetailPositionState.Unavailable, model.state)
        assertEquals(PositionCopy.UntrackedBody, model.note)
        assertEquals(links, model.links)
        assertNull(model.sell)
        // Before anything is loaded, it is loading, not unavailable.
        assertEquals(
            HistoryDetailPositionState.Loading,
            detail(PositionsState(loaded = false)).state,
        )
        // Not a purchase at all: no block.
        assertNull(
            positionDetail(
                record(outcome = ProposalOutcome.Declined),
                state(null),
                wallet(),
                links,
                now,
                clock,
            )
        )
    }

    @Test
    fun aPositionNotFoundYetIsNeitherSoldNorZero() {
        val model = detail(state(holding(snapshot = false, problem = RefreshProblem.NotFound)))
        assertEquals(HistoryDetailPositionState.Unavailable, model.state)
        assertEquals(PositionCopy.NotIndexedBody, model.note)
        assertTrue(model.rows.isEmpty())
        assertFalse(model.toString().contains("Sold"))
    }

    @Test
    fun aLivePositionShowsTheProvidersFiguresAndOffersTheSale() {
        val model = detail(state(holding()))
        assertEquals(HistoryDetailPositionState.Live, model.state)
        val rows = model.rows.associate { it.label to it.value }
        assertEquals("63.55", rows["Contracts held"])
        assertEquals("$22.24", rows["Value now"])
        assertEquals("$0.35 a contract", rows["Best bid"])
        assertEquals("−$0.63", rows["P&L"])
        assertEquals(PositionCopy.Scope, model.scope)
        assertTrue(model.sell!!.enabled)
    }

    @Test
    fun aStaleReadKeepsTheLastFiguresAndSaysSo() {
        val model =
            detail(
                state(
                    holding(
                        observedAt = now.minusSeconds(600),
                        problem = RefreshProblem.RateLimited,
                    )
                )
            )
        assertEquals(HistoryDetailPositionState.Stale, model.state)
        assertTrue(model.stateText.startsWith(PositionCopy.LastKnown))
        assertTrue(model.note!!.contains("slow down"))
        assertEquals("63.55", model.rows.first { it.label == "Contracts held" }.value)
    }

    @Test
    fun twoPurchasesOfOnePositionShowOneAggregateAndSayWhatItIncludes() {
        val shared = state(holding(purchases = listOf(PROPOSAL, OTHER_PROPOSAL)))
        val first = detail(shared, record(PROPOSAL))
        val second = detail(shared, record(OTHER_PROPOSAL))
        // The same position, read once, shown the same way on both items.
        assertEquals(first.rows, second.rows)
        assertTrue(first.scope!!.contains("2 purchases"))
    }

    @Test
    fun sellingNeedsTheOwnersWalletAndSaysPreciselyWhyNot() {
        val stranger = wallet("7ToYommiXbxMdd7KaT8wgFYGzuWeHmGBvscGrgFTEWL2")
        val model = detail(state(holding()), selected = stranger)
        assertFalse(model.sell!!.enabled)
        assertTrue(model.sell!!.reason!!.contains("belongs to"))
        assertEquals(
            "Connect the wallet that bought this position to sell it.",
            detail(state(holding()), selected = null).sell!!.reason,
        )
        val busy = sale(SaleStage.Submitted, SaleResult.Pending)
        assertTrue(
            detail(state(holding(), listOf(busy))).sell!!.reason!!.contains("still being settled")
        )
        val openOrder = holding(position = predictionPosition(openOrders = 1).reading())
        assertTrue(detail(state(openOrder)).sell!!.reason!!.contains("still open"))
    }

    @Test
    fun aSettledPositionIsClaimedInJupiterAndNeverOfferedForSale() {
        val settled =
            holding(
                position =
                    predictionPosition(claimable = true, result = "yes", status = "closed")
                        .reading()
            )
        val model = detail(state(settled))
        assertEquals(HistoryDetailPositionState.Settled, model.state)
        assertNull(model.sell)
        assertEquals(PositionCopy.SettledBody, model.note)
    }

    @Test
    fun theOrdersFillIsItsOwnFactApartFromTheChain() {
        val pending =
            OrderSnapshot(
                OrderReading(
                    ORDER_PUBKEY,
                    OrderFillState.Pending,
                    "created",
                    20_000_000UL,
                    0UL,
                    null,
                    null,
                    null,
                    null,
                ),
                now,
            )
        val rows = detail(state(holding(orders = mapOf(ORDER_PUBKEY to pending)))).orderRows
        assertEquals("Waiting to fill", rows.first { it.label == "Fill" }.value)
        val partial =
            pending.copy(
                reading =
                    pending.reading.copy(
                        fill = OrderFillState.PartiallyFilledClosed,
                        filledContractsMicro = 12_500_000UL,
                    )
            )
        assertEquals(
            "Partly filled, rest returned · 12.5 of 20",
            detail(state(holding(orders = mapOf(ORDER_PUBKEY to partial)))).orderRows.first().value,
        )
        // No report yet is said, never assumed.
        assertEquals(PositionCopy.FillUnknown, detail(state(holding())).orderRows.first().value)
    }

    @Test
    fun aSaleIsItsOwnOperationAndProceedsAppearOnlyOnceEstablished() {
        val pending = sale(SaleStage.Submitted, SaleResult.Pending)
        val shown = detail(state(holding(), listOf(pending))).sales.single()
        assertEquals(HistoryDetailSaleState.Pending, shown.state)
        assertEquals(PositionCopy.KnownAfterFill, shown.rows.first { it.label == "Proceeds" }.value)

        val closed =
            sale(SaleStage.Submitted, SaleResult.Closed)
                .copy(
                    order =
                        OrderSnapshot(
                            OrderReading(
                                SALE_ORDER,
                                OrderFillState.Filled,
                                "filled",
                                HELD_CONTRACTS,
                                HELD_CONTRACTS,
                                340_000UL,
                                20_700_000UL,
                                900_000UL,
                                null,
                            ),
                            now,
                        )
                )
        val model =
            detail(
                state(holding(snapshot = false, problem = RefreshProblem.NotFound), listOf(closed))
            )
        assertEquals(HistoryDetailPositionState.Closed, model.state)
        assertNull(model.sell)
        val row = model.sales.single()
        assertEquals("Sold", row.stateText)
        assertEquals("20.7 JupUSD", row.rows.first { it.label == "Proceeds" }.value)

        val declined = sale(SaleStage.Declined, SaleResult.NotExecuted)
        assertEquals(
            "Declined in wallet",
            detail(state(holding(), listOf(declined))).sales.single().stateText,
        )
    }

    @Test
    fun theSaleReviewSaysItSellsTheWholePositionAndOnlyAVerifiedCurrentReviewCanBeApproved() {
        val holding = holding()
        val ready = SaleReviewState.Ready(draft(Verdict.Verified))
        val sheet = positionSaleSheet(ready, holding, null, now, { "text:$it" }, clock)
        assertEquals(PositionSaleVerdict.Verified, sheet.verdict)
        assertTrue(sheet.primaryEnabled)
        assertTrue(sheet.scope.contains("entire current position"))
        assertTrue(sheet.scope.contains("63.55 contracts"))
        assertEquals(
            "63.55 Yes contracts · all of them",
            sheet.terms.first { it.label == "Sells" }.value,
        )
        assertEquals(
            "$0.27 a contract",
            sheet.terms.first { it.label == "Lowest price accepted" }.value,
        )
        assertEquals(
            "≈ 22.2425 JupUSD",
            sheet.facts.first { it.label == "Estimated proceeds" }.value,
        )
        assertTrue(sheet.expiry!!.contains("not guaranteed"))

        // Ran out: disabled, and the only way on is to prepare again.
        val expired =
            positionSaleSheet(ready, holding, null, now.plusSeconds(120), { "text:$it" }, clock)
        assertFalse(expired.primaryEnabled)
        assertTrue(expired.staleNotice != null)

        // Refused by the review: the findings are shown and nothing can be approved.
        val refused =
            positionSaleSheet(
                SaleReviewState.Ready(draft(Verdict.Invalid)),
                holding,
                null,
                now,
                { "text:$it" },
                clock,
            )
        assertEquals(PositionSaleVerdict.Refused, refused.verdict)
        assertFalse(refused.primaryEnabled)
        assertEquals(listOf("text:7"), refused.findings)

        // A changed position after review disables the sale as well.
        val changed =
            positionSaleSheet(ready, holding, "The position changed", now, { "text:$it" }, clock)
        assertFalse(changed.primaryEnabled)
    }

    private fun draft(verdict: Verdict): SaleDraft =
        SaleDraft(
            held = held(),
            prepared =
                PreparedSale(
                    transaction = ByteString.copyFrom(ByteArray(4)),
                    version = 1,
                    expiresAtEpochSeconds = now.plusSeconds(60).epochSecond,
                    inspection =
                        ActionInspection(
                            verdict = verdict,
                            findings =
                                if (verdict == Verdict.Verified) emptyList()
                                else listOf(PluginFinding("weak_floor", 7)),
                            facts = null,
                            version = 1,
                        ),
                    terms =
                        SaleTerms(
                            position = POSITION_PUBKEY,
                            owner = OWNER,
                            marketId = MARKET_ID,
                            yes = true,
                            contractsMicro = HELD_CONTRACTS,
                            heldContractsMicro = HELD_CONTRACTS,
                            floorPriceMicroUsd = 270_000UL,
                            leastGrossMicroUsd = 17_158_500UL,
                            estimatedGrossMicroUsd = 22_242_500UL,
                            estimatedFeeMicroUsd = 2_891_520UL,
                            proceedsMint = "JuprjznTrTSp2UFa3ZBUFgwdAmtZCq4MQCwysN55USD",
                            proceedsSymbol = "JupUSD",
                            proceedsDecimals = 6,
                            proceedsAccount = POSITION_PUBKEY,
                            orderAccount = SALE_ORDER,
                            sponsor = "748XjHxBdkWo4rLwzMXuYieUejv3R8X2dGPys7VGDkAj",
                            networkFeeLamports = null,
                        ),
                    position = predictionPosition().reading(),
                ),
            wallet = wallet(),
            preparedAt = now,
        )

    private fun sale(stage: SaleStage, result: SaleResult) =
        SaleRecord(
            id = "0b8f5d0e-8a52-4a1c-9d1e-2f1f7c3b9a10",
            held = held(),
            createdAt = now.minusSeconds(30),
            contractsMicro = HELD_CONTRACTS,
            floorPriceMicroUsd = 270_000UL,
            leastGrossMicroUsd = 17_158_500UL,
            estimatedGrossMicroUsd = 22_242_500UL,
            estimatedFeeMicroUsd = 2_891_520UL,
            proceedsSymbol = "JupUSD",
            proceedsDecimals = 6,
            proceedsAccount = POSITION_PUBKEY,
            orderAccount = SALE_ORDER,
            contentHash = "00",
            stage = stage,
            signature = null,
            result = result,
        )

    private companion object {
        const val CONNECTION = "5b1f4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b"
        const val PROPOSAL = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val OTHER_PROPOSAL = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"
    }
}
