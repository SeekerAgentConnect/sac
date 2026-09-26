package io.github.brrenat.seekervault.plugins.actions

import io.github.brrenat.seekervault.jupiter.JUP_MINT
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.jupiter.usdcTerms
import io.github.brrenat.seekervault.plugins.ActionCapability
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.plugins.SWAP_SCHEMA_VERSION
import io.github.brrenat.seekervault.request.v1.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a publisher has to say for a swap to be readable, and what the owner may choose (SEE-93).
 *
 * A publisher is a stranger: a server this phone holds no credential for, whose document arrived
 * through a gateway shared with everybody. So every rule here is about refusing one without being
 * confused by it, and the first of them is the one that matters most — an asset is a mint address,
 * never a ticker.
 */
class SwapActionTest {
    /** A venue that takes any pair, with no floor and no ceiling of its own — as Jupiter does. */
    private val venue =
        ActionCapability(
            action = SWAP_ACTION,
            schemaVersions = SWAP_SCHEMA_VERSION..SWAP_SCHEMA_VERSION,
            networks = setOf(Network.NETWORK_MAINNET),
        )

    private fun terms(vararg pairs: Pair<String, String>): SwapPayloadResult =
        swapPayloadFrom(mapOf(*pairs))

    private val sound =
        arrayOf(
            SwapTermNames.INPUT_MINT to USDC_MINT,
            SwapTermNames.INPUT_DECIMALS to "6",
            SwapTermNames.OUTPUT_MINT to SOL_MINT,
            SwapTermNames.OUTPUT_DECIMALS to "9",
            SwapTermNames.MAX_SLIPPAGE_BPS to "100",
        )

    private fun valid(vararg extra: Pair<String, String>): SwapPayload =
        (terms(*sound, *extra) as SwapPayloadResult.Valid).payload

    private fun problem(vararg pairs: Pair<String, String>): Pair<SwapPayloadProblem, String> =
        (terms(*pairs) as SwapPayloadResult.Invalid).let { it.problem to it.term }

    @Test
    fun aSignalNamesItsAssetsByMintAndItsOwnCeiling() {
        val read = valid(SwapTermNames.INPUT_SYMBOL to "USDC", SwapTermNames.OUTPUT_SYMBOL to "SOL")

        assertEquals(USDC_MINT, read.inputMint)
        assertEquals(SOL_MINT, read.outputMint)
        assertEquals(6, read.inputDecimals)
        assertEquals(9, read.outputDecimals)
        assertEquals(100, read.maxSlippageBps)
        assertEquals("USDC", read.inputSymbol)
        // Bounds the publisher did not set are absent rather than zero: "no ceiling" and "a
        // ceiling of nothing" are opposite statements.
        assertEquals(0UL, read.leastInput)
        assertNull(read.mostInput)
    }

    @Test
    fun anAssetNamedByAnythingButItsMintIsRefused() {
        // The whole reason this rule exists. "BTC" names a dozen things on this chain and nothing
        // at all off it, so a signal that will not name a mint has not said what it proposes.
        for (named in listOf("BTC", "bitcoin", "SOL", "", "  ", "So1111111111111111111111111111")) {
            val (problem, term) =
                problem(
                    SwapTermNames.INPUT_MINT to named,
                    SwapTermNames.INPUT_DECIMALS to "8",
                    SwapTermNames.OUTPUT_MINT to USDC_MINT,
                    SwapTermNames.OUTPUT_DECIMALS to "6",
                    SwapTermNames.MAX_SLIPPAGE_BPS to "50",
                )
            assertTrue(
                "$named: $problem",
                problem == SwapPayloadProblem.NotAMint || problem == SwapPayloadProblem.Missing,
            )
            assertEquals(SwapTermNames.INPUT_MINT, term)
        }
    }

    @Test
    fun eachWayASignalCanBeUnreadableIsSaidWithTheTermItIsAbout() {
        assertEquals(
            SwapPayloadProblem.Missing to SwapTermNames.INPUT_MINT,
            problem(SwapTermNames.OUTPUT_MINT to SOL_MINT),
        )
        assertEquals(
            SwapPayloadProblem.OneAsset to SwapTermNames.OUTPUT_MINT,
            problem(
                SwapTermNames.INPUT_MINT to USDC_MINT,
                SwapTermNames.OUTPUT_MINT to USDC_MINT,
            ),
        )
        // Absent and unreadable are told apart, because they are different things to show someone.
        assertEquals(
            SwapPayloadProblem.Missing to SwapTermNames.INPUT_DECIMALS,
            problem(
                SwapTermNames.INPUT_MINT to USDC_MINT,
                SwapTermNames.OUTPUT_MINT to SOL_MINT,
            ),
        )
        assertEquals(
            SwapPayloadProblem.BadDecimals to SwapTermNames.INPUT_DECIMALS,
            problem(
                SwapTermNames.INPUT_MINT to USDC_MINT,
                SwapTermNames.INPUT_DECIMALS to "99",
                SwapTermNames.OUTPUT_MINT to SOL_MINT,
            ),
        )
        for (slippage in listOf("0", "10001", "-5", "half")) {
            assertEquals(
                slippage,
                SwapPayloadProblem.BadSlippage to SwapTermNames.MAX_SLIPPAGE_BPS,
                problem(
                    SwapTermNames.INPUT_MINT to USDC_MINT,
                    SwapTermNames.INPUT_DECIMALS to "6",
                    SwapTermNames.OUTPUT_MINT to SOL_MINT,
                    SwapTermNames.OUTPUT_DECIMALS to "9",
                    SwapTermNames.MAX_SLIPPAGE_BPS to slippage,
                ),
            )
        }
        assertEquals(
            SwapPayloadProblem.BadAmount to SwapTermNames.LEAST_INPUT,
            problem(*sound, SwapTermNames.LEAST_INPUT to "1.5"),
        )
        assertEquals(
            SwapPayloadProblem.ImpossibleAmounts to SwapTermNames.MOST_INPUT,
            problem(
                *sound,
                SwapTermNames.LEAST_INPUT to "100",
                SwapTermNames.MOST_INPUT to "10",
            ),
        )
        assertEquals(
            SwapPayloadProblem.BadSymbol to SwapTermNames.OUTPUT_SYMBOL,
            problem(*sound, SwapTermNames.OUTPUT_SYMBOL to "S".repeat(17)),
        )
    }

