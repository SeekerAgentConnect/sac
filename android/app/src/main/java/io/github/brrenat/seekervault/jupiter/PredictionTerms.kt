package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.wallet.isSolanaAddress

/**
 * The market payload a publisher broadcasts, as this plugin reads it (SEE-94).
 *
 * The thing to notice is how little of it there is. A publisher names **which market**, and that is
 * all it is believed about: whether the market is open, what the sides currently cost, what the
 * rules are and when it settles all come from the provider's own API at the moment the owner looks
 * (docs/wiki/jupiter-prediction.md#the-publisher-names-a-market-and-nothing-else).
 *
 * That division is the whole reason a publisher can be a stranger. Prose is prose: a signal saying
 * "this is nearly certain" is shown as the publisher's opinion beside the market's own state, and
 * no field here can stand in for a fact the provider would have given.
 */
data class PredictionTerms(
    /** The provider's identifier for the market, which the plugin looks up before anything else. */
    val marketId: String,
    /**
     * The event the market belongs to, when the publisher gives one. Cross-checked against the
     * event the provider names for the market, so a signal cannot point at a market inside an event
     * it was not describing.
     */
    val eventId: String = "",
    /** The market's own source, cross-checked the same way; empty when the publisher gives none. */
    val provider: String = "",
    /** The token a stake is deposited in. One of the two the provider takes. */
    val depositMint: String,
    /** Its base units per whole token, for display only. */
    val depositDecimals: Int,
    val depositSymbol: String = "",
    /** The least the publisher will have this acted on with, in the deposit mint's base units. */
    val leastDeposit: ULong = 0UL,
    /** The most, or null when the publisher set no ceiling. */
    val mostDeposit: ULong? = null,
)

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
}

/**
 * The mints the provider takes a deposit in: its own dollar token, and USDC.
 *
 * A closed set, because a deposit mint is not a free parameter — a publisher naming something else
 * would be naming a token the provider will not accept, and the honest moment to say so is when the
 * signal is read rather than when the order is refused.
 */
const val JUP_USD_MINT: String = "JuprjznTrTSp2UFa3ZBUFgwdAmtZCq4MQCwysN55USD"

/** The other one, which the provider swaps into its own before the order is placed. */
const val USDC_MINT: String = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

val DEPOSIT_MINTS: Set<String> = setOf(JUP_USD_MINT, USDC_MINT)

/**
 * The smallest order the provider accepts: five dollars, in the deposit mint's base units.
 *
 * It is the provider's rule rather than the publisher's or the app's, and it is enforced on this
 * side of the boundary so that an amount too small to act on is said before anything is asked of
 * anybody (docs/integrations/jupiter.md#prediction-orders).
 */
const val LEAST_ORDER_DEPOSIT: ULong = 5_000_000UL

/** A market or event identifier: a bounded token, and never a URL or anything loadable. */
private val IDENTIFIER = Regex("""[A-Za-z0-9][A-Za-z0-9._:-]{0,63}""")

fun isMarketIdentifier(value: String): Boolean = IDENTIFIER.matches(value)

/** Which rule a publisher's market terms broke. */
enum class PredictionTermProblem(val code: String) {
    /** A required term is absent. */
    Missing("missing"),
    /** A market or event named by something that is not an identifier. */
    NotAnIdentifier("not_an_identifier"),
    /** A deposit token named by something that is not a mint address. */
    NotAMint("not_a_mint"),
    /** A mint address, but not one the provider takes a deposit in. */
    UnsupportedMint("unsupported_mint"),
    /** Units no mint can have. */
    BadDecimals("bad_decimals"),
    /** An amount bound that is not a whole number of base units. */
    BadAmount("bad_amount"),
    /** A floor above the publisher's own ceiling, so nothing satisfies both. */
    ImpossibleAmounts("impossible_amounts"),
    /** A label too long to show. */
    BadSymbol("bad_symbol"),
}

sealed interface PredictionTermsResult {
    data class Valid(val terms: PredictionTerms) : PredictionTermsResult

    data class Invalid(val problem: PredictionTermProblem, val term: String) : PredictionTermsResult
}

