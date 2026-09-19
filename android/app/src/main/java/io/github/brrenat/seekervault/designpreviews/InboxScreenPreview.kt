package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.InboxRowKind
import io.github.brrenat.seekervault.designsystem.InboxRowModel
import io.github.brrenat.seekervault.designsystem.InboxRowOrigin
import io.github.brrenat.seekervault.designsystem.InboxRowVerdict
import io.github.brrenat.seekervault.designsystem.InboxTab
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.InboxPendingRowState
import io.github.brrenat.seekervault.inbox.InboxScreen
import io.github.brrenat.seekervault.inbox.InboxScreenCallbacks
import io.github.brrenat.seekervault.inbox.InboxScreenState

@DesignRef(component = "screens", variant = "requests")
@Preview(
    name = "screens/requests",
    widthDp = InboxPreviewWidth,
    heightDp = InboxPreviewHeight,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun InboxScreenDesignPreview() {
    SeekerTheme(darkTheme = true) {
        InboxScreen(state = inboxDesignFixture(), callbacks = inboxPreviewCallbacks())
    }
}

private fun inboxDesignFixture() =
    InboxScreenState(
        selectedTab = InboxTab.Pending,
        pending =
            listOf(
                InboxPendingRowState(
                    id = "ack",
                    model =
                        InboxRowModel(
                            title = "Acknowledge a message",
                            supportingText = "“Still here?” · nothing is signed",
                            sourceName = "studio-mac",
                            timestampAndExpiryText = "9:39 PM · expires in 6 hours",
                            environmentText = "Production",
                            networkText = null,
                            warningCount = 0,
                        ),
                    kind = InboxRowKind.Acknowledgement,
                    origin = InboxRowOrigin.Request,
                    verdict = InboxRowVerdict.Ok,
                ),
                InboxPendingRowState(
                    id = "prediction",
                    model =
                        InboxRowModel(
                            title = "Bitcoin under \$68,000",
                            supportingText = "you pick the side and the stake",
                            sourceName = "Jupiter Prediction demo",
                            timestampAndExpiryText = "9:37 PM · expires in 2 hours",
                            environmentText = "Sandbox · no funds will move",
                            networkText = "Solana devnet",
                            warningCount = 1,
                        ),
                    kind = InboxRowKind.Prediction,
                    origin = InboxRowOrigin.Signal,
                    verdict = InboxRowVerdict.Warning,
                ),
                InboxPendingRowState(
                    id = "transfer",
                    model =
                        InboxRowModel(
                            title = "Send 5 SOL",
                            supportingText = "funds move",
                            sourceName = "studio-mac",
                            timestampAndExpiryText = "9:36 PM · expires in 23 hours",
                            environmentText = "Production",
                            networkText = "Solana devnet",
                            warningCount = 1,
                        ),
                    kind = InboxRowKind.Transfer,
                    origin = InboxRowOrigin.Request,
                    verdict = InboxRowVerdict.Warning,
                ),
                InboxPendingRowState(
                    id = "swap",
                    model =
                        InboxRowModel(
                            title = "Swap SOL for USDC",
                            supportingText = "you set the amount",
                            sourceName = "CopyTrading demo",
                            timestampAndExpiryText = "9:34 PM · expires in 2 hours",
                            environmentText = "Sandbox · no funds will move",
                            networkText = "Solana devnet",
                            warningCount = 1,
                        ),
                    kind = InboxRowKind.Swap,
                    origin = InboxRowOrigin.Signal,
                    verdict = InboxRowVerdict.Warning,
                ),
                InboxPendingRowState(
                    id = "signature",
                    model =
                        InboxRowModel(
                            title = "Sign a message",
                            supportingText = "74 bytes · no funds move",
                            sourceName = "hermes-box",
                            timestampAndExpiryText = "9:12 PM · expires in 23 hours",
                            environmentText = "Production",
                            networkText = "Solana mainnet",
                            warningCount = 0,
                        ),
                    kind = InboxRowKind.Signature,
                    origin = InboxRowOrigin.Request,
                    verdict = InboxRowVerdict.Ok,
                ),
            ),
        history = emptyList(),
    )

private fun inboxPreviewCallbacks() =
    InboxScreenCallbacks(
        onSelectTab = {},
        onReview = {},
        onOpenHistory = {},
        navigation = ScreenNavigationCallbacks({}, {}, {}, {}),
    )

private const val InboxPreviewWidth = 390
private const val InboxPreviewHeight = 844
