package io.github.brrenat.seekervault.designsystem.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun DesignSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "token-colour", variant = "--surf")
@Preview(name = "token-colour/--surf", uiMode = DarkMode)
@Composable
internal fun ThemeSwatchPreview() {
    DesignSurface {
        // The sole legacy-dimension exception in :designsystem: this diagnostic must reproduce the
        // HTML specimen's 1dp border, but tokens.json has no matching named spacing or size token.
        // Keep the exception local and visible instead of inventing a reusable 1dp token.
        @Suppress("DEPRECATION") val diagnosticBorderWidth = SeekerTheme.dimensions.dp1
        // The HTML specimen uses CSS content-box sizing: 64×48 plus a 1px border on every side.
        Box(
            Modifier.size(
                    width =
                        SeekerTheme.spacing.jumbo +
                            SeekerTheme.spacing.jumbo +
                            diagnosticBorderWidth +
                            diagnosticBorderWidth,
                    height =
                        SeekerTheme.sizes.button.large.height +
                            diagnosticBorderWidth +
                            diagnosticBorderWidth,
                )
                .clip(MaterialTheme.shapes.medium)
                .background(SeekerTheme.colors.surface0)
                .border(
                    width = diagnosticBorderWidth,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.medium,
                )
                .testTag("theme-swatch")
        )
    }
}

@DesignRef(component = "size-probe", variant = "height=48dp")
@Preview(name = "size-probe/height=48dp", uiMode = DarkMode)
@Composable
internal fun SizeProbePreview() {
    DesignSurface {
        Box(
            Modifier.size(SeekerTheme.sizes.button.large.height)
                .background(SeekerTheme.colors.lime)
                .testTag("size-probe")
        )
    }
}

@Composable
private fun FontProbeLine(label: String, style: TextStyle, tag: String) {
    Row {
        Text(text = "$label · ", style = MaterialTheme.typography.bodyMedium)
        Text(text = "Seeker", style = style, modifier = Modifier.testTag(tag))
    }
}

@DesignRef(component = "font-weight-probe", variant = "roboto=400-500-700")
@Preview(name = "font-weight-probe/roboto=400-500-700", widthDp = 358, uiMode = DarkMode)
@Composable
internal fun FontWeightProbe() {
    DesignSurface {
        val body = MaterialTheme.typography.bodyLarge
        Column {
            FontProbeLine("Roboto 400", body.copy(fontWeight = FontWeight.Normal), "roboto-400")
            FontProbeLine("Roboto 500", body.copy(fontWeight = FontWeight.Medium), "roboto-500")
            FontProbeLine("Roboto 700", body.copy(fontWeight = FontWeight.Bold), "roboto-700")
            Row {
                Text(text = "Roboto Mono 400 · ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "iiii",
                    style = SeekerTheme.typography.identifier,
                    modifier = Modifier.testTag("mono-iiii"),
                )
                Text(text = " / ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "MMMM",
                    style = SeekerTheme.typography.identifier,
                    modifier = Modifier.testTag("mono-MMMM"),
                )
            }
            FontProbeLine(
                label = "Platform default 400",
                style = body.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal),
                tag = "platform-default",
            )
        }
    }
}

@DesignRef(component = "icon-probe", variant = "material-icons-outlined")
@Preview(name = "icon-probe/material-icons-outlined", uiMode = DarkMode)
@Composable
internal fun IconProbePreview() {
    DesignSurface {
        val iconModifier = Modifier.size(SeekerTheme.sizes.iconButton.large.glyph)
        val tint = MaterialTheme.colorScheme.onSurface
        Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "close",
                modifier = iconModifier,
                tint = tint,
            )
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "content_copy",
                modifier = iconModifier,
                tint = tint,
            )
            Icon(
                Icons.Outlined.Toll,
                contentDescription = "toll",
                modifier = iconModifier,
                tint = tint,
            )
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "refresh",
                modifier = iconModifier,
                tint = tint,
            )
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "delete",
                modifier = iconModifier,
                tint = tint,
            )
        }
    }
}
