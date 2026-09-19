package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

internal enum class OrganismIconActionSize {
    Inline,
    Medium,
    MediumLargeGlyph,
    Large,
}

internal enum class OrganismIconActionStyle {
    Transparent,
    Neutral,
    OnLime,
}

@Composable
internal fun OrganismIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: OrganismIconActionSize,
    style: OrganismIconActionStyle,
    modifier: Modifier = Modifier,
) {
    val dimensions =
        when (size) {
            OrganismIconActionSize.Inline ->
                OrganismIconActionDimensions(
                    box = SeekerTheme.sizes.iconButton.medium.glyph,
                    glyph = SeekerTheme.sizes.iconButton.medium.glyph,
                )
            OrganismIconActionSize.Medium ->
                OrganismIconActionDimensions(
                    box = SeekerTheme.sizes.iconButton.medium.box,
                    glyph = SeekerTheme.sizes.iconButton.medium.glyph,
                )
            OrganismIconActionSize.MediumLargeGlyph ->
                OrganismIconActionDimensions(
                    box = SeekerTheme.sizes.iconButton.medium.box,
                    glyph = SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs,
                )
            OrganismIconActionSize.Large ->
                OrganismIconActionDimensions(
                    box = SeekerTheme.sizes.iconButton.large.box,
                    glyph = SeekerTheme.sizes.iconButton.large.glyph,
                )
        }
    val container =
        when (style) {
            OrganismIconActionStyle.Transparent -> Color.Transparent
            OrganismIconActionStyle.Neutral -> SeekerTheme.colors.surface3
            OrganismIconActionStyle.OnLime -> SeekerTheme.colors.limeContainer
        }
    val content =
        when (style) {
            OrganismIconActionStyle.OnLime -> SeekerTheme.colors.onLimeContainer
            else -> MaterialTheme.colorScheme.onSurface
        }

    Box(
        modifier =
            modifier
                .size(dimensions.box)
                .clip(CircleShape)
                .background(container)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(dimensions.glyph),
            tint = content,
        )
    }
}

private data class OrganismIconActionDimensions(
    val box: androidx.compose.ui.unit.Dp,
    val glyph: androidx.compose.ui.unit.Dp,
)
