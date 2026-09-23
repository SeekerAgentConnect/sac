package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.solana.LookupException
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.wallet.WalletNetwork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Everything the order review refuses (SEE-94).
 *
 * `PredictionFixturesTest` proves the review verifies a real order Jupiter really built. This is
 * the other half, and no provider would supply any of it on request: for each way a transaction
 * could differ from the order the owner agreed to, one case that changes exactly that and shows the
 * review refusing it.
 *
 * Every case is a **versioned** message resolved through a stood-in chain, because that is the only
 * shape this provider produces — so each one exercises the resolution rather than sidestepping it.
 */
@RunWith(AndroidJUnit4::class)
class PredictionInspectionTest {
    private val terms = predictionTerms()
    private val deposit = 5_000_000UL
    private val choice = PredictionChoice(yes = true, deposit = deposit)

    private fun inspect(
        built: OrderBytes,
        order: PredictionOrder? = null,
        terms: PredictionPayload = this.terms,
        choice: PredictionChoice = this.choice,
        walletAddress: String = OWNER,
        network: WalletNetwork = WalletNetwork.Mainnet,
        connected: Boolean = true,
        chain: FakeChain = FakeChain(built.tables),
    ): ActionInspection = runBlocking {
        inspectPrediction(
            terms = terms,
            choice = choice,
            order =
                order
                    ?: predictionOrder(built.transaction, yes = choice.yes, owner = walletAddress),
            wallet = if (connected) wallet(walletAddress, network) else null,
            transaction = built.transaction,
            version = 5,
            chain = chain,
        )
    }

    private fun codes(inspection: ActionInspection) = inspection.findings.map { it.code }

    @Test
    fun anOrderThatIsWhatTheOwnerChoseIsVerified() {
        val inspection = inspect(orderTransaction())

        assertEquals(codes(inspection).toString(), Verdict.Verified, inspection.verdict)
        assertTrue(inspection.approvable)
        assertEquals(5, inspection.version)
        assertEquals(deposit, inspection.facts?.amount)
        assertEquals(USDC_MINT, inspection.facts?.mint)
        assertEquals(ORDER_PUBKEY, inspection.facts?.recipient)
        assertTrue(inspection.facts?.fullyRead == true)
        // The owner sees the side, the stake, the payout if it wins and the price ceiling.
        assertTrue(inspection.details.isNotEmpty())
        // And the record keeps which order it was.
        assertEquals(
            listOf(ORDER_ACCOUNT, POSITION_ACCOUNT, MARKET),
            inspection.references.map { it.key },
        )
    }

    @Test
    fun theOtherSideOfTheMarketIsRefused() {
        // The one mistake with the worst consequences: an order for No when the owner chose Yes.
        // It is read out of the instruction's own byte rather than out of the provider's answer.
        val inspection = inspect(orderTransaction(yes = false))

        assertEquals(Verdict.Invalid, inspection.verdict)
        assertTrue(PredictionFinding.OutcomeMismatch.code in codes(inspection))
        assertFalse(inspection.approvable)
    }

    @Test
    fun everyWayTheBytesCanDisagreeWithTheOrderIsRefused() {
        val cases =
            listOf(
                "the market" to
                    orderTransaction(marketHash = "f".repeat(32)) to
                    PredictionFinding.MarketMismatch,
                "the order's own identifier" to
                    orderTransaction(externalOrderId = "a".repeat(32)) to
                    PredictionFinding.OrderMismatch,
                "the order's account" to
                    orderTransaction(order = SOMEONE_ELSE) to
                    PredictionFinding.OrderMismatch,
                "the position's account" to
                    orderTransaction(position = SOMEONE_ELSE) to
                    PredictionFinding.OrderMismatch,
                "how many contracts" to
                    orderTransaction(contractsMicro = 99_000_000UL) to
                    PredictionFinding.QuoteMismatch,
                "the price ceiling" to
                    orderTransaction(maxPrice = 900_000UL) to
                    PredictionFinding.QuoteMismatch,
                "what it costs" to
                    orderTransaction(cost = 9_000_000UL) to
                    PredictionFinding.QuoteMismatch,
                "the slippage" to
                    orderTransaction(slippageBps = 300) to
                    PredictionFinding.QuoteMismatch,
                "whose order it is" to
                    orderTransaction(orderOwner = SOMEONE_ELSE) to
                    PredictionFinding.NotTheOwnersOrder,
                "who pays" to
                    orderTransaction(payer = SOMEONE_ELSE) to
                    PredictionFinding.FeePayerNotTheWallet,
                "buying or selling" to
                    orderTransaction(buying = false) to
                    PredictionFinding.NotBuying,
                "how many orders" to orderTransaction(orders = 2) to PredictionFinding.ExtraOrder,
                "which token the order is in" to
                    orderTransaction(mint = USDC_MINT, route = false) to
                    PredictionFinding.MintMismatch,
                "whose account funds it" to
                    orderTransaction(
                        funding = associatedTokenAddress(SOMEONE_ELSE, JUP_USD_MINT)
                    ) to
                    PredictionFinding.FundingNotOwners,
                "where the funding lands" to
                    orderTransaction(routeDestination = associatedTokenAddress(OWNER, USDC_MINT)) to
                    PredictionFinding.FundingMismatch,
                "how much the funding takes" to
                    orderTransaction(routeIn = 9_000_000UL) to
                    PredictionFinding.DepositMismatch,
                "whose account the funding comes from" to
                    orderTransaction(
                        routeSource = associatedTokenAddress(SOMEONE_ELSE, USDC_MINT)
                    ) to
                    PredictionFinding.FundingNotOwners,
                "an account created for somebody else" to
                    orderTransaction(createFor = SOMEONE_ELSE) to
                    PredictionFinding.AccountCreationForSomeoneElse,
                "an account created for an unrelated token" to
                    orderTransaction(createMint = JUP_MINT) to
                    PredictionFinding.AccountCreationForSomeoneElse,
            )
        for ((named, expected) in cases) {
            val (what, built) = named
            val inspection = inspect(built)

            assertEquals(what, Verdict.Invalid, inspection.verdict)
            assertFalse(what, inspection.approvable)
            assertTrue("$what: ${codes(inspection)}", expected.code in codes(inspection))
        }
    }

