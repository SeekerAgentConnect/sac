package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.ActionPlugin
import io.github.brrenat.seekervault.plugins.ActionSubject
import io.github.brrenat.seekervault.plugins.PLUGIN_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.PluginDescriptor
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PluginPreparation
import io.github.brrenat.seekervault.plugins.SWAP_OPERATION
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * `jupiter.swap`: executing a publisher's spot-swap signal with the owner's own amount (SEE-93).
 *
 * It is the first plugin written against the boundary SEE-86 landed, and it takes nothing the
 * boundary does not hand it. It has a provider of its own and no other reach: no credential, no
 * wallet authorization token, no way to approve or send. The owner's rules, their hand on the
 * Approve button and the one wallet interaction at a time are all still core's
 * (docs/wiki/jupiter-swap.md).
 *
 * ## The three things it does
 *
 * - **Says what the owner has to choose.** How much of the input mint, and how much slippage they
 *   will tolerate within the publisher's ceiling. Both stay on the phone.
 * - **Gets the route and the bytes.** One quote and one build, from Jupiter, from this phone. If
 *   anything about that fails, it fails: there is no approximate preparation, and a build the
 *   provider's own simulation rejected is not offered to anybody.
 * - **Reads the bytes back.** Independently, out of the transaction, against the owner's choice
 *   ([inspectSwap]).
 *
 * ## Sandbox asks the provider nothing
 *
 * An environment that says it performs no purchase must not be able to make one, so sandbox is
 * refused before any network call rather than after a careful one: no quote, no build, nothing
 * prepared, nothing to sign (SEE-97 owns what sandbox grows into).
 *
 * ## And it is mainnet or nothing
 *
 * Jupiter routes liquidity that exists on one network. There is no devnet Jupiter to point at, and
 * pretending otherwise would be the one thing worse than saying so: the plugin refuses a wallet
 * selected for another network, and the app's existing devnet transfer and message tests are
 * unaffected because they are about a different thing entirely.
 */
class JupiterSwapPlugin(
    private val provider: JupiterProvider,
    private val now: () -> Instant = Instant::now,
) : ActionPlugin {

    override val descriptor: PluginDescriptor =
        PluginDescriptor(
            id = JUPITER_SWAP,
            contract = PLUGIN_CONTRACT,
            operations = setOf(SWAP_OPERATION),
            // Both, because a sandbox deployment is a supported deployment: its signals are read
            // and reviewed, and preparing is what it declines to do.
            environments = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
        )

    // What was offered for the bytes that were built, keyed by those exact bytes. It is the only
    // thing this plugin remembers, and a miss is a refusal rather than a guess: the offer cannot be
    // recovered from the transaction, and an inspection that invented one would be worse than an
    // inspection that says it has nothing to compare against.
    private val offers = Offers()

    private val preparations = AtomicInteger()

    override fun parameters(subject: ActionSubject): ParameterForm =
        when (val terms = swapTermsFrom(subject.terms)) {
            is SwapTermsResult.Valid -> swapParameters(terms.terms)
            is SwapTermsResult.Invalid -> ParameterForm(problem = terms.problem.finding(terms.term))
        }

    override suspend fun prepare(
        subject: ActionSubject,
        choice: ParameterChoice,
    ): PluginPreparation {
        // In this order on purpose. The environment is checked before anything is read and long
        // before anything is asked of the network, so "sandbox performs no purchase" is a property
        // of the first line rather than a promise made by the last one.
        if (subject.environment != PluginEnvironment.Production) {
            throw PluginFailure(SANDBOX, R.string.jupiter_failure_sandbox)
        }
        val terms =
            when (val read = swapTermsFrom(subject.terms)) {
                is SwapTermsResult.Valid -> read.terms
                is SwapTermsResult.Invalid ->
                    throw PluginFailure(
                        read.problem.code,
                        R.string.jupiter_failure_terms,
                        read.term,
                    )
            }
        val wallet =
            subject.wallet ?: throw PluginFailure(NO_WALLET, R.string.jupiter_failure_no_wallet)
        if (wallet.network.network != SWAP_NETWORK) {
            throw PluginFailure(OTHER_NETWORK, R.string.jupiter_failure_other_network)
        }
        val chosen =
            when (val read = swapChoiceFrom(terms, choice)) {
                is SwapChoiceResult.Valid -> read.choice
                is SwapChoiceResult.Invalid ->
                    throw PluginFailure(read.problem.code, read.problem.message)
            }
        val quote =
            try {
                provider.quote(terms, chosen.amount, chosen.slippageBps)
            } catch (e: JupiterException) {
                throw e.asFailure()
            }
        val built =
            try {
                provider.build(quote, wallet.address)
            } catch (e: JupiterException) {
                throw e.asFailure()
            }
        val version = preparations.incrementAndGet()
        offers.remember(built.transaction, quote)
        return PluginPreparation(
            transaction = built.transaction,
            version = version,
            // A quote is a price a moment ago and the transaction carries a blockhash that stops
            // being includable shortly. Both are why this window is short and why it is stated
            // rather than assumed: past it the owner prepares again and reviews again, which is
            // the only honest thing to do with an offer that has expired (SEE-93's own rule).
            expiresAtEpochSeconds = now().plus(SWAP_PREPARATION_LIFETIME).epochSecond,
        )
    }

    override fun inspect(
        subject: ActionSubject,
        choice: ParameterChoice,
        prepared: PluginPreparation,
    ): ActionInspection {
        val terms =
            when (val read = swapTermsFrom(subject.terms)) {
                is SwapTermsResult.Valid -> read.terms
                is SwapTermsResult.Invalid ->
                    return nothing(read.problem.finding(read.term), prepared.version)
            }
        val chosen =
            when (val read = swapChoiceFrom(terms, choice)) {
                is SwapChoiceResult.Valid -> read.choice
                is SwapChoiceResult.Invalid ->
                    return nothing(
                        PluginFinding(read.problem.code, read.problem.message),
                        prepared.version,
                    )
            }
        val quote =
            offers.forBytes(prepared.transaction)
                ?: return nothing(
                    PluginFinding(NO_OFFER, R.string.jupiter_finding_no_offer),
                    prepared.version,
                )
        return inspectSwap(
            terms = terms,
            choice = chosen,
            quote = quote,
            wallet = subject.wallet,
            transaction = prepared.transaction,
            version = prepared.version,
        )
    }

    private fun nothing(finding: PluginFinding, version: Int) =
        ActionInspection.nothingEstablished(version, listOf(finding))

    private companion object {
        const val SANDBOX = "sandbox_no_execution"
        const val NO_WALLET = "no_wallet"
        const val OTHER_NETWORK = "other_network"
        const val NO_OFFER = "no_offer"
    }
}

