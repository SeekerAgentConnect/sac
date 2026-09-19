package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

@Immutable
data class WalletHandoffSheetState(
    val walletName: String,
    val walletKind: String,
    val headline: String,
    val summary: String,
    val explanation: String,
    val primaryLabel: String,
    val declineLabel: String,
    val leaveLabel: String,
    val primaryTag: String? = null,
    val declineTag: String? = null,
    val leaveTag: String? = null,
)

data class WalletHandoffSheetCallbacks(
    val onPrimary: () -> Unit,
    val onDecline: () -> Unit,
    val onLeave: () -> Unit,
)

@Composable
fun WalletHandoffSheet(
    state: WalletHandoffSheetState,
    callbacks: WalletHandoffSheetCallbacks,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(
        title = state.walletName,
        variant = SheetScaffoldVariant.StackedOverBlurred,
        onClose = callbacks.onLeave,
        modifier = modifier,
        header = {
            WalletHandoffHeader(
                walletName = state.walletName,
                walletKind = state.walletKind,
                onClose = callbacks.onLeave,
            )
        },
        body = {
            Text(text = state.headline, style = MaterialTheme.typography.headlineMedium)
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                        .background(SeekerTheme.colors.surface0)
                        .padding(SeekerTheme.spacing.xl)
            ) {
                Text(
                    text = state.summary,
                    style =
                        SeekerTheme.typography.identifier.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                        ),
                )
            }
            Text(
                text = state.explanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        actions = {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            ) {
                SeekerButton(
                    label = state.primaryLabel,
                    onClick = callbacks.onPrimary,
                    variant = SeekerButtonVariant.Filled,
                    size = SeekerButtonSize.Lg,
                    modifier = Modifier.fillMaxWidth().optionalTag(state.primaryTag),
                )
                SeekerButton(
                    label = state.declineLabel,
                    onClick = callbacks.onDecline,
                    variant = SeekerButtonVariant.Neutral,
                    size = SeekerButtonSize.Lg,
                    modifier = Modifier.fillMaxWidth().optionalTag(state.declineTag),
                )
                SeekerButton(
                    label = state.leaveLabel,
                    onClick = callbacks.onLeave,
                    variant = SeekerButtonVariant.Tonal,
                    size = SeekerButtonSize.Md,
                    modifier = Modifier.fillMaxWidth().optionalTag(state.leaveTag),
                )
            }
        },
    )
}

