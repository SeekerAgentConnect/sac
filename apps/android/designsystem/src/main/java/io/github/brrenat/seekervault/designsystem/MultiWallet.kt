package io.github.brrenat.seekervault.designsystem

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

data class WalletProfileCardModel(
    val name: String,
    val shortAddress: String,
    val address: String,
    val network: String,
    val usage: String,
    val added: String,
    val walletApp: String,
    val renameLabel: String,
    val reconnectLabel: String,
    val removeLabel: String,
)

@Composable
fun WalletProfileCard(
    model: WalletProfileCardModel,
    expanded: Boolean,
    enabled: Boolean,
    copyContentDescription: String,
    onToggle: () -> Unit,
    onCopyAddress: () -> Unit,
    onRename: () -> Unit,
    onReconnect: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    renameModifier: Modifier = Modifier,
    reconnectModifier: Modifier = Modifier,
    removeModifier: Modifier = Modifier,
) {
    val radius by
        animateDpAsState(
            if (expanded) SeekerTheme.radii.sheet else SeekerTheme.radii.lg,
            tween(WalletCardMotionMs, easing = Ease),
            label = "walletCardRadius",
        )
    val background = if (expanded) SeekerTheme.colors.limeContainer else SeekerTheme.colors.surface1
    val foreground =
        if (expanded) SeekerTheme.colors.onLimeContainer else MaterialTheme.colorScheme.onSurface
    val secondary = if (expanded) foreground else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(radius))
                .background(background)
                .animateContentSize(tween(WalletCardMotionMs, easing = Ease))
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = SeekerTheme.sizes.button.medium.height)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onToggle),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lgPlus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(SeekerTheme.sizes.button.medium.height)
                        .clip(CircleShape)
                        .background(if (expanded) foreground else SeekerTheme.colors.surface3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Wallet,
                    contentDescription = null,
                    tint =
                        if (expanded) SeekerTheme.colors.limeContainer
                        else SeekerTheme.colors.primaryText,
                    modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    color = foreground,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.shortAddress,
                    color = foreground,
                    style = SeekerTheme.typography.identifier,
                    maxLines = 1,
                )
            }
            WalletNetworkPill(
                label = model.network,
                container = if (expanded) foreground else SeekerTheme.colors.surface3,
                content =
                    if (expanded) SeekerTheme.colors.limeContainer
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = SeekerTheme.spacing.xl + SeekerTheme.spacing.xxs)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onToggle),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    if (model.usage.startsWith("Not used")) Icons.Outlined.LinkOff
                    else Icons.Outlined.Link,
                contentDescription = null,
                tint = secondary,
                modifier = Modifier.size(SeekerTheme.spacing.xl + SeekerTheme.spacing.xxs),
            )
            Text(model.usage, color = secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            WalletAddressBox(
                address = model.address,
                copyContentDescription = copyContentDescription,
                onCopyAddress = onCopyAddress,
            )
            Text(
                text = "${model.added} · opens in ${model.walletApp}",
                color = foreground,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            ) {
                WalletActionButton(
                    label = model.renameLabel,
                    onClick = onRename,
                    enabled = enabled,
                    container = SeekerTheme.colors.surface3,
                    content = SeekerTheme.colors.primaryText,
                    modifier = renameModifier.weight(1f),
                )
                WalletActionButton(
                    label = model.reconnectLabel,
                    onClick = onReconnect,
                    enabled = enabled,
                    container = SeekerTheme.colors.surface3,
                    content = SeekerTheme.colors.primaryText,
                    modifier = reconnectModifier.weight(1f),
                )
                WalletActionButton(
                    label = model.removeLabel,
                    onClick = onRemove,
                    enabled = enabled,
                    container = SeekerTheme.colors.destructiveContainer,
                    content = SeekerTheme.colors.destructive,
                    modifier = removeModifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WalletActionButton(
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .height(SeekerTheme.sizes.button.medium.height)
                .clip(RoundedCornerShape(SeekerTheme.sizes.button.medium.radius))
                .background(container)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun WalletAddressBox(
    address: String,
    copyContentDescription: String,
    onCopyAddress: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(SeekerTheme.spacing.xxl * AddressBoxXxlUnits)
                .clip(RoundedCornerShape(SeekerTheme.radii.md))
                .background(SeekerTheme.colors.surface0)
                .padding(
                    start = SeekerTheme.spacing.lg,
                    top = SeekerTheme.spacing.mdPlus,
                    end = SeekerTheme.spacing.md,
                    bottom = SeekerTheme.spacing.mdPlus,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = address,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = SeekerTheme.typography.walletAddress,
        )
        Box(
            modifier =
                Modifier.size(SeekerTheme.sizes.iconButton.medium.box)
                    .clip(CircleShape)
                    .background(SeekerTheme.colors.surface3)
                    .clickable(role = Role.Button, onClick = onCopyAddress),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = copyContentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(SeekerTheme.sizes.iconButton.medium.glyph),
            )
        }
    }
}

@Composable
private fun WalletNetworkPill(
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier =
            Modifier.height(SeekerTheme.spacing.xxxl)
                .clip(RoundedCornerShape(SeekerTheme.radii.sm))
                .background(container)
                .padding(horizontal = SeekerTheme.spacing.mdPlus),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun AddWalletButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SeekerButton(
        label = label,
        onClick = onClick,
        variant = SeekerButtonVariant.Filled,
        size = SeekerButtonSize.Lg,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.sizes.iconButton.medium.glyph),
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}

data class WalletSegmentModel(val id: String, val label: String, val selected: Boolean)

data class WalletAppRowModel(val id: String, val label: String, val selected: Boolean)

@Composable
fun AddWalletSheet(
    title: String,
    explanation: String,
    networkTitle: String,
    networks: List<WalletSegmentModel>,
    walletAppTitle: String,
    walletAppExplanation: String,
    apps: List<WalletAppRowModel>,
    continueLabel: String,
    cancelLabel: String,
    problem: String? = null,
    interactionEnabled: Boolean,
    canContinue: Boolean,
    onChooseNetwork: (String) -> Unit,
    onChooseApp: (String) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    networkModifier: (String) -> Modifier = { Modifier },
    appModifier: (String) -> Modifier = { Modifier },
    problemModifier: Modifier = Modifier,
    continueModifier: Modifier = Modifier,
    cancelModifier: Modifier = Modifier,
) {
    WalletSheetSurface(modifier) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = SeekerTheme.spacing.xxxl,
                        end = SeekerTheme.spacing.xxxl,
                        bottom = SeekerTheme.spacing.huge,
                    )
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(SeekerTheme.spacing.lg))
            Text(
                explanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(SeekerTheme.spacing.xxl))
            SheetSectionLabel(networkTitle)
            Spacer(Modifier.height(SeekerTheme.spacing.mdPlus))
            WalletNetworkSegments(networks, interactionEnabled, onChooseNetwork, networkModifier)
            Spacer(Modifier.height(SeekerTheme.spacing.xxl))
            SheetSectionLabel(walletAppTitle)
            Spacer(Modifier.height(SeekerTheme.spacing.xs))
            Text(
                walletAppExplanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(SeekerTheme.spacing.lg))
            apps.forEachIndexed { index, app ->
                WalletAppRow(
                    app = app,
                    enabled = interactionEnabled,
                    onClick = { onChooseApp(app.id) },
                    modifier = appModifier(app.id),
                )
                if (index != apps.lastIndex) Spacer(Modifier.height(SeekerTheme.spacing.xs))
            }
            problem?.let {
                Spacer(Modifier.height(SeekerTheme.spacing.lg))
                Text(
                    it,
                    modifier = problemModifier,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(SeekerTheme.spacing.huge))
            SeekerButton(
                label = continueLabel,
                onClick = onContinue,
                variant =
                    if (canContinue) SeekerButtonVariant.Filled else SeekerButtonVariant.Disabled,
                size = SeekerButtonSize.Lg,
                enabled = canContinue,
                modifier = continueModifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(SeekerTheme.spacing.md))
            Box(
                modifier =
                    cancelModifier
                        .fillMaxWidth()
                        .height(SeekerTheme.sizes.button.large.height)
                        .clickable(role = Role.Button, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    cancelLabel,
                    color = SeekerTheme.colors.primaryText,
                    style = SeekerTheme.typography.buttonLarge,
                )
            }
        }
    }
}

