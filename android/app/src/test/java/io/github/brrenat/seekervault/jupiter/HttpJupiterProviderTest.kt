package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * The adapter to Jupiter, over a real HTTP endpoint (SEE-93).
 *
 * Two things are worth proving here and both are about the wire. The first is what leaves the
 * phone: a quote carries two mints and an amount and **nothing about the owner**, and only the
 * build — which has to be built for the account that signs — carries their address. The second is
 * that an answer to a different question is refused rather than used: another pair, another amount,
 * another slippage, a fee somebody added, a route with more hops than was asked for.
 */
@RunWith(AndroidJUnit4::class)
class HttpJupiterProviderTest {
    private val server = MockWebServer()
    private val terms = usdcTerms(inputMint = USDC_MINT, outputMint = SOL_MINT)
    private val amount = 10_000_000UL

    @Before fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After fun stop() = server.close()

    private fun provider(endpoint: String = "http://127.0.0.1:${server.port}") =
        HttpJupiterProvider(OkHttpClient(), endpoint)

    private fun quoteBody(
        inputMint: String = terms.inputMint,
        outputMint: String = terms.outputMint,
        inAmount: String = amount.toString(),
        outAmount: String = "99000000",
        threshold: String = "98505000",
        mode: String = "ExactIn",
        slippageBps: Int = 50,
        legs: Int = 1,
        platformFee: String = "null",
    ) =
        """
        {"inputMint":"$inputMint","outputMint":"$outputMint","inAmount":"$inAmount",
         "outAmount":"$outAmount","otherAmountThreshold":"$threshold","swapMode":"$mode",
         "slippageBps":$slippageBps,"platformFee":$platformFee,
         "routePlan":[${(1..legs).joinToString(",") { """{"percent":100}""" }}]}
        """
            .trimIndent()

    private fun swapBody(
        transaction: String = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
        simulationError: String = "null",
        lookupTables: String = "null",
    ) =
        """
        {"swapTransaction":"$transaction","lastValidBlockHeight":425879727,
         "simulationError":$simulationError,"addressesByLookupTableAddress":$lookupTables}
        """
            .trimIndent()

