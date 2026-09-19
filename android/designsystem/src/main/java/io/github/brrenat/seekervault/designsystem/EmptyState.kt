package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class EmptyStateScreen {
    Inbox,
    Rules,
}

@Composable
fun EmptyState(
    screen: EmptyStateScreen,
    title: String?,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
    ) {
        title?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize
                ),
        )
    }
}

private const val EmptyStateDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun EmptyStatePreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "empty-state", variant = "screen=inbox")
@Preview(name = "empty-state/screen-inbox", widthDp = 358, uiMode = EmptyStateDarkMode)
@Composable
private fun EmptyStateInboxPreview() {
    EmptyStatePreviewSurface {
        EmptyState(
            screen = EmptyStateScreen.Inbox,
            title = "Nothing is waiting for you",
            body =
                "Everything you answered, dismissed, or that expired or was cancelled, is " +
                    "under History. Clear the source filter to see the whole inbox.",
        )
    }
}

@DesignRef(component = "empty-state", variant = "screen=rules")
@Preview(name = "empty-state/screen-rules", widthDp = 358, uiMode = EmptyStateDarkMode)
@Composable
private fun EmptyStateRulesPreview() {
    EmptyStatePreviewSurface {
        EmptyState(
            screen = EmptyStateScreen.Rules,
            title = "Nothing listed yet.",
            body = "On with an empty list, so nothing matches and every request is flagged.",
        )
    }
}
