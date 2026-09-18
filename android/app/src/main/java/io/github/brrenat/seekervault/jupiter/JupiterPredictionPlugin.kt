package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.ActionPlugin
import io.github.brrenat.seekervault.plugins.ActionSubject
import io.github.brrenat.seekervault.plugins.OperationId
import io.github.brrenat.seekervault.plugins.PLUGIN_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.PluginDescriptor
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PluginPreparation
import io.github.brrenat.seekervault.solana.LookupException
import io.github.brrenat.seekervault.solana.LookupProblem
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * `jupiter.prediction`: buying one side of a market a publisher pointed at (SEE-94).
 *
 * A publisher names a market and is believed about nothing else. The owner picks a side and a
 * stake, the provider builds the order, the phone resolves the transaction's accounts from the
 * chain and reads the order back out of the bytes, and the wallet signs once — after which the app
 * **stops**. There is no fill monitoring, no position screen, no settlement, no payout claim and no
 * profit or loss anywhere in it; the owner continues in Jupiter, which is what [destinations] is
 * for (docs/wiki/jupiter-prediction.md).
 *
 * ## Why this one reads the chain and the swap does not
 *
 * Because the provider gives it no choice. A swap can be asked for as a legacy transaction whose
 * every account is written into it; a prediction order comes only as a versioned transaction whose
 * accounts are behind address lookup tables. Signing that without resolving them would be signing
 * something whose effects the phone cannot see, so the tables are read from a configured, read-only
 * endpoint and the accounts rebuilt exactly as the runtime will — and if they cannot be, nothing is
 * prepared (`solana/AddressLookupTables.kt`, docs/security.md#resolving-a-lookup-table).
 *
 * ## What that costs, said plainly
 *
 * The review is then only as accurate as that endpoint. It is not offline verification, and this
 * plugin does not pretend otherwise: the dependency is documented, the endpoint is the
 * application's rather than any publisher's, and a build without one prepares nothing at all.
 */
class JupiterPredictionPlugin(
    private val provider: JupiterPrediction,
    private val chain: SolanaAccounts,
    private val now: () -> Instant = Instant::now,
) : ActionPlugin {

    override val descriptor: PluginDescriptor =
        PluginDescriptor(
            id = JUPITER_PREDICTION,
            contract = PLUGIN_CONTRACT,
            operations = setOf(PREDICTION_OPERATION),
            // Both, and the same work in each: what a sandbox owner reviews is the order this
            // plugin built from the live market, and core is what stops before the wallet
            // (SEE-97, docs/wiki/environments.md).
            environments = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
        )

    // What was offered for the bytes that were built, keyed by those exact bytes, as on the swap
    // side and for the same reason: an order cannot be recovered from a transaction, and an
    // inspection that invented one would be worse than one that says it has nothing to compare
    // against.
    private val offers = Orders()

    private val preparations = AtomicInteger()

    override fun parameters(subject: ActionSubject): ParameterForm =
        when (val terms = predictionTermsFrom(subject.terms)) {
            is PredictionTermsResult.Valid -> predictionParameters(terms.terms)
            is PredictionTermsResult.Invalid ->
                ParameterForm(problem = terms.problem.finding(terms.term))
        }

    override fun destinations(subject: ActionSubject): List<PluginDestination> {
        val terms = predictionTermsFrom(subject.terms)
        if (terms !is PredictionTermsResult.Valid) return emptyList()
        // The market on the provider's own platform, built from an identifier this plugin validated
        // and the provider answered about. There is deliberately **no position link**: the platform
        // has no per-position address, and inventing one would be the one dishonest thing on offer
        // here (docs/wiki/jupiter-prediction.md#where-the-owner-continues).
        return listOf(
            PluginDestination(
                label = R.string.jupiter_destination_market,
                url = "$JUPITER_PLATFORM/prediction/${terms.terms.marketId}",
            )
        )
    }

    override suspend fun prepare(
        subject: ActionSubject,
        choice: ParameterChoice,
    ): PluginPreparation {
        val terms =
            when (val read = predictionTermsFrom(subject.terms)) {
                is PredictionTermsResult.Valid -> read.terms
                is PredictionTermsResult.Invalid ->
                    throw PluginFailure(
                        read.problem.code,
                        R.string.jupiter_failure_prediction_terms,
                        read.term,
                    )
            }
        val wallet =
            subject.wallet ?: throw PluginFailure(NO_WALLET, R.string.jupiter_failure_no_wallet)
        if (wallet.network.network != SWAP_NETWORK) {
            throw PluginFailure(OTHER_NETWORK, R.string.jupiter_failure_other_network)
        }
        val chosen =
            when (val read = predictionChoiceFrom(terms, choice)) {
                is PredictionChoiceResult.Valid -> read.choice
                is PredictionChoiceResult.Invalid ->
                    throw PluginFailure(read.problem.code, read.problem.message)
            }
        // The market's own state, from the provider, at the moment the owner looks — never the
        // publisher's account of it.
        val market =
            try {
                provider.market(terms.marketId)
            } catch (e: PredictionException) {
                throw e.asFailure()
            }
        if (market.marketId != terms.marketId) {
            throw PluginFailure(
                PredictionProblem.Unusable.code,
                R.string.jupiter_failure_prediction_unusable,
                "another market",
            )
        }
        // A publisher that named an event is held to it: a signal cannot point at a market inside
        // an event it was not describing.
        if (terms.eventId.isNotEmpty() && market.eventId != terms.eventId) {
            throw PluginFailure(OTHER_EVENT, R.string.jupiter_failure_other_event)
        }
        if (terms.provider.isNotEmpty() && market.provider != terms.provider) {
            throw PluginFailure(OTHER_PROVIDER, R.string.jupiter_failure_other_provider)
        }
        if (!market.open) {
            throw PluginFailure(
                PredictionProblem.MarketClosed.code,
                R.string.jupiter_failure_market_closed,
                market.result?.let { "settled: $it" } ?: market.status,
            )
        }
        val order =
            try {
                provider.order(terms, chosen, wallet.address)
            } catch (e: PredictionException) {
                throw e.asFailure()
            }
        // Whose signature is still wanted, before the bytes are even read: the provider saying
        // somebody else has to sign is a reason to stop rather than something to check later.
        if (order.requiredSigners.any { it != wallet.address }) {
            throw PluginFailure(OTHER_SIGNER, R.string.jupiter_failure_other_signer)
        }
        val version = preparations.incrementAndGet()
        // Resolving the transaction's accounts reads the chain, so it happens here — where a
        // plugin is allowed to reach a network — and not in [inspect], which stays the pure reading
        // of bytes the boundary promises it is (SEE-86). A resolution that fails is a preparation
        // that fails, with its own reason: there is no parameter-only review anywhere in this
        // plugin, and nothing to sign until the accounts are known.
        val read =
            try {
                inspectPrediction(
                    terms = terms,
                    choice = chosen,
                    order = order,
                    wallet = wallet,
                    transaction = order.transaction,
                    version = version,
                    chain = chain,
                )
            } catch (e: SolanaException) {
                throw PluginFailure(e.problem.code, e.problem.message, e.detail)
            } catch (e: LookupException) {
                throw PluginFailure(e.problem.code, e.problem.message, e.detail)
            }
        offers.remember(
            order.transaction,
            Offer(terms, chosen, wallet.address, market, order, read),
        )
        return PluginPreparation(
            transaction = order.transaction,
            version = version,
            // An order is quoted at a price that moves and carries a blockhash that expires. Past
            // this the owner prepares again and reviews again, which is the only honest thing to
            // do with an offer nobody can vouch for any more.
            expiresAtEpochSeconds = now().plus(ORDER_LIFETIME).epochSecond,
        )
    }

    override fun inspect(
        subject: ActionSubject,
        choice: ParameterChoice,
        prepared: PluginPreparation,
    ): ActionInspection {
        val offer =
            offers.forBytes(prepared.transaction)
                ?: return nothing(
                    PluginFinding(NO_OFFER, R.string.jupiter_finding_no_order_held),
                    prepared.version,
                )
        val terms =
            when (val read = predictionTermsFrom(subject.terms)) {
                is PredictionTermsResult.Valid -> read.terms
                is PredictionTermsResult.Invalid ->
                    return nothing(read.problem.finding(read.term), prepared.version)
            }
        val chosen =
            when (val read = predictionChoiceFrom(terms, choice)) {
                is PredictionChoiceResult.Valid -> read.choice
                is PredictionChoiceResult.Invalid ->
                    return nothing(
                        PluginFinding(read.problem.code, read.problem.message),
                        prepared.version,
                    )
            }
        // The reading that `prepare` made, returned only when it is still about the same terms,
        // the same side, the same stake and the same wallet. A review shown for one of those must
        // never be shown again for another, and the bytes alone would not catch it.
        return offer.inspection(terms, chosen, subject.wallet?.address)
            ?: nothing(
                PluginFinding(NO_OFFER, R.string.jupiter_finding_no_order_held),
                prepared.version,
            )
    }

    private fun nothing(finding: PluginFinding, version: Int) =
        ActionInspection.nothingEstablished(version, listOf(finding))

    private companion object {
        const val NO_WALLET = "no_wallet"
        const val OTHER_NETWORK = "other_network"
        const val OTHER_EVENT = "other_event"
        const val OTHER_PROVIDER = "other_provider"
        const val OTHER_SIGNER = "other_signer"
        const val NO_OFFER = "no_order_held"
    }
}

/** This plugin's stable identity, which a server manifest may require (SEE-88). */
val JUPITER_PREDICTION: PluginId = PluginId("jupiter.prediction")

/** The operation it serves, named at the protocol's own level. */
val PREDICTION_OPERATION: OperationId = OperationId("prediction")

/** The provider's own platform, which is where the owner continues. */
const val JUPITER_PLATFORM: String = "https://jup.ag"

/**
 * How long a prepared order stands.
 *
 * A market's price moves and the transaction carries a recent blockhash, so this is the shorter
 * honest bound on both — the same minute a swap's preparation gets, for the same reasons.
 */
val ORDER_LIFETIME: Duration = Duration.ofSeconds(60)

/** The publisher's terms, as a finding about their document rather than about any bytes. */
private fun PredictionTermProblem.finding(term: String): PluginFinding =
    PluginFinding(code = "${code}:$term", message = message, invalidates = true)

private val PredictionTermProblem.message: Int
    get() =
        when (this) {
            PredictionTermProblem.Missing -> R.string.jupiter_prediction_missing
            PredictionTermProblem.NotAnIdentifier -> R.string.jupiter_prediction_not_an_identifier
            PredictionTermProblem.NotAMint -> R.string.jupiter_terms_not_a_mint
            PredictionTermProblem.UnsupportedMint -> R.string.jupiter_prediction_unsupported_mint
            PredictionTermProblem.BadDecimals -> R.string.jupiter_terms_bad_decimals
            PredictionTermProblem.BadAmount -> R.string.jupiter_terms_bad_amount
            PredictionTermProblem.ImpossibleAmounts -> R.string.jupiter_terms_impossible_amounts
            PredictionTermProblem.BadSymbol -> R.string.jupiter_terms_bad_symbol
        }

private val PredictionChoiceProblem.message: Int
    get() =
        when (this) {
            PredictionChoiceProblem.NoOutcome -> R.string.jupiter_choice_no_outcome
            PredictionChoiceProblem.BadOutcome -> R.string.jupiter_choice_bad_outcome
            PredictionChoiceProblem.NoDeposit -> R.string.jupiter_choice_no_amount
            PredictionChoiceProblem.TooLittle -> R.string.jupiter_choice_stake_too_little
            PredictionChoiceProblem.TooMuch -> R.string.jupiter_choice_too_much
        }

private val LookupProblem.message: Int
    get() =
        when (this) {
            LookupProblem.Unread -> R.string.jupiter_finding_tables_unread
            LookupProblem.Missing,
            LookupProblem.NotATable,
            LookupProblem.Malformed -> R.string.jupiter_finding_tables_unusable
            LookupProblem.Deactivated -> R.string.jupiter_finding_tables_deactivated
            LookupProblem.IndexOutOfRange,
            LookupProblem.AccountOutOfRange -> R.string.jupiter_finding_tables_inconsistent
        }

private val io.github.brrenat.seekervault.solana.SolanaProblem.message: Int
    get() =
        when (this) {
            io.github.brrenat.seekervault.solana.SolanaProblem.NoEndpoint ->
                R.string.jupiter_failure_no_rpc
            io.github.brrenat.seekervault.solana.SolanaProblem.Unreachable ->
                R.string.jupiter_failure_rpc_unreachable
            io.github.brrenat.seekervault.solana.SolanaProblem.RateLimited ->
                R.string.jupiter_failure_rpc_rate_limited
            io.github.brrenat.seekervault.solana.SolanaProblem.Refused ->
                R.string.jupiter_failure_rpc_refused
            io.github.brrenat.seekervault.solana.SolanaProblem.Unusable ->
                R.string.jupiter_failure_rpc_unusable
        }

// The provider's failures become the boundary's, with its own words carried through for display.
private fun PredictionException.asFailure(): PluginFailure =
    PluginFailure(
        code = problem.code,
        explanation =
            when (problem) {
                PredictionProblem.Unreachable -> R.string.jupiter_failure_unreachable
                PredictionProblem.RateLimited -> R.string.jupiter_failure_rate_limited
                PredictionProblem.NoSuchMarket -> R.string.jupiter_failure_no_such_market
                PredictionProblem.MarketClosed -> R.string.jupiter_failure_market_closed
                PredictionProblem.Refused -> R.string.jupiter_failure_refused
                PredictionProblem.InsufficientFunds -> R.string.jupiter_failure_insufficient_funds
                PredictionProblem.Unusable -> R.string.jupiter_failure_prediction_unusable
            },
        detail = detail,
    )

/** What was offered for one set of bytes, and what the phone made of them. */
private class Offer(
    val terms: PredictionTerms,
    val choice: PredictionChoice,
    val wallet: String,
    val market: PredictionMarket,
    val order: PredictionOrder,
    val read: ActionInspection,
) {
    /** The reading, when it is about the same terms, choice and wallet the owner has now. */
    fun inspection(
        terms: PredictionTerms,
        choice: PredictionChoice,
        wallet: String?,
    ): ActionInspection? = read.takeIf {
        terms == this.terms && choice == this.choice && wallet == this.wallet
    }
}

/**
 * The orders this plugin has prepared, keyed by the bytes it prepared them for.
 *
 * Bounded, as the swap's offers are, and for the same reasons. Losing one means preparing again,
 * which is the right thing to do with an order nobody can vouch for.
 */
private class Orders(private val most: Int = 4) {
    private val held = LinkedHashMap<ByteString, Offer>()

    fun remember(bytes: ByteString, offer: Offer) =
        synchronized(held) {
            held.remove(bytes)
            held[bytes] = offer
            while (held.size > most) held.remove(held.keys.first())
        }

    fun forBytes(bytes: ByteString): Offer? = synchronized(held) { held[bytes] }
}
