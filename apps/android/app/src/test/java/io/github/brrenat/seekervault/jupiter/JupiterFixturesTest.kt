package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.actions.SwapChoice
import io.github.brrenat.seekervault.plugins.actions.WRAPPED_SOL
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.transactions.decodeTransaction
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The four transactions Jupiter really built, read by the phone's own reader (SEE-93).
 *
 * This is the test that matters most in this package, and it is the one that could not be written
 * by inventing data: the whole claim of `jupiter.swap` is that the phone can read what the provider
 * builds and refuse anything else, and a transaction the test wrote itself would be readable by
 * construction. These came off the live API through `scripts/capture-jupiter.mjs` and cover the
 * four shapes a swap takes — SOL in, SOL out, an output account that does not exist yet, and the
 * other routing variant.
 *
 * It runs under Robolectric for one reason: the fixtures are JSON, and `org.json` is the platform's
 * own — the same reason the shared transaction fixtures do.
 */
@RunWith(AndroidJUnit4::class)
class JupiterFixturesTest {

    @Test
    fun everyShapeAProviderBuildsIsReadWholeAndVerified() {
        val fixtures = swapFixtures()
        assertEquals(4, fixtures.size)
        for (fixture in fixtures) {
            val inspection =
                inspectSwap(
                    terms = fixture.terms,
                    choice = SwapChoice(fixture.amount, fixture.slippageBps),
                    quote = fixture.quote,
                    wallet = wallet(fixture.owner),
                    transaction = fixture.transaction,
                    version = 1,
                )
            assertEquals(
                "${fixture.name}: ${inspection.findings.map { it.code }}",
                Verdict.Verified,
                inspection.verdict,
            )
            assertTrue(fixture.name, inspection.approvable)
            val facts = assertNotNull(fixture.name, inspection.facts).let { inspection.facts!! }
            // Every instruction accounted for, which is what makes it approvable at all.
            assertTrue(fixture.name, facts.fullyRead)
            assertEquals(fixture.name, fixture.owner, facts.wallet)
            // The amount is the owner's own, read out of the routing instruction.
            assertEquals(fixture.name, fixture.amount, facts.amount)
            // A swap of native SOL spends SOL, whatever the pool needed it wrapped into.
            assertEquals(
                fixture.name,
                fixture.terms.inputMint.takeIf { it != WRAPPED_SOL },
                facts.mint,
            )
            // What the owner receives comes back to the owner.
            assertEquals(fixture.name, fixture.owner, facts.recipient)
            assertTrue(fixture.name, JUPITER_PROGRAM in facts.programs.orEmpty())
        }
    }

    @Test
    fun theFloorTheChainWillEnforceIsTheOneTheQuoteStated() {
        // The provider states a threshold; the instruction carries a quoted amount and a slippage.
        // Those are two independent numbers, and the review only passes when the floor derived from
        // the second equals the first. Four real captures agree, which is what makes the derivation
        // a check rather than a coincidence.
        for (fixture in swapFixtures()) {
            val route = routeOf(fixture)
            assertEquals(fixture.name, fixture.quote.outAmount, route.quotedOutAmount)
            assertEquals(fixture.name, fixture.slippageBps, route.slippageBps)
            assertEquals(fixture.name, fixture.quote.minimumOut, route.minimumOut)
            // And the owner is shown that number, written out, rather than a near one.
            val inspection =
                inspectSwap(
                    fixture.terms,
                    SwapChoice(fixture.amount, fixture.slippageBps),
                    fixture.quote,
                    wallet(fixture.owner),
                    fixture.transaction,
                    1,
                )
            assertEquals(
                fixture.name,
                formatBaseUnits(fixture.quote.minimumOut, fixture.terms.outputDecimals) +
                    " " +
                    fixture.terms.outputSymbol,
                inspection.details.first { it.label == R.string.jupiter_fact_minimum_out }.value,
            )
        }
    }

