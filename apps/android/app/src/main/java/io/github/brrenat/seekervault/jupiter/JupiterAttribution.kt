package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.ProviderAbout
import io.github.brrenat.seekervault.plugins.ProviderNote
import io.github.brrenat.seekervault.plugins.ProviderNoteTopic
import io.github.brrenat.seekervault.plugins.ReceiptKey
import io.github.brrenat.seekervault.plugins.ServiceFeeStatus

/**
 * How this app names Jupiter's integrations and what it tells the owner about them (SEE-173).
 *
 * Jupiter's API agreement asks two things of an integrator: to label the routing engine it actually
 * uses — "Metis" for the v1 Swap API, never "Jupiter Ultra" or plain "Jupiter" as though it were
 * jup.ag — and to display "Powered by Jupiter". Neither implies a partnership or an endorsement,
 * and the notes below say so. The links are Jupiter's own public pages; nothing here is a logo or a
 * brand asset (docs/integrations/jupiter.md#attribution).
 */

/** The prediction venue, named as Jupiter names the product. A name, so it is not translated. */
const val PREDICTION_VENUE_NAME: String = "Jupiter Prediction · Powered by Jupiter"

/** Jupiter's developer documentation for the Metis Swap API. */
const val METIS_DOCS_URL: String = "https://developers.jup.ag/docs/swap"

/** Jupiter's own account of how prediction orders, positions and settlement work. */
const val PREDICTION_HOW_IT_WORKS_URL: String =
    "https://docs.jup.ag/user-docs/trade/predict/how-it-works"

/** Jupiter's terms of use and privacy policy, which apply to its APIs. */
const val JUPITER_TERMS_URL: String = "https://developers.jup.ag/docs/legal/terms-of-use"

const val JUPITER_PRIVACY_URL: String = "https://developers.jup.ag/docs/legal/privacy-policy"

/** The public explanation of this app's service fee and how a build configures it. */
const val SERVICE_FEE_DOCS_URL: String =
    "https://seekeragentconnect.github.io/docs/swap-service-fee"

private val TERMS_LINKS =
    listOf(
        PluginDestination(R.string.jupiter_about_terms, JUPITER_TERMS_URL),
        PluginDestination(R.string.jupiter_about_privacy, JUPITER_PRIVACY_URL),
    )

/** What a swap review says about Metis and this build's service fee. */
internal fun swapAbout(fee: SwapFeePolicy): ProviderAbout =
    ProviderAbout(
        role = R.string.jupiter_about_swap_role,
        name = SWAP_ROUTING_NAME,
        notes =
            listOf(
                ProviderNote(R.string.jupiter_about_swap_what),
                if (fee.enabled) {
                    ProviderNote(R.string.jupiter_about_swap_fee_on, listOf(percent(fee.bps)))
                } else {
                    ProviderNote(R.string.jupiter_about_swap_fee_off)
                },
                ProviderNote(R.string.jupiter_about_not_endorsed),
            ),
        links =
            listOf(
                PluginDestination(R.string.jupiter_about_metis_docs, METIS_DOCS_URL),
                PluginDestination(R.string.jupiter_about_fee_docs, SERVICE_FEE_DOCS_URL),
            ) + TERMS_LINKS,
    )

/** What a prediction review says about Jupiter Prediction before the owner commits. */
internal val PREDICTION_ABOUT: ProviderAbout =
    ProviderAbout(
        role = R.string.jupiter_about_prediction_role,
        name = PREDICTION_VENUE_NAME,
        notes =
            listOf(
                ProviderNote(R.string.jupiter_about_prediction_mainnet),
                ProviderNote(R.string.jupiter_about_prediction_fees),
                ProviderNote(R.string.jupiter_about_prediction_region),
                ProviderNote(
                    R.string.jupiter_about_prediction_lifecycle,
                    topic = ProviderNoteTopic.Order,
                ),
                ProviderNote(R.string.jupiter_about_not_endorsed),
            ),
        links =
            listOf(
                PluginDestination(
                    R.string.jupiter_about_prediction_docs,
                    PREDICTION_HOW_IT_WORKS_URL,
                )
            ) + TERMS_LINKS,
    )

/** What a prediction order's record keeps: the venue, and that this app took no fee on it. */
internal val PREDICTION_RECEIPT: List<PluginReference> =
    listOf(
        PluginReference(ReceiptKey.PREDICTION_VENUE, PREDICTION_VENUE_NAME),
        PluginReference(ReceiptKey.SERVICE_FEE_STATUS, ServiceFeeStatus.NONE),
    )
