package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class InboxRowOrigin {
    Request,
    Signal,
}

enum class InboxRowKind {
    Acknowledgement,
    Prediction,
    Transfer,
    Swap,
    Signature,
}

enum class InboxRowVerdict {
    Ok,
    Warning,
}

enum class InboxRowTitleLines {
    One,
    Two,
}

data class InboxRowModel(
    val title: String,
    val supportingText: String,
    val sourceName: String,
    val timestampAndExpiryText: String,
    val environmentText: String,
    val networkText: String?,
    val warningCount: Int,
)

@Composable
fun InboxRow(
    model: InboxRowModel,
    kind: InboxRowKind,
    origin: InboxRowOrigin,
    verdict: InboxRowVerdict,
    titleLines: InboxRowTitleLines = InboxRowTitleLines.One,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = kind.inboxIcon(),
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
                tint = SeekerTheme.colors.primaryText,
            )
            Text(
                text = model.title,
                modifier = Modifier.weight(1f),
                maxLines = if (titleLines == InboxRowTitleLines.Two) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            VerdictPill(
                verdict =
                    if (verdict == InboxRowVerdict.Ok) {
                        VerdictPillVerdict.Ok
                    } else {
                        VerdictPillVerdict.Warning
                    },
                warningCount = model.warningCount.takeIf { verdict == InboxRowVerdict.Warning },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceChip(
                sourceName = model.sourceName,
                size =
                    if (origin == InboxRowOrigin.Signal) {
                        SourceChipSize.Compact
                    } else {
                        SourceChipSize.Standard
                    },
            )
            if (origin == InboxRowOrigin.Signal) SignalLabel()
        }
        Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs)) {
            Text(
                text = model.supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = model.timestampAndExpiryText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sandbox = model.environmentText.startsWith("Sandbox")
            EnvChip(
                environment =
                    if (sandbox) {
                        EnvChipEnvironment.Sandbox
                    } else {
                        EnvChipEnvironment.Production
                    },
                verbosity =
                    if (sandbox && model.environmentText == "Sandbox") {
                        EnvChipVerbosity.Short
                    } else {
                        EnvChipVerbosity.Full
                    },
            )
            model.networkText?.let { networkText ->
                NetworkChip(
                    network =
                        if (networkText == "Solana mainnet") {
                            NetworkChipNetwork.Mainnet
                        } else {
                            NetworkChipNetwork.Devnet
                        }
                )
            }
        }
        SeekerButton(
            label = "Review",
            onClick = onReview,
            variant = SeekerButtonVariant.Filled,
            size = SeekerButtonSize.Lg,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun InboxRowKind.inboxIcon(): ImageVector =
    when (this) {
        InboxRowKind.Acknowledgement -> Icons.Outlined.DoneAll
        InboxRowKind.Prediction -> Icons.AutoMirrored.Outlined.TrendingUp
        InboxRowKind.Transfer -> Icons.Outlined.NorthEast
        InboxRowKind.Swap -> Icons.Outlined.SwapHoriz
        InboxRowKind.Signature -> Icons.Outlined.Draw
    }

private const val InboxRowPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun InboxRowPreview(
    model: InboxRowModel,
    kind: InboxRowKind,
    origin: InboxRowOrigin,
    verdict: InboxRowVerdict,
    titleLines: InboxRowTitleLines = InboxRowTitleLines.One,
) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            InboxRow(
                model = model,
                kind = kind,
                origin = origin,
                verdict = verdict,
                titleLines = titleLines,
                onReview = {},
            )
        }
    }
}

private val inboxRequestOkModel =
    InboxRowModel(
        title = "Acknowledge a message",
        supportingText = "“Still here?” · nothing is signed",
        sourceName = "studio-mac",
        timestampAndExpiryText = "9:39 PM · expires in 6 hours",
        environmentText = "Production",
        networkText = null,
        warningCount = 0,
    )

@DesignRef(component = "inbox-row", variant = "network=none env=production")
@Preview(
    name = "inbox-row/network-none-env-production",
    widthDp = 358,
    uiMode = InboxRowPreviewDarkMode,
)
@Composable
internal fun InboxRowProductionNoNetworkPreview() =
    InboxRowPreview(
        inboxRequestOkModel,
        InboxRowKind.Acknowledgement,
        InboxRowOrigin.Request,
        InboxRowVerdict.Ok,
    )

@DesignRef(component = "inbox-row", variant = "origin=request verdict=ok")
@Preview(
    name = "inbox-row/origin-request-verdict-ok",
    widthDp = 358,
    uiMode = InboxRowPreviewDarkMode,
)
@Composable
internal fun InboxRowRequestOkPreview() =
    InboxRowPreview(
        inboxRequestOkModel,
        InboxRowKind.Acknowledgement,
        InboxRowOrigin.Request,
        InboxRowVerdict.Ok,
    )

@DesignRef(component = "inbox-row", variant = "origin=request verdict=warning")
@Preview(
    name = "inbox-row/origin-request-verdict-warning",
    widthDp = 358,
    uiMode = InboxRowPreviewDarkMode,
)
@Composable
internal fun InboxRowRequestWarningPreview() =
    InboxRowPreview(
        model =
            InboxRowModel(
                title = "Send 5 SOL",
                supportingText = "funds move",
                sourceName = "studio-mac",
                timestampAndExpiryText = "9:36 PM · expires in 23 hours",
                environmentText = "Production",
                networkText = "Solana devnet",
                warningCount = 1,
            ),
        kind = InboxRowKind.Transfer,
        origin = InboxRowOrigin.Request,
        verdict = InboxRowVerdict.Warning,
    )

@DesignRef(component = "inbox-row", variant = "origin=signal count=3 title=two-line")
@Preview(
    name = "inbox-row/origin-signal-count-3-title-two-line",
    widthDp = 358,
    uiMode = InboxRowPreviewDarkMode,
)
@Composable
internal fun InboxRowSignalThreeWarningsPreview() =
    InboxRowPreview(
        model =
            InboxRowModel(
                title = "Ethereum above \$4,000 at the October close",
                supportingText = "you pick the side and the stake",
                sourceName = "Jupiter Prediction demo",
                timestampAndExpiryText = "9:37 PM · expires in 2 hours",
                environmentText = "Sandbox · no funds will move",
                networkText = "Solana devnet",
                warningCount = 3,
            ),
        kind = InboxRowKind.Prediction,
        origin = InboxRowOrigin.Signal,
        verdict = InboxRowVerdict.Warning,
        titleLines = InboxRowTitleLines.Two,
    )

@DesignRef(component = "inbox-row", variant = "origin=signal verdict=warning")
@Preview(
    name = "inbox-row/origin-signal-verdict-warning",
    widthDp = 358,
    uiMode = InboxRowPreviewDarkMode,
)
@Composable
internal fun InboxRowSignalWarningPreview() =
    InboxRowPreview(
        model =
            InboxRowModel(
                title = "Swap SOL for USDC",
                supportingText = "you set the amount",
                sourceName = "CopyTrading demo",
                timestampAndExpiryText = "9:34 PM · expires in 2 hours",
                environmentText = "Sandbox · no funds will move",
                networkText = "Solana devnet",
                warningCount = 1,
            ),
        kind = InboxRowKind.Swap,
        origin = InboxRowOrigin.Signal,
        verdict = InboxRowVerdict.Warning,
    )
