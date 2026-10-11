package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionCapability
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.ActionOperation
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.actions.SwapChoiceResult
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.message
import io.github.brrenat.seekervault.plugins.actions.swapChoiceFrom
import io.github.brrenat.seekervault.plugins.actions.swapInputs
import io.github.brrenat.seekervault.solana.NetworkAccounts
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jupiter's half of the `swap` action: executing a publisher's spot-swap signal with the owner's
 * own amount (SEE-93, SEE-145).
 *
 * It is one of the two actions [JupiterExecutionProvider] serves, and it takes nothing the boundary
 * does not hand it. It has an API of its own and no other reach: no credential, no wallet
 * authorization token, no way to approve or send. The owner's rules, their hand on the Approve
 * button and the one wallet interaction at a time are all still core's (docs/wiki/jupiter-swap.md).
 *
 * ## The three things it does
 *
 * - **Says what the owner has to choose.** How much of the input mint, and how much slippage they
 *   will tolerate within the publisher's ceiling. Both stay on the phone.
 * - **Gets the route and the bytes.** One quote and one build, from Jupiter, from this phone. If
 *   anything about that fails, it fails: there is no approximate preparation, and a build Jupiter's
 *   own simulation rejected is not offered to anybody.
 * - **Reads the bytes back.** Independently, out of the transaction, against the owner's choice
 *   ([inspectSwap]).
 *
 * What it no longer does is read the publisher's terms. The `swap` payload is the action's schema
 * rather than Jupiter's, so core reads it once and hands it over typed
 * ([io.github.brrenat.seekervault.plugins.actions.SwapPayload]) — which is what makes a second
 * provider of the same action possible without two readings of the same document.
 *
 * ## It does not read the environment, and that is the point
 *
 * Sandbox and production get the same work: the same quote, the same build, the same bytes, the
 * same inspection. What differs is what happens afterwards, and afterwards is not this code's —
 * core holds the wallet, so core is what signs or rehearses (SEE-97, docs/wiki/environments.md).
 *
 * ## And it is mainnet or nothing, in both environments
 *
 * Jupiter routes liquidity that exists on one network. There is no devnet Jupiter to point at, and
 * pretending otherwise would be the one thing worse than saying so: the capability declares mainnet
 * and the registry refuses a wallet selected for anything else before this code is reached
 * ([JupiterExecutionProvider]).
 */
