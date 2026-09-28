package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.solana.ResolvedTransaction
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.DecodedInstruction
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM

/**
 * Reading the instructions a prediction order is made of (SEE-94).
 *
 * An order is a swap and then a bet: the deposit is routed into the provider's own dollar token by
 * exactly the aggregator instruction [SwapInstructions] already reads, and then one instruction of
 * the prediction program buys contracts with it. So this file adds one reader — the order — and
 * delegates everything else to the code that reads a swap.
 *
 * ## What the order instruction carries
 *
 * All of it, and this is the part worth knowing: the provider's request identifier, the market's
 * hash, which side, whether it buys or sells, how many contracts, the price bound — the most a
 * contract may cost for a buy, the least it may fetch for a sale — what the order will cost, and
 * the slippage — each as its own field in the instruction's own Borsh data. So the review does not
 * have to take the provider's word for what it built: it reads the order out of the bytes and
 * compares it with what the owner chose and with what the provider said
 * (docs/wiki/jupiter-prediction.md#what-the-review-covers).
 *
 * Verified against a real order captured from the live API (`fixtures/jupiter/orders.json`), whose
 * every field matched the answer's own JSON to the base unit.
 */

/** The prediction program. The one program in an order this app did not already know. */
const val PREDICTION_PROGRAM: String = "3ZZuTbwC6aJbvteyVxXUS7gtFYdf7AuXeitx6VyvjvUp"

/** The Anchor discriminator of the instruction that places an order. */
private val PLACE_ORDER = byteArrayOf(-115, 54, 37, -49, -19, -46, -6, -41)

/** The fixed part of the order instruction's arguments, after the two length-prefixed strings. */
private const val ORDER_TAIL = 1 + 1 + 8 + 8 + 8 + 2

/** One instruction of an order, as this plugin read it. */
sealed interface OrderStep {
    /**
     * The order itself.
     *
     * [marketHash] is the provider's own hash of the market identifier. The phone cannot compute it
     * — it is not a plain digest of the identifier, which was checked — so it is *cross-checked*
     * between the answer and the bytes rather than proved from the market ID, and the limit is
     * stated rather than glossed over (docs/wiki/jupiter-prediction.md#what-is-out-of-reach).
     */
    data class Order(
        /** Who pays and authorizes it. */
        val payer: String,
        /** Whose order it is. The same account, for an order somebody places for themselves. */
        val owner: String,
        /** The protocol's own co-signer, whose signature the provider has already supplied. */
        val protocol: String,
        /** The market's account on chain. */
        val market: String,
        /** The position the contracts land in. */
        val position: String,
        /** The order's own account. */
        val order: String,
        /** The token account the stake comes out of: the owner's, for the provider's own token. */
        val funding: String,
        /** That token's mint. */
        val mint: String,
        val externalOrderId: String,
        val marketHash: String,
        val buying: Boolean,
        val yes: Boolean,
        /** Contracts in millionths: 1000000 is one contract. */
        val contractsMicro: ULong,
        /**
         * The price bound, in the mint's base units: for a buy the most one contract may cost, for
         * a sale the least one may be sold for — the floor the program enforces.
         */
        val maxPrice: ULong,
        /** What the order will cost, in the same units. */
        val cost: ULong,
        val slippageBps: Int,
        /** A ceiling on slippage when the order carries one. */
        val maxSlippageBps: Int?,
    ) : OrderStep {
        /**
         * What the order pays out if this side wins, in the mint's base units: one unit per
         * contract.
         *
         * It is arithmetic on what the bytes say and **not** a claim that anything will be paid.
         * Whether the side wins, whether the market settles the way anybody expects, and whether a
         * payout is ever claimed are all outside this app entirely.
         */
        val payout: ULong
            get() = contractsMicro
    }

    /** Something the swap reader accounted for: the route, the wrap, a creation, the fee. */
    data class Funding(val step: SwapStep) : OrderStep

    /** A valid instruction this plugin does not read. Not thereby harmless. */
    data class Unread(val program: String, val instruction: Int?) : OrderStep
}

/** The programs a supported order calls. Anything else is an instruction from somewhere else. */
val ORDER_PROGRAMS: Set<String> =
    setOf(
        SYSTEM_PROGRAM,
        TOKEN_PROGRAM,
        ASSOCIATED_TOKEN_PROGRAM,
        COMPUTE_BUDGET_PROGRAM,
        JUPITER_PROGRAM,
        PREDICTION_PROGRAM,
    )

/**
 * Reads one instruction of [this] as a step of an order, or null when its indexes point outside the
 * resolved account list — which makes the whole transaction one this app will not review.
 */
fun ResolvedTransaction.readOrderStep(instruction: DecodedInstruction): OrderStep? {
    val program = programOf(instruction) ?: return null
    val accounts = accountsOf(instruction) ?: return null
    if (program != PREDICTION_PROGRAM) {
        return when (val step = swapStep(program, accounts, instruction.data)) {
            is SwapStep.Unread -> OrderStep.Unread(step.program, step.instruction)
            else -> OrderStep.Funding(step)
        }
    }
    return order(instruction.data, accounts)
}

