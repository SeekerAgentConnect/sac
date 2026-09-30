package io.github.brrenat.seekervault.wallet

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.RenameDialog
import io.github.brrenat.seekervault.connections.labelProblem
import io.github.brrenat.seekervault.designsystem.AddWalletButton
import io.github.brrenat.seekervault.designsystem.AddWalletSheet
import io.github.brrenat.seekervault.designsystem.EmptyState
import io.github.brrenat.seekervault.designsystem.EmptyStateScreen
import io.github.brrenat.seekervault.designsystem.NoticeCard
import io.github.brrenat.seekervault.designsystem.NoticeCardKind
import io.github.brrenat.seekervault.designsystem.ScreenCaption
import io.github.brrenat.seekervault.designsystem.ScreenDestination
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ScreenScaffold
import io.github.brrenat.seekervault.designsystem.ScreenScrollBody
import io.github.brrenat.seekervault.designsystem.WalletAppRowModel
import io.github.brrenat.seekervault.designsystem.WalletProfileCard
import io.github.brrenat.seekervault.designsystem.WalletProfileCardModel
import io.github.brrenat.seekervault.designsystem.WalletPublishWarning
import io.github.brrenat.seekervault.designsystem.WalletSegmentModel
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
    val expandedProfileId: String? = null,
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
    val shortAddress: String,
    val network: String,
    val walletApp: String,
    val added: String,
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
    val cancelLabel: String,
    val problem: String? = null,
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
    val onAddWallet: () -> Unit,
    val onToggleProfile: (String) -> Unit,
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
    onAddWallet: () -> Unit,
    onBack: () -> Unit,
    navigationCallbacks: ScreenNavigationCallbacks,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expandedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.profiles) {
        if (expandedProfileId !in state.profiles.map { it.id }) {
            expandedProfileId = state.profiles.firstOrNull()?.id
        }
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    WalletScreen(
        state = walletScreenState(state).copy(expandedProfileId = expandedProfileId),
        callbacks =
            WalletScreenCallbacks(
                onAddWallet = onAddWallet,
                onToggleProfile = { id ->
                    expandedProfileId = if (expandedProfileId == id) null else id
                },
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

/** The add-wallet sheet; adding still delegates to the existing wallet ViewModel and repository. */
@Composable
fun WalletAddSheetScreen(
    state: WalletUiState,
    onChooseWalletApp: (String) -> Unit,
    onChooseNetwork: (WalletNetwork) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val add = walletAddState(state)
    AddWalletSheet(
        title = add.title,
        explanation = add.explanation,
        networkTitle = add.networkTitle,
        networks =
            add.networks.map {
                WalletSegmentModel(it.network.name, it.label, it.selected)
            },
        walletAppTitle = add.appTitle,
        walletAppExplanation = add.appExplanation,
        apps = add.apps.map { WalletAppRowModel(it.packageName, it.label, it.selected) },
        continueLabel = add.connectLabel,
        cancelLabel = add.cancelLabel,
        problem = add.problem,
        interactionEnabled = !state.busy,
        canContinue = !state.busy && add.canConnect,
        onChooseNetwork = { onChooseNetwork(WalletNetwork.valueOf(it)) },
        onChooseApp = onChooseWalletApp,
        onContinue = onConnect,
        onCancel = onCancel,
        modifier = modifier.testTag(WalletTags.ADD_SHEET),
        networkModifier = { network ->
            Modifier.testTag(WalletTags.network(WalletNetwork.valueOf(network)))
        },
        appModifier = { packageName -> Modifier.testTag(WalletTags.app(packageName)) },
        problemModifier = Modifier.testTag(WalletTags.ADD_PROBLEM),
        continueModifier = Modifier.testTag(WalletTags.CONNECT),
        cancelModifier = Modifier.testTag(ConnectionsTags.DIALOG_DISMISS),
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
        includeDiscover = false,
        modifier = modifier,
    ) {
        ScreenScrollBody {
            Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
                Text(
                    text = stringResource(R.string.wallet_profiles_saved),
                    modifier =
                        Modifier.padding(
                            horizontal = SeekerTheme.spacing.xs,
                            vertical = SeekerTheme.spacing.xs,
                        ),
                    color = SeekerTheme.colors.primaryText,
                    style = MaterialTheme.typography.titleSmall,
                )
                state.empty?.let { empty ->
                    EmptyState(
                        screen = EmptyStateScreen.Inbox,
                        title = empty.title,
                        body = empty.body,
                        modifier =
                            Modifier.testTag(WalletTags.STATUS).semantics(
                                mergeDescendants = true
                            ) {},
                    )
                }
                state.profiles.forEach { profile ->
                    ProfileCard(
                        profile = profile,
                        expanded = state.expandedProfileId == profile.id,
                        busy = state.busy,
                        callbacks = callbacks,
                    )
                    if (profile.warning != null && state.expandedProfileId == profile.id) {
                        NoticeCard(
                            kind = NoticeCardKind.StaleRules,
                            message = profile.warning,
                            modifier =
                                Modifier.testTag(WalletTags.profileWarning(profile.id)).semantics(
                                    mergeDescendants = true
                                ) {},
                        )
                    }
                }
                Spacer(Modifier)
                AddWalletButton(
                    label = stringResource(R.string.wallet_profiles_add),
                    onClick = callbacks.onAddWallet,
                    modifier = Modifier.testTag(WalletTags.ADD),
                )
                Spacer(Modifier.height(SeekerTheme.spacing.xs))
                state.explanation?.let { explanation -> ScreenCaption(text = explanation) }
            }
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
    expanded: Boolean,
    busy: Boolean,
    callbacks: WalletScreenCallbacks,
) {
    WalletProfileCard(
        model =
            WalletProfileCardModel(
                name = profile.name,
                shortAddress = profile.shortAddress,
                address = profile.address,
                network = profile.network,
                usage = profile.usage,
                added = profile.added,
                walletApp = profile.walletApp,
                renameLabel = profile.renameLabel,
                reconnectLabel = profile.reconnectLabel,
                removeLabel = profile.removeLabel,
            ),
        expanded = expanded,
        enabled = !busy,
        copyContentDescription = stringResource(R.string.wallet_copy_address),
        onToggle = { callbacks.onToggleProfile(profile.id) },
        onCopyAddress = { callbacks.onCopyAddress(profile.address) },
        onRename = { callbacks.onRename(profile.id) },
        onReconnect = { callbacks.onReconnect(profile.id) },
        onRemove = { callbacks.onRemove(profile.id) },
        modifier = Modifier.testTag(WalletTags.profile(profile.id)),
        renameModifier = Modifier.testTag(WalletTags.rename(profile.id)),
        reconnectModifier = Modifier.testTag(WalletTags.reconnect(profile.id)),
        removeModifier = Modifier.testTag(WalletTags.remove(profile.id)),
    )
}

@Composable
internal fun walletAddState(state: WalletUiState): WalletAddState {
    val installed = state.apps.orEmpty()
    val selected = state.chosen ?: installed.singleOrNull()
    val walletName = selected?.label ?: stringResource(R.string.wallet_app_generic)
    return WalletAddState(
        title = stringResource(R.string.wallet_profiles_add_title),
        explanation = stringResource(R.string.wallet_profiles_add_explanation),
        appTitle = stringResource(R.string.wallet_app_label),
        appExplanation = stringResource(R.string.wallet_app_sheet_explanation),
        apps =
            installed.map { app ->
                WalletAppOption(
                    packageName = app.packageName,
                    label = app.label,
                    selected = app == selected,
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
        connectLabel = stringResource(R.string.wallet_continue_in, walletName),
        cancelLabel = stringResource(R.string.cancel),
        problem = state.problem?.let { problemText(it, state.detail) },
        canConnect = state.canConnect,
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

private fun shortAddress(address: String): String =
    if (address.length <= ShortAddressVisibleCharacters * 2) address
    else
        address.take(ShortAddressVisibleCharacters) +
            "…" +
            address.takeLast(ShortAddressVisibleCharacters)

@Composable
private fun profileState(profile: WalletProfile, users: List<Connection>): WalletScreenProfile =
    WalletScreenProfile(
        id = profile.id,
        name = profileName(profile),
        address = profile.address,
        shortAddress = shortAddress(profile.address),
        network = networkText(profile.network),
        walletApp = profile.walletApp ?: stringResource(R.string.wallet_profile_unknown_app),
        added =
            stringResource(
                R.string.wallet_profile_added,
                walletTimeFormatter.format(profile.connectedAt),
            ),
        usage =
            if (users.isEmpty()) stringResource(R.string.wallet_profile_unused)
            else
                stringResource(
                    R.string.wallet_profile_used_by_names,
                    users.joinToString { it.label },
                ),
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

private const val ShortAddressVisibleCharacters = 4

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
