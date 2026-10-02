package io.github.brrenat.seekervault.reviews

import androidx.annotation.StringRes
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckResult
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.PolicyDecision
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RuleSource
import io.github.brrenat.seekervault.policy.reasonText

/**
 * What a review's verdict card says, reduced to what a tile needs to say the same thing (SEE-158).
 *
 * One reading of a [PolicyDecision] for both places, so the chip on a home tile and the card in the
 * sheet it opens cannot disagree: the tile counts exactly the warnings the card lists, and says
 * "Outside rules" exactly when the card does.
 */
sealed interface ReviewVerdict {
    /** Every configured check passed. */
    data object Within : ReviewVerdict

    /**
     * Nothing has been prepared yet, and nothing the rules could already say is wrong (SEE-180).
     *
     * The rules are applied to a transaction, and there is none: what they would make of it is not
     * known, which is neither a match nor a warning. It becomes one or the other once the order is
     * prepared and read.
     */
    data object Pending : ReviewVerdict

    /**
     * No rules apply to this connection at all. Nothing failed, but nothing was checked either, so
     * this is shown as outside the rules rather than within them.
     */
    data object NoRules : ReviewVerdict

    data class Warnings(val warnings: List<ReviewWarning>) : ReviewVerdict {
        init {
            require(warnings.isNotEmpty()) { "a warning verdict names at least one warning" }
        }
    }
}

/**
 * What raised a warning: one of the owner's rule documents, or this phone's own reading of the
 * transaction (SEE-180). A transaction the phone could not account for whole is not a rule anybody
 * wrote, so it is never shown as the global rule or the connection's.
 */
enum class WarningOrigin {
    Rules,
    Verification,
}

/** One line of the verdict card: what was flagged, and which rule set flagged it. */
data class ReviewWarning(
    @StringRes val message: Int,
    val detail: String?,
    /** The rule document behind a [WarningOrigin.Rules] warning; not configured otherwise. */
    val source: RuleSource,
    val daily: Boolean = false,
    val origin: WarningOrigin = WarningOrigin.Rules,
)

/**
 * The verdict card's reading of this decision.
 *
 * [prepared] says whether there is a transaction the decision was about. Before there is one the
 * facts are unread by construction, so "the whole transaction could not be accounted for" and every
 * check that could not be applied to a missing amount or asset are not findings: they are the
 * absence of a transaction (SEE-180). What the rules can already say — an action that is not
 * allowed, rules that cannot be read, a day's total that cannot be established — is still said. The
 * decision itself is untouched: it stays as conservative as it was, and only its reading for the
 * card changes.
 */
fun PolicyDecision.reviewVerdict(prepared: Boolean = true): ReviewVerdict {
    if (allowed) return ReviewVerdict.Within
    if (reason == PolicyReason.NoPolicyConfigured) return ReviewVerdict.NoRules
    fun PolicyCheckResult.shown(): Boolean =
        warns() &&
            (prepared ||
                status == PolicyCheckStatus.Failed ||
                reason == PolicyReason.DailyTotalUnverified)
    val warnings = buildList {
        reason
            ?.takeIf { prepared || it != PolicyReason.RequestUnverified }
            ?.let {
                add(
                    if (it == PolicyReason.RequestUnverified) unaccounted()
                    else
                        ReviewWarning(
                            message = reasonText(it),
                            detail = null,
                            source = unreadableSources.firstOrNull() ?: RuleSource.Global,
                        )
                )
            }
        checks
            .filterNot { dailyChecks.isNotEmpty() && it.check == PolicyCheck.DailyLimit }
            .filter { it.shown() }
            .forEach { result ->
                result.reason?.let {
                    add(ReviewWarning(reasonText(it), result.detail, result.source))
                }
            }
        dailyChecks
            .map { it.result }
            .filter { it.shown() }
            .forEach { result ->
                result.reason?.let {
                    add(ReviewWarning(reasonText(it), result.detail, result.source, daily = true))
                }
            }
    }
    if (warnings.isEmpty() && !prepared) return ReviewVerdict.Pending
    // UNDER_RESTRICTIONS always has a reason somewhere; if a later check type ever arrives without
    // one, the card still says something rather than turning lime.
    return ReviewVerdict.Warnings(warnings.ifEmpty { listOf(unaccounted()) })
}

/** The phone's own reading fell short: a verification finding, attributed to no rule. */
private fun unaccounted() =
    ReviewWarning(
        message = reasonText(PolicyReason.RequestUnverified),
        detail = null,
        source = RuleSource.NotConfigured,
        origin = WarningOrigin.Verification,
    )

private fun PolicyCheckResult.warns(): Boolean =
    status == PolicyCheckStatus.Failed || status == PolicyCheckStatus.Unverified
