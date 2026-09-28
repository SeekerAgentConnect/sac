package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.resolveLookups
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.transactions.decodeTransaction
import java.net.InetAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Jupiter really answers about a position, and the close and buy it really builds (SEE-172).
 *
 * Captured from the keyless API on 2026-09-28 by `node scripts/capture-jupiter.mjs --positions`
 * for a public trader's open position, with the lookup tables the builds name. Nothing was signed
 * or sent. The tests run offline: the tables are in the fixture, and the adapter is fed the
 * captured bodies over a local server.
 */
@RunWith(AndroidJUnit4::class)
class PositionFixturesTest {

    private val fixture: JSONObject by lazy {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/jupiter/positions.json")) {
                    "fixtures/jupiter/positions.json is missing; run " +
                        "`node scripts/capture-jupiter.mjs --positions`"
                }
                .use { it.readBytes().decodeToString() }
        JSONObject(text)
    }

    private val owner get() = fixture.getString("owner")
    private val position get() = fixture.getJSONObject("position")
    private val close get() = fixture.getJSONObject("close")
    private val buyNo get() = fixture.getJSONObject("buyNo")

    private fun tables(build: JSONObject): SolanaAccounts {
        val held =
            build.getJSONArray("tables").let { array ->
                (0 until array.length()).associate { index ->
                    val table = array.getJSONObject(index)
                    table.getString("address") to
                        AccountSnapshot(
                            owner = table.getString("owner"),
                            data = Base64.getDecoder().decode(table.getString("data")),
                            executable = false,
                        )
                }
            }
        return object : SolanaAccounts {
            override suspend fun accounts(addresses: List<String>) = addresses.map { held[it] }
        }
    }

    private fun bytes(build: JSONObject): ByteString =
        ByteString.copyFrom(Base64.getDecoder().decode(build.getString("transaction")))

    private fun orderOf(build: JSONObject): OrderStep.Order = runBlocking {
        val decoded =
            (decodeTransaction(bytes(build).toByteArray(), resolvable = true) as DecodeResult.Decoded)
                .transaction
        val resolved = resolveLookups(decoded, tables(build))
        resolved.transaction.instructions
            .mapNotNull { resolved.readOrderStep(it) }
            .filterIsInstance<OrderStep.Order>()
            .single()
    }

    /** The adapter, reading the captured bodies exactly as the wire sent them. */
    private fun <T> adapter(vararg bodies: Pair<Int, String>, block: suspend HttpJupiterPrediction.() -> T): T {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            bodies.forEach { (status, body) ->
                server.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .setHeader("content-type", "application/json")
                        .body(body)
                        .build()
                )
            }
            return runBlocking {
                HttpJupiterPrediction(OkHttpClient(), "http://127.0.0.1:${server.port}").block()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun theOrderInstructionsFlagsAreTheSideThenTheDirection() {
        // A NO buy and a sale were captured precisely because a YES buy writes 1, 1 and cannot tell
        // the two orders apart. Read the other way round, every NO buy was refused as "not buying".
        val sale = orderOf(close)
        assertEquals(close.getJSONObject("order").getBoolean("isYes"), sale.yes)
        assertFalse(sale.buying)
        assertEquals(close.getJSONObject("order").getString("contractsMicro").toULong(), sale.contractsMicro)
        // A sale's price field is its floor, and it costs nothing.
        assertEquals(close.getJSONObject("order").getString("minSellPriceUsd").toULong(), sale.maxPrice)
        assertEquals(0UL, sale.cost)
        assertEquals(associatedTokenAddress(owner, JUP_USD_MINT), sale.funding)

        val buy = orderOf(buyNo)
        assertFalse(buy.yes)
        assertTrue(buy.buying)
    }

    @Test
    fun theRealGaslessNoBuyIsVerified() {
        val order = buyNo.getJSONObject("order")
        val inspection = runBlocking {
            inspectPrediction(
                terms = predictionTerms(marketId = order.getString("marketId")),
                choice = PredictionChoice(yes = false, deposit = 5_000_000UL),
                order =
                    PredictionOrder(
                        transaction = bytes(buyNo),
                        orderPubkey = order.getString("orderPubkey"),
                        positionPubkey = order.getString("positionPubkey"),
                        externalOrderId = order.getString("externalOrderId"),
                        marketIdHash = order.getString("marketIdHash"),
                        isYes = false,
                        isBuy = true,
                        contractsMicro = order.getString("contractsMicro").toULong(),
                        maxBuyPriceUsd = order.getString("maxBuyPriceUsd").toULong(),
                        orderCostUsd = order.getString("orderCostUsd").toULong(),
                        payoutUsd = order.getString("payoutUsd").toULong(),
                        totalFeeUsd = order.getString("estimatedTotalFeeUsd").toULong(),
                        slippageBps = order.getInt("slippageBps"),
                        requiredSigners = listOf(buyNo.getString("owner")),
                    ),
                wallet = wallet(buyNo.getString("owner")),
                transaction = bytes(buyNo),
                version = 1,
                chain = tables(buyNo),
            )
        }
        assertEquals(
            "the review refused a real NO buy: ${inspection.findings.map { it.code }}",
            Verdict.Verified,
            inspection.verdict,
        )
    }

    @Test
    fun theRealCloseIsVerifiedForItsOwnerAndRefusedForAnyoneElse() {
        val stated = adapter(200 to close.toString()) {
            closePosition(position.getString("pubkey"), owner)
        }
        val now = adapter(200 to position.toString()) { position(position.getString("pubkey")) }
        val held =
            HeldPosition(
                provider = JUPITER_PROVIDER,
                owner = owner,
                network = Network.NETWORK_MAINNET,
                account = now.positionPubkey,
                marketId = now.marketId,
                yes = now.isYes,
            )
        val read = runBlocking {
            inspectPredictionSale(held, now, stated, wallet(owner), 1, tables(close))
        }
        assertEquals(
            "the review refused a real close: ${read.inspection.findings.map { it.code }}",
            Verdict.Verified,
            read.inspection.verdict,
        )
        assertEquals(now.contractsMicro, read.terms.contractsMicro)
        // Gasless: Jupiter's own account pays and has already signed.
        assertTrue(stated.gasless)
        assertEquals(null, stated.executionModel)
        assertEquals("create_order", stated.executionType)
        assertTrue(read.terms.sponsor != null && read.terms.sponsor != owner)

        val stranger = runBlocking {
            inspectPredictionSale(
                held,
                now,
                stated,
                wallet("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"),
                2,
                tables(close),
            )
        }
        assertFalse(stranger.inspection.approvable)
    }

    @Test
    fun theRealPositionIsReadWithEveryFigureTheProviderSent() {
        val read = adapter(200 to position.toString()) { position(position.getString("pubkey")) }
        assertEquals(position.getString("contractsMicro").toULong(), read.contractsMicro)
        assertEquals(position.getString("totalCostUsd").toULong(), read.totalCostUsd)
        assertEquals(position.getString("sellPriceUsd").toULong(), read.sellPriceUsd)
        assertEquals(position.getString("pnlUsd").toLong(), read.pnlUsd)
        assertEquals("open", read.marketStatus)
        assertNull(read.marketResult)
        assertEquals("polymarket", read.venue)
        assertEquals(io.github.brrenat.seekervault.plugins.MarketStanding.Open, read.reading().market)
    }

    @Test
    fun aPositionTheProviderHasNoRecordOfIsNotFoundAndNeverZero() {
        val missing = fixture.getJSONObject("missing")
        assertEquals(404, missing.getInt("status"))
        val problem =
            try {
                adapter(404 to missing.getJSONObject("body").toString()) {
                    position("11111111111111111111111111111111")
                }
                null
            } catch (e: PredictionException) {
                e.problem
            }
        assertEquals(PredictionProblem.NotFound, problem)
    }

    @Test
    fun theRealPartialFillReadsAsPartlyFilledAndFinished() {
        val status = fixture.getJSONObject("orderStatus")
        val read = adapter(200 to status.toString()) { orderStatus(status.getString("orderPubkey")) }
        assertEquals(OrderFill.PartiallyFilledClosed, read.fill)
        assertTrue(read.finished)
        assertEquals(65_660_000UL, read.contractsMicro)
        assertEquals(63_550_000UL, read.filledContractsMicro)
    }
}
