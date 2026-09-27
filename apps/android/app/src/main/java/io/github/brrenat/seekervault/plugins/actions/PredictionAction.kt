package io.github.brrenat.seekervault.plugins.actions

import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.wallet.isSolanaAddress

/**
 * The `prediction.buy` payload a publisher broadcasts, as core reads it (SEE-94, SEE-145).
 *
 * The thing to notice is how little of it there is. A publisher names **which market**, and that is
 * all it is believed about: whether the market is open, what the sides currently cost, what the
 * rules are and when it settles all come from the execution provider's own API at the moment the
 * owner looks (docs/wiki/jupiter-prediction.md#the-publisher-names-a-market-and-nothing-else).
 *
 * That division is the whole reason a publisher can be a stranger. Prose is prose: a signal saying
 * "this is nearly certain" is shown as the publisher's opinion beside the market's own state, and
 * no field here can stand in for a fact the provider would have given.
 *
 * ## Why this is not in the Jupiter adapter any more
 *
 * It was, until SEE-145. It is the *action's* schema rather than Jupiter's: every provider of
 * `prediction.buy` has to mean the same thing by `market_id`, and has to refuse the same malformed
 * document in the same way. Reading it once, in core, before any provider is consulted, is the only
 * way that stays true when there is more than one of them — and it is what lets an owner be told
 * that a signal is unreadable without a provider having been reached at all.
 *
 * What stayed behind in the adapter is everything that is genuinely the venue's: which stake tokens
 * it settles in, the smallest order it accepts, its API's shapes, its program layouts and its
 * errors ([io.github.brrenat.seekervault.plugins.ActionCapability]).
 */
data class PredictionPayload(
    /** The provider's identifier for the market, which is looked up before anything else. */
    val marketId: String,
    /**
     * The event the market belongs to, when the publisher gives one. Cross-checked against the
     * event the provider names for the market, so a signal cannot point at a market inside an event
     * it was not describing.
     */
    val eventId: String = "",
    /**
     * The market's own venue — Kalshi, Polymarket — cross-checked the same way; empty when the
     * publisher gives none.
     *
     * It is the **market provider** and not the execution provider, and SEE-145 keeps the two words
     * apart deliberately. Jupiter is who builds the order; Kalshi is whose market it is about. A
     * market at one venue is not interchangeable with a similarly named market at another, and this
     * field is half of the instrument identity that says so ([Instrument]).
     */
    val marketProvider: String = "",
    /**
     * The token a stake is deposited in. Which tokens are acceptable is the provider's own rule.
     */
    val depositMint: String,
    /** Its base units per whole token, for display only. */
    val depositDecimals: Int,
    val depositSymbol: String = "",
    /** The least the publisher will have this acted on with, in the deposit mint's base units. */
    val leastDeposit: ULong = 0UL,
    /** The most, or null when the publisher set no ceiling. */
    val mostDeposit: ULong? = null,
    /**
     * Where the provider's own app keeps this market, when the publisher names it; empty otherwise
     * (SEE-157).
     *
     * It is the *native* destination — the address the provider's installed app answers for, by
     * whatever means that provider publishes: an App Link to its own site, or a scheme of its own.
     * It is carried, never loaded: this app opens no connection to it and reads nothing from it.
     *
     * A publisher writing this is not believed about it. Core checks only that it is a URI anything
     * may be handed ([isProviderLink]); whether it is the provider's own property is the provider's
     * own question, asked in its adapter, and a link that is not is ignored in favour of one the
     * adapter builds itself (docs/wiki/jupiter-prediction.md#where-the-owner-continues).
     */
    val providerDeepLink: String = "",
    /**
     * The same destination on the web, for when no app answers the deep link; empty when the
     * publisher names none.
     *
     * Held to the same rule and treated with the same suspicion. It is the fallback rather than the
     * first choice: a provider with an app installed opens in the app (SEE-157).
     */
    val providerWebUrl: String = "",
)

/**
 * The stake token's name as the owner reads it: the publisher's symbol, or, when it gave none, the
 * mint itself shortened at the middle, so an amount is never shown without saying of what.
 */
fun PredictionPayload.depositUnit(): String = depositSymbol.ifBlank {
    if (depositMint.length <= 17) depositMint
    else depositMint.take(8) + "…" + depositMint.takeLast(8)
}

