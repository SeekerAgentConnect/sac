package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
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
 * The sale review against transactions nobody should approve (SEE-172, acceptance criterion 6).
 *
 * Each case changes exactly one thing about a well-formed sale — whose it is, which position, how
 * much, the floor, where the proceeds go, what else it does — and asserts the review refuses it
 * **before** anything could reach a wallet: the verdict is not approvable and the finding names
 * why.
 */
@RunWith(AndroidJUnit4::class)
class PredictionSaleInspectionTest {

    private fun read(
        bytes: OrderBytes = saleTransaction(),
        close: PredictionClose = predictionClose(bytes),
        position: PredictionPosition = predictionPosition(),
        wallet: io.github.brrenat.seekervault.wallet.SelectedWallet? = wallet(),
        heldPosition: io.github.brrenat.seekervault.plugins.HeldPosition = held(),
    ): SaleRead = runBlocking {
        inspectPredictionSale(heldPosition, position, close, wallet, 1, FakeChain(bytes.tables))
    }

    private fun SaleRead.codes() = inspection.findings.map { it.code.substringBefore(':') }

    private fun assertRefused(read: SaleRead, finding: PredictionFinding) {
        assertFalse("approvable: ${read.codes()}", read.inspection.approvable)
        assertEquals(Verdict.Invalid, read.inspection.verdict)
        assertTrue("${finding.code} not in ${read.codes()}", finding.code in read.codes())
    }

    @Test
    fun aWellFormedGaslessSaleIsVerifiedAndSaysWhatItSells() {
        val read = read()

        assertEquals(read.codes().toString(), Verdict.Verified, read.inspection.verdict)
        assertTrue(read.inspection.approvable)
        val terms = read.terms
        assertEquals(HELD_CONTRACTS, terms.contractsMicro)
        assertEquals(HELD_CONTRACTS, terms.heldContractsMicro)
        assertEquals(SALE_FLOOR, terms.floorPriceMicroUsd)
        // 63.55 contracts at no less than 0.27: the least the fill can gross.
        assertEquals(17_158_500UL, terms.leastGrossMicroUsd)
        assertEquals(22_242_500UL, terms.estimatedGrossMicroUsd)
        assertEquals(associatedTokenAddress(OWNER, JUP_USD_MINT), terms.proceedsAccount)
        assertEquals(SALE_ORDER, terms.orderAccount)
        // The provider pays the network fee with its own pre-signed account, and says so.
        assertEquals(RELAYER, terms.sponsor)
        assertNull(terms.networkFeeLamports)
        // Nothing of the owner's is spent: no spending rule has anything to count.
        assertFalse(checkNotNull(read.inspection.facts).movesValue)
        assertEquals(
            listOf(ORDER_ACCOUNT, POSITION_ACCOUNT, MARKET),
            read.inspection.references.map { it.key },
        )
    }

    @Test
    fun theOwnerPayingTheirOwnFeeIsAcceptedWithTheFeeBounded() {
        val own = saleTransaction(payer = OWNER, createPayer = OWNER)
        val read = read(own)
        assertTrue(read.codes().toString(), read.inspection.approvable)
        assertEquals(7_500UL, read.terms.networkFeeLamports)

        val greedy =
            saleTransaction(
                payer = OWNER,
                createPayer = OWNER,
                priorityMicroLamports = 1_000_000_000UL,
            )
        assertRefused(read(greedy), PredictionFinding.ExcessiveFee)
    }

    @Test
    fun aMissingLimitIsChargedAtTheDefaultAndStillCapped() {
        // No limit instruction: the runtime charges the price over 200 000 units for each of the
        // two other instructions. 20 000 000 micro-lamports a unit is 0.003 SOL over an explicit
        // 150 000 units, under the cap, and 0.008 SOL over the default 400 000, over it.
        val price = 20_000_000UL
        val limited =
            saleTransaction(payer = OWNER, createPayer = OWNER, priorityMicroLamports = price)
        assertTrue(read(limited).codes().toString(), read(limited).inspection.approvable)
        assertEquals(3_000_000UL, read(limited).terms.networkFeeLamports)

        val unlimited =
            saleTransaction(
                payer = OWNER,
                createPayer = OWNER,
                priorityMicroLamports = price,
                unitLimit = null,
            )
        assertRefused(read(unlimited), PredictionFinding.ExcessiveFee)
        assertEquals(8_000_000UL, read(unlimited).terms.networkFeeLamports)

        // A modest price with no limit is still counted, over the default, and shown.
        val modest = saleTransaction(payer = OWNER, createPayer = OWNER, unitLimit = null)
        assertTrue(read(modest).codes().toString(), read(modest).inspection.approvable)
        assertEquals(20_000UL, read(modest).terms.networkFeeLamports)
    }

