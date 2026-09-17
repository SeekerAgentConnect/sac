package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.DecodedInstruction
import io.github.brrenat.seekervault.transactions.DecodedTransaction
import io.github.brrenat.seekervault.transactions.ReadInstruction
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.read

/**
 * Reading the instructions a Jupiter swap is made of (SEE-93).
 *
 * The app already reads the instructions a transfer is made of (`transactions/Programs.kt`), and
 * this is the same idea for the three kinds a swap adds: the aggregator's own routing instruction,
 * and the two token instructions that turn native SOL into the wrapped kind a pool can take and
 * back again. Everything else in a swap is something the transfer path already reads, and is reused
 * rather than re-read.
 *
 * As there, nothing is assumed. An instruction this file cannot account for comes back as
 * [SwapStep.Unread] with the program that runs it, and the review then says so rather than treating
 * it as harmless ([inspectSwap]).
 *
 * ## What is read inside the routing instruction, and what is not
 *
 * The aggregator's instruction carries, in order: a route plan, the exact input amount, the output
 * the quote promised, the slippage in basis points, and a platform fee in basis points. This file
 * reads the four numbers and the accounts; it does **not** read the route plan, which names the
 * pools the aggregator will hop through and is a different shape for each of the hundred-odd venues
 * it supports.
 *
 * That is a real limit and it is stated plainly, but it is not a hole in the review, because the
 * route plan cannot change any of the things the owner is risking. The program takes the input from
 * the owner's own token account, puts the output in the owner's own token account, and fails the
 * whole transaction unless the output is at least the quoted amount less the slippage — and those
 * are the accounts and the numbers this file reads. What route it took to get there is the
 * aggregator's business and the chain's, and the phone neither knows nor needs to
 * (docs/wiki/jupiter-swap.md#what-the-review-covers).
 *
 * The four numbers being read from the end of the instruction's data is safe for the same reason it
 * is exact: Borsh writes fields one after another with no padding, so the last nineteen bytes are
 * those four fields and nothing else. If a later version of the program moved them, they would stop
 * agreeing with the owner's own choice and with the quote, and the review would refuse the bytes
 * rather than misread them ([SwapFinding.AmountMismatch], [SwapFinding.SlippageMismatch]).
 */

/**
 * The Jupiter aggregator program. The only program in a swap that this app did not already know.
 */
const val JUPITER_PROGRAM: String = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"

/** Anchor discriminators: the first eight bytes of an instruction, naming which one it is. */
private val ROUTE = byteArrayOf(-27, 23, -53, -105, 122, -29, -83, 42)

private val SHARED_ACCOUNTS_ROUTE = byteArrayOf(-63, 32, -101, 51, 65, -42, -100, -127)

/**
 * SPL Token instruction 17: have the program credit a wrapped-SOL account with its own lamports.
 */
private const val SYNC_NATIVE = 17

/** SPL Token instruction 9: close an account and send what is left in it somewhere. */
private const val CLOSE_ACCOUNT = 9

/** The four trailing fields, in bytes: two u64 amounts, a u16 of basis points, and a u8 of them. */
private const val TRAILING_ARGS = 8 + 8 + 2 + 1

/** One instruction of a swap, as this plugin read it. */
sealed interface SwapStep {
    /**
     * The aggregator's routing instruction: what goes in, from where, what must come out, and to
     * where.
     *
     * [sourceMint] is null for the variant that does not carry it, which is not a gap: the source
     * is checked by deriving the owner's own token account for the mint the owner chose and
     * comparing the address, which establishes the mint as surely as a field would
     * ([SwapFinding.SourceNotOwnersAccount]).
     */
    data class Route(
        /**
         * Whether this is the shared-accounts variant. Both are supported; they differ in layout.
         */
        val shared: Boolean,
        /** The account that authorizes the input leaving. It has to be the owner. */
        val authority: String,
        /** The token account the input comes out of. */
        val source: String,
        /** The token account the output goes into. */
        val destination: String,
        val sourceMint: String?,
        val destinationMint: String,
        /** An account that would take a cut of the output, or null when there is none. */
        val platformFee: String?,
        /** How many hops the plan takes. */
        val legs: Int,
        val inAmount: ULong,
        /** What the quote promised, which with [slippageBps] is the floor the program enforces. */
        val quotedOutAmount: ULong,
        val slippageBps: Int,
        val platformFeeBps: Int,
    ) : SwapStep {
        /**
         * The least the owner can receive: below this the routing instruction errors and the whole
         * transaction fails.
         *
         * Whole base units, and the subtraction is done the way the provider's own quote does it —
         * the slippage is floored and taken off the quoted amount, rather than the quoted amount
         * being scaled — so this number and the provider's stated threshold are the same number.
         * That is what makes comparing them a check worth making ([SwapFinding.QuoteMismatch]); two
         * nearly-equal numbers would prove nothing.
         */
        val minimumOut: ULong
            get() =
                quotedOutAmount -
                    quotedOutAmount * slippageBps.toULong() / MOST_SLIPPAGE_BPS.toULong()
    }

