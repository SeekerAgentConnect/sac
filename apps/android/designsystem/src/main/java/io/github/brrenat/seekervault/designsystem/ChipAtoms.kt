package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class VerdictPillVerdict {
    Ok,
    Warning,
    /** Nothing failed, but there were no rules to check against: the review says so too. */
    OutsideRules,
}

enum class VerdictPillContext {
    Standard,
    OnTile,
}

@Composable
fun VerdictPill(
    verdict: VerdictPillVerdict,
    warningCount: Int? = null,
    context: VerdictPillContext = VerdictPillContext.Standard,
    modifier: Modifier = Modifier,
) {
    val colors = SeekerTheme.colors
    val (containerColor, contentColor) =
        when (verdict) {
            VerdictPillVerdict.Ok ->
                if (context == VerdictPillContext.OnTile) {
                    colors.onLimeContainer to colors.limeContainer
                } else {
                    colors.limeContainer to colors.onLimeContainer
                }
            VerdictPillVerdict.Warning,
            VerdictPillVerdict.OutsideRules -> colors.orangeContainer to colors.onOrangeContainer
        }
    val text =
        when (verdict) {
            VerdictPillVerdict.Ok -> "In rules"
            VerdictPillVerdict.Warning -> {
                val count = warningCount ?: 1
                "$count ${if (count == 1) "warning" else "warnings"}"
            }
            VerdictPillVerdict.OutsideRules -> "Outside rules"
        }

    Box(
        modifier =
            modifier
                .height(SeekerTheme.spacing.xxxl)
                .background(containerColor, MaterialTheme.shapes.small)
                .padding(horizontal = SeekerTheme.spacing.mdPlus),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

enum class SignalLabelContext {
    Standard,
    OnTile,
}

@Composable
fun SignalLabel(
    context: SignalLabelContext = SignalLabelContext.Standard,
    modifier: Modifier = Modifier,
) {
    val onTile = context == SignalLabelContext.OnTile
    val height =
        if (onTile) {
            SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs
        } else {
            SeekerTheme.spacing.xxxl
        }
    val horizontalPadding =
        if (onTile) {
            SeekerTheme.spacing.md
        } else {
            (SeekerTheme.spacing.md + SeekerTheme.spacing.mdPlus) / 2
        }
    val containerColor =
        if (onTile) SeekerTheme.colors.onLimeContainer else SeekerTheme.colors.surface3
    val contentColor =
        if (onTile) SeekerTheme.colors.limeContainer else MaterialTheme.colorScheme.onSurface

    Box(
        modifier =
            modifier
                .height(height)
                .background(containerColor, MaterialTheme.shapes.small)
                .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Signal",
            color = contentColor,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

enum class SourceChipSize {
    Standard,
    Compact,
}

enum class SourceChipWidth {
    Natural,
    Truncated,
}

@Composable
fun SourceChip(
    sourceName: String,
    colour: SourceColour? = null,
    size: SourceChipSize = SourceChipSize.Standard,
    width: SourceChipWidth = SourceChipWidth.Natural,
    modifier: Modifier = Modifier,
) {
    val colors =
        if (colour == null) sourcePaletteColors(sourceName) else sourcePaletteColors(colour)
    val textStyle =
        when (size) {
            SourceChipSize.Standard -> MaterialTheme.typography.labelLarge
            SourceChipSize.Compact ->
                MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
        }
    val containerModifier =
        when (width) {
            SourceChipWidth.Natural -> modifier
            SourceChipWidth.Truncated ->
                modifier.width(
                    SeekerTheme.spacing.xxxl * SourceChipTruncatedContentWidthUnits +
                        SeekerTheme.spacing.lg * 2
                )
        }
    val textModifier =
        when (width) {
            SourceChipWidth.Natural -> Modifier.padding(horizontal = SeekerTheme.spacing.lg)
            SourceChipWidth.Truncated ->
                Modifier.fillMaxWidth().padding(start = SeekerTheme.spacing.lg)
        }

    Box(
        modifier =
            containerModifier
                .height(SeekerTheme.spacing.huge)
                .background(colors.container, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = sourceName,
            modifier = textModifier,
            color = colors.content,
            maxLines = 1,
            overflow =
                if (width == SourceChipWidth.Truncated) TextOverflow.Clip
                else TextOverflow.Ellipsis,
            softWrap = false,
            style = textStyle,
        )
    }
}

enum class EnvChipEnvironment {
    Production,
    Sandbox,
}

enum class EnvChipVerbosity {
    Full,
    Short,
}

@Composable
fun EnvChip(
    environment: EnvChipEnvironment,
    verbosity: EnvChipVerbosity = EnvChipVerbosity.Full,
    modifier: Modifier = Modifier,
) {
    val sandbox = environment == EnvChipEnvironment.Sandbox
    val text =
        when {
            !sandbox -> "Production"
            verbosity == EnvChipVerbosity.Short -> "Sandbox"
            else -> "Sandbox · no funds will move"
        }
    val containerColor =
        if (sandbox) SeekerTheme.colors.orangeContainer else SeekerTheme.colors.surface3
    val contentColor =
        if (sandbox) SeekerTheme.colors.onOrangeContainer else MaterialTheme.colorScheme.onSurface

    Box(
        modifier =
            modifier
                .height(SeekerTheme.spacing.xxxl)
                .background(containerColor, MaterialTheme.shapes.small)
                .padding(horizontal = (SeekerTheme.spacing.md + SeekerTheme.spacing.mdPlus) / 2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

enum class NetworkChipNetwork {
    Devnet,
    Mainnet,
    /** Hand-written (SEE-176): the neutral chip, as for the captured two. */
    Testnet,
}

@Composable
fun NetworkChip(
    network: NetworkChipNetwork,
    modifier: Modifier = Modifier,
    /**
     * Draws a test network in the sandbox orange rather than the neutral chip. The component
     * reference keeps every network neutral; the prediction review flags devnet (SEE-158).
     */
    flagged: Boolean = false,
) {
    val text =
        when (network) {
            NetworkChipNetwork.Devnet -> "Solana devnet"
            NetworkChipNetwork.Mainnet -> "Solana mainnet"
            NetworkChipNetwork.Testnet -> "Solana testnet"
        }

    val devnet = flagged && network == NetworkChipNetwork.Devnet
    Box(
        modifier =
            modifier
                .height(SeekerTheme.spacing.xxxl)
                .background(
                    if (devnet) SeekerTheme.colors.orangeContainer else SeekerTheme.colors.surface3,
                    MaterialTheme.shapes.small,
                )
                .padding(horizontal = (SeekerTheme.spacing.md + SeekerTheme.spacing.mdPlus) / 2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color =
                if (devnet) SeekerTheme.colors.onOrangeContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

enum class ScopeChipSource {
    Global,
    Connection,
    None,
    /** This phone's own reading of the transaction, which is not a rule (SEE-180). */
    Verification,
}

enum class ScopeChipContext {
    Standard,
    OnVerdict,
}

@Composable
fun ScopeChip(
    source: ScopeChipSource,
    label: String? = null,
    context: ScopeChipContext = ScopeChipContext.Standard,
    modifier: Modifier = Modifier,
) {
    val connection = source == ScopeChipSource.Connection
    val text =
        label
            ?: when (source) {
                ScopeChipSource.Global -> "Global"
                ScopeChipSource.Connection -> "Connection override"
                ScopeChipSource.None -> "Not configured"
                ScopeChipSource.Verification -> "Transaction check"
            }
    val containerColor =
        if (connection) SeekerTheme.colors.limeContainer else SeekerTheme.colors.surface3
    val contentColor =
        if (connection) SeekerTheme.colors.onLimeContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier =
            modifier
                .height(SeekerTheme.spacing.xxxl)
                .background(containerColor, MaterialTheme.shapes.small)
                .padding(horizontal = (SeekerTheme.spacing.md + SeekerTheme.spacing.mdPlus) / 2),
        horizontalArrangement =
            Arrangement.spacedBy((SeekerTheme.spacing.xs + SeekerTheme.spacing.sm) / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (context == ScopeChipContext.OnVerdict) {
            Icon(
                imageVector =
                    when (source) {
                        ScopeChipSource.None -> Icons.Outlined.Block
                        ScopeChipSource.Connection -> Icons.Outlined.Edit
                        ScopeChipSource.Global -> Icons.Outlined.Public
                        ScopeChipSource.Verification -> Icons.Outlined.GppMaybe
                    },
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.lg),
                tint = contentColor,
            )
        }
        Text(
            text = text,
            color = contentColor,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private const val SourceChipTruncatedContentWidthUnits = 5
