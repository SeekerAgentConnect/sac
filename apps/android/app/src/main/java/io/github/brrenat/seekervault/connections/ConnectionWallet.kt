package io.github.brrenat.seekervault.connections

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
import io.github.brrenat.seekervault.designsystem.ConnectionWalletPickerSheet
import io.github.brrenat.seekervault.designsystem.FactRowValueStyle
import io.github.brrenat.seekervault.designsystem.WalletPickerRowModel
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.wallet.BindOutcome
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
import io.github.brrenat.seekervault.wallet.WalletReadiness
import io.github.brrenat.seekervault.wallet.WalletTags
import io.github.brrenat.seekervault.wallet.WalletUiState
import io.github.brrenat.seekervault.wallet.networkText
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
 * Choosing the wallet one connection signs with (SEE-174, SEE-178). Every saved profile stays
 * visible so the owner can understand what exists, while profiles on another declared network are
 * disabled. Adding opens the shared add-wallet sheet, preset to a single declared network, and the
 * newly added compatible profile is selected when this sheet returns. Nothing changes until the
 * owner chooses Use this wallet.
 */
@Composable
fun ConnectionWalletPicker(
    connection: Connection,
    wallet: WalletUiState,
    onUse: (String) -> Unit,
    onAdd: (WalletNetwork?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val supported = connection.server.manifest?.supportedNetworks.orEmpty()
    var chosen by rememberSaveable(connection.id) { mutableStateOf<String?>(null) }
    // A profile the owner just added for this connection is the one they meant.
    LaunchedEffect(wallet.added) {
        wallet.added
            .firstOrNull { id ->
                wallet.profiles.any { profile ->
                    profile.id == id && (supported.isEmpty() || profile.network in supported)
                }
            }
            ?.let { chosen = it }
    }
    val busy = wallet.busy
    val declared = supported.takeIf { it.isNotEmpty() }?.let { networksText(it) }
    ConnectionWalletPickerSheet(
        title = stringResource(R.string.connection_wallet_picker_title, connection.label),
        declaredNetwork = declared,
        declaredNetworkLabel =
            declared?.let { stringResource(R.string.connection_wallet_picker_uses, it) },
        noNetworkTitle = stringResource(R.string.connection_wallet_picker_no_network_title),
        noNetworkMessage = stringResource(R.string.connection_wallet_picker_no_networks),
        rows =
            wallet.profiles.map { profile ->
                val selectable = supported.isEmpty() || profile.network in supported
                WalletPickerRowModel(
                    id = profile.id,
                    name = profileName(profile),
                    shortAddress =
                        profile.address.take(ShortAddressLength) +
                            "…" +
                            profile.address.takeLast(ShortAddressLength),
                    network = networkText(profile.network),
                    note =
                        when {
                            supported.isEmpty() -> null
                            !selectable ->
                                stringResource(
                                    R.string.connection_wallet_picker_blocked,
                                    networkText(profile.network),
                                )
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
        addLabel =
            supported.singleOrNull()?.let {
                stringResource(R.string.wallet_profiles_add_for, networkText(it))
            } ?: stringResource(R.string.wallet_profiles_add_title),
        cancelLabel = stringResource(R.string.cancel),
        useLabel = stringResource(R.string.connection_wallet_use),
        canUse = !busy && chosen != null,
        onChoose = { chosen = it },
        onAdd = { onAdd(supported.singleOrNull()) },
        onCancel = onDismiss,
        onUse = { chosen?.let(onUse) },
        modifier = modifier.testTag(WalletTags.PICKER),
        rowModifier = { Modifier.testTag(WalletTags.choice(it)) },
        addModifier = Modifier.testTag(WalletTags.PICKER_ADD),
        cancelModifier = Modifier.testTag(ConnectionsTags.DIALOG_DISMISS),
        useModifier = Modifier.testTag(WalletTags.PICKER_USE),
    )
}

private const val ShortAddressLength = 4
