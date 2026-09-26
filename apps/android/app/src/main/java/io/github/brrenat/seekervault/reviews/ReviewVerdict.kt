package io.github.brrenat.seekervault.reviews

import androidx.annotation.StringRes
import io.github.brrenat.seekervault.policy.PolicyCheck
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

/** One line of the verdict card: what was flagged, and which rule set flagged it. */
data class ReviewWarning(
    @StringRes val message: Int,
    val detail: String?,
    val source: RuleSource,
    val daily: Boolean = false,
)

fun PolicyDecision.reviewVerdict(): ReviewVerdict {
    if (allowed) return ReviewVerdict.Within
    if (reason == PolicyReason.NoPolicyConfigured) return ReviewVerdict.NoRules
    val warnings = buildList {
        reason?.let {
            add(
                ReviewWarning(
                    message = reasonText(it),
                    detail = null,
                    source = unreadableSources.firstOrNull() ?: RuleSource.Global,
                )
            )
        }
        checks
            .filterNot { dailyChecks.isNotEmpty() && it.check == PolicyCheck.DailyLimit }
            .filter { it.warns() }
            .forEach { result ->
                result.reason?.let {
                    add(ReviewWarning(reasonText(it), result.detail, result.source))
                }
            }
        dailyChecks
            .map { it.result }
            .filter { it.warns() }
            .forEach { result ->
                result.reason?.let {
                    add(ReviewWarning(reasonText(it), result.detail, result.source, daily = true))
                }
            }
    }
    // UNDER_RESTRICTIONS always has a reason somewhere; if a later check type ever arrives without
    // one, the card still says something rather than turning lime.
    return ReviewVerdict.Warnings(
        warnings.ifEmpty {
            listOf(
                ReviewWarning(reasonText(PolicyReason.RequestUnverified), null, RuleSource.Global)
            )
        }
    )
}

private fun io.github.brrenat.seekervault.policy.PolicyCheckResult.warns(): Boolean =
    status == PolicyCheckStatus.Failed || status == PolicyCheckStatus.Unverified
