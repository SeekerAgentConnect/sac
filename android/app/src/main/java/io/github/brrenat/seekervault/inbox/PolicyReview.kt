package io.github.brrenat.seekervault.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun PolicyReview(
    assessment: RequestAssessment?,
    modifier: Modifier = Modifier,
    onRules: (() -> Unit)? = null,
    transaction: Boolean = false,
) {
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
    val allowed = decision.allowed
    val transactionWithoutRules = transaction && decision.reason == PolicyReason.NoPolicyConfigured
    val verdictInk =
        if (allowed) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onTertiaryContainer
    val ordinaryChecks =
        decision.checks.filterNot {
            decision.dailyChecks.isNotEmpty() && it.check == PolicyCheck.DailyLimit
        }
    val configuredChecks = ordinaryChecks.filter { it.status != PolicyCheckStatus.NotConfigured }
    val deliberatelyOff =
        ordinaryChecks
            .filter {
                it.status == PolicyCheckStatus.NotConfigured &&
                    it.source != RuleSource.NotConfigured
            }
            .map { it.check }
    val absent =
        ordinaryChecks
            .filter {
                it.status == PolicyCheckStatus.NotConfigured &&
                    it.source == RuleSource.NotConfigured
            }
            .map { it.check }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SeekerCard(
            modifier = Modifier.fillMaxWidth(),
            color =
                if (allowed) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer,
            radius = 16.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (allowed) Icons.Outlined.Verified else Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = verdictInk,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        stringResource(
                            if (transaction && !allowed) {
                                R.string.transfer_rules_no_policy_title
                            } else {
                                assessmentText(decision.assessment)
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = verdictInk,
                        modifier = Modifier.weight(1f).testTag(InboxTags.POLICY_VERDICT),
                    )
                }
                if (transactionWithoutRules) {
                    Text(
                        stringResource(R.string.transfer_rules_no_policy_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = verdictInk,
                        modifier = Modifier.testTag(InboxTags.POLICY_REASON),
                    )
                    Text(
                        stringResource(R.string.transfer_rules_no_policy_secondary),
                        style = MaterialTheme.typography.bodySmall,
                        color = verdictInk,
                    )
                } else {
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
                            color = verdictInk,
                            modifier = Modifier.testTag(InboxTags.POLICY_REASON),
                        )
                    }
                }
                configuredChecks.forEach { ConfiguredCheck(it, verdictInk) }
                if (
                    !transactionWithoutRules &&
                        (deliberatelyOff.isNotEmpty() || absent.isNotEmpty())
                ) {
                    Column(
                        Modifier.testTag(InboxTags.POLICY_UNCOVERED).semantics(
                            mergeDescendants = true
                        ) {},
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (deliberatelyOff.isNotEmpty()) {
                            Text(
                                stringResource(
                                    R.string.policy_review_deliberately_unchecked,
                                    checkNames(deliberatelyOff),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = verdictInk,
                            )
                        }
                        if (absent.isNotEmpty()) {
                            Text(
                                stringResource(
                                    R.string.policy_review_not_configured,
                                    checkNames(absent),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = verdictInk,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!transactionWithoutRules) {
                        Text(
                            stringResource(R.string.policy_review_manual),
                            style = MaterialTheme.typography.bodySmall,
                            color = verdictInk,
                            modifier = Modifier.weight(1f).testTag(InboxTags.POLICY_MANUAL),
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    onRules?.let {
                        RulesButton(
                            allowed = allowed,
                            onClick = it,
                            label =
                                if (transactionWithoutRules) {
                                    R.string.transfer_configure_rules
                                } else {
                                    R.string.rules_heading
                                },
                        )
                    }
                }
            }
        }
        if (decision.dailyChecks.isNotEmpty()) {
            DailySpend(decision.dailyChecks, assessment.facts.decimals)
        }
    }
}

@Composable
private fun ConfiguredCheck(result: PolicyCheckResult, ink: androidx.compose.ui.graphics.Color) {
    val warns =
        result.status == PolicyCheckStatus.Failed || result.status == PolicyCheckStatus.Unverified
    Row(
        modifier =
            Modifier.fillMaxWidth().testTag(InboxTags.policyCheck(result.check)).semantics(
                mergeDescendants = true
            ) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (warns) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(checkText(result.check)),
                style = MaterialTheme.typography.labelMedium,
                color = ink,
            )
            if (warns) {
                Text(
                    stringResource(reasonText(checkNotNull(result.reason))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                )
                result.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ink)
                }
            } else {
                Text(statusText(result), style = MaterialTheme.typography.bodyMedium, color = ink)
            }
            SourceChip(result.source)
        }
    }
}

@Composable
private fun DailySpend(checks: List<DailyPolicyCheck>, decimals: Int) {
    SeekerCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        radius = 16.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.policy_daily_title),
                style = MaterialTheme.typography.labelLarge,
            )
            checks.forEach { check ->
                DailyRow(check, decimals)
            }
        }
    }
}

@Composable
private fun DailyRow(check: DailyPolicyCheck, decimals: Int) {
    val name =
        stringResource(
            when (check.scope) {
                DailyCheckScope.Global -> R.string.policy_daily_global
                DailyCheckScope.Connection -> R.string.policy_daily_connection
            }
        )
    val warns =
        check.result.status == PolicyCheckStatus.Failed ||
            check.result.status == PolicyCheckStatus.Unverified
    val ink = if (warns) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().testTag(InboxTags.policyDaily(check.scope.code)).semantics(
            mergeDescendants = true
        ) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (warns) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(statusText(check.result), style = MaterialTheme.typography.bodyMedium, color = ink)
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val total = check.total
            val projected = check.projected
            if (total != null && projected != null) {
                Text(
                    stringResource(
                        R.string.policy_daily_totals,
                        formatBaseUnits(total.confirmed, decimals),
                        formatBaseUnits(total.unresolved, decimals),
                        formatBaseUnits(projected, decimals),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SourceChip(check.result.source)
    }
}

@Composable
private fun RulesButton(allowed: Boolean, onClick: () -> Unit, @StringRes label: Int) {
    val container =
        if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val ink =
        if (allowed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary
    Box(
        Modifier.height(32.dp)
            .testTag(InboxTags.RULES_BUTTON)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = ink,
        )
    }
}

@Composable
private fun SourceChip(source: RuleSource) {
    val container =
        if (source == RuleSource.ConnectionOverride) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val ink =
        if (source == RuleSource.ConnectionOverride) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Row(
        Modifier.height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (source) {
                RuleSource.Global -> Icons.Outlined.Public
                RuleSource.ConnectionOverride -> Icons.Outlined.Edit
                RuleSource.NotConfigured -> Icons.Outlined.Block
            },
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(13.dp),
        )
        Text(sourceName(source), style = MaterialTheme.typography.labelSmall, color = ink)
    }
}

@Composable
private fun checkNames(checks: List<PolicyCheck>): String =
    checks.distinct().map { stringResource(checkText(it)) }.joinToString()

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
