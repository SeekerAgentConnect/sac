package io.github.brrenat.seekervault.activity

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
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

    fun item(record: ActivityRecord) = "activity:${record.connectionId}/${record.requestId}"

    fun field(name: String) = "activityField:$name"
}

@Composable
fun kindText(kind: ActivityKind): String =
    stringResource(
        when (kind) {
            ActivityKind.Acknowledgement -> R.string.activity_kind_ack
            ActivityKind.MessageSignature -> R.string.activity_kind_message
            ActivityKind.Transfer -> R.string.activity_kind_transfer
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
    record.transfer?.let {
        stringResource(
            when (it.network) {
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
