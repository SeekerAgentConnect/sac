package io.github.brrenat.seekervault.operations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.designsystem.ConnectionWalletPickerSheet
import io.github.brrenat.seekervault.designsystem.WalletPickerRowModel
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletTags
import io.github.brrenat.seekervault.wallet.WalletUiState
import io.github.brrenat.seekervault.wallet.networkText
import io.github.brrenat.seekervault.wallet.profileName

/**
 * Choosing the wallet a feed signal's operation uses (SEE-185, docs/guides/wallet-setup.md).
 *
 * The same sheet a connection's wallet is chosen with, asked a different question: which saved
 * profile on [network] — the network the signal declares — signs this feed's operations there.
 * Every saved profile stays visible so the owner can see what exists; one on another network, or
 * one whose authorization needs reconnecting, can't be chosen. Adding opens the shared add-wallet
 * sheet preset to [network]. Nothing changes until the owner chooses Use this wallet, and the
 * choice never touches who may read the feed.
 */
@Composable
fun OperationWalletPicker(
    network: WalletNetwork,
    current: String?,
    wallet: WalletUiState,
    onUse: (String) -> Unit,
    onAdd: (WalletNetwork) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable(network) { mutableStateOf(current) }
    // A profile the owner just added on this network is the one they meant.
    LaunchedEffect(wallet.added) {
        wallet.added
            .firstOrNull { id ->
                wallet.profiles.any { it.id == id && it.network == network && it.authorized }
            }
            ?.let { chosen = it }
    }
    val busy = wallet.busy
    val networkName = networkText(network)
    ConnectionWalletPickerSheet(
        title = stringResource(R.string.operation_wallet_sheet_title, networkName),
        declaredNetwork = networkName,
        declaredNetworkLabel = stringResource(R.string.operation_wallet_sheet_note),
        noNetworkTitle = stringResource(R.string.connection_wallet_picker_no_network_title),
        noNetworkMessage = stringResource(R.string.connection_wallet_picker_no_networks),
        rows =
            wallet.profiles.map { profile ->
                val selectable = profile.network == network && profile.authorized
                WalletPickerRowModel(
                    id = profile.id,
                    name = profileName(profile),
                    shortAddress =
                        profile.address.take(SHORT_ADDRESS) +
                            "…" +
                            profile.address.takeLast(SHORT_ADDRESS),
                    network = networkText(profile.network),
                    note =
                        when {
                            profile.network != network ->
                                stringResource(
                                    R.string.connection_wallet_picker_blocked,
                                    networkText(profile.network),
                                )
                            !profile.authorized ->
                                stringResource(R.string.connection_wallet_state_reconnect)
                            else ->
                                stringResource(
                                    R.string.connection_wallet_picker_opens_in,
                                    profile.walletApp
                                        ?: stringResource(R.string.wallet_profile_unknown_app),
                                )
                        },
                    selected = profile.id == chosen,
                    selectable = selectable && !busy,
                )
            },
        addLabel = stringResource(R.string.wallet_profiles_add_for, networkName),
        cancelLabel = stringResource(R.string.cancel),
        useLabel = stringResource(R.string.connection_wallet_use),
        canUse =
            !busy &&
                wallet.profiles.any {
                    it.id == chosen && it.network == network && it.authorized
                },
        onChoose = { chosen = it },
        onAdd = { onAdd(network) },
        onCancel = onDismiss,
        onUse = { chosen?.let(onUse) },
        modifier = modifier.testTag(WalletTags.PICKER),
        rowModifier = { Modifier.testTag(WalletTags.choice(it)) },
        addModifier = Modifier.testTag(WalletTags.PICKER_ADD),
        cancelModifier = Modifier.testTag(ConnectionsTags.DIALOG_DISMISS),
        useModifier = Modifier.testTag(WalletTags.PICKER_USE),
    )
}

private const val SHORT_ADDRESS = 4
