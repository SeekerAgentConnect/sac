package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class SheetScaffoldVariant {
    Plain,
    StackedOverBlurred,
}

@Composable
fun SheetScaffold(
    title: String,
    variant: SheetScaffoldVariant,
    onClose: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (variant == SheetScaffoldVariant.StackedOverBlurred) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = SeekerTheme.spacing.lgPlus)
                        .height(SeekerTheme.spacing.xxxl * SheetUnderlayHeightUnits)
                        .graphicsLayer {
                            scaleX = SheetUnderlayScale
                            scaleY = SheetUnderlayScale
                            transformOrigin = TransformOrigin.Center.copy(pivotFractionY = 0f)
                        }
                        .blur(SeekerTheme.spacing.xxs)
                        .clip(
                            RoundedCornerShape(
                                topStart = SeekerTheme.radii.sheet,
                                topEnd = SeekerTheme.radii.sheet,
                            )
                        )
                        .background(SeekerTheme.colors.dim)
                        .clearAndSetSemantics {}
            ) {
                Text(
                    text = "The sheet underneath",
                    modifier =
                        Modifier.padding(
                            start = SeekerTheme.spacing.xl,
                            end = SeekerTheme.spacing.xl,
                            top = SeekerTheme.spacing.huge,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = SeekerTheme.spacing.xxxl)
                    .clip(RoundedCornerShape(SeekerTheme.radii.sheet))
                    .background(SeekerTheme.colors.surface2)
        ) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            top = SeekerTheme.spacing.lg,
                            bottom = SeekerTheme.spacing.xs,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier.width(SeekerTheme.spacing.jumbo)
                            .height(SeekerTheme.spacing.xs)
                            .clip(RoundedCornerShape(SeekerTheme.radii.xs))
                            .background(MaterialTheme.colorScheme.outline)
                )
            }
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(SeekerTheme.spacing.huge * SheetHeaderHeightUnits)
                        .padding(start = SeekerTheme.spacing.xl, end = SeekerTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.headlineSmall,
                )
                OrganismIconAction(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Close",
                    onClick = onClose,
                    size = OrganismIconActionSize.Large,
                    style = OrganismIconActionStyle.Transparent,
                )
            }
            Column(
                modifier =
                    Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = SeekerTheme.spacing.xl,
                            top = SeekerTheme.spacing.xs,
                            end = SeekerTheme.spacing.xl,
                            bottom = SeekerTheme.spacing.xxxl,
                        ),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
                content = body,
            )
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = SeekerTheme.spacing.xl,
                            top = SeekerTheme.spacing.lg,
                            end = SeekerTheme.spacing.xl,
                            bottom = SeekerTheme.spacing.xxl,
                        ),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

private const val SheetUnderlayHeightUnits = 5
private const val SheetHeaderHeightUnits = 2
private const val SheetUnderlayScale = 0.96f
private const val SheetScaffoldPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun SheetScaffoldPreview(variant: SheetScaffoldVariant) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            SheetScaffold(
                title = "Sheet title",
                variant = variant,
                onClose = {},
                body = {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                                .background(SeekerTheme.colors.surface1)
                                .padding(SeekerTheme.spacing.xl)
                    ) {
                        Text(
                            text = "The body scrolls; the header and the actions do not.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                actions = {
                    SeekerButton(
                        label = "Primary",
                        onClick = {},
                        variant = SeekerButtonVariant.Filled,
                        size = SeekerButtonSize.Lg,
                        modifier = Modifier.weight(1f),
                    )
                    SeekerButton(
                        label = "Secondary",
                        onClick = {},
                        variant = SeekerButtonVariant.Neutral,
                        size = SeekerButtonSize.Lg,
                    )
                },
            )
        }
    }
}

@DesignRef(component = "sheet-scaffold", variant = "variant=plain")
@Preview(
    name = "sheet-scaffold/variant-plain",
    widthDp = 358,
    uiMode = SheetScaffoldPreviewDarkMode,
)
@Composable
private fun SheetScaffoldPlainPreview() = SheetScaffoldPreview(SheetScaffoldVariant.Plain)

@DesignRef(component = "sheet-scaffold", variant = "variant=stacked-over-blurred")
@Preview(
    name = "sheet-scaffold/variant-stacked-over-blurred",
    widthDp = 358,
    uiMode = SheetScaffoldPreviewDarkMode,
)
@Composable
private fun SheetScaffoldStackedPreview() =
    SheetScaffoldPreview(SheetScaffoldVariant.StackedOverBlurred)
