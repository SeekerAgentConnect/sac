package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import java.net.InetAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The adapter to Jupiter's prediction API, over a real HTTP endpoint (SEE-94).
 *
 * What is worth proving here is the same as on the swap side and one thing more. What leaves the
 * phone: which market, which side, how much, and the address that will sign — and **nothing about
 * the publisher**, because the provider has no business knowing whose signal this came from. What
 * is refused: an answer about another market or another side, and the two refusals the provider
 * makes that the owner should hear as themselves rather than as a general failure.
 */
@RunWith(AndroidJUnit4::class)
class HttpJupiterPredictionTest {
    private val server = MockWebServer()
    private val terms = predictionTerms()
    private val choice = PredictionChoice(yes = true, deposit = 5_000_000UL)

    @Before fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After fun stop() = server.close()

    private fun provider(endpoint: String = "http://127.0.0.1:${server.port}") =
        HttpJupiterPrediction(OkHttpClient(), endpoint)

    private fun answer(body: String, status: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .setHeader("content-type", "application/json")
                .body(body)
                .build()
        )
    }

    private fun orderBody(
        marketId: String = MARKET_ID,
        yes: Boolean = true,
        buy: Boolean = true,
        transaction: String =
            Base64.getEncoder().encodeToString(orderTransaction().transaction.toByteArray()),
    ): String =
        JSONObject()
            .put("transaction", transaction)
            .put(
                "order",
                JSONObject()
                    .put("orderPubkey", ORDER_PUBKEY)
                    .put("positionPubkey", POSITION_PUBKEY)
                    .put("externalOrderId", EXTERNAL_ORDER)
                    .put("marketIdHash", MARKET_HASH)
                    .put("marketId", marketId)
                    .put("isYes", yes)
                    .put("isBuy", buy)
                    .put("contractsMicro", "23700000")
                    .put("maxBuyPriceUsd", "200000")
                    .put("orderCostUsd", "4996705")
                    .put("payoutUsd", "23700000")
                    .put("estimatedTotalFeeUsd", "256743")
                    .put("slippageBps", 0),
            )
            .put("requiredSigners", org.json.JSONArray().put(OWNER))
            .toString()

    private fun failure(block: suspend JupiterPrediction.() -> Unit): PredictionException =
        runBlocking {
            try {
                withTimeout(30_000) { provider().block() }
                throw AssertionError("it answered")
            } catch (e: PredictionException) {
                e
            }
        }

    @Test
    fun aMarketIsReadFromTheProviderAndNotFromTheSignal() {
        answer(marketBody())

        val market = runBlocking { provider().market(MARKET_ID) }

        val asked = server.takeRequest()
        assertEquals("GET", asked.method)
        assertEquals("/prediction/v1/markets/$MARKET_ID", checkNotNull(asked.url).encodedPath)
        // Reading a market says nothing about who is reading it.
        assertFalse(checkNotNull(asked.url).toString().contains(OWNER))
        assertEquals(MARKET_ID, market.marketId)
        assertEquals(EVENT_ID, market.eventId)
        assertTrue(market.open)
        assertEquals(200_000UL, market.buyYesPriceUsd)
        assertEquals(810_000UL, market.buyNoPriceUsd)
        assertTrue(market.rules.isNotEmpty())
    }

    @Test
    fun aMarketThatIsNotOpenIsSaidToBeSoRatherThanRefused() {
        // Closed is a fact about the market, not a failure to read it: the plugin decides what to
        // do about it, and the owner is told which of the two it is.
        answer(marketBody(status = "closed"))
        assertFalse(runBlocking { provider().market(MARKET_ID) }.open)

        answer(marketBody(result = "yes"))
        val settled = runBlocking { provider().market(MARKET_ID) }
        assertFalse(settled.open)
        assertEquals("yes", settled.result)
    }

    @Test
    fun anOrderCarriesTheMarketTheSideTheStakeAndTheAccountThatWillSign() {
        answer(orderBody())

        val order = runBlocking { provider().order(terms, choice, OWNER) }

        val asked = server.takeRequest()
        assertEquals("POST", asked.method)
        assertEquals("/prediction/v1/orders", checkNotNull(asked.url).encodedPath)
        val body = JSONObject(checkNotNull(asked.body).utf8())
        assertEquals(OWNER, body.getString("ownerPubkey"))
        assertEquals(MARKET_ID, body.getString("marketId"))
        assertTrue(body.getBoolean("isYes"))
        // Buying, always: opening a position is what this app does, and managing one is not.
        assertTrue(body.getBoolean("isBuy"))
        assertEquals("5000000", body.getString("depositAmount"))
        assertEquals(USDC_MINT, body.getString("depositMint"))
        // Nothing about the publisher, the proposal, or the feed it arrived on.
        assertFalse(checkNotNull(asked.body).utf8().contains("server/"))
        assertEquals(ORDER_PUBKEY, order.orderPubkey)
        assertEquals(POSITION_PUBKEY, order.positionPubkey)
        assertEquals(23_700_000UL, order.contractsMicro)
        assertEquals(4_996_705UL, order.orderCostUsd)
        assertEquals(256_743UL, order.totalFeeUsd)
        assertEquals(listOf(OWNER), order.requiredSigners)
    }

    @Test
    fun anAnswerAboutAnotherMarketOrAnotherSideIsRefused() {
        answer(orderBody(marketId = "POLY-000000"))
        assertEquals(
            PredictionProblem.Unusable,
            failure { order(terms, choice, OWNER) }.problem,
        )

        answer(orderBody(yes = false))
        assertEquals(
            PredictionProblem.Unusable,
            failure { order(terms, choice, OWNER) }.problem,
        )

        // Selling is not something this app asks for, so an answer that sells is not the answer to
        // the question that was asked.
        answer(orderBody(buy = false))
        assertEquals(
            PredictionProblem.Unusable,
            failure { order(terms, choice, OWNER) }.problem,
        )

        answer(orderBody(transaction = "not base64 at all !!"))
        assertEquals(
            PredictionProblem.Unusable,
            failure { order(terms, choice, OWNER) }.problem,
        )

        answer("not json")
        assertEquals(PredictionProblem.Unusable, failure { market(MARKET_ID) }.problem)
    }

    @Test
    fun theTwoRefusalsWorthTellingApartAreToldApart() {
        // No funds, and a market that closed between the read and the order. Both are the owner's
        // to know about, and neither is "the provider refused".
        answer(
            JSONObject()
                .put("type", "invalid_request_error")
                .put("code", "INSUFFICIENT_FUNDS")
                .put("message", "Insufficient funds")
                .toString(),
            status = 400,
        )
        val funds = failure { order(terms, choice, OWNER) }
        assertEquals(PredictionProblem.InsufficientFunds, funds.problem)
        assertEquals("Insufficient funds", funds.detail)

        answer(JSONObject().put("code", "MARKET_CLOSED").toString(), status = 400)
        assertEquals(
            PredictionProblem.MarketClosed,
            failure { order(terms, choice, OWNER) }.problem,
        )

        answer(JSONObject().put("code", "SOMETHING_ELSE").toString(), status = 400)
        assertEquals(PredictionProblem.Refused, failure { order(terms, choice, OWNER) }.problem)
    }

    @Test
    fun eachWayTheProviderCanSayNoIsReportedAsItself() {
        answer("", status = 404)
        val missing = failure { market("POLY-000000") }
        assertEquals(PredictionProblem.NoSuchMarket, missing.problem)
        assertEquals("POLY-000000", missing.detail)

        answer("slow down", status = 429)
        assertEquals(PredictionProblem.RateLimited, failure { market(MARKET_ID) }.problem)

        answer("oops", status = 503)
        val refused = failure { market(MARKET_ID) }
        assertEquals(PredictionProblem.Refused, refused.problem)
        assertEquals("HTTP 503", refused.detail)
        assertFalse(refused.detail.orEmpty().contains("oops"))
    }

    @Test
    fun aProviderThatIsNotThereIsUnreachableRatherThanSilent() {
        val closed = MockWebServer()
        closed.start(InetAddress.getByName("127.0.0.1"), 0)
        val endpoint = "http://127.0.0.1:${closed.port}"
        closed.close()

        val failed = runBlocking {
            try {
                HttpJupiterPrediction(OkHttpClient(), endpoint).market(MARKET_ID)
                throw AssertionError("it answered")
            } catch (e: PredictionException) {
                e
            }
        }

        assertEquals(PredictionProblem.Unreachable, failed.problem)
    }
}