    @Test
    fun aFeeThatOverflowsOrASettingGivenTwiceIsRefused() {
        val overflowing =
            saleTransaction(
                payer = OWNER,
                createPayer = OWNER,
                priorityMicroLamports = ULong.MAX_VALUE,
            )
        assertRefused(read(overflowing), PredictionFinding.ExcessiveFee)
        assertNull(read(overflowing).terms.networkFeeLamports)

        val twice =
            saleTransaction(
                payer = OWNER,
                createPayer = OWNER,
                extra = listOf(Step(COMPUTE_BUDGET_PROGRAM, emptyList(), byteArrayOf(2) + u32(1U))),
            )
        assertRefused(read(twice), PredictionFinding.ExcessiveFee)
    }

    @Test
    fun defaultUnitsAndPriorityFeesFollowTheRuntime() {
        assertEquals(0UL, defaultComputeUnits(0))
        assertEquals(400_000UL, defaultComputeUnits(2))
        assertEquals(MOST_COMPUTE_UNITS, defaultComputeUnits(64))
        // Rounded up, as the runtime rounds it.
        assertEquals(1UL, priorityFeeLamports(1UL, 1UL))
        assertEquals(7_500UL, priorityFeeLamports(150_000UL, 50_000UL))
        assertNull(priorityFeeLamports(2UL, ULong.MAX_VALUE))
    }

    @Test
    fun anotherWalletCannotSellThisPosition() {
        // The wallet selected now is not the one that holds the position: a switch never
        // retargets it.
        val other = "7ToYommiXbxMdd7KaT8wgFYGzuWeHmGBvscGrgFTEWL2"
        assertRefused(read(wallet = wallet(other)), PredictionFinding.NotTheOwnersOrder)
    }

    @Test
    fun anotherNetworkIsRefused() {
        assertRefused(
            read(wallet = wallet(network = WalletNetwork.Devnet)),
            PredictionFinding.OtherNetwork,
        )
    }

    @Test
    fun anOrderForSomebodyElseIsRefused() {
        val someone = "4NboL3fNkq6KFPmTKtTVL8Ww9YzkVJ9n8aKRo27X8vs3"
        assertRefused(
            read(saleTransaction(orderOwner = someone)),
            PredictionFinding.NotTheOwnersOrder,
        )
    }

    @Test
    fun anotherPositionIsRefused() {
        val other = "AW7upnw3hJ8KEpFuEjw279ZwEf5uKZYSw8y8PShHCrKf"
        assertRefused(read(saleTransaction(position = other)), PredictionFinding.PositionMismatch)
    }

    @Test
    fun theOtherSideIsRefused() {
        assertRefused(read(saleTransaction(yes = false)), PredictionFinding.OutcomeMismatch)
    }

    @Test
    fun aBuyDressedAsASaleIsRefused() {
        assertRefused(read(saleTransaction(buying = true)), PredictionFinding.NotSelling)
    }

    @Test
    fun sellingFewerOrMoreThanTheWholePositionIsRefused() {
        // Fewer contracts than the position holds would leave a residue nobody chose.
        val fewer = saleTransaction(contracts = HELD_CONTRACTS / 2UL)
        assertRefused(
            read(fewer, predictionClose(fewer, contracts = HELD_CONTRACTS / 2UL)),
            PredictionFinding.QuantityMismatch,
        )
        // The position moved between the read and the build: what the bytes sell is not what was
        // reviewed.
        assertRefused(
            read(position = predictionPosition(contracts = HELD_CONTRACTS + 1_000_000UL)),
            PredictionFinding.QuantityMismatch,
        )
    }

    @Test
    fun aFloorFarBelowTheBidOrMissingIsWeakenedProtection() {
        // A quarter under the bid is the provider's own bound; a third under is not.
        val low = saleTransaction(floor = 200_000UL)
        assertRefused(
            read(low, predictionClose(low, floor = 200_000UL)),
            PredictionFinding.WeakFloor,
        )
        val none = saleTransaction(floor = 0UL)
        assertRefused(read(none, predictionClose(none, floor = 0UL)), PredictionFinding.WeakFloor)
        // A floor the bytes carry that is not the one the provider stated.
        assertRefused(
            read(close = predictionClose(saleTransaction(), floor = 300_000UL)),
            PredictionFinding.QuoteMismatch,
        )
        // Slippage the program would honour past the provider's bound.
        assertRefused(read(saleTransaction(maxSlippageBps = 5_000)), PredictionFinding.WeakFloor)
    }

