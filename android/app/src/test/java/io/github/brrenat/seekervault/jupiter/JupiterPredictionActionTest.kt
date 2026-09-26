package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.plugins.ActionOperation
import io.github.brrenat.seekervault.plugins.JUPITER_PREDICTION
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionChoiceProblem
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.plugins.actions.PredictionPayloadResult
import io.github.brrenat.seekervault.plugins.actions.PredictionTermNames
import io.github.brrenat.seekervault.plugins.actions.predictionPayloadFrom
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jupiter's `prediction.buy`, over an API and a chain that are stood in for (SEE-94, SEE-145).
 *
 * The bytes and the resolution are tested elsewhere; this is about the order the plugin does things
 * in, which is where its promises live: the market is asked about before an order is requested, the
 * chain is read before anything is offered to sign, and each of those failing stops the operation
 * with its own reason rather than degrading the review.
 */
@RunWith(AndroidJUnit4::class)
class JupiterPredictionActionTest {
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
        JupiterExecutionProvider(NoSwaps, provider, chain) { at }

    /** A swap API that is never reached: these cases are all about the other action. */
    private object NoSwaps : JupiterProvider {
        override suspend fun quote(
            terms: io.github.brrenat.seekervault.plugins.actions.SwapPayload,
            amount: ULong,
            slippageBps: Int,
        ) = throw AssertionError("a prediction asked for a swap quote")

        override suspend fun build(quote: JupiterQuote, wallet: String) =
            throw AssertionError("a prediction asked for a swap build")
    }

