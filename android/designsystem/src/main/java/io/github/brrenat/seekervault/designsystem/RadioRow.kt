package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class RadioRowState {
    On,
    Off,
}

@Composable
fun RadioRow(
    label: String,
    state: RadioRowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = SeekerTheme.spacing
    val isOn = state == RadioRowState.On
    Row(
        modifier = modifier.selectable(selected = isOn, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(spacing.xxxl)
                    .background(SeekerTheme.colors.surface1, CircleShape)
                    .border(
                        width = spacing.xxs,
                        color =
                            if (isOn) SeekerTheme.colors.primaryText
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(spacing.mdPlus)
                    .background(
                        if (isOn) SeekerTheme.colors.primaryText else SeekerTheme.colors.surface1,
                        CircleShape,
                    )
            )
        }
        Text(
            text = label,
            modifier = Modifier.padding(start = spacing.lg),
            style = SeekerTheme.typography.buttonLarge.copy(fontWeight = FontWeight.Normal),
        )
    }
}

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun RadioRowPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "radio-row", variant = "state=off")
@Preview(name = "radio-row/state-off", uiMode = DarkMode)
@Composable
internal fun RadioRowOffPreview() {
    RadioRowPreviewSurface {
        RadioRow(label = "Another token", state = RadioRowState.Off, onClick = {})
    }
}

@DesignRef(component = "radio-row", variant = "state=on")
@Preview(name = "radio-row/state-on", uiMode = DarkMode)
@Composable
internal fun RadioRowOnPreview() {
    RadioRowPreviewSurface {
        RadioRow(label = "Native SOL", state = RadioRowState.On, onClick = {})
    }
}
