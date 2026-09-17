package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.wallet.isSolanaAddress

/**
 * The spot-swap payload a publisher broadcasts, as this plugin reads it (SEE-93).
 *
 * A proposal's terms are a bounded list of named strings, and core carries them without
 * interpreting any of them (`Proposal.values`, SEE-89). This file is where `jupiter.swap` says what
 * it requires of them, and every rule here exists because a publisher is a stranger: it is a
 * developer's server this phone holds no credential for, whose documents arrive through a gateway
 * shared with everyone else, and whose prose is read by nobody but the owner.
 *
 * ## Assets are mints, never tickers
 *
 * "BTC" names a dozen different things on Solana and nothing at all off it. So an asset here is an
 * exact base58 mint address and only that, and a symbol — if the publisher gives one — is a label
 * the screen shows as the publisher's word, beside the mint, never instead of it. There is
 * deliberately no way to express "buy Bitcoin": a signal about Bitcoin is a signal about one
 * specific wrapped mint on this chain, and a publisher that will not name it has not said what it
 * is proposing (docs/wiki/jupiter-swap.md#what-a-signal-has-to-say).
 *
 * ## Direction is the pair, and the pair is ordered
 *
 * [inputMint] is spent and [outputMint] is received. There is no separate side or direction field,
 * because a field that could disagree with the pair is a field that will: a publisher that means
 * the other way round publishes the other pair.
 *
 * ## What is the owner's
 *
 * Not the amount, and not the slippage they will tolerate. The publisher states a ceiling for
 * slippage and may state bounds on the amount; within those, both are chosen on the phone, stay on
 * the phone, and differ between two people who received the same document.
 */
data class SwapTerms(
    /** The mint the owner spends. Native SOL is the wrapped mint, spelled out ([WRAPPED_SOL]). */
    val inputMint: String,
    /** Its base units per whole token, for display only: nothing here rounds anything. */
    val inputDecimals: Int,
    /** The mint the owner receives. */
    val outputMint: String,
    val outputDecimals: Int,
    /**
     * The most the received amount may fall below the quote, in basis points, as the publisher's
     * ceiling. The owner chooses at or below it; nothing raises it.
     */
    val maxSlippageBps: Int,
    /** The least the publisher will have this acted on with, in [inputMint]'s base units. */
    val leastInput: ULong = 0UL,
    /** The most, or null when the publisher set no ceiling. The owner's wallet is the real one. */
    val mostInput: ULong? = null,
    /** The publisher's label for the input mint, unverified and shown as theirs; may be empty. */
    val inputSymbol: String = "",
    val outputSymbol: String = "",
)

/**
 * The term names. They are the payload's contract with every publisher, so they are spelled here
 * once and read nowhere else; a template that publishes them is SEE-95.
 */
object SwapTermNames {
    const val INPUT_MINT = "input_mint"
    const val INPUT_DECIMALS = "input_decimals"
    const val INPUT_SYMBOL = "input_symbol"
    const val OUTPUT_MINT = "output_mint"
    const val OUTPUT_DECIMALS = "output_decimals"
    const val OUTPUT_SYMBOL = "output_symbol"
    const val MAX_SLIPPAGE_BPS = "max_slippage_bps"
    const val LEAST_INPUT = "least_input"
    const val MOST_INPUT = "most_input"
}

/** The wrapped-SOL mint, which is how native SOL is named in a swap: as the mint that moves. */
const val WRAPPED_SOL: String = "So11111111111111111111111111111111111111112"

/** The widest slippage that can be expressed at all: basis points of a whole. */
const val MOST_SLIPPAGE_BPS: Int = 10_000

/** The most decimals an SPL mint can have. */
const val MOST_DECIMALS: Int = 18

/** A label is shown, so it is short, and it is never believed. */
const val MOST_SYMBOL_LENGTH: Int = 16

/** Which rule a publisher's terms broke. Each is a separate fact, and none of them is a guess. */
enum class SwapTermProblem(val code: String) {
    /** A required term is absent. A swap with no assets named is not a swap with defaults. */
    Missing("missing"),
    /** A named asset is not a base58 32-byte mint — a ticker, a name, or a typo. */
    NotAMint("not_a_mint"),
    /** Both sides are the same mint, which proposes nothing. */
    OneAsset("one_asset"),
    /** Decimals outside what a mint can have. */
    BadDecimals("bad_decimals"),
    /** A slippage ceiling that is not basis points of a whole, or is zero. */
    BadSlippage("bad_slippage"),
    /** An amount bound that is not a whole number of base units. */
    BadAmount("bad_amount"),
    /** The publisher's floor is above its own ceiling, so nothing satisfies both. */
    ImpossibleAmounts("impossible_amounts"),
    /** A label too long to show. */
    BadSymbol("bad_symbol"),
}

sealed interface SwapTermsResult {
    data class Valid(val terms: SwapTerms) : SwapTermsResult

    /** Which rule broke, and the term it broke on, so the owner is told which one. */
    data class Invalid(val problem: SwapTermProblem, val term: String) : SwapTermsResult
}

