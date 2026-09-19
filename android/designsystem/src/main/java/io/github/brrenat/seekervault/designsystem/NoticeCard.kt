package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Sync
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

enum class NoticeCardKind {
    Sandbox,
    StaleRules,
}

@Composable
fun NoticeCard(kind: NoticeCardKind, message: String, modifier: Modifier = Modifier) {
    val icon =
        when (kind) {
            NoticeCardKind.Sandbox -> Icons.Outlined.Science
            NoticeCardKind.StaleRules -> Icons.Outlined.Sync
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.orangeContainer)
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = SeekerTheme.spacing.lgPlus,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxl),
            tint = SeekerTheme.colors.onOrangeContainer,
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = SeekerTheme.colors.onOrangeContainer,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize
                ),
        )
    }
}

private const val NoticeCardDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun NoticeCardPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "notice-card", variant = "sandbox-card")
@Preview(name = "notice-card/sandbox-card", widthDp = 358, uiMode = NoticeCardDarkMode)
@Composable
private fun NoticeCardSandboxPreview() {
    NoticeCardPreviewSurface {
        NoticeCard(
            kind = NoticeCardKind.Sandbox,
            message =
                "Sandbox. Everything above is real — the live market, the exact transaction " +
                    "and this phone’s reading of it. The last step is not: no funds will move, " +
                    "nothing is signed and nothing is sent.",
        )
    }
}

@DesignRef(component = "notice-card", variant = "stale-card")
@Preview(name = "notice-card/stale-card", widthDp = 358, uiMode = NoticeCardDarkMode)
@Composable
private fun NoticeCardStalePreview() {
    NoticeCardPreviewSurface {
        NoticeCard(
            kind = NoticeCardKind.StaleRules,
            message =
                "The rules changed while this was open. The assessment below was redone — " +
                    "read it again before approving.",
        )
    }
}
