package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionCapability
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.plugins.InspectedAction
import io.github.brrenat.seekervault.plugins.MarketStanding
import io.github.brrenat.seekervault.plugins.OrderFillState
import io.github.brrenat.seekervault.plugins.OrderRead
import io.github.brrenat.seekervault.plugins.OrderReading
import io.github.brrenat.seekervault.plugins.PREDICTION_SELL_ACTION
import io.github.brrenat.seekervault.plugins.PREDICTION_SELL_SCHEMA_VERSION
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.PluginFact
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.PositionManagement
import io.github.brrenat.seekervault.plugins.PositionRead
import io.github.brrenat.seekervault.plugins.PositionReading
import io.github.brrenat.seekervault.plugins.PreparedSale
import io.github.brrenat.seekervault.plugins.ReadProblem
import io.github.brrenat.seekervault.plugins.SaleBlock
import io.github.brrenat.seekervault.plugins.SaleTerms
import io.github.brrenat.seekervault.plugins.saleBlockOf
import io.github.brrenat.seekervault.solana.LookupException
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.resolveLookups
import io.github.brrenat.seekervault.transactions.DecodeFailure
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.transactions.decodeTransaction
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Selling a prediction position the owner holds, as Jupiter serves it (SEE-172).
 *
 * ## What a sale is on the wire
 *
 * The same instruction as a buy — the prediction program's place-order — with its direction flag
 * clear. Its price field is then the **floor**: the least one contract may be sold for, below which
 * the keeper cannot fill it. That floor is the one price protection the bytes enforce; the
 * `slippageBps` Jupiter's JSON reports beside a sale is a quote signal and is not in the
 * instruction (captured 2026-09-28, `fixtures/jupiter/positions.json`). A sale spends nothing, so
 * the order's cost is zero, and its proceeds land in the owner's own account for Jupiter's dollar
 * token.
 *
 * ## What the review establishes before anything is shown
 *
 * 1. The bytes decode whole and their lookup tables resolve from the chain.
 * 2. The only signature still missing is the owner's, and a fee payer that is not the owner has
 *    already signed (a gasless build) — [checkSigners], the same rule a buy is held to.
 * 3. Exactly one order instruction; it **sells**, the side the position holds, of the position the
 *    owner opened this from, for the owner.
 * 4. It sells exactly the contracts the position held when it was read a moment ago — all of them,
 *    and never more — and leaves nothing behind in the provider's own account of the order.
 * 5. Its floor is the one the provider stated, and no lower than [MOST_FLOOR_DISCOUNT_BPS] under
 *    the best current bid: a floor that would let the sale go for much less than the market pays
 *    now is weakened protection and is refused.
 * 6. The proceeds go to the owner's own account for the provider's token.
 * 7. Every other instruction is a compute-budget setting or the creation of that very account; no
 *    route, no transfer, no wrap, nothing unread. A priority fee the owner pays is bounded.
 * 8. The provider's estimate of its fee is less than the least the sale can gross.
 *
 * Anything failing means the review is not approvable. Nothing here signs, stores or sends.
 */

/** How far under the best current bid a sale's floor may sit: Jupiter's own 25 %. */
const val MOST_FLOOR_DISCOUNT_BPS: Int = 2_500

/**
 * The most priority fee a sale may ask the owner to pay when they pay the network fee: 0.005 SOL.
 */
const val MOST_SALE_PRIORITY_LAMPORTS: ULong = 5_000_000UL

private const val BPS: ULong = 10_000UL

/** A contract's millionths, and the dollar token's. Both are six decimals. */
private const val MICRO: ULong = 1_000_000UL

/**
 * `prediction.sell`, as Jupiter serves it: mainnet, and only positions it can close keeper-side.
 */
val PREDICTION_SELL_CAPABILITY: ActionCapability =
    ActionCapability(
        action = PREDICTION_SELL_ACTION,
        schemaVersions = PREDICTION_SELL_SCHEMA_VERSION..PREDICTION_SELL_SCHEMA_VERSION,
        networks = setOf(SWAP_NETWORK),
    )

/** What reading a sale produced: the owner's review, and the terms it is about. */
class SaleRead(val inspection: ActionInspection, val terms: SaleTerms)

/**
 * Reads [close]'s bytes and checks them against [held] — the position the owner's record names —
 * and [position], what the provider said about it a moment ago, for [wallet].
 *
 * Throws [SolanaException] or [LookupException] when the accounts could not be resolved: then there
 * is nothing to review, and the caller refuses to prepare.
 */
