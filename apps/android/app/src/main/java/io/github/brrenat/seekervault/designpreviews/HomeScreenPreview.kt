package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.connections.HomeScreen
import io.github.brrenat.seekervault.connections.HomeScreenCallbacks
import io.github.brrenat.seekervault.connections.HomeScreenState
import io.github.brrenat.seekervault.connections.HomeServerState
import io.github.brrenat.seekervault.connections.HomeWalletState
import io.github.brrenat.seekervault.designsystem.RequestCarouselItem
import io.github.brrenat.seekervault.designsystem.RequestTileKind
import io.github.brrenat.seekervault.designsystem.RequestTileModel
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ServerRowModel
import io.github.brrenat.seekervault.designsystem.ServerRowState
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

@DesignRef(component = "screens", variant = "home")
@Preview(
    name = "screens/home",
    widthDp = HomePreviewWidth,
    heightDp = HomePreviewHeight,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeScreenDesignPreview() {
    SeekerTheme(darkTheme = true) {
        HomeScreen(state = homeDesignFixture(), callbacks = homePreviewCallbacks())
    }
}

internal fun homeDesignFixture() =
    HomeScreenState(
        wallet =
            HomeWalletState(
                name = "renatnomad.skr",
                address = "Bzy2LsonMmTZmLpKX3dAZ4NLaEqTQs772CzUm2B16K54",
                canCopy = true,
            ),
        pendingCount = 5,
        pending =
            listOf(
                RequestCarouselItem(
                    id = "ack",
                    kind = RequestTileKind.Acknowledgement,
                    tile =
                        RequestTileModel(
                            title = "Still here?",
                            sourceName = "studio-mac",
                            time = "9:41 PM",
                            warningCount = 0,
                        ),
                ),
                RequestCarouselItem(
                    id = "prediction",
                    kind = RequestTileKind.PredictionSignal,
                    tile =
                        RequestTileModel(
                            title = "What price will Bitcoin hit on September 25?",
                            sourceName = "Jupiter Prediction demo",
                            time = "9:37 PM",
                            warningCount = 1,
                        ),
                ),
                RequestCarouselItem(
                    id = "transfer",
                    kind = RequestTileKind.Transfer,
                    tile =
                        RequestTileModel(
                            title = "5 SOL",
                            sourceName = "studio-mac",
                            time = "9:36 PM",
                            warningCount = 3,
                        ),
                ),
                RequestCarouselItem(
                    id = "swap",
                    kind = RequestTileKind.SwapSignal,
                    tile =
                        RequestTileModel(
                            title = "SOL → USDC",
                            sourceName = "CopyTrading demo",
                            time = "9:30 PM",
                            warningCount = 1,
                        ),
                ),
                RequestCarouselItem(
                    id = "signature",
                    kind = RequestTileKind.SignatureRequest,
                    tile =
                        RequestTileModel(
                            title = "74 bytes",
                            sourceName = "hermes-box",
                            time = "9:12 PM",
                            warningCount = 0,
                        ),
                ),
            ),
        serversLoaded = true,
        servers =
            listOf(
                HomeServerState(
                    id = "studio-mac",
                    model =
                        ServerRowModel(
                            sourceName = "studio-mac",
                            initials = "SM",
                            statusText = "Connected · 2 pending",
                        ),
                    rowState = ServerRowState.Connected,
                ),
                HomeServerState(
                    id = "hermes-box",
                    model =
                        ServerRowModel(
                            sourceName = "hermes-box",
                            initials = "HB",
                            statusText = "Couldn’t reach the server · 9:48 PM",
                        ),
                    rowState = ServerRowState.Unreachable,
                ),
                HomeServerState(
                    id = "runner-node",
                    model =
                        ServerRowModel(
                            sourceName = "runner-node",
                            initials = "RN",
                            statusText = "Disconnected · pair again to reconnect",
                        ),
                    rowState = ServerRowState.Disconnected,
                ),
            ),
    )

internal fun homePreviewCallbacks() =
    HomeScreenCallbacks(
        onWallet = {},
        onCopyWalletAddress = {},
        onSeeAll = {},
        onPending = {},
        onGlobalRules = {},
        onServer = {},
        onRetryServer = {},
        onAddConnection = {},
        navigation = ScreenNavigationCallbacks({}, {}, {}, {}),
    )

private const val HomePreviewWidth = 390
private const val HomePreviewHeight = 844
