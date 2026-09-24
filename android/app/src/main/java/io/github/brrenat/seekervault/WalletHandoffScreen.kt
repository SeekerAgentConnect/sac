package io.github.brrenat.seekervault

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.designsystem.WalletHandoffSheet
import io.github.brrenat.seekervault.designsystem.WalletHandoffSheetCallbacks
import io.github.brrenat.seekervault.designsystem.WalletHandoffSheetState
import io.github.brrenat.seekervault.policy.networkText
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.StakingAction
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.skr.SKR_DECIMALS
import io.github.brrenat.seekervault.skr.staking
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.transactions.transfer

/** The app-owned step immediately before a reviewed action is handed to Seed Vault Wallet. */
@Composable
internal fun WalletHandoffScreen(
    summary: String,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onLeaveWithoutAnswering: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletHandoffSheet(
        state =
            WalletHandoffSheetState(
                walletName = "Seed Vault Wallet",
                walletKind = "Another app",
                headline = "Approve a transaction",
                summary = summary,
                explanation =
                    "The wallet holds the keys. Seeker Agent Connect only asked; what happens " +
                        "next is decided here.",
                primaryLabel = "Sign and send",
                declineLabel = "Decline",
                leaveLabel = "Leave without answering",
            ),
        callbacks =
            WalletHandoffSheetCallbacks(
                onPrimary = onApprove,
                onDecline = onDecline,
                onLeave = onLeaveWithoutAnswering,
            ),
        modifier = modifier.fillMaxWidth().testTag(AppNavigationTags.WALLET_HANDOFF),
    )
}

@Composable
internal fun walletHandoffSummary(request: ActionRequest): String {
    request.signMessage()?.let { message ->
        return "Sign this message with ${message.wallet.shortAddress()}"
    }
    request.staking()?.let { staking ->
        return stakingHandoffSummary(staking)
    }
    val transfer = checkNotNull(request.transfer()) { "Wallet hand-off requires a wallet action" }
    val amount =
        transfer.amount.toULongOrNull()?.let {
            if (transfer.asset.kindCase == Asset.KindCase.NATIVE_SOL) {
                "${formatBaseUnits(it, LAMPORT_DECIMALS)} SOL"
            } else {
                "${transfer.amount} token units"
            }
        } ?: "${transfer.amount} token units"
    return "Send $amount to ${transfer.recipient.shortAddress()} on ${networkText(transfer.network)}"
}

/**
 * What the owner is about to do in the wallet, for each staking action.
 *
 * Unstaking and withdrawing get different sentences on purpose: one of them starts a wait and moves
 * nothing, and the other is the one that finally moves the SKR. This is the last screen before the
 * wallet opens, so it is the worst place to call both "unstake".
 */
@Composable
private fun stakingHandoffSummary(staking: StakingAction): String {
    val wallet = staking.wallet.shortAddress()
    val network = networkText(staking.network)
    val amount = staking.amount.toULongOrNull()?.let { "${formatBaseUnits(it, SKR_DECIMALS)} SKR" }
    return when (staking.operation) {
        StakingOperation.STAKING_OPERATION_STAKE ->
            "Stake ${amount ?: staking.amount} from $wallet on $network"
        StakingOperation.STAKING_OPERATION_UNSTAKE ->
            "Start unstaking ${amount ?: staking.amount} from $wallet on $network. " +
                "Nothing moves yet: a cooldown begins, and withdrawing is a separate step."
        StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE ->
            "Cancel the pending unstake for $wallet on $network, putting it back to work"
        StakingOperation.STAKING_OPERATION_WITHDRAW ->
            "Withdraw the unstaked SKR to $wallet on $network"
        else -> "Act on the SKR staking position of $wallet on $network"
    }
}

private fun String.shortAddress(): String = if (length <= 14) this else "${take(7)}…${takeLast(5)}"

internal object AppNavigationTags {
    const val WALLET_HANDOFF = "walletHandoff"
}
