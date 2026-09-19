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
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Toll
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

enum class RuleRowKind {
    Action,
    Asset,
    Program,
    Recipient,
}

enum class RuleRowState {
    Default,
    Checked,
    Unchecked,
    ReadOnly,
}

data class RuleRowModel(
    val title: String,
    val supportingText: String,
    val iconName: String,
)

@Composable
fun RuleRow(
    model: RuleRowModel,
    kind: RuleRowKind,
    state: RuleRowState = RuleRowState.Default,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    require(ruleVariantSupported(kind, state)) { "Unsupported RuleRow kind/state combination" }
    val rowModifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SeekerTheme.radii.md))
            .background(SeekerTheme.colors.surface3)
            .let { base ->
                if (onClick == null || state == RuleRowState.ReadOnly) {
                    base
                } else {
                    base.clickable(
                        role = if (kind == RuleRowKind.Action) Role.Checkbox else Role.Button,
                        onClick = onClick,
                    )
                }
            }
            .padding(SeekerTheme.spacing.lg)

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ruleIcon(model.iconName),
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxl),
            tint =
                if (state == RuleRowState.Unchecked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    SeekerTheme.colors.primaryText
                },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            Text(
                text = model.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = SeekerTheme.typography.buttonLarge.copy(fontWeight = null),
            )
            Text(
                text = model.supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state != RuleRowState.ReadOnly) {
            onDelete?.let { delete ->
                OrganismIconAction(
                    icon = Icons.Outlined.Delete,
                    contentDescription = "Delete ${model.title}",
                    onClick = delete,
                    size = OrganismIconActionSize.Inline,
                    style = OrganismIconActionStyle.Transparent,
                )
            }
        }
    }
}

private fun ruleVariantSupported(kind: RuleRowKind, state: RuleRowState): Boolean =
    when (kind) {
        RuleRowKind.Action -> state == RuleRowState.Checked || state == RuleRowState.Unchecked
        RuleRowKind.Asset -> state == RuleRowState.Default || state == RuleRowState.ReadOnly
        RuleRowKind.Program,
        RuleRowKind.Recipient -> state == RuleRowState.Default
    }

private fun ruleIcon(name: String): ImageVector =
    when (name) {
        "check_box" -> Icons.Outlined.CheckBox
        "check_box_outline_blank" -> Icons.Outlined.CheckBoxOutlineBlank
        "toll" -> Icons.Outlined.Toll
        "code" -> Icons.Outlined.Code
        "account_balance_wallet" -> Icons.Outlined.AccountBalanceWallet
        else -> Icons.AutoMirrored.Outlined.HelpOutline
    }

private const val RuleRowPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun RuleRowPreview(
    model: RuleRowModel,
    kind: RuleRowKind,
    state: RuleRowState = RuleRowState.Default,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            RuleRow(
                model = model,
                kind = kind,
                state = state,
                onClick = onClick,
                onDelete = onDelete,
            )
        }
    }
}

@DesignRef(component = "rule-row", variant = "kind=action state=checked")
@Preview(
    name = "rule-row/kind-action-state-checked",
    widthDp = 358,
    uiMode = RuleRowPreviewDarkMode,
)
@Composable
private fun RuleRowActionCheckedPreview() =
    RuleRowPreview(
        model = RuleRowModel("Transfer funds", "Expected", "check_box"),
        kind = RuleRowKind.Action,
        state = RuleRowState.Checked,
        onClick = {},
    )

@DesignRef(component = "rule-row", variant = "kind=action state=unchecked")
@Preview(
    name = "rule-row/kind-action-state-unchecked",
    widthDp = 358,
    uiMode = RuleRowPreviewDarkMode,
)
@Composable
private fun RuleRowActionUncheckedPreview() =
    RuleRowPreview(
        model = RuleRowModel("Acknowledge text", "Not expected", "check_box_outline_blank"),
        kind = RuleRowKind.Action,
        state = RuleRowState.Unchecked,
        onClick = {},
    )

@DesignRef(component = "rule-row", variant = "kind=asset")
@Preview(name = "rule-row/kind-asset", widthDp = 358, uiMode = RuleRowPreviewDarkMode)
@Composable
private fun RuleRowAssetPreview() =
    RuleRowPreview(
        model = RuleRowModel("Native SOL · devnet", "6 per request · 8 a day here", "toll"),
        kind = RuleRowKind.Asset,
        onClick = {},
        onDelete = {},
    )

@DesignRef(component = "rule-row", variant = "kind=asset state=readonly")
@Preview(
    name = "rule-row/kind-asset-state-readonly",
    widthDp = 358,
    uiMode = RuleRowPreviewDarkMode,
)
@Composable
private fun RuleRowAssetReadOnlyPreview() =
    RuleRowPreview(
        model =
            RuleRowModel(
                "Native SOL · devnet",
                "2 per request · 10 a day, all connections",
                "toll",
            ),
        kind = RuleRowKind.Asset,
        state = RuleRowState.ReadOnly,
    )

@DesignRef(component = "rule-row", variant = "kind=program")
@Preview(name = "rule-row/kind-program", widthDp = 358, uiMode = RuleRowPreviewDarkMode)
@Composable
private fun RuleRowProgramPreview() =
    RuleRowPreview(
        model = RuleRowModel("Compute Budget", "Compute…111111", "code"),
        kind = RuleRowKind.Program,
        onDelete = {},
    )

@DesignRef(component = "rule-row", variant = "kind=recipient")
@Preview(name = "rule-row/kind-recipient", widthDp = 358, uiMode = RuleRowPreviewDarkMode)
@Composable
private fun RuleRowRecipientPreview() =
    RuleRowPreview(
        model =
            RuleRowModel("FyfWsS…vYSpEA", "Wallet that owns the funds", "account_balance_wallet"),
        kind = RuleRowKind.Recipient,
        onDelete = {},
    )
