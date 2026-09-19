package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

@Composable
fun FilterBar(
    sourceName: String,
    visibleCount: Int,
    totalCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(
                    start = SeekerTheme.spacing.xl,
                    top = SeekerTheme.spacing.lg,
                    end = SeekerTheme.spacing.lg,
                    bottom = SeekerTheme.spacing.lg,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.FilterAlt,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxl),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
        ) {
            SourceChip(sourceName = sourceName, size = SourceChipSize.Compact)
            Text(
                text = "$visibleCount of $totalCount in the inbox",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SeekerButton(
            label = "Clear",
            onClick = onClear,
            variant = SeekerButtonVariant.Tonal,
            size = SeekerButtonSize.Sm,
        )
    }
}

private const val FilterBarDarkMode = Configuration.UI_MODE_NIGHT_YES

@DesignRef(component = "filter-bar", variant = "src=studio-mac")
@Preview(name = "filter-bar/src-studio-mac", widthDp = 358, uiMode = FilterBarDarkMode)
@Composable
internal fun FilterBarStudioMacPreview() {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            FilterBar(
                sourceName = "studio-mac",
                visibleCount = 2,
                totalCount = 5,
                onClear = {},
            )
        }
    }
}
