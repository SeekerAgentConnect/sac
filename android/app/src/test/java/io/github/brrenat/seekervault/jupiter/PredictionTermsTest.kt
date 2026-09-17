package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a publisher has to say for a market to be readable, and what the owner may choose (SEE-94).
 *
 * The division is the point of the whole payload: a publisher names **which market**, and every
 * other fact about it — open or closed, what the sides cost, when it settles — comes from the
 * provider when the owner looks. So these tests are mostly about what is *not* accepted from a
 * publisher.
 */
class PredictionTermsTest {
    private val sound =
        arrayOf(
            PredictionTermNames.MARKET_ID to MARKET_ID,
            PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
            PredictionTermNames.DEPOSIT_DECIMALS to "6",
        )

    private fun valid(vararg extra: Pair<String, String>): PredictionTerms =
        (predictionTermsFrom(mapOf(*sound, *extra)) as PredictionTermsResult.Valid).terms

    private fun problem(vararg pairs: Pair<String, String>): Pair<PredictionTermProblem, String> =
        (predictionTermsFrom(mapOf(*pairs)) as PredictionTermsResult.Invalid).let {
            it.problem to it.term
        }

    @Test
    fun aSignalNamesAMarketAndAStakeTokenAndLittleElse() {
        val read =
            valid(
                PredictionTermNames.EVENT_ID to EVENT_ID,
                PredictionTermNames.PROVIDER to "polymarket",
                PredictionTermNames.DEPOSIT_SYMBOL to "USDC",
            )

        assertEquals(MARKET_ID, read.marketId)
        assertEquals(EVENT_ID, read.eventId)
        assertEquals("polymarket", read.provider)
        assertEquals(USDC_MINT, read.depositMint)
        assertEquals(6, read.depositDecimals)
        // The provider's own minimum is folded into the floor, so an amount too small to act on is
        // refused before anything is asked of anybody.
        assertEquals(LEAST_ORDER_DEPOSIT, read.leastDeposit)
        assertNull(read.mostDeposit)
    }

    @Test
    fun theProvidersMinimumIsAFloorUnderEveryPublishersOwn() {
        // A publisher asking for less than the provider accepts has asked for an order that cannot
        // be placed, so the floor is the higher of the two rather than the publisher's.
        assertEquals(
            LEAST_ORDER_DEPOSIT,
            valid(PredictionTermNames.LEAST_DEPOSIT to "1").leastDeposit,
        )
        assertEquals(
            9_000_000UL,
            valid(PredictionTermNames.LEAST_DEPOSIT to "9000000").leastDeposit,
        )
    }

    @Test
    fun aStakeTokenTheProviderDoesNotTakeIsRefused() {
        // Not a validation flourish: a publisher naming something else is naming a token the
        // provider will not accept, and the honest moment to say so is when the signal is read.
        assertEquals(
            PredictionTermProblem.UnsupportedMint to PredictionTermNames.DEPOSIT_MINT,
            problem(
                PredictionTermNames.MARKET_ID to MARKET_ID,
                PredictionTermNames.DEPOSIT_MINT to JUP_MINT,
                PredictionTermNames.DEPOSIT_DECIMALS to "6",
            ),
        )
        // And both the ones it does take are read.
        assertEquals(
            JUP_USD_MINT,
            (predictionTermsFrom(
                    mapOf(
                        PredictionTermNames.MARKET_ID to MARKET_ID,
                        PredictionTermNames.DEPOSIT_MINT to JUP_USD_MINT,
                        PredictionTermNames.DEPOSIT_DECIMALS to "6",
                    )
                )
                    as PredictionTermsResult.Valid)
                .terms
                .depositMint,
        )
    }