@Composable
private fun RowScope.WalletHandoffHeader(
    walletName: String,
    walletKind: String,
    onClose: () -> Unit,
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
        Text(text = walletName, style = MaterialTheme.typography.titleLarge)
        Text(
            text = walletKind,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OrganismIconAction(
        icon = Icons.Outlined.Close,
        contentDescription = "Leave without answering",
        onClick = onClose,
        size = OrganismIconActionSize.Large,
        style = OrganismIconActionStyle.Transparent,
    )
}

enum class ConnectionDetailStatusTone {
    Connected,
    Problem,
}

@Immutable
data class ConnectionDetailStatus(
    val headline: String,
    val supportingText: String?,
    val tone: ConnectionDetailStatusTone,
    val tag: String? = null,
    val enabled: Boolean = true,
)

@Immutable
data class ConnectionDetailFact(
    val label: String,
    val value: String,
    val style: FactRowValueStyle,
    val tag: String? = null,
)

@Immutable
data class ConnectionDetailRules(
    val title: String,
    val supportingText: String,
    val caption: String,
    val tag: String? = null,
)

@Immutable
data class ConnectionDetailSheetState(
    val title: String,
    val initials: String,
    val colourName: String,
    val colourSupportingText: String,
    val status: ConnectionDetailStatus,
    val facts: List<ConnectionDetailFact>,
    val rules: ConnectionDetailRules?,
    val renameLabel: String,
    val inboxLabel: String,
    val disconnectExplanation: String,
    val disconnectLabel: String,
    val canDisconnect: Boolean = true,
    val renameTag: String? = null,
    val inboxTag: String? = null,
    val disconnectTag: String? = null,
    val closeTag: String? = null,
)

data class ConnectionDetailSheetCallbacks(
    val onClose: () -> Unit,
    val onRefresh: () -> Unit,
    val onRules: () -> Unit,
    val onRename: () -> Unit,
    val onInbox: () -> Unit,
    val onDisconnect: () -> Unit,
)

@Composable
fun ConnectionDetailSheet(
    state: ConnectionDetailSheetState,
    callbacks: ConnectionDetailSheetCallbacks,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(
        title = state.title,
        variant = SheetScaffoldVariant.Plain,
        onClose = callbacks.onClose,
        modifier = modifier,
        expandToAvailableHeight = true,
        closeTag = state.closeTag,
        body = {
            ConnectionStatusCard(state.status, callbacks.onRefresh)
            ConnectionColourCard(
                sourceName = state.title,
                initials = state.initials,
                colourName = state.colourName,
                supportingText = state.colourSupportingText,
            )
            Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus)) {
                state.facts.forEach { fact -> ConnectionFactCard(fact) }
            }
            state.rules?.let { rules ->
                SheetNavigationRow(
                    title = rules.title,
                    supportingText = rules.supportingText,
                    icon = Icons.Outlined.Tune,
                    onClick = callbacks.onRules,
                    modifier = Modifier.optionalTag(rules.tag),
                )
                SheetCaption(rules.caption)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            ) {
                SeekerButton(
                    label = state.renameLabel,
                    onClick = callbacks.onRename,
                    variant = SeekerButtonVariant.Tonal,
                    size = SeekerButtonSize.Md,
                    modifier = Modifier.weight(1f).optionalTag(state.renameTag),
                )
                SeekerButton(
                    label = state.inboxLabel,
                    onClick = callbacks.onInbox,
                    variant = SeekerButtonVariant.Tonal,
                    size = SeekerButtonSize.Md,
                    modifier = Modifier.weight(1f).optionalTag(state.inboxTag),
                )
            }
            DangerBlock(
                explanation = state.disconnectExplanation,
                actionLabel = state.disconnectLabel,
                enabled = state.canDisconnect,
                onAction = callbacks.onDisconnect,
                actionTag = state.disconnectTag,
            )
        },
    )
}

@Composable
private fun ConnectionStatusCard(status: ConnectionDetailStatus, onRefresh: () -> Unit) {
    val problem = status.tone == ConnectionDetailStatusTone.Problem
    val container =
        if (problem) SeekerTheme.colors.destructiveContainer else SeekerTheme.colors.limeContainer
    val content =
        if (problem) SeekerTheme.colors.onDestructiveContainer
        else SeekerTheme.colors.onLimeContainer
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .optionalTag(status.tag)
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(container)
                .clickable(enabled = status.enabled, role = Role.Button, onClick = onRefresh)
                .padding(SeekerTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
            tint = content,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status.headline,
                color = content,
                style = MaterialTheme.typography.titleMedium,
            )
            status.supportingText?.let {
                Text(text = it, color = content, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConnectionColourCard(
    sourceName: String,
    initials: String,
    colourName: String,
    supportingText: String,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = SeekerTheme.spacing.lgPlus,
                ),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lgPlus),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceAvatar(sourceName = sourceName, initials = initials)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Colour", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$colourName · $supportingText",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionColourSlots.forEach { slot ->
                SourceColourChoice(
                    slot = slot,
                    selected = slot.name.equals(colourName, ignoreCase = true),
                )
            }
        }
    }
}

