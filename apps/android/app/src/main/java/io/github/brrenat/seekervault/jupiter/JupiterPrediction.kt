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

    /**
     * One position as the provider holds it now (SEE-172). A position the provider has no record
     * of throws [PredictionProblem.NotFound], which is **not** a sale, a loss or a zero: it is also
     * what a position looks like before the indexer has caught up with a fill.
     */
    suspend fun position(positionPubkey: String): PredictionPosition

    /** What the provider says happened to one order: its fills, and whether it is finished. */
    suspend fun orderStatus(orderPubkey: String): PredictionOrderStatus

    /**
     * The order that would sell the whole of [positionPubkey], built for [owner] to sign. An
     * answer is unsigned bytes and the provider's account of them — never a sale.
     */
    suspend fun closePosition(positionPubkey: String, owner: String): PredictionClose
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

/**
 * A position as the provider stated it (SEE-172): the wallet's **whole** holding of one side of one
 * market, however many orders — from this app or anywhere else — went into it.
 *
 * Every figure is the provider's and is shown as the provider's. The ones Jupiter sends as null
 * once a market has closed stay null here rather than becoming zero: a value nobody quoted is not a
 * value of nothing.
 */
data class PredictionPosition(
    val positionPubkey: String,
    val owner: String,
    val marketId: String,
    val eventId: String,
    val isYes: Boolean,
    /** Contracts held, in millionths. */
    val contractsMicro: ULong,
    /** What the holding cost in total, in micro-dollars. */
    val totalCostUsd: ULong,
    /** What it is marked at now, or null when the provider quotes nothing (a closed market). */
    val valueUsd: ULong?,
    val markPriceUsd: ULong?,
    /** The best price a contract of this side could be sold at now, or null when there is none. */
    val sellPriceUsd: ULong?,
    val avgPriceUsd: ULong?,
    val pnlUsd: Long?,
    val pnlUsdAfterFees: Long?,
    val feesPaidUsd: ULong,
    /** Orders on this position the provider has not finished with. */
    val openOrders: Int,
    val claimable: Boolean,
    val claimed: Boolean,
    val payoutUsd: ULong,
    /** The market's own state, as the provider spells it: `open`, `closed`, `cancelled`… */
    val marketStatus: String,
    /** Null while unresolved; the winning side once it has settled. */
    val marketResult: String?,
    val marketTitle: String,
    val eventTitle: String,
    /** The venue the market is on — Polymarket, Kalshi — which is not the execution provider. */
    val venue: String,
    /** When the provider last saw the position change, in epoch seconds. */
    val updatedAt: Long,
)

/** What an order came to, as far as the provider can say (SEE-172). */
enum class OrderFill {
    /** Placed and waiting for the keeper. Nothing has filled yet. */
    Pending,
    /** Some contracts filled and the order is still open. */
    PartiallyFilled,
    /** Every contract it asked for filled. */
    Filled,
    /** Finished with some contracts filled and the rest returned. */
    PartiallyFilledClosed,
    /** Finished with nothing filled. */
    Failed,
    /** An answer this build does not know the meaning of. Never read as any of the others. */
    Unknown,
}

/** One order's status, and the numbers the provider attaches to its fill. */
data class PredictionOrderStatus(
    val orderPubkey: String,
    val fill: OrderFill,
    /** The provider's own words for the state, kept for the record and never parsed again. */
    val rawStatus: String,
    val finished: Boolean,
    val contractsMicro: ULong?,
    val filledContractsMicro: ULong?,
    val avgFillPriceUsd: ULong?,
    /** What a sale actually paid out after fees, when the provider states it. */
    val netProceedsUsd: ULong?,
    val feeUsd: ULong?,
    /** The chain signature of the provider's latest event about the order. */
    val latestSignature: String?,
)

/**
 * The order that would sell a whole position, and what the provider said about building it.
 *
 * Only [transaction] is evidence. Everything else is the provider's claim and is compared with what
 * the bytes say before anything is put in front of the owner ([inspectPredictionSale]).
 */
