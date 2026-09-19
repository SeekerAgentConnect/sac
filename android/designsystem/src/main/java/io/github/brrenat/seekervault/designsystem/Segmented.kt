package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class SegmentedCount {
    Two,
    Three,
}

enum class SegmentedUsage {
    Standard,
    RuleMode,
}

@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    count: SegmentedCount,
    usage: SegmentedUsage = SegmentedUsage.Standard,
    modifier: Modifier = Modifier,
) {
    val expectedCount = if (count == SegmentedCount.Two) 2 else 3
    require(options.size == expectedCount) { "$count requires $expectedCount options" }
    require(selectedIndex in options.indices) { "selectedIndex must identify an option" }
    if (usage == SegmentedUsage.RuleMode) {
        require(count == SegmentedCount.Two) { "RuleMode requires two options" }
    }
    val outlineWidth = SeekerTheme.spacing.xxs / 2
    val shape = RoundedCornerShape(SeekerTheme.radii.xl)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SeekerTheme.sizes.button.medium.height)
                .clip(shape)
                .background(SeekerTheme.colors.surface1)
                .border(outlineWidth, MaterialTheme.colorScheme.outline, shape)
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                Box(
                    Modifier.fillMaxHeight()
                        .width(outlineWidth)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
            val selected = index == selectedIndex
            Box(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (selected) {
                                SeekerTheme.colors.limeContainer
                            } else {
                                SeekerTheme.colors.surface1
                            }
                        )
                        .clickable(role = Role.RadioButton) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    color =
                        if (selected) {
                            SeekerTheme.colors.onLimeContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private const val SegmentedDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun SegmentedPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "segmented", variant = "count=2 mode")
@Preview(name = "segmented/count-2-mode", widthDp = 250, uiMode = SegmentedDarkMode)
@Composable
private fun SegmentedTwoModePreview() {
    SegmentedPreviewSurface {
        Segmented(
            options = listOf("Use global", "Override"),
            selectedIndex = 1,
            onSelect = {},
            count = SegmentedCount.Two,
            usage = SegmentedUsage.RuleMode,
        )
    }
}

@DesignRef(component = "segmented", variant = "count=2 selected=0")
@Preview(name = "segmented/count-2-selected-0", widthDp = 250, uiMode = SegmentedDarkMode)
@Composable
private fun SegmentedTwoSelectedPreview() {
    SegmentedPreviewSurface {
        Segmented(
            options = listOf("Yes", "No"),
            selectedIndex = 0,
            onSelect = {},
            count = SegmentedCount.Two,
        )
    }
}

@DesignRef(component = "segmented", variant = "count=3 selected=1")
@Preview(name = "segmented/count-3-selected-1", widthDp = 250, uiMode = SegmentedDarkMode)
@Composable
private fun SegmentedThreeSelectedPreview() {
    SegmentedPreviewSurface {
        Segmented(
            options = listOf("0.1%", "0.5%", "1%"),
            selectedIndex = 1,
            onSelect = {},
            count = SegmentedCount.Three,
        )
    }
}
