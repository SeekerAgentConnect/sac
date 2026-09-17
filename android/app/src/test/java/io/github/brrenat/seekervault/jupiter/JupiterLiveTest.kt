package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.transactions.Verdict
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real request to the real provider, opt-in (SEE-93).
 *
 * Every other test in this package runs against captured bytes or a stand-in, because a check that
 * needs the internet is not a check. This one exists for the question none of those can answer:
 * whether the live API still answers the way the fixtures were captured — whether it still serves
 * keyless requests, still honours `asLegacyTransaction`, and still builds a transaction this phone
 * can read whole.
 *
 * Run it with:
 *
 *     android/gradlew -p android :app:testDebugUnitTest \
 *       --tests '*JupiterLiveTest' -Dseekervault.jupiter=https://lite-api.jup.ag
 *
 * **It spends nothing.** A quote is a public read and a build returns unsigned bytes; nothing here
 * holds a key, and no wallet is opened by a unit test. The result of a real *swap* is a different
 * matter entirely, and is recorded by hand in `docs/testing/stage-7-1.md`.
 */
@RunWith(AndroidJUnit4::class)
class JupiterLiveTest {
    private val endpoint = System.getProperty("seekervault.jupiter")

    @Test
    fun theLiveProviderStillBuildsATransactionThisPhoneCanRead() {
        assumeTrue(
            "set -Dseekervault.jupiter=https://lite-api.jup.ag to run this",
            endpoint != null,
        )
        val provider = HttpJupiterProvider(OkHttpClient(), endpoint!!)
        val terms = usdcTerms(inputMint = SOL_MINT, outputMint = USDC_MINT, maxSlippageBps = 100)
        val amount = 100_000_000UL

        val quote = runBlocking { withTimeout(60_000) { provider.quote(terms, amount, 50) } }
        val built = runBlocking { withTimeout(60_000) { provider.build(quote, OWNER) } }
        val inspection =
            inspectSwap(
                terms = terms,
                choice = SwapChoice(amount, 50),
                quote = quote,
                wallet = wallet(OWNER),
                transaction = built.transaction,
                version = 1,
            )

        assertEquals(
            "the live provider built something this phone refused: " +
                inspection.findings.map { it.code },
            Verdict.Verified,
            inspection.verdict,
        )
        assertTrue(inspection.approvable)
        assertEquals(amount, inspection.facts?.amount)
        assertEquals(OWNER, inspection.facts?.recipient)
        // The offer and the floor, agreeing between the quote and the bytes. If Jupiter ever
        // changes where those numbers sit, this is the test that says so out loud.
        assertEquals(quote.minimumOut, quote.outAmount - quote.outAmount * 50UL / 10_000UL)
    }
}
