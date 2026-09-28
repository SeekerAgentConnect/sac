package io.github.brrenat.seekervault.positions

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.ChainState
import io.github.brrenat.seekervault.jupiter.HELD_CONTRACTS
import io.github.brrenat.seekervault.jupiter.ORDER_PUBKEY
import io.github.brrenat.seekervault.jupiter.POSITION_PUBKEY
import io.github.brrenat.seekervault.jupiter.SALE_ORDER
import io.github.brrenat.seekervault.jupiter.held
import io.github.brrenat.seekervault.jupiter.predictionPosition
import io.github.brrenat.seekervault.jupiter.reading
import io.github.brrenat.seekervault.plugins.OrderFillState
import io.github.brrenat.seekervault.plugins.OrderRead
import io.github.brrenat.seekervault.plugins.OrderReading
import io.github.brrenat.seekervault.plugins.PositionRead
import io.github.brrenat.seekervault.positions.storage.PositionStore
import java.io.File
import java.time.Duration
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The store, and the rule that turns what was read into what a sale came to (SEE-172). */
@RunWith(AndroidJUnit4::class)
class PositionStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val at = Instant.parse("2026-09-28T08:00:00Z")

    private fun holding() =
        HoldingRecord(
            held = held(),
            purchases =
                listOf(
                    PositionPurchase(CONNECTION, "p1", ORDER_PUBKEY, "sig", at, 5_000_000UL),
                    PositionPurchase(CONNECTION, "p2", null, null, at.plusSeconds(5), null),
                ),
            snapshot = predictionPosition().reading(),
            observedAt = at,
            attemptedAt = at.plusSeconds(30),
            problem = RefreshProblem.RateLimited,
            orders =
                mapOf(
                    ORDER_PUBKEY to
                        OrderSnapshot(
                            OrderReading(
                                ORDER_PUBKEY,
                                OrderFillState.PartiallyFilledClosed,
                                "partiallyfilled",
                                65_660_000UL,
                                63_550_000UL,
                                360_000UL,
                                null,
                                732_160UL,
                                "sig2",
                            ),
                            at,
                        )
                ),
            linkedAt = at,
        )

    private fun sale(stage: SaleStage = SaleStage.Submitted) =
        SaleRecord(
            id = "0b8f5d0e-8a52-4a1c-9d1e-2f1f7c3b9a10",
            held = held(),
            createdAt = at,
            contractsMicro = HELD_CONTRACTS,
            floorPriceMicroUsd = 270_000UL,
            leastGrossMicroUsd = 17_158_500UL,
            estimatedGrossMicroUsd = null,
            estimatedFeeMicroUsd = 2_891_520UL,
            proceedsSymbol = "JupUSD",
            proceedsDecimals = 6,
            proceedsAccount = POSITION_PUBKEY,
            orderAccount = SALE_ORDER,
            contentHash = "ab".repeat(32),
            stage = stage,
            signature = "5".repeat(88).takeIf { stage == SaleStage.Submitted },
            settledAt = at,
            blockhash = "4".repeat(44),
        )

    @Test
    fun aHoldingAndASaleSurviveARestartExactly() {
        val dir = File(folder.root, "positions")
        PositionStore(dir).apply {
            put(holding())
            put(sale())
        }
        val reopened = PositionStore(dir)
        assertEquals(listOf(holding()), reopened.holdings())
        assertEquals(listOf(sale()), reopened.sales())
    }

    @Test
    fun aRecordFromANewerFormatOrWithAStateThisBuildDoesNotKnowIsNotGuessedAt() {
        val newer =
            JSONObject(PositionStore.encodeSale(sale())).put("version", PositionStore.VERSION + 1)
        assertNull(PositionStore.decodeSale(newer.toString()))
        val strange = JSONObject(PositionStore.encodeSale(sale())).put("stage", "teleported")
        assertNull(PositionStore.decodeSale(strange.toString()))
        // An additive field a newer build wrote is simply ignored.
        val extended = JSONObject(PositionStore.encodeSale(sale())).put("proceedsUsd", "123")
        assertEquals(sale(), PositionStore.decodeSale(extended.toString()))
        // A sale written before its blockhash was kept still reads, with none: it can then only
        // stay
        // unresolved, never lapse.
        val older = JSONObject(PositionStore.encodeSale(sale())).apply { remove("blockhash") }
        assertEquals(sale().copy(blockhash = null), PositionStore.decodeSale(older.toString()))
        // A market state this build does not know reads as Unknown, never as open.
        val holding = JSONObject(PositionStore.encodeHolding(holding()))
        holding.getJSONObject("snapshot").put("market", "halted")
        assertEquals(
            io.github.brrenat.seekervault.plugins.MarketStanding.Unknown,
            PositionStore.decodeHolding(holding.toString())?.snapshot?.market,
        )
    }

    @Test
    fun clearingForgetsEverythingAndRemembersWhen() {
        val store = PositionStore(File(folder.root, "positions"))
        store.put(holding())
        store.put(sale())
        store.clear(at)
        assertTrue(store.holdings().isEmpty())
        assertTrue(store.sales().isEmpty())
        assertEquals(at, store.clearedAt())
    }

    @Test
    fun settlingASaleNeedsTheProvidersEvidence() {
        val submitted = sale()
        val filled = OrderRead.Found(order(OrderFillState.Filled))
        val later = at.plusSeconds(30)

        // A chain confirmation alone is not a sale.
        assertEquals(
            SaleResult.Pending,
            settleSale(submitted, ChainCheck(ChainState.Confirmed), OrderRead.NotFound, null, later)
                .result,
        )
        // Filled, and the position holds nothing: closed — a 404 afterwards is expected.
        assertEquals(
            SaleResult.Closed,
            settleSale(submitted, null, filled, PositionRead.NotFound, later).result,
        )
        // Filled, and contracts remain (bought again, or bought elsewhere): residual.
        assertEquals(
            SaleResult.Residual,
            settleSale(
                    submitted,
                    null,
                    filled,
                    PositionRead.Found(predictionPosition(contracts = 1_000_000UL).reading()),
                    later,
                )
                .result,
        )
        assertEquals(
            SaleResult.Residual,
            settleSale(
                    submitted,
                    null,
                    OrderRead.Found(order(OrderFillState.PartiallyFilledClosed)),
                    null,
                    later,
                )
                .result,
        )
        assertEquals(
            SaleResult.NotExecuted,
            settleSale(submitted, null, OrderRead.Found(order(OrderFillState.Failed)), null, later)
                .result,
        )
        assertEquals(
            SaleResult.Pending,
            settleSale(
                    submitted,
                    null,
                    OrderRead.Found(order(OrderFillState.PartiallyFilled)),
                    null,
                    later,
                )
                .result,
        )
        assertEquals(
            SaleResult.NotExecuted,
            settleSale(submitted, ChainCheck(ChainState.Expired), OrderRead.NotFound, null, later)
                .result,
        )
        // A read that failed establishes nothing.
        assertEquals(
            submitted,
            settleSale(
                submitted,
                null,
                OrderRead.Failed(io.github.brrenat.seekervault.plugins.ReadProblem.RateLimited),
                null,
                later,
            ),
        )
        // A signed, submitted sale never lapses on silence, however long it lasts.
        val muchLater = at.plus(Duration.ofHours(6))
        assertEquals(
            SaleResult.Pending,
            settleSale(submitted, null, OrderRead.NotFound, null, muchLater).result,
        )
        assertEquals(
            SaleResult.Pending,
            settleSale(submitted, null, OrderRead.NotFound, null, muchLater, neverLanded = true)
                .result,
        )
        // An unknown answer lapses only on the chain's proof, never on time or a provider 404.
        val unknown = sale(SaleStage.Unresolved)
        assertEquals(
            SaleResult.Pending,
            settleSale(unknown, null, OrderRead.NotFound, null, muchLater).result,
        )
        assertEquals(
            SaleResult.Lapsed,
            settleSale(unknown, null, OrderRead.NotFound, null, later, neverLanded = true).result,
        )
        // An order the provider knows about outranks any claim that nothing landed.
        assertEquals(
            SaleResult.Pending,
            settleSale(
                    unknown,
                    null,
                    OrderRead.Found(order(OrderFillState.Pending)),
                    null,
                    later,
                    neverLanded = true,
                )
                .result,
        )
        // A residue beside a full fill is revised by a later read that finds the position empty,
        // and only by that: a failed read or a 404 changes nothing, and a residue a partial fill
        // left is the order's own account and stays.
        val residual =
            settleSale(
                submitted,
                null,
                filled,
                PositionRead.Found(predictionPosition(contracts = HELD_CONTRACTS).reading()),
                later,
            )
        assertEquals(SaleResult.Residual, residual.result)
        assertTrue(residual.revisable)
        assertFalse(residual.inFlight)
        assertEquals(residual, settleSale(residual, null, null, PositionRead.NotFound, muchLater))
        assertEquals(
            SaleResult.Closed,
            settleSale(
                    residual,
                    null,
                    null,
                    PositionRead.Found(predictionPosition(contracts = 0UL).reading()),
                    muchLater,
                )
                .result,
        )
        val partial =
            settleSale(
                submitted,
                null,
                OrderRead.Found(order(OrderFillState.PartiallyFilledClosed)),
                null,
                later,
            )
        assertFalse(partial.revisable)
        assertEquals(
            partial,
            settleSale(
                partial,
                null,
                null,
                PositionRead.Found(predictionPosition(contracts = 0UL).reading()),
                muchLater,
            ),
        )
        // Once final, nothing moves it.
        val closed = settleSale(submitted, null, filled, PositionRead.NotFound, later)
        assertEquals(
            closed,
            settleSale(closed, ChainCheck(ChainState.Failed), OrderRead.NotFound, null, later),
        )
    }

    private fun order(fill: OrderFillState) =
        OrderReading(
            SALE_ORDER,
            fill,
            fill.code,
            HELD_CONTRACTS,
            HELD_CONTRACTS,
            340_000UL,
            20_000_000UL,
            900_000UL,
            null,
        )

    private companion object {
        const val CONNECTION = "5b1f4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b"
    }
}
