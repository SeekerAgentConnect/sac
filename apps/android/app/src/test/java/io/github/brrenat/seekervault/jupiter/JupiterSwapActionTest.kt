package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.operations.OrderChain
import io.github.brrenat.seekervault.plugins.ActionOperation
import io.github.brrenat.seekervault.plugins.JUPITER_PREDICTION
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.JUPITER_SWAP
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.SwapChoiceProblem
import io.github.brrenat.seekervault.plugins.actions.SwapParameterNames
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.SwapPayloadResult
import io.github.brrenat.seekervault.plugins.actions.SwapTermNames
import io.github.brrenat.seekervault.plugins.actions.swapPayloadFrom
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.rpc.serving
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.SolanaAccounts
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
 * Jupiter's `swap`, over an API that is stood in for (SEE-93, SEE-145).
 *
 * The bytes and the wire are tested elsewhere; this is about what the boundary asks of a provider
 * and the order it does it in — what the owner is asked for, what happens when the API fails, and
 * that two people acting on the same document get two different transactions.
 *
 * It goes through the real [JupiterExecutionProvider], so what is exercised is the same object the
 * app registers, dispatching on the same typed payload core hands it.
 */
@RunWith(AndroidJUnit4::class)
class JupiterSwapActionTest {

    /** A provider that records what it was asked and answers what the test tells it to. */
    private class Provider(
        var quote: ((SwapPayload, ULong, Int) -> JupiterQuote)? = null,
        var build: ((JupiterQuote, String) -> JupiterSwap)? = null,
    ) : JupiterProvider {
        val asked = mutableListOf<String>()

        override suspend fun quote(
            terms: SwapPayload,
            amount: ULong,
            slippageBps: Int,
            platformFeeBps: Int,
        ): JupiterQuote {
            asked +=
                "quote $amount $slippageBps" +
                    (if (platformFeeBps > 0) " fee $platformFeeBps" else "")
            return checkNotNull(quote) { "no quote was expected" }(terms, amount, slippageBps)
        }

        override suspend fun build(
            quote: JupiterQuote,
            wallet: String,
            feeAccount: String?,
        ): JupiterSwap {
            asked += "build $wallet" + (feeAccount?.let { " fee $it" } ?: "")
            return checkNotNull(build) { "no build was expected" }(quote, wallet)
        }
    }

    private val terms =
        mapOf(
            SwapTermNames.INPUT_MINT to USDC_MINT,
            SwapTermNames.INPUT_DECIMALS to "6",
            SwapTermNames.OUTPUT_MINT to SOL_MINT,
            SwapTermNames.OUTPUT_DECIMALS to "9",
            SwapTermNames.MAX_SLIPPAGE_BPS to "100",
        )

    private fun subject(
        environment: PluginEnvironment = PluginEnvironment.Production,
        wallet: SelectedWallet? = wallet(),
        values: Map<String, String> = terms,
    ) =
        ActionOperation(
            connectionId = "1f0b4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b",
            action = SWAP_ACTION,
            schemaVersion = 1,
            provider = JUPITER_PROVIDER,
            environment = environment,
            network = Network.NETWORK_MAINNET,
            // Core reads the publisher's terms once, against the action's own schema, and hands
            // the provider the typed result (SEE-145).
            payload =
                ActionPayload.Swap((swapPayloadFrom(values) as SwapPayloadResult.Valid).payload),
            // A broadcast proposal carries no request.
            request = null,
            wallet = wallet,
        )

    private fun chose(amount: ULong, slippage: Int = 50) =
        ParameterChoice(
            mapOf(
                SwapParameterNames.INPUT_AMOUNT to ParameterValue.Amount(amount),
                SwapParameterNames.SLIPPAGE_BPS to ParameterValue.Count(slippage.toUInt()),
            )
        )

    /** A provider that quotes honestly and builds the transaction the quote describes. */
    private fun honest(): Provider {
        val provider = Provider()
        provider.quote = { read, amount, slippage ->
            quoteFor(read, amount, outAmount = amount * 10UL, slippageBps = slippage)
        }
        provider.build = { quote, owner ->
            JupiterSwap(
                swapTransaction(
                    terms = usdcTerms(quote.inputMint, quote.outputMint),
                    amount = quote.inAmount,
                    quote = quote,
                    owner = owner,
                )
            )
        }
        return provider
    }

    private fun plugin(provider: JupiterProvider, at: Instant = Instant.ofEpochSecond(1_000)) =
        JupiterExecutionProvider(provider, FakePrediction(), OrderChain().serving()) { at }