@Composable
private fun WalletNetworkSegments(
    networks: List<WalletSegmentModel>,
    enabled: Boolean,
    onChoose: (String) -> Unit,
    modifier: (String) -> Modifier,
) {
    val dividerWidth = SeekerTheme.spacing.xxs
    val dividerColor = MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth()
            .height(SeekerTheme.sizes.button.medium.height)
            .border(
                width = SeekerTheme.spacing.xxs / BorderDivisor,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(SeekerTheme.radii.xl),
            )
            .clip(RoundedCornerShape(SeekerTheme.radii.xl))
    ) {
        networks.forEachIndexed { index, network ->
            Row(
                modifier =
                    modifier(network.id)
                        .weight(1f)
                        .height(SeekerTheme.sizes.button.medium.height)
                        .background(
                            if (network.selected) SeekerTheme.colors.limeContainer
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable(enabled = enabled, role = Role.RadioButton) {
                            onChoose(network.id)
                        }
                        .then(
                            if (index == 0) Modifier
                            else
                                Modifier.drawBehind {
                                    val stroke = dividerWidth.toPx() / BorderDivisor
                                    drawLine(
                                        color = dividerColor,
                                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                        end = androidx.compose.ui.geometry.Offset(0f, size.height),
                                        strokeWidth = stroke,
                                    )
                                }
                        ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (network.selected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = SeekerTheme.colors.onLimeContainer,
                        modifier = Modifier.size(SeekerTheme.spacing.xl + SeekerTheme.spacing.xxs),
                    )
                    Spacer(Modifier.width(SeekerTheme.spacing.sm))
                }
                Text(
                    network.label,
                    color =
                        if (network.selected) SeekerTheme.colors.onLimeContainer
                        else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun WalletAppRow(
    app: WalletAppRowModel,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SeekerTheme.spacing.huge * AppRowHeightUnits)
                .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(SeekerTheme.sizes.button.medium.height)
                    .clip(RoundedCornerShape(SeekerTheme.radii.md))
                    .background(SeekerTheme.colors.surface3),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.label.firstOrNull()?.uppercase().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        WalletRadio(selected = app.selected, enabled = enabled)
    }
}

data class WalletPickerRowModel(
    val id: String,
    val name: String,
    val shortAddress: String,
    val network: String,
    val note: String? = null,
    val selected: Boolean = false,
    val selectable: Boolean = true,
)

@Composable
fun ConnectionWalletPickerSheet(
    title: String,
    declaredNetwork: String?,
    declaredNetworkLabel: String?,
    noNetworkTitle: String,
    noNetworkMessage: String,
    rows: List<WalletPickerRowModel>,
    addLabel: String,
    cancelLabel: String,
    useLabel: String,
    canUse: Boolean,
    onChoose: (String) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
    rowModifier: (String) -> Modifier = { Modifier },
    addModifier: Modifier = Modifier,
    cancelModifier: Modifier = Modifier,
    useModifier: Modifier = Modifier,
) {
    WalletSheetSurface(modifier) {
        Spacer(Modifier.height(SeekerTheme.spacing.md))
        Text(
            title,
            modifier = Modifier.padding(horizontal = SeekerTheme.spacing.xxxl),
            style = MaterialTheme.typography.headlineMedium,
        )
        if (declaredNetwork != null) {
            Spacer(Modifier.height(SeekerTheme.spacing.lg))
            Row(
                modifier = Modifier.padding(horizontal = SeekerTheme.spacing.xxxl),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Lan,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(SeekerTheme.spacing.xl + SeekerTheme.spacing.xxs),
                )
                Text(
                    declaredNetworkLabel.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Spacer(Modifier.height(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs))
            NoNetworkNotice(noNetworkTitle, noNetworkMessage)
        }
        Spacer(Modifier.height(SeekerTheme.spacing.xl))
        Column(
            modifier = Modifier.padding(horizontal = SeekerTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
        ) {
            rows.forEach { row ->
                WalletPickerRow(
                    model = row,
                    onClick = { onChoose(row.id) },
                    modifier = rowModifier(row.id),
                )
            }
            AddWalletRow(addLabel, onAdd, addModifier)
        }
        WalletPickerActions(
            cancelLabel = cancelLabel,
            useLabel = useLabel,
            canUse = canUse,
            onCancel = onCancel,
            onUse = onUse,
            cancelModifier = cancelModifier,
            useModifier = useModifier,
        )
    }
}

@Composable
private fun WalletPickerRow(
    model: WalletPickerRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foreground =
        if (model.selectable) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.outline
    val container = if (model.selected) SeekerTheme.colors.surface3 else SeekerTheme.colors.surface1
    val shape = RoundedCornerShape(SeekerTheme.radii.lg)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(container)
                .then(
                    if (model.selected) {
                        Modifier.border(
                            width = SeekerTheme.spacing.xxs,
                            color = SeekerTheme.colors.lime,
                            shape = shape,
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    enabled = model.selectable,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    if (!model.selectable) disabled()
                }
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = SeekerTheme.spacing.lg,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalletRadio(selected = model.selected, enabled = model.selectable)
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, color = foreground, style = MaterialTheme.typography.titleMedium)
            Text(
                model.shortAddress,
                color = foreground,
                style = SeekerTheme.typography.identifier,
            )
            model.note?.let {
                Text(it, color = foreground, style = MaterialTheme.typography.bodySmall)
            }
        }
        WalletNetworkPill(
            label = model.network,
            container = SeekerTheme.colors.surface3,
            content = foreground,
        )
    }
}

@Composable
private fun WalletRadio(selected: Boolean, enabled: Boolean) {
    val ring =
        when {
            selected -> SeekerTheme.colors.lime
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    val stroke = SeekerTheme.spacing.xxs
    val selectedColor = SeekerTheme.colors.lime
    Canvas(Modifier.size(SeekerTheme.spacing.xxl)) {
        drawCircle(color = ring, style = Stroke(width = stroke.toPx()))
        if (selected) drawCircle(color = selectedColor, radius = size.minDimension / 4f)
    }
}

@Composable
private fun AddWalletRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val radius = SeekerTheme.radii.lg
    val stroke = SeekerTheme.spacing.xxs
    val dash = SeekerTheme.spacing.md
    val gap = SeekerTheme.spacing.xs
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(
                    min = SeekerTheme.spacing.huge * AddRowHeightUnits + SeekerTheme.spacing.xxs
                )
                .drawBehind {
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(radius.toPx()),
                        style =
                            Stroke(
                                width = stroke.toPx() / BorderDivisor,
                                pathEffect =
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(
                                            dash.toPx(),
                                            gap.toPx(),
                                        )
                                    ),
                            ),
                    )
                }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = SeekerTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            tint = SeekerTheme.colors.primaryText,
            modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
        )
        Text(
            label,
            color = SeekerTheme.colors.primaryText,
            style = SeekerTheme.typography.buttonLarge,
        )
    }
}

