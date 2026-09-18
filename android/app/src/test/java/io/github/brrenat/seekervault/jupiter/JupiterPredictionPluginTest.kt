package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.plugins.ActionSubject
import io.github.brrenat.seekervault.plugins.PLUGIN_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginPreparation
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plugin, over a provider and a chain that are stood in for (SEE-94).
 *
 * The bytes and the resolution are tested elsewhere; this is about the order the plugin does things
 * in, which is where its promises live: the market is asked about before an order is requested, the
 * chain is read before anything is offered to sign, and each of those failing stops the operation
 * with its own reason rather than degrading the review.
 */
@RunWith(AndroidJUnit4::class)
class JupiterPredictionPluginTest {
    private val provider = FakePrediction()
    private val terms =
        mapOf(
            PredictionTermNames.MARKET_ID to MARKET_ID,
            PredictionTermNames.EVENT_ID to EVENT_ID,
            PredictionTermNames.PROVIDER to "polymarket",
            PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
            PredictionTermNames.DEPOSIT_DECIMALS to "6",
            PredictionTermNames.DEPOSIT_SYMBOL to "USDC",
        )

    // The chain serves whatever table the last order built, which is what a real one would do.
    private val chain = ReplayChain()

    private class ReplayChain : io.github.brrenat.seekervault.solana.SolanaAccounts {
        var tables: Map<String, List<String>> = emptyMap()
        var fails: SolanaProblem? = null

        override suspend fun accounts(
            addresses: List<String>
        ): List<io.github.brrenat.seekervault.solana.AccountSnapshot?> {
            fails?.let { throw io.github.brrenat.seekervault.solana.SolanaException(it) }
            return addresses.map { tables[it]?.let { held -> tableFor(held) } }
        }
    }

    private fun plugin(at: Instant = Instant.ofEpochSecond(2_000)) =
        JupiterPredictionPlugin(provider, chain) { at }

    private fun subject(
        environment: PluginEnvironment = PluginEnvironment.Production,
        wallet: SelectedWallet? = wallet(),
        values: Map<String, String> = terms,
    ) =
        ActionSubject(
            connectionId = "5b1f4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b",
            operation = PREDICTION_OPERATION,
            environment = environment,
            request = null,
            wallet = wallet,
            terms = values,
        )

    private fun chose(yes: Boolean = true, stake: ULong = 5_000_000UL) =
        ParameterChoice(
            mapOf(
                PredictionParameterNames.OUTCOME to
                    ParameterValue.Selected(
                        if (yes) PredictionOutcomes.YES else PredictionOutcomes.NO
                    ),
                PredictionParameterNames.DEPOSIT to ParameterValue.Amount(stake),
            )
        )

    /** A provider that builds the order it was asked for, and a chain that can resolve it. */
    private fun honest() {
        provider.answersOrder = { terms, choice, wallet ->
            val built =
                orderTransaction(
                    terms = terms,
                    yes = choice.yes,
                    deposit = choice.deposit,
                    owner = wallet,
                )
            chain.tables = built.tables
            predictionOrder(built.transaction, yes = choice.yes, owner = wallet)
        }
    }

    private fun failure(block: suspend () -> Unit): PluginFailure = runBlocking {
        try {
            block()
            throw AssertionError("it prepared something")
        } catch (e: PluginFailure) {
            e
        }
    }

    @Test
    fun itDeclaresWhatItIsAndWhatItServes() {
        val descriptor = plugin().descriptor

        assertEquals("jupiter.prediction", descriptor.id.value)
        assertEquals(PLUGIN_CONTRACT, descriptor.contract)
        assertTrue(descriptor.contractSupported)
        assertEquals(setOf(PREDICTION_OPERATION), descriptor.operations)
        assertEquals(
            setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            descriptor.environments,
        )
    }

