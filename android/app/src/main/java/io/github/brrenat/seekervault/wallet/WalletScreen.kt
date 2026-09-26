package io.github.brrenat.seekervault.wallet

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionsTags
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Display-only state for [WalletScreen]. */
data class WalletScreenState(
    val title: String,
    val wallet: WalletScreenWallet? = null,
    val disconnected: WalletDisconnectedState? = null,
    val publishWarning: WalletPublishWarningState? = null,
    val explanation: String? = null,
    val problem: String? = null,
    val networkWarning: String? = null,
    val disconnectLabel: String? = null,
    val busy: Boolean = false,
)

data class WalletScreenWallet(val name: String, val address: String, val statusText: String)

data class WalletDisconnectedState(
    val title: String,
    val body: String,
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

/** One installed wallet app the owner can connect (SEE-159). */
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
    val onDisconnect: () -> Unit,
    val onPublishAgain: () -> Unit,
    val onBack: () -> Unit,
    val navigation: ScreenNavigationCallbacks,
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
    WalletScreen(
        state = walletScreenState(state),
        callbacks =
            WalletScreenCallbacks(
                onChooseWalletApp = viewModel::chooseWalletApp,
                onChooseNetwork = viewModel::chooseNetwork,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
                onPublishAgain = viewModel::publishAgain,
                onBack = onBack,
                navigation = navigationCallbacks,
            ),
        modifier = modifier,
    )
}

/** Wallet composed entirely from the shared design-system library. */
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
            state.wallet?.let { wallet ->
                WalletBanner(
                    walletName = wallet.name,
                    address = wallet.address,
                    statusText = wallet.statusText,
                    variant = WalletBannerVariant.Expanded,
                    onCopyAddress = null,
                    modifier =
                        Modifier.testTag(WalletTags.STATUS).semantics(mergeDescendants = true) {},
                )
            }
            state.disconnected?.let { disconnected ->
                EmptyState(
                    screen = EmptyStateScreen.Inbox,
                    title = disconnected.title,
                    body = disconnected.body,
                    modifier =
                        Modifier.testTag(WalletTags.STATUS).semantics(mergeDescendants = true) {},
                )
                if (disconnected.apps.isNotEmpty()) {
                    SectionHeader(
                        title = disconnected.appTitle,
                        trailing = SectionHeaderTrailing.None,
                    )
                    ScreenCaption(text = disconnected.appExplanation)
                    disconnected.apps.forEach { app ->
                        RadioRow(
                            label = app.label,
                            state = if (app.selected) RadioRowState.On else RadioRowState.Off,
                            onClick = {
                                if (!state.busy) callbacks.onChooseWalletApp(app.packageName)
                            },
                            modifier =
                                Modifier.testTag(WalletTags.app(app.packageName)).let {
                                    if (state.busy) it.semantics { disabled() } else it
                                },
                        )
                    }
                }
                SectionHeader(
                    title = disconnected.networkTitle,
                    trailing = SectionHeaderTrailing.None,
                )
                disconnected.networks.forEach { network ->
                    RadioRow(
                        label = network.label,
                        state = if (network.selected) RadioRowState.On else RadioRowState.Off,
                        onClick = {
                            if (!state.busy) callbacks.onChooseNetwork(network.network)
                        },
                        modifier =
                            Modifier.testTag(WalletTags.network(network.network)).let {
                                if (state.busy) it.semantics { disabled() } else it
                            },
                    )
                }
                SeekerButton(
                    label = disconnected.connectLabel,
                    onClick = callbacks.onConnect,
                    variant = SeekerButtonVariant.Filled,
                    size = SeekerButtonSize.Md,
                    enabled = !state.busy && disconnected.canConnect,
                    modifier = Modifier.fillMaxWidth().testTag(WalletTags.CONNECT),
                )
            }
            state.problem?.let { problem ->
                NoticeCard(
                    kind = NoticeCardKind.StaleRules,
                    message = problem,
                    modifier =
                        Modifier.testTag(WalletTags.PROBLEM).semantics(mergeDescendants = true) {},
                )
            }
            state.networkWarning?.let { warning ->
                NoticeCard(
                    kind = NoticeCardKind.StaleRules,
                    message = warning,
                    modifier =
                        Modifier.testTag(WalletTags.UNCONFIRMED).semantics(
                            mergeDescendants = true
                        ) {},
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
            state.explanation?.let { explanation -> ScreenCaption(text = explanation) }
            state.disconnectLabel?.let { label ->
                SeekerButton(
                    label = label,
                    onClick = callbacks.onDisconnect,
                    variant = SeekerButtonVariant.Error,
                    size = SeekerButtonSize.Md,
                    enabled = !state.busy,
                    modifier = Modifier.testTag(WalletTags.DISCONNECT),
                )
            }
        }
    }
}

@Composable
internal fun walletScreenState(state: WalletUiState): WalletScreenState {
    val wallet = state.wallet
    return WalletScreenState(
        title = stringResource(R.string.wallet_title),
        wallet =
            wallet?.let {
                val account = it.label?.takeIf(String::isNotBlank)
                val app = state.walletApp?.takeIf(String::isNotBlank)
                WalletScreenWallet(
                    // The wallet is the app the session belongs to, and the account's own label is
                    // the account's (SEE-159). Naming the card after the account is how a label
                    // like "phantom" came to read as the wallet that would be opened.
                    name = app ?: account ?: stringResource(R.string.wallet_title),
                    address = it.address,
                    statusText =
                        if (app != null && account != null) {
                            stringResource(
                                R.string.wallet_connected_summary_account,
                                walletTimeFormatter.format(it.selectedAt),
                                networkText(it.network),
                                account,
                            )
                        } else {
                            stringResource(
                                R.string.wallet_connected_summary,
                                walletTimeFormatter.format(it.selectedAt),
                                networkText(it.network),
                            )
                        },
                )
            },
        disconnected =
            if (wallet == null) {
                WalletDisconnectedState(
                    title =
                        if (state.connecting) {
                            stringResource(R.string.wallet_connecting)
                        } else {
                            stringResource(R.string.wallet_none_title)
                        },
                    body = stringResource(R.string.wallet_none_text),
                    appTitle = stringResource(R.string.wallet_app_label),
                    appExplanation = stringResource(R.string.wallet_app_explanation),
                    // Nothing to choose when this phone has one wallet app, or couldn't list any:
                    // a single answer needs no question, and a list of none asks nothing.
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
                    connectLabel = stringResource(R.string.wallet_connect),
                    canConnect = state.canConnect,
                )
            } else {
                null
            },
        publishWarning = state.unpublished.takeIf { it.isNotEmpty() }?.let { publishWarning(it) },
        explanation = wallet?.let { stringResource(R.string.wallet_security_explanation) },
        problem = state.problem?.let { problemText(it, state.detail) },
        networkWarning =
            wallet
                ?.takeUnless { it.networkConfirmed }
                ?.let {
                    stringResource(R.string.wallet_network_unconfirmed)
                },
        disconnectLabel =
            wallet?.let {
                if (state.disconnecting) {
                    stringResource(R.string.wallet_disconnecting)
                } else {
                    stringResource(R.string.wallet_disconnect)
                }
            },
        busy = state.busy,
    )
}

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
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
