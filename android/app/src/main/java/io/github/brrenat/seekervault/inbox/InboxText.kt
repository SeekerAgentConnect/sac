package io.github.brrenat.seekervault.inbox

import android.text.format.DateUtils
import androidx.annotation.StringRes
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
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.messageBytes
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.SignMessageAction
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
    const val APPROVE = "approve"
    const val REJECT = "reject"
    const val ENCODING = "requestEncoding"
    const val SIGNS_WITH = "requestSignsWith"
    const val HIDDEN = "requestHiddenCharacters"
    const val NOT_A_PAYMENT = "requestNotAPayment"
    const val SIGNING_PROBLEM = "signingProblem"
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

/** An ack's text, for previews. A message to sign has its own preview: see [messagePreview]. */
fun ActionRequest.text(): String? = if (action.hasAck()) action.ack.text else null

/**
 * The message a `sign_message` request asks the wallet to sign, ready to show
 * (docs/guides/message-signing.md). The owner sees the complete message and every byte of it: text
 * is shown with its invisible characters marked, and bytes as hex, so nothing can hide in what they
 * approve.
 */
data class MessagePreview(
    /** What to show: the text with invisible characters marked, or the bytes as hex. */
    val display: String,
    /** How many bytes the wallet will sign. */
    val bytes: Int,
    /** False for a message the agent sent as bytes rather than text. */
    val isText: Boolean,
    /** True when the text holds characters that would otherwise not be visible. */
    val hasHidden: Boolean,
)

fun messagePreview(request: ActionRequest): MessagePreview? {
    val message = request.signMessage() ?: return null
    val bytes = message.messageBytes().size()
    if (message.contentCase != SignMessageAction.ContentCase.TEXT) {
        return MessagePreview(
            display = message.data.toByteArray().joinToString(" ") { "%02x".format(it) },
            bytes = bytes,
            isText = false,
            hasHidden = false,
        )
    }
    return MessagePreview(
        display = visibleText(message.text),
        bytes = bytes,
        isText = true,
        hasHidden = message.text.any(::isHidden),
    )
}

/**
 * The text with every character that would otherwise be invisible made visible: control characters
 * as their Control Pictures symbol, and zero-width, bidirectional, and non-breaking characters as
 * their code point. A line break is kept as a line break and marked, so the message still reads the
 * way it was written. Nothing else is changed: this is for display only, and the wallet signs the
 * original bytes.
 */
fun visibleText(text: String): String = buildString {
    for (char in text) {
        when {
            char == '\n' -> append("\u240A\n")
            char == '\r' -> append('\u240D')
            char == '\t' -> append('\u2409')
            char.code < 0x20 -> append((0x2400 + char.code).toChar())
            char.code == 0x7F -> append('\u2421')
            isHidden(char) -> append("<U+%04X>".format(char.code))
            else -> append(char)
        }
    }
}

/** Whether a character takes no visible space, or looks like an ordinary space but isn't one. */
fun isHidden(char: Char): Boolean =
    char.code < 0x20 ||
        char.code == 0x7F ||
        char == '\u00A0' || // no-break space
        char == '\u00AD' || // soft hyphen
        char.code in 0x200B..0x200F || // zero-width and directional marks
        char.code in 0x202A..0x202E || // bidirectional overrides
        char.code in 0x2060..0x2064 ||
        char.code in 0x2066..0x2069 ||
        char == '\uFEFF' // byte order mark

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

/** Why an approval never reached the wallet. */
@StringRes
fun problemText(problem: SigningProblem): Int =
    when (problem) {
        SigningProblem.NoWallet -> R.string.problem_no_wallet
        SigningProblem.OtherWallet -> R.string.problem_other_wallet
        SigningProblem.Changed -> R.string.problem_wallet_changed
    }

/** Whether this app can put [request] in front of the owner for an answer at all. */
fun isAnswerable(request: ActionRequest): Boolean =
    request.action.hasAck() || request.signMessage() != null

