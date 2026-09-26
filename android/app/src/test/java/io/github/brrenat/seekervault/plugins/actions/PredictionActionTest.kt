package io.github.brrenat.seekervault.plugins.actions

import io.github.brrenat.seekervault.jupiter.EVENT_ID
import io.github.brrenat.seekervault.jupiter.JUP_MINT
import io.github.brrenat.seekervault.jupiter.JUP_USD_MINT
import io.github.brrenat.seekervault.jupiter.LEAST_ORDER_DEPOSIT
import io.github.brrenat.seekervault.jupiter.MARKET_ID
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.plugins.ActionCapability
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_SCHEMA_VERSION
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.request.v1.Network
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
class PredictionActionTest {
    /**
     * A venue that settles in one token and will not place an order under five dollars.
     *
     * Both facts are the *venue's* and neither is the action's, which is what SEE-145 separated:
     * the reader below accepts any well-formed mint, and it is this capability that says which ones
     * are actually acceptable. What the registry does with that is `ProviderRegistryTest`'s.
     */
    private val venue =
        ActionCapability(
            action = PREDICTION_BUY_ACTION,
            schemaVersions = PREDICTION_BUY_SCHEMA_VERSION..PREDICTION_BUY_SCHEMA_VERSION,
            networks = setOf(Network.NETWORK_MAINNET),
            depositAssets = setOf(USDC_MINT, JUP_USD_MINT),
            leastDeposit = LEAST_ORDER_DEPOSIT,
        )

    private val sound =
        arrayOf(
            PredictionTermNames.MARKET_ID to MARKET_ID,
            PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
            PredictionTermNames.DEPOSIT_DECIMALS to "6",
        )

    private fun valid(vararg extra: Pair<String, String>): PredictionPayload =
        (predictionPayloadFrom(mapOf(*sound, *extra)) as PredictionPayloadResult.Valid).payload

    private fun problem(
        vararg pairs: Pair<String, String>
    ): Pair<PredictionPayloadProblem, String> =
        (predictionPayloadFrom(mapOf(*pairs)) as PredictionPayloadResult.Invalid).let {
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
        assertEquals("polymarket", read.marketProvider)
        assertEquals(USDC_MINT, read.depositMint)
        assertEquals(6, read.depositDecimals)
        // The publisher's own floor and nothing else. The venue's minimum is applied where the
        // venue is known, which is the whole of what SEE-145 moved out of this reader.
        assertEquals(0UL, read.leastDeposit)
        assertNull(read.mostDeposit)
    }

    @Test
    fun theVenuesMinimumIsAFloorUnderEveryPublishersOwn() {
        // A publisher asking for less than the venue accepts has asked for an order that cannot be
        // placed, so the floor the owner is shown is the higher of the two. It is applied where the
        // venue is known rather than inside the payload, which is what makes the payload the
        // action's and not Jupiter's (SEE-145).
        assertEquals(
            LEAST_ORDER_DEPOSIT,
            (predictionBuyInputs(valid(PredictionTermNames.LEAST_DEPOSIT to "1"), venue)
                    .fields[1]
                    .kind as ParameterKind.Amount)
                .least,
        )
        assertEquals(
            9_000_000UL,
            (predictionBuyInputs(
                        valid(PredictionTermNames.LEAST_DEPOSIT to "9000000"),
                        venue,
                    )
                    .fields[1]
                    .kind as ParameterKind.Amount)
                .least,
        )
    }

    @Test
    fun anyWellFormedStakeMintIsReadAndWhichOnesAreAcceptableIsTheVenuesBusiness() {
        // The reader's job is that the term names a mint at all. Whether *this* venue settles in it
        // is its capability's, and an unacceptable one is refused before anything is prepared
        // (`ProviderRegistryTest.anAssetTheVenueDoesNotSettleInIsRefusedBeforeAnythingIsPrepared`).
        for (mint in listOf(USDC_MINT, JUP_USD_MINT, JUP_MINT)) {
            assertEquals(
                mint,
                (predictionPayloadFrom(
                        mapOf(
                            PredictionTermNames.MARKET_ID to MARKET_ID,
                            PredictionTermNames.DEPOSIT_MINT to mint,
                            PredictionTermNames.DEPOSIT_DECIMALS to "6",
                        )
                    )
                        as PredictionPayloadResult.Valid)
                    .payload
                    .depositMint,
            )
        }
    }

