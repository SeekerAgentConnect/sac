package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class RequestTileKind {
    Acknowledgement,
    PredictionSignal,
    SwapSignal,
    SignatureRequest,
    Transfer,
}

enum class RequestTileRailState {
    InRail,
    Centred,
}

data class RequestTileModel(
    /** The request's question, or its amount and unit ("5 SOL", "74 bytes"); shown whole. */
    val title: String,
    /** The connection it came from: the footer chip, the only place the tile names it. */
    val sourceName: String,
    /** When it arrived, already formatted ("9:37 PM"). */
    val time: String,
    val warningCount: Int,
    /** The connection's stored marker. Null keeps the source-name hash used by captured tiles. */
    val sourceColour: SourceColour? = null,
    /**
     * Nothing was checked: there is no transaction yet for the rules to read (SEE-180), or no rules
     * apply at all (SEE-181). With no warnings to count, the tile shows no verdict rather than "In
     * rules" — not checked is neither a match nor a warning.
     */
    val unchecked: Boolean = false,
)

/** The three title sizes (SEE-183): the shorter the title, the larger it is drawn. */
enum class RequestTileTitleSize {
    Large,
    Medium,
    Small;

    companion object {
        fun of(title: String): RequestTileTitleSize =
            when {
                title.length <= RequestTileTitleLargeMaxLength -> Large
                title.length <= RequestTileTitleMediumMaxLength -> Medium
                else -> Small
            }
    }
}

/** What the header's badge says, or null when nothing was checked. */
enum class RequestTileStatus {
    Ok,
    Warning;

    companion object {
        fun of(model: RequestTileModel): RequestTileStatus? =
            when {
                model.warningCount > 0 -> Warning
                model.unchecked -> null
                else -> Ok
            }
    }
}

/** The badge's spoken label: "In rules", "1 warning" or "N warnings". */
fun RequestTileModel.statusLabel(): String? =
    when (RequestTileStatus.of(this)) {
        RequestTileStatus.Ok -> "In rules"
        RequestTileStatus.Warning ->
            if (warningCount == 1) "1 warning" else "$warningCount warnings"
        null -> null
    }

@Composable
fun RequestTile(
    model: RequestTileModel,
    kind: RequestTileKind,
    railState: RequestTileRailState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileColors =
        when (railState) {
            RequestTileRailState.Centred ->
                RequestTileColors(
                    container = SeekerTheme.colors.limeContainer,
                    content = SeekerTheme.colors.onLimeContainer,
                    time = SeekerTheme.colors.onLimeContainer,
                )
            RequestTileRailState.InRail ->
                RequestTileColors(
                    container = SeekerTheme.colors.surface1,
                    content = MaterialTheme.colorScheme.onSurface,
                    time = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }

    Column(
        modifier =
            modifier
                .size(
                    width =
                        SeekerTheme.spacing.huge * RequestTileSizeHugeUnits +
                            SeekerTheme.spacing.xl +
                            SeekerTheme.spacing.xxs,
                    height =
                        SeekerTheme.spacing.huge * RequestTileSizeHugeUnits +
                            SeekerTheme.spacing.xs,
                )
                .clip(RoundedCornerShape(SeekerTheme.radii.xl))
                .background(tileColors.container)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(SeekerTheme.spacing.xl)
    ) {
        RequestTileHeader(model = model, kind = kind, contentColor = tileColors.content)
        Spacer(Modifier.height(SeekerTheme.spacing.mdPlus))
        // Space between header, title and footer, never less than the gap: the title sits centred
        // in what the header and footer leave. Both rows are measured first, so a title that ever
        // outgrew the box would be the one to give way, never the time.
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = model.title,
                color = tileColors.content,
                style = RequestTileTitleSize.of(model.title).style(),
            )
        }
        Spacer(Modifier.height(SeekerTheme.spacing.mdPlus))
        RequestTileFooter(model = model, timeColor = tileColors.time)
    }
}