    @Test
    fun itDeclaresWhoItIsWhatItServesAndWhereItServesIt() {
        val capabilities = plugin(Provider()).capabilities

        assertEquals("jupiter", capabilities.id.value)
        assertEquals(PROVIDER_CONTRACT, capabilities.contract)
        assertTrue(capabilities.contractSupported)
        // The legacy names a manifest written before SEE-145 still requires, declared rather than
        // parsed out of anything.
        assertEquals(setOf(JUPITER_SWAP, JUPITER_PREDICTION), capabilities.legacyPlugins)
        val swap = checkNotNull(capabilities.forAction(SWAP_ACTION))
        assertEquals(1..1, swap.schemaVersions)
        // One cluster, in both environments, because there is no devnet Jupiter.
        assertEquals(setOf(Network.NETWORK_MAINNET), swap.networks)
        assertNull("any pair the payload can name", swap.depositAssets)
        // Both environments, because a sandbox server is a supported server: its signals are read
        // and reviewed here, and preparing is the part it declines.
        assertEquals(
            setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            capabilities.environments,
        )
        // It answers status queries since SEE-172 — about a prediction order's fills, by the
        // order's
        // own account — and a swap, which has no order, still gets Unsupported.
        assertTrue(capabilities.statusQueries)
    }

    @Test
    fun twoOwnersActingOnOneSignalGetTwoDifferentTransactions() {
        // The acceptance, at this level: the document is the same, the amounts are theirs, and
        // nothing about either choice is anywhere but on their own phone.
        val provider = honest()
        val one = plugin(provider)
        val other = plugin(honest())

        val mine = runBlocking { one.prepare(subject(), chose(1_000_000UL)) }
        val theirs = runBlocking {
            other.prepare(
                subject(wallet = wallet(SOMEONE_ELSE)),
                chose(7_500_000UL, slippage = 25),
            )
        }

        assertFalse(mine.transaction == theirs.transaction)
        val readMine = one.inspect(subject(), chose(1_000_000UL), mine)
        val readTheirs =
            other.inspect(
                subject(wallet = wallet(SOMEONE_ELSE)),
                chose(7_500_000UL, slippage = 25),
                theirs,
            )
        assertEquals(Verdict.Verified, readMine.verdict)
        assertEquals(Verdict.Verified, readTheirs.verdict)
        assertEquals(1_000_000UL, readMine.facts?.amount)
        assertEquals(7_500_000UL, readTheirs.facts?.amount)
        assertEquals(OWNER, readMine.facts?.wallet)
        assertEquals(SOMEONE_ELSE, readTheirs.facts?.wallet)
        // Only the owner's own address ever reaches the provider, and only for the build.
        assertEquals(listOf("quote 1000000 50", "build $OWNER"), provider.asked)
    }

    @Test
    fun everyPreparationIsANewThingToApprove() {
        val provider = honest()
        val plugin = plugin(provider)

        val first = runBlocking { plugin.prepare(subject(), chose(1_000_000UL)) }
        val second = runBlocking { plugin.prepare(subject(), chose(1_000_000UL)) }

        // The version goes up even for the same amount, because a transaction prepared again is a
        // different transaction and consent given for the first is not consent for the second.
        assertEquals(1, first.version)
        assertEquals(2, second.version)
        assertEquals(2, plugin.inspect(subject(), chose(1_000_000UL), second).version)
    }

    @Test
    fun aPreparationStandsForOneMinuteAndSaysSo() {
        val at = Instant.ofEpochSecond(1_700_000_000)
        val prepared = runBlocking { plugin(honest(), at).prepare(subject(), chose(5UL)) }

        // A quote is a price from a moment ago and a blockhash lasts about a minute. Past that the
        // owner prepares again, which is what the binding's own expiry check enforces (SEE-89).
        assertEquals(at.epochSecond + 60, prepared.expiresAtEpochSeconds)
    }

    @Test
    fun preparingDoesNotReadTheEnvironment() {
        // Sandbox gets the same work as production: the same quote, the same build, the same bytes
        // (SEE-97). What differs is what happens afterwards, and afterwards is not this plugin's —
        // core holds the wallet, so core is what signs or rehearses. A plugin that decided for
        // itself would be a second place for the answer to be wrong, and a sandbox owner would be
        // reviewing something other than the thing production does.
        val provider = honest()
        val plugin = plugin(provider)

        val production = runBlocking { plugin.prepare(subject(), chose(5UL)) }
        val asked = provider.asked.toList()
        val sandbox = runBlocking { plugin.prepare(subject(PluginEnvironment.Sandbox), chose(5UL)) }

        assertEquals(production.transaction, sandbox.transaction)
        // The same two calls again, in the same order, for the same amount and the same wallet.
        assertEquals(asked + asked, provider.asked)
        // And the inspection a sandbox owner reads is the production one, not a summary of it.
        assertEquals(
            plugin.inspect(subject(), chose(5UL), production).findings,
            plugin.inspect(subject(PluginEnvironment.Sandbox), chose(5UL), sandbox).findings,
        )
    }