/** Whether the owner can still answer [request] on this phone. */
fun canAnswer(request: ActionRequest, result: LocalResult?, now: Instant): Boolean =
    result == null && isAnswerable(request) && request.expiresAt.instant() > now

/** Where the request stands, in one sentence. */
@Composable
fun statusText(request: ActionRequest, result: LocalResult?, sending: Boolean, now: Instant) =
    when {
        sending -> stringResource(R.string.status_sending)
        result == null && !isAnswerable(request) -> stringResource(R.string.status_unsupported)
        result == null && request.expiresAt.instant() <= now ->
            stringResource(R.string.status_expired)
        result == null -> stringResource(R.string.status_waiting_for_you)
        else -> resultText(result)
    }

@Composable
fun resultText(result: LocalResult): String {
    if (result.answer == Answer.Approve) return approvedText(result)
    val acknowledged = result.answer == Answer.Acknowledge
    return stringResource(
        when (result.delivery) {
            Delivery.Waiting ->
                if (acknowledged) R.string.status_to_send_acknowledged
                else R.string.status_to_send_rejected
            Delivery.Accepted ->
                if (acknowledged) R.string.status_acknowledged else R.string.status_rejected
            Delivery.Superseded -> supersededText(result)
            Delivery.Undeliverable -> R.string.status_undeliverable
        }
    )
}

@StringRes
private fun supersededText(result: LocalResult): Int =
    when (result.request.state) {
        RequestState.REQUEST_STATE_CANCELLED -> R.string.status_superseded_cancelled
        RequestState.REQUEST_STATE_EXPIRED -> R.string.status_superseded_expired
        else -> R.string.status_superseded_other
    }

/** Where an approved message stands: what the wallet did, and whether the server knows yet. */
@Composable
private fun approvedText(result: LocalResult): String {
    if (result.delivery == Delivery.Superseded) {
        return stringResource(supersededText(result))
    }
    if (result.delivery == Delivery.Undeliverable) {
        return stringResource(R.string.status_undeliverable)
    }
    val waiting = result.delivery == Delivery.Waiting
    return when (val outcome = result.signing) {
        null -> stringResource(R.string.status_waiting_for_wallet)
        is SigningOutcome.Signed ->
            stringResource(if (waiting) R.string.status_to_send_signed else R.string.status_signed)
        SigningOutcome.Declined ->
            stringResource(
                if (waiting) R.string.status_to_send_declined_in_wallet
                else R.string.status_declined_in_wallet
            )
        is SigningOutcome.Failed -> stringResource(R.string.status_not_signed, outcome.detail)
        is SigningOutcome.Unresolved -> stringResource(R.string.status_unresolved, outcome.detail)
    }
}

@StringRes
private fun answerSummary(result: LocalResult): Int =
    when (result.answer) {
        Answer.Acknowledge -> R.string.answer_acknowledged
        Answer.Reject -> R.string.answer_rejected
        Answer.Approve ->
            when (result.signing) {
                null -> R.string.answer_approved
                is SigningOutcome.Signed -> R.string.answer_signed
                SigningOutcome.Declined -> R.string.answer_declined_in_wallet
                is SigningOutcome.Failed -> R.string.answer_not_signed
                is SigningOutcome.Unresolved -> R.string.answer_unresolved
            }
    }

/** A short label for an answer in a list: "Acknowledged, waiting to be sent". */
@Composable
fun resultSummary(result: LocalResult): String {
    val answer = stringResource(answerSummary(result))
    return when (result.delivery) {
        Delivery.Waiting -> stringResource(R.string.summary_waiting, answer)
        Delivery.Accepted -> answer
        Delivery.Superseded -> stringResource(R.string.summary_superseded, answer)
        Delivery.Undeliverable -> stringResource(R.string.summary_undeliverable, answer)
    }
}