    @Test
    fun theOnlySignatureStillMissingHasToBeTheOwnersOwn() {
        // The provider co-signs, so "nothing else signs" would be the wrong rule. This is the
        // right one, and both ways of breaking it are refused.
        assertEquals(Verdict.Verified, inspect(orderTransaction()).verdict)

        // The provider left its own slot empty, so two signatures are still wanted.
        val two = inspect(orderTransaction(protocolSigns = false))
        assertEquals(Verdict.Invalid, two.verdict)
        assertTrue(PredictionFinding.ExtraSigner.code in codes(two))

        // Or the owner's slot is already filled, which is not something to hand to a wallet.
        val filled = inspect(orderTransaction(ownerSigns = true))
        assertEquals(Verdict.Invalid, filled.verdict)
        assertTrue(PredictionFinding.NotTheOwnersToSign.code in codes(filled))
    }

    @Test
    fun anOrderWithNoOrderInItEstablishesNothing() {
        val inspection = inspect(orderTransaction(orders = 0))

        assertEquals(Verdict.Invalid, inspection.verdict)
        assertNull(inspection.facts)
        assertTrue(PredictionFinding.NoOrder.code in codes(inspection))
        assertTrue(inspection.details.isEmpty())
    }

    @Test
    fun anotherInstructionOfThePredictionProgramIsNotReadAsAnOrder() {
        // Selling a position, claiming a payout, a version of ordering this plugin was not written
        // for: all of them are the same program and none of them is an order.
        val other = Step(PREDICTION_PROGRAM, List(12) { OWNER }, ByteArray(40) { 3 })
        val inspection = inspect(orderTransaction(extra = listOf(other)))

        assertEquals(Verdict.Invalid, inspection.verdict)
        assertTrue(PredictionFinding.UnreadableValueInstruction.code in codes(inspection))
    }

    @Test
    fun anOrderInstructionWithTrailingBytesIsNotReadAtAll() {
        // The arguments must end exactly where the data does. A field appended by a later version
        // of the program is a layout this plugin does not know, and the honest answer is to refuse
        // rather than to read the part it recognizes.
        val inspection = inspect(orderTransaction(trailing = byteArrayOf(1, 2, 3)))

        assertTrue(PredictionFinding.NoOrder.code in codes(inspection))
        assertTrue(PredictionFinding.UnreadableValueInstruction.code in codes(inspection))
    }

    @Test
    fun anOrderWithAnOptionalSlippageCeilingIsStillRead() {
        // The layout ends in a Borsh option, and both spellings of it are read.
        val inspection = inspect(orderTransaction(maxSlippageBps = 150), order = null)

        assertEquals(codes(inspection).toString(), Verdict.Verified, inspection.verdict)
    }

    @Test
    fun anythingElseInTheTransactionIsSaidRatherThanAssumedHarmless() {
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
        val moved = inspect(orderTransaction(extra = listOf(transfer)))
        assertEquals(Verdict.Invalid, moved.verdict)
        assertTrue(PredictionFinding.ExtraTransfer.code in codes(moved))

        // An instruction from an unrelated program is not covered, which is not the same as being
        // wrong — and is still not approvable.
        val elsewhere = Step(POOL, listOf(OWNER), byteArrayOf(1, 2, 3))
        val uncovered = inspect(orderTransaction(extra = listOf(elsewhere)))
        assertEquals(Verdict.Unverified, uncovered.verdict)
        assertFalse(uncovered.approvable)
        assertEquals(listOf(PredictionFinding.UnrecognizedInstruction.code), codes(uncovered))
        assertFalse(uncovered.facts?.fullyRead == true)
    }

