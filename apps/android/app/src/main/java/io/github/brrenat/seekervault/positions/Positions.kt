package io.github.brrenat.seekervault.positions

import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.ChainState
import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.plugins.OrderFillState
import io.github.brrenat.seekervault.plugins.OrderRead
import io.github.brrenat.seekervault.plugins.OrderReading
import io.github.brrenat.seekervault.plugins.PositionRead
import io.github.brrenat.seekervault.plugins.PositionReading
import java.time.Duration
import java.time.Instant

/**
 * The owner's prediction positions, and what they did with them, as this phone records it (SEE-172,
 * docs/wiki/prediction-positions.md).
 *
 * Four things are kept apart on purpose, because they are four different facts:
 *
 * 1. **The purchase** — the owner's decision on a signal, its binding and its signature. It lives
 *    in the proposal and Activity records and is never rewritten here; this package keeps only a
 *    link to it ([PositionPurchase]).
 * 2. **Each order's fill**, as the provider reports it ([OrderSnapshot]). A chain-confirmed
 *    transaction is not a fill.
 * 3. **The position now** — the wallet's whole holding of that side of that market, however many
 *    purchases, here or elsewhere, went into it ([HoldingRecord.snapshot]), with when it was seen
 *    and whether the last attempt to see it failed.
 * 4. **Each sale attempt**, its own operation with its own review and its own signature
 *    ([SaleRecord]). A sale never reopens, replays or rewrites the purchase.
 */

/** One purchase that went into a position: which record, which order, which signature, when. */
data class PositionPurchase(
    val connectionId: String,
    val proposalId: String,
    /** The order's own account, read from the purchase's bytes. Null for a record without one. */
    val orderAccount: String?,
    /** The purchase transaction's signature, base58. */
    val signature: String?,
    val boughtAt: Instant,
    /** What the owner staked, as the provider's deposit token's base units. */
    val depositBaseUnits: ULong?,
)

/** One order's fill, as the provider reported it, and when. */
data class OrderSnapshot(val reading: OrderReading, val observedAt: Instant)

/** Why the last attempt to read a position failed. The last good snapshot is kept regardless. */
enum class RefreshProblem(val code: String) {
    Unreachable("unreachable"),
    RateLimited("rate_limited"),
    Refused("refused"),
    Unusable("unusable"),
    /** The provider has no record of it: not indexed yet, or long closed. Never "zero". */
    NotFound("not_found"),
    /** This build has no provider that manages positions for this record. */
    Unsupported("unsupported");

