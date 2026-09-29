package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.ReceiptKey
import io.github.brrenat.seekervault.plugins.ServiceFeeStatus
import io.github.brrenat.seekervault.plugins.actions.SwapChoice
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.bpsPercent
import io.github.brrenat.seekervault.plugins.receiptRows
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.TOKEN_2022_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.wallet.decodeBase58
import io.github.brrenat.seekervault.wallet.encodeBase58
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SAC service fee on swaps (SEE-173): the build's policy, the chain check of its receiving
 * account, and the inspection that holds the transaction to exactly the fee the owner was shown.
 *
 * Every address here is synthetic — thirty-two repeated bytes, or an account derived from one — and
 * belongs to nobody's operator.
 */
class SwapFeeTest {
    private val terms = usdcTerms(inputMint = USDC_MINT, outputMint = SOL_MINT)
    private val amount = 10_000_000UL
    private val bps = 20
    private val charged = SwapFee.Charged(bps, FEE_ACCOUNT_SOL, FEE_OWNER, SOL_MINT)
    // What the route produces before the fee, and what the owner is quoted after it.
    private val gross = 99_123_457UL
    private val quote = feeQuote(terms, amount, gross, bps)
    private val choice = SwapChoice(amount, quote.slippageBps)

    private fun inspect(
        transaction: ByteString,
        fee: SwapFee = charged,
        quote: JupiterQuote = this.quote,
        terms: SwapPayload = this.terms,
    ): ActionInspection =
        inspectSwap(
            terms = terms,
            choice = choice,
            quote = quote,
            wallet = wallet(),
            transaction = transaction,
            version = 1,
            fee = fee,
        )

    private fun built(
        platformFee: String? = FEE_ACCOUNT_SOL,
        platformFeeBps: Int = bps,
        shared: Boolean = true,
        quote: JupiterQuote = this.quote,
        extra: List<Step> = emptyList(),
        quotedOut: ULong = quote.outAmount,
    ) =
        swapTransaction(
            terms,
            amount,
            quote,
            shared = shared,
            platformFee = platformFee,
            platformFeeBps = platformFeeBps,
            extra = extra,
            quotedOut = quotedOut,
        )

    private fun codes(inspection: ActionInspection) = inspection.findings.map { it.code }

    // --- The build's policy ------------------------------------------------------------------

    @Test
    fun aBuildNobodyConfiguredChargesNothing() {
        assertEquals(SwapFeePolicy.Off, SwapFeePolicy.fromBuild(0, "", ""))
        assertFalse(SwapFeePolicy.fromBuild(0, "", "").enabled)
        // A rate with nothing to pay it to is no fee, not a fee to somewhere.
        assertFalse(SwapFeePolicy.fromBuild(20, "", "").enabled)
        assertFalse(SwapFeePolicy.fromBuild(20, FEE_OWNER, "").enabled)
    }

    @Test
    fun aConfiguredBuildIsReadIntoOneAccountPerMint() {
        val policy =
            SwapFeePolicy.fromBuild(
                20,
                FEE_OWNER,
                " $SOL_MINT=$FEE_ACCOUNT_SOL , $USDC_MINT=$FEE_ACCOUNT_USDC ",
            )
        assertTrue(policy.enabled)
        assertEquals(20, policy.bps)
        assertEquals(FEE_ACCOUNT_SOL, policy.accounts[SOL_MINT])
        assertEquals(FEE_ACCOUNT_USDC, policy.accounts[USDC_MINT])
        assertNull("no account for a mint nobody configured", policy.accounts[JUP_MINT])
    }

