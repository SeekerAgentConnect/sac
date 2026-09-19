package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.wallet.WalletPublishWarningState
import io.github.brrenat.seekervault.wallet.WalletScreen
import io.github.brrenat.seekervault.wallet.WalletScreenCallbacks
import io.github.brrenat.seekervault.wallet.WalletScreenState
import io.github.brrenat.seekervault.wallet.WalletScreenWallet

private const val WalletReferenceAddress = "Bzy2LsonMmTZmLpKX3dAZ4NLaEqTQs772CzUm2B16K54"

@DesignRef(component = "screens", variant = "wallet")
@Preview(
    name = "screens/wallet",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WalletScreenPreview() {
    SeekerTheme(darkTheme = true) {
        WalletScreen(
            state =
                WalletScreenState(
                    title = "Wallet",
                    wallet =
                        WalletScreenWallet(
                            name = "renatnomad.skr",
                            address = WalletReferenceAddress,
                            statusText = "Connected 8:34 PM · Devnet",
                        ),
                    publishWarning =
                        WalletPublishWarningState(
                            serverName = "hermes-box",
                            message =
                                "That server still has the previous address. Until it hears, " +
                                    "its requests will name the wrong wallet.",
                            actionLabel = "Tell them again",
                        ),
                    explanation =
                        "This app never sees your seed phrase or keys. It asks the wallet you " +
                            "already use, and keeps only the address you pick.",
                    disconnectLabel = "Disconnect wallet",
                ),
            callbacks =
                WalletScreenCallbacks(
                    onChooseNetwork = {},
                    onConnect = {},
                    onDisconnect = {},
                    onPublishAgain = {},
                    onBack = {},
                    navigation =
                        ScreenNavigationCallbacks(
                            onHome = {},
                            onInbox = {},
                            onWallet = {},
                            onActivity = {},
                        ),
                ),
        )
    }
}
