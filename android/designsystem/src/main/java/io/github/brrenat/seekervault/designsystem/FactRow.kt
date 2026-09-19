package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class FactRowValueStyle {
    Plain,
    Mono,
    MonoWrap,
}

@Composable
fun FactRow(
    label: String,
    value: String,
    valueStyle: FactRowValueStyle,
    modifier: Modifier = Modifier,
) {
    val labelModifier =
        if (valueStyle == FactRowValueStyle.MonoWrap && label.any { it.isWhitespace() }) {
            Modifier.widthIn(max = SeekerTheme.spacing.huge + SeekerTheme.spacing.xxxl)
        } else {
            Modifier
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .semantics(mergeDescendants = true) {}
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = (SeekerTheme.spacing.lg + SeekerTheme.spacing.lgPlus) / 2,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = labelModifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign =
                if (valueStyle == FactRowValueStyle.MonoWrap) TextAlign.Start else TextAlign.End,
            softWrap = valueStyle == FactRowValueStyle.MonoWrap,
            style = valueStyle.textStyle(),
        )
    }
}

@Composable
private fun FactRowValueStyle.textStyle(): TextStyle =
    when (this) {
        FactRowValueStyle.Plain -> MaterialTheme.typography.bodyMedium
        FactRowValueStyle.Mono ->
            SeekerTheme.typography.identifier.copy(
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            )
        FactRowValueStyle.MonoWrap ->
            SeekerTheme.typography.identifier.copy(
                lineHeight = SeekerTheme.typography.buttonLarge.lineHeight
            )
    }

private const val FactRowDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun FactRowPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "fact-row", variant = "value=full-address")
@Preview(name = "fact-row/value-full-address", widthDp = 358, uiMode = FactRowDarkMode)
@Composable
internal fun FactRowFullAddressPreview() {
    FactRowPreviewSurface {
        FactRow(
            label = "Recipient",
            value = "FyfWsSPWUHbFWA5iwtgujuHbmaJxyCvrYSxLobvYSpEA",
            valueStyle = FactRowValueStyle.MonoWrap,
        )
    }
}

@DesignRef(component = "fact-row", variant = "value=longest wraps")
@Preview(name = "fact-row/value-longest-wraps", widthDp = 358, uiMode = FactRowDarkMode)
@Composable
internal fun FactRowLongestWrapsPreview() {
    FactRowPreviewSurface {
        FactRow(
            label = "Server ID",
            value = "0b83547c-b1c8-4663-b0c6-04e54a9f5f9c",
            valueStyle = FactRowValueStyle.MonoWrap,
        )
    }
}

@DesignRef(component = "fact-row", variant = "value=mono")
@Preview(name = "fact-row/value-mono", widthDp = 358, uiMode = FactRowDarkMode)
@Composable
internal fun FactRowMonoPreview() {
    FactRowPreviewSurface {
        FactRow(
            label = "Wallet",
            value = "Bzy2Lson…2B16K54",
            valueStyle = FactRowValueStyle.Mono,
        )
    }
}

@DesignRef(component = "fact-row", variant = "value=short")
@Preview(name = "fact-row/value-short", widthDp = 358, uiMode = FactRowDarkMode)
@Composable
internal fun FactRowShortPreview() {
    FactRowPreviewSurface {
        FactRow(label = "From", value = "studio-mac", valueStyle = FactRowValueStyle.Plain)
    }
}