    /** Lamports moving from the owner to their own wrapped-SOL account: the wrap. */
    data class Wrap(val from: String, val to: String, val lamports: ULong) : SwapStep

    /** The token program crediting that account with them. */
    data class Sync(val account: String) : SwapStep

    /** That account being closed, which returns the lamports left in it: the unwrap. */
    data class Unwrap(val account: String, val to: String, val authority: String) : SwapStep

    /** A token account being created for somebody, so the output has somewhere to land. */
    data class Account(
        val payer: String,
        val account: String,
        val owner: String,
        val mint: String,
        val idempotent: Boolean,
    ) : SwapStep

    /** What the owner is prepared to pay for the transaction to be picked up. */
    data class Budget(val unitLimit: UInt?, val microLamportsPerUnit: ULong?) : SwapStep

    /**
     * A movement of value that a swap has no place for: a plain transfer of SOL that is not the
     * wrap, or a token transfer. It is read, and it is refused.
     */
    data class Moves(val read: ReadInstruction) : SwapStep

    /** A valid instruction this plugin does not read. Not thereby harmless — simply not covered. */
    data class Unread(val program: String, val instruction: Int?) : SwapStep
}

/**
 * Reads one instruction of [this] as a step of a swap, or null when its account indexes point
 * outside the message — which makes the whole transaction malformed rather than this instruction
 * unread.
 */
fun DecodedTransaction.readSwapStep(instruction: DecodedInstruction): SwapStep? {
    val program = programOf(instruction) ?: return null
    val accounts = accountsOf(instruction) ?: return null
    if (program == JUPITER_PROGRAM) return route(instruction.data, accounts)
    if (program == TOKEN_PROGRAM && instruction.data.size == 1) {
        when (instruction.data[0].toInt() and 0xff) {
            SYNC_NATIVE ->
                return if (accounts.size == 1) SwapStep.Sync(accounts[0])
                else SwapStep.Unread(program, SYNC_NATIVE)
            CLOSE_ACCOUNT ->
                return if (accounts.size >= 3)
                    SwapStep.Unwrap(accounts[0], accounts[1], accounts[2])
                else SwapStep.Unread(program, CLOSE_ACCOUNT)
        }
    }
    // Everything else a swap contains is something the transfer path already reads, so it is read
    // by the same code: one reader for one wire format.
    return when (val step = read(instruction)) {
        null -> null
        is ReadInstruction.SolTransfer -> SwapStep.Wrap(step.from, step.to, step.lamports)
        is ReadInstruction.CreateTokenAccount ->
            SwapStep.Account(step.payer, step.account, step.owner, step.mint, step.idempotent)
        is ReadInstruction.ComputeBudget ->
            SwapStep.Budget(step.unitLimit, step.microLamportsPerUnit)
        is ReadInstruction.TokenTransfer -> SwapStep.Moves(step)
        is ReadInstruction.Unrecognized -> SwapStep.Unread(step.program, step.instruction)
    }
}

/** The programs a supported swap calls. Anything else is an instruction from somewhere else. */
val SWAP_PROGRAMS: Set<String> =
    setOf(
        SYSTEM_PROGRAM,
        TOKEN_PROGRAM,
        ASSOCIATED_TOKEN_PROGRAM,
        COMPUTE_BUDGET_PROGRAM,
        JUPITER_PROGRAM,
    )