    @Test
    fun theOwnerIsSentToTheMarketAndNeverToAnInventedPosition() {
        // The platform has no address for a position, so none is offered. A link that looked like
        // one would be the only dishonest thing available here.
        val destinations = plugin().destinations(subject())

        assertEquals(1, destinations.size)
        assertEquals("https://jup.ag/prediction/$MARKET_ID", destinations.single().url)
        // And a signal this plugin cannot read is sent nowhere at all.
        assertEquals(
            emptyList<Any>(),
            plugin().destinations(subject(values = mapOf("market" to "something"))),
        )
    }

    @Test
    fun theMarketIsAskedAboutBeforeAnOrderIsRequested() {
        honest()
        val prepared = runBlocking { plugin().prepare(subject(), chose()) }

        assertEquals(
            listOf("market $MARKET_ID", "order $MARKET_ID yes=true 5000000 $OWNER"),
            provider.asked,
        )
        assertEquals(1, prepared.version)
        // A minute, for the same reasons a swap's preparation gets one.
        assertEquals(
            Instant.ofEpochSecond(2_000).plus(ORDER_LIFETIME).epochSecond,
            prepared.expiresAtEpochSeconds,
        )
    }

    @Test
    fun aClosedMarketIsRefusedAndNoOrderIsRequested() {
        // The publisher's prose cannot make a settled market open, which is the whole reason the
        // market is asked about rather than taken from the signal.
        for (market in
            listOf(
                openMarket(status = "closed"),
                openMarket(status = "cancelled"),
                openMarket(result = "yes"),
            )) {
            provider.asked.clear()
            provider.answersMarket = { market }

            val failed = failure { plugin().prepare(subject(), chose()) }

            assertEquals(PredictionProblem.MarketClosed.code, failed.code)
            assertEquals(listOf("market $MARKET_ID"), provider.asked)
        }
    }

    @Test
    fun aMarketInsideAnotherEventOrFromAnotherSourceIsRefused() {
        provider.answersMarket = { openMarket(eventId = "POLY-000000") }
        assertEquals("other_event", failure { plugin().prepare(subject(), chose()) }.code)

        provider.answersMarket = { openMarket(provider = "somewhere-else") }
        assertEquals("other_provider", failure { plugin().prepare(subject(), chose()) }.code)
    }

    @Test
    fun anOrderTheProviderWantsAnotherSignatureForIsRefused() {
        honest()
        provider.answersOrder = { terms, choice, wallet ->
            val built =
                orderTransaction(
                    terms = terms,
                    yes = choice.yes,
                    deposit = choice.deposit,
                    owner = wallet,
                )
            chain.tables = built.tables
            predictionOrder(built.transaction, yes = choice.yes, owner = wallet)
                .copy(requiredSigners = listOf(wallet, SOMEONE_ELSE))
        }

        assertEquals("other_signer", failure { plugin().prepare(subject(), chose()) }.code)
    }

    @Test
    fun aChainThatCannotBeReadStopsThePreparationWithItsOwnReason() {
        // The owner's own instruction: block signing and say why, rather than fall back to a
        // parameter-only review.
        honest()
        for (problem in
            listOf(
                SolanaProblem.NoEndpoint,
                SolanaProblem.Unreachable,
                SolanaProblem.RateLimited,
            )) {
            chain.fails = problem

            val failed = failure { plugin().prepare(subject(), chose()) }

            assertEquals(problem.code, failed.code)
        }
        chain.fails = null
    }

    @Test
    fun preparingDoesNotReadTheEnvironment() {
        // The same work in both, as on the swap side and for the same reason: what a sandbox owner
        // reviews is the order this plugin built from the live market, and core is what stops
        // before the wallet (SEE-97).
        honest()
        val production = runBlocking { plugin().prepare(subject(), chose()) }
        val asked = provider.asked.toList()
        provider.asked.clear()

        val sandbox = runBlocking {
            plugin().prepare(subject(PluginEnvironment.Sandbox), chose())
        }

        assertEquals(production.transaction, sandbox.transaction)
        assertEquals(asked, provider.asked)
    }

