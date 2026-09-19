package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class SectionHeaderTrailing {
    None,
    Button,
}

@Composable
fun SectionHeader(
    title: String,
    trailing: SectionHeaderTrailing,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailingModifier: Modifier = Modifier,
) {
    val trailingContent =
        when (trailing) {
            SectionHeaderTrailing.None -> null
            SectionHeaderTrailing.Button -> {
                requireNotNull(trailingLabel) {
                    "trailingLabel is required when trailing is Button"
                } to
                    requireNotNull(onTrailingClick) {
                        "onTrailingClick is required when trailing is Button"
                    }
            }
        }
    val height =
        if (trailingContent == null) {
            SeekerTheme.spacing.xxxl
        } else {
            SeekerTheme.sizes.button.medium.height
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .padding(
                    start = SeekerTheme.spacing.xs,
                    top = SeekerTheme.spacing.md,
                    end = SeekerTheme.spacing.xs,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = SeekerTheme.colors.primaryText,
            style = MaterialTheme.typography.titleSmall,
        )
        trailingContent?.let { (label, onClick) ->
            SeekerButton(
                label = label,
                onClick = onClick,
                variant = SeekerButtonVariant.Tonal,
                size = SeekerButtonSize.Sm,
                modifier = trailingModifier,
            )
        }
    }
}

private const val SectionHeaderDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun SectionHeaderPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "section-header", variant = "trailing=button")
@Preview(
    name = "section-header/trailing-button",
    widthDp = 358,
    uiMode = SectionHeaderDarkMode,
)
@Composable
private fun SectionHeaderButtonPreview() {
    SectionHeaderPreviewSurface {
        SectionHeader(
            title = "Waiting for you",
            trailing = SectionHeaderTrailing.Button,
            trailingLabel = "5 · see all",
            onTrailingClick = {},
        )
    }
}

@DesignRef(component = "section-header", variant = "trailing=none")
@Preview(
    name = "section-header/trailing-none",
    widthDp = 358,
    uiMode = SectionHeaderDarkMode,
)
@Composable
private fun SectionHeaderNonePreview() {
    SectionHeaderPreviewSurface {
        SectionHeader(title = "Rules", trailing = SectionHeaderTrailing.None)
    }
}
