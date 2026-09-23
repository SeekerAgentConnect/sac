package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.actions.SwapChoice
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.WRAPPED_SOL
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.wallet.WalletNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything the review refuses (SEE-93).
 *
 * `JupiterFixturesTest` proves the phone reads what the provider really builds. This proves the
 * other half, which no provider would ever supply on request: for each way a transaction could
 * differ from what the owner agreed to, one test that changes exactly that and shows the review
 * refusing it. They are built rather than captured for that reason — there is no asking Jupiter for
 * a transaction that cheats you.
 *
 * Every case runs through the real serializer and the real decoder, so nothing here asserts about
 * bytes the phone would not have read in the first place.
 */
class SwapInspectionTest {
    private val terms = usdcTerms(inputMint = USDC_MINT, outputMint = SOL_MINT)
    private val amount = 10_000_000UL
    private val quote = quoteFor(terms, amount, outAmount = 99_000_000UL)
    private val choice = SwapChoice(amount, quote.slippageBps)

    private fun inspect(
        transaction: ByteString,
        terms: SwapPayload = this.terms,
        choice: SwapChoice = this.choice,
        quote: JupiterQuote = this.quote,
        walletAddress: String = OWNER,
        network: WalletNetwork = WalletNetwork.Mainnet,
        connected: Boolean = true,
    ) =
        inspectSwap(
            terms = terms,
            choice = choice,
            quote = quote,
            wallet = if (connected) wallet(walletAddress, network) else null,
            transaction = transaction,
            version = 3,
        )

    private fun codes(inspection: io.github.brrenat.seekervault.plugins.ActionInspection) =
        inspection.findings.map { it.code }

    @Test
    fun aSwapThatIsWhatTheOwnerChoseIsVerified() {
        for (shared in listOf(true, false)) {
            val inspection = inspect(swapTransaction(terms, amount, quote, shared = shared))

            assertEquals(
                "shared=$shared ${codes(inspection)}",
                Verdict.Verified,
                inspection.verdict,
            )
            assertTrue(inspection.approvable)
            assertEquals(3, inspection.version)
            assertEquals(amount, inspection.facts?.amount)
            assertEquals(USDC_MINT, inspection.facts?.mint)
            assertEquals(OWNER, inspection.facts?.recipient)
            assertTrue(inspection.facts?.fullyRead == true)
        }
    }

    @Test
    fun everyWayTheBytesCanDisagreeWithWhatWasChosenIsRefused() {
        // One table, one changed thing each, so the reason a case fails is the case itself.
        val cases =
            listOf(
                "the amount" to
                    swapTransaction(terms, amount, quote, inAmount = amount + 1UL) to
                    SwapFinding.AmountMismatch,
                "the slippage" to
                    swapTransaction(terms, amount, quote, slippageBps = quote.slippageBps + 1) to
                    SwapFinding.SlippageMismatch,
                "the quoted output" to
                    swapTransaction(terms, amount, quote, quotedOut = quote.outAmount - 1UL) to
                    SwapFinding.QuoteMismatch,
                "the account the funds come from" to
                    swapTransaction(
                        terms,
                        amount,
                        quote,
                        source = associatedTokenAddress(SOMEONE_ELSE, USDC_MINT),
                    ) to
                    SwapFinding.SourceNotOwnersAccount,
                "the account the funds go to" to
                    swapTransaction(
                        terms,
                        amount,
                        quote,
                        destination = associatedTokenAddress(SOMEONE_ELSE, SOL_MINT),
                    ) to
                    SwapFinding.DestinationNotOwnersAccount,
                "the mint received" to
                    swapTransaction(terms, amount, quote, destinationMint = JUP_MINT) to
                    SwapFinding.MintMismatch,
                "the mint spent" to
                    swapTransaction(terms, amount, quote, sourceMint = JUP_MINT) to
                    SwapFinding.MintMismatch,
                "who authorizes the funds leaving" to
                    swapTransaction(terms, amount, quote, authority = SOMEONE_ELSE) to
                    SwapFinding.NotTheOwnersRoute,
                "who pays" to
                    swapTransaction(terms, amount, quote, payer = SOMEONE_ELSE) to
                    SwapFinding.FeePayerNotTheWallet,
                "who else signs" to
                    swapTransaction(
                        terms,
                        amount,
                        quote,
                        signers = listOf(OWNER, SOMEONE_ELSE),
                    ) to
                    SwapFinding.ExtraSigner,
                "who takes a share" to
                    swapTransaction(terms, amount, quote, platformFee = SOMEONE_ELSE) to
                    SwapFinding.PlatformFee,
                "how big a share" to
                    swapTransaction(terms, amount, quote, platformFeeBps = 1) to
                    SwapFinding.PlatformFee,
                "how many hops" to
                    swapTransaction(terms, amount, quote, legs = 2) to
                    SwapFinding.TooManyLegs,
                "how many swaps" to
                    swapTransaction(terms, amount, quote, routes = 2) to
                    SwapFinding.ExtraRoute,
                "whether it is already signed" to
                    swapTransaction(terms, amount, quote, signed = true) to
                    SwapFinding.AlreadySigned,
            )
        for ((named, expected) in cases) {
            val (what, transaction) = named
            val inspection = inspect(transaction)

            assertEquals(what, Verdict.Invalid, inspection.verdict)
            assertFalse(what, inspection.approvable)
            assertTrue("$what: ${codes(inspection)}", expected.code in codes(inspection))
        }
    }

