package io.github.brrenat.seekervault.inbox

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.google.protobuf.Timestamp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.Inbox
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tags for the inbox screens' controls. */
object InboxTags {
    const val LIST = "inboxList"
    const val REFRESH = "inboxRefresh"
    const val EMPTY = "inboxEmpty"
    const val NO_CONNECTIONS = "inboxNoConnections"
    const val PROBLEM = "inboxProblem"
    const val SECTION_TO_SEND = "sectionToSend"
    const val SECTION_PENDING = "sectionPending"
    const val SECTION_ANSWERED = "sectionAnswered"
    const val STATUS = "requestStatus"
    const val MESSAGE = "requestMessage"
    const val NOTE = "requestNote"
    const val ACKNOWLEDGE = "acknowledge"
    const val REJECT = "reject"
    const val SEND_AGAIN = "sendAgain"
    const val SENDING = "sending"
    const val GONE = "requestGone"

    fun item(key: RequestKey) = "request:${key.connectionId}/${key.requestId}"

    fun field(name: String) = "requestField:$name"
}

/** The inbox's three lists, for one connection or, when [connectionId] is null, for all. */
data class InboxItems(
    /** Answers that haven't reached the sidecar: waiting, or impossible to send. */
    val toSend: List<LocalResult>,
    /** Requests waiting for the owner, oldest first. */
    val pending: List<ActionRequest>,
    /** Answers the sidecar settled, newest first. */
    val answered: List<LocalResult>,
) {
    fun isEmpty() = toSend.isEmpty() && pending.isEmpty() && answered.isEmpty()
}

fun inboxItems(inbox: Inbox, connectionId: String?): InboxItems {
    fun ours(id: String) = connectionId == null || id == connectionId
    val answered = inbox.results.map { it.key }.toSet()
    return InboxItems(
        toSend =
            inbox.results.filter {
                ours(it.connectionId) &&
                    (it.delivery == Delivery.Waiting || it.delivery == Delivery.Undeliverable)
            },
        pending =
            inbox.pending
                .filterKeys(::ours)
                .values
                .flatten()
                .filter { it.key !in answered }
                .sortedWith(
                    compareBy(
                        { it.createdAt.seconds },
                        { it.createdAt.nanos },
                        { it.ref.requestId },
                    )
                ),
        answered =
            inbox.results
                .filter {
                    ours(it.connectionId) &&
                        (it.delivery == Delivery.Accepted || it.delivery == Delivery.Superseded)
                }
                .sortedByDescending { it.answeredAt },
    )
}

val ActionRequest.key: RequestKey
    get() = RequestKey(ref.connectionId, ref.requestId)

fun Timestamp.instant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

/** An ack's text, for previews; other actions have none yet. */
fun ActionRequest.text(): String? = if (action.hasAck()) action.ack.text else null

@Composable
fun actionText(request: ActionRequest): String =
    stringResource(
        when (request.action.kindCase) {
            Action.KindCase.ACK -> R.string.action_ack
            Action.KindCase.SIGN_MESSAGE -> R.string.action_sign_message
            Action.KindCase.TRANSFER -> R.string.action_transfer
            Action.KindCase.SWAP -> R.string.action_swap
            else -> R.string.action_unknown
        }
    )

/** "5 minutes ago", "In 3 hours": relative to [now], to the minute. */
fun relativeTime(instant: Instant, now: Instant): String =
    DateUtils.getRelativeTimeSpanString(
            instant.toEpochMilli(),
            now.toEpochMilli(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        .toString()

@Composable
fun shortTime(instant: Instant): String =
    remember(instant) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }

/** Whether the owner can still answer [request] on this phone. */
fun canAnswer(request: ActionRequest, result: LocalResult?, now: Instant): Boolean =
    result == null && request.action.hasAck() && request.expiresAt.instant() > now

/** Where the request stands, in one sentence. */
@Composable
fun statusText(request: ActionRequest, result: LocalResult?, sending: Boolean, now: Instant) =
    when {
        sending -> stringResource(R.string.status_sending)
        result == null && !request.action.hasAck() -> stringResource(R.string.status_unsupported)
        result == null && request.expiresAt.instant() <= now ->
            stringResource(R.string.status_expired)
        result == null -> stringResource(R.string.status_waiting_for_you)
        else -> resultText(result)
    }

@Composable
fun resultText(result: LocalResult): String {
    val acknowledged = result.answer == Answer.Acknowledge
    return stringResource(
        when (result.delivery) {
            Delivery.Waiting ->
                if (acknowledged) R.string.status_to_send_acknowledged
                else R.string.status_to_send_rejected
            Delivery.Accepted ->
                if (acknowledged) R.string.status_acknowledged else R.string.status_rejected
            Delivery.Superseded ->
                when (result.request.state) {
                    RequestState.REQUEST_STATE_CANCELLED -> R.string.status_superseded_cancelled
                    RequestState.REQUEST_STATE_EXPIRED -> R.string.status_superseded_expired
                    else -> R.string.status_superseded_other
                }
            Delivery.Undeliverable -> R.string.status_undeliverable
        }
    )
}

/** A short label for an answer in a list: "Acknowledged, waiting to be sent". */
@Composable
fun resultSummary(result: LocalResult): String {
    val answer =
        stringResource(
            if (result.answer == Answer.Acknowledge) R.string.answer_acknowledged
            else R.string.answer_rejected
        )
    return when (result.delivery) {
        Delivery.Waiting -> stringResource(R.string.summary_waiting, answer)
        Delivery.Accepted -> answer
        Delivery.Superseded -> stringResource(R.string.summary_superseded, answer)
        Delivery.Undeliverable -> stringResource(R.string.summary_undeliverable, answer)
    }
}
