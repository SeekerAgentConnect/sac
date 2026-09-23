package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.LookupException
import io.github.brrenat.seekervault.solana.LookupProblem
import io.github.brrenat.seekervault.solana.ResolvedTransaction
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.resolveLookups
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.transactions.decodeTransaction
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The order Jupiter really built, and the lookup tables it really named (SEE-94).
 *
 * This is the test that could not be written by inventing data. The whole claim of
 * `jupiter.prediction` is that the phone can resolve a versioned transaction's accounts from the
 * chain and then read the order out of the bytes — and a transaction the test wrote itself would
 * resolve by construction. So this one comes off the live API through `scripts/capture-jupiter.mjs
 * --orders`, together with the real contents of the real tables, read through the same read-only
 * method the app uses.
 *
 * It runs offline: the tables are in the fixture, so the test reaches nothing.
 */
@RunWith(AndroidJUnit4::class)
class PredictionFixturesTest {

    private class Fixture(json: JSONObject) {
        val owner: String = json.getString("owner")
        val marketId: String = json.getString("marketId")
        val eventId: String = json.getString("eventId")
        val depositMint: String = json.getString("depositMint")
        val deposit: ULong = json.getString("depositAmount").toULong()
        val yes: Boolean = json.getBoolean("isYes")
        val order: JSONObject = json.getJSONObject("order")
        val transaction: ByteString =
            ByteString.copyFrom(Base64.getDecoder().decode(json.getString("transaction")))
        val tables: Map<String, AccountSnapshot> =
            json.getJSONArray("tables").let { array ->
                (0 until array.length()).associate { index ->
                    val table = array.getJSONObject(index)
                    table.getString("address") to
                        AccountSnapshot(
                            owner = table.getString("owner"),
                            data = Base64.getDecoder().decode(table.getString("data")),
                            executable = false,
                        )
                }
            }
    }

