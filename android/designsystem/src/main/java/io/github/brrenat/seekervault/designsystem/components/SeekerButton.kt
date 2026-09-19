package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerButtonSize as SeekerButtonDimensions
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class SeekerButtonVariant {
    Filled,
    Tonal,
    Neutral,
    Error,
    ErrorStrong,
    Tertiary,
    OnVerdictOk,
    OnVerdictWarn,
    Disabled,
}

enum class SeekerButtonSize {
    Sm,
    Md,
    Lg,
}

@Composable
fun SeekerButton(
    label: String,
    onClick: () -> Unit,
    variant: SeekerButtonVariant,
    size: SeekerButtonSize,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val dimensions = size.dimensions()
    val colors = variant.colors()
    val enabled = variant != SeekerButtonVariant.Disabled
    val startPadding =
        if (leadingIcon == null) {
            dimensions.horizontalPadding
        } else {
            dimensions.horizontalPadding - SeekerTheme.spacing.md
        }

    Row(
        modifier =
            modifier
                .height(dimensions.height)
                .clip(RoundedCornerShape(dimensions.radius))
                .background(colors.container)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(start = startPadding, end = dimensions.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.content) {
            leadingIcon?.invoke()
            Text(text = label, color = colors.content, style = size.textStyle())
        }
    }
}

private data class ButtonColors(val container: Color, val content: Color)

@Composable
private fun SeekerButtonVariant.colors(): ButtonColors =
    when (this) {
        SeekerButtonVariant.Filled ->
            ButtonColors(SeekerTheme.colors.lime, SeekerTheme.colors.onLime)
        SeekerButtonVariant.Tonal ->
            ButtonColors(SeekerTheme.colors.surface3, SeekerTheme.colors.primaryText)
        SeekerButtonVariant.Neutral ->
            ButtonColors(SeekerTheme.colors.surface3, MaterialTheme.colorScheme.onSurface)
        SeekerButtonVariant.Error ->
            ButtonColors(
                SeekerTheme.colors.destructiveContainer,
                SeekerTheme.colors.destructive,
            )
        SeekerButtonVariant.ErrorStrong ->
            ButtonColors(SeekerTheme.colors.destructive, SeekerTheme.colors.onDestructive)
        SeekerButtonVariant.Tertiary ->
            ButtonColors(SeekerTheme.colors.orangeContainer, MaterialTheme.colorScheme.onSurface)
        SeekerButtonVariant.OnVerdictOk ->
            ButtonColors(SeekerTheme.colors.onLimeContainer, SeekerTheme.colors.limeContainer)
        SeekerButtonVariant.OnVerdictWarn ->
            ButtonColors(SeekerTheme.colors.onOrangeContainer, SeekerTheme.colors.orangeContainer)
        SeekerButtonVariant.Disabled ->
            ButtonColors(SeekerTheme.colors.surface3, MaterialTheme.colorScheme.onSurfaceVariant)
    }

@Composable
private fun SeekerButtonSize.dimensions(): SeekerButtonDimensions =
    when (this) {
        SeekerButtonSize.Sm -> SeekerTheme.sizes.button.small
        SeekerButtonSize.Md -> SeekerTheme.sizes.button.medium
        SeekerButtonSize.Lg -> SeekerTheme.sizes.button.large
    }

@Composable
private fun SeekerButtonSize.textStyle(): TextStyle =
    when (this) {
        SeekerButtonSize.Sm ->
            MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
        SeekerButtonSize.Md -> MaterialTheme.typography.labelLarge
        SeekerButtonSize.Lg -> SeekerTheme.typography.buttonLarge
    }

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun ButtonPreview(
    variant: SeekerButtonVariant,
    size: SeekerButtonSize,
    label: String,
) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            SeekerButton(label = label, onClick = {}, variant = variant, size = size)
        }
    }
}

@DesignRef(component = "button", variant = "variant=disabled size=lg")
@Preview(name = "button/variant-disabled-size-lg", uiMode = DarkMode)
@Composable
internal fun ButtonDisabledLargePreview() =
    ButtonPreview(SeekerButtonVariant.Disabled, SeekerButtonSize.Lg, "Approve and send")

@DesignRef(component = "button", variant = "variant=disabled size=md")
@Preview(name = "button/variant-disabled-size-md", uiMode = DarkMode)
@Composable
internal fun ButtonDisabledMediumPreview() =
    ButtonPreview(SeekerButtonVariant.Disabled, SeekerButtonSize.Md, "Approve and send")

@DesignRef(component = "button", variant = "variant=disabled size=sm")
@Preview(name = "button/variant-disabled-size-sm", uiMode = DarkMode)
@Composable
internal fun ButtonDisabledSmallPreview() =
    ButtonPreview(SeekerButtonVariant.Disabled, SeekerButtonSize.Sm, "Approve and send")

@DesignRef(component = "button", variant = "variant=error size=lg")
@Preview(name = "button/variant-error-size-lg", uiMode = DarkMode)
@Composable
internal fun ButtonErrorLargePreview() =
    ButtonPreview(SeekerButtonVariant.Error, SeekerButtonSize.Lg, "Review")

@DesignRef(component = "button", variant = "variant=error size=md")
@Preview(name = "button/variant-error-size-md", uiMode = DarkMode)
@Composable
internal fun ButtonErrorMediumPreview() =
    ButtonPreview(SeekerButtonVariant.Error, SeekerButtonSize.Md, "Review")