suspend fun inspectPredictionSale(
    held: HeldPosition,
    position: PredictionPosition,
    close: PredictionClose,
    wallet: SelectedWallet?,
    version: Int,
    chain: SolanaAccounts,
): SaleRead {
    val findings = mutableListOf<PredictionFinding>()
    val owner = wallet?.address
    val proceeds = associatedTokenAddress(held.owner, JUP_USD_MINT).orEmpty()
    fun terms(contracts: ULong, floor: ULong, order: String, sponsor: String?, fee: ULong?) =
        SaleTerms(
            position = held.account,
            owner = held.owner,
            marketId = held.marketId,
            yes = held.yes,
            contractsMicro = contracts,
            heldContractsMicro = position.contractsMicro,
            floorPriceMicroUsd = floor,
            leastGrossMicroUsd = contracts * floor / MICRO,
            estimatedGrossMicroUsd = position.sellPriceUsd?.let { contracts * it / MICRO },
            estimatedFeeMicroUsd = close.totalFeeUsd,
            proceedsMint = JUP_USD_MINT,
            proceedsSymbol = PROCEEDS_SYMBOL,
            proceedsDecimals = PROCEEDS_DECIMALS,
            proceedsAccount = proceeds,
            orderAccount = order,
            sponsor = sponsor,
            networkFeeLamports = fee,
        )
    fun nothing(): SaleRead =
        SaleRead(
            ActionInspection.nothingEstablished(
                version,
                findings.distinct().map { it.finding() },
            ),
            terms(0UL, 0UL, close.orderPubkey, null, null),
        )

    val decoded =
        when (val result = decodeTransaction(close.transaction.toByteArray(), resolvable = true)) {
            is DecodeResult.Decoded -> result.transaction
            is DecodeResult.Failed -> {
                findings +=
                    if (result.failure == DecodeFailure.UnsupportedVersion) {
                        PredictionFinding.UnsupportedVersion
                    } else {
                        PredictionFinding.Malformed
                    }
                return nothing()
            }
        }
    val resolved = resolveLookups(decoded, chain)

    if (wallet == null) findings += PredictionFinding.NoWallet
    else if (wallet.network.network != SWAP_NETWORK) findings += PredictionFinding.OtherNetwork
    // A wallet switch never retargets a position: the one who may sell it is the one who holds it.
    if (owner != null && owner != held.owner) findings += PredictionFinding.NotTheOwnersOrder
    val sponsor = checkSigners(decoded, owner, findings)

    val read = decoded.instructions.map { resolved.readOrderStep(it) }
    if (read.any { it == null }) {
        findings += PredictionFinding.Malformed
        return nothing()
    }
    val steps = read.filterNotNull()
    for (step in steps.filterIsInstance<OrderStep.Unread>()) {
        findings +=
            if (step.program in ORDER_PROGRAMS) PredictionFinding.UnreadableValueInstruction
            else PredictionFinding.UnrecognizedInstruction
    }
    val orders = steps.filterIsInstance<OrderStep.Order>()
    if (orders.size > 1) findings += PredictionFinding.ExtraOrder
    val placed = orders.firstOrNull()
    if (placed == null) {
        findings += PredictionFinding.NoOrder
        return nothing()
    }

    // The order: a sale, of this side, of this position, the owner's.
    if (placed.buying) findings += PredictionFinding.NotSelling
    if (placed.yes != held.yes || close.isYes != held.yes || position.isYes != held.yes) {
        findings += PredictionFinding.OutcomeMismatch
    }
    if (
        owner == null ||
            placed.owner != owner ||
            placed.owner != held.owner ||
            placed.payer !in setOfNotNull(owner, sponsor)
    ) {
        findings += PredictionFinding.NotTheOwnersOrder
    }
    if (placed.position != held.account || close.positionPubkey != held.account) {
        findings += PredictionFinding.PositionMismatch
    }
    if (close.marketId != held.marketId || placed.marketHash != close.marketIdHash) {
        findings += PredictionFinding.MarketMismatch
    }
    if (placed.order != close.orderPubkey || placed.externalOrderId != close.externalOrderId) {
        findings += PredictionFinding.OrderMismatch
    }
    // The whole position and nothing more: what the bytes sell is what the provider said, what the
    // position held a moment ago, and what the review shows. A sale of fewer contracts would leave
    // a residue the owner did not choose; a sale of more is not possible and is not reviewed.
    if (
        placed.contractsMicro == 0UL ||
            placed.contractsMicro != close.contractsMicro ||
            placed.contractsMicro != position.contractsMicro ||
            close.newContractsMicro != 0UL
    ) {
        findings += PredictionFinding.QuantityMismatch
    }
    // A sale costs the owner nothing, and its price field is the floor the provider stated.
    if (placed.cost != 0UL || placed.maxPrice != close.minSellPriceUsd) {
        findings += PredictionFinding.QuoteMismatch
    }
    val bid = position.sellPriceUsd
    if (
        placed.maxPrice == 0UL ||
            bid == null ||
            placed.maxPrice * BPS < bid * (BPS - MOST_FLOOR_DISCOUNT_BPS.toULong()) ||
            placed.slippageBps > MOST_FLOOR_DISCOUNT_BPS ||
            (placed.maxSlippageBps ?: 0) > MOST_FLOOR_DISCOUNT_BPS
    ) {
        findings += PredictionFinding.WeakFloor
    }
    if (placed.mint != JUP_USD_MINT) findings += PredictionFinding.MintMismatch
    if (placed.funding != proceeds) findings += PredictionFinding.ProceedsNotOwners

    // Everything else: fee settings and the proceeds account, and nothing that moves value.
    var networkFee: ULong? = null
    val limit = steps.mapNotNull {
        ((it as? OrderStep.Funding)?.step as? SwapStep.Budget)?.unitLimit
    }
    val price = steps.mapNotNull {
        ((it as? OrderStep.Funding)?.step as? SwapStep.Budget)?.microLamportsPerUnit
    }
    if (sponsor == null && limit.isNotEmpty() && price.isNotEmpty()) {
        networkFee = limit.first().toULong() * price.first() / MICRO
        if (networkFee > MOST_SALE_PRIORITY_LAMPORTS) findings += PredictionFinding.ExcessiveFee
    }
    for (step in steps) {
        val funding = (step as? OrderStep.Funding)?.step ?: continue
        when (funding) {
            is SwapStep.Budget -> Unit
            is SwapStep.Account ->
                if (
                    funding.owner != held.owner ||
                        funding.account != proceeds ||
                        funding.mint != JUP_USD_MINT ||
                        funding.payer !in setOfNotNull(owner, sponsor)
                ) {
                    findings += PredictionFinding.AccountCreationForSomeoneElse
                }
            is SwapStep.Route,
            is SwapStep.Wrap,
            is SwapStep.Sync,
            is SwapStep.Unwrap,
            is SwapStep.Moves -> findings += PredictionFinding.ExtraTransfer
            is SwapStep.Unread -> findings += PredictionFinding.UnreadableValueInstruction
        }
    }
    val least = placed.contractsMicro * placed.maxPrice / MICRO
    if (close.totalFeeUsd >= least) findings += PredictionFinding.ExcessiveFee

    val verdict =
        when {
            findings.any { it.invalidates } -> Verdict.Invalid
            findings.isNotEmpty() -> Verdict.Unverified
            else -> Verdict.Verified
        }
    val terms = terms(placed.contractsMicro, placed.maxPrice, placed.order, sponsor, networkFee)
    return SaleRead(
        ActionInspection(
            verdict = verdict,
            findings = findings.distinct().map { it.finding() },
            facts =
                InspectedAction(
                    wallet = owner,
                    // Nothing of the owner's is spent: contracts leave, dollars arrive. No spending
                    // rule has anything to count here.
                    movesValue = false,
                    mint = null,
                    recipient = placed.funding,
                    programs = decoded.instructions.mapNotNull(resolved::programOf).distinct(),
                    amount = null,
                    decimals = PROCEEDS_DECIMALS,
                    instructionCount = steps.size,
                    recognizedInstructions = steps.count { it !is OrderStep.Unread },
                ),
            version = version,
            details =
                saleDetails(
                    terms,
                    decoded.instructions.size,
                    resolved.accounts.size - resolved.static,
                ),
            references =
                listOf(
                    PluginReference(ORDER_ACCOUNT, placed.order),
                    PluginReference(POSITION_ACCOUNT, placed.position),
                    PluginReference(MARKET, held.marketId),
                ),
        ),
        terms,
    )
}

