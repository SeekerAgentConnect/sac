package io.github.brrenat.seekervault.wallet

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.ui.Identifier
import io.github.brrenat.seekervault.ui.NetworkChip
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard

/** Wallet selection stays manual and delegates every wallet operation to the existing ViewModel. */
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
    val wallet = state.wallet
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Text(
                stringResource(R.string.wallet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            NetworkChip(networkText(wallet?.network ?: state.network))
        }
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SeekerCard(
                Modifier.fillMaxWidth().testTag(WalletTags.STATUS).semantics(
                    mergeDescendants = true
                ) {},
                color =
                    if (wallet != null) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        when {
                            state.connecting -> stringResource(R.string.wallet_connecting)
                            state.disconnecting -> stringResource(R.string.wallet_disconnecting)
                            wallet == null -> stringResource(R.string.wallet_none_title)
                            else -> wallet.label ?: stringResource(R.string.wallet_title)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        if (wallet == null) stringResource(R.string.wallet_none_text)
                        else
                            stringResource(
                                R.string.wallet_row_connected,
                                wallet.address,
                                networkText(wallet.network),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (wallet == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (state.busy) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            state.problem?.let { problem ->
                SeekerCard(
                    Modifier.fillMaxWidth().testTag(WalletTags.PROBLEM).semantics(
                        mergeDescendants = true
                    ) {},
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        problemText(problem, state.detail),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (wallet == null) {
                NetworkChoice(state, onChooseNetwork)
                SeekerButton(
                    text = stringResource(R.string.wallet_connect),
                    onClick = onConnect,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag(WalletTags.CONNECT),
                )
            } else {
                SeekerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Field(R.string.wallet_field_address, wallet.address, "address")
                        Field(R.string.wallet_field_network, networkText(wallet.network), "network")
                        wallet.label?.let { Field(R.string.wallet_field_label, it, "label") }
                        Field(
                            R.string.wallet_field_connected_at,
                            formatInstant(wallet.selectedAt),
                            "connectedAt",
                        )
                    }
                }
                if (!wallet.networkConfirmed) {
                    SeekerCard(
                        Modifier.fillMaxWidth().testTag(WalletTags.UNCONFIRMED).semantics(
                            mergeDescendants = true
                        ) {},
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            stringResource(R.string.wallet_network_unconfirmed),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                SeekerButton(
                    text = stringResource(R.string.wallet_disconnect),
                    onClick = onDisconnect,
                    enabled = !state.busy,
                    role = SeekerButtonRole.Error,
                    modifier = Modifier.fillMaxWidth().testTag(WalletTags.DISCONNECT),
                )
            }
            Published(state, onPublishAgain)
        }
    }
}

@Composable
private fun NetworkChoice(state: WalletUiState, onChoose: (WalletNetwork) -> Unit) {
    SeekerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.wallet_network_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (network in WalletNetwork.entries) {
                    val selected = state.network == network
                    Row(
                        Modifier.height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            .selectable(
                                selected = selected,
                                enabled = !state.busy,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                role = Role.RadioButton,
                                onClick = { onChoose(network) },
                            )
                            .padding(horizontal = 14.dp)
                            .testTag(WalletTags.network(network)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(networkText(network), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun Published(state: WalletUiState, onPublishAgain: () -> Unit) {
    val failed = state.unpublished
    val reachable = state.connections.count { it.usable }
    SeekerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                modifier = Modifier.testTag(WalletTags.PUBLISHED),
            )
            if (failed.isNotEmpty()) {
                SeekerButton(
                    text = stringResource(R.string.wallet_publish_again),
                    onClick = onPublishAgain,
                    enabled = !state.busy,
                    role = SeekerButtonRole.Neutral,
                    modifier = Modifier.fillMaxWidth().testTag(WalletTags.PUBLISH_AGAIN),
                )
            }
        }
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String, tag: String) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(WalletTags.field(tag))
            .semantics(mergeDescendants = true) {}
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Identifier(value, Modifier.padding(top = 3.dp), maxLines = 3)
    }
}