// The order instruction, walked forward: the lengths of the two strings are in the data, so the
// walk is exact rather than positional, and the arguments must end exactly where the data does.
private fun order(data: ByteArray, accounts: List<String>): OrderStep {
    if (!data.startsWith(PLACE_ORDER)) {
        // Another instruction of the prediction program — claiming a payout, a version of ordering
        // this plugin was not written for. (Selling is not one: a sale is this same instruction
        // with its direction flag clear, SEE-172.) Named by its first byte so the log
        // says something, and covered by nothing.
        return OrderStep.Unread(PREDICTION_PROGRAM, data.firstOrNull()?.toInt()?.and(0xff))
    }
    if (accounts.size < OrderLayout.least) return unread(data)
    var at = PLACE_ORDER.size
    val externalOrderId = text(data, at) ?: return unread(data)
    at += 4 + externalOrderId.length
    val marketHash = text(data, at) ?: return unread(data)
    at += 4 + marketHash.length
    if (data.size < at + ORDER_TAIL) return unread(data)
    // The side first, then the direction. A YES buy writes 1, 1 and cannot tell the two apart,
    // which is how they were once read the other way round; a NO buy (0, 1) and a YES sale (1, 0),
    // both captured from the live API, can (SEE-172, fixtures/jupiter/positions.json).
    val yes = data[at].toInt() == 1
    val buying = data[at + 1].toInt() == 1
    // A byte that is neither 0 nor 1 is not a boolean, and a side this plugin guessed at would be
    // the worst possible thing to guess at.
    if (data[at].toInt() !in 0..1 || data[at + 1].toInt() !in 0..1) return unread(data)
    val contracts = data.u64(at + 2) ?: return unread(data)
    val maxPrice = data.u64(at + 10) ?: return unread(data)
    val cost = data.u64(at + 18) ?: return unread(data)
    val slippage = data.u16(at + 26) ?: return unread(data)
    at += ORDER_TAIL
    // A Borsh option: absent is one zero byte, present is a one and the value. Anything else, or
    // anything left over afterwards, is a layout this plugin does not know.
    val maxSlippage =
        when (data.getOrNull(at)?.toInt()) {
            0 -> null.also { at += 1 }
            1 -> (data.u16(at + 1) ?: return unread(data)).toInt().also { at += 3 }
            else -> return unread(data)
        }
    if (at != data.size) return unread(data)
    return OrderStep.Order(
        payer = accounts[OrderLayout.payer],
        owner = accounts[OrderLayout.owner],
        protocol = accounts[OrderLayout.protocol],
        market = accounts[OrderLayout.market],
        position = accounts[OrderLayout.position],
        order = accounts[OrderLayout.order],
        funding = accounts[OrderLayout.funding],
        mint = accounts[OrderLayout.mint],
        externalOrderId = externalOrderId,
        marketHash = marketHash,
        buying = buying,
        yes = yes,
        contractsMicro = contracts,
        maxPrice = maxPrice,
        cost = cost,
        slippageBps = slippage.toInt(),
        maxSlippageBps = maxSlippage,
    )
}

/** Where each account sits in the order instruction. */
private object OrderLayout {
    const val least = 12
    const val payer = 0
    const val owner = 1
    const val protocol = 2
    const val market = 3
    const val position = 4
    const val order = 5
    const val funding = 6
    const val mint = 7
}

private fun unread(data: ByteArray) =
    OrderStep.Unread(PREDICTION_PROGRAM, data.firstOrNull()?.toInt()?.and(0xff))

/** A Borsh string: a little-endian `u32` length and that many bytes of text. */
private fun text(data: ByteArray, at: Int): String? {
    val length = data.u32(at)?.toInt() ?: return null
    if (length !in 1..MOST_IDENTIFIER_BYTES) return null
    if (at + 4 + length > data.size) return null
    val bytes = data.copyOfRange(at + 4, at + 4 + length)
    // Both of these are hexadecimal identifiers on the wire. Anything else is not something to
    // show somebody as an order's identity.
    val decoded = bytes.decodeToString()
    return decoded.takeIf { it.all { character -> character in '0'..'9' || character in 'a'..'f' } }
}

/** Neither identifier is long, and a length that says otherwise is a length nobody wrote. */
private const val MOST_IDENTIFIER_BYTES = 64

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.u16(at: Int): UInt? =
    if (at + 2 > size) null
    else (this[at].toUInt() and 0xffU) or ((this[at + 1].toUInt() and 0xffU) shl 8)

private fun ByteArray.u32(at: Int): UInt? =
    if (at + 4 > size) null
    else
        (0 until 4).fold(0U) { value, index ->
            value or ((this[at + index].toUInt() and 0xffU) shl (index * 8))
        }

private fun ByteArray.u64(at: Int): ULong? =
    if (at + 8 > size) null
    else
        (0 until 8).fold(0UL) { value, index ->
            value or ((this[at + index].toULong() and 0xffUL) shl (index * 8))
        }