@Composable
private fun SourceColourChoice(slot: SourcePaletteSlot, selected: Boolean) {
    val colors = sourcePaletteColors(slot)
    Box(
        modifier =
            Modifier.size(SeekerTheme.sizes.iconButton.medium.box)
                .alpha(if (selected || slot == SourcePaletteSlot.Sand) 1f else 0.4f)
                .then(
                    if (selected) {
                        Modifier.border(
                            SeekerTheme.spacing.xs,
                            colors.container,
                            CircleShape,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(if (selected) SeekerTheme.spacing.xs else SeekerTheme.spacing.xxs)
                .background(colors.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (slot != SourcePaletteSlot.Sand) {
            Icon(
                imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Link,
                contentDescription = if (selected) "Selected colour" else null,
                tint = colors.content,
            )
        }
    }
}

private val ConnectionColourSlots =
    listOf(
        SourcePaletteSlot.Tangerine,
        SourcePaletteSlot.Blue,
        SourcePaletteSlot.Violet,
        SourcePaletteSlot.Teal,
        SourcePaletteSlot.Pink,
        SourcePaletteSlot.Sand,
    )

@Composable
private fun ConnectionFactCard(fact: ConnectionDetailFact) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .optionalTag(fact.tag)
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .semantics(mergeDescendants = true) {}
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = SeekerTheme.spacing.lgPlus,
                ),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
    ) {
        Text(
            text = fact.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = fact.value,
            color = MaterialTheme.colorScheme.onSurface,
            style = fact.style.connectionFactTextStyle(),
        )
    }
}

@Composable
private fun FactRowValueStyle.connectionFactTextStyle(): TextStyle =
    when (this) {
        FactRowValueStyle.Plain -> MaterialTheme.typography.bodyMedium
        FactRowValueStyle.Mono,
        FactRowValueStyle.MonoWrap -> SeekerTheme.typography.identifier
    }

@Composable
private fun SheetNavigationRow(
    title: String,
    supportingText: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = SeekerTheme.spacing.lgPlus,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lgPlus),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(SeekerTheme.sizes.iconButton.medium.box)
                    .background(SeekerTheme.colors.limeContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SeekerTheme.colors.onLimeContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DangerBlock(
    explanation: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
    actionTag: String? = null,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.destructiveContainer)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        Text(
            text = explanation,
            color = SeekerTheme.colors.onDestructiveContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
        SeekerButton(
            label = actionLabel,
            onClick = onAction,
            variant =
                if (enabled) SeekerButtonVariant.ErrorStrong else SeekerButtonVariant.Disabled,
            size = SeekerButtonSize.Md,
            enabled = enabled,
            modifier = Modifier.optionalTag(actionTag),
        )
    }
}

enum class RulesSectionKind {
    Actions,
    Assets,
    Recipients,
    Programs,
}

@Immutable
data class RulesSheetItem(
    val id: String,
    val model: RuleRowModel,
    val kind: RuleRowKind,
    val checked: Boolean? = null,
    val readOnly: Boolean = false,
    val clickable: Boolean = true,
    val tag: String? = null,
)

@Immutable
data class RulesSheetSection(
    val kind: RulesSectionKind,
    val title: String,
    val source: ScopeChipSource,
    val override: Boolean? = null,
    val enabled: Boolean,
    val switchLabel: String,
    val statusText: String,
    val supportingText: String,
    val items: List<RulesSheetItem>,
    val addLabel: String? = null,
    val sectionTag: String? = null,
    val switchTag: String? = null,
    val inheritTag: String? = null,
    val overrideTag: String? = null,
    val addTag: String? = null,
)

data class RulesSheetCallbacks(
    val onClose: () -> Unit,
    val onIntroToggle: () -> Unit,
    val onOpenGlobal: () -> Unit,
    val onOverrideChange: (RulesSectionKind, Boolean) -> Unit,
    val onEnabledChange: (RulesSectionKind, Boolean) -> Unit,
    val onItemClick: (RulesSectionKind, String) -> Unit,
    val onDelete: (RulesSectionKind, String) -> Unit,
    val onAdd: (RulesSectionKind) -> Unit,
    val onClear: () -> Unit,
    val onSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

@Immutable
data class ConnectionRulesSheetState(
    val title: String,
    val intro: String,
    val introExpanded: Boolean,
    val introDetails: List<String> = emptyList(),
    val replacementSummary: String,
    val replacementCaption: String,
    val sections: List<RulesSheetSection>,
    val footerCaption: String,
    val saveLabel: String? = null,
    val cancelLabel: String? = null,
    val actionsEnabled: Boolean = true,
    val saveTag: String? = null,
    val cancelTag: String? = null,
    val globalTag: String? = null,
    val introTag: String? = null,
    val introContentTag: String? = null,
)

@Immutable
data class GlobalRulesSheetState(
    val title: String,
    val intro: String,
    val introExpanded: Boolean,
    val introDetails: List<String> = emptyList(),
    val defaultsCaption: String,
    val sections: List<RulesSheetSection>,
    val clearLabel: String,
    val footerCaption: String,
    val saveLabel: String? = null,
    val cancelLabel: String? = null,
    val actionsEnabled: Boolean = true,
    val saveTag: String? = null,
    val cancelTag: String? = null,
    val introTag: String? = null,
    val introContentTag: String? = null,
)

@Composable
fun ConnectionRulesSheet(
    state: ConnectionRulesSheetState,
    callbacks: RulesSheetCallbacks,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(
        title = state.title,
        variant = SheetScaffoldVariant.Plain,
        onClose = callbacks.onClose,
        modifier = modifier,
        expandToAvailableHeight = true,
        body = {
            RulesIntro(
                text = state.intro,
                expanded = state.introExpanded,
                details = state.introDetails,
                onClick = callbacks.onIntroToggle,
                tag = state.introTag,
                contentTag = state.introContentTag,
            )
            RulesReplacementSummary(
                summary = state.replacementSummary,
                caption = state.replacementCaption,
                onOpenGlobal = callbacks.onOpenGlobal,
                actionTag = state.globalTag,
            )
            state.sections.forEach { section -> RuleSection(section, callbacks) }
            SheetCaption(state.footerCaption)
        },
        actions =
            state.saveLabel?.let { saveLabel ->
                {
                    RulesActions(
                        saveLabel = saveLabel,
                        cancelLabel = state.cancelLabel,
                        enabled = state.actionsEnabled,
                        saveTag = state.saveTag,
                        cancelTag = state.cancelTag,
                        callbacks = callbacks,
                    )
                }
            },
    )
}

@Composable
fun GlobalRulesSheet(
    state: GlobalRulesSheetState,
    callbacks: RulesSheetCallbacks,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(
        title = state.title,
        variant = SheetScaffoldVariant.Plain,
        onClose = callbacks.onClose,
        modifier = modifier,
        expandToAvailableHeight = true,
        body = {
            RulesIntro(
                text = state.intro,
                expanded = state.introExpanded,
                details = state.introDetails,
                onClick = callbacks.onIntroToggle,
                tag = state.introTag,
                contentTag = state.introContentTag,
            )
            SheetCaption(state.defaultsCaption)
            state.sections.forEach { section -> RuleSection(section, callbacks) }
            SeekerButton(
                label = state.clearLabel,
                onClick = callbacks.onClear,
                variant = SeekerButtonVariant.Error,
                size = SeekerButtonSize.Md,
            )
            SheetCaption(state.footerCaption)
        },
        actions =
            state.saveLabel?.let { saveLabel ->
                {
                    RulesActions(
                        saveLabel = saveLabel,
                        cancelLabel = state.cancelLabel,
                        enabled = state.actionsEnabled,
                        saveTag = state.saveTag,
                        cancelTag = state.cancelTag,
                        callbacks = callbacks,
                    )
                }
            },
    )
}

@Composable
private fun RowScope.RulesActions(
    saveLabel: String,
    cancelLabel: String?,
    enabled: Boolean,
    saveTag: String?,
    cancelTag: String?,
    callbacks: RulesSheetCallbacks,
) {
    cancelLabel?.let {
        SeekerButton(
            label = it,
            onClick = callbacks.onCancel,
            variant = if (enabled) SeekerButtonVariant.Neutral else SeekerButtonVariant.Disabled,
            size = SeekerButtonSize.Lg,
            enabled = enabled,
            modifier = Modifier.weight(1f).optionalTag(cancelTag),
        )
    }
    SeekerButton(
        label = saveLabel,
        onClick = callbacks.onSave,
        variant = if (enabled) SeekerButtonVariant.Filled else SeekerButtonVariant.Disabled,
        size = SeekerButtonSize.Lg,
        enabled = enabled,
        modifier = Modifier.weight(1f).optionalTag(saveTag),
    )
}

@Composable
private fun RulesIntro(
    text: String,
    expanded: Boolean,
    details: List<String>,
    onClick: () -> Unit,
    tag: String?,
    contentTag: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .optionalTag(tag)
                    .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                    .background(SeekerTheme.colors.surface1)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(
                        horizontal = SeekerTheme.spacing.xl,
                        vertical = SeekerTheme.spacing.lgPlus,
                    ),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(
                imageVector =
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded && details.isNotEmpty()) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .optionalTag(contentTag)
                        .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                        .background(SeekerTheme.colors.surface1)
                        .padding(SeekerTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            ) {
                details.forEach { detail ->
                    Text(
                        text = detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RulesReplacementSummary(
    summary: String,
    caption: String,
    onOpenGlobal: () -> Unit,
    actionTag: String? = null,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SeekerButton(
            label = "Global",
            onClick = onOpenGlobal,
            variant = SeekerButtonVariant.Tonal,
            size = SeekerButtonSize.Md,
            modifier = Modifier.optionalTag(actionTag),
        )
    }
}

@Composable
private fun RuleSection(section: RulesSheetSection, callbacks: RulesSheetCallbacks) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .optionalTag(section.sectionTag)
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
                imageVector = section.kind.icon(),
                contentDescription = null,
                tint = SeekerTheme.colors.primaryText,
            )
            Text(
                text = section.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            ScopeChip(source = section.source)
        }
        section.override?.let { override ->
            Segmented(
                options = listOf("Use global", "Override"),
                selectedIndex = if (override) 1 else 0,
                onSelect = { callbacks.onOverrideChange(section.kind, it == 1) },
                count = SegmentedCount.Two,
                usage = SegmentedUsage.RuleMode,
                optionModifiers =
                    listOf(
                        Modifier.optionalTag(section.inheritTag),
                        Modifier.optionalTag(section.overrideTag),
                    ),
            )
        }
        if (section.override != false) {
            SwitchRow(
                label = section.switchLabel,
                state = if (section.enabled) SwitchRowState.On else SwitchRowState.Off,
                onStateChange = {
                    callbacks.onEnabledChange(section.kind, it == SwitchRowState.On)
                },
                modifier = Modifier.optionalTag(section.switchTag),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = section.statusText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = section.supportingText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        section.items.forEach { item ->
            val rowState =
                when {
                    item.kind == RuleRowKind.Action && item.checked == true -> RuleRowState.Checked
                    item.kind == RuleRowKind.Action -> RuleRowState.Unchecked
                    item.kind == RuleRowKind.Asset && item.readOnly -> RuleRowState.ReadOnly
                    else -> RuleRowState.Default
                }
            RuleRow(
                model = item.model,
                kind = item.kind,
                state = rowState,
                onClick =
                    if (item.readOnly || !item.clickable) null
                    else {
                        { callbacks.onItemClick(section.kind, item.id) }
                    },
                onDelete =
                    if (item.readOnly || item.kind == RuleRowKind.Action) null
                    else {
                        { callbacks.onDelete(section.kind, item.id) }
                    },
                modifier = Modifier.optionalTag(item.tag),
            )
        }
        section.addLabel?.let { label ->
            SeekerButton(
                label = label,
                onClick = { callbacks.onAdd(section.kind) },
                variant = SeekerButtonVariant.Tonal,
                size = SeekerButtonSize.Md,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(SeekerTheme.spacing.xxl),
                    )
                },
                modifier = Modifier.optionalTag(section.addTag),
            )
        }
    }
}

private fun RulesSectionKind.icon(): ImageVector =
    when (this) {
        RulesSectionKind.Actions -> Icons.Outlined.Bolt
        RulesSectionKind.Assets -> Icons.Outlined.Toll
        RulesSectionKind.Recipients -> Icons.Outlined.AccountBalanceWallet
        RulesSectionKind.Programs -> Icons.Outlined.Code
    }

enum class AssetEditorKind {
    NativeSol,
    Token,
}

@Immutable data class AssetEditorNetwork(val id: String, val label: String)

@Immutable
data class AssetEditorSheetState(
    val title: String,
    val saveLabel: String,
    val scopeLabel: String,
    val kind: AssetEditorKind,
    val nativeLabel: String,
    val tokenLabel: String,
    val mintLabel: String,
    val mint: String,
    val mintError: String? = null,
    val networkLabel: String,
    val networks: List<AssetEditorNetwork>,
    val selectedNetwork: Int,
    val thresholdsLabel: String,
    val perRequestLabel: String,
    val perRequest: String,
    val perRequestError: String? = null,
    val dailyLabel: String,
    val daily: String,
    val dailyError: String? = null,
    val globalDailyTitle: String,
    val globalDailySupportingText: String,
    val editGlobalLabel: String,
    val footerCaption: String,
    val canSave: Boolean,
    val saveTag: String? = null,
    val nativeTag: String? = null,
    val tokenTag: String? = null,
    val mintTag: String? = null,
    val networkTags: List<String?> = emptyList(),
    val perRequestTag: String? = null,
    val dailyTag: String? = null,
    val globalDailyTag: String? = null,
)

data class AssetEditorSheetCallbacks(
    val onClose: () -> Unit,
    val onSave: () -> Unit,
    val onKindChange: (AssetEditorKind) -> Unit,
    val onMintChange: (String) -> Unit,
    val onNetworkChange: (Int) -> Unit,
    val onPerRequestChange: (String) -> Unit,
    val onDailyChange: (String) -> Unit,
    val onEditGlobal: () -> Unit,
)

@Composable
fun AssetEditorSheet(
    state: AssetEditorSheetState,
    callbacks: AssetEditorSheetCallbacks,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(
        title = state.title,
        variant = SheetScaffoldVariant.Plain,
        onClose = callbacks.onClose,
        modifier = modifier,
        bodySpacing = SeekerTheme.spacing.xl,
        headerAction = {
            SeekerButton(
                label = state.saveLabel,
                onClick = callbacks.onSave,
                variant =
                    if (state.canSave) SeekerButtonVariant.Filled else SeekerButtonVariant.Disabled,
                size = SeekerButtonSize.Md,
                enabled = state.canSave,
                modifier = Modifier.optionalTag(state.saveTag),
            )
        },
        body = {
            ConnectionOnlyChip(state.scopeLabel)
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            ) {
                SheetRadioRow(
                    label = state.nativeLabel,
                    selected = state.kind == AssetEditorKind.NativeSol,
                    onClick = { callbacks.onKindChange(AssetEditorKind.NativeSol) },
                    modifier = Modifier.optionalTag(state.nativeTag),
                )
                SheetRadioRow(
                    label = state.tokenLabel,
                    selected = state.kind == AssetEditorKind.Token,
                    onClick = { callbacks.onKindChange(AssetEditorKind.Token) },
                    modifier = Modifier.optionalTag(state.tokenTag),
                )
            }
            if (state.kind == AssetEditorKind.Token) {
                SeekerTextField(
                    label = state.mintLabel,
                    value = state.mint,
                    onValueChange = callbacks.onMintChange,
                    state =
                        if (state.mintError == null) DesignTextFieldState.Rest
                        else DesignTextFieldState.Error,
                    errorMessage = state.mintError,
                    reserveErrorSpace = false,
                    inputModifier = Modifier.optionalTag(state.mintTag),
                )
            }
            SheetLabel(state.networkLabel)
            Segmented(
                options = state.networks.map { it.label },
                selectedIndex = state.selectedNetwork,
                onSelect = callbacks.onNetworkChange,
                count = SegmentedCount.Three,
                optionModifiers =
                    state.networks.mapIndexed { index, _ ->
                        Modifier.optionalTag(state.networkTags.getOrNull(index))
                    },
            )
            SheetLabel(state.thresholdsLabel)
            SeekerTextField(
                label = state.perRequestLabel,
                value = state.perRequest,
                onValueChange = callbacks.onPerRequestChange,
                state =
                    if (state.perRequestError == null) DesignTextFieldState.Rest
                    else DesignTextFieldState.Error,
                errorMessage = state.perRequestError,
                reserveErrorSpace = false,
                inputModifier = Modifier.optionalTag(state.perRequestTag),
            )
            SeekerTextField(
                label = state.dailyLabel,
                value = state.daily,
                onValueChange = callbacks.onDailyChange,
                state =
                    if (state.dailyError == null) DesignTextFieldState.Rest
                    else DesignTextFieldState.Error,
                errorMessage = state.dailyError,
                reserveErrorSpace = false,
                inputModifier = Modifier.optionalTag(state.dailyTag),
            )
            GlobalDailyLimitRow(
                title = state.globalDailyTitle,
                supportingText = state.globalDailySupportingText,
                actionLabel = state.editGlobalLabel,
                onAction = callbacks.onEditGlobal,
                modifier = Modifier.optionalTag(state.globalDailyTag),
            )
            SheetCaption(state.footerCaption)
        },
    )
}

@Composable
private fun SheetRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.lg)
    ) {
        RadioRow(
            label = label,
            state = if (selected) RadioRowState.On else RadioRowState.Off,
            onClick = onClick,
        )
    }
}