    private fun subject(
        environment: PluginEnvironment = PluginEnvironment.Production,
        wallet: SelectedWallet? = wallet(),
        values: Map<String, String> = terms,
    ) =
        ActionOperation(
            connectionId = "5b1f4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b",
            action = PREDICTION_BUY_ACTION,
            schemaVersion = 1,
            provider = JUPITER_PROVIDER,
            environment = environment,
            network = Network.NETWORK_MAINNET,
            payload =
                ActionPayload.PredictionBuy(
                    (predictionPayloadFrom(values) as PredictionPayloadResult.Valid).payload
                ),
            request = null,
            wallet = wallet,
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
    fun itDeclaresTheActionTheVenuesStakeTokensAndItsSmallestOrder() {
        val capabilities = plugin().capabilities

        assertEquals("jupiter", capabilities.id.value)
        assertEquals(PROVIDER_CONTRACT, capabilities.contract)
        assertTrue(capabilities.contractSupported)
        assertTrue(JUPITER_PREDICTION in capabilities.legacyPlugins)
        val buy = checkNotNull(capabilities.forAction(PREDICTION_BUY_ACTION))
        assertEquals(1..1, buy.schemaVersions)
        assertEquals(setOf(Network.NETWORK_MAINNET), buy.networks)
        // The venue's own rules, declared rather than hidden inside the payload reader (SEE-145).
        assertEquals(DEPOSIT_MINTS, buy.depositAssets)
        assertEquals(LEAST_ORDER_DEPOSIT, buy.leastDeposit)
        assertEquals(
            setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            capabilities.environments,
        )
    }

    @Test
    fun theOwnerIsSentToTheMarketAndNeverToAnInventedPosition() {
        // The platform has no address for a position, so none is offered. A link that looked like
        // one would be the only dishonest thing available here.
        val destinations = plugin().destinations(subject())

        assertEquals(1, destinations.size)
        assertEquals("https://jup.ag/prediction/$MARKET_ID", destinations.single().url)
        // And the same address is the deep link, because `jup.ag` delegates its addresses to
        // Jupiter's own Android app. No scheme is invented to make it look more native (SEE-157).
        assertEquals("https://jup.ag/prediction/$MARKET_ID", destinations.single().deepLink)
    }

    @Test
    fun aPublishersAddressIsUsedOnlyWhenItIsJupitersOwn() {
        // The publisher read Jupiter's listing, so it knows the page's real address and this build
        // does not: left to itself the adapter composes one out of the market identifier, which
        // lands near the market rather than on it (SEE-157).
        val page = "https://jup.ag/prediction/fed-decision-in-october"
        val named =
            plugin()
                .destinations(
                    subject(
                        values =
                            terms +
                                mapOf(
                                    PredictionTermNames.PROVIDER_DEEP_LINK to page,
                                    PredictionTermNames.PROVIDER_WEB_URL to page,
                                )
                    )
                )
                .single()

        assertEquals(page, named.url)
        assertEquals(page, named.deepLink)
    }

    @Test
    fun aPublisherCannotSendTheOwnerSomewhereThatIsNotJupiter() {
        // The whole of the risk in letting a stranger name a destination. An address that is not
        // Jupiter's is ignored — not shown, not offered, not followed — and the adapter's own
        // address stands, so the owner still has somewhere real to go.
        //
        // Every one of these is an address core is happy to carry: they are well-formed `https`
        // and they are not Jupiter's, which is exactly the case core cannot decide and this
        // adapter can. What core refuses outright — code, storage, a page without the guarantee —
        // never reaches here at all (`PredictionActionTest`).
        for (elsewhere in
            listOf(
                "https://notjup.ag/prediction/x",
                "https://jup.ag.example.com/prediction/x",
                "https://evil.example.com/?next=https://jup.ag/prediction/x",
                "https://evil.example.com/jup.ag/prediction/x",
            )) {
            val destination =
                plugin()
                    .destinations(
                        subject(
                            values =
                                terms +
                                    mapOf(
                                        PredictionTermNames.PROVIDER_DEEP_LINK to elsewhere,
                                        PredictionTermNames.PROVIDER_WEB_URL to elsewhere,
                                    )
                        )
                    )
                    .single()

            assertEquals(elsewhere, "https://jup.ag/prediction/$MARKET_ID", destination.url)
            assertEquals(elsewhere, "https://jup.ag/prediction/$MARKET_ID", destination.deepLink)
        }
    }

    @Test
    fun theOrderIsOfferedOnlyOnceThereIsOne() {
        // Nothing has been ordered, so there is nowhere to go and look at one: the market is the
        // only destination, and an "Open order" with nothing behind it is not shown (SEE-157).
        assertEquals(1, plugin().destinations(subject()).size)

        val afterwards =
            plugin()
                .destinations(
                    subject(),
                    listOf(PluginReference(ORDER_ACCOUNT, "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9")),
                )

        assertEquals(2, afterwards.size)
        // Jupiter has no per-order page, so the order's destination is the portfolio the order is
        // actually in. An address with an order account in it would be a page nobody serves.
        assertEquals("https://jup.ag/prediction/portfolio", afterwards[1].url)
        assertEquals("https://jup.ag/prediction/portfolio", afterwards[1].deepLink)
        // A reference with nothing in it is not an order.
        assertEquals(
            1,
            plugin().destinations(subject(), listOf(PluginReference(ORDER_ACCOUNT, ""))).size,
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
    fun theMarketIsReadWhenTheReviewOpensAndAClosedOneIsSaidThere() {
        // `resolve` is a read and nothing else: the market's state, when the owner looks, so a
        // market that has already settled is a sentence on the screen rather than a preparation
        // waiting to fail. No order is asked for, and nothing is bound (SEE-145).
        provider.answersMarket = { openMarket(it, status = "closed", result = "yes") }

        val resolution = runBlocking { plugin().resolve(subject()) }

        assertEquals(PredictionProblem.MarketClosed.code, resolution.problem?.code)
        assertEquals(listOf("market $MARKET_ID"), provider.asked)
        // And what it says about itself, for the owner to read and for no rule to evaluate.
        assertEquals(listOf("closed", "yes"), resolution.details.map { it.value })
    }

    @Test
    fun anOpenMarketResolvesToTheFieldsAndTheVenuesOwnWords() {
        honest()

        val resolution = runBlocking { plugin().resolve(subject()) }

        assertNull(resolution.problem)
        assertEquals(
            listOf(PredictionParameterNames.OUTCOME, PredictionParameterNames.DEPOSIT),
            resolution.form.fields.map { it.key },
        )
    }

    @Test
    fun itAnswersNoStatusQueryRatherThanGuessingAtOne() {
        // There is no read here that could turn "submitted" into "filled", and nothing in this app
        // polls for one. Saying so is the honest answer (SEE-145).
        val answer = runBlocking {
            plugin()
                .status(
                    subject(),
                    io.github.brrenat.seekervault.plugins.PluginReference(ORDER_ACCOUNT, "x"),
                )
        }

        assertEquals(io.github.brrenat.seekervault.plugins.ActionStatus.Unsupported, answer)
        assertEquals(emptyList<String>(), provider.asked)
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
    fun theVenuesOwnFloorIsWhatTheOwnerIsShownAndWhatIsEnforced() {
        // The publisher set none, so the five-dollar minimum on the field is Jupiter's, carried
        // there by its capability rather than baked into the action's schema (SEE-145).
        val stake =
            plugin().inputs(subject()).fields[1].kind
                as io.github.brrenat.seekervault.plugins.ParameterKind.Amount

        assertEquals(LEAST_ORDER_DEPOSIT, stake.least)
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
                    PreparedOperation(
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
        val theirApi = FakePrediction()
        val theirs =
            JupiterExecutionProvider(NoSwaps, theirApi, chain) { Instant.ofEpochSecond(2_000) }
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
            theirApi.answersOrder = { _, _, _ ->
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