    companion object {
        fun of(code: String): RefreshProblem? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One position, bound for good, with every purchase that went into it and what was last seen.
 *
 * [snapshot] is replaced only by a newer successful read; a failed read sets [problem] and leaves
 * the last good one — marked stale by its age — rather than inventing a zero.
 */
data class HoldingRecord(
    val held: HeldPosition,
    val purchases: List<PositionPurchase>,
    val snapshot: PositionReading? = null,
    val observedAt: Instant? = null,
    val attemptedAt: Instant? = null,
    val problem: RefreshProblem? = null,
    /** Buy orders' fills, by the order's own account. */
    val orders: Map<String, OrderSnapshot> = emptyMap(),
    val linkedAt: Instant,
) {
    /** Whether any purchase's order is still waiting on the provider. */
    val ordersUnresolved: Boolean
        get() = purchases.any { purchase ->
            val order = purchase.orderAccount ?: return@any false
            orders[order]?.reading?.fill?.finished != true
        }
}

/** Where a sale attempt got to with the wallet. */
enum class SaleStage(val code: String) {
    /** Written down before the wallet was opened. Still in hand, or the app stopped mid-way. */
    Signing("signing"),
    /** The wallet signed and sent it, and returned a signature. */
    Submitted("submitted"),
    /** The owner declined in the wallet. Nothing was sent. */
    Declined("declined"),
    /** The wallet refused before anything could be sent. */
    Failed("failed"),
    /** It may or may not have been sent. Reconciled by reading, never by sending again. */
    Unresolved("unresolved");

    companion object {
        fun of(code: String): SaleStage? = entries.firstOrNull { it.code == code }
    }
}

/** What a sale came to, as far as the evidence goes. */
enum class SaleResult(val code: String) {
    /** Not known yet: the order is open, unindexed, or the transaction unconfirmed. */
    Pending("pending"),
    /** The order filled and the position holds nothing more. */
    Closed("closed"),
    /** The order finished and contracts remain in the position. */
    Residual("residual"),
    /** Nothing was sold: declined, refused, failed on chain, or the order failed. */
    NotExecuted("not_executed"),
    /**
     * No order from it ever reached the provider, and its transaction can no longer land. Only
     * reached for an attempt whose outcome the wallet never reported.
     */
    Lapsed("lapsed");

    val final: Boolean
        get() = this != Pending

    companion object {
        fun of(code: String): SaleResult? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One sale attempt, immutable in what was reviewed and approved; only its outcome moves on.
 *
 * Everything reviewed is kept as the review showed it — the contracts, the floor, the estimates —
 * so the record says what the owner approved even after the position is gone.
 */
data class SaleRecord(
    /** A lowercase UUID, and the sale's key for chain tracking. */
    val id: String,
    val held: HeldPosition,
    val createdAt: Instant,
    val contractsMicro: ULong,
    val floorPriceMicroUsd: ULong,
    val leastGrossMicroUsd: ULong,
    val estimatedGrossMicroUsd: ULong?,
    val estimatedFeeMicroUsd: ULong,
    val proceedsSymbol: String,
    val proceedsDecimals: Int,
    val proceedsAccount: String,
    /** The sale's own order account, from its bytes: how its fill is read. */
    val orderAccount: String,
    /** A SHA-256 of the exact bytes approved, hex. */
    val contentHash: String,
    val stage: SaleStage,
    val signature: String? = null,
    val settledAt: Instant? = null,
    val detail: String? = null,
    val order: OrderSnapshot? = null,
    val result: SaleResult = SaleResult.Pending,
    val resolvedAt: Instant? = null,
) {
    /** Whether this attempt still stands in the way of another. */
    val inFlight: Boolean
        get() = !result.final
}

/** How long an attempt whose wallet answer never came waits for evidence before it is lapsed. */
val SALE_LAPSE: Duration = Duration.ofMinutes(5)

/**
 * What [sale] has come to, given what was just read — or [sale] unchanged when nothing new is
 * established. Pure: it reads, it never builds, signs or sends anything.
 *
 * - A filled order is a sale; the position read beside it says whether anything remains.
 * - A sale is never called closed on a chain confirmation alone, nor on a position that 404s.
 * - An attempt the wallet never reported on stays pending until an order appears, the chain says it
 *   failed, or — with no order ever recorded — [SALE_LAPSE] has passed.
 */
fun settleSale(
    sale: SaleRecord,
    chain: ChainCheck?,
    order: OrderRead?,
    position: PositionRead?,
    now: Instant,
): SaleRecord {
    if (sale.result.final) return sale
    val observed = (order as? OrderRead.Found)?.order?.let { OrderSnapshot(it, now) }
    val withOrder = if (observed != null) sale.copy(order = observed) else sale
    fun done(result: SaleResult, detail: String? = sale.detail) =
        withOrder.copy(result = result, resolvedAt = now, detail = detail)
    return when (sale.stage) {
        SaleStage.Declined,
        SaleStage.Failed -> done(SaleResult.NotExecuted)
        SaleStage.Signing -> withOrder
        SaleStage.Submitted,
        SaleStage.Unresolved -> {
            val fill = observed?.reading?.fill
            val remaining = (position as? PositionRead.Found)?.position?.contractsMicro
            when {
                fill == OrderFillState.Filled ->
                    if (remaining != null && remaining > 0UL) done(SaleResult.Residual)
                    else done(SaleResult.Closed)
                fill == OrderFillState.PartiallyFilledClosed -> done(SaleResult.Residual)
                fill == OrderFillState.Failed -> done(SaleResult.NotExecuted, FAILED_ORDER)
                fill != null -> withOrder
                chain?.state == ChainState.Failed -> done(SaleResult.NotExecuted, FAILED_ON_CHAIN)
                chain?.state == ChainState.Expired -> done(SaleResult.NotExecuted, EXPIRED)
                sale.stage == SaleStage.Unresolved &&
                    sale.signature == null &&
                    order is OrderRead.NotFound &&
                    !now.isBefore(sale.createdAt.plus(SALE_LAPSE)) ->
                    done(SaleResult.Lapsed, LAPSED)
                else -> withOrder
            }
        }
    }
}

internal const val FAILED_ORDER = "The provider reported the sale order as failed."
internal const val FAILED_ON_CHAIN = "The sale's transaction failed on chain."
internal const val EXPIRED = "The sale's transaction expired before it landed."
internal const val LAPSED =
    "No order from this sale reached the provider, and its transaction can no longer land."

/** Whether a holding's snapshot is too old to present as current. */
fun HoldingRecord.stale(now: Instant): Boolean =
    observedAt == null || Duration.between(observedAt, now) > STALE_AFTER

/** A snapshot older than this is shown as last known rather than as now. */
val STALE_AFTER: Duration = Duration.ofMinutes(2)