    @Test
    fun atermThisPluginDoesNotKnowChangesNothing() {
        // A publisher may say more than this plugin reads, and the extra is not an error. It is
        // also never consulted, so nothing in it can change what would be prepared.
        val read = valid("venue" to "somewhere", "urgency" to "high", "input_mint_hint" to JUP_MINT)

        assertEquals(USDC_MINT, read.inputMint)
        assertEquals(SOL_MINT, read.outputMint)
    }

    @Test
    fun theOwnerIsAskedForTheAmountAndTheSlippageWithinWhatTheSignalAllows() {
        val read = valid(SwapTermNames.LEAST_INPUT to "1000", SwapTermNames.MOST_INPUT to "9000")
        val form = swapInputs(read, venue)

        assertEquals(
            listOf(SwapParameterNames.INPUT_AMOUNT, SwapParameterNames.SLIPPAGE_BPS),
            form.fields.map { it.key },
        )
        assertNull(form.problem)
        val amount = form.fields[0].kind as ParameterKind.Amount
        assertEquals(USDC_MINT, amount.mint)
        assertEquals(6, amount.decimals)
        assertEquals(1000UL, amount.least)
        assertEquals(9000UL, amount.most)
        val slippage = form.fields[1].kind as ParameterKind.Count
        assertEquals(1U, slippage.least)
        assertEquals(100U, slippage.most)
        // The app suggests a usual figure, and a publisher's tighter ceiling wins over it.
        assertEquals(50U, slippage.initial)
        assertEquals(
            25U,
            (swapInputs(valid(SwapTermNames.MAX_SLIPPAGE_BPS to "25"), venue).fields[1].kind
                    as ParameterKind.Count)
                .initial,
        )
    }

    @Test
    fun nativeSolIsAskedForAsSolEvenThoughASignalNamesTheWrappedMint() {
        // The terms name the mint that moves, because that is what a pool takes. What the owner
        // holds and spends is SOL, and the field says so.
        val form = swapInputs(usdcTerms(inputMint = SOL_MINT, outputMint = USDC_MINT), venue)

        assertNull((form.fields[0].kind as ParameterKind.Amount).mint)
    }

    @Test
    fun everyWayAChoiceCanBeOutsideWhatWasPublishedIsRefused() {
        val read = valid(SwapTermNames.LEAST_INPUT to "1000", SwapTermNames.MOST_INPUT to "9000")

        fun chose(amount: ULong?, slippage: Int?): SwapChoiceResult =
            swapChoiceFrom(
                read,
                venue,
                ParameterChoice(
                    buildMap {
                        amount?.let {
                            put(SwapParameterNames.INPUT_AMOUNT, ParameterValue.Amount(it))
                        }
                        slippage?.let {
                            put(SwapParameterNames.SLIPPAGE_BPS, ParameterValue.Count(it.toUInt()))
                        }
                    }
                ),
            )

        assertEquals(
            SwapChoice(5000UL, 30),
            (chose(5000UL, 30) as SwapChoiceResult.Valid).choice,
        )
        assertEquals(
            SwapChoiceProblem.NoAmount,
            (chose(null, 30) as SwapChoiceResult.Invalid).problem,
        )
        assertEquals(
            SwapChoiceProblem.TooLittle,
            (chose(999UL, 30) as SwapChoiceResult.Invalid).problem,
        )
        assertEquals(
            SwapChoiceProblem.TooLittle,
            (chose(0UL, 30) as SwapChoiceResult.Invalid).problem,
        )
        assertEquals(
            SwapChoiceProblem.TooMuch,
            (chose(9001UL, 30) as SwapChoiceResult.Invalid).problem,
        )
        assertEquals(
            SwapChoiceProblem.BadSlippage,
            (chose(5000UL, null) as SwapChoiceResult.Invalid).problem,
        )
        // Above the publisher's own ceiling. Nothing on this side raises it.
        assertEquals(
            SwapChoiceProblem.BadSlippage,
            (chose(5000UL, 101) as SwapChoiceResult.Invalid).problem,
        )
        assertEquals(
            SwapChoiceProblem.BadSlippage,
            (chose(5000UL, 0) as SwapChoiceResult.Invalid).problem,
        )
    }
}
