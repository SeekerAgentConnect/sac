package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterField
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue

/**
 * The half of a swap that is the owner's, and never the publisher's (SEE-93).
 *
 * A signal says what pair to swap and how much slippage it will tolerate at most. It does not say
 * how much of the owner's money to spend, and it never learns: two people acting on the same
 * document spend different amounts, and neither the publisher nor the gateway is told either number
 * (docs/wiki/shared-proposals.md#what-stays-on-the-phone).
 *
 * Both fields are collected in base units and whole basis points, because that is what the
 * transaction carries and what a rule is written in. Nothing here rounds anything, and the decimals
 * the publisher stated are used to *show* an amount and never to compare one.
 */
object SwapParameterNames {
    /** How much of the input mint to spend, in its base units. */
    val INPUT_AMOUNT: ParameterKey = ParameterKey("input_amount")

    /** How far below the quote the owner will still accept, in basis points. */
    val SLIPPAGE_BPS: ParameterKey = ParameterKey("slippage_bps")
}

/**
 * A sensible slippage to start from, in basis points: half a percent.
 *
 * It is a starting point and not a recommendation. A publisher's ceiling below it wins, because a
 * ceiling is the publisher's statement about its own signal and this is only the app's opening
 * suggestion.
 */
const val USUAL_SLIPPAGE_BPS: Int = 50

/** What [JupiterSwapPlugin] asks the owner for, given what the publisher said. */
fun swapParameters(terms: SwapTerms): ParameterForm =
    ParameterForm(
        listOf(
            ParameterField(
                key = SwapParameterNames.INPUT_AMOUNT,
                label = R.string.jupiter_amount_label,
                kind =
                    ParameterKind.Amount(
                        // Native SOL is the wrapped mint in a swap's terms, and it is native
                        // again in the field: what the owner holds and spends is SOL, and the
                        // wrapping is the provider's business inside the transaction.
                        mint = terms.inputMint.takeIf { it != WRAPPED_SOL },
                        decimals = terms.inputDecimals,
                        most = terms.mostInput,
                        least = terms.leastInput,
                    ),
            ),
            ParameterField(
                key = SwapParameterNames.SLIPPAGE_BPS,
                label = R.string.jupiter_slippage_label,
                kind =
                    ParameterKind.Count(
                        // One basis point is the tightest anything can be asked for; zero would
                        // be a quote that has to hold exactly, which no market offers.
                        least = 1U,
                        most = terms.maxSlippageBps.toUInt(),
                        initial = minOf(USUAL_SLIPPAGE_BPS, terms.maxSlippageBps).toUInt(),
                    ),
            ),
        )
    )

/** What the owner chose, once it has been read back and checked against the publisher's bounds. */
data class SwapChoice(val amount: ULong, val slippageBps: Int)

/** Why what was chosen cannot be acted on. */
enum class SwapChoiceProblem(val code: String) {
    /** No amount was entered yet. */
    NoAmount("no_amount"),
    /** Zero, or below the publisher's floor: there is nothing to swap. */
    TooLittle("too_little"),
    /** Above the publisher's ceiling for its own signal. */
    TooMuch("too_much"),
    /** A slippage outside the publisher's ceiling, or none chosen. */
    BadSlippage("bad_slippage"),
}

sealed interface SwapChoiceResult {
    data class Valid(val choice: SwapChoice) : SwapChoiceResult

    data class Invalid(val problem: SwapChoiceProblem) : SwapChoiceResult
}

/**
 * Reads [choice] against [terms], or says why it cannot be acted on.
 *
 * This is where the publisher's bounds are enforced, and they are enforced on the owner's own side
 * of the boundary: the screen can offer whatever it likes, and nothing is prepared unless the
 * numbers are within what the publisher actually published.
 */
fun swapChoiceFrom(terms: SwapTerms, choice: ParameterChoice): SwapChoiceResult {
    val amount =
        (choice[SwapParameterNames.INPUT_AMOUNT] as? ParameterValue.Amount)?.baseUnits
            ?: return SwapChoiceResult.Invalid(SwapChoiceProblem.NoAmount)
    if (amount == 0UL || amount < terms.leastInput) {
        return SwapChoiceResult.Invalid(SwapChoiceProblem.TooLittle)
    }
    terms.mostInput?.let {
        if (amount > it) return SwapChoiceResult.Invalid(SwapChoiceProblem.TooMuch)
    }
    val slippage =
        (choice[SwapParameterNames.SLIPPAGE_BPS] as? ParameterValue.Count)?.value?.toInt()
            ?: return SwapChoiceResult.Invalid(SwapChoiceProblem.BadSlippage)
    if (slippage < 1 || slippage > terms.maxSlippageBps) {
        return SwapChoiceResult.Invalid(SwapChoiceProblem.BadSlippage)
    }
    return SwapChoiceResult.Valid(SwapChoice(amount, slippage))
}