@Composable
private fun ConnectionOnlyChip(label: String) {
    Row(
        modifier =
            Modifier.clip(RoundedCornerShape(SeekerTheme.radii.md))
                .background(SeekerTheme.colors.limeContainer)
                .padding(
                    horizontal = SeekerTheme.spacing.mdPlus,
                    vertical = SeekerTheme.spacing.xs,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.lgPlus),
            tint = SeekerTheme.colors.onLimeContainer,
        )
        Text(
            text = label,
            color = SeekerTheme.colors.onLimeContainer,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SheetLabel(label: String) {
    Text(
        text = label,
        color = SeekerTheme.colors.primaryText,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun GlobalDailyLimitRow(
    title: String,
    supportingText: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = SeekerTheme.spacing.lgPlus,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SeekerButton(
            label = actionLabel,
            onClick = onAction,
            variant = SeekerButtonVariant.Tonal,
            size = SeekerButtonSize.Sm,
        )
    }
}

@Immutable
data class AddAddressSheetState(
    val title: String,
    val addLabel: String,
    val scopeLabel: String,
    val fieldLabel: String,
    val value: String,
    val placeholder: String,
    val error: String? = null,
    val caption: String,
    val canAdd: Boolean,
    val addTag: String? = null,
    val fieldTag: String? = null,
)

data class AddAddressSheetCallbacks(
    val onClose: () -> Unit,
    val onAdd: () -> Unit,
    val onValueChange: (String) -> Unit,
)

@Composable
fun AddAddressSheet(
    state: AddAddressSheetState,
    callbacks: AddAddressSheetCallbacks,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(
        title = state.title,
        variant = SheetScaffoldVariant.Plain,
        onClose = callbacks.onClose,
        modifier = modifier,
        headerAction = {
            SeekerButton(
                label = state.addLabel,
                onClick = callbacks.onAdd,
                variant =
                    if (state.canAdd) SeekerButtonVariant.Filled else SeekerButtonVariant.Disabled,
                size = SeekerButtonSize.Md,
                enabled = state.canAdd,
                modifier = Modifier.optionalTag(state.addTag),
            )
        },
        body = {
            ConnectionOnlyChip(state.scopeLabel)
            SeekerTextField(
                label = state.fieldLabel,
                value = state.value,
                onValueChange = callbacks.onValueChange,
                state =
                    if (state.error == null) DesignTextFieldState.Rest
                    else DesignTextFieldState.Error,
                errorMessage = state.error,
                placeholder = state.placeholder,
                reserveErrorSpace = false,
                inputModifier = Modifier.optionalTag(state.fieldTag),
            )
            SheetCaption(state.caption)
        },
    )
}

@Composable
private fun SheetCaption(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.spacing.xs),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun Modifier.optionalTag(tag: String?): Modifier =
    if (tag == null) this else then(Modifier.testTag(tag).semantics(mergeDescendants = true) {})
