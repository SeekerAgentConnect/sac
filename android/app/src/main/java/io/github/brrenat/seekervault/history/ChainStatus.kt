package io.github.brrenat.seekervault.history

import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.ChainLevel
import io.github.brrenat.seekervault.confirmations.ChainReason
import io.github.brrenat.seekervault.confirmations.ChainState
import io.github.brrenat.seekervault.designsystem.HistoryDetailCheckStatus
import io.github.brrenat.seekervault.designsystem.HistoryDetailExecution
import io.github.brrenat.seekervault.designsystem.HistoryDetailExecutionState
import io.github.brrenat.seekervault.designsystem.HistoryDetailRow
import io.github.brrenat.seekervault.designsystem.HistoryDetailTransactionStatus
import io.github.brrenat.seekervault.designsystem.HistoryRowStatus
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import java.time.Instant

/** Where a sent transaction stands, as History says it (SEE-165). */
enum class ChainStanding {
    /** Sent, and nobody has settled it yet. */
    Waiting,
    Confirmed,
    Failed,
    /** Proven never to land. Nothing was spent. */
    Expired,
    /** No automatic check will settle it; the reason is on the check. */
    Unresolved,
}

/**
 * One answer about the chain, from two possible sources, with a fixed order between them.
 *
 * The phone's own check of the approved bytes comes first: a verified local answer is never
 * overruled by a server, whether the server is behind, unreachable, or disagrees. A server's
 * settled word counts where the phone has none of its own. "Sent" never overwrites either. The same
 * rule is applied to History records (`ActivityLog.merged`), so every screen agrees.
 */
data class ChainView(
    val standing: ChainStanding,
    /** The phone's own check, when it made one. */
    val local: ChainCheck?,
    /** When the server settled it, when the standing rests on the server's word. */
    val serverAt: Instant? = null,
    /** The server's raw chain error, for Identifiers. */
    val serverError: String? = null,
    /** The server's readable account of a failure, for the card. */
    val serverReason: String? = null,
) {
    /** Whether the standing rests on this phone's own check. */
    val verifiedHere: Boolean
        get() = local?.state?.verified == true

    val chainError: String?
        get() = local?.chainError?.takeIf { verifiedHere } ?: serverError

    val settledAt: Instant?
        get() = if (verifiedHere) local?.checkedAt else serverAt
}

fun chainView(
    local: ChainCheck?,
    server: RequestState? = null,
    serverAt: Instant? = null,
    serverError: String? = null,
    serverReason: String? = null,
): ChainView {
    when (local?.state) {
        ChainState.Confirmed -> return ChainView(ChainStanding.Confirmed, local)
        ChainState.Failed -> return ChainView(ChainStanding.Failed, local)
        ChainState.Expired -> return ChainView(ChainStanding.Expired, local)
        else -> Unit
    }
    when (server) {
        RequestState.REQUEST_STATE_CONFIRMED,
        RequestState.REQUEST_STATE_COMPLETED ->
            return ChainView(ChainStanding.Confirmed, local, serverAt)
        RequestState.REQUEST_STATE_FAILED ->
            return ChainView(ChainStanding.Failed, local, serverAt, serverError, serverReason)
        else -> Unit
    }
    if (local?.state == ChainState.Unresolved) return ChainView(ChainStanding.Unresolved, local)
    return ChainView(ChainStanding.Waiting, local)
}

/** The transaction chip's state. An expired transaction is shown as failed: it never ran. */
fun ChainView.transactionStatus(): HistoryDetailTransactionStatus =
    when (standing) {
        ChainStanding.Confirmed -> HistoryDetailTransactionStatus.Confirmed
        ChainStanding.Failed,
        ChainStanding.Expired -> HistoryDetailTransactionStatus.Failed
        ChainStanding.Waiting,
        ChainStanding.Unresolved -> HistoryDetailTransactionStatus.Pending
    }

/** A History row's status chip. An expired transaction never ran, and reads as failed. */
fun ChainView.rowStatus(): HistoryRowStatus =
    when (standing) {
        ChainStanding.Confirmed -> HistoryRowStatus.Confirmed
        ChainStanding.Failed,
        ChainStanding.Expired -> HistoryRowStatus.Failed
        ChainStanding.Waiting -> HistoryRowStatus.Pending
        ChainStanding.Unresolved -> HistoryRowStatus.Unknown
    }

/** A History row's outcome line. */
fun ChainView.rowText(): String =
    when (standing) {
        ChainStanding.Confirmed -> "Confirmed on the network"
        ChainStanding.Failed -> "Failed on the network"
        ChainStanding.Expired -> "Expired · never landed"
        ChainStanding.Unresolved -> "Network status unresolved"
        ChainStanding.Waiting ->
            if (local?.reason?.delayed == true) "Sent · status check delayed"
            else "Sent to the network"
    }

/**
 * The execution card for a sent transaction.
 *
 * [caveat] is what a confirmation does *not* prove for this kind of action — a prediction order's
 * fill, an unstake's cooldown — and it is shown whenever the transaction confirmed, because a
 * confirmed transaction is exactly when somebody would assume the rest.
 */
