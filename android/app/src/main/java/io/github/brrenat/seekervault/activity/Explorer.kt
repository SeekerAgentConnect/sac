package io.github.brrenat.seekervault.activity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.brrenat.seekervault.request.v1.Network

/**
 * The public block explorer, which is the one place outside this phone a record points at.
 *
 * The app opens no connection to it. It hands the address to whatever app the owner opens links
 * with, and that app fetches whatever it fetches; this one has no endpoint of its own on any chain
 * and makes no request to any host but the sidecars it is paired with (`StageBoundaryTest`).
 */
private const val EXPLORER = "https://explorer.solana.com/tx/"

/**
 * Where [record]'s transaction can be read, or null when there is nothing to read.
 *
 * There is nothing to read for anything but a transaction that was sent: a transfer the owner
 * approved, or an operation they executed from a shared proposal (SEE-89). A signed message carries
 * a signature too, and it is not a transaction: it moved nothing, no cluster has it, and no
 * explorer can show it. Offering a link for one would say it was a payment.
 *
 * The cluster comes from what the owner reviewed — the transfer's own, or the one an operation was
 * bound to — and mainnet is the explorer's default. The same signature on another cluster is
 * another transaction, or nothing at all, so a record that names no cluster gets no link: a guess
 * here is a wrong link, which is worse than none.
 */
fun explorerUrl(record: ActivityRecord): String? {
    if (!record.signatureIsTransaction) return null
    val signature = record.signature ?: return null
    return explorerUrl(signature, record.transfer?.network ?: record.operation?.network)
}

/**
 * Where a transaction with this signature, on this cluster, can be read.
 *
 * The same rule and the same one address, for a caller that has a signature and a cluster rather
 * than a stored record: the review screen an operation was submitted from shows the link straight
 * away (SEE-94), and it must be the same link the owner's history will show later. A cluster that
 * is absent or unspecified gets no link, because a guess here is a wrong link.
 */
fun explorerUrl(signature: String, network: Network?): String? =
    when (network) {
        Network.NETWORK_MAINNET -> "$EXPLORER$signature"
        Network.NETWORK_DEVNET -> "$EXPLORER$signature?cluster=devnet"
        Network.NETWORK_TESTNET -> "$EXPLORER$signature?cluster=testnet"
        else -> null
    }

/**
 * Opens [url] with whatever app handles links, and returns false when nothing does. A phone with no
 * browser is unusual and not an error: the record is on screen either way, and the owner can copy
 * the signature.
 *
 * With [appOnly], a browser is not an answer: the launch succeeds only if some app other than a
 * browser claims the address, and fails as if nothing had handled it at all. That is what makes a
 * provider's own app the first choice rather than whatever happens to be registered — an `https`
 * address a provider's app has verified opens *in that app*, and the same address falls through to
 * the browser on a phone that does not have it (SEE-157).
 */
fun openLink(context: Context, url: String, appOnly: Boolean = false): Boolean =
    try {
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appOnly) intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

/**
 * Opens [deepLink] in the provider's own app if it is installed, and [url] in a browser if it is
 * not (SEE-157).
 *
 * The order is the whole of it. A deep link is tried first and only as an app: an owner who has the
 * provider installed is carried into it, on the page the destination names, rather than into a
 * browser that then has to hand them back. When no app takes it — the provider is not installed,
 * the phone never verified the link, the address is one no app claims — nothing has happened yet,
 * and the web address is opened instead.
 *
 * Returns false when neither opened, which is the same non-error [openLink] reports: the screen
 * still says everything it said, and nothing about the operation depended on the link.
 */
fun openDestination(context: Context, deepLink: String?, url: String): Boolean =
    (deepLink != null && openLink(context, deepLink, appOnly = true)) || openLink(context, url)