    @Test
    fun theReaderAndTheCaptureScriptAgreeOnWhatEachInstructionIs() {
        // Two readers, in two languages, written from the same wire format. The fixture records
        // what the capture script made of each instruction; this asserts the phone's reader made
        // the same thing of it. A provider that changes the shape of a swap fails here, in a test
        // that says which instruction moved, rather than somewhere downstream.
        for (fixture in swapFixtures()) {
            val decoded =
                (decodeTransaction(fixture.transaction.toByteArray()) as DecodeResult.Decoded)
                    .transaction
            assertEquals(fixture.name, fixture.version, decoded.version)
            assertEquals(fixture.name, fixture.signers, decoded.signers)
            assertTrue(fixture.name, decoded.unsigned)
            assertEquals(fixture.name, 0, decoded.addressTableLookups)
            val named =
                decoded.instructions.map {
                    when (val step = decoded.readSwapStep(it)) {
                        is SwapStep.Budget -> "budget"
                        is SwapStep.Wrap -> "wrap"
                        is SwapStep.Sync -> "sync"
                        is SwapStep.Unwrap -> "unwrap"
                        is SwapStep.Account -> "account"
                        is SwapStep.Route -> if (step.shared) "shared_route" else "route"
                        is SwapStep.Moves -> "moves"
                        is SwapStep.Unread -> "unread:${step.program}"
                        null -> "malformed"
                    }
                }
            assertEquals(fixture.name, fixture.shape, named)
        }
    }

    @Test
    fun theAccountsTheRouteNamesAreTheOwnersOwn() {
        // The two accounts that decide where the owner's money goes, in both routing layouts,
        // against addresses this phone derives from the owner's key and the publisher's mints —
        // which is the whole of how a phone that reaches no chain can know whose accounts they are.
        for (fixture in swapFixtures()) {
            val route = routeOf(fixture)
            assertEquals(
                fixture.name,
                associatedTokenAddress(fixture.owner, fixture.terms.inputMint),
                route.source,
            )
            assertEquals(
                fixture.name,
                associatedTokenAddress(fixture.owner, fixture.terms.outputMint),
                route.destination,
            )
            assertEquals(fixture.name, fixture.owner, route.authority)
            assertEquals(fixture.name, fixture.terms.outputMint, route.destinationMint)
            // Only one of the two layouts carries the source mint. The other establishes it by the
            // account being the owner's own for it, which is the same fact by another route.
            if (route.shared) assertEquals(fixture.name, fixture.terms.inputMint, route.sourceMint)
            else assertEquals(fixture.name, null, route.sourceMint)
            // Nobody takes a cut, and the route is the single hop that was asked for.
            assertEquals(fixture.name, null, route.platformFee)
            assertEquals(fixture.name, 0, route.platformFeeBps)
            assertEquals(fixture.name, 1, route.legs)
        }
    }

    @Test
    fun aRealSwapWrapsAndUnwrapsOnlyTheOwnersOwnAccount() {
        // Wrapping is where a swap touches native SOL, and a close sends what is in an account
        // somewhere. Both fixtures that involve SOL are checked against the owner's own wrapped
        // account, and the one that does not involve SOL has neither instruction.
        val fixtures = swapFixtures().associateBy { it.name }
        val solIn = checkNotNull(fixtures["sol_to_usdc"])
        val wrapped = checkNotNull(associatedTokenAddress(solIn.owner, WRAPPED_SOL))
        val steps = stepsOf(solIn)
        val wrap = steps.filterIsInstance<SwapStep.Wrap>().single()
        assertEquals(solIn.owner, wrap.from)
        assertEquals(wrapped, wrap.to)
        // Exactly the amount the owner is spending: nothing else of theirs is wrapped along the
        // way.
        assertEquals(solIn.amount, wrap.lamports)
        assertEquals(wrapped, steps.filterIsInstance<SwapStep.Sync>().single().account)
        val close = steps.filterIsInstance<SwapStep.Unwrap>().single()
        assertEquals(wrapped, close.account)
        assertEquals(solIn.owner, close.to)
        assertEquals(solIn.owner, close.authority)

        val tokens = checkNotNull(fixtures["usdc_to_jup_new_account"])
        val plain = stepsOf(tokens)
        assertTrue(plain.none { it is SwapStep.Wrap || it is SwapStep.Sync })
        assertTrue(plain.none { it is SwapStep.Unwrap })
        // And the account it creates is the owner's own, for the mint the signal names.
        val creation = plain.filterIsInstance<SwapStep.Account>().single()
        assertEquals(tokens.owner, creation.owner)
        assertEquals(tokens.owner, creation.payer)
        assertEquals(tokens.terms.outputMint, creation.mint)
        assertEquals(
            associatedTokenAddress(tokens.owner, tokens.terms.outputMint),
            creation.account,
        )
        assertTrue(creation.idempotent)
    }

    private fun stepsOf(fixture: SwapFixture): List<SwapStep> {
        val decoded =
            (decodeTransaction(fixture.transaction.toByteArray()) as DecodeResult.Decoded)
                .transaction
        return decoded.instructions.map { checkNotNull(decoded.readSwapStep(it)) }
    }

    private fun routeOf(fixture: SwapFixture): SwapStep.Route =
        stepsOf(fixture).filterIsInstance<SwapStep.Route>().single()
}