internal class JupiterSwapAction(
    private val api: JupiterProvider,
    private val capability: ActionCapability,
    private val now: () -> Instant,
    /** The build's SAC service fee (SEE-173). Off unless the APK was built with one. */
    private val fee: SwapFeePolicy = SwapFeePolicy.Off,
    /**
     * Where a configured fee account is verified before it is used: the application's own endpoint
     * for the swap's network, the same one every other mainnet read uses (SEE-184).
     */
    private val chain: NetworkAccounts? = null,
) {

    // What was offered for the bytes that were built, keyed by those exact bytes. It is the only
    // thing this remembers, and a miss is a refusal rather than a guess: the offer cannot be
    // recovered from the transaction, and an inspection that invented one would be worse than an
    // inspection that says it has nothing to compare against.
    private val offers = Offers()

    private val preparations = AtomicInteger()

    fun inputs(payload: SwapPayload): ParameterForm = swapInputs(payload, capability)

    suspend fun prepare(
        operation: ActionOperation,
        payload: SwapPayload,
        choice: ParameterChoice,
    ): PreparedOperation {
        val wallet =
            operation.wallet ?: throw PluginFailure(NO_WALLET, R.string.jupiter_failure_no_wallet)
        if (wallet.network.network != SWAP_NETWORK) {
            throw PluginFailure(OTHER_NETWORK, R.string.jupiter_failure_other_network)
        }
        val chosen =
            when (val read = swapChoiceFrom(payload, capability, choice)) {
                is SwapChoiceResult.Valid -> read.choice
                is SwapChoiceResult.Invalid ->
                    throw PluginFailure(read.problem.code, read.problem.message)
            }
        // The fee is decided before anything is quoted, from the build's policy and the chain,
        // and then bound to the bytes with the quote: nothing later can change it (SEE-173).
        val decided =
            if (fee.enabled && chain != null) {
                decideSwapFee(fee, payload.outputMint, chain.on(wallet.network.network))
            } else {
                SwapFee.Disabled
            }
        val charged = decided as? SwapFee.Charged
        val quote =
            try {
                api.quote(payload, chosen.amount, chosen.slippageBps, decided.chargedBps)
            } catch (e: JupiterException) {
                throw e.asFailure()
            }
        val built =
            try {
                api.build(quote, wallet.address, charged?.account)
            } catch (e: JupiterException) {
                throw e.asFailure()
            }
        val version = preparations.incrementAndGet()
        offers.remember(built.transaction, SwapOffer(quote, decided))
        return PreparedOperation(
            transaction = built.transaction,
            version = version,
            // A quote is a price a moment ago and the transaction carries a blockhash that stops
            // being includable shortly. Both are why this window is short and why it is stated
            // rather than assumed: past it the owner prepares again and reviews again, which is
            // the only honest thing to do with an offer that has expired (SEE-93's own rule).
            expiresAtEpochSeconds = now().plus(SWAP_PREPARATION_LIFETIME).epochSecond,
        )
    }

    fun inspect(
        operation: ActionOperation,
        payload: SwapPayload,
        choice: ParameterChoice,
        prepared: PreparedOperation,
    ): ActionInspection {
        val chosen =
            when (val read = swapChoiceFrom(payload, capability, choice)) {
                is SwapChoiceResult.Valid -> read.choice
                is SwapChoiceResult.Invalid ->
                    return nothing(
                        PluginFinding(read.problem.code, read.problem.message),
                        prepared.version,
                    )
            }
        val offer =
            offers.forBytes(prepared.transaction)
                ?: return nothing(
                    PluginFinding(NO_OFFER, R.string.jupiter_finding_no_offer),
                    prepared.version,
                )
        return inspectSwap(
            terms = payload,
            choice = chosen,
            quote = offer.quote,
            wallet = operation.wallet,
            transaction = prepared.transaction,
            version = prepared.version,
            fee = offer.fee,
        )
    }

    private fun nothing(finding: PluginFinding, version: Int) =
        ActionInspection.nothingEstablished(version, listOf(finding))

    private companion object {
        const val NO_WALLET = "no_wallet"
        const val OTHER_NETWORK = "other_network"
        const val NO_OFFER = "no_offer"
    }
}

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

// Jupiter's failures become the boundary's, with Jupiter's own words carried through for display
// when it gave any. Nothing is parsed out of them.
internal fun JupiterException.asFailure(): PluginFailure =
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
 * The offers this has made, keyed by the bytes it made them for.
 *
 * Bounded, because the owner may have more than one signal open and none of them is worth holding
 * forever; and keyed by the bytes, so an entry can only ever describe the transaction it was
 * written beside. Losing one — the process restarted, the owner opened a fifth signal — means the
 * offer is prepared again, which is exactly what should happen to an offer nobody can vouch for.
 */
private class Offers(private val most: Int = 4) {
    private val held = LinkedHashMap<ByteString, SwapOffer>()

    fun remember(bytes: ByteString, offer: SwapOffer) =
        synchronized(held) {
            held.remove(bytes)
            held[bytes] = offer
            while (held.size > most) held.remove(held.keys.first())
        }

    fun forBytes(bytes: ByteString): SwapOffer? = synchronized(held) { held[bytes] }
}

/** The quote the owner is shown, and the fee decided for it before it was made (SEE-173). */
private data class SwapOffer(val quote: JupiterQuote, val fee: SwapFee)