@Composable
private fun NoNetworkNotice(title: String, message: String) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = SeekerTheme.spacing.xl)
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.orangeContainer)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = SeekerTheme.colors.onOrangeContainer,
                modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
            )
            Text(
                title,
                color = SeekerTheme.colors.onOrangeContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            message,
            color = SeekerTheme.colors.onOrangeContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WalletPickerActions(
    cancelLabel: String,
    useLabel: String,
    canUse: Boolean,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    cancelModifier: Modifier,
    useModifier: Modifier,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = SeekerTheme.spacing.xxxl,
                    top = SeekerTheme.spacing.xxxl,
                    end = SeekerTheme.spacing.xxxl,
                    bottom = SeekerTheme.spacing.huge,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
    ) {
        SeekerButton(
            label = cancelLabel,
            onClick = onCancel,
            variant = SeekerButtonVariant.Neutral,
            size = SeekerButtonSize.Lg,
            modifier = cancelModifier.weight(1f),
        )
        SeekerButton(
            label = useLabel,
            onClick = onUse,
            variant = if (canUse) SeekerButtonVariant.Filled else SeekerButtonVariant.Disabled,
            size = SeekerButtonSize.Lg,
            enabled = canUse,
            modifier = useModifier.weight(1f),
        )
    }
}

@Composable
private fun WalletSheetSurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart = SeekerTheme.radii.sheet,
                        topEnd = SeekerTheme.radii.sheet,
                    )
                )
                .background(SeekerTheme.colors.surface2)
    ) {
        Box(
            Modifier.fillMaxWidth().height(SeekerTheme.spacing.xxxl),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.width(SeekerTheme.spacing.jumbo)
                    .height(SeekerTheme.spacing.xs)
                    .clip(RoundedCornerShape(SeekerTheme.radii.xs))
                    .background(MaterialTheme.colorScheme.outline)
            )
        }
        content()
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(text, color = SeekerTheme.colors.primaryText, style = MaterialTheme.typography.titleSmall)
}

private const val WalletCardMotionMs = 200
private const val AddressBoxXxlUnits = 3
private const val AppRowHeightUnits = 2
private const val AddRowHeightUnits = 2
private const val BorderDivisor = 2f
