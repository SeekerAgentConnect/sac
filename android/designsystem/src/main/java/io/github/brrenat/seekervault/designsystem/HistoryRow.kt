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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class HistoryRowState {
    Sent,
    Simulated,
    Dismissed,
    Cancelled,
    Expired,
    Unknown,
}

/**
 * The status icon beside the outcome text (SEE-161). Every status has its own glyph, so the line
 * never relies on colour alone.
 */
enum class HistoryRowStatus {
    Confirmed,
    Pending,
    Failed,
    Signed,
    Simulated,
    Declined,
    Dismissed,
    Expired,
    Cancelled,
    Unknown,
}

data class HistoryRowModel(
    val title: String,
    val sourceName: String,
    val outcomeText: String,
    val timestampText: String,
    val isSignal: Boolean,
)

@Composable
fun HistoryRow(
    model: HistoryRowModel,
    state: HistoryRowState,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** The operation's own icon; null keeps the reference's icon for [state]. */
    kind: InboxRowKind? = null,
    /** An icon ahead of the outcome text; null draws the text alone, as the reference does. */
    status: HistoryRowStatus? = null,
) {
    val rowModifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SeekerTheme.radii.lg))
            .background(SeekerTheme.colors.surface1)
            .let { base ->
                if (onClick == null) base else base.clickable(role = Role.Button, onClick = onClick)
            }
            .padding(
                horizontal = SeekerTheme.spacing.xl,
                vertical = SeekerTheme.spacing.lgPlus,
            )

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = kind?.historyIcon() ?: state.historyIcon(),
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (model.isSignal) SignalLabel()
            }
            Row(
                modifier = Modifier.padding(vertical = SeekerTheme.spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceChip(sourceName = model.sourceName, size = SourceChipSize.Compact)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                if (status != null) {
                    Icon(
                        imageVector = status.icon(),
                        contentDescription = null,
                        modifier =
                            Modifier.padding(top = SeekerTheme.spacing.xxs / 2)
                                .size(SeekerTheme.spacing.xl),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${model.outcomeText} · ${model.timestampText}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // A tappable row says so: the whole row is the hit target, and the chevron is its sign.
        if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.xxxl),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun InboxRowKind.historyIcon(): ImageVector =
    when (this) {
        InboxRowKind.Acknowledgement -> Icons.Outlined.DoneAll
        InboxRowKind.Prediction -> Icons.AutoMirrored.Outlined.TrendingUp
        InboxRowKind.Transfer -> Icons.Outlined.NorthEast
        InboxRowKind.Swap -> Icons.Outlined.SwapHoriz
        InboxRowKind.Signature -> Icons.Outlined.Draw
    }

private fun HistoryRowStatus.icon(): ImageVector =
    when (this) {
        HistoryRowStatus.Confirmed -> Icons.Outlined.CheckCircle
        HistoryRowStatus.Pending -> Icons.Outlined.HourglassTop
        HistoryRowStatus.Failed -> Icons.Outlined.ErrorOutline
        HistoryRowStatus.Signed -> Icons.Outlined.Draw
        HistoryRowStatus.Simulated -> Icons.Outlined.Science
        HistoryRowStatus.Declined -> Icons.Outlined.Cancel
        HistoryRowStatus.Dismissed -> Icons.Outlined.DoNotDisturbOn
        HistoryRowStatus.Expired -> Icons.Outlined.TimerOff
        HistoryRowStatus.Cancelled -> Icons.Outlined.Block
        HistoryRowStatus.Unknown -> Icons.AutoMirrored.Outlined.HelpOutline
    }

private fun HistoryRowState.historyIcon(): ImageVector =
    when (this) {
        HistoryRowState.Sent,
        HistoryRowState.Cancelled -> Icons.Outlined.NorthEast
        HistoryRowState.Simulated -> Icons.Outlined.SwapHoriz
        HistoryRowState.Dismissed -> Icons.AutoMirrored.Outlined.TrendingUp
        HistoryRowState.Expired -> Icons.Outlined.DoneAll
        HistoryRowState.Unknown -> Icons.Outlined.Draw
    }

private const val HistoryRowPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun HistoryRowPreview(model: HistoryRowModel, state: HistoryRowState) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) { HistoryRow(model = model, state = state) }
    }
}