/** The labelled values a sale's review lists under its terms. Every one from the bytes. */
private fun saleDetails(terms: SaleTerms, instructions: Int, fromTables: Int): List<PluginFact> =
    buildList {
        if (terms.sponsor != null) {
            add(PluginFact(R.string.jupiter_fact_fee_sponsor, terms.sponsor))
        } else {
            terms.networkFeeLamports?.let {
                add(
                    PluginFact(
                        R.string.jupiter_fact_priority_fee,
                        formatBaseUnits(it, LAMPORT_DECIMALS),
                    )
                )
            }
        }
        add(PluginFact(R.string.jupiter_fact_instructions, instructions.toString()))
        add(PluginFact(R.string.jupiter_fact_resolved_accounts, fromTables.toString()))
    }

/** The dollar token a sale pays out in, as the owner reads it. */
const val PROCEEDS_SYMBOL: String = "JupUSD"

const val PROCEEDS_DECIMALS: Int = 6

/**
 * Jupiter's half of position management: reading a position and its orders, and building and
 * reading a sale of it (SEE-172).
 *
 * Reads and builds only. It holds the last sale it built, keyed by its bytes, for exactly as long
 * as a review needs it — nothing here is stored, and nothing here signs.
 */
internal class JupiterPositions(
    private val api: JupiterPrediction,
    private val chain: SolanaAccounts,
    private val now: () -> Instant,
) : PositionManagement {

    private val builds = AtomicInteger()

    override val sale: ActionCapability = PREDICTION_SELL_CAPABILITY

    override suspend fun position(held: HeldPosition): PositionRead =
        try {
            val read = api.position(held.account)
            // An answer about another account, owner, market or side is not an answer about this
            // one, and is not shown as one.
            if (
                read.positionPubkey != held.account ||
                    read.owner != held.owner ||
                    read.marketId != held.marketId ||
                    read.isYes != held.yes
            ) {
                PositionRead.Failed(ReadProblem.Unusable, "another position")
            } else {
                PositionRead.Found(read.reading())
            }
        } catch (e: PredictionException) {
            when (e.problem) {
                PredictionProblem.NotFound -> PositionRead.NotFound
                else -> PositionRead.Failed(e.problem.readProblem, e.detail)
            }
        }

    override suspend fun order(held: HeldPosition, orderAccount: String): OrderRead =
        try {
            val status = api.orderStatus(orderAccount)
            if (status.orderPubkey != orderAccount) {
                OrderRead.Failed(ReadProblem.Unusable, "another order")
            } else {
                OrderRead.Found(status.reading())
            }
        } catch (e: PredictionException) {
            when (e.problem) {
                PredictionProblem.NotFound -> OrderRead.NotFound
                else -> OrderRead.Failed(e.problem.readProblem, e.detail)
            }
        }

    override suspend fun prepareSale(held: HeldPosition, wallet: SelectedWallet?): PreparedSale {
        if (wallet == null) throw PluginFailure(NO_WALLET, R.string.jupiter_failure_no_wallet)
        if (wallet.network.network != SWAP_NETWORK || held.network != SWAP_NETWORK) {
            throw PluginFailure(OTHER_NETWORK, R.string.jupiter_failure_other_network)
        }
        if (wallet.address != held.owner) {
            throw PluginFailure(OTHER_OWNER, R.string.jupiter_failure_other_owner)
        }
        // The position, read again at the moment the owner asks — never the snapshot on screen.
        val position =
            try {
                api.position(held.account)
            } catch (e: PredictionException) {
                if (e.problem == PredictionProblem.NotFound) {
                    throw PluginFailure(NO_POSITION, R.string.jupiter_failure_no_position)
                }
                throw e.asFailure()
            }
        if (
            position.positionPubkey != held.account ||
                position.owner != held.owner ||
                position.marketId != held.marketId ||
                position.isYes != held.yes
        ) {
            throw PluginFailure(
                PredictionProblem.Unusable.code,
                R.string.jupiter_failure_prediction_unusable,
                "another position",
            )
        }
        val reading = position.reading()
        saleBlockOf(reading)?.let { throw it.failure() }
        val close =
            try {
                api.closePosition(held.account, held.owner)
            } catch (e: PredictionException) {
                throw e.asFailure()
            }
        // Keeper-filled orders are submitted by the owner's wallet like a buy. A build that says it
        // executes some other way would need submitting through the provider instead, and sending
        // it through the wallet as well would be the double submission this must never make.
        if (
            close.executionModel != null ||
                (close.executionType != null && close.executionType != KEEPER_EXECUTION)
        ) {
            throw PluginFailure(OTHER_EXECUTION, R.string.jupiter_failure_other_execution)
        }
        if (close.requiredSigners.any { it != held.owner }) {
            throw PluginFailure(OTHER_SIGNER, R.string.jupiter_failure_other_signer)
        }
        val version = builds.incrementAndGet()
        val read =
            try {
                inspectPredictionSale(held, position, close, wallet, version, chain)
            } catch (e: SolanaException) {
                throw PluginFailure(e.problem.code, e.problem.message, e.detail)
            } catch (e: LookupException) {
                throw PluginFailure(e.problem.code, e.problem.message, e.detail)
            }
        return PreparedSale(
            transaction = close.transaction,
            version = version,
            expiresAtEpochSeconds = now().plus(ORDER_LIFETIME).epochSecond,
            inspection = read.inspection,
            terms = read.terms,
            position = reading,
        )
    }

    override fun destinations(held: HeldPosition): List<PluginDestination> =
        listOf(
            PluginDestination(
                label = R.string.jupiter_destination_portfolio,
                url = JUPITER_ORDERS,
                deepLink = JUPITER_ORDERS,
            ),
            PluginDestination(
                label = R.string.jupiter_destination_market,
                url = "$JUPITER_PLATFORM/prediction/${held.marketId}",
                deepLink = "$JUPITER_PLATFORM/prediction/${held.marketId}",
            ),
        )

    private companion object {
        const val NO_WALLET = "no_wallet"
        const val OTHER_NETWORK = "other_network"
        const val OTHER_OWNER = "other_owner"
        const val OTHER_SIGNER = "other_signer"
        const val OTHER_EXECUTION = "other_execution"
        const val NO_POSITION = "no_position"
        const val KEEPER_EXECUTION = "create_order"
    }
}

