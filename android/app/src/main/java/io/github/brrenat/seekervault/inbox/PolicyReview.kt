package io.github.brrenat.seekervault.inbox

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.policy.DailyCheckScope
import io.github.brrenat.seekervault.policy.DailyPolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckResult
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RuleSource
import io.github.brrenat.seekervault.policy.assessmentText
import io.github.brrenat.seekervault.policy.checkText
import io.github.brrenat.seekervault.policy.reasonText
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.ui.SeekerCard

/**
 * What the owner's own rules made of the request, on the screen where they answer it (SAW-028,
 * docs/guides/policies.md).
 *
 * It is shown apart from the facts above it, and it is the weaker of the two on purpose. The facts
 * come from the transaction's own bytes, and a transaction that disagrees with its request has no
 * Approve button at all, whatever this says. This is the owner's own note to themselves about what
 * they expected this agent to ask for, and it decides nothing: **allowed** means the request
 * matched what they wrote down, not that anything has been approved, and **outside your rules**
 * stops nothing.
 *
 * Every check is named, with what it read and what became of it, in words. Nothing here is said by
 * colour alone: a reader who sees no colour at all, or hears the screen rather than seeing it, is
 * told exactly the same things.
 */
@Composable
fun PolicyReview(assessment: RequestAssessment?, modifier: Modifier = Modifier) {
    if (assessment == null) {
        // The rules are read from disk when the request is opened. It is a moment, and it says so
        // rather than leaving a gap that could be read as "nothing to say".
        SeekerCard(
            modifier =
                modifier.fillMaxWidth().padding(16.dp).testTag(InboxTags.POLICY_PENDING).semantics(
                    mergeDescendants = true
                ) {}
        ) {
            Text(
                stringResource(R.string.policy_review_pending),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    val decision = assessment.decision
    SeekerCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color =
            if (decision.warns) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.policy_review_heading),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            Text(
                stringResource(assessmentText(decision.assessment)),
                style = MaterialTheme.typography.bodyLarge,
                // Colour where there is something to warn about, and never colour on its own: the
                // verdict, every reason, and every check say what they mean in words.
                color =
                    if (decision.warns) MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier =
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag(InboxTags.POLICY_VERDICT),
            )
            // Why there was nothing to match, or why a match was withheld. It is the reason for the
            // verdict itself rather than any one check's, so it stands above them.
            decision.reason?.let { reason ->
                Text(
                    if (
                        reason == PolicyReason.PolicyUnreadable &&
                            decision.unreadableSources.isNotEmpty()
                    ) {
                        stringResource(unreadableText(decision.unreadableSources))
                    } else {
                        stringResource(reasonText(reason))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag(InboxTags.POLICY_REASON),
                )
            }
            decision.checks
                .filterNot {
                    decision.dailyChecks.isNotEmpty() && it.check == PolicyCheck.DailyLimit
                }
                .forEach { Check(it) }
            decision.dailyChecks.forEach { DailyCheck(it, assessment.facts.decimals) }
            // Coverage, not compliance: ALLOWED is never a statement about a parameter nobody wrote
            // a
            // rule for, so the ones nothing covered are named.
            if (decision.notChecked.isNotEmpty()) {
                val names = decision.notChecked.map { stringResource(checkText(it)) }
                Text(
                    stringResource(R.string.policy_review_uncovered, names.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag(InboxTags.POLICY_UNCOVERED),
                )
            }
            // The line that never changes, under every verdict there is.
            Text(
                stringResource(R.string.policy_review_manual),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp).testTag(InboxTags.POLICY_MANUAL),
            )
        }
    }
}

/** One check: what it is about, what became of it, and what it read. */
@Composable
private fun Check(result: PolicyCheckResult) {
    val name = stringResource(checkText(result.check))
    SeekerCard(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(InboxTags.policyCheck(result.check))
                .semantics(mergeDescendants = true) {},
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        radius = 12.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                checkName(name, result.source, result.status),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(statusText(result), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The two daily scopes never collapse into one row: either one can independently warn. */
@Composable
private fun DailyCheck(check: DailyPolicyCheck, decimals: Int) {
    val name =
        stringResource(
            when (check.scope) {
                DailyCheckScope.Global -> R.string.policy_daily_global
                DailyCheckScope.Connection -> R.string.policy_daily_connection
            }
        )
    val total = check.total
    val projected = check.projected
    SeekerCard(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(InboxTags.policyDaily(check.scope.code))
                .semantics(mergeDescendants = true) {},
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        radius = 12.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                checkName(name, check.result.source, check.result.status),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(statusText(check.result), style = MaterialTheme.typography.bodyMedium)
            if (total != null && projected != null) {
                Text(
                    stringResource(
                        R.string.policy_daily_totals,
                        formatBaseUnits(total.confirmed, decimals),
                        formatBaseUnits(total.unresolved, decimals),
                        formatBaseUnits(projected, decimals),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun checkName(
    name: String,
    source: RuleSource,
    status: PolicyCheckStatus,
): String {
    // A legacy flat decision did not carry source metadata. Do not call that "Not configured"
    // when its check ran; current effective decisions always carry Global or Connection.
    if (source == RuleSource.NotConfigured && status != PolicyCheckStatus.NotConfigured) return name
    return stringResource(R.string.policy_check_with_source, name, sourceName(source))
}

@Composable
private fun sourceName(source: RuleSource): String =
    stringResource(
        when (source) {
            RuleSource.Global -> R.string.policy_source_global
            RuleSource.ConnectionOverride -> R.string.policy_source_connection
            RuleSource.NotConfigured -> R.string.policy_source_none
        }
    )

@Composable
private fun statusText(result: PolicyCheckResult): String {
    val detail = result.detail
    return when (result.status) {
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
}

private fun unreadableText(sources: List<RuleSource>): Int =
    when (sources.toSet()) {
        setOf(RuleSource.Global) -> R.string.policy_unreadable_global
        setOf(RuleSource.ConnectionOverride) -> R.string.policy_unreadable_connection
        else -> R.string.policy_unreadable_both
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
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(InboxTags.POLICY_ACKNOWLEDGE)
                .toggleable(
                    value = acknowledged,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Checkbox,
                    onValueChange = onAcknowledge,
                )
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (acknowledged) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
            contentDescription = null,
            tint =
                if (acknowledged) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.policy_acknowledge),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
