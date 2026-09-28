package io.github.brrenat.seekervault.positions.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.plugins.ExecutionProviderId
import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.plugins.MarketStanding
import io.github.brrenat.seekervault.plugins.OrderFillState
import io.github.brrenat.seekervault.plugins.OrderReading
import io.github.brrenat.seekervault.plugins.PositionReading
import io.github.brrenat.seekervault.positions.HoldingRecord
import io.github.brrenat.seekervault.positions.OrderSnapshot
import io.github.brrenat.seekervault.positions.PositionPurchase
import io.github.brrenat.seekervault.positions.RefreshProblem
import io.github.brrenat.seekervault.positions.SaleRecord
import io.github.brrenat.seekervault.positions.SaleResult
import io.github.brrenat.seekervault.positions.SaleStage
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.isSolanaAddress
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The owner's positions and sale attempts (SEE-172), one JSON file each, written atomically:
 *
 * - `<dir>/holdings/<position account>.json` — a [HoldingRecord]
 * - `<dir>/sales/<sale ID>.json` — a [SaleRecord]
 * - `<dir>/cleared-at` — when the owner last cleared History, so a late writer cannot bring back
 *   what they deleted
 *
 * Public facts only: addresses, amounts, signatures, the provider's words for states. Never a URL,
 * a credential or a transaction's bytes — a sale keeps a hash of what was approved, and the
 * confirmation tracker keeps the message it follows to the chain.
 *
 * Versioned as the other stores are: fields are added as optional keys and the version is left
 * alone, so a record written by a newer build still reads here and a bump never drops a row.
 */
class PositionStore(private val dir: File) {

    private val holdings = File(dir, HOLDINGS)
    private val sales = File(dir, SALES)

    fun holdings(): List<HoldingRecord> =
        entriesOf(holdings).mapNotNull { file ->
            val account = file.name.removeSuffix(SUFFIX)
            if (!file.name.endsWith(SUFFIX) || !isSolanaAddress(account)) null else holding(account)
        }

    fun holding(account: String): HoldingRecord? {
        if (!isSolanaAddress(account)) return null
        return read(File(holdings, "$account$SUFFIX"))?.let(::decodeHolding)?.takeIf {
            it.held.account == account
        }
    }

    fun put(record: HoldingRecord) {
        require(isSolanaAddress(record.held.account)) { "not a position account" }
        write(File(holdings, "${record.held.account}$SUFFIX"), encodeHolding(record))
    }

    fun sales(): List<SaleRecord> =
        entriesOf(sales).mapNotNull { file ->
            val id = file.name.removeSuffix(SUFFIX)
            if (!file.name.endsWith(SUFFIX) || !isConnectionId(id)) null else sale(id)
        }

    fun sale(id: String): SaleRecord? {
        if (!isConnectionId(id)) return null
        return read(File(sales, "$id$SUFFIX"))?.let(::decodeSale)?.takeIf { it.id == id }
    }

    fun put(record: SaleRecord) {
        require(isConnectionId(record.id)) { "not a sale ID" }
        write(File(sales, "${record.id}$SUFFIX"), encodeSale(record))
    }

    /**
     * Forgets every position and sale, and remembers when, so nothing in flight brings one back.
     */
    fun clear(at: Instant) {
        holdings.deleteRecursively()
        sales.deleteRecursively()
        dir.mkdirs()
        write(File(dir, CLEARED), at.toString())
    }