    @Test
    fun eachWayASignalCanBeUnreadableIsSaidWithTheTermItIsAbout() {
        assertEquals(
            PredictionTermProblem.Missing to PredictionTermNames.MARKET_ID,
            problem(PredictionTermNames.DEPOSIT_MINT to USDC_MINT),
        )
        for (named in listOf("https://jup.ag/prediction/x", "a market", "", "x".repeat(65))) {
            val (problem, term) =
                problem(
                    PredictionTermNames.MARKET_ID to named,
                    PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
                    PredictionTermNames.DEPOSIT_DECIMALS to "6",
                )
            assertTrue(
                "$named: $problem",
                problem == PredictionTermProblem.NotAnIdentifier ||
                    problem == PredictionTermProblem.Missing,
            )
            assertEquals(PredictionTermNames.MARKET_ID, term)
        }
        assertEquals(
            PredictionTermProblem.NotAMint to PredictionTermNames.DEPOSIT_MINT,
            problem(
                PredictionTermNames.MARKET_ID to MARKET_ID,
                PredictionTermNames.DEPOSIT_MINT to "USDC",
                PredictionTermNames.DEPOSIT_DECIMALS to "6",
            ),
        )
        assertEquals(
            PredictionTermProblem.Missing to PredictionTermNames.DEPOSIT_DECIMALS,
            problem(
                PredictionTermNames.MARKET_ID to MARKET_ID,
                PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
            ),
        )
        assertEquals(
            PredictionTermProblem.BadDecimals to PredictionTermNames.DEPOSIT_DECIMALS,
            problem(
                PredictionTermNames.MARKET_ID to MARKET_ID,
                PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
                PredictionTermNames.DEPOSIT_DECIMALS to "99",
            ),
        )
        assertEquals(
            PredictionTermProblem.BadAmount to PredictionTermNames.MOST_DEPOSIT,
            problem(*sound, PredictionTermNames.MOST_DEPOSIT to "1.5"),
        )
        assertEquals(
            PredictionTermProblem.ImpossibleAmounts to PredictionTermNames.MOST_DEPOSIT,
            problem(
                *sound,
                PredictionTermNames.LEAST_DEPOSIT to "9000000",
                PredictionTermNames.MOST_DEPOSIT to "8000000",
            ),
        )
        assertEquals(
            PredictionTermProblem.NotAnIdentifier to PredictionTermNames.EVENT_ID,
            problem(*sound, PredictionTermNames.EVENT_ID to "an event"),
        )
    }

    @Test
    fun atermThisPluginDoesNotKnowChangesNothing() {
        val read =
            valid(
                "confidence" to "high",
                "publisher_price" to "0.2",
                "market_id_hint" to "POLY-999",
            )

        assertEquals(MARKET_ID, read.marketId)
    }

    @Test
    fun theOwnerIsAskedForASideAndAStakeAndIsSuggestedNeither() {
        val form = predictionParameters(valid(PredictionTermNames.MOST_DEPOSIT to "50000000"))

        assertEquals(
            listOf(PredictionParameterNames.OUTCOME, PredictionParameterNames.DEPOSIT),
            form.fields.map { it.key },
        )
        assertNull(form.problem)
        val outcome = form.fields[0].kind as ParameterKind.Choice
        assertEquals(
            listOf(PredictionOutcomes.YES, PredictionOutcomes.NO),
            outcome.options.map { it.key },
        )
        val stake = form.fields[1].kind as ParameterKind.Amount
        assertEquals(USDC_MINT, stake.mint)
        assertEquals(LEAST_ORDER_DEPOSIT, stake.least)
        assertEquals(50_000_000UL, stake.most)
    }

    @Test
    fun everyWayAChoiceCanBeOutsideWhatWasPublishedIsRefused() {
        val read = valid(PredictionTermNames.MOST_DEPOSIT to "50000000")

        fun chose(side: io.github.brrenat.seekervault.plugins.ParameterKey?, stake: ULong?) =
            predictionChoiceFrom(
                read,
                ParameterChoice(
                    buildMap {
                        side?.let {
                            put(PredictionParameterNames.OUTCOME, ParameterValue.Selected(it))
                        }
                        stake?.let {
                            put(PredictionParameterNames.DEPOSIT, ParameterValue.Amount(it))
                        }
                    }
                ),
            )

        assertEquals(
            PredictionChoice(yes = false, deposit = 6_000_000UL),
            (chose(PredictionOutcomes.NO, 6_000_000UL) as PredictionChoiceResult.Valid).choice,
        )
        // There is no default side: a market has two answers and no third, and guessing one would
        // be the app having an opinion about a market.
        assertEquals(
            PredictionChoiceProblem.NoOutcome,
            (chose(null, 6_000_000UL) as PredictionChoiceResult.Invalid).problem,
        )
        assertEquals(
            PredictionChoiceProblem.BadOutcome,
            (chose(io.github.brrenat.seekervault.plugins.ParameterKey("maybe"), 6_000_000UL)
                    as PredictionChoiceResult.Invalid)
                .problem,
        )
        assertEquals(
            PredictionChoiceProblem.NoDeposit,
            (chose(PredictionOutcomes.YES, null) as PredictionChoiceResult.Invalid).problem,
        )
        assertEquals(
            PredictionChoiceProblem.TooLittle,
            (chose(PredictionOutcomes.YES, 4_999_999UL) as PredictionChoiceResult.Invalid).problem,
        )
        assertEquals(
            PredictionChoiceProblem.TooMuch,
            (chose(PredictionOutcomes.YES, 50_000_001UL) as PredictionChoiceResult.Invalid).problem,
        )
    }
}
