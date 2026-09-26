package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class SeekerFabVariant {
    Filled
}

enum class SeekerFabWidth {
    Wrap,
    Full,
}

@Composable
fun SeekerFab(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    variant: SeekerFabVariant = SeekerFabVariant.Filled,
    width: SeekerFabWidth = SeekerFabWidth.Wrap,
    modifier: Modifier = Modifier,
) {
    val colors = variant.colors()
    val widthModifier =
        when (width) {
            SeekerFabWidth.Wrap -> Modifier
            SeekerFabWidth.Full -> Modifier.fillMaxWidth()
        }

    Row(
        modifier =
            modifier
                .then(widthModifier)
                .height(SeekerTheme.spacing.huge + SeekerTheme.spacing.huge)
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(colors.container)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = SeekerTheme.spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.content) {
            Box(
                modifier = Modifier.size(SeekerTheme.spacing.xxxl),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Text(
                text = label,
                color = colors.content,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private data class FabColors(val container: Color, val content: Color)

@Composable
private fun SeekerFabVariant.colors(): FabColors =
    when (this) {
        SeekerFabVariant.Filled -> FabColors(SeekerTheme.colors.lime, SeekerTheme.colors.onLime)
    }

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun FabPreview(
    label: String,
    icon: @Composable () -> Unit,
    width: SeekerFabWidth,
) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            SeekerFab(label = label, icon = icon, onClick = {}, width = width)
        }
    }
}

@DesignRef(component = "fab", variant = "variant=filled icon=add")
@Preview(name = "fab/variant-filled-icon-add", uiMode = DarkMode)
@Composable
internal fun FabFilledAddPreview() {
    FabPreview(
        label = "Add connection",
        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
        width = SeekerFabWidth.Wrap,
    )
}

@DesignRef(component = "fab", variant = "variant=filled width=full")
@Preview(name = "fab/variant-filled-width-full", widthDp = 358, uiMode = DarkMode)
@Composable
internal fun FabFilledFullWidthPreview() {
    FabPreview(
        label = "Scan QR code",
        icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
        width = SeekerFabWidth.Full,
    )
}
