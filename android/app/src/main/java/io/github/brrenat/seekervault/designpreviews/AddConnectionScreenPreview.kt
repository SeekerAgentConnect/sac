package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.connections.AddConnectionScreen
import io.github.brrenat.seekervault.connections.AddConnectionScreenCallbacks
import io.github.brrenat.seekervault.connections.AddConnectionScreenState
import io.github.brrenat.seekervault.connections.AddConnectionState
import io.github.brrenat.seekervault.connections.CameraAccess
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@DesignRef(component = "screens", variant = "add")
@Preview(name = "screens/add", widthDp = 390, heightDp = 844, uiMode = DarkMode)
@Composable
private fun AddConnectionScreenPreview() {
    SeekerTheme(darkTheme = true) {
        AddConnectionScreen(
            state =
                AddConnectionScreenState(
                    adding = AddConnectionState.Idle,
                    codeDraft = "",
                    camera = CameraAccess.Idle,
                ),
            callbacks =
                AddConnectionScreenCallbacks(
                    onScan = {},
                    onStopScanning = {},
                    onOpenSettings = {},
                    onCodeDraftChange = {},
                    onCode = {},
                    onConfirmPairing = {},
                    onConfirmFeed = {},
                    onConfirmInvitation = {},
                    onOpenFeed = {},
                    onCancel = {},
                    onBack = {},
                    navigation =
                        ScreenNavigationCallbacks(
                            onHome = {},
                            onInbox = {},
                            onWallet = {},
                            onActivity = {},
                        ),
                ),
            scanner = {},
        )
    }
}
