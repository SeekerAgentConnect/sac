package io.github.brrenat.seekervault.inbox

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.google.protobuf.ByteString
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
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.SignMessageAction
import io.github.brrenat.seekervault.transactions.Finding
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.TransferFacts
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.wallet.encodeBase58
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
    const val QUICK_APPROVE = "requestQuickApprove"
    const val ENCODING = "requestEncoding"
    const val SIGNS_WITH = "requestSignsWith"
    const val HIDDEN = "requestHiddenCharacters"
    const val NOT_A_PAYMENT = "requestNotAPayment"
    const val SIGNING_PROBLEM = "signingProblem"
    const val SEND_AGAIN = "sendAgain"
    const val SENDING = "sending"
    const val GONE = "requestGone"

    /** The phone's own verdict on a transfer's transaction (SAW-020). */
    const val TRANSFER_VERDICT = "transferVerdict"
    const val TRANSFER_FINDINGS = "transferFindings"
    const val TRANSFER_DERIVED = "transferDerived"
    const val TRANSFER_CHECKING = "transferChecking"
    const val TRANSFER_FAILED = "transferFailed"
    const val TRANSFER_AGAIN = "transferAgain"
    /** Approving one through the wallet (SAW-021). */
    const val TRANSFER_APPROVE = "transferApprove"

    /** Why there is no Approve button: input validation, and never a rule (SAW-020). */
    const val TRANSFER_NOT_APPROVABLE = "transferNotApprovable"

    /** What the owner's own rules made of the request (SAW-028). */
    const val POLICY_PENDING = "policyPending"
    const val POLICY_VERDICT = "policyVerdict"
    const val POLICY_REASON = "policyReason"
    const val POLICY_UNCOVERED = "policyUncovered"
    const val POLICY_MANUAL = "policyManual"
    const val POLICY_ACKNOWLEDGE = "policyAcknowledge"

    fun policyDaily(scope: String) = "policyDaily:$scope"

    /** What the server has checked on chain, and the owner's own way of asking again (SAW-022). */
    const val CONFIRMATION = "requestConfirmation"
    const val CHECK_STATUS = "checkStatus"

    fun item(key: RequestKey) = "request:${key.connectionId}/${key.requestId}"

    fun policyCheck(check: PolicyCheck) = "policyCheck:${check.code}"

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
    val display = visibleText(message.text)
    return MessagePreview(
        display = display,
        bytes = bytes,
        isText = true,
        // Whatever the preview had to mark is something the owner would not otherwise have seen.
        hasHidden = display != message.text,
    )
}

/**
 * The text with every character that would otherwise be invisible made visible: control characters
 * as their Control Pictures symbol, and anything else [isHidden] finds as its code point. A line
 * break is kept as a line break and marked, so the message still reads the way it was written.
 * Nothing else is changed: this is for display only, and the wallet signs the original bytes.
 */
fun visibleText(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        // By code point, so that a character written as a surrogate pair is one character here and
        // one beyond the basic plane, such as a tag character, can't slip through as two halves.
        val code = text.codePointAt(index)
        index += Character.charCount(code)
        when {
            code == '\n'.code -> append("\u240A\n")
            code == '\r'.code -> append('\u240D')
            code == '\t'.code -> append('\u2409')
            code < 0x20 -> append((0x2400 + code).toChar())
            code == 0x7F -> append('\u2421')
            isHidden(code) -> append("<U+%04X>".format(code))
            else -> appendCodePoint(code)
        }
    }
}

/**
 * Whether a code point takes no visible space, or looks like an ordinary space but isn't one.
 *
 * It asks Unicode rather than listing the characters that came to mind: every control and format
 * code point is invisible, and so is every unassigned or private-use one, whose appearance this
 * phone can't know. A code point this phone's Unicode tables don't know yet is marked rather than
 * shown, which is the safe way round for a message the owner is about to have their wallet sign.
 */