    @Test
    fun carriesWhereTheProviderKeepsTheMarketWithoutBelievingWhoseItIs() {
        // A publisher may name where its provider keeps this market, because it read the venue's
        // listing and the phone did not (SEE-157). Core checks the shape and carries it; whose
        // address it is belongs to the provider's own adapter, and is not asked here.
        val read =
            valid(
                PredictionTermNames.PROVIDER_DEEP_LINK to
                    "https://jup.ag/prediction/fed-decision-in-october",
                PredictionTermNames.PROVIDER_WEB_URL to
                    "https://jup.ag/prediction/fed-decision-in-october",
            )

        assertEquals("https://jup.ag/prediction/fed-decision-in-october", read.providerDeepLink)
        assertEquals("https://jup.ag/prediction/fed-decision-in-october", read.providerWebUrl)
        // Neither is required, and a signal that names none is the ordinary signal: every
        // prediction before SEE-157 was one, and none of them regressed.
        assertEquals("", valid().providerDeepLink)
        assertEquals("", valid().providerWebUrl)
    }

    @Test
    fun refusesADestinationThatIsNotOneRatherThanCarryingIt() {
        // A term that is present and unusable stops the read: a publisher that meant to send
        // somebody somewhere and wrote something else should be told, and an owner should not be
        // shown a signal that half-named a destination.
        for (name in
            listOf(
                PredictionTermNames.PROVIDER_DEEP_LINK,
                PredictionTermNames.PROVIDER_WEB_URL,
            )) {
            for (named in
                listOf(
                    "javascript:alert(1)",
                    "file:///data/data/x",
                    "intent://scan/#Intent;scheme=zxing;end",
                    "http://jup.ag/prediction/x",
                    "jup.ag/prediction/x",
                )) {
                assertEquals(
                    "$name = $named",
                    PredictionPayloadProblem.NotALink to name,
                    problem(*sound, name to named),
                )
            }
        }
    }

    @Test
    fun eachWayASignalCanBeUnreadableIsSaidWithTheTermItIsAbout() {
        assertEquals(
            PredictionPayloadProblem.Missing to PredictionTermNames.MARKET_ID,
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
                problem == PredictionPayloadProblem.NotAnIdentifier ||
                    problem == PredictionPayloadProblem.Missing,
            )
            assertEquals(PredictionTermNames.MARKET_ID, term)
        }
        assertEquals(
            PredictionPayloadProblem.NotAMint to PredictionTermNames.DEPOSIT_MINT,
            problem(
                PredictionTermNames.MARKET_ID to MARKET_ID,
                PredictionTermNames.DEPOSIT_MINT to "USDC",
                PredictionTermNames.DEPOSIT_DECIMALS to "6",
            ),
        )
        assertEquals(
            PredictionPayloadProblem.Missing to PredictionTermNames.DEPOSIT_DECIMALS,
            problem(
                PredictionTermNames.MARKET_ID to MARKET_ID,
                PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
            ),
        )
        assertEquals(
            PredictionPayloadProblem.BadDecimals to PredictionTermNames.DEPOSIT_DECIMALS,
            problem(
                PredictionTermNames.MARKET_ID to MARKET_ID,
                PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
                PredictionTermNames.DEPOSIT_DECIMALS to "99",
            ),
        )
        assertEquals(
            PredictionPayloadProblem.BadAmount to PredictionTermNames.MOST_DEPOSIT,
            problem(*sound, PredictionTermNames.MOST_DEPOSIT to "1.5"),
        )
        assertEquals(
            PredictionPayloadProblem.ImpossibleAmounts to PredictionTermNames.MOST_DEPOSIT,
            problem(
                *sound,
                PredictionTermNames.LEAST_DEPOSIT to "9000000",
                PredictionTermNames.MOST_DEPOSIT to "8000000",
            ),
        )
        assertEquals(
            PredictionPayloadProblem.NotAnIdentifier to PredictionTermNames.EVENT_ID,
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
        val form = predictionBuyInputs(valid(PredictionTermNames.MOST_DEPOSIT to "50000000"), venue)

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
                venue,
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
