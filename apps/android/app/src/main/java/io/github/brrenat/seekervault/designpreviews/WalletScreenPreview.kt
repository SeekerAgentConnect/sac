package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.ConnectionWalletPickerSheet
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.WalletPickerRowModel
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.wallet.InstalledWallet
import io.github.brrenat.seekervault.wallet.WalletAddSheetScreen
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletScreen
import io.github.brrenat.seekervault.wallet.WalletScreenCallbacks
import io.github.brrenat.seekervault.wallet.WalletScreenProfile
import io.github.brrenat.seekervault.wallet.WalletScreenState
import io.github.brrenat.seekervault.wallet.WalletUiState

private const val PhantomAddress = "D3QxmK1ouKzJ8vsoWgxzuotSFNLLRe1TtP6a1H5RfrhB"
private const val RenatAddress = "Bzy2LsonMmTZmLpKX3dAZ4NLaEqTQs772CzUm2B16K54"

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
            state = walletScreenPreviewState(),
            callbacks = walletScreenPreviewCallbacks(),
        )
    }
}

@DesignRef(component = "screens", variant = "wallet-add")
@Preview(
    name = "screens/wallet-add",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AddWalletSheetPreview() {
    SeekerTheme(darkTheme = true) {
        SheetPreviewBackground {
            WalletAddSheetScreen(
                state =
                    WalletUiState(
                        apps =
                            listOf(
                                InstalledWallet("wallet", "Wallet"),
                                InstalledWallet("jupiter", "Jupiter"),
                                InstalledWallet("backpack", "Backpack"),
                            ),
                        chosen = InstalledWallet("jupiter", "Jupiter"),
                        network = WalletNetwork.Devnet,
                    ),
                onChooseWalletApp = {},
                onChooseNetwork = {},
                onConnect = {},
                onCancel = {},
            )
        }
    }
}

@DesignRef(component = "screens", variant = "wallet-picker-declared")
@Preview(
    name = "screens/wallet-picker-declared",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DeclaredWalletPickerPreview() {
    SeekerTheme(darkTheme = true) {
        SheetPreviewBackground {
            ConnectionWalletPickerSheet(
                title = "Wallet for Polymarket",
                declaredNetwork = "Devnet",
                declaredNetworkLabel = "This server uses Devnet",
                noNetworkTitle = "No network declared",
                noNetworkMessage = NoNetworkMessage,
                rows =
                    listOf(
                        WalletPickerRowModel(
                            id = "phantom",
                            name = "phantom",
                            shortAddress = "D3Qx…frhB",
                            network = "Mainnet",
                            note = "Mainnet only · switch it on the Wallet tab",
                            selectable = false,
                        ),
                        WalletPickerRowModel(
                            id = "renat",
                            name = "renatnomad.skr",
                            shortAddress = "Bzy2…6K54",
                            network = "Devnet",
                            note = "Opens in Jupiter",
                            selected = true,
                        ),
                    ),
                addLabel = "Add a Devnet wallet",
                cancelLabel = "Cancel",
                useLabel = "Use this wallet",
                canUse = true,
                onChoose = {},
                onAdd = {},
                onCancel = {},
                onUse = {},
            )
        }
    }
}

@DesignRef(component = "screens", variant = "wallet-picker-undeclared")
@Preview(
    name = "screens/wallet-picker-undeclared",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun UndeclaredWalletPickerPreview() {
    SeekerTheme(darkTheme = true) {
        SheetPreviewBackground {
            ConnectionWalletPickerSheet(
                title = "Wallet for Market Feed",
                declaredNetwork = null,
                declaredNetworkLabel = null,
                noNetworkTitle = "No network declared",
                noNetworkMessage = NoNetworkMessage,
                rows =
                    listOf(
                        WalletPickerRowModel(
                            id = "phantom",
                            name = "phantom",
                            shortAddress = "D3Qx…frhB",
                            network = "Mainnet",
                        ),
                        WalletPickerRowModel(
                            id = "renat",
                            name = "renatnomad.skr",
                            shortAddress = "Bzy2…6K54",
                            network = "Devnet",
                        ),
                    ),
                addLabel = "Add a wallet",
                cancelLabel = "Cancel",
                useLabel = "Use this wallet",
                canUse = false,
                onChoose = {},
                onAdd = {},
                onCancel = {},
                onUse = {},
            )
        }
    }
}

@Composable
private fun SheetPreviewBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(SeekerTheme.colors.dim),
        contentAlignment = Alignment.BottomCenter,
    ) {
        content()
    }
}

private fun walletScreenPreviewState() =
    WalletScreenState(
        title = "Wallet",
        profiles =
            listOf(
                WalletScreenProfile(
                    id = "phantom",
                    name = "phantom",
                    address = PhantomAddress,
                    shortAddress = "D3Qx…frhB",
                    network = "Mainnet",
                    walletApp = "Wallet",
                    added = "Added 9/29/26, 10:31 PM",
                    usage = "Used by Polymarket",
                    renameLabel = "Rename",
                    reconnectLabel = "Reconnect",
                    removeLabel = "Remove",
                ),
                WalletScreenProfile(
                    id = "renat",
                    name = "renatnomad.skr",
                    address = RenatAddress,
                    shortAddress = "Bzy2…6K54",
                    network = "Devnet",
                    walletApp = "Jupiter",
                    added = "Added 9/29/26, 10:32 PM",
                    usage = "Not used by any connection",
                    renameLabel = "Rename",
                    reconnectLabel = "Reconnect",
                    removeLabel = "Remove",
                ),
            ),
        expandedProfileId = "phantom",
        explanation =
            "This app never sees your seed phrase or keys. It asks the wallet you already use, " +
                "and keeps only the address you pick.",
    )

private fun walletScreenPreviewCallbacks() =
    WalletScreenCallbacks(
        onAddWallet = {},
        onToggleProfile = {},
        onPublishAgain = {},
        onBack = {},
        navigation =
            ScreenNavigationCallbacks(
                onHome = {},
                onInbox = {},
                onWallet = {},
                onActivity = {},
            ),
    )

private const val NoNetworkMessage =
    "This server hasn't declared any Solana network. You can still choose a wallet — a " +
        "restricted feed proves who you are with it — but nothing is signed until the server " +
        "declares one."
