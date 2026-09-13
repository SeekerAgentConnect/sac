package io.github.brrenat.seekervault.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.policy.PolicyAssessment
import io.github.brrenat.seekervault.policy.PolicyCheckResult
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.assessmentText
import io.github.brrenat.seekervault.policy.checkText
import io.github.brrenat.seekervault.policy.reasonText
import io.github.brrenat.seekervault.ui.GlassCheck
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space
import io.github.brrenat.seekervault.ui.TextLink
import io.github.brrenat.seekervault.ui.dashedBorder

/**
 * What the owner's own rules made of the request, on the screen where they answer it (SAW-028,
 * docs/guides/policies.md).
 *
 * It is shown apart from the facts above it, and it is the weaker of the two on purpose. The facts
 * come from the transaction's own bytes, and a transaction that disagrees with its request has no
 * Approve button at all, whatever this says. This is the owner's own note to themselves about what
 * they expected this agent to ask for, and it decides nothing: **within the rules you set** means
 * the request matched what they wrote down, not that anything has been approved, and **under
 * restrictions** stops nothing.
 *
 * Every check is named, with what it read and what became of it, in words. Nothing here is said by
 * colour alone (SEE-57): the card that warns is drawn with a *dashed* border and a warning mark,
 * and a reader who sees no colour at all, or hears the screen rather than seeing it, is told
 * exactly the same things.
 */
@Composable
fun PolicyReview(
    assessment: RequestAssessment?,
    modifier: Modifier = Modifier,
    onRules: (() -> Unit)? = null,
) {
    if (assessment == null) {
        // The rules are read from disk when the request is opened. It is a moment, and it says so
        // rather than leaving a gap that could be read as "nothing to say".
        Text(
            stringResource(R.string.policy_review_pending),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral500,
            modifier = modifier.testTag(InboxTags.POLICY_PENDING),
        )
        return
    }
    val decision = assessment.decision
    val within = decision.assessment == PolicyAssessment.Allowed
    val shape = RoundedCornerShape(Radius.Inner)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (within) Nocturne.accent(0.14f) else Nocturne.text(0.06f))
            .then(
                if (within) Modifier.border(1.dp, Nocturne.accent(0.38f), shape)
                else Modifier.dashedBorder(Nocturne.text(0.22f), Radius.Inner)
            )
            .padding(Space.Inset),
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        val ink = if (within) Nocturne.Accent100 else Nocturne.Neutral200
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (within) Glyph.WithinRules else Glyph.Warning,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(assessmentText(decision.assessment)),
                style = MaterialTheme.typography.bodyLarge,
                color = ink,
                modifier = Modifier.weight(1f).testTag(InboxTags.POLICY_VERDICT),
            )
            if (onRules != null) {
                TextLink(stringResource(R.string.policy_rules), onRules)
            }
        }
        // Why there was nothing to match, or why a match was withheld. It is the reason for the
        // verdict itself rather than any one check's, so it stands above them.
        decision.reason?.let { reason ->
            Bullet(
                stringResource(reasonText(reason)),
                Modifier.testTag(InboxTags.POLICY_REASON).semantics(mergeDescendants = true) {},
            )
        }
        decision.checks.forEach { Check(it) }
        // Coverage, not compliance: a match is never a statement about a parameter nobody wrote a
        // rule for, so the ones nothing covered are named.
        if (decision.notChecked.isNotEmpty()) {
            val names = decision.notChecked.map { stringResource(checkText(it)) }
            Text(
                stringResource(R.string.policy_review_uncovered, names.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral500,
                modifier = Modifier.testTag(InboxTags.POLICY_UNCOVERED),
            )
        }
        // The line that never changes, under every verdict there is.
        Text(
            stringResource(R.string.policy_review_manual),
            style = MaterialTheme.typography.bodySmall,
            color = Nocturne.Neutral500,
            modifier = Modifier.testTag(InboxTags.POLICY_MANUAL),
        )
    }
}

/** One check: what it is about, what became of it, and what it read. */
@Composable
private fun Check(result: PolicyCheckResult) {
    val detail = result.detail
    val status =
        when (result.status) {
            PolicyCheckStatus.Passed ->
                if (detail == null) stringResource(R.string.policy_status_passed_plain)
                else stringResource(R.string.policy_status_passed, detail)
            PolicyCheckStatus.Failed ->
                if (detail == null) stringResource(R.string.policy_status_failed_plain)
                else stringResource(R.string.policy_status_failed, detail)
            PolicyCheckStatus.Unverified ->
                if (detail == null) stringResource(R.string.policy_status_unverified_plain)
                else stringResource(R.string.policy_status_unverified, detail)
            PolicyCheckStatus.NotConfigured -> stringResource(R.string.policy_status_not_configured)
        }
    Column(
        Modifier.fillMaxWidth().testTag(InboxTags.policyCheck(result.check)).semantics(
            mergeDescendants = true
        ) {},
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        SectionLabel(stringResource(checkText(result.check)))
        Bullet(status)
    }
}

/**
 * One line of the card's reasoning, marked as one of several rather than as a sentence on its own.
 */
@Composable
private fun Bullet(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.Xs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "·",
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral500,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Nocturne.Neutral200)
    }
}

/**
 * The step between a warning and an affirmative answer (SAW-028).
 *
 * A warning the owner can tap straight past is a warning that teaches them to tap past warnings, so
 * going ahead is a thing they say they are doing. What is kept is the assessment they said it
 * about: read the reasons again for a different assessment, because these are not those reasons.
 *
 * The whole row is the control, so the words and the box are one thing to a screen reader and one
 * target for a finger, and the box carries no meaning the words don't.
 */
@Composable
fun ApproveAnyway(
    acknowledged: Boolean,
    onAcknowledge: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.InnerTight))
            .testTag(InboxTags.POLICY_ACKNOWLEDGE)
            .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = onAcknowledge)
            .padding(Space.Md),
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCheck(acknowledged)
        Text(
            stringResource(R.string.policy_acknowledge),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral200,
        )
    }
}
