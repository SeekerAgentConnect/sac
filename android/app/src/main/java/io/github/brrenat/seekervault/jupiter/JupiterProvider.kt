package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/**
 * Where a swap's execution data comes from (SEE-93).
 *
 * This is the plugin's own adapter to Jupiter and nothing in core knows it exists
 * (docs/integrations/jupiter.md). Two calls: what the market currently offers for an exact input
 * amount, and the transaction that would take it. Both are made from the owner's phone, directly.
 *
 * ## What leaves the phone, and to whom
 *
 * The quote carries two mint addresses and an amount. The build additionally carries the owner's
 * public address, because a transaction has to be built for the account that will sign it. That is
 * the whole of it, and it goes to Jupiter alone: **the publisher and the shared gateway are told
 * nothing** — not the amount, not the address, not the transaction, not whether anything was
 * signed. A feed has no path back to its publisher at all (SEE-89), which is what makes that a
 * property of the architecture rather than a promise.
 *
 * ## No credential in the app
 *
 * Nothing here is authenticated. Jupiter's keyless tier is what this uses, so there is no secret in
 * the APK to extract and no proxy of the owner's requests through anything of ours. The price is
 * the rate limit — 0.5 requests a second, 30 a minute, in a sliding minute — which is ample for a
 * person deciding about one signal and is *not* ample for polling, so nothing here polls
 * ([JupiterProblem.RateLimited] is reported as itself and never retried in a loop).
 */
interface JupiterProvider {
    /** What the market offers for exactly [amount] base units of [terms]'s input mint. */
    suspend fun quote(terms: SwapTerms, amount: ULong, slippageBps: Int): JupiterQuote

    /** The transaction that would take [quote], built for [wallet] to sign. */
    suspend fun build(quote: JupiterQuote, wallet: String): JupiterSwap
}

/**
 * A quote, as the provider stated it.
 *
 * Every number here is the provider's claim and is treated as one: it is what the owner is shown as
 * the offer, and the transaction is checked against the owner's own choice and against this — but
 * what the transaction *does* is read from the transaction ([inspectSwap]).
 *
 * [raw] is the answer verbatim, because the build call takes the quote back whole. It is carried
 * rather than rebuilt so that nothing this plugin does can change the offer between the two calls.
 */
data class JupiterQuote(
    val inputMint: String,
    val outputMint: String,
    /** The exact input, in the input mint's base units: the owner's own choice, echoed back. */
    val inAmount: ULong,
    /** What the route currently expects to produce, in the output mint's base units. */
    val outAmount: ULong,
    /** The least the provider's own route will accept, which the chain then enforces. */
    val minimumOut: ULong,
    val slippageBps: Int,
    /** How many hops the route takes. One, because that is what this plugin asks for. */
    val legs: Int,
    val raw: String,
)

/** The transaction the provider built, and what it said about building it. */
data class JupiterSwap(
    /** The bytes, unsigned. Nothing else in this class is evidence about them. */
    val transaction: ByteString,
    /**
     * The provider's own simulation, when it failed: its words, for display. A build that will not
     * execute is not prepared at all, so this is the reason and not a warning
     * ([JupiterProblem.WouldFail]).
     */
    val simulationError: String? = null,
    /** The block height after which the bytes can no longer be included. Display only. */
    val lastValidBlockHeight: Long = 0L,
)

/** Why the provider produced nothing usable. Each is a separate thing to tell the owner. */
enum class JupiterProblem(val code: String) {
    /** The network could not be reached, or the answer never arrived. */
    Unreachable("provider_unreachable"),
    /** The keyless allowance is spent. Waiting is the only fix, and nothing here waits for them. */
    RateLimited("provider_rate_limited"),
    /** There is no route for this pair at this size under the conditions this plugin requires. */
    NoRoute("no_route"),
    /** The provider refused the request, and said so with a status this phone can report. */
    Refused("provider_refused"),
    /**
     * The answer arrived and could not be used: a field missing, a number that is not one, a quote
     * about another pair or another amount, a transaction that loads accounts from a lookup table.
     * It is deliberately one problem rather than several — the owner's next step is the same for
     * all of them, and the difference is in the log, not in the decision.
     */
    Unusable("provider_unusable"),
    /**
     * The provider simulated the transaction and it failed — most often, funds that aren't there.
     */
    WouldFail("would_fail"),
}

/** What the provider could not do, with its own words when it gave any. */
class JupiterException(val problem: JupiterProblem, val detail: String? = null) :
    Exception("jupiter: ${problem.code}${detail?.let { ": $it" } ?: ""}")

