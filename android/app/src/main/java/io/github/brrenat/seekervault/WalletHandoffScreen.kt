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

private fun String.shortAddress(): String = if (length <= 14) this else "${take(7)}…${takeLast(5)}"

internal object AppNavigationTags {
    const val WALLET_HANDOFF = "walletHandoff"
}