/** The term names — the payload's contract with every publisher, spelled here once. */
object PredictionTermNames {
    const val MARKET_ID = "market_id"
    const val EVENT_ID = "event_id"
    const val PROVIDER = "provider"
    const val DEPOSIT_MINT = "deposit_mint"
    const val DEPOSIT_DECIMALS = "deposit_decimals"
    const val DEPOSIT_SYMBOL = "deposit_symbol"
    const val LEAST_DEPOSIT = "least_deposit"
    const val MOST_DEPOSIT = "most_deposit"

    /** Where the provider's own app keeps this market, and the same page on the web (SEE-157). */
    const val PROVIDER_DEEP_LINK = "provider_deep_link"

    const val PROVIDER_WEB_URL = "provider_web_url"
}

/** A market or event identifier: a bounded token, and never a URL or anything loadable. */
private val IDENTIFIER = Regex("""[A-Za-z0-9][A-Za-z0-9._:-]{0,63}""")

fun isMarketIdentifier(value: String): Boolean = IDENTIFIER.matches(value)

/** Which rule a publisher's market terms broke. */
enum class PredictionPayloadProblem(val code: String) {
    /** A required term is absent. */
    Missing("missing"),
    /** A market or event named by something that is not an identifier. */
    NotAnIdentifier("not_an_identifier"),
    /** A deposit token named by something that is not a mint address. */
    NotAMint("not_a_mint"),
    /** Units no mint can have. */
    BadDecimals("bad_decimals"),
    /** An amount bound that is not a whole number of base units. */
    BadAmount("bad_amount"),
    /** A floor above the publisher's own ceiling, so nothing satisfies both. */
    ImpossibleAmounts("impossible_amounts"),
    /** A label too long to show. */
    BadSymbol("bad_symbol"),
    /** A destination that is not somewhere this app would hand to another app (SEE-157). */
    NotALink("not_a_link"),
}

/** The owner-facing words for each rule. They stay in resources, not in code. */
val PredictionPayloadProblem.message: Int
    get() =
        when (this) {
            PredictionPayloadProblem.Missing -> R.string.action_prediction_terms_missing
            PredictionPayloadProblem.NotAnIdentifier ->
                R.string.action_prediction_terms_not_an_identifier
            PredictionPayloadProblem.NotAMint -> R.string.action_terms_not_a_mint
            PredictionPayloadProblem.BadDecimals -> R.string.action_terms_bad_decimals
            PredictionPayloadProblem.BadAmount -> R.string.action_terms_bad_amount
            PredictionPayloadProblem.ImpossibleAmounts -> R.string.action_terms_impossible_amounts
            PredictionPayloadProblem.BadSymbol -> R.string.action_terms_bad_symbol
            PredictionPayloadProblem.NotALink -> R.string.action_terms_not_a_link
        }

sealed interface PredictionPayloadResult {
    data class Valid(val payload: PredictionPayload) : PredictionPayloadResult

    data class Invalid(val problem: PredictionPayloadProblem, val term: String) :
        PredictionPayloadResult {
        /** The rule that broke, and the term it broke on, as one finding the owner is shown. */
        val finding: PluginFinding
            get() = PluginFinding("${problem.code}:$term", problem.message, invalidates = true)
    }
}

/**
 * Reads [terms] as a prediction market, or says which rule broke and where.
 *
 * A term this reader does not know is ignored and never consulted, exactly as on the swap side: a
 * publisher may say more than this reads, and nothing in the extra can change what is prepared.
 */