    @Test
    fun aTransactionWithNoSwapInItEstablishesNothing() {
        val inspection = inspect(swapTransaction(terms, amount, quote, routes = 0))

        assertEquals(Verdict.Invalid, inspection.verdict)
        assertNull(inspection.facts)
        assertEquals(listOf(SwapFinding.NoRoute.code), codes(inspection))
        // And nothing is claimed about a transaction nobody could read the swap out of.
        assertTrue(inspection.details.isEmpty())
    }

    @Test
    fun theOwnersMoneyMayOnlyBeWrappedAndUnwrappedForThemselves() {
        val sol = usdcTerms(inputMint = SOL_MINT, outputMint = USDC_MINT)
        val solQuote = quoteFor(sol, amount, outAmount = 1_000_000UL)
        val solChoice = SwapChoice(amount, solQuote.slippageBps)

        // The shape a provider really builds, first: it passes.
        assertEquals(
            Verdict.Verified,
            inspect(swapTransaction(sol, amount, solQuote), sol, solChoice, solQuote).verdict,
        )
        // Closing the wrapped account to somebody else would hand them what is left in it.
        assertTrue(
            SwapFinding.WrappingNotTheOwners.code in
                codes(
                    inspect(
                        swapTransaction(sol, amount, solQuote, unwrapTo = SOMEONE_ELSE),
                        sol,
                        solChoice,
                        solQuote,
                    )
                )
        )
        // Wrapping into somebody else's account would put the input there.
        assertTrue(
            SwapFinding.WrappingNotTheOwners.code in
                codes(
                    inspect(
                        swapTransaction(
                            sol,
                            amount,
                            solQuote,
                            wrapTo = associatedTokenAddress(SOMEONE_ELSE, WRAPPED_SOL),
                        ),
                        sol,
                        solChoice,
                        solQuote,
                    )
                )
        )
        // And wrapping more than the owner is spending takes more than they agreed to.
        assertTrue(
            SwapFinding.WrapsMoreThanTheAmount.code in
                codes(
                    inspect(
                        swapTransaction(sol, amount, solQuote, wrap = amount + 1UL),
                        sol,
                        solChoice,
                        solQuote,
                    )
                )
        )
        // Neither side is SOL here, so there is nothing to wrap and no reason to.
        assertTrue(
            SwapFinding.UnexpectedWrapping.code in
                codes(swapTransaction(terms, amount, quote, wrap = 1UL).let { inspect(it) })
        )
    }

    @Test
    fun anAccountIsOnlyEverCreatedForTheOwnerAndForTheseAssets() {
        // A creation is not free: the payer funds the new account's rent. It is allowed because the
        // output needs somewhere to land, and only in that shape.
        assertEquals(
            Verdict.Verified,
            inspect(swapTransaction(terms, amount, quote, createFor = OWNER)).verdict,
        )
        assertTrue(
            SwapFinding.AccountCreationForSomeoneElse.code in
                codes(inspect(swapTransaction(terms, amount, quote, createFor = SOMEONE_ELSE)))
        )
        assertTrue(
            SwapFinding.AccountCreationForSomeoneElse.code in
                codes(inspect(swapTransaction(terms, amount, quote, createMint = JUP_MINT)))
        )
    }

    @Test
    fun anythingElseInTheTransactionIsSaidRatherThanAssumedHarmless() {
        // A plain token transfer alongside the swap: read, and refused.
        val transfer =
            Step(
                TOKEN_PROGRAM,
                listOf(
                    checkNotNull(associatedTokenAddress(OWNER, USDC_MINT)),
                    USDC_MINT,
                    checkNotNull(associatedTokenAddress(SOMEONE_ELSE, USDC_MINT)),
                    OWNER,
                ),
                byteArrayOf(12) + u64(1UL) + byteArrayOf(6),
            )
        val moved = inspect(swapTransaction(terms, amount, quote, extra = listOf(transfer)))
        assertEquals(Verdict.Invalid, moved.verdict)
        assertTrue(SwapFinding.ExtraTransfer.code in codes(moved))

        // An instruction of the token program this plugin does not read — Approve hands a delegate
        // the account. A program being one a swap may call is no reason to accept everything it
        // offers.
        val approve =
            Step(
                TOKEN_PROGRAM,
                listOf(checkNotNull(associatedTokenAddress(OWNER, USDC_MINT)), SOMEONE_ELSE, OWNER),
                byteArrayOf(4) + u64(1UL),
            )
        val delegated = inspect(swapTransaction(terms, amount, quote, extra = listOf(approve)))
        assertEquals(Verdict.Invalid, delegated.verdict)
        assertTrue(SwapFinding.UnreadableValueInstruction.code in codes(delegated))

        // An instruction from an unrelated program is not covered, which is not the same as being
        // wrong — and is still not approvable, because a review with a gap in it is not a review.
        val elsewhere = Step(POOL, listOf(OWNER), byteArrayOf(1, 2, 3))
        val uncovered = inspect(swapTransaction(terms, amount, quote, extra = listOf(elsewhere)))
        assertEquals(Verdict.Unverified, uncovered.verdict)
        assertFalse(uncovered.approvable)
        assertEquals(listOf(SwapFinding.UnrecognizedInstruction.code), codes(uncovered))
        assertFalse(uncovered.facts?.fullyRead == true)

        // An aggregator instruction that is not one of the two routing ones — a limit order, a fee
        // claim, a routing version written after this plugin.
        val other = Step(JUPITER_PROGRAM, listOf(OWNER), ByteArray(40) { 1 })
        val unknown = inspect(swapTransaction(terms, amount, quote, extra = listOf(other)))
        assertTrue(SwapFinding.UnreadableValueInstruction.code in codes(unknown))
    }

