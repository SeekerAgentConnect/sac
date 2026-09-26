package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
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
 * Where a prediction order's execution data comes from (SEE-94).
 *
 * Two calls, both to Jupiter, both from the owner's own phone: what the market currently is, and
 * the order itself. The publisher and the shared gateway are told none of it — not the side, not
 * the stake, not the address, not the transaction, not whether anything was signed — because a feed
 * has nowhere to send it (docs/integrations/jupiter.md#prediction-orders).
 *
 * ## The market is asked about, never assumed
 *
 * A publisher names a market and is believed about nothing else. Whether it is open, what the two
 * sides cost, what the rules say and when it settles are read here, at the moment the owner looks,
 * so a signal that has gone stale shows as a closed market rather than as an order that fails.
 *
 * ## No credential in the app
 *
 * The keyless tier, as for the swap: nothing to extract from the APK, and no proxy of the owner's
 * requests through anything of ours. Jupiter's own documentation says the Prediction endpoints take
 * an `x-api-key`; the keyless host serves them without one, which is the arrangement this build
 * uses and the one written down.
 */
interface JupiterPrediction {
    /** The market as the provider currently holds it. */
    suspend fun market(marketId: String): PredictionMarket

    /** The order that would buy [choice]'s side of it, built for [wallet] to sign. */
    suspend fun order(
        terms: PredictionPayload,
        choice: PredictionChoice,
        wallet: String,
    ): PredictionOrder
}

/**
 * A market, as the provider stated it. Every number is the provider's claim and treated as one: it
 * is what the owner is shown about the market, and the transaction is checked against the order the
 * provider then says it built ([inspectPrediction]).
 */
data class PredictionMarket(
    val marketId: String,
    val eventId: String,
    val provider: String,
    val title: String,
    /** `open`, `closed` or `cancelled`, as the provider spells it. */
    val status: String,
    /** Null while unresolved; `yes` or `no` once it has settled. */
    val result: String?,
    /** What a contract of each side costs now, in the deposit token's base units. */
    val buyYesPriceUsd: ULong,
    val buyNoPriceUsd: ULong,
    /** The provider's own rules text. Shown as the provider's, and never parsed. */
    val rules: String,
    /** When it stops trading, in epoch seconds; zero when the provider gives none. */
    val closeTime: Long,
) {
    /** Whether an order can be placed at all. Anything but an open market is refused. */
    val open: Boolean
        get() = status == OPEN && result == null

    companion object {
        const val OPEN = "open"
    }
}

/** The order the provider built, and what it said about building it. */
data class PredictionOrder(
    /** The bytes, with the owner's slot still empty. Nothing else here is evidence about them. */
    val transaction: ByteString,
    /** The order's own account on chain, which the owner's record keeps. */
    val orderPubkey: String,
    /** The position's account, which is where the stake ends up. */
    val positionPubkey: String,
    /** The provider's own identifier for the request, which the instruction carries. */
    val externalOrderId: String,
    /** The market hash the instruction carries, as the provider computes it. */
    val marketIdHash: String,
    val isYes: Boolean,
    val isBuy: Boolean,
    /** Contracts, in millionths: 1000000 is one contract. */
    val contractsMicro: ULong,
    /** The most a contract may cost, in the deposit token's base units. */
    val maxBuyPriceUsd: ULong,
    /** What the order will cost, in the same units. */
    val orderCostUsd: ULong,
    /** What it would pay out if the side wins. Not a promise that it will. */
    val payoutUsd: ULong,
    val totalFeeUsd: ULong,
    val slippageBps: Int,
    /** Who still has to sign. It should be the owner and nobody else. */
    val requiredSigners: List<String>,
)

/** Why the provider produced nothing usable. */
enum class PredictionProblem(val code: String) {
    Unreachable("provider_unreachable"),
    RateLimited("provider_rate_limited"),
    /** The provider has no such market. A publisher can name one that never existed. */
    NoSuchMarket("no_such_market"),
    /** The market exists and is not open, or has already settled. */
    MarketClosed("market_closed"),
    /** The provider refused the order, and said so with a status this phone can report. */
    Refused("provider_refused"),
    /** The owner does not hold enough of the deposit token. The provider checks and says so. */
    InsufficientFunds("insufficient_funds"),
    /** The answer arrived and could not be used. */
    Unusable("provider_unusable"),
}

class PredictionException(val problem: PredictionProblem, val detail: String? = null) :
    Exception("prediction: ${problem.code}${detail?.let { ": $it" } ?: ""}")

/** The real adapter, over the app's shared HTTP client. */
class HttpJupiterPrediction(
    private val httpClient: OkHttpClient,
    private val endpoint: String = JUPITER_ENDPOINT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JupiterPrediction {

    override suspend fun market(marketId: String): PredictionMarket {
        val url = "${endpoint.trimEnd('/')}/prediction/v1/markets/$marketId"
        val answer = parse(fetch(Request.Builder().url(url).get().build(), marketId))
        val pricing = answer.optJSONObject("pricing") ?: JSONObject()
        return PredictionMarket(
            marketId = answer.text("marketId"),
            eventId = answer.optText("eventId").orEmpty(),
            provider = answer.optText("provider").orEmpty(),
            title = answer.optText("title").orEmpty(),
            status = answer.text("status"),
            result = answer.optText("result"),
            buyYesPriceUsd = pricing.baseUnits("buyYesPriceUsd"),
            buyNoPriceUsd = pricing.baseUnits("buyNoPriceUsd"),
            rules = answer.optText("rulesPrimary").orEmpty(),
            closeTime = answer.optLong("closeTime", 0L),
        )
    }

    override suspend fun order(
        terms: PredictionPayload,
        choice: PredictionChoice,
        wallet: String,
    ): PredictionOrder {
        val request =
            JSONObject().apply {
                put("ownerPubkey", wallet)
                put("marketId", terms.marketId)
                put("isYes", choice.yes)
                // Buying, always. Selling a position is managing one, and this app stops at
                // submission (docs/wiki/jupiter-prediction.md#where-this-stops).
                put("isBuy", true)
                put("depositAmount", choice.deposit.toString())
                put("depositMint", terms.depositMint)
            }
        val call =
            Request.Builder()
                .url("${endpoint.trimEnd('/')}/prediction/v1/orders")
                .post(request.toString().toRequestBody(JSON))
                .build()
        return readOrder(parse(fetch(call, terms.marketId)), terms, choice)
    }

    private suspend fun fetch(request: Request, marketId: String): String =
        withContext(io) {
            val response =
                try {
                    httpClient.newCall(request).execute()
                } catch (e: IOException) {
                    throw PredictionException(PredictionProblem.Unreachable, e.message)
                }
            response.use {
                val text =
                    try {
                        it.body?.string().orEmpty()
                    } catch (e: IOException) {
                        throw PredictionException(PredictionProblem.Unreachable, e.message)
                    }
                when {
                    it.isSuccessful -> text
                    it.code == 429 -> throw PredictionException(PredictionProblem.RateLimited)
                    it.code == 404 ->
                        throw PredictionException(PredictionProblem.NoSuchMarket, marketId)
                    // The provider names its own refusals, and two of them are worth telling the
                    // owner apart from a general failure: no funds, and a market that has closed.
                    it.code == 400 -> throw PredictionException(refusal(text), problemDetail(text))
                    else -> throw PredictionException(PredictionProblem.Refused, "HTTP ${it.code}")
                }
            }
        }

    private fun parse(body: String): JSONObject =
        try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw PredictionException(PredictionProblem.Unusable, "the answer is not an object")
        }

    private fun readOrder(
        answer: JSONObject,
        terms: PredictionPayload,
        choice: PredictionChoice,
    ): PredictionOrder {
        val order =
            answer.optJSONObject("order")
                ?: throw PredictionException(PredictionProblem.Unusable, "no order")
        val encoded = answer.text("transaction")
        val bytes =
            try {
                ByteString.copyFrom(Base64.getDecoder().decode(encoded))
            } catch (_: IllegalArgumentException) {
                throw PredictionException(
                    PredictionProblem.Unusable,
                    "the transaction is not base64",
                )
            }
        // The provider answered the question that was asked, or it answered a different one. This
        // is not the review — that reads the bytes — it is the check that the two calls are about
        // one thing.
        if (order.optString("marketId") != terms.marketId) {
            throw PredictionException(PredictionProblem.Unusable, "an order for another market")
        }
        if (order.optBoolean("isYes") != choice.yes || !order.optBoolean("isBuy")) {
            throw PredictionException(PredictionProblem.Unusable, "an order for another side")
        }
        val signers =
            answer.optJSONArray("requiredSigners")?.let { array ->
                (0 until array.length()).map(array::getString)
            } ?: emptyList()
        return PredictionOrder(
            transaction = bytes,
            orderPubkey = order.text("orderPubkey"),
            positionPubkey = order.text("positionPubkey"),
            externalOrderId = order.text("externalOrderId"),
            marketIdHash = order.text("marketIdHash"),
            isYes = order.optBoolean("isYes"),
            isBuy = order.optBoolean("isBuy"),
            contractsMicro = order.baseUnits("contractsMicro"),
            maxBuyPriceUsd = order.baseUnits("maxBuyPriceUsd"),
            orderCostUsd = order.baseUnits("orderCostUsd"),
            payoutUsd = order.baseUnits("payoutUsd"),
            totalFeeUsd = order.optString("estimatedTotalFeeUsd").toULongOrNull() ?: 0UL,
            slippageBps = order.optInt("slippageBps", 0),
            requiredSigners = signers,
        )
    }

    private fun refusal(body: String): PredictionProblem =
        when (code(body)) {
            "INSUFFICIENT_FUNDS" -> PredictionProblem.InsufficientFunds
            "MARKET_CLOSED",
            "MARKET_NOT_OPEN" -> PredictionProblem.MarketClosed
            else -> PredictionProblem.Refused
        }

    private fun code(body: String): String =
        try {
            JSONObject(body).optString("code")
        } catch (_: JSONException) {
            ""
        }

    // The provider's own words, for display, when it gave any that are not a whole error page.
    private fun problemDetail(body: String): String? =
        try {
            JSONObject(body).optString("message").takeIf { it.isNotEmpty() && it.length <= 200 }
        } catch (_: JSONException) {
            null
        }

    /**
     * An optional string, or null when the field is absent **or explicitly null**.
     *
     * The distinction is not pedantry: `optString` on a JSON null answers the four characters
     * `null`, so a market whose `result` is null would read as a market that had settled on an
     * outcome called "null" — and therefore as one no order could be placed on. The wire really
     * does send explicit nulls here.
     */
    private fun JSONObject.optText(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun JSONObject.text(name: String): String =
        optString(name).takeIf { it.isNotEmpty() }
            ?: throw PredictionException(PredictionProblem.Unusable, "no $name")

    /**
     * An amount in base units. The prediction wire writes some of them as JSON numbers and some as
     * strings, so both are read — and neither through a floating-point type.
     */
    private fun JSONObject.baseUnits(name: String): ULong {
        if (isNull(name)) throw PredictionException(PredictionProblem.Unusable, "no $name")
        val text = optString(name)
        return text.toULongOrNull()
            ?: throw PredictionException(PredictionProblem.Unusable, "$name is not base units")
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