// The two routing instructions, which differ in one leading byte and in where the accounts sit.
private fun route(data: ByteArray, accounts: List<String>): SwapStep {
    val shared =
        when {
            data.startsWith(SHARED_ACCOUNTS_ROUTE) -> true
            data.startsWith(ROUTE) -> false
            // Some other instruction of the aggregator — a limit order, a fee claim, a version of
            // routing this plugin was not written for. Named by its discriminator's first byte so
            // the log says something, and covered by nothing.
            else -> return SwapStep.Unread(JUPITER_PROGRAM, data.firstOrNull()?.toInt()?.and(0xff))
        }
    val head = ROUTE.size + if (shared) 1 + 4 else 4
    if (data.size < head + TRAILING_ARGS) {
        return SwapStep.Unread(JUPITER_PROGRAM, data[0].toInt() and 0xff)
    }
    val layout = if (shared) SharedLayout else PlainLayout
    if (accounts.size < layout.least) {
        return SwapStep.Unread(JUPITER_PROGRAM, data[0].toInt() and 0xff)
    }
    // The number of hops the plan takes, read and not judged: how many there are changes nothing
    // about where the four numbers below sit, and what is an acceptable number of them is the
    // review's business ([SwapFinding.TooManyLegs]) rather than the reader's.
    val legs =
        data.u32(head - 4) ?: return SwapStep.Unread(JUPITER_PROGRAM, data[0].toInt() and 0xff)
    val tail = data.size - TRAILING_ARGS
    val inAmount = data.u64(tail) ?: return SwapStep.Unread(JUPITER_PROGRAM, null)
    val quoted = data.u64(tail + 8) ?: return SwapStep.Unread(JUPITER_PROGRAM, null)
    val slippage = data.u16(tail + 16) ?: return SwapStep.Unread(JUPITER_PROGRAM, null)
    val fee = data[data.size - 1].toInt() and 0xff
    return SwapStep.Route(
        shared = shared,
        authority = accounts[layout.authority],
        source = accounts[layout.source],
        destination = accounts[layout.destination],
        sourceMint = layout.sourceMint?.let(accounts::get),
        destinationMint = accounts[layout.destinationMint],
        // An Anchor account that is absent is written as the program's own address. So "no platform
        // fee account" and "a platform fee account that happens to be the program" are the same
        // bytes, and both mean nobody takes a cut.
        platformFee = accounts[layout.platformFee].takeIf { it != JUPITER_PROGRAM },
        legs = legs.toInt(),
        inAmount = inAmount,
        quotedOutAmount = quoted,
        slippageBps = slippage.toInt(),
        platformFeeBps = fee,
    )
}

/** Where each account sits in one of the two routing instructions. */
private class Layout(
    val least: Int,
    val authority: Int,
    val source: Int,
    val destination: Int,
    val sourceMint: Int?,
    val destinationMint: Int,
    val platformFee: Int,
)

// route(token_program, user_transfer_authority, user_source, user_destination,
//       destination_token_account?, destination_mint, platform_fee?, event_authority, program)
private val PlainLayout =
    Layout(
        least = 9,
        authority = 1,
        source = 2,
        destination = 3,
        sourceMint = null,
        destinationMint = 5,
        platformFee = 6,
    )

// sharedAccountsRoute(token_program, program_authority, user_transfer_authority, source,
//       program_source, program_destination, destination, source_mint, destination_mint,
//       platform_fee?, token_2022_program?, event_authority, program)
private val SharedLayout =
    Layout(
        least = 13,
        authority = 2,
        source = 3,
        destination = 6,
        sourceMint = 7,
        destinationMint = 8,
        platformFee = 9,
    )

/**
 * The most hops a supported route may take. It is one: this plugin asks the provider for a direct
 * route and then checks that it got one, because the number of hops is in the bytes and a request
 * is not a guarantee.
 */
const val MOST_LEGS: Int = 1

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

// Little-endian, read at an offset, because these fields are found by position rather than by
// walking the whole instruction.
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
