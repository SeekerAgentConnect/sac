package io.github.brrenat.seekervault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.designsystem.WalletHandoff
import io.github.brrenat.seekervault.designsystem.WalletHandoffKind
import io.github.brrenat.seekervault.designsystem.WalletHandoffWallet
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
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
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .testTag(AppNavigationTags.WALLET_HANDOFF),
        contentAlignment = Alignment.BottomCenter,
    ) {
        WalletHandoff(
            summary = summary,
            wallet = WalletHandoffWallet.SeedVault,
            kind = WalletHandoffKind.Transfer,
            onApprove = onApprove,
            onDecline = onDecline,
            onLeaveWithoutAnswering = onLeaveWithoutAnswering,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier =
                Modifier.align(Alignment.TopEnd)
                    .padding(
                        top = SeekerTheme.spacing.lg,
                        end = SeekerTheme.spacing.md,
                    )
        ) {
            CloseButton(
                onClose = onLeaveWithoutAnswering,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }
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