@DesignRef(component = "history-row", variant = "state=cancelled")
@Preview(
    name = "history-row/state-cancelled",
    widthDp = 358,
    uiMode = HistoryRowPreviewDarkMode,
)
@Composable
internal fun HistoryRowCancelledPreview() =
    HistoryRowPreview(
        HistoryRowModel(
            title = "Send 2 SOL",
            sourceName = "studio-mac",
            outcomeText = "Cancelled by the sender",
            timestampText = "7:48 PM",
            isSignal = false,
        ),
        HistoryRowState.Cancelled,
    )

@DesignRef(component = "history-row", variant = "state=dismissed")
@Preview(
    name = "history-row/state-dismissed",
    widthDp = 358,
    uiMode = HistoryRowPreviewDarkMode,
)
@Composable
internal fun HistoryRowDismissedPreview() =
    HistoryRowPreview(
        HistoryRowModel(
            title = "Ethereum above \$4,000",
            sourceName = "Jupiter Prediction demo",
            outcomeText = "Dismissed on this phone",
            timestampText = "8:02 PM",
            isSignal = true,
        ),
        HistoryRowState.Dismissed,
    )

@DesignRef(component = "history-row", variant = "state=expired")
@Preview(name = "history-row/state-expired", widthDp = 358, uiMode = HistoryRowPreviewDarkMode)
@Composable
internal fun HistoryRowExpiredPreview() =
    HistoryRowPreview(
        HistoryRowModel(
            title = "Acknowledge a message",
            sourceName = "studio-mac",
            outcomeText = "Expired before you answered",
            timestampText = "7:45 PM",
            isSignal = false,
        ),
        HistoryRowState.Expired,
    )

@DesignRef(component = "history-row", variant = "state=sent")
@Preview(name = "history-row/state-sent", widthDp = 358, uiMode = HistoryRowPreviewDarkMode)
@Composable
internal fun HistoryRowSentPreview() =
    HistoryRowPreview(
        HistoryRowModel(
            title = "Send 5 SOL",
            sourceName = "studio-mac",
            outcomeText = "Sent to the network",
            timestampText = "9:36 PM",
            isSignal = false,
        ),
        HistoryRowState.Sent,
    )

@DesignRef(component = "history-row", variant = "state=simulated")
@Preview(
    name = "history-row/state-simulated",
    widthDp = 358,
    uiMode = HistoryRowPreviewDarkMode,
)
@Composable
internal fun HistoryRowSimulatedPreview() =
    HistoryRowPreview(
        HistoryRowModel(
            title = "Swap SOL for USDC",
            sourceName = "CopyTrading demo",
            outcomeText = "Simulated on this phone · no funds moved",
            timestampText = "8:11 PM",
            isSignal = true,
        ),
        HistoryRowState.Simulated,
    )

@DesignRef(component = "history-row", variant = "state=unknown")
@Preview(name = "history-row/state-unknown", widthDp = 358, uiMode = HistoryRowPreviewDarkMode)
@Composable
internal fun HistoryRowUnknownPreview() =
    HistoryRowPreview(
        HistoryRowModel(
            title = "Sign a message",
            sourceName = "hermes-box",
            outcomeText = "Unknown: no answer from the wallet",
            timestampText = "7:43 PM",
            isSignal = false,
        ),
        HistoryRowState.Unknown,
    )

@DesignRef(component = "history-row", variant = "tappable=confirmed")
@Preview(
    name = "history-row/tappable-confirmed",
    widthDp = 358,
    uiMode = HistoryRowPreviewDarkMode,
)
@Composable
internal fun HistoryRowTappableConfirmedPreview() =
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            HistoryRow(
                model =
                    HistoryRowModel(
                        title = "Place 25 USDC on Yes: Will SOL close above \$250?",
                        sourceName = "Prediction Signals",
                        outcomeText = "Approved · confirmed",
                        timestampText = "9:01 PM",
                        isSignal = true,
                    ),
                state = HistoryRowState.Sent,
                onClick = {},
                kind = InboxRowKind.Prediction,
                status = HistoryRowStatus.Confirmed,
            )
        }
    }

@DesignRef(component = "history-row", variant = "tappable=pending")
@Preview(name = "history-row/tappable-pending", widthDp = 358, uiMode = HistoryRowPreviewDarkMode)
@Composable
internal fun HistoryRowTappablePendingPreview() =
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            HistoryRow(
                model =
                    HistoryRowModel(
                        title = "Swap 25 USDC for SOL",
                        sourceName = "Trader Signals",
                        outcomeText = "Approved · waiting for the network",
                        timestampText = "9:14 PM",
                        isSignal = true,
                    ),
                state = HistoryRowState.Sent,
                onClick = {},
                kind = InboxRowKind.Swap,
                status = HistoryRowStatus.Pending,
            )
        }
    }