fun isHidden(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.CONTROL,
        Character.FORMAT, // zero-width, directional marks and overrides, the byte order mark
        Character.PRIVATE_USE,
        Character.SURROGATE, // only ever an unpaired half here
        Character.UNASSIGNED,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR -> true
        // Every other space than the ordinary one, such as a no-break or an ideographic space.
        Character.SPACE_SEPARATOR -> codePoint != ' '.code
        else -> isInvisibleLetterOrMark(codePoint)
    }

/**
 * The code points that are invisible although Unicode files them as letters or marks: the Hangul
 * fillers, and the variation selectors, which change the character before them without showing
 * anything themselves.
 */
private fun isInvisibleLetterOrMark(codePoint: Int): Boolean =
    codePoint == 0x115F ||
        codePoint == 0x1160 ||
        codePoint == 0x3164 ||
        codePoint == 0xFFA0 ||
        codePoint in 0x180B..0x180D ||
        codePoint in 0xFE00..0xFE0F ||
        codePoint in 0xE0100..0xE01EF

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
        SigningProblem.NotVerified -> R.string.problem_not_verified
        SigningProblem.Stale -> R.string.problem_stale_preparation
        SigningProblem.NotApproved -> R.string.problem_not_approved
        SigningProblem.NotSentYet -> R.string.problem_not_sent_yet
        SigningProblem.NotChecked -> R.string.problem_not_checked
        SigningProblem.RulesChanged -> R.string.problem_rules_changed
        SigningProblem.NotAcknowledged -> R.string.problem_not_acknowledged
    }

/** Whether this app can put [request] in front of the owner for an answer at all. */
fun isAnswerable(request: ActionRequest): Boolean =
    request.action.hasAck() || request.signMessage() != null || request.transfer() != null

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

/** A transaction's ID on chain, the way every explorer and wallet writes it. */
private fun base58(signature: ByteString): String = encodeBase58(signature.toByteArray())

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
        is SigningOutcome.Sent ->
            if (waiting) {
                stringResource(R.string.status_to_send_sent, base58(outcome.signature))
            } else {
                sentText(result, outcome)
            }
        SigningOutcome.Declined ->
            stringResource(
                if (waiting) R.string.status_to_send_declined_in_wallet
                else R.string.status_declined_in_wallet
            )
        is SigningOutcome.Failed -> stringResource(R.string.status_not_signed, outcome.detail)
        // A message that never came back was never signed. A transfer that never came back may
        // have been sent, and this phone says exactly that rather than either of the easy answers.
        is SigningOutcome.Unresolved ->
            stringResource(
                if (result.request.transfer() != null) R.string.status_unknown_transfer
                else R.string.status_unresolved,
                outcome.detail,
            )
    }
}

/**
 * Where a sent transaction stands, which the server learns from the chain and this phone only
 * repeats (SAW-022). Until it has looked, and whenever it can't settle it, this says sent and no
 * more: the wallet's word that it sent something is not the network's word that it went through.
 */
@Composable
private fun sentText(result: LocalResult, outcome: SigningOutcome.Sent): String {
    val id = base58(outcome.signature)
    return when (result.request.state) {
        RequestState.REQUEST_STATE_CONFIRMED -> stringResource(R.string.status_confirmed, id)
        RequestState.REQUEST_STATE_FAILED ->
            stringResource(R.string.status_chain_failed, id, result.request.outcome.detail)
        else -> stringResource(R.string.status_sent, id)
    }
}

/**
 * What the server checked on the network, and which endpoint's word it rests on. There is no second
 * opinion behind a confirmed or failed transfer, and the owner is told whose word it is.
 */
