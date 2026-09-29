package io.github.brrenat.seekervault.wallet

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.RenameDialog
import io.github.brrenat.seekervault.connections.labelProblem
import io.github.brrenat.seekervault.designsystem.EmptyState
import io.github.brrenat.seekervault.designsystem.EmptyStateScreen
import io.github.brrenat.seekervault.designsystem.NoticeCard
import io.github.brrenat.seekervault.designsystem.NoticeCardKind
import io.github.brrenat.seekervault.designsystem.RadioRow
import io.github.brrenat.seekervault.designsystem.RadioRowState
import io.github.brrenat.seekervault.designsystem.ScreenCaption
import io.github.brrenat.seekervault.designsystem.ScreenDestination
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ScreenScaffold
import io.github.brrenat.seekervault.designsystem.ScreenScrollBody
import io.github.brrenat.seekervault.designsystem.SectionHeader
import io.github.brrenat.seekervault.designsystem.SectionHeaderTrailing
import io.github.brrenat.seekervault.designsystem.SeekerButton
import io.github.brrenat.seekervault.designsystem.SeekerButtonSize
import io.github.brrenat.seekervault.designsystem.SeekerButtonVariant
import io.github.brrenat.seekervault.designsystem.WalletBanner
import io.github.brrenat.seekervault.designsystem.WalletBannerVariant
import io.github.brrenat.seekervault.designsystem.WalletPublishWarning
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SolidDialog
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Display-only state for [WalletScreen]. */
data class WalletScreenState(
    val title: String,
    /** Every saved profile, in the order they were added. */
    val profiles: List<WalletScreenProfile> = emptyList(),
    /** Shown when there are none. */
    val empty: WalletEmptyState? = null,
    val add: WalletAddState,
    val publishWarning: WalletPublishWarningState? = null,
    val explanation: String? = null,
    val problem: String? = null,
    val busy: Boolean = false,
    /** The profile being removed, with who uses it, while the owner confirms. */
    val removing: WalletRemoveState? = null,
    /** The profile being renamed, with its current name. */
    val renaming: Pair<String, String>? = null,
)

/** One saved wallet profile as the Wallets screen shows it (SEE-174). */
data class WalletScreenProfile(
    val id: String,
    /** The owner's name for it, or the account's own. */
    val name: String,
    val address: String,
    /** The network and the wallet app, told apart from the account's name. */
    val statusText: String,
    /** Which connections use it, or that none does. */
    val usage: String,
    /** Why it can't sign now, when it can't. */
    val warning: String? = null,
    val renameLabel: String,
    val reconnectLabel: String,
    val removeLabel: String,
)

data class WalletEmptyState(val title: String, val body: String)

data class WalletAddState(
    val title: String,
    val explanation: String,
    /** The wallet apps to pick from, and the header over them. Empty when there is no choice. */
    val appTitle: String,
    val appExplanation: String,
    val apps: List<WalletAppOption> = emptyList(),
    val networkTitle: String,
    val networks: List<WalletNetworkOption>,
    val connectLabel: String,
    /** False while the owner still has a wallet app to pick, so nothing opens Android's chooser. */
    val canConnect: Boolean = true,
)

data class WalletRemoveState(val id: String, val title: String, val body: String)

/** One installed wallet app the owner can add from (SEE-159). */
data class WalletAppOption(val packageName: String, val label: String, val selected: Boolean)

data class WalletNetworkOption(
    val network: WalletNetwork,
    val label: String,
    val selected: Boolean,
)

data class WalletPublishWarningState(
    val serverName: String,
    val message: String,
    val actionLabel: String,
)

/** Every interaction emitted by the stateless [WalletScreen]. */
data class WalletScreenCallbacks(
    val onChooseWalletApp: (String) -> Unit,
    val onChooseNetwork: (WalletNetwork) -> Unit,
    val onConnect: () -> Unit,
    val onPublishAgain: () -> Unit,
    val onBack: () -> Unit,
    val navigation: ScreenNavigationCallbacks,
    val onCopyAddress: (String) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onSaveName: (String, String) -> Unit = { _, _ -> },
    val onCancelRename: () -> Unit = {},
    val onReconnect: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onConfirmRemove: () -> Unit = {},
    val onCancelRemove: () -> Unit = {},
)