/**
 * The real adapter, over the app's shared HTTP client.
 *
 * ## Why the transaction is asked for in the legacy format
 *
 * Because it is the only format the phone can read on its own. A versioned message loads most of
 * its accounts from address lookup tables, and resolving one needs the chain — which this app never
 * reaches, on purpose. Jupiter will happily say what the tables contain, and that answer is exactly
 * the kind of thing this app has never accepted: a builder's account of its own bytes. So the
 * request says `asLegacyTransaction`, the answer's every account is in the message, and a version
 * that still carries a lookup table is refused rather than trusted
 * (docs/wiki/jupiter-swap.md#why-a-legacy-transaction).
 *
 * ## And why one hop
 *
 * `onlyDirectRoutes` narrows which pairs can be swapped and occasionally costs a better price. It
 * buys a transaction of a fixed, small shape, whose single hop this phone then *verifies* in the
 * bytes rather than merely having asked for.
 */
class HttpJupiterProvider(
    private val httpClient: OkHttpClient,
    private val endpoint: String = JUPITER_ENDPOINT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JupiterProvider {

    override suspend fun quote(terms: SwapTerms, amount: ULong, slippageBps: Int): JupiterQuote {
        val url =
            endpoint.trimEnd('/') +
                "/swap/v1/quote" +
                "?inputMint=${terms.inputMint}" +
                "&outputMint=${terms.outputMint}" +
                "&amount=$amount" +
                "&slippageBps=$slippageBps" +
                // The owner chose what they spend, so the input is exact and the output is what it
                // turns out to be. The other mode would let the input float, which is not a thing
                // anyone agreed to.
                "&swapMode=ExactIn" +
                "&onlyDirectRoutes=true" +
                "&asLegacyTransaction=true"
        val body = fetch(Request.Builder().url(url).get().build())
        val answer = parse(body)
        return readQuote(answer, terms, amount, slippageBps, body)
    }

    override suspend fun build(quote: JupiterQuote, wallet: String): JupiterSwap {
        val request =
            JSONObject().apply {
                // The quote goes back exactly as it arrived: the offer the owner is being shown is
                // the offer this is built from.
                put("quoteResponse", JSONObject(quote.raw))
                put("userPublicKey", wallet)
                put("asLegacyTransaction", true)
                // The owner's input may be SOL, which a pool cannot take: these are the wrap and
                // the unwrap, both to and from the owner's own account, and both are read back.
                put("wrapAndUnwrapSol", true)
                put("useSharedAccounts", true)
                // This is what makes the provider simulate. A build that already failed once is
                // not something to put in front of someone as ready to sign.
                put("dynamicComputeUnitLimit", true)
            }
        val call =
            Request.Builder()
                .url(endpoint.trimEnd('/') + "/swap/v1/swap")
                .post(request.toString().toRequestBody(JSON))
                .build()
        return readSwap(parse(fetch(call)))
    }

    // One place where the network's failures become this plugin's, so every call reports them the
    // same way. A status is reported as a status; a body is never quoted back as one.
    private suspend fun fetch(request: Request): String =
        withContext(io) {
            val response =
                try {
                    httpClient.newCall(request).execute()
                } catch (e: IOException) {
                    throw JupiterException(JupiterProblem.Unreachable, e.message)
                }
            response.use {
                val text =
                    try {
                        it.body?.string().orEmpty()
                    } catch (e: IOException) {
                        throw JupiterException(JupiterProblem.Unreachable, e.message)
                    }
                when {
                    it.isSuccessful -> text
                    it.code == 429 -> throw JupiterException(JupiterProblem.RateLimited)
                    // The provider says "no route" with a 4xx and a coded body. It is a fact about
                    // the market at this size, not a failure of the request.
                    it.code == 400 && noRoute(text) ->
                        throw JupiterException(JupiterProblem.NoRoute)
                    else -> throw JupiterException(JupiterProblem.Refused, "HTTP ${it.code}")
                }
            }
        }

    private fun parse(body: String): JSONObject =
        try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw JupiterException(JupiterProblem.Unusable, "the answer is not an object")
        }

    private fun readQuote(
        answer: JSONObject,
        terms: SwapTerms,
        amount: ULong,
        slippageBps: Int,
        raw: String,
    ): JupiterQuote {
        // Everything here is a check that the provider answered the question that was asked. It is
        // not verification of the transaction — that is inspectSwap, and it reads bytes.
        val inputMint = answer.text("inputMint")
        val outputMint = answer.text("outputMint")
        val inAmount = answer.baseUnits("inAmount")
        val outAmount = answer.baseUnits("outAmount")
        val minimumOut = answer.baseUnits("otherAmountThreshold")
        val mode = answer.text("swapMode")
        val slippage = answer.number("slippageBps")
        val legs = answer.optJSONArray("routePlan")?.length() ?: 0
        if (inputMint != terms.inputMint || outputMint != terms.outputMint) {
            throw JupiterException(JupiterProblem.Unusable, "the quote is about another pair")
        }
        if (inAmount != amount) {
            throw JupiterException(JupiterProblem.Unusable, "the quote is for another amount")
        }
        if (mode != "ExactIn") throw JupiterException(JupiterProblem.Unusable, "not an exact input")
        if (slippage != slippageBps) {
            throw JupiterException(JupiterProblem.Unusable, "another slippage")
        }
        // A platform fee would be a cut taken out of the owner's output by whoever asked for it.
        // This plugin asks for none, so one appearing means this is not the request that was made.
        if (!answer.isNull("platformFee")) {
            throw JupiterException(JupiterProblem.Unusable, "a platform fee was added")
        }
        if (legs != 1) throw JupiterException(JupiterProblem.Unusable, "not a single hop")
        if (outAmount == 0UL || minimumOut == 0UL || minimumOut > outAmount) {
            throw JupiterException(JupiterProblem.Unusable, "an output that is not one")
        }
        return JupiterQuote(
            inputMint = inputMint,
            outputMint = outputMint,
            inAmount = inAmount,
            outAmount = outAmount,
            minimumOut = minimumOut,
            slippageBps = slippage,
            legs = legs,
            raw = raw,
        )
    }

    private fun readSwap(answer: JSONObject): JupiterSwap {
        val encoded = answer.text("swapTransaction")
        // Jupiter states what its lookup tables hold when it uses them. That is its account of its
        // own bytes, and this app has never read one of those in place of the bytes, so a build
        // that needs a table is refused here by its own admission and again by the decoder.
        if (!answer.isNull("addressesByLookupTableAddress")) {
            throw JupiterException(JupiterProblem.Unusable, "the transaction needs a lookup table")
        }
        val bytes =
            try {
                ByteString.copyFrom(Base64.getDecoder().decode(encoded))
            } catch (_: IllegalArgumentException) {
                throw JupiterException(JupiterProblem.Unusable, "the transaction is not base64")
            }
        val simulation =
            answer.optJSONObject("simulationError")?.let {
                it.optString("error")
                    .ifEmpty { it.optString("errorCode") }
                    .ifEmpty { "simulation failed" }
            }
        if (simulation != null) throw JupiterException(JupiterProblem.WouldFail, simulation)
        return JupiterSwap(
            transaction = bytes,
            lastValidBlockHeight = answer.optLong("lastValidBlockHeight", 0L),
        )
    }

    private fun noRoute(body: String): Boolean =
        try {
            JSONObject(body).optString("errorCode").contains("ROUTE", ignoreCase = true)
        } catch (_: JSONException) {
            false
        }

    private fun JSONObject.text(name: String): String =
        optString(name).takeIf { it.isNotEmpty() }
            ?: throw JupiterException(JupiterProblem.Unusable, "no $name")

    // Every amount on this wire is a decimal string of base units, and it is read as one: a whole
    // number, unrounded, never through a floating-point type.
    private fun JSONObject.baseUnits(name: String): ULong =
        text(name).toULongOrNull()
            ?: throw JupiterException(JupiterProblem.Unusable, "$name is not base units")

    private fun JSONObject.number(name: String): Int =
        if (isNull(name)) throw JupiterException(JupiterProblem.Unusable, "no $name")
        else
            optInt(name, -1).takeIf { it >= 0 }
                ?: throw JupiterException(JupiterProblem.Unusable, "$name is not a number")

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/**
 * Jupiter's keyless host, which is the one this build uses.
 *
 * It is a constant in the plugin's own package because that is where a provider belongs: core names
 * no provider and holds no endpoint of one (`StageBoundaryTest`). A build pointing somewhere else —
 * a keyed host, a v2 endpoint, a recording proxy in a test — passes it to [HttpJupiterProvider] and
 * changes nothing else.
 */
const val JUPITER_ENDPOINT: String = "https://lite-api.jup.ag"
