package io.github.brrenat.seekervault.plugins.actions

import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionCapability
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

/**
 * What the owner has to choose for a swap, given what the publisher published and what the provider
 * serving it says it will accept.
 *
 * The two bounds are folded together here rather than argued about later: the publisher's floor and
 * the venue's floor are both floors, and the owner is shown the one that actually applies rather
 * than an amount that would be refused after they entered it (SEE-145).
 */
fun swapInputs(payload: SwapPayload, capability: ActionCapability): ParameterForm =
    ParameterForm(
        listOf(
            ParameterField(
                key = SwapParameterNames.INPUT_AMOUNT,
                label = R.string.action_swap_amount_label,
                kind =
                    ParameterKind.Amount(
                        // Native SOL is the wrapped mint in a swap's terms, and it is native
                        // again in the field: what the owner holds and spends is SOL, and the
                        // wrapping is the provider's business inside the transaction.
                        mint = payload.inputMint.takeIf { it != WRAPPED_SOL },
                        decimals = payload.inputDecimals,
                        most = leastOf(payload.mostInput, capability.mostDeposit),
                        least = maxOf(payload.leastInput, capability.leastDeposit),
                    ),
            ),
            ParameterField(
                key = SwapParameterNames.SLIPPAGE_BPS,
                label = R.string.action_swap_slippage_label,
                kind =
                    ParameterKind.Count(
                        // One basis point is the tightest anything can be asked for; zero would
                        // be a quote that has to hold exactly, which no market offers.
                        least = 1U,
                        most = payload.maxSlippageBps.toUInt(),
                        initial = minOf(USUAL_SLIPPAGE_BPS, payload.maxSlippageBps).toUInt(),
                    ),
            ),
        )
    )

/** Two ceilings, whichever binds; null only when neither of them set one. */
internal fun leastOf(one: ULong?, other: ULong?): ULong? =
    when {
        one == null -> other
        other == null -> one
        else -> minOf(one, other)
    }

/** What the owner chose, once it has been read back and checked against the bounds. */
data class SwapChoice(val amount: ULong, val slippageBps: Int)

/** Why what was chosen cannot be acted on. */
enum class SwapChoiceProblem(val code: String) {
    /** No amount was entered yet. */
    NoAmount("no_amount"),
    /** Zero, or below the floor the publisher or the provider set: there is nothing to swap. */
    TooLittle("too_little"),
    /** Above the ceiling one of them set for it. */
    TooMuch("too_much"),
    /** A slippage outside the publisher's ceiling, or none chosen. */
    BadSlippage("bad_slippage"),
}

/** The owner-facing words for each. */
val SwapChoiceProblem.message: Int
    get() =
        when (this) {
            SwapChoiceProblem.NoAmount -> R.string.action_choice_no_amount
            SwapChoiceProblem.TooLittle -> R.string.action_choice_too_little
            SwapChoiceProblem.TooMuch -> R.string.action_choice_too_much
            SwapChoiceProblem.BadSlippage -> R.string.action_choice_bad_slippage
        }

sealed interface SwapChoiceResult {
    data class Valid(val choice: SwapChoice) : SwapChoiceResult

    data class Invalid(val problem: SwapChoiceProblem) : SwapChoiceResult
}

/**
 * Reads [choice] against [payload] and [capability], or says why it cannot be acted on.
 *
 * This is where both sets of bounds are enforced, and they are enforced on the owner's own side of
 * the boundary: the screen can offer whatever it likes, and nothing is prepared unless the numbers
 * are within what the publisher published and what the venue accepts.
 */
fun swapChoiceFrom(
    payload: SwapPayload,
    capability: ActionCapability,
    choice: ParameterChoice,
): SwapChoiceResult {
    val amount =
        (choice[SwapParameterNames.INPUT_AMOUNT] as? ParameterValue.Amount)?.baseUnits
            ?: return SwapChoiceResult.Invalid(SwapChoiceProblem.NoAmount)
    if (amount == 0UL || amount < maxOf(payload.leastInput, capability.leastDeposit)) {
        return SwapChoiceResult.Invalid(SwapChoiceProblem.TooLittle)
    }
    leastOf(payload.mostInput, capability.mostDeposit)?.let {
        if (amount > it) return SwapChoiceResult.Invalid(SwapChoiceProblem.TooMuch)
    }
    val slippage =
        (choice[SwapParameterNames.SLIPPAGE_BPS] as? ParameterValue.Count)?.value?.toInt()
            ?: return SwapChoiceResult.Invalid(SwapChoiceProblem.BadSlippage)
    if (slippage < 1 || slippage > payload.maxSlippageBps) {
        return SwapChoiceResult.Invalid(SwapChoiceProblem.BadSlippage)
    }
    return SwapChoiceResult.Valid(SwapChoice(amount, slippage))
}
