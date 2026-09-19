package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class InboxTab {
    Pending,
    History,
}

@Composable
fun SeekerTabBar(
    selected: InboxTab,
    onSelect: (InboxTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ruleHeight = SeekerTheme.spacing.xxs / 2
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(SeekerTheme.sizes.button.large.height)) {
            InboxTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .background(SeekerTheme.colors.surface0)
                            .clickable(role = Role.Tab) { onSelect(tab) }
                ) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (tab == InboxTab.Pending) "Pending" else "History",
                            color =
                                if (isSelected) {
                                    SeekerTheme.colors.primaryText
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .height(SeekerTheme.spacing.sm / 2)
                            .background(
                                if (isSelected) {
                                    SeekerTheme.colors.primaryText
                                } else {
                                    SeekerTheme.colors.surface0
                                }
                            )
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .height(ruleHeight)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

private const val TabBarDarkMode = Configuration.UI_MODE_NIGHT_YES

@DesignRef(component = "tab-bar", variant = "selected=pending")
@Preview(name = "tab-bar/selected-pending", widthDp = 358, uiMode = TabBarDarkMode)
@Composable
private fun TabBarPendingPreview() {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            SeekerTabBar(selected = InboxTab.Pending, onSelect = {})
        }
    }
}
