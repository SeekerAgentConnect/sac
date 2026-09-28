package io.github.brrenat.seekervault.plugins

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.SelectedWallet

/**
 * What a provider can say and do about a position the owner already holds (SEE-172).
 *
 * The rest of the boundary is about an operation a publisher proposed: `prepare` builds it and
 * `inspect` reads it. A position is different in the one way that matters — **nobody proposed
 * anything**. The owner opened a History item, looked at what they hold, and asked to sell it. So
 * this is a separate, optional part of a provider rather than another payload: a publisher cannot
 * name `prediction.sell`, and a signal can never become a sale.
 *
 * Everything here is data and pure types. The provider reads its own API and the chain; core
 * decides whether a sale is eligible, stores what happened, drives the wallet and follows the
 * chain.
 */
interface PositionManagement {
    /** The action a sale is, and where the provider serves it. */
    val sale: ActionCapability

    /** The position as the provider holds it now. Never throws for "not found". */
    suspend fun position(held: HeldPosition): PositionRead

    /** What one order on that position came to, as far as the provider can say. */
    suspend fun order(held: HeldPosition, orderAccount: String): OrderRead

    /**
     * Builds and reads the transaction that would sell the **whole** of [held] for [wallet].
     *
     * Throws [PluginFailure] with the reason when nothing can be offered — a wallet that is not the
     * position's owner, a market that no longer trades, a transaction that could not be resolved. A
     * returned sale has always been read; whether it is approvable is its verdict.
     */
    suspend fun prepareSale(held: HeldPosition, wallet: SelectedWallet?): PreparedSale

    /** Where the owner continues on the provider's own platform. */
    fun destinations(held: HeldPosition): List<PluginDestination>
}

/**
 * One position this phone knows a purchase of, bound for good: which provider, whose, on which
 * network, which market, which side, which account.
 *
 * A wallet or network switch never retargets it. Reading stays about [owner]; signing requires that
 * owner, on [network].
 */
data class HeldPosition(
    val provider: ExecutionProviderId,
    val owner: String,
    val network: Network,
    /** The position's own account, read out of the purchase's bytes. */
    val account: String,
    val marketId: String,
    val yes: Boolean,
)

/**
 * Where a market stands, in the provider's words mapped onto the few that change what is offered.
 */
enum class MarketStanding(val code: String) {
    Open("open"),
    /** No longer trading and not yet settled. */
    Closed("closed"),
    /** Settled on an outcome. */
    Settled("settled"),
    Cancelled("cancelled"),
    /** A state this build has no name for. Never read as open. */
    Unknown("unknown");