    @Test
    fun withoutAWalletOnTheRightNetworkNothingIsAsked() {
        val provider = Provider()

        for ((expected, subject) in
            listOf(
                "no_wallet" to subject(wallet = null),
                "other_network" to subject(wallet = wallet(network = WalletNetwork.Devnet)),
            )) {
            val failed = runBlocking {
                try {
                    plugin(provider).prepare(subject, chose(5UL))
                    throw AssertionError("it prepared something")
                } catch (e: PluginFailure) {
                    e
                }
            }
            assertEquals(expected, failed.code)
        }
        assertEquals(emptyList<String>(), provider.asked)
    }

    @Test
    fun aChoiceOutsideWhatWasPublishedNeverReachesTheProvider() {
        val provider = honest()

        val failed = runBlocking {
            try {
                plugin(provider).prepare(subject(), chose(5UL, slippage = 500))
                throw AssertionError("it prepared something")
            } catch (e: PluginFailure) {
                e
            }
        }

        assertEquals(SwapChoiceProblem.BadSlippage.code, failed.code)
        assertEquals(emptyList<String>(), provider.asked)
    }

    @Test
    fun aProviderThatFailsIsReportedAndNothingIsPrepared() {
        for (problem in JupiterProblem.entries) {
            val provider = Provider()
            provider.quote = { _, _, _ -> throw JupiterException(problem, "because") }

            val failed = runBlocking {
                try {
                    plugin(provider).prepare(subject(), chose(5UL))
                    throw AssertionError("it prepared something")
                } catch (e: PluginFailure) {
                    e
                }
            }

            assertEquals(problem.code, failed.code)
            // The provider's own words travel with it, for display and for nothing else.
            assertEquals("because", failed.detail)
        }
    }

    @Test
    fun aQuoteThisPhoneNoLongerHoldsIsSaidRatherThanAssumed() {
        // The offer cannot be recovered from the transaction, so bytes this plugin did not prepare
        // establish nothing. A restart, or a fifth signal opened, means preparing again — which is
        // the right thing to do with an offer nobody can vouch for.
        val inspection =
            plugin(honest())
                .inspect(
                    subject(),
                    chose(5UL),
                    PreparedOperation(
                        transaction = swapTransaction(usdcTerms(), 5UL, quoteFor(usdcTerms(), 5UL)),
                        version = 9,
                    ),
                )

        assertEquals(Verdict.Invalid, inspection.verdict)
        assertFalse(inspection.approvable)
        assertNull(inspection.facts)
        assertEquals(listOf("no_offer"), inspection.findings.map { it.code })
        assertEquals(9, inspection.version)
    }

    @Test
    fun theOwnersOwnChoiceIsWhatTheBytesAreCheckedAgainst() {
        // The choice is core's copy, handed in. A plugin that asked its provider for one amount
        // and is inspected against another is caught here rather than trusted about it.
        val plugin = plugin(honest())
        val prepared = runBlocking { plugin.prepare(subject(), chose(1_000_000UL)) }

        val reread = plugin.inspect(subject(), chose(2_000_000UL), prepared)

        assertEquals(Verdict.Invalid, reread.verdict)
        assertTrue(SwapFinding.AmountMismatch.code in reread.findings.map { it.code })
    }

    @Test
    fun aFieldTheOwnerLeftEmptyIsNotGuessedAt() {
        val plugin = plugin(honest())

        val failed = runBlocking {
            try {
                plugin.prepare(
                    subject(),
                    ParameterChoice(
                        mapOf(ParameterKey("input_amount") to ParameterValue.Count(5U))
                    ),
                )
                throw AssertionError("it prepared something")
            } catch (e: PluginFailure) {
                e
            }
        }

        assertEquals(SwapChoiceProblem.NoAmount.code, failed.code)
    }

    // --- The SAC service fee (SEE-173) -------------------------------------------------------

    private val feePolicy = SwapFeePolicy(20, FEE_OWNER, mapOf(SOL_MINT to FEE_ACCOUNT_SOL))

    /** A chain holding exactly the accounts given, and counting what it was asked. */
    private class Chain(vararg held: Pair<String, AccountSnapshot>) : SolanaAccounts {
        val accounts = held.toMap()
        val asked = mutableListOf<List<String>>()

        override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> {
            asked += addresses
            return addresses.map { accounts[it] }
        }
    }

    private fun feePlugin(provider: JupiterProvider, chain: SolanaAccounts) =
        JupiterExecutionProvider(provider, FakePrediction(), chain.serving(), feePolicy) {
            Instant.ofEpochSecond(1_000)
        }

