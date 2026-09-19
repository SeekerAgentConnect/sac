package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class SwitchRowState {
    On,
    Off,
}

@Composable
fun SwitchRow(
    label: String,
    state: SwitchRowState,
    onStateChange: (SwitchRowState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .toggleable(
                    value = state == SwitchRowState.On,
                    role = Role.Switch,
                    onValueChange = {
                        onStateChange(if (it) SwitchRowState.On else SwitchRowState.Off)
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = SeekerTheme.typography.buttonLarge.copy(fontWeight = FontWeight.Normal),
        )
        SwitchTrack(state)
    }
}

@Composable
private fun SwitchTrack(state: SwitchRowState) {
    val spacing = SeekerTheme.spacing
    val isOn = state == SwitchRowState.On
    val trackWidth = spacing.jumbo + spacing.xxxl
    val trackHeight = spacing.jumbo + spacing.xxs + spacing.xxs
    val trackShape = RoundedCornerShape(SeekerTheme.radii.lg)
    val trackColor = if (isOn) SeekerTheme.colors.lime else SeekerTheme.colors.surface3
    val borderColor = if (isOn) SeekerTheme.colors.lime else MaterialTheme.colorScheme.outline

    Box(
        Modifier.size(width = trackWidth, height = trackHeight)
            .clip(trackShape)
            .background(trackColor)
            .border(width = spacing.xxs, color = borderColor, shape = trackShape)
    ) {
        val knobSize: Dp = if (isOn) spacing.xxxl else spacing.xl
        val knobX: Dp = if (isOn) spacing.xxxl else spacing.md
        val knobY: Dp = if (isOn) spacing.xs else spacing.md
        val knobColor = if (isOn) SeekerTheme.colors.onLime else MaterialTheme.colorScheme.outline
        Box(Modifier.offset(x = knobX, y = knobY).size(knobSize).background(knobColor, CircleShape))
    }
}

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun SwitchRowPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "switch-row", variant = "state=off")
@Preview(name = "switch-row/state-off", widthDp = 358, uiMode = DarkMode)
@Composable
private fun SwitchRowOffPreview() {
    SwitchRowPreviewSurface {
        SwitchRow(
            label = "Only these assets may move",
            state = SwitchRowState.Off,
            onStateChange = {},
        )
    }
}

@DesignRef(component = "switch-row", variant = "state=on")
@Preview(name = "switch-row/state-on", widthDp = 358, uiMode = DarkMode)
@Composable
private fun SwitchRowOnPreview() {
    SwitchRowPreviewSurface {
        SwitchRow(
            label = "Only these assets may move",
            state = SwitchRowState.On,
            onStateChange = {},
        )
    }
}
