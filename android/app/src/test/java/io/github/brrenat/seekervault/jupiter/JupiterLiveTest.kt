package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.plugins.actions.SwapChoice
import io.github.brrenat.seekervault.transactions.Verdict
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real request to the real provider, opt-in (SEE-93).
 *
 * Every other test in this package runs against captured bytes or a stand-in, because a check that
 * needs the internet is not a check. This one exists for the question none of those can answer:
 * whether the live API still answers the way the fixtures were captured — whether it still serves
 * keyless requests, still honours `asLegacyTransaction`, and still builds a transaction this phone
 * can read whole.
 *
 * Run it with:
 *
 *     android/gradlew -p android :app:testDebugUnitTest \
 *       --tests '*JupiterLiveTest' -Dseekervault.jupiter=https://lite-api.jup.ag
 *
 * **It spends nothing.** A quote is a public read and a build returns unsigned bytes; nothing here
 * holds a key, and no wallet is opened by a unit test. The result of a real *swap* or a real order
 * is a different matter entirely, and is recorded by hand in `docs/testing/stage-7-1.md`.
 */
@RunWith(AndroidJUnit4::class)
class JupiterLiveTest {
    private val endpoint = System.getProperty("seekervault.jupiter")

    @Test
    fun theLiveProviderStillBuildsATransactionThisPhoneCanRead() {
        assumeTrue(
            "set -Dseekervault.jupiter=https://lite-api.jup.ag to run this",
            endpoint != null,
        )
        val provider = HttpJupiterProvider(OkHttpClient(), endpoint!!)
        val terms = usdcTerms(inputMint = SOL_MINT, outputMint = USDC_MINT, maxSlippageBps = 100)
        val amount = 100_000_000UL

        val quote = runBlocking { withTimeout(60_000) { provider.quote(terms, amount, 50) } }
        val built = runBlocking { withTimeout(60_000) { provider.build(quote, OWNER) } }
        val inspection =
            inspectSwap(
                terms = terms,
                choice = SwapChoice(amount, 50),
                quote = quote,
                wallet = wallet(OWNER),
                transaction = built.transaction,
                version = 1,
            )

        assertEquals(
            "the live provider built something this phone refused: " +
                inspection.findings.map { it.code },
            Verdict.Verified,
            inspection.verdict,
        )
        assertTrue(inspection.approvable)
        assertEquals(amount, inspection.facts?.amount)
        assertEquals(OWNER, inspection.facts?.recipient)
        // The offer and the floor, agreeing between the quote and the bytes. If Jupiter ever
        // changes where those numbers sit, this is the test that says so out loud.
        assertEquals(quote.minimumOut, quote.outAmount - quote.outAmount * 50UL / 10_000UL)
    }

    @Test
    fun theLivePredictionApiStillServesMarketsAndOrdersWithoutAKey() {
        assumeTrue(
            "set -Dseekervault.jupiter=https://lite-api.jup.ag to run this",
            endpoint != null,
        )
        val provider = HttpJupiterPrediction(OkHttpClient(), endpoint!!)

        // One open market, from the provider's own listing rather than a hardcoded identifier: a
        // market settles eventually, and a test pinned to one would rot rather than report.
        val listed = runBlocking { withTimeout(60_000) { events(endpoint) } }
        val market = runBlocking { withTimeout(60_000) { provider.market(listed) } }

        assertEquals(listed, market.marketId)
        assertTrue("the listing offered a market that is not open", market.open)
        assertTrue(market.buyYesPriceUsd > 0UL)
        assertTrue(market.buyNoPriceUsd > 0UL)

        // And an order, for a wallet that holds nothing. The provider checks balances and refuses,
        // which is the whole of what this asserts: the endpoint still serves orders keyless, and
        // the refusal still arrives as one this phone can tell apart. **Nothing is placed.**
        val terms =
            PredictionPayload(
                marketId = market.marketId,
                depositMint = USDC_MINT,
                depositDecimals = 6,
                leastDeposit = LEAST_ORDER_DEPOSIT,
            )
        val refused = runBlocking {
            try {
                withTimeout(60_000) {
                    provider.order(
                        terms,
                        PredictionChoice(yes = true, deposit = LEAST_ORDER_DEPOSIT),
                        EMPTY_WALLET,
                    )
                }
                null
            } catch (e: PredictionException) {
                e
            }
        }
        assertEquals(
            "the live API built an order for a wallet holding nothing",
            PredictionProblem.InsufficientFunds,
            refused?.problem,
        )
    }

    /** The first open market the provider currently lists, read with the plainest possible call. */
    private suspend fun events(endpoint: String): String {
        val request =
            okhttp3.Request.Builder()
                .url("${endpoint.trimEnd('/')}/prediction/v1/events?filter=trending&end=20")
                .get()
                .build()
        val body =
            OkHttpClient().newCall(request).execute().use {
                assertTrue("events HTTP ${it.code}", it.isSuccessful)
                checkNotNull(it.body).string()
            }
        val events = org.json.JSONObject(body).getJSONArray("data")
        for (index in 0 until events.length()) {
            val markets = events.getJSONObject(index).optJSONArray("markets") ?: continue
            for (inner in 0 until markets.length()) {
                val market = markets.getJSONObject(inner)
                if (market.optString("status") == PredictionMarket.OPEN) {
                    return market.getString("marketId")
                }
            }
        }
        throw AssertionError("the provider listed no open market")
    }

    private companion object {
        /** A well-formed address that holds nothing, so an order for it is always refused. */
        const val EMPTY_WALLET = "HJdVwh6EY9XSkh3EyKofGqUF7x5tiujt6iKxcJjwEnft"
    }
}