/**
 * Reads [terms] as a prediction market, or says which rule broke and where.
 *
 * A term this plugin does not know is ignored and never consulted, exactly as on the swap side: a
 * publisher may say more than this reads, and nothing in the extra can change what is prepared.
 */
fun predictionTermsFrom(terms: Map<String, String>): PredictionTermsResult {
    val marketId =
        terms[PredictionTermNames.MARKET_ID]?.takeIf { it.isNotBlank() }
            ?: return missing(PredictionTermNames.MARKET_ID)
    if (!isMarketIdentifier(marketId)) {
        return invalid(PredictionTermProblem.NotAnIdentifier, PredictionTermNames.MARKET_ID)
    }
    val eventId = terms[PredictionTermNames.EVENT_ID].orEmpty()
    if (eventId.isNotEmpty() && !isMarketIdentifier(eventId)) {
        return invalid(PredictionTermProblem.NotAnIdentifier, PredictionTermNames.EVENT_ID)
    }
    val provider = terms[PredictionTermNames.PROVIDER].orEmpty()
    if (provider.length > MOST_SYMBOL_LENGTH) {
        return invalid(PredictionTermProblem.BadSymbol, PredictionTermNames.PROVIDER)
    }
    val depositMint =
        terms[PredictionTermNames.DEPOSIT_MINT]?.takeIf { it.isNotBlank() }
            ?: return missing(PredictionTermNames.DEPOSIT_MINT)
    if (!isSolanaAddress(depositMint)) {
        return invalid(PredictionTermProblem.NotAMint, PredictionTermNames.DEPOSIT_MINT)
    }
    if (depositMint !in DEPOSIT_MINTS) {
        return invalid(PredictionTermProblem.UnsupportedMint, PredictionTermNames.DEPOSIT_MINT)
    }
    val decimals =
        terms[PredictionTermNames.DEPOSIT_DECIMALS]?.toIntOrNull()?.takeIf {
            it in 0..MOST_DECIMALS
        }
            ?: return if (terms[PredictionTermNames.DEPOSIT_DECIMALS].isNullOrBlank()) {
                missing(PredictionTermNames.DEPOSIT_DECIMALS)
            } else {
                invalid(PredictionTermProblem.BadDecimals, PredictionTermNames.DEPOSIT_DECIMALS)
            }
    val symbol =
        terms[PredictionTermNames.DEPOSIT_SYMBOL].orEmpty().takeIf {
            it.length <= MOST_SYMBOL_LENGTH
        } ?: return invalid(PredictionTermProblem.BadSymbol, PredictionTermNames.DEPOSIT_SYMBOL)
    val least =
        if (PredictionTermNames.LEAST_DEPOSIT in terms) {
            terms[PredictionTermNames.LEAST_DEPOSIT]?.toULongOrNull()
                ?: return invalid(
                    PredictionTermProblem.BadAmount,
                    PredictionTermNames.LEAST_DEPOSIT,
                )
        } else 0UL
    val most =
        if (PredictionTermNames.MOST_DEPOSIT in terms) {
            terms[PredictionTermNames.MOST_DEPOSIT]?.toULongOrNull()
                ?: return invalid(PredictionTermProblem.BadAmount, PredictionTermNames.MOST_DEPOSIT)
        } else null
    if (most != null && (most == 0UL || most < least)) {
        return invalid(PredictionTermProblem.ImpossibleAmounts, PredictionTermNames.MOST_DEPOSIT)
    }
    return PredictionTermsResult.Valid(
        PredictionTerms(
            marketId = marketId,
            eventId = eventId,
            provider = provider,
            depositMint = depositMint,
            depositDecimals = decimals,
            depositSymbol = symbol,
            // The provider's own minimum is a floor under every publisher's: one that asked for
            // less than the provider accepts has asked for an order that cannot be placed.
            leastDeposit = maxOf(least, LEAST_ORDER_DEPOSIT),
            mostDeposit = most,
        )
    )
}

private fun missing(term: String) =
    PredictionTermsResult.Invalid(PredictionTermProblem.Missing, term)

private fun invalid(problem: PredictionTermProblem, term: String) =
    PredictionTermsResult.Invalid(problem, term)
