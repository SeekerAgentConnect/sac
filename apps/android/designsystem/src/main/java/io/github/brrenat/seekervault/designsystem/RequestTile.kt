package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Toll
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
    val title: String,
    val sourceName: String,
    val supportingText: String,
    val warningCount: Int,
    val signatureByteCount: Int? = null,
    val assetSymbol: String? = null,
    /** Optional source-owned footer copy; kinds use their safe default when absent. */
    val footerText: String? = null,
    /** The connection's stored marker. Null keeps the source-name hash used by captured tiles. */
    val sourceColour: SourceColour? = null,
    /**
     * Nothing was checked: there is no transaction yet for the rules to read (SEE-180), or no rules
     * apply at all (SEE-181). With no warnings to count, the tile shows no verdict rather than "In
     * rules" — not checked is neither a match nor a warning.
     */
    val unchecked: Boolean = false,
)

@Composable
fun RequestTile(
    model: RequestTileModel,
    kind: RequestTileKind,
    railState: RequestTileRailState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val centred = railState == RequestTileRailState.Centred
    val tileWidth =
        SeekerTheme.spacing.huge * RequestTileWidthUnits +
            SeekerTheme.spacing.xxl +
            SeekerTheme.spacing.xxs
    val tileHeight = SeekerTheme.spacing.huge * RequestTileHeightUnits + SeekerTheme.spacing.md
    val tileColors =
        if (centred) {
            RequestTileColors(
                container = SeekerTheme.colors.limeContainer,
                content = SeekerTheme.colors.onLimeContainer,
                secondary = SeekerTheme.colors.onLimeContainer,
            )
        } else {
            RequestTileColors(
                container = SeekerTheme.colors.surface1,
                content = MaterialTheme.colorScheme.onSurface,
                secondary = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

    Column(
        modifier =
            modifier
                .size(width = tileWidth, height = tileHeight)
                .clip(RoundedCornerShape(SeekerTheme.radii.xl))
                .background(tileColors.container)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        RequestTileHeader(
            model = model,
            kind = kind,
            centred = centred,
            contentColor = tileColors.content,
        )
        RequestTileHeadline(
            model = model,
            kind = kind,
            centred = centred,
            contentColor = tileColors.content,
            secondaryColor = tileColors.secondary,
        )
        RequestTileFooter(
            kind = kind,
            text = model.footerText ?: kind.effect(),
            warningCount = model.warningCount,
            unchecked = model.unchecked,
            centred = centred,
            secondaryColor = tileColors.secondary,
        )
    }
}

@Composable
private fun RequestTileHeader(
    model: RequestTileModel,
    kind: RequestTileKind,
    centred: Boolean,
    contentColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = kind.icon(),
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
                tint = contentColor,
            )
            Text(
                text = kind.label(),
                modifier = Modifier.weight(1f),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = SeekerTheme.typography.buttonLarge.copy(fontWeight = null),
            )
            if (kind.isSignal()) {
                SignalLabel(
                    context =
                        if (centred) SignalLabelContext.OnTile else SignalLabelContext.Standard
                )
            }
        }
        SourceChip(
            sourceName = model.sourceName,
            colour = model.sourceColour,
            size = if (kind.isSignal()) SourceChipSize.Compact else SourceChipSize.Standard,
            modifier = Modifier.widthIn(max = SeekerTheme.spacing.huge * RequestTileSourceMaxUnits),
        )
    }
}

