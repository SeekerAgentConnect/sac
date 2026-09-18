package io.github.brrenat.seekervault.activity

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.policy.DailyCheckScope
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.RuleSource
import io.github.brrenat.seekervault.policy.assessmentOf
import io.github.brrenat.seekervault.policy.assessmentText
import io.github.brrenat.seekervault.policy.checkOf
import io.github.brrenat.seekervault.policy.checkText
import io.github.brrenat.seekervault.policy.reasonOf
import io.github.brrenat.seekervault.policy.reasonText
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits

/** Test tags for the Activity screens' controls. */
object ActivityTags {
    const val LIST = "activityList"
    const val EMPTY = "activityEmpty"
    const val REFRESH = "activityRefresh"
    const val CLEAR = "activityClear"
    const val CONFIRM_CLEAR = "activityConfirmClear"
    const val UNREADABLE = "activityUnreadable"
    const val OUTCOME = "activityOutcome"
    const val OPERATION = "activityOperation"
    const val CLUSTER = "activityCluster"
    const val SIGNATURE = "activitySignature"
    const val NOT_A_PAYMENT = "activityNotAPayment"
    const val EXPLORER = "activityExplorer"
    const val CHECKED_WITH = "activityCheckedWith"
    const val DETAIL = "activityDetail"
    const val SOURCE = "activitySource"
    const val POLICY = "activityPolicy"

    fun item(record: ActivityRecord) = "activity:${record.connectionId}/${record.requestId}"

    /** Which promise an operation was bound under (SEE-97). */
    const val ENVIRONMENT = "activityEnvironment"

    fun field(name: String) = "activityField:$name"
}

@Composable
fun kindText(kind: ActivityKind): String =
    stringResource(
        when (kind) {
            ActivityKind.Acknowledgement -> R.string.activity_kind_ack
            ActivityKind.MessageSignature -> R.string.activity_kind_message
            ActivityKind.Transfer -> R.string.activity_kind_transfer
            ActivityKind.Operation -> R.string.activity_kind_operation
            ActivityKind.Other -> R.string.activity_kind_other
        }
    )

@StringRes
fun outcomeText(outcome: ActivityOutcome): Int =
    when (outcome) {
        ActivityOutcome.Waiting -> R.string.activity_outcome_waiting
        ActivityOutcome.Acknowledged -> R.string.activity_outcome_acknowledged
        ActivityOutcome.Rejected -> R.string.activity_outcome_rejected
        ActivityOutcome.DeclinedInWallet -> R.string.activity_outcome_declined
        ActivityOutcome.MessageSigned -> R.string.activity_outcome_message_signed
        ActivityOutcome.Sent -> R.string.activity_outcome_sent
        ActivityOutcome.Confirmed -> R.string.activity_outcome_confirmed
        ActivityOutcome.ChainFailed -> R.string.activity_outcome_chain_failed
        ActivityOutcome.NotSigned -> R.string.activity_outcome_not_signed
        ActivityOutcome.Simulated -> R.string.activity_outcome_simulated
        ActivityOutcome.Unknown -> R.string.activity_outcome_unknown
        ActivityOutcome.NotDelivered -> R.string.activity_outcome_not_delivered
        ActivityOutcome.Superseded -> R.string.activity_outcome_superseded
    }

/**
 * The cluster the record belongs to, spelled out. It is on every transfer in the history, because a
 * signature means nothing without it: the same 64 bytes on another cluster are another transaction,
 * or none.
 */
@Composable
fun clusterText(record: ActivityRecord): String? =
    // A transfer's own cluster, or the one an operation from a shared proposal was bound to
    // (SEE-89): its signature is a transaction's ID too, and means nothing without the cluster.
    (record.transfer?.network ?: record.operation?.network)?.let { network ->
        stringResource(
            when (network) {
                Network.NETWORK_MAINNET -> R.string.activity_cluster_mainnet
                Network.NETWORK_DEVNET -> R.string.activity_cluster_devnet
                Network.NETWORK_TESTNET -> R.string.activity_cluster_testnet
                else -> R.string.activity_cluster_unknown
            }
        )
    }

/**
 * What was asked for, in the terms the owner reviewed. The base units are always there: they are
 * the number the transaction carried. SOL is also shown the readable way, because its decimals are
 * fixed and this phone knows them; a token's are the mint's, and are not stored (SAW-023).
 */