fun chainExecution(
    view: ChainView,
    network: Network?,
    clock: HistoryDetailClock,
    caveat: String? = null,
    checking: Boolean = false,
    rows: List<HistoryDetailRow> = emptyList(),
): HistoryDetailExecution {
    val local = view.local
    val check =
        if (local?.checkable == true && view.standing != ChainStanding.Expired)
            HistoryDetailCheckStatus(checking = checking)
        else null
    val where = network.word()
    val source = sourceText(view, clock)
    return when (view.standing) {
        ChainStanding.Confirmed ->
            HistoryDetailExecution(
                state = HistoryDetailExecutionState.Confirmed,
                title = where?.let { "Confirmed on $it" } ?: "Confirmed",
                body = listOfNotNull(source, caveat).joinToString(" "),
                rows = levelRows(view),
                checkStatus = check,
            )
        ChainStanding.Failed ->
            HistoryDetailExecution(
                state = HistoryDetailExecutionState.Failed,
                title = "Failed on the network",
                body =
                    listOfNotNull(source, "Nothing moved except the network fee.")
                        .joinToString(" "),
                rows = levelRows(view),
                // Readable text on the card; the chain's raw error goes under Identifiers.
                failureReason =
                    if (view.verifiedHere) {
                        "The transaction ran and the network returned an error."
                    } else {
                        view.serverReason ?: "The network didn't say why."
                    },
                checkStatus = check,
            )
        ChainStanding.Expired ->
            HistoryDetailExecution(
                state = HistoryDetailExecutionState.Failed,
                title = "Expired · never landed",
                body =
                    "Its blockhash is no longer valid and the network has no record of it, so it " +
                        "can never land. Nothing was spent." +
                        (local?.checkedAt?.let { " Checked ${clock.preciseTime(it)}" } ?: "") +
                        (local?.host?.let { " via $it." }
                            ?: if (local?.checkedAt != null) "." else ""),
            )
        ChainStanding.Unresolved ->
            HistoryDetailExecution(
                state = HistoryDetailExecutionState.Pending,
                title = "Network status unresolved",
                body = unresolvedText(local, clock),
                rows = rows,
                checkStatus = check,
            )
        ChainStanding.Waiting ->
            HistoryDetailExecution(
                state = HistoryDetailExecutionState.Pending,
                title = "Waiting for network confirmation",
                body = waitingText(local, clock),
                rows = rows,
                checkStatus = check,
            )
    }
}

/** Where a settled answer came from: this phone's check, or the server's. */
private fun sourceText(view: ChainView, clock: HistoryDetailClock): String? {
    val local = view.local
    if (view.verifiedHere && local != null) {
        return "This phone checked the approved transaction on chain" +
            (local.checkedAt?.let { " at ${clock.preciseTime(it)}" } ?: "") +
            (local.host?.let { " via $it" } ?: "") +
            "."
    }
    return view.serverAt?.let { "The server reported it by ${clock.preciseTime(it)}." }
        ?: "The server reported it."
}

private fun levelRows(view: ChainView): List<HistoryDetailRow> {
    val local = view.local?.takeIf { view.verifiedHere } ?: return emptyList()
    return listOfNotNull(
        local.level?.let { HistoryDetailRow("Commitment", it.word()) },
        local.slot?.let { HistoryDetailRow("Slot", it.toString()) },
    )
}

private fun waitingText(local: ChainCheck?, clock: HistoryDetailClock): String {
    val parts = mutableListOf("Sent and not confirmed yet.")
    when (local?.reason) {
        ChainReason.ProcessedOnly ->
            parts += "A node has processed it, but no supermajority has voted on it yet."
        ChainReason.NotYetVisible -> parts += "The network has no status for it yet."
        ChainReason.BodyNotServed ->
            parts += "Its status is in, and the transaction itself isn't served yet to compare."
        ChainReason.NoEndpoint ->
            parts += "This build has no network endpoint for its cluster, so the phone can't check."
        ChainReason.WrongCluster ->
            parts += "The configured endpoint serves another cluster, so its answer doesn't count."
        ChainReason.Unreachable -> parts += "The last check couldn't reach the network endpoint."
        ChainReason.RateLimited -> parts += "The network endpoint asked this phone to slow down."
        ChainReason.Refused,
        ChainReason.Unusable -> parts += "The network endpoint's last answer couldn't be used."
        else -> Unit
    }
    parts +=
        local?.checkedAt?.let { "Last checked ${clock.time(it)}." }
            ?: if (local == null) "This phone isn't checking it." else "Not checked yet."
    local?.nextCheckAt?.let { parts += "Next check around ${clock.time(it)}." }
    parts += "Approving it doesn't mean it went through."
    return parts.joinToString(" ")
}

private fun unresolvedText(local: ChainCheck?, clock: HistoryDetailClock): String =
    when (local?.reason) {
        ChainReason.Mismatch ->
            "The network holds a different transaction under this signature, so this says " +
                "nothing about the one you approved. Look the signature up on an explorer before " +
                "assuming anything, and don't send a replacement."
        ChainReason.MissingContext ->
            "This was sent before this phone kept what it needs to check a transaction, so it " +
                "can't be verified here. The explorer, or your wallet's history, shows where it " +
                "stands."
        else ->
            "Automatic checks stopped without an answer" +
                (local?.checkedAt?.let { " (last ${clock.time(it)})" } ?: "") +
                ". Check again, or look the signature up on an explorer. Nothing is sent again."
    }

private val ChainReason.delayed: Boolean
    get() =
        this == ChainReason.Unreachable ||
            this == ChainReason.RateLimited ||
            this == ChainReason.Refused ||
            this == ChainReason.Unusable ||
            this == ChainReason.NoEndpoint ||
            this == ChainReason.WrongCluster

private fun ChainLevel.word(): String =
    when (this) {
        ChainLevel.Processed -> "Processed"
        ChainLevel.Confirmed -> "Confirmed"
        ChainLevel.Finalized -> "Finalized"
    }

/** What History says about a transaction whose signature never reached this phone. */
const val NO_SIGNATURE_GUIDANCE =
    "No signature reached this phone, so the network can't be asked about it; your wallet's own " +
        "history shows whether it was sent. Nothing here will send it again."