/** This plugin's stable identity, which a server manifest may require (SEE-88). */
val JUPITER_SWAP: PluginId = PluginId("jupiter.swap")

/**
 * How long a preparation stands.
 *
 * Two things expire underneath it and this is the shorter honest bound on both: the quote is a
 * price from a moment ago, and the transaction carries a recent blockhash, which a validator will
 * accept for about 150 slots — roughly a minute. So the owner has a minute from preparing to the
 * wallet opening, and after that the app prepares again rather than sending something that would
 * either be refused by the chain or filled at a price nobody looked at.
 */
val SWAP_PREPARATION_LIFETIME: Duration = Duration.ofSeconds(60)

/** The publisher's terms, as a finding about their document rather than about any bytes. */
private fun SwapTermProblem.finding(term: String): PluginFinding =
    PluginFinding(code = "${code}:$term", message = message, invalidates = true)

private val SwapTermProblem.message: Int
    get() =
        when (this) {
            SwapTermProblem.Missing -> R.string.jupiter_terms_missing
            SwapTermProblem.NotAMint -> R.string.jupiter_terms_not_a_mint
            SwapTermProblem.OneAsset -> R.string.jupiter_terms_one_asset
            SwapTermProblem.BadDecimals -> R.string.jupiter_terms_bad_decimals
            SwapTermProblem.BadSlippage -> R.string.jupiter_terms_bad_slippage
            SwapTermProblem.BadAmount -> R.string.jupiter_terms_bad_amount
            SwapTermProblem.ImpossibleAmounts -> R.string.jupiter_terms_impossible_amounts
            SwapTermProblem.BadSymbol -> R.string.jupiter_terms_bad_symbol
        }

private val SwapChoiceProblem.message: Int
    get() =
        when (this) {
            SwapChoiceProblem.NoAmount -> R.string.jupiter_choice_no_amount
            SwapChoiceProblem.TooLittle -> R.string.jupiter_choice_too_little
            SwapChoiceProblem.TooMuch -> R.string.jupiter_choice_too_much
            SwapChoiceProblem.BadSlippage -> R.string.jupiter_choice_bad_slippage
        }

// The provider's failures become the boundary's, with the provider's own words carried through for
// display when it gave any. Nothing is parsed out of them.
private fun JupiterException.asFailure(): PluginFailure =
    PluginFailure(
        code = problem.code,
        explanation =
            when (problem) {
                JupiterProblem.Unreachable -> R.string.jupiter_failure_unreachable
                JupiterProblem.RateLimited -> R.string.jupiter_failure_rate_limited
                JupiterProblem.NoRoute -> R.string.jupiter_failure_no_route
                JupiterProblem.Refused -> R.string.jupiter_failure_refused
                JupiterProblem.Unusable -> R.string.jupiter_failure_unusable
                JupiterProblem.WouldFail -> R.string.jupiter_failure_would_fail
            },
        detail = detail,
    )

/**
 * The offers this plugin has made, keyed by the bytes it made them for.
 *
 * Bounded, because the owner may have more than one signal open and none of them is worth holding
 * forever; and keyed by the bytes, so an entry can only ever describe the transaction it was
 * written beside. Losing one — the process restarted, the owner opened a fifth signal — means the
 * offer is prepared again, which is exactly what should happen to an offer nobody can vouch for.
 */
private class Offers(private val most: Int = 4) {
    private val held = LinkedHashMap<ByteString, JupiterQuote>()

    fun remember(bytes: ByteString, quote: JupiterQuote) =
        synchronized(held) {
            held.remove(bytes)
            held[bytes] = quote
            while (held.size > most) held.remove(held.keys.first())
        }

    fun forBytes(bytes: ByteString): JupiterQuote? = synchronized(held) { held[bytes] }
}
