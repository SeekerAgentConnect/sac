package io.github.brrenat.seekervault.plugins.actions

import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionCapability
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterField
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterOption
import io.github.brrenat.seekervault.plugins.ParameterValue

/**
 * The half of a prediction order that is the owner's (SEE-94).
 *
 * Two things, and the publisher supplies neither: **which side** and **how much**. A signal says
 * which market; whether this owner thinks yes or no, and what they are prepared to stake on it, is
 * theirs, stays on their phone, and differs between two people who received the same document
 * (docs/wiki/shared-proposals.md#what-stays-on-the-phone).
 *
 * There is deliberately no default side. An amount has no suggestion for the same reason a swap's
 * does not — it is the owner's money — and a side has none because suggesting one would be the app
 * expressing an opinion about a market, which it has no business doing and no basis for.
 */
object PredictionParameterNames {
    /** Which side of the market to buy. */
    val OUTCOME: ParameterKey = ParameterKey("outcome")

    /** How much to stake, in the deposit mint's base units. */
    val DEPOSIT: ParameterKey = ParameterKey("deposit_amount")
}

/** The two sides a market has. Their keys are what a record keeps, so they are stable. */
object PredictionOutcomes {
    val YES: ParameterKey = ParameterKey("yes")
    val NO: ParameterKey = ParameterKey("no")
}

/**
 * What the owner has to choose for a prediction order, given the market the publisher named and
 * what the venue serving it will accept.
 *
 * The venue's smallest order is folded into the floor here. It used to be a constant inside the
 * payload reader, which quietly made "the smallest order Jupiter accepts" part of what the *action*
 * means; SEE-145 moved it to where it is true — the capability of the provider that would place it.
 */
fun predictionBuyInputs(payload: PredictionPayload, capability: ActionCapability): ParameterForm =
    ParameterForm(
        listOf(
            ParameterField(
                key = PredictionParameterNames.OUTCOME,
                label = R.string.action_prediction_outcome_label,
                kind =
                    ParameterKind.Choice(
                        listOf(
                            ParameterOption(
                                PredictionOutcomes.YES,
                                R.string.action_prediction_outcome_yes,
                            ),
                            ParameterOption(
                                PredictionOutcomes.NO,
                                R.string.action_prediction_outcome_no,
                            ),
                        )
                    ),
            ),
            ParameterField(
                key = PredictionParameterNames.DEPOSIT,
                label = R.string.action_prediction_deposit_label,
                kind =
                    ParameterKind.Amount(
                        mint = payload.depositMint,
                        decimals = payload.depositDecimals,
                        most = leastOf(payload.mostDeposit, capability.mostDeposit),
                        least = maxOf(payload.leastDeposit, capability.leastDeposit),
                    ),
            ),
        )
    )

/** What the owner chose, once it has been read back and checked against what was published. */
data class PredictionChoice(val yes: Boolean, val deposit: ULong)

/** Why what was chosen cannot be acted on. */
enum class PredictionChoiceProblem(val code: String) {
    /** No side has been picked. There is no default: a market has two answers and no third. */
    NoOutcome("no_outcome"),
    /** A side that is neither of the market's two. */
    BadOutcome("bad_outcome"),
    NoDeposit("no_deposit"),
    /** Below the publisher's floor, or below the least the venue will accept. */
    TooLittle("too_little"),
    /** Above the ceiling one of them set. */
    TooMuch("too_much"),
}

/** The owner-facing words for each. */
val PredictionChoiceProblem.message: Int
    get() =
        when (this) {
            PredictionChoiceProblem.NoOutcome -> R.string.action_choice_no_outcome
            PredictionChoiceProblem.BadOutcome -> R.string.action_choice_bad_outcome
            PredictionChoiceProblem.NoDeposit -> R.string.action_choice_no_amount
            PredictionChoiceProblem.TooLittle -> R.string.action_choice_stake_too_little
            PredictionChoiceProblem.TooMuch -> R.string.action_choice_too_much
        }

sealed interface PredictionChoiceResult {
    data class Valid(val choice: PredictionChoice) : PredictionChoiceResult

    data class Invalid(val problem: PredictionChoiceProblem) : PredictionChoiceResult
}

/**
 * Reads [choice] against [payload] and [capability], or says why it cannot be acted on.
 *
 * Both bounds are enforced here, on the owner's own side of the boundary: the publisher's, and the
 * venue's own minimum order. The screen may offer whatever it likes; nothing is prepared unless the
 * numbers are within both.
 */
fun predictionChoiceFrom(
    payload: PredictionPayload,
    capability: ActionCapability,
    choice: ParameterChoice,
): PredictionChoiceResult {
    val selected =
        (choice[PredictionParameterNames.OUTCOME] as? ParameterValue.Selected)?.option
            ?: return PredictionChoiceResult.Invalid(PredictionChoiceProblem.NoOutcome)
    val yes =
        when (selected) {
            PredictionOutcomes.YES -> true
            PredictionOutcomes.NO -> false
            else -> return PredictionChoiceResult.Invalid(PredictionChoiceProblem.BadOutcome)
        }
    val deposit =
        (choice[PredictionParameterNames.DEPOSIT] as? ParameterValue.Amount)?.baseUnits
            ?: return PredictionChoiceResult.Invalid(PredictionChoiceProblem.NoDeposit)
    if (deposit < maxOf(payload.leastDeposit, capability.leastDeposit)) {
        return PredictionChoiceResult.Invalid(PredictionChoiceProblem.TooLittle)
    }
    leastOf(payload.mostDeposit, capability.mostDeposit)?.let {
        if (deposit > it) return PredictionChoiceResult.Invalid(PredictionChoiceProblem.TooMuch)
    }
    return PredictionChoiceResult.Valid(PredictionChoice(yes, deposit))
}