@Composable
private fun RequestTileHeadline(
    model: RequestTileModel,
    kind: RequestTileKind,
    centred: Boolean,
    contentColor: Color,
    secondaryColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (kind == RequestTileKind.Transfer) {
                val coinContainer =
                    if (centred) SeekerTheme.colors.onLimeContainer
                    else SeekerTheme.colors.limeContainer
                val coinContent =
                    if (centred) SeekerTheme.colors.limeContainer
                    else SeekerTheme.colors.onLimeContainer
                Box(
                    modifier =
                        Modifier.size(SeekerTheme.spacing.huge)
                            .background(coinContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Toll,
                        contentDescription = null,
                        modifier = Modifier.size(SeekerTheme.spacing.xl + SeekerTheme.spacing.xxs),
                        tint = coinContent,
                    )
                }
            }
            Text(
                text = model.headline(kind),
                color = contentColor,
                maxLines = 1,
                // A signal's title is words, and words end in an ellipsis rather than being cut
                // through a glyph. Amounts and byte counts stay clipped: they are sized to fit.
                overflow = if (kind.isSignal()) TextOverflow.Ellipsis else TextOverflow.Clip,
                softWrap = false,
                style = kind.headlineStyle(),
            )
        }
        Text(
            text = model.supportingText,
            color = secondaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style =
                if (kind == RequestTileKind.SignatureRequest || kind == RequestTileKind.Transfer) {
                    SeekerTheme.typography.identifier.copy(
                        fontSize = MaterialTheme.typography.labelMedium.fontSize,
                        lineHeight = MaterialTheme.typography.labelMedium.lineHeight,
                    )
                } else {
                    MaterialTheme.typography.labelMedium.copy(fontWeight = null)
                },
        )
    }
}

@Composable
private fun RequestTileFooter(
    kind: RequestTileKind,
    text: String,
    warningCount: Int,
    unchecked: Boolean,
    centred: Boolean,
    secondaryColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = secondaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = null),
        )
        val verdict =
            when {
                warningCount > 0 -> VerdictPillVerdict.Warning
                unchecked -> null
                else -> VerdictPillVerdict.Ok
            }
        if (verdict != null) {
            VerdictPill(
                verdict = verdict,
                warningCount = warningCount.takeIf { it > 0 },
                context = if (centred) VerdictPillContext.OnTile else VerdictPillContext.Standard,
            )
        }
    }
}

private data class RequestTileColors(
    val container: Color,
    val content: Color,
    val secondary: Color,
)

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

private fun RequestTileKind.effect(): String =
    when (this) {
        RequestTileKind.Acknowledgement -> "Nothing is signed"
        RequestTileKind.PredictionSignal,
        RequestTileKind.SwapSignal -> "Nothing moves"
        RequestTileKind.SignatureRequest -> "No funds move"
        RequestTileKind.Transfer -> "Funds move"
    }

private fun RequestTileKind.isSignal(): Boolean =
    this == RequestTileKind.PredictionSignal || this == RequestTileKind.SwapSignal

@Composable
private fun RequestTileKind.headlineStyle() =
    when (this) {
        RequestTileKind.Acknowledgement -> MaterialTheme.typography.headlineMedium
        RequestTileKind.PredictionSignal,
        RequestTileKind.SwapSignal ->
            SeekerTheme.typography.screenTitle.copy(fontWeight = FontWeight.Normal)
        RequestTileKind.SignatureRequest,
        RequestTileKind.Transfer -> MaterialTheme.typography.headlineLarge
    }.let { style -> style.copy(lineHeight = style.fontSize * RequestTileHeadlineLineHeight) }

@Composable
private fun RequestTileModel.headline(kind: RequestTileKind): AnnotatedString {
    val unit =
        when (kind) {
            RequestTileKind.SignatureRequest -> "bytes"
            RequestTileKind.Transfer -> assetSymbol
            else -> null
        }
    val value =
        when (kind) {
            RequestTileKind.SignatureRequest -> signatureByteCount?.toString() ?: title
            else -> title
        }
    return buildAnnotatedString {
        append(value)
        if (!unit.isNullOrEmpty()) {
            append(" ")
            withStyle(SpanStyle(fontSize = SeekerTheme.typography.amount.fontSize)) { append(unit) }
        }
    }
}

private const val RequestTileWidthUnits = 8
private const val RequestTileHeightUnits = 8
private const val RequestTileSourceMaxUnits = 7
private const val RequestTileHeadlineLineHeight = 1.05f