data class PredictionClose(
    val transaction: ByteString,
    val orderPubkey: String,
    val positionPubkey: String,
    val externalOrderId: String,
    val marketId: String,
    val marketIdHash: String,
    val isYes: Boolean,
    val isBuy: Boolean,
    val contractsMicro: ULong,
    /** What the position holds once the order has executed. A whole sale leaves zero. */
    val newContractsMicro: ULong,
    /** The floor: the program fills no contract for less than this. The one enforceable bound. */
    val minSellPriceUsd: ULong,
    val totalFeeUsd: ULong,
    val requiredSigners: List<String>,
    /** `null` for a keeper-filled order, `atomic_swap` for one that executes through `/execute`. */
    val executionModel: String?,
    /** The `type` inside the provider's opaque execution context: `create_order` for a keeper. */
    val executionType: String?,
    /** Whether the provider pays the network fee with its own pre-signed account. */
    val gasless: Boolean,
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
    /**
     * The provider has no record of the position or order asked about. Not a sale and not a loss:
     * a fill the indexer has not caught up with looks exactly like this.
     */
    NotFound("not_found"),
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
                // Buying, always. Selling is a different request with a different review: the
                // whole position is closed through [closePosition] (SEE-172).
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

    override suspend fun position(positionPubkey: String): PredictionPosition {
        val url = "${endpoint.trimEnd('/')}/prediction/v1/positions/${account(positionPubkey)}"
        return readPosition(parse(fetch(Request.Builder().url(url).get().build(), null)))
    }

    override suspend fun orderStatus(orderPubkey: String): PredictionOrderStatus {
        val url = "${endpoint.trimEnd('/')}/prediction/v1/orders/status/${account(orderPubkey)}"
        return readStatus(parse(fetch(Request.Builder().url(url).get().build(), null)))
    }

    override suspend fun closePosition(positionPubkey: String, owner: String): PredictionClose {
        // The whole position, and only the owner's key: the endpoint takes nothing else, so there
        // is no quantity, price or slippage here for anybody to have got wrong. What bounds the
        // sale is the floor in the bytes, which the review reads.
        val body = JSONObject().put("ownerPubkey", owner)
        val call =
            Request.Builder()
                .url("${endpoint.trimEnd('/')}/prediction/v1/positions/${account(positionPubkey)}")
                .delete(body.toString().toRequestBody(JSON))
                .build()
        return readClose(parse(fetch(call, null)), positionPubkey, owner)
    }

    private suspend fun fetch(request: Request, marketId: String?): String =
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
                    it.code == 404 && marketId != null ->
                        throw PredictionException(PredictionProblem.NoSuchMarket, marketId)
                    it.code == 404 -> throw PredictionException(PredictionProblem.NotFound)
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

    private fun readPosition(answer: JSONObject): PredictionPosition {
        val market = answer.optJSONObject("marketMetadata") ?: JSONObject()
        val event = answer.optJSONObject("eventMetadata") ?: JSONObject()
        return PredictionPosition(
            positionPubkey = answer.text("pubkey"),
            owner = answer.optText("ownerPubkey") ?: answer.text("owner"),
            marketId = answer.text("marketId"),
            eventId = answer.optText("eventId").orEmpty(),
            isYes = answer.flag("isYes"),
            contractsMicro = answer.baseUnits("contractsMicro"),
            totalCostUsd = answer.baseUnits("totalCostUsd"),
            valueUsd = answer.optBaseUnits("valueUsd"),
            markPriceUsd = answer.optBaseUnits("markPriceUsd"),
            sellPriceUsd = answer.optBaseUnits("sellPriceUsd"),
            avgPriceUsd = answer.optBaseUnits("avgPriceUsd"),
            pnlUsd = answer.optSigned("pnlUsd"),
            pnlUsdAfterFees = answer.optSigned("pnlUsdAfterFees"),
            feesPaidUsd = answer.optBaseUnits("feesPaidUsd") ?: 0UL,
            openOrders = answer.optInt("openOrders", 0),
            claimable = answer.optBoolean("claimable", false),
            claimed = answer.optBoolean("claimed", false),
            payoutUsd = answer.optBaseUnits("payoutUsd") ?: 0UL,
            marketStatus = market.optText("status").orEmpty(),
            marketResult = market.optText("result"),
            marketTitle = market.optText("title").orEmpty(),
            eventTitle = event.optText("title").orEmpty(),
            venue = market.optText("provider").orEmpty(),
            updatedAt = answer.optLong("updatedAt", 0L),
        )
    }

    private fun readStatus(answer: JSONObject): PredictionOrderStatus {
        val raw = answer.text("status")
        val latest = answer.optText("latestEventType").orEmpty()
        // The numbers ride on the latest event that carries any; an order that has only been
        // created carries none, and then there are none.
        val history = answer.optJSONArray("history")
        val fill =
            history?.let { events ->
                (events.length() - 1 downTo 0)
                    .asSequence()
                    .mapNotNull { events.optJSONObject(it)?.optJSONObject("fillInfo") }
                    .firstOrNull()
            }
        val finished = latest == "order_closed" || latest == "order_failed"
        return PredictionOrderStatus(
            orderPubkey = answer.text("orderPubkey"),
            fill = fillOf(raw.lowercase(), finished),
            rawStatus = raw,
            finished = finished || raw.lowercase() in FINAL,
            contractsMicro = fill?.optBaseUnits("contractsMicro"),
            filledContractsMicro = fill?.optBaseUnits("filledContractsMicro"),
            avgFillPriceUsd = fill?.optBaseUnits("avgFillPriceUsd"),
            netProceedsUsd = fill?.optBaseUnits("netProceedsUsd"),
            feeUsd = fill?.optBaseUnits("feeUsd"),
            latestSignature = answer.optText("latestSignature"),
        )
    }

    private fun readClose(answer: JSONObject, positionPubkey: String, owner: String): PredictionClose {
        val order =
            answer.optJSONObject("order")
                ?: throw PredictionException(PredictionProblem.Unusable, "no order")
        val encoded = answer.optText("transaction")
            ?: throw PredictionException(PredictionProblem.Unusable, "no transaction")
        val bytes =
            try {
                ByteString.copyFrom(Base64.getDecoder().decode(encoded))
            } catch (_: IllegalArgumentException) {
                throw PredictionException(
                    PredictionProblem.Unusable,
                    "the transaction is not base64",
                )
            }
        // As for a buy: the answer is about the question that was asked, or it is refused before
        // the bytes are read at all.
        if (order.optString("positionPubkey") != positionPubkey) {
            throw PredictionException(PredictionProblem.Unusable, "an order for another position")
        }
        if (order.optString("userPubkey").let { it.isNotEmpty() && it != owner }) {
            throw PredictionException(PredictionProblem.Unusable, "an order for another owner")
        }
        if (order.flag("isBuy")) {
            throw PredictionException(PredictionProblem.Unusable, "a close that buys")
        }
        val signers =
            answer.optJSONArray("requiredSigners")?.let { array ->
                (0 until array.length()).map(array::getString)
            } ?: emptyList()
        val context = answer.optJSONObject("execution")?.optJSONObject("context")
        return PredictionClose(
            transaction = bytes,
            orderPubkey = order.text("orderPubkey"),
            positionPubkey = positionPubkey,
            externalOrderId = order.text("externalOrderId"),
            marketId = order.text("marketId"),
            marketIdHash = order.text("marketIdHash"),
            isYes = order.flag("isYes"),
            isBuy = false,
            contractsMicro = order.baseUnits("contractsMicro"),
            newContractsMicro = order.optBaseUnits("newContractsMicro") ?: 0UL,
            minSellPriceUsd = order.baseUnits("minSellPriceUsd"),
            totalFeeUsd = order.optBaseUnits("estimatedTotalFeeUsd") ?: 0UL,
            requiredSigners = signers,
            executionModel = answer.optText("executionModel"),
            executionType = context?.optText("type"),
            gasless = answer.optBoolean("isGasless", false),
        )
    }

    private fun fillOf(raw: String, finished: Boolean): OrderFill =
        when (raw) {
            "filled" -> OrderFill.Filled
            "partiallyfilled",
            "partially_filled" ->
                if (finished) OrderFill.PartiallyFilledClosed else OrderFill.PartiallyFilled
            "created",
            "pending",
            "open" -> OrderFill.Pending
            "failed",
            "cancelled",
            "canceled",
            "expired",
            "rejected" -> OrderFill.Failed
            else -> OrderFill.Unknown
        }

    /** An address this adapter puts in a path: base58 and of an address's length, or refused. */
    private fun account(address: String): String {
        if (address.length !in 32..44 || !address.all { it in BASE58 }) {
            throw PredictionException(PredictionProblem.Unusable, "not an address")
        }
        return address
    }

    private fun JSONObject.flag(name: String): Boolean {
        if (!has(name) || isNull(name)) {
            throw PredictionException(PredictionProblem.Unusable, "no $name")
        }
        return optBoolean(name)
    }

    private fun JSONObject.optBaseUnits(name: String): ULong? =
        if (!has(name) || isNull(name)) null
        else
            optString(name).toULongOrNull()
                ?: throw PredictionException(PredictionProblem.Unusable, "$name is not base units")

    private fun JSONObject.optSigned(name: String): Long? =
        if (!has(name) || isNull(name)) null
        else
            optString(name).toLongOrNull()
                ?: throw PredictionException(PredictionProblem.Unusable, "$name is not an amount")

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
        const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val FINAL = setOf("filled", "failed", "cancelled", "canceled", "expired", "rejected")
    }
}