/**
 * Reads [terms] as a spot swap, or says which rule broke and where.
 *
 * Nothing is inferred and nothing is defaulted except the parts the payload explicitly makes
 * optional: a missing decimals is not zero, and a missing slippage ceiling is not "any". A term
 * this plugin does not know is ignored, because a publisher may say more than this plugin reads and
 * the extra is not an error — but it is also not consulted, so nothing in an unknown term can
 * change what is prepared.
 */
fun swapTermsFrom(terms: Map<String, String>): SwapTermsResult {
    val inputMint =
        required(terms, SwapTermNames.INPUT_MINT) ?: return missing(SwapTermNames.INPUT_MINT)
    if (!isSolanaAddress(inputMint)) {
        return SwapTermsResult.Invalid(SwapTermProblem.NotAMint, SwapTermNames.INPUT_MINT)
    }
    val outputMint =
        required(terms, SwapTermNames.OUTPUT_MINT) ?: return missing(SwapTermNames.OUTPUT_MINT)
    if (!isSolanaAddress(outputMint)) {
        return SwapTermsResult.Invalid(SwapTermProblem.NotAMint, SwapTermNames.OUTPUT_MINT)
    }
    if (inputMint == outputMint) {
        return SwapTermsResult.Invalid(SwapTermProblem.OneAsset, SwapTermNames.OUTPUT_MINT)
    }
    val inputDecimals =
        decimals(terms, SwapTermNames.INPUT_DECIMALS)
            ?: return badOrMissing(
                terms,
                SwapTermNames.INPUT_DECIMALS,
                SwapTermProblem.BadDecimals,
            )
    val outputDecimals =
        decimals(terms, SwapTermNames.OUTPUT_DECIMALS)
            ?: return badOrMissing(
                terms,
                SwapTermNames.OUTPUT_DECIMALS,
                SwapTermProblem.BadDecimals,
            )
    val slippage =
        terms[SwapTermNames.MAX_SLIPPAGE_BPS]?.toIntOrNull()?.takeIf { it in 1..MOST_SLIPPAGE_BPS }
            ?: return badOrMissing(
                terms,
                SwapTermNames.MAX_SLIPPAGE_BPS,
                SwapTermProblem.BadSlippage,
            )
    // Absent is absent; present and unreadable is a broken term, and the two are not the same
    // thing to tell the owner.
    val least =
        if (SwapTermNames.LEAST_INPUT in terms) {
            terms[SwapTermNames.LEAST_INPUT]?.toULongOrNull()
                ?: return SwapTermsResult.Invalid(
                    SwapTermProblem.BadAmount,
                    SwapTermNames.LEAST_INPUT,
                )
        } else 0UL
    val most =
        if (SwapTermNames.MOST_INPUT in terms) {
            terms[SwapTermNames.MOST_INPUT]?.toULongOrNull()
                ?: return SwapTermsResult.Invalid(
                    SwapTermProblem.BadAmount,
                    SwapTermNames.MOST_INPUT,
                )
        } else null
    if (most != null && (most < least || most == 0UL)) {
        return SwapTermsResult.Invalid(
            SwapTermProblem.ImpossibleAmounts,
            SwapTermNames.MOST_INPUT,
        )
    }
    val inputSymbol =
        symbol(terms, SwapTermNames.INPUT_SYMBOL) ?: return badSymbol(SwapTermNames.INPUT_SYMBOL)
    val outputSymbol =
        symbol(terms, SwapTermNames.OUTPUT_SYMBOL) ?: return badSymbol(SwapTermNames.OUTPUT_SYMBOL)
    return SwapTermsResult.Valid(
        SwapTerms(
            inputMint = inputMint,
            inputDecimals = inputDecimals,
            outputMint = outputMint,
            outputDecimals = outputDecimals,
            maxSlippageBps = slippage,
            leastInput = least,
            mostInput = most,
            inputSymbol = inputSymbol,
            outputSymbol = outputSymbol,
        )
    )
}

private fun required(terms: Map<String, String>, name: String): String? =
    terms[name]?.takeIf { it.isNotBlank() }

private fun decimals(terms: Map<String, String>, name: String): Int? =
    terms[name]?.toIntOrNull()?.takeIf { it in 0..MOST_DECIMALS }

private fun symbol(terms: Map<String, String>, name: String): String? =
    (terms[name] ?: "").takeIf { it.length <= MOST_SYMBOL_LENGTH }

private fun missing(name: String) = SwapTermsResult.Invalid(SwapTermProblem.Missing, name)

private fun badSymbol(name: String) = SwapTermsResult.Invalid(SwapTermProblem.BadSymbol, name)

// Absent and unreadable are told apart, because "the publisher said nothing" and "the publisher
// said something this plugin can't read" are different things to show someone.
private fun badOrMissing(
    terms: Map<String, String>,
    name: String,
    problem: SwapTermProblem,
): SwapTermsResult.Invalid =
    if (required(terms, name) == null) SwapTermsResult.Invalid(SwapTermProblem.Missing, name)
    else SwapTermsResult.Invalid(problem, name)