/** The provider's reason for a [SaleBlock], in its own words. */
internal fun SaleBlock.failure(): PluginFailure =
    PluginFailure(
        code,
        when (this) {
            SaleBlock.Settled -> R.string.jupiter_failure_position_settled
            SaleBlock.MarketClosed -> R.string.jupiter_failure_market_closed
            SaleBlock.NothingHeld -> R.string.jupiter_failure_nothing_held
            SaleBlock.OpenOrders -> R.string.jupiter_failure_open_orders
            SaleBlock.NoBid -> R.string.jupiter_failure_no_bid
        },
    )

internal fun PredictionPosition.reading(): PositionReading =
    PositionReading(
        account = positionPubkey,
        owner = owner,
        marketId = marketId,
        eventId = eventId,
        yes = isYes,
        contractsMicro = contractsMicro,
        costMicroUsd = totalCostUsd,
        valueMicroUsd = valueUsd,
        markPriceMicroUsd = markPriceUsd,
        sellPriceMicroUsd = sellPriceUsd,
        averagePriceMicroUsd = avgPriceUsd,
        pnlMicroUsd = pnlUsd,
        pnlAfterFeesMicroUsd = pnlUsdAfterFees,
        feesPaidMicroUsd = feesPaidUsd,
        openOrders = openOrders,
        market = standingOf(marketStatus, marketResult),
        marketStatus = marketStatus,
        marketResult = marketResult,
        claimable = claimable,
        claimed = claimed,
        payoutMicroUsd = payoutUsd,
        marketTitle = marketTitle,
        eventTitle = eventTitle,
        venue = venue,
        updatedAtEpochSeconds = updatedAt,
    )