    @Test
    fun withoutAWalletOnTheRightNetworkNothingIsAsked() {
        assertEquals(
            "no_wallet",
            failure { plugin().prepare(subject(wallet = null), chose()) }.code,
        )
        assertEquals(
            "other_network",
            failure {
                plugin().prepare(subject(wallet = wallet(network = WalletNetwork.Devnet)), chose())
            }
                .code,
        )
        assertEquals(emptyList<String>(), provider.asked)
    }

    @Test
    fun aChoiceOutsideWhatWasPublishedNeverReachesTheProvider() {
        honest()

        val failed = failure { plugin().prepare(subject(), chose(stake = 1_000_000UL)) }

        assertEquals(PredictionChoiceProblem.TooLittle.code, failed.code)
        assertEquals(emptyList<String>(), provider.asked)
    }

    @Test
    fun aSignalThisPluginCannotReadAsksForNothingAndSaysWhichTermWasWrong() {
        val form = plugin().parameters(subject(values = mapOf("market" to MARKET_ID)))

        assertTrue(form.isEmpty)
        val problem = checkNotNull(form.problem)
        assertTrue(problem.code.startsWith(PredictionTermProblem.Missing.code))
        assertTrue(problem.code.endsWith(PredictionTermNames.MARKET_ID))
    }

    @Test
    fun theReviewIsTheOneThatWasMadeForTheseTermsAndThisChoice() {
        honest()
        val one = plugin()
        val prepared = runBlocking { one.prepare(subject(), chose()) }

        val read = one.inspect(subject(), chose(), prepared)
        assertEquals(Verdict.Verified, read.verdict)
        assertTrue(read.approvable)

        // Asked about with a different side, or a different stake, it has nothing to show: the
        // review was of an order for what the owner chose then.
        assertFalse(one.inspect(subject(), chose(yes = false), prepared).approvable)
        assertEquals(
            listOf("no_order_held"),
            one.inspect(subject(), chose(stake = 6_000_000UL), prepared).findings.map { it.code },
        )
        // And bytes it never prepared establish nothing — a different order, so genuinely bytes
        // this plugin has never seen.
        assertEquals(
            listOf("no_order_held"),
            one.inspect(
                    subject(),
                    chose(),
                    PluginPreparation(
                        orderTransaction(order = SOMEONE_ELSE, cost = 4_000_000UL).transaction,
                        version = 9,
                    ),
                )
                .findings
                .map { it.code },
        )
    }

    @Test
    fun twoOwnersBackTwoSidesOfOneMarketWithTheirOwnStakes() {
        honest()
        val mine = plugin()
        val theirs =
            JupiterPredictionPlugin(FakePrediction(), chain) { Instant.ofEpochSecond(2_000) }
        val second = "7EqQdEULxWcraVx3mXKFjc84LhCkMGZCkRuDpvcMwJeK"

        val one = runBlocking { mine.prepare(subject(), chose(yes = true, stake = 5_000_000UL)) }
        val other = runBlocking {
            val built =
                orderTransaction(
                    yes = false,
                    deposit = 8_000_000UL,
                    owner = second,
                    cost = 7_900_000UL,
                )
            chain.tables = built.tables
            (theirs.javaClass.getDeclaredField("provider").apply { isAccessible = true }.get(theirs)
                    as FakePrediction)
                .answersOrder =
                { _, _, _ ->
                    predictionOrder(
                        built.transaction,
                        yes = false,
                        owner = second,
                        orderCostUsd = 7_900_000UL,
                    )
                }
            theirs.prepare(
                subject(wallet = wallet(second)),
                chose(yes = false, stake = 8_000_000UL),
            )
        }

        assertFalse(one.transaction == other.transaction)
        val readMine = mine.inspect(subject(), chose(yes = true, stake = 5_000_000UL), one)
        assertEquals(Verdict.Verified, readMine.verdict)
        assertEquals(5_000_000UL, readMine.facts?.amount)
        assertEquals(OWNER, readMine.facts?.wallet)
    }
}