    @Test
    fun aMalformedConfigurationThatSlippedPastTheBuildFailsClosedToNoFee() {
        val accounts = "$SOL_MINT=$FEE_ACCOUNT_SOL"
        val cases =
            listOf(
                "a rate above the bound" to SwapFeePolicy.fromBuild(101, FEE_OWNER, accounts),
                "a negative rate" to SwapFeePolicy.fromBuild(-5, FEE_OWNER, accounts),
                "an owner that is not an address" to SwapFeePolicy.fromBuild(20, "nope", accounts),
                "an entry without its account" to SwapFeePolicy.fromBuild(20, FEE_OWNER, SOL_MINT),
                "an account that is not an address" to
                    SwapFeePolicy.fromBuild(20, FEE_OWNER, "$SOL_MINT=0OIl"),
                "the same mint twice" to
                    SwapFeePolicy.fromBuild(20, FEE_OWNER, "$accounts,$SOL_MINT=$FEE_ACCOUNT_USDC"),
                // One wallet address for every pair is exactly what the policy refuses: a fee is
                // received by a token account for the fee's mint.
                "the owner's wallet as the account" to
                    SwapFeePolicy.fromBuild(20, FEE_OWNER, "$SOL_MINT=$FEE_OWNER"),
                "the mint as its own account" to
                    SwapFeePolicy.fromBuild(20, FEE_OWNER, "$SOL_MINT=$SOL_MINT"),
            )
        for ((what, policy) in cases) assertEquals(what, SwapFeePolicy.Off, policy)
    }

    // --- The chain check ---------------------------------------------------------------------

    @Test
    fun theReceivingAccountIsReadFromTheChainAndMustBeExactlyTheConfiguredOne() {
        assertNull(verifyFeeAccount(tokenAccount(SOL_MINT, FEE_OWNER), SOL_MINT, FEE_OWNER))
        val cases =
            listOf(
                "an account that was never created" to (null to FeeAccountProblem.MISSING),
                "a Token-2022 account" to
                    (tokenAccount(SOL_MINT, FEE_OWNER, program = TOKEN_2022_PROGRAM) to
                        FeeAccountProblem.NOT_TOKEN_ACCOUNT),
                "a wallet rather than a token account" to
                    (AccountSnapshot(SYSTEM, ByteArray(0), false) to
                        FeeAccountProblem.NOT_TOKEN_ACCOUNT),
                "a token account of another size" to
                    (AccountSnapshot(TOKEN_PROGRAM, ByteArray(82), false) to
                        FeeAccountProblem.NOT_TOKEN_ACCOUNT),
                "an account for another mint" to
                    (tokenAccount(USDC_MINT, FEE_OWNER) to FeeAccountProblem.OTHER_MINT),
                "an account somebody else holds" to
                    (tokenAccount(SOL_MINT, SOMEONE_ELSE) to FeeAccountProblem.OTHER_OWNER),
                "an uninitialized account" to
                    (tokenAccount(SOL_MINT, FEE_OWNER, state = 0) to FeeAccountProblem.NOT_USABLE),
                "a frozen account" to
                    (tokenAccount(SOL_MINT, FEE_OWNER, state = 2) to FeeAccountProblem.NOT_USABLE),
            )
        for ((what, case) in cases) {
            val (snapshot, problem) = case
            assertEquals(what, problem, verifyFeeAccount(snapshot, SOL_MINT, FEE_OWNER))
        }
    }

    @Test
    fun eachFeeDecisionIsTheOneTheChainAndThePolicySupport() = runBlocking {
        val policy = SwapFeePolicy(bps, FEE_OWNER, mapOf(SOL_MINT to FEE_ACCOUNT_SOL))
        val good = chain(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, FEE_OWNER))