@Composable
fun operationText(record: ActivityRecord): String {
    // An operation from a shared proposal is named at the protocol's level, with the plugin that
    // prepared its bytes beside it: both are codes the record kept, and neither is this app's
    // word for what a provider does (SEE-89).
    record.operation?.let {
        return stringResource(R.string.activity_operation_plugin, it.operation, it.plugin)
    }
    val transfer = record.transfer ?: return kindText(record.kind)
    val mint = transfer.mint
    val amount = transfer.amount.toULongOrNull()
    return if (mint == null && amount != null) {
        stringResource(
            R.string.activity_amount_sol,
            formatBaseUnits(amount, LAMPORT_DECIMALS),
            transfer.amount,
            transfer.recipient,
        )
    } else if (mint == null) {
        stringResource(R.string.activity_amount_units, transfer.amount, transfer.recipient)
    } else {
        stringResource(
            R.string.activity_amount_token,
            transfer.amount,
            mint,
            transfer.recipient,
        )
    }
}

/**
 * The assessment the owner read when they answered, in words (SAW-028).
 *
 * The record keeps codes, so what a code means can be said better later without the record having
 * to be rewritten — and a code this build has no name for is left out of the reading rather than
 * shown as itself. It says nothing about what the rules were: those are stored once, in the one
 * place they belong, and a rule the owner has changed since does not rewrite what they were told.
 */
@Composable
fun policyText(policy: ReviewedPolicy): String {
    val assessment =
        assessmentOf(policy.assessment) ?: return stringResource(R.string.activity_policy_unknown)
    val verdict = stringResource(assessmentText(assessment))
    val lines = mutableListOf<String>()
    lines +=
        if (policy.approvedAnyway) stringResource(R.string.activity_policy_anyway, verdict)
        else verdict
    for (code in policy.reasons) {
        reasonOf(code)?.let { lines += stringResource(reasonText(it)) }
    }
    val uncovered = mutableListOf<String>()
    for (code in policy.notChecked) {
        checkOf(code)?.let { uncovered += stringResource(checkText(it)) }
    }
    if (uncovered.isNotEmpty()) {
        lines += stringResource(R.string.activity_policy_uncovered, uncovered.joinToString())
    }
    for (stored in policy.ruleSources) {
        val check = checkOf(stored.check) ?: continue
        val source = RuleSource.entries.firstOrNull { it.code == stored.source } ?: continue
        lines +=
            stringResource(
                R.string.activity_policy_rule_source,
                stringResource(checkText(check)),
                reviewedSourceText(source),
            )
    }
    for (stored in policy.dailyChecks) {
        val scope = DailyCheckScope.entries.firstOrNull { it.code == stored.scope } ?: continue
        val source = RuleSource.entries.firstOrNull { it.code == stored.source } ?: continue
        val status = PolicyCheckStatus.entries.firstOrNull { it.code == stored.status } ?: continue
        lines +=
            stringResource(
                R.string.activity_policy_daily_source,
                stringResource(
                    if (scope == DailyCheckScope.Global) R.string.policy_daily_global
                    else R.string.policy_daily_connection
                ),
                reviewedStatusText(status),
                reviewedSourceText(source),
            )
    }
    val unreadable =
        policy.unreadableSources.mapNotNull { code ->
            RuleSource.entries.firstOrNull { it.code == code }?.let { reviewedSourceText(it) }
        }
    if (unreadable.isNotEmpty()) {
        lines +=
            stringResource(R.string.activity_policy_unreadable_sources, unreadable.joinToString())
    }
    return lines.joinToString("\n")
}

@Composable
private fun reviewedSourceText(source: RuleSource): String =
    stringResource(
        when (source) {
            RuleSource.Global -> R.string.policy_source_global
            RuleSource.ConnectionOverride -> R.string.policy_source_connection
            RuleSource.NotConfigured -> R.string.policy_source_none
        }
    )

@Composable
private fun reviewedStatusText(status: PolicyCheckStatus): String =
    stringResource(
        when (status) {
            PolicyCheckStatus.Passed -> R.string.policy_status_passed_plain
            PolicyCheckStatus.Failed -> R.string.policy_status_failed_plain
            PolicyCheckStatus.Unverified -> R.string.policy_status_unverified_plain
            PolicyCheckStatus.NotConfigured -> R.string.policy_status_not_configured
        }
    )