    private val fixture: Fixture by lazy {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/jupiter/orders.json")) {
                    "fixtures/jupiter/orders.json is missing; run " +
                        "`node scripts/capture-jupiter.mjs --orders`"
                }
                .use { it.readBytes().decodeToString() }
        Fixture(JSONObject(text).getJSONArray("cases").getJSONObject(0))
    }

    /** The chain, as the fixture recorded it. Nothing here reaches a network. */
    private inner class Recorded : SolanaAccounts {
        val asked = mutableListOf<List<String>>()

        override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> {
            asked += addresses
            return addresses.map { fixture.tables[it] }
        }
    }

    private val terms
        get() =
            PredictionPayload(
                marketId = fixture.marketId,
                eventId = fixture.eventId,
                marketProvider = "polymarket",
                depositMint = fixture.depositMint,
                depositDecimals = 6,
                depositSymbol = "USDC",
                leastDeposit = LEAST_ORDER_DEPOSIT,
            )

    private val order
        get() =
            PredictionOrder(
                transaction = fixture.transaction,
                orderPubkey = fixture.order.getString("orderPubkey"),
                positionPubkey = fixture.order.getString("positionPubkey"),
                externalOrderId = fixture.order.getString("externalOrderId"),
                marketIdHash = fixture.order.getString("marketIdHash"),
                isYes = fixture.order.getBoolean("isYes"),
                isBuy = fixture.order.getBoolean("isBuy"),
                contractsMicro = fixture.order.getString("contractsMicro").toULong(),
                maxBuyPriceUsd = fixture.order.getString("maxBuyPriceUsd").toULong(),
                orderCostUsd = fixture.order.getString("orderCostUsd").toULong(),
                payoutUsd = fixture.order.getString("payoutUsd").toULong(),
                totalFeeUsd = fixture.order.getString("estimatedTotalFeeUsd").toULong(),
                slippageBps = fixture.order.getInt("slippageBps"),
                requiredSigners = listOf(fixture.owner),
            )

    private fun resolved(): ResolvedTransaction = runBlocking {
        val decoded =
            (decodeTransaction(fixture.transaction.toByteArray(), resolvable = true)
                    as DecodeResult.Decoded)
                .transaction
        resolveLookups(decoded, Recorded())
    }

    @Test
    fun theRealOrderIsRefusedUntilItsAccountsAreResolved() {
        // The default is what every other reviewer in this app uses, and it refuses this shape —
        // which is the whole reason the resolution exists.
        val refused = decodeTransaction(fixture.transaction.toByteArray())
        assertTrue(refused is DecodeResult.Failed)
        assertEquals(
            io.github.brrenat.seekervault.transactions.DecodeFailure.AddressTableLookup,
            (refused as DecodeResult.Failed).failure,
        )

        val decoded =
            (decodeTransaction(fixture.transaction.toByteArray(), resolvable = true)
                    as DecodeResult.Decoded)
                .transaction
        assertEquals(0, decoded.version)
        // Two signature slots, one of them already filled by the provider: the owner's is the only
        // one still missing, which is the rule the review is written against.
        assertEquals(2, decoded.signatureCount)
        assertEquals(listOf(0), decoded.emptySignatures)
        assertFalse(decoded.unsigned)
        assertEquals(fixture.owner, decoded.feePayer)
        assertTrue(decoded.lookups.isNotEmpty())
        // And on its own, most of what it touches is out of reach.
        assertTrue(
            decoded.instructions.any { instruction ->
                instruction.accountIndexes.any { it >= decoded.accounts.size }
            }
        )
    }

    @Test
    fun everyAccountResolvesFromTheTablesTheMessageItselfNames() {
        val chain = Recorded()
        val resolved = runBlocking {
            val decoded =
                (decodeTransaction(fixture.transaction.toByteArray(), resolvable = true)
                        as DecodeResult.Decoded)
                    .transaction
            resolveLookups(decoded, chain)
        }

        // The tables asked about are the ones the message names, in its own order, and nothing
        // else was asked about at all.
        assertEquals(
            listOf(resolved.transaction.lookups.map { it.table }),
            chain.asked,
        )
        assertTrue(resolved.accounts.size > resolved.static)
        // Every instruction now lands inside the list, program included.
        for (instruction in resolved.transaction.instructions) {
            assertNotNull(resolved.programOf(instruction))
            assertNotNull(resolved.accountsOf(instruction))
        }
    }

    @Test
    fun theOrderIsReadOutOfTheBytesAndAgreesWithTheProvidersOwnAnswer() {
        val placed =
            resolved().let { resolved ->
                resolved.transaction.instructions
                    .mapNotNull { resolved.readOrderStep(it) }
                    .filterIsInstance<OrderStep.Order>()
                    .single()
            }

        // Every field, against the JSON the provider sent beside the transaction. This is the check
        // that says the layout in `PredictionInstructions` is the program's own and not a guess.
        assertEquals(fixture.order.getString("externalOrderId"), placed.externalOrderId)
        assertEquals(fixture.order.getString("marketIdHash"), placed.marketHash)
        assertEquals(fixture.order.getBoolean("isBuy"), placed.buying)
        assertEquals(fixture.order.getBoolean("isYes"), placed.yes)
        assertEquals(fixture.order.getString("contractsMicro").toULong(), placed.contractsMicro)
        assertEquals(fixture.order.getString("maxBuyPriceUsd").toULong(), placed.maxPrice)
        assertEquals(fixture.order.getString("orderCostUsd").toULong(), placed.cost)
        assertEquals(fixture.order.getInt("slippageBps"), placed.slippageBps)
        assertEquals(fixture.order.getString("orderPubkey"), placed.order)
        assertEquals(fixture.order.getString("positionPubkey"), placed.position)
        // The owner pays and the order is theirs; the stake comes out of their own account for the
        // provider's own token, at the address this phone derives.
        assertEquals(fixture.owner, placed.payer)
        assertEquals(fixture.owner, placed.owner)
        assertEquals(JUP_USD_MINT, placed.mint)
        assertEquals(associatedTokenAddress(fixture.owner, JUP_USD_MINT), placed.funding)
    }

    @Test
    fun theFundingSwapIsReadByTheCodeThatReadsASwap() {
        val steps =
            resolved().let { resolved ->
                resolved.transaction.instructions.mapNotNull { resolved.readOrderStep(it) }
            }
        val placed = steps.filterIsInstance<OrderStep.Order>().single()
        val route =
            steps.mapNotNull { (it as? OrderStep.Funding)?.step as? SwapStep.Route }.single()

        // SEE-93's reader, unchanged, on a transaction from a different API: the deposit leaves the
        // owner's own account for the mint they chose, and lands in the very account the order then
        // spends from.
        assertEquals(fixture.owner, route.authority)
        assertEquals(associatedTokenAddress(fixture.owner, fixture.depositMint), route.source)
        assertEquals(placed.funding, route.destination)
        // This capture uses the routing variant that does not carry a source mint, and that is
        // not a gap: the source account was derived from the owner and the deposit mint just
        // above, which establishes the same fact by another route. The other variant does carry
        // it, and the inspection checks it when it is there.
        assertEquals(null, route.sourceMint)
        assertEquals(JUP_USD_MINT, route.destinationMint)
        assertEquals(fixture.deposit, route.inAmount)
        assertEquals(null, route.platformFee)
        assertEquals(0, route.platformFeeBps)
        // And the account it creates is the owner's own, for the token the order is placed in.
        val created = steps.mapNotNull { (it as? OrderStep.Funding)?.step as? SwapStep.Account }
        created.forEach {
            assertEquals(fixture.owner, it.owner)
            assertEquals(fixture.owner, it.payer)
            assertTrue(it.mint == JUP_USD_MINT || it.mint == fixture.depositMint)
        }
    }

    @Test
    fun theWholeOrderIsVerifiedAndSaysWhatTheOwnerIsRisking() {
        val inspection = runBlocking {
            inspectPrediction(
                terms = terms,
                choice = PredictionChoice(fixture.yes, fixture.deposit),
                order = order,
                wallet = wallet(fixture.owner),
                transaction = fixture.transaction,
                version = 1,
                chain = Recorded(),
            )
        }

        assertEquals(
            "the review refused a real order: ${inspection.findings.map { it.code }}",
            Verdict.Verified,
            inspection.verdict,
        )
        assertTrue(inspection.approvable)
        val facts = checkNotNull(inspection.facts)
        assertTrue(facts.fullyRead)
        assertEquals(fixture.owner, facts.wallet)
        // What leaves is the deposit token the owner chose, in the amount they entered.
        assertEquals(fixture.deposit, facts.amount)
        assertEquals(fixture.depositMint, facts.mint)
        // And where it goes is the order's own account, which is not the owner's — saying otherwise
        // would be the one dishonest thing available here.
        assertEquals(fixture.order.getString("orderPubkey"), facts.recipient)
        assertTrue(PREDICTION_PROGRAM in facts.programs.orEmpty())
        // The record keeps which order and which position, read out of the bytes.
        assertEquals(
            listOf(ORDER_ACCOUNT, POSITION_ACCOUNT, MARKET),
            inspection.references.map { it.key },
        )
        assertEquals(
            fixture.order.getString("positionPubkey"),
            inspection.references.first { it.key == POSITION_ACCOUNT }.value,
        )
    }

    @Test
    fun aChainThatCannotBeReadBlocksTheOrderRatherThanReviewingItsParameters() {
        // The owner's own instruction: no parameter-only review, and no blind signature. An order
        // whose accounts could not be resolved is not reviewed at all, and the reading stops with
        // the reason rather than returning something unapprovable to interpret.
        val problem =
            try {
                runBlocking {
                    inspectPrediction(
                        terms = terms,
                        choice = PredictionChoice(fixture.yes, fixture.deposit),
                        order = order,
                        wallet = wallet(fixture.owner),
                        transaction = fixture.transaction,
                        version = 4,
                        chain = FakeChain(),
                    )
                }
                throw AssertionError("it reviewed something")
            } catch (e: LookupException) {
                e.problem
            }

        assertEquals(LookupProblem.Missing, problem)
    }
}