    @Test
    fun bytesThePhoneCannotReadAtAllEstablishNothing() {
        val malformed = inspect(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
        assertEquals(Verdict.Invalid, malformed.verdict)
        assertNull(malformed.facts)
        assertEquals(listOf(SwapFinding.Malformed.code), codes(malformed))

        // A versioned message that loads an account from a lookup table. The provider was asked for
        // a legacy transaction; one of these arriving is refused rather than resolved, because
        // resolving it would mean reading the chain or believing the provider about it.
        val steps =
            listOf(
                route(
                    shared = true,
                    authority = OWNER,
                    source = checkNotNull(associatedTokenAddress(OWNER, USDC_MINT)),
                    destination = checkNotNull(associatedTokenAddress(OWNER, SOL_MINT)),
                    sourceMint = USDC_MINT,
                    destinationMint = SOL_MINT,
                    platformFee = null,
                    legs = 1,
                    plan = ByteArray(4) { 7 },
                    inAmount = amount,
                    quotedOut = quote.outAmount,
                    slippageBps = quote.slippageBps,
                    platformFeeBps = 0,
                )
            )
        val lookup = inspect(withLookupTable(steps))
        assertEquals(Verdict.Invalid, lookup.verdict)
        assertEquals(listOf(SwapFinding.AddressTableLookup.code), codes(lookup))
    }

    @Test
    fun withoutTheOwnersWalletOnTheRightNetworkNothingIsEstablished() {
        val transaction = swapTransaction(terms, amount, quote)

        val none = inspect(transaction, connected = false)
        assertEquals(Verdict.Invalid, none.verdict)
        assertTrue(SwapFinding.NoWallet.code in codes(none))
        // Nothing about whose accounts these are can be established without a wallet to derive
        // them from, so the review says that rather than guessing an owner.
        assertNull(none.facts?.recipient)

        // Jupiter routes liquidity that exists on one network. A devnet wallet is not a devnet
        // Jupiter, and the app's own devnet transfer and message tests are about something else.
        val devnet = inspect(transaction, network = WalletNetwork.Devnet)
        assertEquals(Verdict.Invalid, devnet.verdict)
        assertTrue(SwapFinding.OtherNetwork.code in codes(devnet))

        // A different wallet selected than the transaction was built for.
        val other = inspect(transaction, walletAddress = SOMEONE_ELSE)
        assertEquals(Verdict.Invalid, other.verdict)
        assertTrue(SwapFinding.FeePayerNotTheWallet.code in codes(other))
    }

    @Test
    fun theRoutingInstructionsOwnNumbersAreNeverHalfRead() {
        // The four numbers sit at the end of the instruction's data, after a route plan this plugin
        // does not parse. An instruction too short to hold them is unread rather than misread,
        // which is the behaviour that keeps a future change to the program's layout safe: it
        // stops agreeing with the owner's choice instead of quietly meaning something else.
        val truncated = Step(JUPITER_PROGRAM, List(13) { OWNER }, ByteArray(20) { 1 })
        val inspection = inspect(swapTransaction(terms, amount, quote, extra = listOf(truncated)))

        assertTrue(SwapFinding.UnreadableValueInstruction.code in codes(inspection))
    }

    @Test
    fun theNumbersTheOwnerSeesAreTheOnesInTheBytes() {
        val inspection = inspect(swapTransaction(terms, amount, quote))

        val values = inspection.details.map { it.value }
        // The floor, the offer, and how far apart they may be — all read out of the instruction.
        assertEquals("0.098505 SOL", values[0])
        assertEquals("0.099 SOL", values[1])
        assertEquals("0.5%", values[2])
        // What the transaction will cost to be picked up: the limit times the price, in SOL.
        assertEquals("0.000099999", values[3])
        // And nothing about a new account, because this transaction creates none.
        assertEquals(4, values.size)
    }
}
