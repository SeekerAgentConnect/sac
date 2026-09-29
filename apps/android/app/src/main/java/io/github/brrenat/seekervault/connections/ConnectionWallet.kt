package io.github.brrenat.seekervault.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.ConnectionDetailFact
import io.github.brrenat.seekervault.designsystem.ConnectionDetailRules
import io.github.brrenat.seekervault.designsystem.FactRowValueStyle
import io.github.brrenat.seekervault.designsystem.RadioRow
import io.github.brrenat.seekervault.designsystem.RadioRowState
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SolidDialog
import io.github.brrenat.seekervault.wallet.BindOutcome
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
import io.github.brrenat.seekervault.wallet.WalletReadiness
import io.github.brrenat.seekervault.wallet.WalletTags
import io.github.brrenat.seekervault.wallet.WalletUiState
import io.github.brrenat.seekervault.wallet.networkText
import io.github.brrenat.seekervault.wallet.problemText
import io.github.brrenat.seekervault.wallet.profileName

/**
 * The wallet profiles a connection may be bound to (SEE-174): those on a network its server
 * declared. A server that declared none may be bound to any profile — a restricted feed proves its
 * reader with one — but nothing is signed for it until it declares a network, so no list here is
 * ever read as "every network works".
 */
fun compatibleProfiles(connection: Connection, profiles: List<WalletProfile>): List<WalletProfile> {
    val supported = connection.server.manifest?.supportedNetworks.orEmpty()
    return if (supported.isEmpty()) profiles else profiles.filter { it.network in supported }
}

/** The connection's wallet, as the navigation row on its detail sheet shows it. */
@Composable
fun connectionWalletRow(readiness: WalletReadiness?): ConnectionDetailRules {
    val profile = readiness?.profile
    return ConnectionDetailRules(
        title = stringResource(R.string.connection_wallet_title),
        supportingText =
            profile?.let {
                stringResource(
                    R.string.connection_wallet_ready,
                    profileName(it),
                    networkText(it.network),
                )
            } ?: stringResource(R.string.connection_wallet_none),
        caption = walletStateText(readiness),
        tag = ConnectionsTags.WALLET_ROW,
    )
}

/** What a connection's wallet state means to the owner. */
@Composable
fun walletStateText(readiness: WalletReadiness?): String =
    when (readiness) {
        null,
        WalletReadiness.NoConnection,
        WalletReadiness.NoProfile -> stringResource(R.string.connection_wallet_state_none)
        WalletReadiness.ProfileMissing -> stringResource(R.string.connection_wallet_state_missing)
        is WalletReadiness.NeedsReconnect ->
            stringResource(R.string.connection_wallet_state_reconnect)
        is WalletReadiness.NetworksUnknown ->
            stringResource(R.string.connection_wallet_state_unknown)
        is WalletReadiness.NetworkUnsupported ->
            stringResource(
                R.string.connection_wallet_state_unsupported,
                networkText(readiness.profile.network),
                networksText(readiness.supported),
            )
        is WalletReadiness.PublicationPending ->
            stringResource(R.string.connection_wallet_state_pending)
        is WalletReadiness.Ready -> stringResource(R.string.connection_wallet_caption)
    }

/**
 * The bound wallet's own facts for the detail sheet: the whole address, the wallet app and the
 * network, each on its own row so none of them can be mistaken for another.
 */
@Composable
fun connectionWalletFacts(readiness: WalletReadiness?): List<ConnectionDetailFact> {
    val profile = readiness?.profile ?: return emptyList()
    return listOf(
        ConnectionDetailFact(
            stringResource(R.string.connection_wallet_address),
            profile.address,
            FactRowValueStyle.MonoWrap,
            ConnectionsTags.field("walletAddress"),
        ),
        ConnectionDetailFact(
            stringResource(R.string.connection_wallet_app),
            profile.walletApp ?: stringResource(R.string.wallet_profile_unknown_app),
            FactRowValueStyle.Plain,
            ConnectionsTags.field("walletApp"),
        ),
        ConnectionDetailFact(
            stringResource(R.string.connection_wallet_network),
            networkText(profile.network),
            FactRowValueStyle.Plain,
            ConnectionsTags.field("walletNetwork"),
        ),
    )
}

/** What binding came to, in the owner's words, or null when there is nothing to say. */
@Composable
fun bindingText(outcome: BindOutcome): String? =
    when (outcome) {
        BindOutcome.Bound -> null
        is BindOutcome.Published ->
            if (outcome.cancelled == 0) stringResource(R.string.connection_wallet_published)
            else
                pluralStringResource(
                    R.plurals.connection_wallet_published_cancelled,
                    outcome.cancelled,
                    outcome.cancelled,
                )
        BindOutcome.PublicationFailed -> stringResource(R.string.connection_wallet_publish_failed)
        BindOutcome.Incompatible -> stringResource(R.string.connection_wallet_incompatible)
        BindOutcome.Gone -> null
    }

