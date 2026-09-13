package io.github.brrenat.seekervault.wallet

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.ui.CardDivider
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassScreen
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.IconChip
import io.github.brrenat.seekervault.ui.MonoText
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.PillButton
import io.github.brrenat.seekervault.ui.PillTone
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space
import io.github.brrenat.seekervault.ui.TabBar

/**
 * The Wallet screen: connect the wallet the owner already has, see which address and network it
 * selected, and disconnect again. The screen shows a public address and nothing else about the
 * wallet: the authorization stays on the phone, and a key never gets here.
 */
@Composable
fun WalletScreen(
    state: WalletUiState,
    onChooseNetwork: (WalletNetwork) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPublishAgain: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    tabs: TabBar? = null,
) {
    val wallet = state.wallet
    GlassScreen(
        title = stringResource(R.string.wallet_title),
        tag = wallet?.let { networkText(it.network) },
        onBack = onBack,
        tabs = tabs,
        modifier = modifier,
    ) {
        GlassCard {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.Md),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconChip(Glyph.Wallet, contentDescription = null)
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
                    color = Nocturne.Text,
                    modifier = Modifier.testTag(WalletTags.STATUS),
                )
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.problem?.let { problem ->
            GlassCard {
                Text(
                    problemText(problem, state.detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Danger,
                    modifier = Modifier.testTag(WalletTags.PROBLEM),
                )
            }
        }
        if (wallet == null) {
            GlassCard {
                Text(
                    stringResource(R.string.wallet_none_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral400,
                )
            }
            SectionLabel(stringResource(R.string.wallet_network_label))
            NetworkChoice(state, onChooseNetwork)
            PillButton(
                stringResource(R.string.wallet_connect),
                onConnect,
                tone = PillTone.Accent,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag(WalletTags.CONNECT),
            )
        } else {
            GlassCard(spacing = 0.dp) {
                Field(R.string.wallet_field_address, "address") { MonoText(wallet.address) }
                CardDivider()
                Field(R.string.wallet_field_network, "network") {
                    Text(
                        networkText(wallet.network),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Nocturne.Text,
                    )
                }
                wallet.label?.let {
                    CardDivider()
                    Field(R.string.wallet_field_label, "label") {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Nocturne.Text,
                        )
                    }
                }
                CardDivider()
                Field(R.string.wallet_field_connected_at, "connectedAt") {
                    Text(
                        formatInstant(wallet.selectedAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Nocturne.Text,
                    )
                }
            }
            if (!wallet.networkConfirmed) {
                Text(
                    stringResource(R.string.wallet_network_unconfirmed),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nocturne.Danger,
                    modifier = Modifier.testTag(WalletTags.UNCONFIRMED),
                )
            }
            PillButton(
                stringResource(R.string.wallet_disconnect),
                onDisconnect,
                tone = PillTone.Danger,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag(WalletTags.DISCONNECT),
            )
        }
        Published(state, onPublishAgain)
    }
}

/** Which network the owner will connect on. It's fixed once a wallet is connected. */
@Composable
private fun NetworkChoice(state: WalletUiState, onChoose: (WalletNetwork) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
        for (network in WalletNetwork.entries) {
            val on = state.network == network
            Text(
                networkText(network),
                style = MaterialTheme.typography.bodyMedium,
                color = if (on) Nocturne.Accent100 else Nocturne.Neutral400,
                modifier =
                    Modifier.clip(RoundedCornerShape(Radius.Pill))
                        .background(if (on) Nocturne.accent(0.20f) else Nocturne.text(0.06f))
                        .border(
                            1.dp,
                            if (on) Nocturne.accent(0.46f) else Nocturne.text(0.12f),
                            RoundedCornerShape(Radius.Pill),
                        )
                        .clickable(enabled = !state.busy) { onChoose(network) }
                        .padding(horizontal = Space.Md, vertical = Space.Sm)
                        .testTag(WalletTags.network(network)),
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
        color = if (failed.isEmpty()) Nocturne.Neutral500 else Nocturne.Danger,
        modifier = Modifier.testTag(WalletTags.PUBLISHED),
    )
    if (failed.isNotEmpty()) {
        PillButton(
            stringResource(R.string.wallet_publish_again),
            onPublishAgain,
            enabled = !state.busy,
            modifier = Modifier.testTag(WalletTags.PUBLISH_AGAIN),
        )
    }
}

@Composable
private fun Field(@StringRes label: Int, tag: String, value: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = Space.Md)
            .testTag(WalletTags.field(tag))
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionLabel(stringResource(label))
        value()
    }
}