@Composable
fun confirmationText(result: LocalResult): String? {
    if (result.signing !is SigningOutcome.Sent || result.delivery != Delivery.Accepted) return null
    val confirmation = result.request.outcome.confirmation
    if (!result.request.outcome.hasConfirmation() || confirmation.endpoint.isEmpty()) {
        return stringResource(R.string.confirmation_unchecked)
    }
    return stringResource(
        R.string.confirmation_checked,
        confirmation.endpoint,
        confirmation.detail,
    )
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
                is SigningOutcome.Sent ->
                    when (result.request.state) {
                        RequestState.REQUEST_STATE_CONFIRMED -> R.string.answer_confirmed
                        RequestState.REQUEST_STATE_FAILED -> R.string.answer_chain_failed
                        else -> R.string.answer_sent
                    }
                SigningOutcome.Declined -> R.string.answer_declined_in_wallet
                is SigningOutcome.Failed -> R.string.answer_not_signed
                is SigningOutcome.Unresolved ->
                    if (result.request.transfer() != null) R.string.answer_unknown
                    else R.string.answer_unresolved
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

/**
 * What the phone made of a transfer's transaction, in words (SAW-020). Every one of these comes
 * from the bytes the wallet would sign; the sidecar's account of them is shown apart, and labelled
 * as the server's.
 */
@StringRes
fun verdictText(verdict: Verdict): Int =
    when (verdict) {
        Verdict.Verified -> R.string.transfer_verdict_verified
        Verdict.Unverified -> R.string.transfer_verdict_unverified
        Verdict.Invalid -> R.string.transfer_verdict_invalid
    }

@StringRes
fun findingText(finding: Finding): Int =
    when (finding) {
        Finding.HashMismatch -> R.string.finding_hash_mismatch
        Finding.Malformed -> R.string.finding_malformed
        Finding.UnsupportedVersion -> R.string.finding_unsupported_version
        Finding.AddressTableLookup -> R.string.finding_address_table_lookup
        Finding.AlreadySigned -> R.string.finding_already_signed
        Finding.FeePayerNotTheWallet -> R.string.finding_fee_payer
        Finding.ExtraSigner -> R.string.finding_extra_signer
        Finding.NoWallet -> R.string.finding_no_wallet
        Finding.OtherWallet -> R.string.finding_other_wallet
        Finding.NetworkMismatch -> R.string.finding_network_mismatch
        Finding.NoTransfer -> R.string.finding_no_transfer
        Finding.ExtraTransfer -> R.string.finding_extra_transfer
        Finding.RecipientMismatch -> R.string.finding_recipient_mismatch
        Finding.AmountMismatch -> R.string.finding_amount_mismatch
        Finding.MintMismatch -> R.string.finding_mint_mismatch
        Finding.SourceNotOwnersAccount -> R.string.finding_source_account
        Finding.DestinationNotRecipientsAccount -> R.string.finding_destination_account
        Finding.DestinationOwnerUnchecked -> R.string.finding_destination_owner_unchecked
        Finding.AccountCreationForSomeoneElse -> R.string.finding_creation_for_someone_else
        Finding.UnrecognizedInstruction -> R.string.finding_unrecognized
        Finding.UnreadableValueInstruction -> R.string.finding_unreadable_value
    }

/**
 * The amount, with its base units always alongside it. The readable form is a convenience; the base
 * units are the number the transaction actually carries, and the one to compare against a request.
 */
@Composable
fun amountText(facts: TransferFacts): String =
    stringResource(
        if (facts.mint == null) R.string.transfer_amount_sol else R.string.transfer_amount_token,
        formatBaseUnits(facts.amount, facts.decimals),
        facts.amount.toString(),
    )

/** The sidecar's own estimate of what the transfer costs, which is not something bytes can show. */
@Composable
fun estimateText(prepared: PreparedTransaction): String {
    val fee = formatBaseUnits(prepared.feeLamports.toULong(), LAMPORT_DECIMALS)
    val rent = prepared.rentLamports.toULong()
    return if (rent == 0UL) stringResource(R.string.transfer_estimate_fee, fee)
    else
        stringResource(
            R.string.transfer_estimate_fee_and_rent,
            fee,
            formatBaseUnits(rent, LAMPORT_DECIMALS),
        )
}
