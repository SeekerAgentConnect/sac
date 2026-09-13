package io.github.brrenat.seekervault.inbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.policy.PolicyAssessment
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.transactions.mint
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.Tag
import io.github.brrenat.seekervault.ui.TagTone
import io.github.brrenat.seekervault.ui.truncateMiddle

/**
 * How one request reads at a glance (SEE-57): its kind, what is at stake, the one line that names
 * it, and the identifier under that. The carousel tile, the swipe row and the review panel all say
 * the same things in the same words, so moving between them never looks like a different request.
 *
 * Everything here comes from the structured request — what the agent *asked for*. It is not, and
 * never claims to be, what the transaction does: that is read from the transaction's own bytes, in
 * the review, and only there. This is why no answer that reaches a wallet can be given from a tile
 * or a row (docs/guides/pending-requests.md).
 */
enum class RequestKind {
    Acknowledge,
    Signature,
    Transfer,
    Swap,
    Unknown,
}

fun kindOf(request: ActionRequest): RequestKind =
    when (request.action.kindCase) {
        Action.KindCase.ACK -> RequestKind.Acknowledge
        Action.KindCase.SIGN_MESSAGE -> RequestKind.Signature
        Action.KindCase.TRANSFER -> RequestKind.Transfer
        Action.KindCase.SWAP -> RequestKind.Swap
        else -> RequestKind.Unknown
    }

/** The icon of a kind. */
fun kindIcon(kind: RequestKind): ImageVector =
    when (kind) {
        RequestKind.Acknowledge -> Glyph.Acknowledge
        RequestKind.Signature -> Glyph.Signature
        RequestKind.Transfer,
        RequestKind.Swap -> Glyph.Transfer
        RequestKind.Unknown -> Glyph.Warning
    }

/**
 * What is at stake, in three words: what the owner is being asked to risk, not what it is called.
 */
@Composable
fun stakeText(kind: RequestKind): String =
    stringResource(
        when (kind) {
            RequestKind.Acknowledge -> R.string.stake_acknowledge
            RequestKind.Signature -> R.string.stake_signature
            RequestKind.Transfer,
            RequestKind.Swap -> R.string.stake_transfer
            RequestKind.Unknown -> R.string.stake_unknown
        }
    )

/**
 * How much weight a request carries, from 0 to 1. The design uses it for the accent ring and the
 * glow on a carousel tile: the more a request could cost, the more the tile is lit.
 */
fun stakeWeight(kind: RequestKind): Float =
    when (kind) {
        RequestKind.Transfer,
        RequestKind.Swap -> 0.64f
        RequestKind.Signature -> 0.49f
        RequestKind.Acknowledge -> 0.36f
        RequestKind.Unknown -> 0.49f
    }

/**
 * The one line that names the request: the amount asked for, the size of what would be signed, or
 * the acknowledgement's own words.
 *
 * For a transfer this is the agent's own figure. The transaction is read in the review and nowhere
 * else, and nothing here can be approved, so no unread number ever becomes an answer.
 */
@Composable
fun headlineOf(request: ActionRequest): String {
    val transfer = request.transfer()
    if (transfer != null) {
        val amount = transfer.amount.toULongOrNull() ?: return actionText(request)
        val mint = transfer.mint()
        return if (mint == null) "${formatBaseUnits(amount, LAMPORT_DECIMALS)} SOL"
        else stringResource(R.string.transfer_amount_token, amount.toString(), amount.toString())
    }
    val message = messagePreview(request)
    if (message != null) {
        return pluralStringResource(R.plurals.carousel_bytes, message.bytes, message.bytes)
    }
    return request.text() ?: stringResource(R.string.carousel_acknowledge)
}

/** The identifier under the headline: who would be paid, who would sign, or which request it is. */
fun detailOf(request: ActionRequest): String {
    val transfer = request.transfer()
    if (transfer != null) return truncateAddress(transfer.recipient)
    val message = request.action.takeIf { it.hasSignMessage() }?.signMessage
    if (message != null) return truncateAddress(message.wallet)
    return truncateAddress(request.ref.requestId)
}

/** An address shortened the way the design shows one: `FyfWsS…SpEA`. */
fun truncateAddress(value: String): String = truncateMiddle(value)

/**
 * The rule pill on a request row (SEE-57): what the owner's own rules make of this request, in two
 * words, next to the request rather than instead of it.
 *
 * It is absent rather than wrong when there is nothing to say — a transfer whose transaction this
 * phone has not read yet is not "restricted", it is unread, and the review is where it is read.
 */
@Composable
fun RulePill(assessment: RequestAssessment?, modifier: Modifier = Modifier) {
    if (assessment == null) return
    val within = assessment.decision.assessment == PolicyAssessment.Allowed
    Tag(
        text =
            stringResource(
                if (within) R.string.rule_pill_within else R.string.rule_pill_restricted
            ),
        tone = if (within) TagTone.Accent else TagTone.Dashed,
        icon = Glyph.Rules,
        modifier = modifier.testTag(InboxTags.RULE_PILL),
    )
}
