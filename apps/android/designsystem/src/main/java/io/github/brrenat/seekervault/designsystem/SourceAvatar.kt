package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

@Composable
fun SourceAvatar(
    sourceName: String,
    initials: String,
    colour: SourceColour? = null,
    modifier: Modifier = Modifier,
) {
    val colors =
        if (colour == null) sourcePaletteColors(sourceName) else sourcePaletteColors(colour)
    Box(
        modifier =
            modifier
                .size(SeekerTheme.sizes.iconButton.medium.box)
                .background(colors.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = colors.content,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun SourceAvatarPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "source-avatar", variant = "src=hermes-box")
@Preview(name = "source-avatar/src-hermes-box", uiMode = DarkMode)
@Composable
internal fun SourceAvatarHermesBoxPreview() {
    SourceAvatarPreviewSurface {
        SourceAvatar(sourceName = "hermes-box", initials = "HB")
    }
}

@DesignRef(component = "source-avatar", variant = "src=runner-node")
@Preview(name = "source-avatar/src-runner-node", uiMode = DarkMode)
@Composable
internal fun SourceAvatarRunnerNodePreview() {
    SourceAvatarPreviewSurface {
        SourceAvatar(sourceName = "runner-node", initials = "RN")
    }
}

@DesignRef(component = "source-avatar", variant = "src=studio-mac")
@Preview(name = "source-avatar/src-studio-mac", uiMode = DarkMode)
@Composable
internal fun SourceAvatarStudioMacPreview() {
    SourceAvatarPreviewSurface {
        SourceAvatar(sourceName = "studio-mac", initials = "SM")
    }
}
