package io.github.brrenat.seekervault.wallet

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R

/** Test tags for the Wallet screen's controls. */
object WalletTags {
    const val WALLET = "wallet"
    const val STATUS = "walletStatus"
    const val CONNECT = "walletConnect"
    const val ADD = "walletAdd"
    const val ADD_SHEET = "walletAddSheet"
    const val ADD_PROBLEM = "walletAddProblem"
    const val DISCONNECT = "walletDisconnect"
    const val PROBLEM = "walletProblem"
    const val PUBLISHED = "walletPublished"
    const val PUBLISH_AGAIN = "walletPublishAgain"
    const val UNCONFIRMED = "walletNetworkUnconfirmed"

    fun network(network: WalletNetwork) = "walletNetwork:${network.name}"

    /** One installed wallet app the owner can pick (SEE-159). */
    fun app(packageName: String) = "walletApp:$packageName"

    fun field(name: String) = "walletField:$name"

    /** One saved wallet profile, and its actions (SEE-174). */
    fun profile(id: String) = "walletProfile:$id"

    fun profileWarning(id: String) = "walletProfileWarning:$id"

    fun rename(id: String) = "walletProfileRename:$id"

    fun reconnect(id: String) = "walletProfileReconnect:$id"

    fun remove(id: String) = "walletProfileRemove:$id"

    /** A connection's wallet picker (SEE-174). */
    const val PICKER = "walletPicker"
    const val PICKER_USE = "walletPickerUse"
    const val PICKER_ADD = "walletPickerAdd"
    const val REMOVE_CONFIRM = "walletProfileRemoveConfirm"

    fun choice(id: String) = "walletPickerChoice:$id"
}

@Composable
fun networkText(network: WalletNetwork): String =
    stringResource(
        when (network) {
            WalletNetwork.Mainnet -> R.string.wallet_network_mainnet
            WalletNetwork.Devnet -> R.string.wallet_network_devnet
            WalletNetwork.Testnet -> R.string.wallet_network_testnet
        }
    )

@Composable
fun problemText(problem: WalletProblem, detail: String?): String {
    val text =
        stringResource(
            when (problem) {
                WalletProblem.NoWallet -> R.string.wallet_problem_no_wallet
                WalletProblem.Declined -> R.string.wallet_problem_declined
                WalletProblem.AuthorizationExpired -> R.string.wallet_problem_expired
                WalletProblem.NetworkUnsupported -> R.string.wallet_problem_network
                WalletProblem.Storage -> R.string.wallet_problem_storage
                WalletProblem.Failed -> R.string.wallet_problem_failed
            }
        )
    return if (problem == WalletProblem.Failed && !detail.isNullOrBlank()) "$text $detail" else text
}