    @Test
    fun noBidMeansNoProtectionToCheckAgainst() {
        assertRefused(read(position = predictionPosition(bid = null)), PredictionFinding.WeakFloor)
    }

    @Test
    fun proceedsToSomebodyElsesAccountAreRefused() {
        val elsewhere = checkNotNull(associatedTokenAddress(RELAYER, JUP_USD_MINT))
        assertRefused(
            read(saleTransaction(proceeds = elsewhere)),
            PredictionFinding.ProceedsNotOwners,
        )
    }

    @Test
    fun anotherMintIsRefused() {
        assertRefused(
            read(saleTransaction(mint = USDC_MINT, createFor = null)),
            PredictionFinding.MintMismatch,
        )
    }

    @Test
    fun aSaleThatCostsSomethingIsRefused() {
        assertRefused(read(saleTransaction(cost = 1_000_000UL)), PredictionFinding.QuoteMismatch)
    }

    @Test
    fun anUnexpectedTransferOrProgramIsRefused() {
        val transfer =
            Step(
                SYSTEM_PROGRAM,
                listOf(OWNER, RELAYER),
                byteArrayOf(2, 0, 0, 0) + u64(1_000_000_000UL),
            )
        assertRefused(
            read(saleTransaction(extra = listOf(transfer))),
            PredictionFinding.ExtraTransfer,
        )
        val tokenMove =
            Step(
                TOKEN_PROGRAM,
                listOf(
                    checkNotNull(associatedTokenAddress(OWNER, JUP_USD_MINT)),
                    checkNotNull(associatedTokenAddress(RELAYER, JUP_USD_MINT)),
                    OWNER,
                ),
                byteArrayOf(3) + u64(5_000_000UL),
            )
        // Read as a transfer or not read at all, a token instruction in a sale is refused.
        val moved = read(saleTransaction(extra = listOf(tokenMove)))
        assertFalse(moved.inspection.approvable)
        assertTrue(
            moved.codes().toString(),
            PredictionFinding.ExtraTransfer.code in moved.codes() ||
                PredictionFinding.UnreadableValueInstruction.code in moved.codes(),
        )
        val unknown = Step("11111111111111111111111111111112", listOf(OWNER), byteArrayOf(9))
        val read = read(saleTransaction(extra = listOf(unknown)))
        assertFalse(read.inspection.approvable)
        assertTrue(PredictionFinding.UnrecognizedInstruction.code in read.codes())
    }

    @Test
    fun anAccountCreatedForSomebodyElseIsRefused() {
        assertRefused(
            read(saleTransaction(createFor = RELAYER)),
            PredictionFinding.AccountCreationForSomeoneElse,
        )
    }

    @Test
    fun aSecondOrderIsRefused() {
        assertRefused(read(saleTransaction(orders = 2)), PredictionFinding.ExtraOrder)
    }

    @Test
    fun aFeePayerStillWaitingToSignIsASecondSigner() {
        // Gasless is accepted only when the relayer has already signed: otherwise somebody other
        // than the owner would still have to sign, which is refused.
        val read = read(saleTransaction(relayerSigned = false))
        assertFalse(read.inspection.approvable)
        assertTrue(PredictionFinding.ExtraSigner.code in read.codes())
        assertTrue(PredictionFinding.FeePayerNotTheWallet.code in read.codes())
    }

    @Test
    fun anAlreadySignedOwnerSlotIsRefused() {
        assertRefused(
            read(saleTransaction(ownerSigned = true)),
            PredictionFinding.NotTheOwnersToSign,
        )
    }

    @Test
    fun feesAtLeastTheLeastProceedsAreRefused() {
        val bytes = saleTransaction()
        assertRefused(
            read(bytes, predictionClose(bytes, fee = 17_158_500UL)),
            PredictionFinding.ExcessiveFee,
        )
    }

    @Test
    fun anotherOrderThanTheProviderDescribedIsRefused() {
        assertRefused(
            read(saleTransaction(externalOrderId = "ffff")),
            PredictionFinding.OrderMismatch,
        )
        assertRefused(read(saleTransaction(marketHash = "abcd")), PredictionFinding.MarketMismatch)
    }

    @Test
    fun bytesThatAreNotATransactionEstablishNothing() {
        val garbage =
            OrderBytes(com.google.protobuf.ByteString.copyFrom(ByteArray(12) { 1 }), emptyMap())
        val read = read(garbage, predictionClose(garbage))
        assertFalse(read.inspection.approvable)
        assertNull(read.inspection.facts)
    }
}
