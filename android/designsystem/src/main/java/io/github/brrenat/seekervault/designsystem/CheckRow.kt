package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
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

enum class CheckRowState {
    Checked,
    Unchecked,
}

enum class CheckRowContentKind {
    Standard,
    WarningAcknowledgement,
}

@Composable
fun CheckRow(
    label: String,
    state: CheckRowState,
    onStateChange: (CheckRowState) -> Unit,
    contentKind: CheckRowContentKind = CheckRowContentKind.Standard,
    modifier: Modifier = Modifier,
) {
    val spacing = SeekerTheme.spacing
    val isChecked = state == CheckRowState.Checked
    val warningHeight = spacing.huge + spacing.huge
    val contentHeight =
        if (contentKind == CheckRowContentKind.WarningAcknowledgement) {
            Modifier.heightIn(min = warningHeight)
        } else {
            Modifier
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(contentHeight)
                .clip(RoundedCheckRowShape())
                .background(SeekerTheme.colors.surface1)
                .toggleable(
                    value = isChecked,
                    role = Role.Checkbox,
                    onValueChange = {
                        onStateChange(if (it) CheckRowState.Checked else CheckRowState.Unchecked)
                    },
                )
                .padding(horizontal = spacing.lgPlus, vertical = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                if (isChecked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            modifier = Modifier.size(spacing.xxl + spacing.xxs),
            tint =
                if (isChecked) SeekerTheme.colors.primaryText
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = spacing.lg).weight(1f),
            style =
                if (contentKind == CheckRowContentKind.WarningAcknowledgement) {
                    MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
        )
    }
}

@Composable
private fun RoundedCheckRowShape() =
    androidx.compose.foundation.shape.RoundedCornerShape(SeekerTheme.radii.lg)

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun CheckRowPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "check-row", variant = "state=checked")
@Preview(name = "check-row/state-checked", widthDp = 358, uiMode = DarkMode)
@Composable
private fun CheckRowCheckedPreview() {
    CheckRowPreviewSurface {
        CheckRow(
            label = "Expected",
            state = CheckRowState.Checked,
            onStateChange = {},
        )
    }
}

@DesignRef(component = "check-row", variant = "state=unchecked")
@Preview(name = "check-row/state-unchecked", widthDp = 358, uiMode = DarkMode)
@Composable
private fun CheckRowUncheckedPreview() {
    CheckRowPreviewSurface {
        CheckRow(
            label = "Not expected",
            state = CheckRowState.Unchecked,
            onStateChange = {},
        )
    }
}

@DesignRef(component = "check-row", variant = "state=unchecked label=warning-ack")
@Preview(
    name = "check-row/state-unchecked-label-warning-ack",
    widthDp = 358,
    uiMode = DarkMode,
)
@Composable
private fun CheckRowWarningAcknowledgementPreview() {
    CheckRowPreviewSurface {
        CheckRow(
            label = "I have read all 3 warnings and want to approve anyway",
            state = CheckRowState.Unchecked,
            onStateChange = {},
            contentKind = CheckRowContentKind.WarningAcknowledgement,
        )
    }
}
