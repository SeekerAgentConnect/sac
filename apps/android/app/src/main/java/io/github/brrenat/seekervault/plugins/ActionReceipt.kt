package io.github.brrenat.seekervault.plugins

import androidx.annotation.StringRes

/**
 * Who carries an operation out, and what it costs beyond the network (SEE-173).
 *
 * Two things a review has to make plain and a record has to keep: the integration that routed or
 * placed the operation — named as the provider requires it to be named — and any service fee this
 * app's build adds. They are neither the owner's rules' business ([InspectedAction]) nor
 * identifiers ([PluginReference]'s ordinary use), so they travel as their own list: stable keys a
 * History screen knows how to label, and values already formatted for display.
 *
 * Every value is public — a product name, a rate, an estimated amount, a mint, a token account —
 * and never a URL, for the same reason [PluginReference] is never one.
 */
object ReceiptKey {
    /** The integration a swap is routed through, as it must be shown (e.g. "Metis · …"). */
    const val SWAP_ROUTING = "swap_routing"

    /** The venue a prediction order is placed on. */
    const val PREDICTION_VENUE = "prediction_venue"

    /** One of [ServiceFeeStatus]: whether this app's build took a service fee, and if not why. */
    const val SERVICE_FEE_STATUS = "service_fee_status"

    /** Why a configured fee was not charged: a stable code from the provider. */
    const val SERVICE_FEE_REASON = "service_fee_reason"

    /** The rate, in whole basis points, as the transaction carried it. */
    const val SERVICE_FEE_BPS = "service_fee_bps"

    /** The provider's estimate of the fee, formatted with its unit. Never an on-chain amount. */
    const val SERVICE_FEE_ESTIMATE = "service_fee_estimate"

    /** The mint the fee is taken in. */
    const val SERVICE_FEE_MINT = "service_fee_mint"

    /** Which side of the operation the fee comes out of: [ServiceFeeStatus.SIDE_OUTPUT]. */
    const val SERVICE_FEE_SIDE = "service_fee_side"

    /** The public token account that receives it. */
    const val SERVICE_FEE_RECIPIENT = "service_fee_recipient"
}

/** The values of [ReceiptKey.SERVICE_FEE_STATUS] and [ReceiptKey.SERVICE_FEE_SIDE]. */
object ServiceFeeStatus {
    /** The fee was in the approved transaction, exactly as reviewed. */
    const val CHARGED = "charged"

    /** This build charges no service fee on this kind of operation. */
    const val NONE = "none"

    /** This build charges one, but not on this pair: no receiving account for its output. */
    const val NOT_FOR_PAIR = "not_for_pair"

    /** This build charges one, but its receiving account could not be verified, so it did not. */
    const val UNVERIFIED = "unverified"

    const val SIDE_OUTPUT = "output"
}

/**
 * What an execution provider says about itself, for a review to show before anything is prepared
 * (SEE-173): the integration's name as the provider requires it to be displayed, the role it plays,
 * the disclosures an owner should read before committing, and official links.
 *
 * It is the provider's own statement about its own integration, so it lives with the provider and
 * core shows it without interpreting it — exactly as it shows [PluginFact]s.
 */
data class ProviderAbout(
    /** The role the integration plays here, e.g. "Swap routing". */
    @StringRes val role: Int,
    /** Its name as it must be shown. A proper name, so it is not translated. */
    val name: String,
    /** What the owner should know before committing, one paragraph each. */
    val notes: List<ProviderNote>,
    /** Official pages about the integration, its terms and its privacy policy. */
    val links: List<PluginDestination>,
)

/** One paragraph of a [ProviderAbout], with the values its resource formats in. */
data class ProviderNote(@StringRes val text: Int, val args: List<String> = emptyList())

/**
 * The receipt as rows a person reads: a label and a value, in the order they matter.
 *
 * Unknown keys are left out rather than shown raw, so a record written by a newer build still reads
 * cleanly in an older one. The words are plain English, as the rest of the History detail's are.
 */
fun receiptRows(receipt: List<PluginReference>): List<Pair<String, String>> {
    val values = receipt.associate { it.key to it.value }
    return buildList {
        values[ReceiptKey.SWAP_ROUTING]?.let { add("Swap routing" to it) }
        values[ReceiptKey.PREDICTION_VENUE]?.let { add("Prediction market" to it) }
        when (values[ReceiptKey.SERVICE_FEE_STATUS]) {
            ServiceFeeStatus.CHARGED -> {
                val rate = values[ReceiptKey.SERVICE_FEE_BPS]?.toIntOrNull()?.let(::bpsPercent)
                val side =
                    if (values[ReceiptKey.SERVICE_FEE_SIDE] == ServiceFeeStatus.SIDE_OUTPUT) {
                        " of what you receive"
                    } else ""
                rate?.let { add("SAC service fee" to "$it$side") }
                values[ReceiptKey.SERVICE_FEE_ESTIMATE]?.let {
                    add("SAC service fee, estimated at review" to it)
                }
                values[ReceiptKey.SERVICE_FEE_MINT]?.let { add("SAC service fee token" to it) }
                values[ReceiptKey.SERVICE_FEE_RECIPIENT]?.let {
                    add("SAC service fee recipient" to it)
                }
            }
            ServiceFeeStatus.NONE -> add("SAC service fee" to "None")
            ServiceFeeStatus.NOT_FOR_PAIR -> add("SAC service fee" to "None on this pair")
            ServiceFeeStatus.UNVERIFIED ->
                add("SAC service fee" to "None: its fee account could not be verified")
            else -> Unit
        }
    }
}

/** Basis points as a percentage, exactly: 20 is "0.2%", 100 is "1%". */
fun bpsPercent(bps: Int): String {
    val whole = bps / 100
    val rest = (bps % 100).toString().padStart(2, '0').trimEnd('0')
    return if (rest.isEmpty()) "$whole%" else "$whole.$rest%"
}
