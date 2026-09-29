package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.wallet.WalletAddState
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletNetworkOption
import io.github.brrenat.seekervault.wallet.WalletPublishWarningState
import io.github.brrenat.seekervault.wallet.WalletScreen
import io.github.brrenat.seekervault.wallet.WalletScreenCallbacks
import io.github.brrenat.seekervault.wallet.WalletScreenProfile
import io.github.brrenat.seekervault.wallet.WalletScreenState

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
                    title = "Wallets",
                    profiles =
                        listOf(
                            WalletScreenProfile(
                                id = "preview",
                                name = "renatnomad.skr",
                                address = WalletReferenceAddress,
                                statusText = "Devnet · Seed Vault · 8:34 PM",
                                usage = "Used by 2 connections",
                                renameLabel = "Rename",
                                reconnectLabel = "Reconnect",
                                removeLabel = "Remove",
                            )
                        ),
                    add =
                        WalletAddState(
                            title = "Add a wallet",
                            explanation =
                                "Your wallet asks which account to authorize. Adding the same " +
                                    "account on another network makes a separate profile.",
                            appTitle = "Wallet app",
                            appExplanation = "",
                            networkTitle = "Network",
                            networks =
                                WalletNetwork.entries.map {
                                    WalletNetworkOption(it, it.name, it == WalletNetwork.Mainnet)
                                },
                            connectLabel = "Add wallet",
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
                ),
            callbacks =
                WalletScreenCallbacks(
                    onChooseWalletApp = {},
                    onChooseNetwork = {},
                    onConnect = {},
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
