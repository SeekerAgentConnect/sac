package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.plugins.ActionSubject
import io.github.brrenat.seekervault.plugins.PLUGIN_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginPreparation
import io.github.brrenat.seekervault.plugins.SWAP_OPERATION
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plugin, over a provider that is stood in for (SEE-93).
 *
 * The bytes and the wire are tested elsewhere; this is about the three things the boundary asks of
 * a plugin and the order it does them in — what the owner is asked for, what happens when a
 * provider fails, and that two people acting on the same document get two different transactions.
 */
@RunWith(AndroidJUnit4::class)
class JupiterSwapPluginTest {

    /** A provider that records what it was asked and answers what the test tells it to. */
    private class Provider(
        var quote: ((SwapTerms, ULong, Int) -> JupiterQuote)? = null,
        var build: ((JupiterQuote, String) -> JupiterSwap)? = null,
    ) : JupiterProvider {
        val asked = mutableListOf<String>()

        override suspend fun quote(
            terms: SwapTerms,
            amount: ULong,
            slippageBps: Int,
        ): JupiterQuote {
            asked += "quote $amount $slippageBps"
            return checkNotNull(quote) { "no quote was expected" }(terms, amount, slippageBps)
        }

        override suspend fun build(quote: JupiterQuote, wallet: String): JupiterSwap {
            asked += "build $wallet"
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
        ActionSubject(
            connectionId = "1f0b4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b",
            operation = SWAP_OPERATION,
            environment = environment,
            // A broadcast proposal carries no request, and the plugin is handed the terms instead.
            request = null,
            wallet = wallet,
            terms = values,
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
        JupiterSwapPlugin(provider) { at }

    @Test
    fun itDeclaresWhatItIsAndWhatItServes() {
        val descriptor = plugin(Provider()).descriptor

        assertEquals("jupiter.swap", descriptor.id.value)
        assertEquals(PLUGIN_CONTRACT, descriptor.contract)
        assertTrue(descriptor.contractSupported)
        assertEquals(setOf(SWAP_OPERATION), descriptor.operations)
        // Both environments, because a sandbox server is a supported server: its signals are read
        // and reviewed here, and preparing is the part it declines.
        assertEquals(
            setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            descriptor.environments,
        )
    }

    @Test
    fun aSignalItCannotReadAsksForNothingAndSaysWhy() {
        val form = plugin(Provider()).parameters(subject(values = mapOf("pair" to "BTC/USD")))

        assertTrue(form.isEmpty)
        // And not merely empty: the owner is told which term was the trouble.
        val problem = assertNotNull(form.problem).let { form.problem!! }
        assertTrue(problem.code.startsWith(SwapTermProblem.Missing.code))
        assertTrue(problem.code.endsWith(SwapTermNames.INPUT_MINT))
        assertTrue(problem.invalidates)
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
    fun sandboxAsksTheProviderNothingAtAll() {
        // An environment that says it performs no purchase must not be able to make one, so this
        // is refused before the first network call rather than after a careful one.
        val provider = Provider()

        val failed = runBlocking {
            try {
                plugin(provider).prepare(subject(PluginEnvironment.Sandbox), chose(5UL))
                throw AssertionError("it prepared something")
            } catch (e: PluginFailure) {
                e
            }
        }

        assertEquals("sandbox_no_execution", failed.code)
        assertEquals(emptyList<String>(), provider.asked)
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
                    PluginPreparation(
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
}
