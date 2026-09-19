package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

@Composable
fun InlineCodeInstruction(
    beforeCode: String,
    code: String,
    afterCode: String,
    modifier: Modifier = Modifier,
) {
    val text = buildAnnotatedString {
        append(beforeCode)
        withStyle(
            SpanStyle(
                color = SeekerTheme.colors.primaryText,
                fontFamily = SeekerTheme.typography.identifier.fontFamily,
            )
        ) {
            append(code)
        }
        append(afterCode)
    }
    ScreenSurfaceText(text = text, modifier = modifier)
}

@Composable
private fun ScreenSurfaceText(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        style = MaterialTheme.typography.bodyMedium,
    )
}

enum class ScreenCaptionSize {
    Small,
    Medium,
}

@Composable
fun ScreenCaption(
    text: String,
    modifier: Modifier = Modifier,
    size: ScreenCaptionSize = ScreenCaptionSize.Medium,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = SeekerTheme.spacing.xs),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style =
            when (size) {
                ScreenCaptionSize.Small -> MaterialTheme.typography.bodySmall
                ScreenCaptionSize.Medium ->
                    MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = MaterialTheme.typography.bodyLarge.fontSize
                    )
            },
    )
}

/** Expands one child through [ScreenScrollBody]'s horizontal inset. */
@Composable
fun ScreenFullBleed(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val horizontalInset = SeekerTheme.spacing.xl
    Layout(modifier = modifier.fillMaxWidth(), content = content) { measurables, constraints ->
        val inset = horizontalInset.roundToPx()
        val expandedWidth = constraints.maxWidth + inset * ScreenHorizontalInsetSides
        val placeable =
            measurables
                .single()
                .measure(constraints.copy(minWidth = expandedWidth, maxWidth = expandedWidth))
        layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-inset, 0) }
    }
}

@Composable
fun HomeRulesRow(
    title: String,
    supportingText: String,
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
                .padding(SeekerTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(SeekerTheme.spacing.xxl * HomeRulesAvatarXxlUnits)
                    .clip(CircleShape)
                    .background(SeekerTheme.colors.limeContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
                tint = SeekerTheme.colors.onLimeContainer,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxxl),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WalletPublishWarning(
    serverName: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
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
                imageVector = Icons.Outlined.SyncProblem,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
                tint = SeekerTheme.colors.onOrangeContainer,
            )
            Text(
                text = "Couldn't tell $serverName",
                color = SeekerTheme.colors.onOrangeContainer,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = message,
            color = SeekerTheme.colors.onOrangeContainer,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize
                ),
        )
        SeekerButton(
            label = actionLabel,
            onClick = onAction,
            variant = SeekerButtonVariant.Tertiary,
            size = SeekerButtonSize.Md,
            modifier = actionModifier,
        )
    }
}

enum class ActivityRowKind {
    Transfer,
    Unknown,
    Signature,
    Acknowledgement,
}

data class ActivityRowModel(val title: String, val supportingText: String)

@Composable
fun ActivityRow(
    model: ActivityRowModel,
    kind: ActivityRowKind,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val base =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SeekerTheme.radii.lg))
            .background(SeekerTheme.colors.surface1)
    Row(
        modifier =
            (if (onClick == null) base else base.clickable(role = Role.Button, onClick = onClick))
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = SeekerTheme.spacing.lgPlus,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(SeekerTheme.spacing.xxl * ActivityIconXxlUnits)
                    .clip(CircleShape)
                    .background(SeekerTheme.colors.surface3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = kind.icon(),
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            Text(text = model.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = model.supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun ActivityRowKind.icon(): ImageVector =
    when (this) {
        ActivityRowKind.Transfer -> Icons.Outlined.Toll
        ActivityRowKind.Unknown -> Icons.AutoMirrored.Outlined.HelpOutline
        ActivityRowKind.Signature -> Icons.Outlined.Draw
        ActivityRowKind.Acknowledgement -> Icons.Outlined.DoneAll
    }

private const val HomeRulesAvatarXxlUnits = 2
private const val ActivityIconXxlUnits = 2
private const val ScreenHorizontalInsetSides = 2