    /** When the owner last cleared History, or null if they never have. */
    fun clearedAt(): Instant? {
        val text = read(File(dir, CLEARED)) ?: return null
        return try {
            Instant.parse(text.trim())
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun write(target: File, text: String) {
        target.parentFile?.mkdirs()
        val file = AtomicFile(target)
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun read(target: File): String? {
        val file = AtomicFile(target)
        if (!file.baseFile.exists()) return null
        return try {
            String(file.readFully(), Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    // A directory that exists and cannot be listed throws, rather than reading as "nothing held".
    private fun entriesOf(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles()?.toList() ?: throw IOException("cannot list ${directory.name}")
    }

    internal companion object {
        const val VERSION = 1
        const val OLDEST_VERSION = 1
        const val SUFFIX = ".json"
        const val HOLDINGS = "holdings"
        const val SALES = "sales"
        const val CLEARED = "cleared-at"

        fun encodeHolding(record: HoldingRecord): String =
            JSONObject()
                .put("version", VERSION)
                .put("held", encodeHeld(record.held))
                .put(
                    "purchases",
                    JSONArray().apply { record.purchases.forEach { put(encodePurchase(it)) } },
                )
                .putOpt("snapshot", record.snapshot?.let(::encodeReading))
                .putOpt("observedAt", record.observedAt?.toString())
                .putOpt("attemptedAt", record.attemptedAt?.toString())
                .putOpt("problem", record.problem?.code)
                .put(
                    "orders",
                    JSONArray().apply { record.orders.values.forEach { put(encodeOrder(it)) } },
                )
                .put("linkedAt", record.linkedAt.toString())
                .toString()

        fun decodeHolding(text: String): HoldingRecord? = decoding {
            val json = JSONObject(text)
            if (json.getInt("version") !in OLDEST_VERSION..VERSION) return@decoding null
            val purchases = json.getJSONArray("purchases")
            val orders = json.optJSONArray("orders") ?: JSONArray()
            HoldingRecord(
                held = decodeHeld(json.getJSONObject("held")) ?: return@decoding null,
                purchases =
                    (0 until purchases.length()).map {
                        decodePurchase(purchases.getJSONObject(it)) ?: return@decoding null
                    },
                snapshot =
                    json.optJSONObject("snapshot")?.let {
                        decodeReading(it) ?: return@decoding null
                    },
                observedAt = json.text("observedAt")?.let(Instant::parse),
                attemptedAt = json.text("attemptedAt")?.let(Instant::parse),
                problem = json.text("problem")?.let(RefreshProblem::of),
                orders =
                    (0 until orders.length())
                        .mapNotNull { decodeOrder(orders.getJSONObject(it)) }
                        .associateBy { it.reading.orderAccount },
                linkedAt = Instant.parse(json.getString("linkedAt")),
            )
        }

        fun encodeSale(record: SaleRecord): String =
            JSONObject()
                .put("version", VERSION)
                .put("id", record.id)
                .put("held", encodeHeld(record.held))
                .put("createdAt", record.createdAt.toString())
                .put("contractsMicro", record.contractsMicro.toString())
                .put("floorPriceMicroUsd", record.floorPriceMicroUsd.toString())
                .put("leastGrossMicroUsd", record.leastGrossMicroUsd.toString())
                .putOpt("estimatedGrossMicroUsd", record.estimatedGrossMicroUsd?.toString())
                .put("estimatedFeeMicroUsd", record.estimatedFeeMicroUsd.toString())
                .put("proceedsSymbol", record.proceedsSymbol)
                .put("proceedsDecimals", record.proceedsDecimals)
                .put("proceedsAccount", record.proceedsAccount)
                .put("orderAccount", record.orderAccount)
                .put("contentHash", record.contentHash)
                .put("stage", record.stage.code)
                .putOpt("signature", record.signature)
                .putOpt("settledAt", record.settledAt?.toString())
                .putOpt("detail", record.detail)
                .putOpt("order", record.order?.let(::encodeOrder))
                .put("result", record.result.code)
                .putOpt("resolvedAt", record.resolvedAt?.toString())
                .putOpt("blockhash", record.blockhash)
                .toString()

        fun decodeSale(text: String): SaleRecord? = decoding {
            val json = JSONObject(text)
            if (json.getInt("version") !in OLDEST_VERSION..VERSION) return@decoding null
            SaleRecord(
                id = json.getString("id"),
                held = decodeHeld(json.getJSONObject("held")) ?: return@decoding null,
                createdAt = Instant.parse(json.getString("createdAt")),
                contractsMicro = json.getString("contractsMicro").toULong(),
                floorPriceMicroUsd = json.getString("floorPriceMicroUsd").toULong(),
                leastGrossMicroUsd = json.getString("leastGrossMicroUsd").toULong(),
                estimatedGrossMicroUsd = json.text("estimatedGrossMicroUsd")?.toULong(),
                estimatedFeeMicroUsd = json.getString("estimatedFeeMicroUsd").toULong(),
                proceedsSymbol = json.getString("proceedsSymbol"),
                proceedsDecimals = json.getInt("proceedsDecimals"),
                proceedsAccount = json.getString("proceedsAccount"),
                orderAccount = json.getString("orderAccount"),
                contentHash = json.getString("contentHash"),
                // A stage or result this build has no name for is not guessed at: the record
                // is unreadable here rather than read as something it is not.
                stage = SaleStage.of(json.getString("stage")) ?: return@decoding null,
                signature = json.text("signature"),
                settledAt = json.text("settledAt")?.let(Instant::parse),
                detail = json.text("detail"),
                order = json.optJSONObject("order")?.let(::decodeOrder),
                result = SaleResult.of(json.getString("result")) ?: return@decoding null,
                resolvedAt = json.text("resolvedAt")?.let(Instant::parse),
                blockhash = json.text("blockhash"),
            )
        }

        private fun encodeHeld(held: HeldPosition): JSONObject =
            JSONObject()
                .put("provider", held.provider.value)
                .put("owner", held.owner)
                .put("network", held.network.number)
                .put("account", held.account)
                .put("marketId", held.marketId)
                .put("yes", held.yes)

        private fun decodeHeld(json: JSONObject): HeldPosition? {
            val network = Network.forNumber(json.getInt("network")) ?: return null
            if (network == Network.UNRECOGNIZED) return null
            val owner = json.getString("owner")
            val account = json.getString("account")
            if (!isSolanaAddress(owner) || !isSolanaAddress(account)) return null
            return HeldPosition(
                provider = ExecutionProviderId(json.getString("provider")),
                owner = owner,
                network = network,
                account = account,
                marketId = json.getString("marketId"),
                yes = json.getBoolean("yes"),
            )
        }

        private fun encodePurchase(purchase: PositionPurchase): JSONObject =
            JSONObject()
                .put("connectionId", purchase.connectionId)
                .put("proposalId", purchase.proposalId)
                .putOpt("orderAccount", purchase.orderAccount)
                .putOpt("signature", purchase.signature)
                .put("boughtAt", purchase.boughtAt.toString())
                .putOpt("deposit", purchase.depositBaseUnits?.toString())

        private fun decodePurchase(json: JSONObject): PositionPurchase? {
            val connection = json.getString("connectionId")
            if (!isConnectionId(connection)) return null
            return PositionPurchase(
                connectionId = connection,
                proposalId = json.getString("proposalId"),
                orderAccount = json.text("orderAccount"),
                signature = json.text("signature"),
                boughtAt = Instant.parse(json.getString("boughtAt")),
                depositBaseUnits = json.text("deposit")?.toULong(),
            )
        }

        private fun encodeReading(reading: PositionReading): JSONObject =
            JSONObject()
                .put("account", reading.account)
                .put("owner", reading.owner)
                .put("marketId", reading.marketId)
                .put("eventId", reading.eventId)
                .put("yes", reading.yes)
                .put("contractsMicro", reading.contractsMicro.toString())
                .put("costMicroUsd", reading.costMicroUsd.toString())
                .putOpt("valueMicroUsd", reading.valueMicroUsd?.toString())
                .putOpt("markPriceMicroUsd", reading.markPriceMicroUsd?.toString())
                .putOpt("sellPriceMicroUsd", reading.sellPriceMicroUsd?.toString())
                .putOpt("averagePriceMicroUsd", reading.averagePriceMicroUsd?.toString())
                .putOpt("pnlMicroUsd", reading.pnlMicroUsd?.toString())
                .putOpt("pnlAfterFeesMicroUsd", reading.pnlAfterFeesMicroUsd?.toString())
                .put("feesPaidMicroUsd", reading.feesPaidMicroUsd.toString())
                .put("openOrders", reading.openOrders)
                .put("market", reading.market.code)
                .put("marketStatus", reading.marketStatus)
                .putOpt("marketResult", reading.marketResult)
                .put("claimable", reading.claimable)
                .put("claimed", reading.claimed)
                .put("payoutMicroUsd", reading.payoutMicroUsd.toString())
                .put("marketTitle", reading.marketTitle)
                .put("eventTitle", reading.eventTitle)
                .put("venue", reading.venue)
                .put("updatedAt", reading.updatedAtEpochSeconds)

        private fun decodeReading(json: JSONObject): PositionReading? =
            PositionReading(
                account = json.getString("account"),
                owner = json.getString("owner"),
                marketId = json.getString("marketId"),
                eventId = json.optString("eventId"),
                yes = json.getBoolean("yes"),
                contractsMicro = json.getString("contractsMicro").toULong(),
                costMicroUsd = json.getString("costMicroUsd").toULong(),
                valueMicroUsd = json.text("valueMicroUsd")?.toULong(),
                markPriceMicroUsd = json.text("markPriceMicroUsd")?.toULong(),
                sellPriceMicroUsd = json.text("sellPriceMicroUsd")?.toULong(),
                averagePriceMicroUsd = json.text("averagePriceMicroUsd")?.toULong(),
                pnlMicroUsd = json.text("pnlMicroUsd")?.toLong(),
                pnlAfterFeesMicroUsd = json.text("pnlAfterFeesMicroUsd")?.toLong(),
                feesPaidMicroUsd = json.getString("feesPaidMicroUsd").toULong(),
                openOrders = json.getInt("openOrders"),
                // A state this build does not know is Unknown, which is never read as open.
                market = MarketStanding.of(json.getString("market")) ?: MarketStanding.Unknown,
                marketStatus = json.optString("marketStatus"),
                marketResult = json.text("marketResult"),
                claimable = json.getBoolean("claimable"),
                claimed = json.getBoolean("claimed"),
                payoutMicroUsd = json.getString("payoutMicroUsd").toULong(),
                marketTitle = json.optString("marketTitle"),
                eventTitle = json.optString("eventTitle"),
                venue = json.optString("venue"),
                updatedAtEpochSeconds = json.optLong("updatedAt", 0L),
            )

        private fun encodeOrder(snapshot: OrderSnapshot): JSONObject =
            snapshot.reading.let { order ->
                JSONObject()
                    .put("orderAccount", order.orderAccount)
                    .put("fill", order.fill.code)
                    .put("raw", order.raw)
                    .putOpt("contractsMicro", order.contractsMicro?.toString())
                    .putOpt("filledContractsMicro", order.filledContractsMicro?.toString())
                    .putOpt("averageFillPriceMicroUsd", order.averageFillPriceMicroUsd?.toString())
                    .putOpt("netProceedsMicroUsd", order.netProceedsMicroUsd?.toString())
                    .putOpt("feeMicroUsd", order.feeMicroUsd?.toString())
                    .putOpt("latestSignature", order.latestSignature)
                    .put("observedAt", snapshot.observedAt.toString())
            }

        private fun decodeOrder(json: JSONObject): OrderSnapshot? =
            try {
                OrderSnapshot(
                    OrderReading(
                        orderAccount = json.getString("orderAccount"),
                        fill = OrderFillState.of(json.getString("fill")) ?: OrderFillState.Unknown,
                        raw = json.optString("raw"),
                        contractsMicro = json.text("contractsMicro")?.toULong(),
                        filledContractsMicro = json.text("filledContractsMicro")?.toULong(),
                        averageFillPriceMicroUsd = json.text("averageFillPriceMicroUsd")?.toULong(),
                        netProceedsMicroUsd = json.text("netProceedsMicroUsd")?.toULong(),
                        feeMicroUsd = json.text("feeMicroUsd")?.toULong(),
                        latestSignature = json.text("latestSignature"),
                    ),
                    Instant.parse(json.getString("observedAt")),
                )
            } catch (_: JSONException) {
                null
            } catch (_: DateTimeException) {
                null
            } catch (_: NumberFormatException) {
                null
            }

        private inline fun <T> decoding(block: () -> T?): T? =
            try {
                block()
            } catch (_: JSONException) {
                null
            } catch (_: DateTimeException) {
                null
            } catch (_: NumberFormatException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        // `optString` reads an explicit JSON null as "null" (SEE-94's lesson).
        private fun JSONObject.text(name: String): String? =
            if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotEmpty)
    }
}