@DesignRef(component = "button", variant = "variant=error size=sm")
@Preview(name = "button/variant-error-size-sm", uiMode = DarkMode)
@Composable
internal fun ButtonErrorSmallPreview() =
    ButtonPreview(SeekerButtonVariant.Error, SeekerButtonSize.Sm, "Review")

@DesignRef(component = "button", variant = "variant=errorStrong size=lg")
@Preview(name = "button/variant-errorstrong-size-lg", uiMode = DarkMode)
@Composable
internal fun ButtonErrorStrongLargePreview() =
    ButtonPreview(SeekerButtonVariant.ErrorStrong, SeekerButtonSize.Lg, "Review")

@DesignRef(component = "button", variant = "variant=errorStrong size=md")
@Preview(name = "button/variant-errorstrong-size-md", uiMode = DarkMode)
@Composable
internal fun ButtonErrorStrongMediumPreview() =
    ButtonPreview(SeekerButtonVariant.ErrorStrong, SeekerButtonSize.Md, "Review")

@DesignRef(component = "button", variant = "variant=errorStrong size=sm")
@Preview(name = "button/variant-errorstrong-size-sm", uiMode = DarkMode)
@Composable
internal fun ButtonErrorStrongSmallPreview() =
    ButtonPreview(SeekerButtonVariant.ErrorStrong, SeekerButtonSize.Sm, "Review")

@DesignRef(component = "button", variant = "variant=filled size=lg")
@Preview(name = "button/variant-filled-size-lg", uiMode = DarkMode)
@Composable
internal fun ButtonFilledLargePreview() =
    ButtonPreview(SeekerButtonVariant.Filled, SeekerButtonSize.Lg, "Approve and send")

@DesignRef(component = "button", variant = "variant=filled size=md")
@Preview(name = "button/variant-filled-size-md", uiMode = DarkMode)
@Composable
internal fun ButtonFilledMediumPreview() =
    ButtonPreview(SeekerButtonVariant.Filled, SeekerButtonSize.Md, "Approve and send")

@DesignRef(component = "button", variant = "variant=filled size=sm")
@Preview(name = "button/variant-filled-size-sm", uiMode = DarkMode)
@Composable
internal fun ButtonFilledSmallPreview() =
    ButtonPreview(SeekerButtonVariant.Filled, SeekerButtonSize.Sm, "Approve and send")

@DesignRef(component = "button", variant = "variant=neutral size=lg")
@Preview(name = "button/variant-neutral-size-lg", uiMode = DarkMode)
@Composable
internal fun ButtonNeutralLargePreview() =
    ButtonPreview(SeekerButtonVariant.Neutral, SeekerButtonSize.Lg, "Review")

@DesignRef(component = "button", variant = "variant=neutral size=md")
@Preview(name = "button/variant-neutral-size-md", uiMode = DarkMode)
@Composable
internal fun ButtonNeutralMediumPreview() =
    ButtonPreview(SeekerButtonVariant.Neutral, SeekerButtonSize.Md, "Review")

@DesignRef(component = "button", variant = "variant=neutral size=sm")
@Preview(name = "button/variant-neutral-size-sm", uiMode = DarkMode)
@Composable
internal fun ButtonNeutralSmallPreview() =
    ButtonPreview(SeekerButtonVariant.Neutral, SeekerButtonSize.Sm, "Review")

@DesignRef(component = "button", variant = "variant=tertiary size=lg")
@Preview(name = "button/variant-tertiary-size-lg", uiMode = DarkMode)
@Composable
internal fun ButtonTertiaryLargePreview() =
    ButtonPreview(SeekerButtonVariant.Tertiary, SeekerButtonSize.Lg, "Review")

@DesignRef(component = "button", variant = "variant=tertiary size=md")
@Preview(name = "button/variant-tertiary-size-md", uiMode = DarkMode)
@Composable
internal fun ButtonTertiaryMediumPreview() =
    ButtonPreview(SeekerButtonVariant.Tertiary, SeekerButtonSize.Md, "Review")

@DesignRef(component = "button", variant = "variant=tertiary size=sm")
@Preview(name = "button/variant-tertiary-size-sm", uiMode = DarkMode)
@Composable
internal fun ButtonTertiarySmallPreview() =
    ButtonPreview(SeekerButtonVariant.Tertiary, SeekerButtonSize.Sm, "Review")

@DesignRef(component = "button", variant = "variant=tonal size=lg")
@Preview(name = "button/variant-tonal-size-lg", uiMode = DarkMode)
@Composable
internal fun ButtonTonalLargePreview() =
    ButtonPreview(SeekerButtonVariant.Tonal, SeekerButtonSize.Lg, "Review")

@DesignRef(component = "button", variant = "variant=tonal size=md")
@Preview(name = "button/variant-tonal-size-md", uiMode = DarkMode)
@Composable
internal fun ButtonTonalMediumPreview() =
    ButtonPreview(SeekerButtonVariant.Tonal, SeekerButtonSize.Md, "Review")

@DesignRef(component = "button", variant = "variant=tonal size=sm")
@Preview(name = "button/variant-tonal-size-sm", uiMode = DarkMode)
@Composable
internal fun ButtonTonalSmallPreview() =
    ButtonPreview(SeekerButtonVariant.Tonal, SeekerButtonSize.Sm, "Review")
