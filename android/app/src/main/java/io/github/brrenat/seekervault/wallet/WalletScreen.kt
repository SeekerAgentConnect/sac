package io.github.brrenat.seekervault.wallet

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.formatInstant

/**
 * The Wallet screen: connect the wallet the owner already has, see which address and network it
 * selected, and disconnect again. Stock Material 3 only. The screen shows a public address and
 * nothing else about the wallet: the authorization stays on the phone, and a key never gets here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    state: WalletUiState,
    onChooseNetwork: (WalletNetwork) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPublishAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wallet_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            val wallet = state.wallet
            Text(
                when {
                    state.connecting -> stringResource(R.string.wallet_connecting)
                    state.disconnecting -> stringResource(R.string.wallet_disconnecting)
                    wallet == null -> stringResource(R.string.wallet_none_title)
                    else ->
                        stringResource(
                            R.string.wallet_row_connected,
                            wallet.address,
                            networkText(wallet.network),
                        )
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp).testTag(WalletTags.STATUS),
            )
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            state.problem?.let { problem ->
                Text(
                    problemText(problem, state.detail),
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag(WalletTags.PROBLEM),
                )
            }
            if (wallet == null) {
                Text(
                    stringResource(R.string.wallet_none_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                NetworkChoice(state, onChooseNetwork)
                Button(
                    onClick = onConnect,
                    enabled = !state.busy,
                    modifier = Modifier.padding(16.dp).testTag(WalletTags.CONNECT),
                ) {
                    Text(stringResource(R.string.wallet_connect))
                }
            } else {
                Field(R.string.wallet_field_address, wallet.address, "address")
                Field(R.string.wallet_field_network, networkText(wallet.network), "network")
                wallet.label?.let { Field(R.string.wallet_field_label, it, "label") }
                Field(
                    R.string.wallet_field_connected_at,
                    formatInstant(wallet.selectedAt),
                    "connectedAt",
                )
                if (!wallet.networkConfirmed) {
                    Text(
                        stringResource(R.string.wallet_network_unconfirmed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.padding(horizontal = 16.dp).testTag(WalletTags.UNCONFIRMED),
                    )
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !state.busy,
                    modifier = Modifier.padding(16.dp).testTag(WalletTags.DISCONNECT),
                ) {
                    Text(stringResource(R.string.wallet_disconnect))
                }
            }
            Published(state, onPublishAgain)
        }
    }
}

/** Which network the owner will connect on. It's fixed once a wallet is connected. */
@Composable
private fun NetworkChoice(state: WalletUiState, onChoose: (WalletNetwork) -> Unit) {
    Text(
        stringResource(R.string.wallet_network_label),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
    )
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (network in WalletNetwork.entries) {
            FilterChip(
                selected = state.network == network,
                onClick = { onChoose(network) },
                enabled = !state.busy,
                label = { Text(networkText(network)) },
                modifier = Modifier.testTag(WalletTags.network(network)),
            )
        }
    }
}

/** What every paired sidecar has been told, and the way to tell them again. */
@Composable
private fun Published(state: WalletUiState, onPublishAgain: () -> Unit) {
    val failed = state.unpublished
    val reachable = state.connections.count { it.usable }
    Text(
        when {
            reachable == 0 -> stringResource(R.string.wallet_published_none)
            failed.isEmpty() ->
                pluralStringResource(R.plurals.wallet_published_all, reachable, reachable)
            else ->
                pluralStringResource(
                    R.plurals.wallet_published_failed,
                    failed.size,
                    failed.size,
                    failed.joinToString { it.label },
                )
        },
        style = MaterialTheme.typography.bodySmall,
        color =
            if (failed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp).testTag(WalletTags.PUBLISHED),
    )
    if (failed.isNotEmpty()) {
        OutlinedButton(
            onClick = onPublishAgain,
            enabled = !state.busy,
            modifier = Modifier.padding(16.dp).testTag(WalletTags.PUBLISH_AGAIN),
        ) {
            Text(stringResource(R.string.wallet_publish_again))
        }
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String, tag: String) {
    ListItem(
        overlineContent = { Text(stringResource(label)) },
        headlineContent = { Text(value) },
        modifier = Modifier.testTag(WalletTags.field(tag)),
    )
}