    private fun answer(body: String, status: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .setHeader("content-type", "application/json")
                .body(body)
                .build()
        )
    }

    private fun quote(slippageBps: Int = 50): JupiterQuote = runBlocking {
        withTimeout(30_000) { provider().quote(terms, amount, slippageBps) }
    }

    private fun failure(block: suspend JupiterProvider.() -> Unit): JupiterException = runBlocking {
        try {
            withTimeout(30_000) { provider().block() }
            throw AssertionError("the provider answered")
        } catch (e: JupiterException) {
            e
        }
    }

    @Test
    fun aQuoteAsksAboutTwoMintsAndAnAmountAndSaysNothingAboutTheOwner() {
        answer(quoteBody())

        val read = quote()

        val asked = server.takeRequest()
        val url = checkNotNull(asked.url)
        assertEquals("GET", asked.method)
        assertEquals("/swap/v1/quote", url.encodedPath)
        assertEquals(terms.inputMint, url.queryParameter("inputMint"))
        assertEquals(terms.outputMint, url.queryParameter("outputMint"))
        assertEquals(amount.toString(), url.queryParameter("amount"))
        assertEquals("50", url.queryParameter("slippageBps"))
        // The owner chose what they spend, so the input is exact.
        assertEquals("ExactIn", url.queryParameter("swapMode"))
        // And the two conditions that make the answer readable on this phone.
        assertEquals("true", url.queryParameter("onlyDirectRoutes"))
        assertEquals("true", url.queryParameter("asLegacyTransaction"))
        // Nothing about whose swap this is. A price is a public fact.
        assertFalse(url.toString().contains(OWNER))
        assertEquals(99_000_000UL, read.outAmount)
        assertEquals(98_505_000UL, read.minimumOut)
        assertEquals(1, read.legs)
    }

    @Test
    fun theBuildCarriesTheQuoteBackWholeAndTheAccountThatWillSign() {
        answer(quoteBody())
        answer(swapBody())
        val read = quote()

        val built = runBlocking { withTimeout(30_000) { provider().build(read, OWNER) } }

        server.takeRequest()
        val asked = server.takeRequest()
        assertEquals("POST", asked.method)
        assertEquals("/swap/v1/swap", checkNotNull(asked.url).encodedPath)
        val body = JSONObject(checkNotNull(asked.body).utf8())
        assertEquals(OWNER, body.getString("userPublicKey"))
        assertTrue(body.getBoolean("asLegacyTransaction"))
        assertTrue(body.getBoolean("wrapAndUnwrapSol"))
        assertTrue(body.getBoolean("useSharedAccounts"))
        // Which is what makes the provider simulate, and therefore what makes a swap that would
        // fail for want of funds stop here instead of at the wallet.
        assertTrue(body.getBoolean("dynamicComputeUnitLimit"))
        // The offer goes back exactly as it arrived, so the bytes are built from the offer the
        // owner is being shown rather than from a second, later one.
        val echoed = body.getJSONObject("quoteResponse")
        assertEquals("99000000", echoed.getString("outAmount"))
        assertEquals(3, built.transaction.size())
    }

    @Test
    fun anAnswerToADifferentQuestionIsRefused() {
        val cases =
            listOf(
                "another pair" to quoteBody(outputMint = JUP_MINT),
                "another amount" to quoteBody(inAmount = "9999999"),
                "another slippage" to quoteBody(slippageBps = 300),
                "a floating input" to quoteBody(mode = "ExactOut"),
                "a fee somebody added" to quoteBody(platformFee = """{"amount":"1","feeBps":1}"""),
                "more hops than asked for" to quoteBody(legs = 2),
                "no hops at all" to quoteBody(legs = 0),
                "an output of nothing" to quoteBody(outAmount = "0"),
                "a floor above the offer" to quoteBody(threshold = "99000001"),
                "an amount that is not base units" to quoteBody(outAmount = "99.5"),
                "no answer at all" to "not json",
            )
        for ((what, body) in cases) {
            answer(body)
            val failed = failure { quote(terms, amount, 50) }
            assertEquals(what, JupiterProblem.Unusable, failed.problem)
        }
    }

    @Test
    fun eachWayTheProviderCanSayNoIsReportedAsItself() {
        answer("""{"error":"slow down"}""", status = 429)
        assertEquals(JupiterProblem.RateLimited, failure { quote(terms, amount, 50) }.problem)

        answer("""{"errorCode":"COULD_NOT_FIND_ANY_ROUTE"}""", status = 400)
        assertEquals(JupiterProblem.NoRoute, failure { quote(terms, amount, 50) }.problem)

        answer("""{"error":"bad request"}""", status = 400)
        assertEquals(JupiterProblem.Refused, failure { quote(terms, amount, 50) }.problem)

        answer("oops", status = 503)
        val refused = failure { quote(terms, amount, 50) }
        assertEquals(JupiterProblem.Refused, refused.problem)
        // A status, and never the body: an error page is not something to quote back to somebody.
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
                HttpJupiterProvider(OkHttpClient(), endpoint).quote(terms, amount, 50)
                throw AssertionError("it answered")
            } catch (e: JupiterException) {
                e
            }
        }

        assertEquals(JupiterProblem.Unreachable, failed.problem)
    }

    @Test
    fun aBuildTheProviderItselfSaysWouldFailIsNotUsed() {
        answer(quoteBody())
        answer(
            swapBody(
                simulationError =
                    """{"errorCode":"TRANSACTION_ERROR","error":"Attempt to debit an account but found no record of a prior credit."}"""
            )
        )
        val read = quote()

        val failed = failure { build(read, OWNER) }

        assertEquals(JupiterProblem.WouldFail, failed.problem)
        // The provider's own words, carried through for the owner to read and parsed by nobody.
        assertTrue(failed.detail.orEmpty().contains("no record of a prior credit"))
    }

    @Test
    fun aBuildThatNeedsALookupTableOrIsNotBytesIsRefused() {
        answer(quoteBody())
        answer(swapBody(lookupTables = """{"$POOL":["$OWNER"]}"""))
        val read = quote()

        // Jupiter states what its tables hold. That is its account of its own bytes, and this app
        // has never read one of those in place of the bytes.
        val table = failure { build(read, OWNER) }
        assertEquals(JupiterProblem.Unusable, table.problem)
        assertTrue(table.detail.orEmpty().contains("lookup table"))

        answer(quoteBody())
        answer(swapBody(transaction = "not base64 at all !!"))
        val second = quote()
        assertEquals(JupiterProblem.Unusable, failure { build(second, OWNER) }.problem)
    }

    @Test
    fun theRealAnswersThatWereCapturedGoThroughThisAdapterUnchanged() {
        // The committed fixtures are real bodies from the live API. Running one back through the
        // adapter proves the parsing is about the provider's actual answer rather than about the
        // shape of the examples in this file.
        val fixture = swapFixtures().first()
        val real = usdcTerms(fixture.terms.inputMint, fixture.terms.outputMint)
        answer(
            quoteBody(
                inputMint = fixture.terms.inputMint,
                outputMint = fixture.terms.outputMint,
                inAmount = fixture.amount.toString(),
                outAmount = fixture.quote.outAmount.toString(),
                threshold = fixture.quote.minimumOut.toString(),
                slippageBps = fixture.slippageBps,
            )
        )
        answer(
            swapBody(
                transaction = Base64.getEncoder().encodeToString(fixture.transaction.toByteArray())
            )
        )

        val read = runBlocking {
            provider().quote(real, fixture.amount, fixture.slippageBps)
        }
        val built = runBlocking { provider().build(read, fixture.owner) }

        assertEquals(fixture.quote.outAmount, read.outAmount)
        assertEquals(fixture.quote.minimumOut, read.minimumOut)
        assertEquals(fixture.transaction, built.transaction)
    }
}