    @Test
    fun aTransactionWhoseAccountsCannotBeResolvedIsNeverReviewedInPart() {
        val built = orderTransaction()
        // Each of these stops the reading outright rather than producing a review of the part that
        // happened to be legible. That is the owner's own instruction: no parameter-only fallback
        // and no blind signature, and the caller turns each into a stated refusal to prepare.
        val chains =
            listOf(
                FakeChain(built.tables).apply { fails = SolanaProblem.Unreachable },
                FakeChain(built.tables).apply { fails = SolanaProblem.NoEndpoint },
                FakeChain(built.tables).apply { missing = setOf(TABLE_ONE) },
                FakeChain(built.tables).apply { owner = TOKEN_PROGRAM },
                FakeChain(built.tables).apply { deactivated = setOf(TABLE_ONE) },
                FakeChain(built.tables).apply { truncate = setOf(TABLE_ONE) },
                FakeChain(built.tables).apply { replace = mapOf(TABLE_ONE to listOf(OWNER)) },
            )
        for (chain in chains) {
            val thrown =
                try {
                    inspect(built, chain = chain)
                    throw AssertionError("it reviewed something")
                } catch (e: SolanaException) {
                    e.problem.code
                } catch (e: LookupException) {
                    e.problem.code
                }

            assertTrue(thrown, thrown.isNotEmpty())
        }
    }

    @Test
    fun bytesThePhoneCannotReadAtAllEstablishNothing() {
        val malformed = inspect(OrderBytes(ByteString.copyFrom(byteArrayOf(1, 2, 3)), emptyMap()))

        assertEquals(Verdict.Invalid, malformed.verdict)
        assertNull(malformed.facts)
        assertEquals(listOf(PredictionFinding.Malformed.code), codes(malformed))
    }

    @Test
    fun withoutTheOwnersWalletOnTheRightNetworkNothingIsEstablished() {
        val built = orderTransaction()

        val none = inspect(built, connected = false)
        assertEquals(Verdict.Invalid, none.verdict)
        assertTrue(PredictionFinding.NoWallet.code in codes(none))

        // There is no devnet prediction market to point at, and the app's own devnet transfer
        // tests are about something else entirely.
        val devnet = inspect(built, network = WalletNetwork.Devnet)
        assertEquals(Verdict.Invalid, devnet.verdict)
        assertTrue(PredictionFinding.OtherNetwork.code in codes(devnet))

        val other = inspect(built, walletAddress = SOMEONE_ELSE)
        assertEquals(Verdict.Invalid, other.verdict)
        assertTrue(PredictionFinding.FeePayerNotTheWallet.code in codes(other))
    }

    @Test
    fun anOrderStakedDirectlyInTheProvidersOwnTokenNeedsNoFundingSwap() {
        // The other supported shape: the owner already holds the provider's dollar token, so there
        // is nothing to route and the stake has to be exactly what the order costs.
        val direct = predictionTerms(depositMint = JUP_USD_MINT)
        val stake = 4_996_705UL
        val built = orderTransaction(terms = direct, deposit = stake, route = false, cost = stake)

        val inspection =
            inspect(
                built,
                terms = direct,
                choice = PredictionChoice(yes = true, deposit = stake),
            )

        assertEquals(codes(inspection).toString(), Verdict.Verified, inspection.verdict)
        assertEquals(JUP_USD_MINT, inspection.facts?.mint)
        // And an order that costs more than the owner staked is refused, because with no route
        // there is nothing between the two numbers.
        val over =
            inspect(
                orderTransaction(
                    terms = direct,
                    deposit = stake,
                    route = false,
                    cost = stake + 1UL,
                ),
                terms = direct,
                choice = PredictionChoice(yes = true, deposit = stake),
                order = null,
            )
        assertTrue(PredictionFinding.DepositMismatch.code in codes(over))
    }

    @Test
    fun theNumbersTheOwnerSeesAreTheOnesInTheBytes() {
        val inspection = inspect(orderTransaction())

        val values = inspection.details.map { it.value }
        // Contracts, in contracts. 23700000 millionths is 23.7 of them.
        assertEquals("23.7", values[0])
        // What it costs and what it pays out if the side wins, both in the deposit token's units.
        assertEquals("4.996705", values[1])
        assertEquals("23.7", values[2])
        // And the most one contract may cost.
        assertEquals("0.2", values[3])
    }
}