    /** A provider that applies the fee it is asked for, to the account it is given. */
    private fun charging(feeAccount: String = FEE_ACCOUNT_SOL): Provider {
        val provider = Provider()
        provider.quote = { read, amount, slippage ->
            feeQuote(read, amount, gross = amount * 10UL, bps = 20, slippageBps = slippage)
        }
        provider.build = { quote, owner ->
            JupiterSwap(
                swapTransaction(
                    terms = usdcTerms(quote.inputMint, quote.outputMint),
                    amount = quote.inAmount,
                    quote = quote,
                    owner = owner,
                    platformFee = feeAccount,
                    platformFeeBps = quote.platformFeeBps,
                )
            )
        }
        return provider
    }

    @Test
    fun aFeeBuildVerifiesItsAccountThenQuotesBuildsAndInspectsWithExactlyThatFee() {
        val provider = charging()
        val chain = Chain(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, FEE_OWNER))
        val plugin = feePlugin(provider, chain)

        val prepared = runBlocking { plugin.prepare(subject(), chose(1_000_000UL)) }
        val read = plugin.inspect(subject(), chose(1_000_000UL), prepared)

        assertEquals(Verdict.Verified, read.verdict)
        // The account was read from the chain before anything was quoted, and the provider was
        // asked for this build's rate and handed this build's account — nothing else.
        assertEquals(listOf(listOf(FEE_ACCOUNT_SOL)), chain.asked)
        assertEquals(
            listOf("quote 1000000 50 fee 20", "build $OWNER fee $FEE_ACCOUNT_SOL"),
            provider.asked,
        )
        assertTrue(read.receipt.any { it.value == FEE_ACCOUNT_SOL })
    }

    @Test
    fun aProviderThatCreditsAnotherAccountNeverReachesTheWallet() {
        val plugin =
            feePlugin(
                charging(feeAccount = SOMEONE_ELSE_ACCOUNT),
                Chain(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, FEE_OWNER)),
            )

        val prepared = runBlocking { plugin.prepare(subject(), chose(1_000_000UL)) }
        val read = plugin.inspect(subject(), chose(1_000_000UL), prepared)

        assertEquals(Verdict.Invalid, read.verdict)
        assertFalse(read.approvable)
        assertTrue(read.findings.any { it.code == SwapFinding.FeeAccountMismatch.code })
    }

    @Test
    fun anAccountTheChainCannotConfirmMeansNoFeeAndTheReviewSaysSo() {
        val cases =
            listOf(
                "never created" to Chain(),
                "held by someone else" to
                    Chain(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, SOMEONE_ELSE)),
                "not initialized" to
                    Chain(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, FEE_OWNER, state = 0)),
            )
        for ((what, chain) in cases) {
            val provider = honest()
            val plugin = feePlugin(provider, chain)

            val prepared = runBlocking { plugin.prepare(subject(), chose(1_000_000UL)) }
            val read = plugin.inspect(subject(), chose(1_000_000UL), prepared)

            assertEquals(what, Verdict.Verified, read.verdict)
            // No fee asked for, no account handed over: an unverified account is never paid.
            assertEquals(what, listOf("quote 1000000 50", "build $OWNER"), provider.asked)
            assertTrue(
                what,
                read.details.any { it.label == R.string.jupiter_fact_service_fee_unverified },
            )
        }
    }

    @Test
    fun aPairWithoutAConfiguredAccountCarriesNoFeeAndReadsNothing() {
        val provider = honest()
        val chain = Chain()
        val plugin =
            JupiterExecutionProvider(
                provider,
                FakePrediction(),
                chain.serving(),
                SwapFeePolicy(20, FEE_OWNER, mapOf(JUP_MINT to FEE_ACCOUNT_USDC)),
            ) {
                Instant.ofEpochSecond(1_000)
            }

        val prepared = runBlocking { plugin.prepare(subject(), chose(1_000_000UL)) }
        val read = plugin.inspect(subject(), chose(1_000_000UL), prepared)

        assertEquals(Verdict.Verified, read.verdict)
        assertTrue(chain.asked.isEmpty())
        assertEquals(listOf("quote 1000000 50", "build $OWNER"), provider.asked)
        assertTrue(read.details.any { it.label == R.string.jupiter_fact_service_fee_not_for_pair })
    }

    @Test
    fun theProviderDescribesItsIntegrationAndItsOfficialPages() {
        val off = plugin(Provider()).about(subject())
        assertEquals(SWAP_ROUTING_NAME, off.name)
        assertEquals(R.string.jupiter_about_swap_role, off.role)
        assertTrue(off.notes.any { it.text == R.string.jupiter_about_swap_fee_off })
        assertEquals(
            listOf(METIS_DOCS_URL, SERVICE_FEE_DOCS_URL, JUPITER_TERMS_URL, JUPITER_PRIVACY_URL),
            off.links.map { it.url },
        )
        val on = feePlugin(Provider(), Chain()).about(subject())
        val note = on.notes.single { it.text == R.string.jupiter_about_swap_fee_on }
        assertEquals(listOf("0.2%"), note.args)
    }
}