@Composable
private fun networksText(networks: Set<WalletNetwork>): String =
    WalletNetwork.entries.filter { it in networks }.map { networkText(it) }.joinToString(" / ")

/**
 * Choosing the wallet one connection signs with (SEE-174): the same list, with the same network
 * filter, whether the connection was just added or the owner is changing it. Only compatible
 * profiles are offered; with none, the owner adds one for the network the server needs without
 * leaving, and the one they added is offered at once. Nothing changes until they choose Use this
 * wallet, and cancelling — here or in the wallet app — leaves every profile and connection as it
 * was.
 */
@Composable
fun ConnectionWalletPicker(
    connection: Connection,
    wallet: WalletUiState,
    onUse: (String) -> Unit,
    onAdd: (WalletNetwork) -> Unit,
    onChooseWalletApp: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val offered = compatibleProfiles(connection, wallet.profiles)
    val supported = connection.server.manifest?.supportedNetworks.orEmpty()
    var chosen by
        rememberSaveable(connection.id) {
            mutableStateOf(
                connection.walletProfileId?.takeIf { id -> offered.any { it.id == id } }
                    ?: offered.singleOrNull()?.id
            )
        }
    // A profile the owner just added for this connection is the one they meant.
    LaunchedEffect(wallet.added) {
        wallet.added.firstOrNull { id -> offered.any { it.id == id } }?.let { chosen = it }
    }
    val busy = wallet.busy
    SolidDialog(
        title = stringResource(R.string.connection_wallet_picker_title, connection.label),
        modifier = Modifier.testTag(WalletTags.PICKER),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
                Text(
                    if (supported.isEmpty()) {
                        stringResource(R.string.connection_wallet_picker_no_networks)
                    } else {
                        stringResource(
                            R.string.connection_wallet_picker_networks,
                            networksText(supported),
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                offered.forEach { profile ->
                    RadioRow(
                        label =
                            listOfNotNull(
                                    profileName(profile),
                                    networkText(profile.network),
                                    profile.walletApp,
                                    profile.address.take(4) + "…" + profile.address.takeLast(4),
                                )
                                .joinToString(" · "),
                        state = if (profile.id == chosen) RadioRowState.On else RadioRowState.Off,
                        onClick = { if (!busy) chosen = profile.id },
                        modifier = Modifier.testTag(WalletTags.choice(profile.id)),
                    )
                }
                if (offered.isEmpty()) {
                    Text(
                        stringResource(R.string.connection_wallet_picker_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val apps = wallet.apps.orEmpty()
                if (apps.size > 1) {
                    Text(
                        stringResource(R.string.wallet_app_label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    apps.forEach { app ->
                        RadioRow(
                            label = app.label,
                            state =
                                if (app == wallet.chosen) RadioRowState.On else RadioRowState.Off,
                            onClick = { if (!busy) onChooseWalletApp(app.packageName) },
                            modifier = Modifier.testTag(WalletTags.app(app.packageName)),
                        )
                    }
                }
                // One button per network the server needs, prefilled; a server that declared
                // none gets the network the owner last chose on the Wallets screen.
                val networks =
                    WalletNetwork.entries
                        .filter { it in supported }
                        .ifEmpty {
                            listOf(wallet.network)
                        }
                networks.forEach { network ->
                    SeekerButton(
                        text =
                            stringResource(R.string.wallet_profiles_add_for, networkText(network)),
                        onClick = { onAdd(network) },
                        role = SeekerButtonRole.Neutral,
                        enabled = !busy && wallet.canConnect,
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag("${WalletTags.PICKER_ADD}:${network.name}"),
                    )
                }
                if (connection.walletProfileId != null) {
                    Text(
                        stringResource(
                            if (connection.mode == ConnectionMode.Direct) {
                                R.string.connection_wallet_picker_direct
                            } else {
                                R.string.connection_wallet_picker_feed
                            }
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                wallet.problem?.let {
                    Text(problemText(it, wallet.detail), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        actions = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
            ) {
                SeekerButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    role = SeekerButtonRole.Neutral,
                    modifier = Modifier.weight(1f).testTag(ConnectionsTags.DIALOG_DISMISS),
                )
                SeekerButton(
                    text = stringResource(R.string.connection_wallet_use),
                    onClick = { chosen?.let(onUse) },
                    enabled =
                        !busy &&
                            chosen != null &&
                            (chosen != connection.walletProfileId ||
                                connection.walletProfileId == null),
                    modifier = Modifier.weight(1f).testTag(WalletTags.PICKER_USE),
                )
            }
        },
    )
}