@Composable
private fun RequestTileHeader(model: RequestTileModel, kind: RequestTileKind, contentColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = kind.icon(),
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxl),
            tint = contentColor,
        )
        Text(
            text = kind.label(),
            modifier = Modifier.weight(1f),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            style = MaterialTheme.typography.bodyMedium,
        )
        RequestTileStatus.of(model)?.let { status ->
            RequestTileStatusBadge(
                status = status,
                warningCount = model.warningCount,
                label = checkNotNull(model.statusLabel()),
            )
        }
    }
}

/** A green check, or an orange `!` with the count beside it from two warnings up. */
@Composable
private fun RequestTileStatusBadge(status: RequestTileStatus, warningCount: Int, label: String) {
    val colors = SeekerTheme.colors
    val (container, content) =
        when (status) {
            RequestTileStatus.Ok -> colors.statusOk to colors.onStatusOk
            RequestTileStatus.Warning -> colors.orangeContainer to colors.onOrangeContainer
        }
    val showCount = status == RequestTileStatus.Warning && warningCount >= 2
    Row(
        modifier =
            Modifier.clearAndSetSemantics { contentDescription = label }
                .height(SeekerTheme.spacing.huge)
                .defaultMinSize(minWidth = SeekerTheme.spacing.huge)
                .background(container, CircleShape)
                .padding(
                    if (showCount) {
                        PaddingValues(start = SeekerTheme.spacing.xs, end = SeekerTheme.spacing.md)
                    } else {
                        PaddingValues()
                    }
                ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                when (status) {
                    RequestTileStatus.Ok -> Icons.Outlined.Check
                    RequestTileStatus.Warning -> Icons.Outlined.PriorityHigh
                },
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xl + SeekerTheme.spacing.xxs),
            tint = content,
        )
        if (showCount) {
            Text(
                text = warningCount.toString(),
                color = content,
                maxLines = 1,
                softWrap = false,
                style = SeekerTheme.typography.badgeCount,
            )
        }
    }
}

@Composable
private fun RequestTileFooter(model: RequestTileModel, timeColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The time is measured first and kept whole; the chip ellipsizes in what is left.
        Box(modifier = Modifier.weight(1f)) {
            SourceChip(
                sourceName = model.sourceName,
                colour = model.sourceColour,
                size = SourceChipSize.Small,
            )
        }
        Text(
            text = model.time,
            color = timeColor,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
        )
    }
}

private data class RequestTileColors(
    val container: Color,
    val content: Color,
    val time: Color,
)

@Composable
private fun RequestTileTitleSize.style(): TextStyle =
    when (this) {
        RequestTileTitleSize.Large -> MaterialTheme.typography.titleLarge
        RequestTileTitleSize.Medium -> SeekerTheme.typography.tileTitle
        RequestTileTitleSize.Small ->
            SeekerTheme.typography.buttonLarge.copy(fontWeight = FontWeight.Normal)
    }.copy(lineBreak = LineBreak.Paragraph)

private fun RequestTileKind.icon(): ImageVector =
    when (this) {
        RequestTileKind.Acknowledgement -> Icons.Outlined.DoneAll
        RequestTileKind.PredictionSignal -> Icons.AutoMirrored.Outlined.TrendingUp
        RequestTileKind.SwapSignal -> Icons.Outlined.SwapHoriz
        RequestTileKind.SignatureRequest -> Icons.Outlined.Draw
        RequestTileKind.Transfer -> Icons.Outlined.NorthEast
    }

private fun RequestTileKind.label(): String =
    when (this) {
        RequestTileKind.Acknowledgement -> "Acknowledge"
        RequestTileKind.PredictionSignal -> "Prediction"
        RequestTileKind.SwapSignal -> "Swap"
        RequestTileKind.SignatureRequest -> "Signature"
        RequestTileKind.Transfer -> "Transfer"
    }

/** 214×200: seven `huge` steps plus `xl` + `xxs` across, plus `xs` down. */
private const val RequestTileSizeHugeUnits = 7
private const val RequestTileTitleLargeMaxLength = 34
private const val RequestTileTitleMediumMaxLength = 60
