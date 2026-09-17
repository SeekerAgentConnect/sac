package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.R
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

/** What [JupiterPredictionPlugin] asks the owner for, given the market the publisher named. */
fun predictionParameters(terms: PredictionTerms): ParameterForm =
    ParameterForm(
        listOf(
            ParameterField(
                key = PredictionParameterNames.OUTCOME,
                label = R.string.jupiter_outcome_label,
                kind =
                    ParameterKind.Choice(
                        listOf(
                            ParameterOption(PredictionOutcomes.YES, R.string.jupiter_outcome_yes),
                            ParameterOption(PredictionOutcomes.NO, R.string.jupiter_outcome_no),
                        )
                    ),
            ),
            ParameterField(
                key = PredictionParameterNames.DEPOSIT,
                label = R.string.jupiter_deposit_label,
                kind =
                    ParameterKind.Amount(
                        mint = terms.depositMint,
                        decimals = terms.depositDecimals,
                        most = terms.mostDeposit,
                        least = terms.leastDeposit,
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
    /** Below the publisher's floor, or below the least the provider will accept. */
    TooLittle("too_little"),
    /** Above the publisher's ceiling. */
    TooMuch("too_much"),
}

sealed interface PredictionChoiceResult {
    data class Valid(val choice: PredictionChoice) : PredictionChoiceResult

    data class Invalid(val problem: PredictionChoiceProblem) : PredictionChoiceResult
}

/**
 * Reads [choice] against [terms], or says why it cannot be acted on.
 *
 * Both bounds are enforced here, on the owner's own side of the boundary: the publisher's, and the
 * provider's five-dollar minimum, which [PredictionTerms] has already folded into the floor. The
 * screen may offer whatever it likes; nothing is prepared unless the numbers are within both.
 */
fun predictionChoiceFrom(
    terms: PredictionTerms,
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
    if (deposit < terms.leastDeposit) {
        return PredictionChoiceResult.Invalid(PredictionChoiceProblem.TooLittle)
    }
    terms.mostDeposit?.let {
        if (deposit > it) return PredictionChoiceResult.Invalid(PredictionChoiceProblem.TooMuch)
    }
    return PredictionChoiceResult.Valid(PredictionChoice(yes, deposit))
}
