package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.associatedTokenAddress

/**
 * Building sales for the tests (SEE-172).
 *
 * The committed fixture (`fixtures/jupiter/positions.json`) is a real close Jupiter built. This is
 * the builder beside it, shaped like it — gasless, the provider's relayer paying and pre-signed,
 * the protocol pre-signed, the owner's slot the only one empty — so a test can change exactly one
 * thing and show the review refusing it.
 */

/** Jupiter's gasless relayer, as the real close names it. */
const val RELAYER: String = "748XjHxBdkWo4rLwzMXuYieUejv3R8X2dGPys7VGDkAj"

const val SALE_ORDER: String = "BLdpH2SNsSnvqgxrwZEHZpTfmawicXphgaNJi9wmKJCh"

const val SALE_EXTERNAL: String = "b8332f75a3b141da9c8cb823aab88e49"

const val HELD_CONTRACTS: ULong = 63_550_000UL

const val SALE_FLOOR: ULong = 270_000UL

const val BID: ULong = 350_000UL

fun held(
    owner: String = OWNER,
    account: String = POSITION_PUBKEY,
    yes: Boolean = true,
): HeldPosition =
    HeldPosition(
        provider = JUPITER_PROVIDER,
        owner = owner,
        network = Network.NETWORK_MAINNET,
        account = account,
        marketId = MARKET_ID,
        yes = yes,
    )

fun predictionPosition(
    owner: String = OWNER,
    account: String = POSITION_PUBKEY,
    yes: Boolean = true,
    contracts: ULong = HELD_CONTRACTS,
    bid: ULong? = BID,
    status: String = "open",
    result: String? = null,
    claimable: Boolean = false,
    openOrders: Int = 0,
): PredictionPosition =
    PredictionPosition(
        positionPubkey = account,
        owner = owner,
        marketId = MARKET_ID,
        eventId = EVENT_ID,
        isYes = yes,
        contractsMicro = contracts,
        totalCostUsd = 22_878_000UL,
        valueUsd = bid?.let { contracts * it / 1_000_000UL },
        markPriceUsd = bid,
        sellPriceUsd = bid,
        avgPriceUsd = 360_000UL,
        pnlUsd = -635_500L,
        pnlUsdAfterFees = -1_367_660L,
        feesPaidUsd = 732_160UL,
        openOrders = openOrders,
        claimable = claimable,
        claimed = false,
        payoutUsd = contracts,
        marketStatus = status,
        marketResult = result,
        marketTitle = "Ukraine",
        eventTitle = "Georgia vs. Ukraine",
        venue = "polymarket",
        updatedAt = 1_790_582_181L,
    )

/** What the provider says it built, beside the bytes. */
fun predictionClose(
    bytes: OrderBytes,
    yes: Boolean = true,
    contracts: ULong = HELD_CONTRACTS,
    floor: ULong = SALE_FLOOR,
    fee: ULong = 2_891_520UL,
    position: String = POSITION_PUBKEY,
    signers: List<String> = listOf(OWNER),
    executionModel: String? = null,
    executionType: String? = "create_order",
): PredictionClose =
    PredictionClose(
        transaction = bytes.transaction,
        orderPubkey = SALE_ORDER,
        positionPubkey = position,
        externalOrderId = SALE_EXTERNAL,
        marketId = MARKET_ID,
        marketIdHash = MARKET_HASH,
        isYes = yes,
        isBuy = false,
        contractsMicro = contracts,
        newContractsMicro = 0UL,
        minSellPriceUsd = floor,
        totalFeeUsd = fee,
        requiredSigners = signers,
        executionModel = executionModel,
        executionType = executionType,
        gasless = true,
    )

/**
 * A complete, well-formed sale in the shape Jupiter builds it: two compute-budget settings, the
 * owner's own JupUSD account created idempotently by the relayer, and the order instruction with
 * its direction flag clear. Every parameter is one thing a test can get wrong on purpose.
 */
fun saleTransaction(
    owner: String = OWNER,
    payer: String = RELAYER,
    relayerSigned: Boolean = true,
    ownerSigned: Boolean = false,
    orderOwner: String = owner,
    orderPayer: String = payer,
    position: String = POSITION_PUBKEY,
    order: String = SALE_ORDER,
    proceeds: String? = null,
    mint: String = JUP_USD_MINT,
    yes: Boolean = true,
    buying: Boolean = false,
    contracts: ULong = HELD_CONTRACTS,
    floor: ULong = SALE_FLOOR,
    cost: ULong = 0UL,
    slippageBps: Int = 0,
    maxSlippageBps: Int? = null,
    externalOrderId: String = SALE_EXTERNAL,
    marketHash: String = MARKET_HASH,
    createFor: String? = owner,
    createPayer: String = payer,
    priorityMicroLamports: ULong = 50_000UL,
    /** Null leaves the limit instruction out, as a build that relies on the default would. */
    unitLimit: UInt? = 150_000U,
    orders: Int = 1,
    extra: List<Step> = emptyList(),
): OrderBytes {
    val proceedsAccount = proceeds ?: checkNotNull(associatedTokenAddress(owner, mint))
    val steps = mutableListOf<Step>()
    steps += Step(COMPUTE_BUDGET_PROGRAM, emptyList(), byteArrayOf(3) + u64(priorityMicroLamports))
    if (unitLimit != null) {
        steps += Step(COMPUTE_BUDGET_PROGRAM, emptyList(), byteArrayOf(2) + u32(unitLimit))
    }
    if (createFor != null) {
        steps +=
            Step(
                ASSOCIATED_TOKEN_PROGRAM,
                listOf(
                    createPayer,
                    checkNotNull(associatedTokenAddress(createFor, mint)),
                    createFor,
                    mint,
                    SYSTEM_PROGRAM,
                    TOKEN_PROGRAM,
                ),
                byteArrayOf(1),
            )
    }
    repeat(orders) {
        steps +=
            Step(
                PREDICTION_PROGRAM,
                listOf(
                    orderPayer,
                    orderOwner,
                    PROTOCOL_SIGNER,
                    MARKET_ACCOUNT,
                    position,
                    order,
                    proceedsAccount,
                    mint,
                    ORDER_ATA,
                    TOKEN_PROGRAM,
                    ASSOCIATED_TOKEN_PROGRAM,
                    SYSTEM_PROGRAM,
                ),
                orderData(
                    externalOrderId = externalOrderId,
                    marketHash = marketHash,
                    buying = buying,
                    yes = yes,
                    contractsMicro = contracts,
                    maxPrice = floor,
                    cost = cost,
                    slippageBps = slippageBps,
                    maxSlippageBps = maxSlippageBps,
                ),
            )
    }
    steps += extra
    // Three slots when the relayer pays, in Jupiter's order: the payer, the protocol, the owner.
    val signers =
        if (payer == owner) listOf(owner, PROTOCOL_SIGNER)
        else listOf(payer, PROTOCOL_SIGNER, owner)
    val filled = buildList {
        if (payer != owner && relayerSigned) add(0)
        add(1)
        if (ownerSigned) add(signers.indexOf(owner))
    }
    return versioned(payer = payer, signers = signers, filled = filled, steps = steps)
}
