package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class WalletBannerVariant {
    Compact,
    Expanded,
}

@Composable
fun WalletBanner(
    walletName: String,
    address: String,
    statusText: String?,
    variant: WalletBannerVariant,
    onCopyAddress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shortenAddress: Boolean = true,
) {
    val horizontalPadding =
        if (variant == WalletBannerVariant.Compact) {
            SeekerTheme.spacing.xl
        } else {
            SeekerTheme.spacing.xxl
        }
    val shape =
        RoundedCornerShape(
            if (variant == WalletBannerVariant.Compact) {
                SeekerTheme.radii.lg
            } else {
                SeekerTheme.radii.sheet
            }
        )
    val bannerModifier =
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SeekerTheme.colors.limeContainer)
            .let { base ->
                if (onClick == null) base else base.clickable(role = Role.Button, onClick = onClick)
            }
            .padding(horizontal = horizontalPadding)

    when (variant) {
        WalletBannerVariant.Compact ->
            WalletBannerCompact(
                walletName = walletName,
                address = address,
                onCopyAddress = onCopyAddress,
                shortenAddress = shortenAddress,
                modifier = bannerModifier.padding(vertical = SeekerTheme.spacing.xl),
            )
        WalletBannerVariant.Expanded ->
            WalletBannerExpanded(
                walletName = walletName,
                address = address,
                statusText = statusText,
                onCopyAddress = onCopyAddress,
                modifier = bannerModifier.padding(vertical = SeekerTheme.spacing.xxl),
            )
    }
}

@Composable
private fun WalletBannerCompact(
    walletName: String,
    address: String,
    onCopyAddress: (() -> Unit)?,
    shortenAddress: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.AccountBalanceWallet,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxxl),
            tint = SeekerTheme.colors.onLimeContainer,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            Text(
                text = walletName,
                color = SeekerTheme.colors.onLimeContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                text = if (shortenAddress) address.shortWalletAddress() else address,
                color = SeekerTheme.colors.onLimeContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = SeekerTheme.typography.identifier,
            )
        }
        onCopyAddress?.let { copy ->
            OrganismIconAction(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = "Copy wallet address",
                onClick = copy,
                size = OrganismIconActionSize.MediumLargeGlyph,
                style = OrganismIconActionStyle.OnLime,
            )
        }
    }
}

@Composable
private fun WalletBannerExpanded(
    walletName: String,
    address: String,
    statusText: String?,
    onCopyAddress: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Wallet,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.huge),
                tint = SeekerTheme.colors.onLimeContainer,
            )
            Text(
                text = walletName,
                modifier = Modifier.weight(1f),
                color = SeekerTheme.colors.onLimeContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineSmall,
            )
            onCopyAddress?.let { copy ->
                OrganismIconAction(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy wallet address",
                    onClick = copy,
                    size = OrganismIconActionSize.MediumLargeGlyph,
                    style = OrganismIconActionStyle.OnLime,
                )
            }
        }
        Text(
            text = address,
            color = SeekerTheme.colors.onLimeContainer,
            style = SeekerTheme.typography.identifier,
        )
        statusText?.let { status ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(SeekerTheme.spacing.xl + SeekerTheme.spacing.xxs),
                    tint = SeekerTheme.colors.onLimeContainer,
                )
                Text(
                    text = status,
                    color = SeekerTheme.colors.onLimeContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun String.shortWalletAddress(): String =
    if (
        length <=
            WalletBannerVisibleAddressPrefixCharacters + WalletBannerVisibleAddressSuffixCharacters
    ) {
        this
    } else {
        take(WalletBannerVisibleAddressPrefixCharacters) +
            "…" +
            takeLast(WalletBannerVisibleAddressSuffixCharacters)
    }

private const val WalletBannerVisibleAddressPrefixCharacters = 8
private const val WalletBannerVisibleAddressSuffixCharacters = 7
private const val WalletBannerPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES
private const val WalletBannerPreviewAddress = "Bzy2LsonMmTZmLpKX3dAZ4NLaEqTQs772CzUm2B16K54"

@Composable
private fun WalletBannerPreview(variant: WalletBannerVariant) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            WalletBanner(
                walletName = "renatnomad.skr",
                address = WalletBannerPreviewAddress,
                statusText = "Connected 8:34 PM · Solana devnet",
                variant = variant,
                onCopyAddress = {},
            )
        }
    }
}

@DesignRef(component = "wallet-banner", variant = "variant=compact")
@Preview(
    name = "wallet-banner/variant-compact",
    widthDp = 390,
    uiMode = WalletBannerPreviewDarkMode,
)
@Composable
internal fun WalletBannerCompactPreview() = WalletBannerPreview(WalletBannerVariant.Compact)

@DesignRef(component = "wallet-banner", variant = "variant=expanded")
@Preview(
    name = "wallet-banner/variant-expanded",
    widthDp = 398,
    uiMode = WalletBannerPreviewDarkMode,
)
@Composable
internal fun WalletBannerExpandedPreview() = WalletBannerPreview(WalletBannerVariant.Expanded)