fun predictionPayloadFrom(terms: Map<String, String>): PredictionPayloadResult {
    val marketId =
        terms[PredictionTermNames.MARKET_ID]?.takeIf { it.isNotBlank() }
            ?: return missing(PredictionTermNames.MARKET_ID)
    if (!isMarketIdentifier(marketId)) {
        return invalid(PredictionPayloadProblem.NotAnIdentifier, PredictionTermNames.MARKET_ID)
    }
    val eventId = terms[PredictionTermNames.EVENT_ID].orEmpty()
    if (eventId.isNotEmpty() && !isMarketIdentifier(eventId)) {
        return invalid(PredictionPayloadProblem.NotAnIdentifier, PredictionTermNames.EVENT_ID)
    }
    val marketProvider = terms[PredictionTermNames.PROVIDER].orEmpty()
    if (marketProvider.length > MOST_SYMBOL_LENGTH) {
        return invalid(PredictionPayloadProblem.BadSymbol, PredictionTermNames.PROVIDER)
    }
    val depositMint =
        terms[PredictionTermNames.DEPOSIT_MINT]?.takeIf { it.isNotBlank() }
            ?: return missing(PredictionTermNames.DEPOSIT_MINT)
    if (!isSolanaAddress(depositMint)) {
        return invalid(PredictionPayloadProblem.NotAMint, PredictionTermNames.DEPOSIT_MINT)
    }
    val decimals =
        terms[PredictionTermNames.DEPOSIT_DECIMALS]?.toIntOrNull()?.takeIf {
            it in 0..MOST_DECIMALS
        }
            ?: return if (terms[PredictionTermNames.DEPOSIT_DECIMALS].isNullOrBlank()) {
                missing(PredictionTermNames.DEPOSIT_DECIMALS)
            } else {
                invalid(PredictionPayloadProblem.BadDecimals, PredictionTermNames.DEPOSIT_DECIMALS)
            }
    val symbol =
        terms[PredictionTermNames.DEPOSIT_SYMBOL].orEmpty().takeIf {
            it.length <= MOST_SYMBOL_LENGTH
        } ?: return invalid(PredictionPayloadProblem.BadSymbol, PredictionTermNames.DEPOSIT_SYMBOL)
    val least =
        if (PredictionTermNames.LEAST_DEPOSIT in terms) {
            terms[PredictionTermNames.LEAST_DEPOSIT]?.toULongOrNull()
                ?: return invalid(
                    PredictionPayloadProblem.BadAmount,
                    PredictionTermNames.LEAST_DEPOSIT,
                )
        } else 0UL
    val most =
        if (PredictionTermNames.MOST_DEPOSIT in terms) {
            terms[PredictionTermNames.MOST_DEPOSIT]?.toULongOrNull()
                ?: return invalid(
                    PredictionPayloadProblem.BadAmount,
                    PredictionTermNames.MOST_DEPOSIT,
                )
        } else null
    if (most != null && (most == 0UL || most < least)) {
        return invalid(PredictionPayloadProblem.ImpossibleAmounts, PredictionTermNames.MOST_DEPOSIT)
    }
    // Where the owner may carry on, when the publisher says (SEE-157). A term that is present and
    // unusable stops the read rather than being dropped quietly: a publisher that meant to send
    // somebody somewhere and wrote something else should be told, and an owner should not be shown
    // a signal that half-named a destination.
    val deepLink = terms[PredictionTermNames.PROVIDER_DEEP_LINK].orEmpty()
    if (deepLink.isNotEmpty() && !isProviderLink(deepLink)) {
        return invalid(PredictionPayloadProblem.NotALink, PredictionTermNames.PROVIDER_DEEP_LINK)
    }
    val webUrl = terms[PredictionTermNames.PROVIDER_WEB_URL].orEmpty()
    if (webUrl.isNotEmpty() && !isProviderLink(webUrl)) {
        return invalid(PredictionPayloadProblem.NotALink, PredictionTermNames.PROVIDER_WEB_URL)
    }
    return PredictionPayloadResult.Valid(
        PredictionPayload(
            marketId = marketId,
            eventId = eventId,
            marketProvider = marketProvider,
            depositMint = depositMint,
            depositDecimals = decimals,
            depositSymbol = symbol,
            // The publisher's floor, and only the publisher's. The provider's own minimum is a
            // fact about the venue and is applied where the venue is known
            // ([io.github.brrenat.seekervault.plugins.ActionCapability.leastDeposit]).
            leastDeposit = least,
            mostDeposit = most,
            providerDeepLink = deepLink,
            providerWebUrl = webUrl,
        )
    )
}

private fun missing(term: String) =
    PredictionPayloadResult.Invalid(PredictionPayloadProblem.Missing, term)

private fun invalid(problem: PredictionPayloadProblem, term: String) =
    PredictionPayloadResult.Invalid(problem, term)