/** Keeps wallet state collection and actions outside the display-only screen. */
@Composable
fun WalletRoute(
    viewModel: WalletViewModel,
    onBack: () -> Unit,
    navigationCallbacks: ScreenNavigationCallbacks,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    WalletScreen(
        state = walletScreenState(state),
        callbacks =
            WalletScreenCallbacks(
                onChooseWalletApp = viewModel::chooseWalletApp,
                onChooseNetwork = viewModel::chooseNetwork,
                onConnect = { viewModel.connect() },
                onPublishAgain = viewModel::publishAgain,
                onBack = onBack,
                navigation = navigationCallbacks,
                onCopyAddress = { address ->
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("Wallet address", address))
                        )
                    }
                },
                onRename = viewModel::startRename,
                onSaveName = viewModel::rename,
                onCancelRename = viewModel::cancelRename,
                onReconnect = viewModel::reconnect,
                onRemove = viewModel::askToRemove,
                onConfirmRemove = viewModel::confirmRemove,
                onCancelRemove = viewModel::cancelRemove,
            ),
        modifier = modifier,
    )
}

/** Wallets, composed entirely from the shared design-system library. */
@Composable
fun WalletScreen(
    state: WalletScreenState,
    callbacks: WalletScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        title = state.title,
        selectedDestination = ScreenDestination.Wallet,
        navigationCallbacks = callbacks.navigation,
        onBack = callbacks.onBack,
        backButtonModifier = Modifier.testTag(ConnectionsTags.BACK),
        modifier = modifier,
    ) {
        ScreenScrollBody {
            state.empty?.let { empty ->
                EmptyState(
                    screen = EmptyStateScreen.Inbox,
                    title = empty.title,
                    body = empty.body,
                    modifier =
                        Modifier.testTag(WalletTags.STATUS).semantics(mergeDescendants = true) {},
                )
            }
            state.profiles.forEach { profile -> ProfileCard(profile, state.busy, callbacks) }
            state.problem?.let { problem ->
                NoticeCard(
                    kind = NoticeCardKind.StaleRules,
                    message = problem,
                    modifier =
                        Modifier.testTag(WalletTags.PROBLEM).semantics(mergeDescendants = true) {},
                )
            }
            state.publishWarning?.let { warning ->
                WalletPublishWarning(
                    serverName = warning.serverName,
                    message = warning.message,
                    actionLabel = warning.actionLabel,
                    onAction = { if (!state.busy) callbacks.onPublishAgain() },
                    actionModifier = Modifier.testTag(WalletTags.PUBLISH_AGAIN),
                    modifier =
                        Modifier.testTag(WalletTags.PUBLISHED).semantics(
                            mergeDescendants = true
                        ) {},
                )
            }
            AddWallet(state.add, state.busy, callbacks)
            state.explanation?.let { explanation -> ScreenCaption(text = explanation) }
        }
    }
    state.renaming?.let { (id, current) ->
        RenameDialog(
            current = current,
            onSave = { label ->
                // A blank name goes back to the wallet's own, so only a name that is too long is
                // refused.
                labelProblem(label.ifBlank { current })?.also {
                    return@RenameDialog it
                }
                callbacks.onSaveName(id, label)
                null
            },
            onDismiss = callbacks.onCancelRename,
        )
    }
    state.removing?.let { removing ->
        RemoveProfileDialog(removing, callbacks.onConfirmRemove, callbacks.onCancelRemove)
    }
}