/** The provider's spelling of a market's state, onto the few that change what is offered. */
internal fun standingOf(status: String, result: String?): MarketStanding =
    when {
        result != null -> MarketStanding.Settled
        status == PredictionMarket.OPEN -> MarketStanding.Open
        status == "closed" -> MarketStanding.Closed
        status == "cancelled" || status == "canceled" -> MarketStanding.Cancelled
        status == "settled" || status == "resolved" || status == "finalized" ->
            MarketStanding.Settled
        else -> MarketStanding.Unknown
    }

internal fun PredictionOrderStatus.reading(): OrderReading =
    OrderReading(
        orderAccount = orderPubkey,
        fill =
            when (fill) {
                OrderFill.Pending -> OrderFillState.Pending
                OrderFill.PartiallyFilled -> OrderFillState.PartiallyFilled
                OrderFill.Filled -> OrderFillState.Filled
                OrderFill.PartiallyFilledClosed -> OrderFillState.PartiallyFilledClosed
                OrderFill.Failed -> OrderFillState.Failed
                OrderFill.Unknown -> OrderFillState.Unknown
            },
        raw = rawStatus,
        contractsMicro = contractsMicro,
        filledContractsMicro = filledContractsMicro,
        averageFillPriceMicroUsd = avgFillPriceUsd,
        netProceedsMicroUsd = netProceedsUsd,
        feeMicroUsd = feeUsd,
        latestSignature = latestSignature,
    )

private val PredictionProblem.readProblem: ReadProblem
    get() =
        when (this) {
            PredictionProblem.Unreachable -> ReadProblem.Unreachable
            PredictionProblem.RateLimited -> ReadProblem.RateLimited
            PredictionProblem.Refused,
            PredictionProblem.NoSuchMarket,
            PredictionProblem.MarketClosed,
            PredictionProblem.InsufficientFunds -> ReadProblem.Refused
            PredictionProblem.Unusable,
            PredictionProblem.NotFound -> ReadProblem.Unusable
        }
