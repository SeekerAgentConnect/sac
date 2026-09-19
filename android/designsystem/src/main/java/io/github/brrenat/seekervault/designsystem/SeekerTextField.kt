package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class DesignTextFieldState {
    Rest,
    Error,
}

@Composable
fun SeekerTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    state: DesignTextFieldState,
    errorMessage: String? = null,
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    reserveErrorSpace: Boolean = true,
    inputModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val error = state == DesignTextFieldState.Error
    val labelColor =
        if (error) SeekerTheme.colors.errorText else MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor =
        if (error) SeekerTheme.colors.errorText else MaterialTheme.colorScheme.onSurfaceVariant
    val lineWidth = if (error) SeekerTheme.spacing.xxs else SeekerTheme.spacing.xxs / 2
    val fieldShape =
        RoundedCornerShape(
            topStart = SeekerTheme.radii.xs,
            topEnd = SeekerTheme.radii.xs,
        )

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(fieldShape)
                    .background(SeekerTheme.colors.surface3)
                    .drawBottomLine(lineColor = lineColor, lineWidth = lineWidth)
                    .padding(
                        horizontal = SeekerTheme.spacing.xl,
                        vertical = SeekerTheme.spacing.md,
                    )
        ) {
            Text(
                text = label,
                color = labelColor,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(top = SeekerTheme.spacing.xxs)
                        .then(inputModifier),
                singleLine = true,
                keyboardOptions = keyboardOptions,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                cursorBrush = SolidColor(SeekerTheme.colors.primaryText),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        if (reserveErrorSpace || errorMessage != null) {
            Box(modifier = Modifier.size(SeekerTheme.spacing.sm))
        }
        if (error && !errorMessage.isNullOrEmpty()) {
            Text(
                text = errorMessage,
                color = SeekerTheme.colors.errorText,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
            )
        }
    }
}

private fun Modifier.drawBottomLine(
    lineColor: Color,
    lineWidth: Dp,
): Modifier =
    then(
        Modifier.drawBehind {
            val strokeWidth = lineWidth.toPx()
            drawLine(
                color = lineColor,
                start = Offset(x = 0f, y = size.height - strokeWidth / 2),
                end = Offset(x = size.width, y = size.height - strokeWidth / 2),
                strokeWidth = strokeWidth,
            )
        }
    )

private const val TextFieldDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun TextFieldPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "text-field", variant = "state=error")
@Preview(name = "text-field/state-error", widthDp = 358, uiMode = TextFieldDarkMode)
@Composable
private fun TextFieldErrorPreview() {
    TextFieldPreviewSurface {
        SeekerTextField(
            label = "Wallet address",
            value = "FyfWsSPW",
            onValueChange = {},
            state = DesignTextFieldState.Error,
            errorMessage = "Not a Solana address. That is base58 for 32 bytes.",
        )
    }
}

@DesignRef(component = "text-field", variant = "state=rest")
@Preview(name = "text-field/state-rest", widthDp = 358, uiMode = TextFieldDarkMode)
@Composable
private fun TextFieldRestPreview() {
    TextFieldPreviewSurface {
        SeekerTextField(
            label = "Amount to swap, in SOL",
            value = "1.5",
            onValueChange = {},
            state = DesignTextFieldState.Rest,
        )
    }
}