@Composable
private fun ProfileCard(
    profile: WalletScreenProfile,
    busy: Boolean,
    callbacks: WalletScreenCallbacks,
) {
    WalletBanner(
        walletName = profile.name,
        address = profile.address,
        statusText = "${profile.statusText}\n${profile.usage}",
        variant = WalletBannerVariant.Expanded,
        onCopyAddress = { callbacks.onCopyAddress(profile.address) },
        modifier =
            Modifier.testTag(WalletTags.profile(profile.id)).semantics(mergeDescendants = true) {},
    )
    profile.warning?.let { warning ->
        NoticeCard(
            kind = NoticeCardKind.StaleRules,
            message = warning,
            modifier =
                Modifier.testTag(WalletTags.profileWarning(profile.id)).semantics(
                    mergeDescendants = true
                ) {},
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
    ) {
        SeekerButton(
            label = profile.renameLabel,
            onClick = { callbacks.onRename(profile.id) },
            variant = SeekerButtonVariant.Tonal,
            size = SeekerButtonSize.Md,
            enabled = !busy,
            modifier = Modifier.weight(1f).testTag(WalletTags.rename(profile.id)),
        )
        SeekerButton(
            label = profile.reconnectLabel,
            onClick = { callbacks.onReconnect(profile.id) },
            variant = SeekerButtonVariant.Tonal,
            size = SeekerButtonSize.Md,
            enabled = !busy,
            modifier = Modifier.weight(1f).testTag(WalletTags.reconnect(profile.id)),
        )
        SeekerButton(
            label = profile.removeLabel,
            onClick = { callbacks.onRemove(profile.id) },
            variant = SeekerButtonVariant.Error,
            size = SeekerButtonSize.Md,
            enabled = !busy,
            modifier = Modifier.weight(1f).testTag(WalletTags.remove(profile.id)),
        )
    }
}

@Composable
private fun AddWallet(add: WalletAddState, busy: Boolean, callbacks: WalletScreenCallbacks) {
    SectionHeader(title = add.title, trailing = SectionHeaderTrailing.None)
    ScreenCaption(text = add.explanation)
    if (add.apps.isNotEmpty()) {
        SectionHeader(title = add.appTitle, trailing = SectionHeaderTrailing.None)
        ScreenCaption(text = add.appExplanation)
        add.apps.forEach { app ->
            RadioRow(
                label = app.label,
                state = if (app.selected) RadioRowState.On else RadioRowState.Off,
                onClick = { if (!busy) callbacks.onChooseWalletApp(app.packageName) },
                modifier =
                    Modifier.testTag(WalletTags.app(app.packageName)).let {
                        if (busy) it.semantics { disabled() } else it
                    },
            )
        }
    }
    SectionHeader(title = add.networkTitle, trailing = SectionHeaderTrailing.None)
    add.networks.forEach { network ->
        RadioRow(
            label = network.label,
            state = if (network.selected) RadioRowState.On else RadioRowState.Off,
            onClick = { if (!busy) callbacks.onChooseNetwork(network.network) },
            modifier =
                Modifier.testTag(WalletTags.network(network.network)).let {
                    if (busy) it.semantics { disabled() } else it
                },
        )
    }
    SeekerButton(
        label = add.connectLabel,
        onClick = callbacks.onConnect,
        variant = SeekerButtonVariant.Filled,
        size = SeekerButtonSize.Md,
        enabled = !busy && add.canConnect,
        modifier = Modifier.fillMaxWidth().testTag(WalletTags.CONNECT),
    )
}

@Composable
internal fun walletScreenState(state: WalletUiState): WalletScreenState {
    val removing = state.removing?.let { id -> state.profiles.firstOrNull { it.id == id } }
    val renaming = state.renaming?.let { id -> state.profiles.firstOrNull { it.id == id } }
    return WalletScreenState(
        title = stringResource(R.string.wallet_profiles_title),
        profiles = state.profiles.map { profileState(it, state.usersOf(it.id)) },
        empty =
            if (state.loaded && state.profiles.isEmpty()) {
                WalletEmptyState(
                    title = stringResource(R.string.wallet_profiles_none_title),
                    body = stringResource(R.string.wallet_profiles_none_text),
                )
            } else {
                null
            },
        add =
            WalletAddState(
                title =
                    if (state.connecting) stringResource(R.string.wallet_connecting)
                    else stringResource(R.string.wallet_profiles_add_title),
                explanation = stringResource(R.string.wallet_profiles_add_explanation),
                appTitle = stringResource(R.string.wallet_app_label),
                appExplanation = stringResource(R.string.wallet_app_explanation),
                // Nothing to choose when this phone has one wallet app, or couldn't list any: a
                // single answer needs no question, and a list of none asks nothing.
                apps =
                    state.apps
                        .orEmpty()
                        .takeIf { it.size > 1 }
                        .orEmpty()
                        .map { app ->
                            WalletAppOption(
                                packageName = app.packageName,
                                label = app.label,
                                selected = app == state.chosen,
                            )
                        },
                networkTitle = stringResource(R.string.wallet_network_label),
                networks =
                    WalletNetwork.entries.map {
                        WalletNetworkOption(
                            network = it,
                            label = networkText(it),
                            selected = it == state.network,
                        )
                    },
                connectLabel = stringResource(R.string.wallet_profiles_add),
                canConnect = state.canConnect,
            ),
        publishWarning = state.unpublished.takeIf { it.isNotEmpty() }?.let { publishWarning(it) },
        explanation = stringResource(R.string.wallet_security_explanation),
        problem = state.problem?.let { problemText(it, state.detail) },
        busy = state.busy,
        removing =
            removing?.let { profile ->
                val users = state.usersOf(profile.id)
                WalletRemoveState(
                    id = profile.id,
                    title =
                        stringResource(
                            R.string.wallet_profile_remove_title,
                            profileName(profile),
                        ),
                    body =
                        if (users.isEmpty()) {
                            stringResource(R.string.wallet_profile_remove_unused)
                        } else {
                            stringResource(
                                R.string.wallet_profile_remove_used,
                                users.joinToString { it.label },
                            )
                        },
                )
            },
        renaming = renaming?.let { it.id to profileName(it) },
    )
}

@Composable
private fun profileState(profile: WalletProfile, users: List<Connection>): WalletScreenProfile =
    WalletScreenProfile(
        id = profile.id,
        name = profileName(profile),
        address = profile.address,
        // The network first, because two profiles of one address differ by nothing else, and the
        // wallet app apart from the account's name (SEE-159).
        statusText =
            stringResource(
                R.string.wallet_profile_summary_app,
                networkText(profile.network),
                profile.walletApp ?: stringResource(R.string.wallet_profile_unknown_app),
                walletTimeFormatter.format(profile.connectedAt),
            ),
        usage =
            if (users.isEmpty()) stringResource(R.string.wallet_profile_unused)
            else pluralStringResource(R.plurals.wallet_profile_used_by, users.size, users.size),
        warning =
            when {
                !profile.authorized -> stringResource(R.string.wallet_profile_needs_reconnect)
                !profile.networkConfirmed -> stringResource(R.string.wallet_network_unconfirmed)
                else -> null
            },
        renameLabel = stringResource(R.string.wallet_profile_rename),
        reconnectLabel = stringResource(R.string.wallet_profile_reconnect),
        removeLabel = stringResource(R.string.wallet_profile_remove),
    )

/** What a profile is called on screen: the owner's name, the account's own, or a plain noun. */
@Composable
fun profileName(profile: WalletProfile): String =
    profile.displayLabel ?: stringResource(R.string.wallet_profile_unnamed)

@Composable
private fun publishWarning(connections: List<Connection>): WalletPublishWarningState {
    val names = connections.joinToString { it.label }
    return WalletPublishWarningState(
        serverName =
            if (connections.size == 1) {
                names
            } else {
                stringResource(R.string.wallet_unpublished_servers, connections.size, names)
            },
        message = stringResource(R.string.wallet_publish_warning),
        actionLabel = stringResource(R.string.wallet_publish_again),
    )
}

private val walletTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/**
 * Confirms removing a profile, naming the connections that use it (SEE-174). Nothing is chosen in
 * its place: they are left without a wallet until the owner picks one.
 */
@Composable
private fun RemoveProfileDialog(
    removing: WalletRemoveState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SolidDialog(
        title = removing.title,
        body = { Text(removing.body, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        actions = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
            ) {
                io.github.brrenat.seekervault.ui.SeekerButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    role = SeekerButtonRole.Neutral,
                    modifier = Modifier.weight(1f).testTag(ConnectionsTags.DIALOG_DISMISS),
                )
                io.github.brrenat.seekervault.ui.SeekerButton(
                    text = stringResource(R.string.wallet_profile_remove),
                    onClick = onConfirm,
                    role = SeekerButtonRole.Error,
                    modifier = Modifier.weight(1f).testTag(WalletTags.REMOVE_CONFIRM),
                )
            }
        },
    )
}