    companion object {
        fun of(code: String): MarketStanding? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A position as the provider reported it, with the time it was observed.
 *
 * Amounts are in millionths — of a contract, or of a dollar — as the provider sends them. A value
 * the provider does not quote (a closed market has no mark) stays null: a value nobody quoted is
 * not a value of nothing.
 */
data class PositionReading(
    val account: String,
    val owner: String,
    val marketId: String,
    val eventId: String,
    val yes: Boolean,
    val contractsMicro: ULong,
    val costMicroUsd: ULong,
    val valueMicroUsd: ULong?,
    val markPriceMicroUsd: ULong?,
    /** The best price a contract of this side could be sold at now, or null when nobody bids. */
    val sellPriceMicroUsd: ULong?,
    val averagePriceMicroUsd: ULong?,
    val pnlMicroUsd: Long?,
    val pnlAfterFeesMicroUsd: Long?,
    val feesPaidMicroUsd: ULong,
    /** Orders on the position the provider has not finished with. */
    val openOrders: Int,
    val market: MarketStanding,
    /** The provider's own spelling of the market's state, kept for display. */
    val marketStatus: String,
    val marketResult: String?,
    val claimable: Boolean,
    val claimed: Boolean,
    val payoutMicroUsd: ULong,
    val marketTitle: String,
    val eventTitle: String,
    /** The market's venue — Polymarket, Kalshi — which is not the execution provider. */
    val venue: String,
    val updatedAtEpochSeconds: Long,
)

/** Why a read produced nothing usable. None of these is an answer about the position. */
enum class ReadProblem(val code: String) {
    Unreachable("unreachable"),
    RateLimited("rate_limited"),
    Refused("refused"),
    Unusable("unusable"),
}

sealed interface PositionRead {
    data class Found(val position: PositionReading) : PositionRead

    /**
     * The provider has no record of it. Not sold, not lost, not zero: a fill the indexer has not
     * caught up with looks exactly like this, and so does a position closed long ago.
     */
    data object NotFound : PositionRead

    data class Failed(val problem: ReadProblem, val detail: String? = null) : PositionRead
}

/** What an order came to. Only [Filled] and the two partial states are evidence of a fill. */
enum class OrderFillState(val code: String) {
    Pending("pending"),
    PartiallyFilled("partially_filled"),
    Filled("filled"),
    /** Finished with part of it filled and the rest returned. */
    PartiallyFilledClosed("partially_filled_closed"),
    Failed("failed"),
    Unknown("unknown");

    /** Whether the provider has finished with the order. */
    val finished: Boolean
        get() = this == Filled || this == PartiallyFilledClosed || this == Failed

    companion object {
        fun of(code: String): OrderFillState? = entries.firstOrNull { it.code == code }
    }
}

data class OrderReading(
    val orderAccount: String,
    val fill: OrderFillState,
    /** The provider's own word for the state, kept for the record. */
    val raw: String,
    val contractsMicro: ULong?,
    val filledContractsMicro: ULong?,
    val averageFillPriceMicroUsd: ULong?,
    /** What a sale paid out after fees, when the provider states it. */
    val netProceedsMicroUsd: ULong?,
    val feeMicroUsd: ULong?,
    val latestSignature: String?,
)

sealed interface OrderRead {
    data class Found(val order: OrderReading) : OrderRead

    /** No record yet: an order the indexer has not seen. Not a failure of the order. */
    data object NotFound : OrderRead

    data class Failed(val problem: ReadProblem, val detail: String? = null) : OrderRead
}

/**
 * A sale, built and read.
 *
 * [inspection] is the reading of [transaction]'s own bytes — its verdict decides whether it can be
 * approved. [terms] are the numbers the review shows, every one of them out of the bytes or
 * labelled as the provider's estimate.
 */
data class PreparedSale(
    val transaction: ByteString,
    val version: Int,
    val expiresAtEpochSeconds: Long,
    val inspection: ActionInspection,
    val terms: SaleTerms,
    /** The position as it was read immediately before the sale was built. */
    val position: PositionReading,
)

/** What the owner is asked to approve. */
data class SaleTerms(
    val position: String,
    val owner: String,
    val marketId: String,
    val yes: Boolean,
    /** Contracts the transaction sells, read from its bytes. */
    val contractsMicro: ULong,
    /** Contracts the position held when it was read just now. A whole sale sells exactly this. */
    val heldContractsMicro: ULong,
    /** The least one contract may fetch: the floor the program enforces. From the bytes. */
    val floorPriceMicroUsd: ULong,
    /** Contracts times the floor: the least the fill can gross before the venue's fee. */
    val leastGrossMicroUsd: ULong,
    /** Contracts times the provider's current bid. An estimate, and labelled as one. */
    val estimatedGrossMicroUsd: ULong?,
    /** The provider's estimate of its and the venue's fees. Not in the bytes. */
    val estimatedFeeMicroUsd: ULong,
    /** The token the proceeds are paid in, and the owner's account they land in. */
    val proceedsMint: String,
    val proceedsSymbol: String,
    val proceedsDecimals: Int,
    val proceedsAccount: String,
    /** The sale's own order account, which its record keeps and its fill is read by. */
    val orderAccount: String,
    /** The account that pays the network fee when it is not the owner (a gasless build). */
    val sponsor: String?,
    /** The priority fee the owner pays, in lamports, when they pay it. */
    val networkFeeLamports: ULong?,
)

/**
 * Why a position, as last read, cannot be sold (SEE-172). The History item shows the reason, and
 * the provider checks the same rule again on a fresh read before it builds anything.
 */
enum class SaleBlock(val code: String) {
    /** The market settled: a settled position is claimed, not sold. */
    Settled("settled"),
    /** The market no longer trades. */
    MarketClosed("market_closed"),
    NothingHeld("nothing_held"),
    /** An order on the position is still open, so what would be sold is still changing. */
    OpenOrders("open_orders"),
    /** Nobody bids for this side now, so there is no price to sell at. */
    NoBid("no_bid"),
}

/**
 * The one eligibility rule, over what the provider reported. Null when the position can be sold.
 */
fun saleBlockOf(position: PositionReading): SaleBlock? =
    when {
        position.claimable || position.market == MarketStanding.Settled -> SaleBlock.Settled
        position.market != MarketStanding.Open -> SaleBlock.MarketClosed
        position.contractsMicro == 0UL -> SaleBlock.NothingHeld
        position.openOrders > 0 -> SaleBlock.OpenOrders
        position.sellPriceMicroUsd == null || position.sellPriceMicroUsd == 0UL -> SaleBlock.NoBid
        else -> null
    }
