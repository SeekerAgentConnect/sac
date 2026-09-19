package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class WalletHandoffWallet {
    SeedVault
}

enum class WalletHandoffKind {
    Transfer
}

@Composable
fun WalletHandoffCard(
    summary: String,
    wallet: WalletHandoffWallet,
    kind: WalletHandoffKind,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onLeaveWithoutAnswering: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentWidth =
        SeekerTheme.spacing.huge * WalletHandoffContentWidthUnits +
            SeekerTheme.spacing.xxl +
            SeekerTheme.spacing.xxs
    Column(
        modifier =
            modifier
                .width(contentWidth + SeekerTheme.spacing.xxl * 2)
                .clip(RoundedCornerShape(SeekerTheme.radii.sheet))
                .background(SeekerTheme.colors.surface2)
                .padding(SeekerTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(SeekerTheme.sizes.iconButton.medium.box)
                        .background(SeekerTheme.colors.orangeContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
                    tint = SeekerTheme.colors.onOrangeContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = wallet.title(), style = SeekerTheme.typography.amount)
                Text(
                    text = "Another app",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(text = kind.title(), style = MaterialTheme.typography.headlineMedium)
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                    .background(SeekerTheme.colors.surface0)
                    .padding(SeekerTheme.spacing.xl)
        ) {
            Text(
                text = summary,
                style =
                    SeekerTheme.typography.identifier.copy(
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                    ),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
            SeekerButton(
                label = "Sign and send",
                onClick = onApprove,
                variant = SeekerButtonVariant.Filled,
                size = SeekerButtonSize.Lg,
                modifier = Modifier.fillMaxWidth(),
            )
            SeekerButton(
                label = "Decline",
                onClick = onDecline,
                variant = SeekerButtonVariant.Neutral,
                size = SeekerButtonSize.Lg,
                modifier = Modifier.fillMaxWidth(),
            )
            SeekerButton(
                label = "Leave without answering",
                onClick = onLeaveWithoutAnswering,
                variant = SeekerButtonVariant.Tonal,
                size = SeekerButtonSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun WalletHandoff(
    summary: String,
    wallet: WalletHandoffWallet,
    kind: WalletHandoffKind,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onLeaveWithoutAnswering: () -> Unit,
    modifier: Modifier = Modifier,
) =
    WalletHandoffCard(
        summary = summary,
        wallet = wallet,
        kind = kind,
        onApprove = onApprove,
        onDecline = onDecline,
        onLeaveWithoutAnswering = onLeaveWithoutAnswering,
        modifier = modifier,
    )

private fun WalletHandoffWallet.title(): String =
    when (this) {
        WalletHandoffWallet.SeedVault -> "Seed Vault Wallet"
    }

private fun WalletHandoffKind.title(): String =
    when (this) {
        WalletHandoffKind.Transfer -> "Approve a transaction"
    }

private const val WalletHandoffContentWidthUnits = 12
private const val WalletHandoffPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@DesignRef(component = "wallet-handoff", variant = "wallet=seed-vault kind=transfer")
@Preview(
    name = "wallet-handoff/wallet-seed-vault-kind-transfer",
    widthDp = 398,
    uiMode = WalletHandoffPreviewDarkMode,
)
@Composable
internal fun WalletHandoffTransferPreview() {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            WalletHandoffCard(
                summary = "Send 5 SOL to FyfWsSPW…YSpEA on Solana devnet",
                wallet = WalletHandoffWallet.SeedVault,
                kind = WalletHandoffKind.Transfer,
                onApprove = {},
                onDecline = {},
                onLeaveWithoutAnswering = {},
            )
        }
    }
}