        assertEquals(charged, decideSwapFee(policy, SOL_MINT, good.first))
        assertEquals(listOf(listOf(FEE_ACCOUNT_SOL)), good.second)
        // Disabled, and nothing is read.
        val untouched = chain()
        assertEquals(SwapFee.Disabled, decideSwapFee(SwapFeePolicy.Off, SOL_MINT, untouched.first))
        assertTrue(untouched.second.isEmpty())
        // No account for this output mint: no fee on this pair, and nothing is read either.
        assertEquals(
            SwapFee.NotForPair(bps, USDC_MINT),
            decideSwapFee(policy, USDC_MINT, untouched.first),
        )
        assertTrue(untouched.second.isEmpty())
        // An account that is not there on chain.
        assertEquals(
            SwapFee.Unverified(bps, FEE_ACCOUNT_SOL, SOL_MINT, FeeAccountProblem.MISSING),
            decideSwapFee(policy, SOL_MINT, chain().first),
        )
        // An endpoint that cannot answer, or a build with none: no fee, never a guess.
        assertEquals(
            SwapFee.Unverified(bps, FEE_ACCOUNT_SOL, SOL_MINT, FeeAccountProblem.NO_RPC),
            decideSwapFee(policy, SOL_MINT, failing(SolanaProblem.NoEndpoint)),
        )
        assertEquals(
            SwapFee.Unverified(bps, FEE_ACCOUNT_SOL, SOL_MINT, FeeAccountProblem.UNREADABLE),
            decideSwapFee(policy, SOL_MINT, failing(SolanaProblem.RateLimited)),
        )
    }

    // --- The inspection ----------------------------------------------------------------------

    @Test
    fun theConfiguredFeeInBothRoutingVariantsIsVerified() {
        for (shared in listOf(true, false)) {
            val read = inspect(built(shared = shared))
            assertEquals("shared=$shared ${codes(read)}", Verdict.Verified, read.verdict)
            // The fee comes out of the output, so what the owner spends is exactly what they
            // entered: a spending rule sees the same amount with or without a fee.
            assertEquals(amount, read.facts?.amount)
            assertEquals(OWNER, read.facts?.recipient)
        }
    }

    @Test
    fun aBuildWithoutAFeeStillRefusesAnyCutAtAll() {
        // The fee-disabled regression: exactly the pre-SEE-173 rule.
        val plain = quoteFor(terms, amount, outAmount = 99_000_000UL)
        val clean =
            inspect(
                swapTransaction(terms, amount, plain),
                fee = SwapFee.Disabled,
                quote = plain,
            )
        assertEquals(Verdict.Verified, clean.verdict)
        for (fee in listOf(SwapFee.Disabled, SwapFee.NotForPair(bps, SOL_MINT))) {
            val cut =
                inspect(
                    swapTransaction(
                        terms,
                        amount,
                        plain,
                        platformFee = FEE_ACCOUNT_SOL,
                        platformFeeBps = bps,
                    ),
                    fee = fee,
                    quote = plain,
                )
            assertEquals(Verdict.Invalid, cut.verdict)
            assertTrue(SwapFinding.PlatformFee.code in codes(cut))
        }
        // A quote that carries a fee nobody decided on is refused even when the bytes carry none.
        val quoted = inspect(swapTransaction(terms, amount, quote), fee = SwapFee.Disabled)
        assertTrue(SwapFinding.PlatformFee.code in codes(quoted))
    }

    @Test
    fun everyWayTheFeeCanDifferFromTheReviewedOneIsRefused() {
        val cases =
            listOf(
                "a higher rate" to (built(platformFeeBps = 21) to SwapFinding.FeeRateMismatch),
                "a lower rate" to (built(platformFeeBps = 19) to SwapFinding.FeeRateMismatch),
                "the most the byte can say" to
                    (built(platformFeeBps = 255) to SwapFinding.FeeRateMismatch),
                "another recipient" to
                    (built(platformFee = SOMEONE_ELSE_ACCOUNT) to SwapFinding.FeeAccountMismatch),
                "no recipient where one was reviewed" to
                    (built(platformFee = null) to SwapFinding.FeeAccountMismatch),
                "no fee where one was reviewed" to
                    (built(platformFee = null, platformFeeBps = 0) to SwapFinding.FeeRateMismatch),
                // The receiving account for the *input* mint: Metis would take the fee from the
                // other side, which is not the fee the quote was made with.
                "the input mint's account" to
                    (built(platformFee = FEE_ACCOUNT_USDC) to SwapFinding.FeeAccountMismatch),
            )
        for ((what, case) in cases) {
            val (bytes, finding) = case
            val read = inspect(bytes)
            assertEquals(what, Verdict.Invalid, read.verdict)
            assertTrue("$what: ${codes(read)}", finding.code in codes(read))
            assertFalse(what, read.approvable)
        }
    }

    @Test
    fun aFeeDecidedForAnotherMintIsRefused() {
        val wrongMint = SwapFee.Charged(bps, FEE_ACCOUNT_SOL, FEE_OWNER, USDC_MINT)
        val read = inspect(built(), fee = wrongMint)
        assertTrue(SwapFinding.FeeMintMismatch.code in codes(read))
        assertEquals(Verdict.Invalid, read.verdict)
    }

    @Test
    fun aQuoteAtAnotherRateThanTheDecisionIsRefused() {
        val other = feeQuote(terms, amount, gross, 30)
        val read = inspect(built(quote = other), quote = other)
        assertTrue(SwapFinding.FeeRateMismatch.code in codes(read))
    }

    @Test
    fun aFeeDoesNotLoosenTheOtherProtections() {
        // An extra transfer beside a correct fee is still an extra transfer.
        val transfer =
            Step(
                TOKEN_PROGRAM,
                listOf(
                    checkNotNull(associatedTokenAddress(OWNER, USDC_MINT)),
                    USDC_MINT,
                    FEE_ACCOUNT_USDC,
                    OWNER,
                ),
                byteArrayOf(12) + u64(1UL) + byteArrayOf(6),
            )
        val moved = inspect(built(extra = listOf(transfer)))
        assertEquals(Verdict.Invalid, moved.verdict)
        assertTrue(SwapFinding.ExtraTransfer.code in codes(moved))
        // And the floor is still the quote's: an instruction quoting the gross output — before
        // the fee — would enforce a floor the owner was not shown.
        val grossFloor = inspect(built(quotedOut = gross))
        assertTrue(SwapFinding.QuoteMismatch.code in codes(grossFloor))
    }

    @Test
    fun theOwnerIsShownTheRateTheEstimateAndTheRecipientAndHistoryKeepsThem() {
        val read = inspect(built())
        val facts = read.details.associate { it.label to it.value }
        assertEquals(SWAP_ROUTING_NAME, facts[R.string.jupiter_fact_routing])
        assertEquals("0.2%", facts[R.string.jupiter_fact_service_fee_rate])
        // floor(99 123 457 × 20 / 10 000) = 198 246 lamports, net 98 925 211.
        assertEquals("0.000198246 SOL", facts[R.string.jupiter_fact_service_fee_estimate])
        assertEquals(FEE_ACCOUNT_SOL, facts[R.string.jupiter_fact_service_fee_recipient])
        // What the owner receives at worst is net of the fee, and so is the quote shown.
        assertEquals("0.098925211 SOL", facts[R.string.jupiter_fact_quoted_out])
        assertEquals("0.098430585 SOL", facts[R.string.jupiter_fact_minimum_out])

        val receipt = read.receipt.associate { it.key to it.value }
        assertEquals(SWAP_ROUTING_NAME, receipt[ReceiptKey.SWAP_ROUTING])
        assertEquals(ServiceFeeStatus.CHARGED, receipt[ReceiptKey.SERVICE_FEE_STATUS])
        assertEquals("20", receipt[ReceiptKey.SERVICE_FEE_BPS])
        assertEquals("0.000198246 SOL", receipt[ReceiptKey.SERVICE_FEE_ESTIMATE])
        assertEquals(SOL_MINT, receipt[ReceiptKey.SERVICE_FEE_MINT])
        assertEquals(FEE_ACCOUNT_SOL, receipt[ReceiptKey.SERVICE_FEE_RECIPIENT])
        assertEquals(
            listOf(
                "Swap routing" to SWAP_ROUTING_NAME,
                "SAC service fee" to "0.2% of what you receive",
                "SAC service fee, estimated at review" to "0.000198246 SOL",
                "SAC service fee token" to SOL_MINT,
                "SAC service fee recipient" to FEE_ACCOUNT_SOL,
            ),
            receiptRows(read.receipt),
        )
    }

    @Test
    fun aSwapWithoutAFeeSaysWhichKindOfNoFeeItIs() {
        val plain = quoteFor(terms, amount, outAmount = 99_000_000UL)
        val bytes = swapTransaction(terms, amount, plain)
        val cases =
            listOf(
                SwapFee.Disabled to
                    (R.string.jupiter_fact_service_fee_none to ServiceFeeStatus.NONE),
                SwapFee.NotForPair(bps, SOL_MINT) to
                    (R.string.jupiter_fact_service_fee_not_for_pair to
                        ServiceFeeStatus.NOT_FOR_PAIR),
                SwapFee.Unverified(bps, FEE_ACCOUNT_SOL, SOL_MINT, FeeAccountProblem.MISSING) to
                    (R.string.jupiter_fact_service_fee_unverified to ServiceFeeStatus.UNVERIFIED),
            )
        for ((fee, expected) in cases) {
            val read = inspect(bytes, fee = fee, quote = plain)
            assertEquals("$fee", Verdict.Verified, read.verdict)
            val (label, status) = expected
            assertEquals("0%", read.details.single { it.label == label }.value)
            assertEquals(
                status,
                read.receipt.single { it.key == ReceiptKey.SERVICE_FEE_STATUS }.value,
            )
            // Nobody is named as a recipient of a fee nobody is paid.
            assertTrue(read.receipt.none { it.key == ReceiptKey.SERVICE_FEE_RECIPIENT })
        }
    }

    @Test
    fun ratesAreWrittenExactly() {
        assertEquals("0%", bpsPercent(0))
        assertEquals("0.01%", bpsPercent(1))
        assertEquals("0.2%", bpsPercent(20))
        assertEquals("0.25%", bpsPercent(25))
        assertEquals("1%", bpsPercent(100))
    }

    private fun chain(
        vararg held: Pair<String, AccountSnapshot>
    ): Pair<SolanaAccounts, MutableList<List<String>>> {
        val asked = mutableListOf<List<String>>()
        val accounts = held.toMap()
        val reader =
            object : SolanaAccounts {
                override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> {
                    asked += addresses
                    return addresses.map { accounts[it] }
                }
            }
        return reader to asked
    }

    private fun failing(problem: SolanaProblem) =
        object : SolanaAccounts {
            override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> =
                throw SolanaException(problem)
        }
}

