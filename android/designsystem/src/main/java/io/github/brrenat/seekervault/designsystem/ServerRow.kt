package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class ServerRowState {
    Connected,
    Unreachable,
    Disconnected,
}

data class ServerRowModel(
    val sourceName: String,
    val initials: String,
    val statusText: String,
)

@Composable
fun ServerRow(
    model: ServerRowModel,
    state: ServerRowState,
    onOpen: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rowModifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SeekerTheme.radii.lg))
            .background(SeekerTheme.colors.surface1)
            .let { base ->
                if (onOpen != null) {
                    base.clickable(role = Role.Button, onClick = onOpen).semantics {
                        contentDescription = "Open ${model.sourceName}"
                    }
                } else {
                    base
                }
            }
            .padding(
                horizontal = SeekerTheme.spacing.xl,
                vertical = SeekerTheme.spacing.lgPlus,
            )
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceAvatar(sourceName = model.sourceName, initials = model.initials)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            Text(text = model.sourceName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = model.statusText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (state) {
            ServerRowState.Connected ->
                onOpen?.let {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            ServerRowState.Unreachable ->
                onRetry?.let { retry ->
                    OrganismIconAction(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Retry ${model.sourceName}",
                        onClick = retry,
                        size = OrganismIconActionSize.Medium,
                        style = OrganismIconActionStyle.Neutral,
                    )
                }
            ServerRowState.Disconnected -> Unit
        }
    }
}

private const val ServerRowPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun ServerRowPreview(
    model: ServerRowModel,
    state: ServerRowState,
    onOpen: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            ServerRow(
                model = model,
                state = state,
                onOpen = onOpen,
                onRetry = onRetry,
            )
        }
    }
}

@DesignRef(component = "server-row", variant = "state=connected")
@Preview(name = "server-row/state-connected", widthDp = 358, uiMode = ServerRowPreviewDarkMode)
@Composable
internal fun ServerRowConnectedPreview() =
    ServerRowPreview(
        model =
            ServerRowModel(
                sourceName = "studio-mac",
                initials = "SM",
                statusText = "Connected · 2 pending",
            ),
        state = ServerRowState.Connected,
        onOpen = {},
    )

@DesignRef(component = "server-row", variant = "state=disconnected")
@Preview(
    name = "server-row/state-disconnected",
    widthDp = 358,
    uiMode = ServerRowPreviewDarkMode,
)
@Composable
internal fun ServerRowDisconnectedPreview() =
    ServerRowPreview(
        model =
            ServerRowModel(
                sourceName = "runner-node",
                initials = "RN",
                statusText = "Disconnected · pair again to reconnect",
            ),
        state = ServerRowState.Disconnected,
    )

@DesignRef(component = "server-row", variant = "state=unreachable")
@Preview(
    name = "server-row/state-unreachable",
    widthDp = 358,
    uiMode = ServerRowPreviewDarkMode,
)
@Composable
internal fun ServerRowUnreachablePreview() =
    ServerRowPreview(
        model =
            ServerRowModel(
                sourceName = "hermes-box",
                initials = "HB",
                statusText = "Couldn’t reach the server · 9:48 PM",
            ),
        state = ServerRowState.Unreachable,
        onRetry = {},
    )