/** A synthetic wallet that owns the fee accounts: thirty-two bytes of 0x42. */
val FEE_OWNER: String = encodeBase58(ByteArray(32) { 0x42 })

/** Its token accounts for the swap's two mints, derived as a real operator's would be. */
val FEE_ACCOUNT_SOL: String = checkNotNull(associatedTokenAddress(FEE_OWNER, SOL_MINT))

val FEE_ACCOUNT_USDC: String = checkNotNull(associatedTokenAddress(FEE_OWNER, USDC_MINT))

/** Somebody else's account for the output mint. */
val SOMEONE_ELSE_ACCOUNT: String = checkNotNull(associatedTokenAddress(SOMEONE_ELSE, SOL_MINT))

private const val SYSTEM = "11111111111111111111111111111111"

/**
 * A quote with a service fee, computed as Metis computes one for an exact-input swap: the fee is
 * floor(gross × bps / 10 000) of the route's output, the quoted output is net of it, and the floor
 * is the slippage off the net amount.
 */
fun feeQuote(
    terms: SwapPayload,
    amount: ULong,
    gross: ULong,
    bps: Int,
    slippageBps: Int = 50,
): JupiterQuote {
    val fee = gross * bps.toULong() / 10_000UL
    val net = gross - fee
    return quoteFor(terms, amount, outAmount = net, slippageBps = slippageBps)
        .copy(platformFeeBps = bps, platformFeeAmount = fee)
}

/** A classic SPL Token account as `getMultipleAccounts` returns it. */
fun tokenAccount(
    mint: String,
    owner: String,
    state: Int = 1,
    program: String = TOKEN_PROGRAM,
): AccountSnapshot {
    val data = ByteArray(165)
    checkNotNull(decodeBase58(mint)).copyInto(data, 0)
    checkNotNull(decodeBase58(owner)).copyInto(data, 32)
    data[108] = state.toByte()
    return AccountSnapshot(program, data, executable = false)
}
