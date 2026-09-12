package io.github.brrenat.seekervault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.connections.AddConnectionRoute
import io.github.brrenat.seekervault.connections.ConnectionDetailsScreen
import io.github.brrenat.seekervault.connections.ConnectionsScreen
import io.github.brrenat.seekervault.connections.ConnectionsUiState
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.connections.InboxSummary
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.inbox.InboxViewModel
import io.github.brrenat.seekervault.inbox.PendingRequestsScreen
import io.github.brrenat.seekervault.inbox.Preparation
import io.github.brrenat.seekervault.inbox.RequestDetailsScreen
import io.github.brrenat.seekervault.inbox.RequestGoneScreen
import io.github.brrenat.seekervault.inbox.inboxCounts
import io.github.brrenat.seekervault.live.LiveCommandRoute
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.wallet.WalletScreen
import io.github.brrenat.seekervault.wallet.WalletViewModel
import java.time.Instant

/**
 * The app's screens: Connections first, then a connection's details, Add connection, Pending
 * requests and Request details, Wallet, and the Stage 1 live test. The back stack is a list of
 * route strings, so it survives rotation and process death; no route carries a secret.
 */
@Composable
fun SeekerVaultApp(
    connections: ConnectionsViewModel,
    inbox: InboxViewModel,
    wallet: WalletViewModel,
    live: LiveCommandViewModel,
) {
    var stack by rememberSaveable { mutableStateOf(listOf(Routes.CONNECTIONS)) }
    val push = { route: String -> stack = stack + route }
    val pop = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }
    val state by connections.state.collectAsStateWithLifecycle()
    val inboxState by inbox.state.collectAsStateWithLifecycle()
    val walletState by wallet.state.collectAsStateWithLifecycle()
    val route = stack.last()
    when {
        route == Routes.CONNECTIONS -> {
            val (waitingForYou, toSend) = inboxCounts(inboxState)
            ConnectionsScreen(
                state = state,
                onOpen = { push(Routes.DETAILS + it) },
                onAdd = { push(Routes.ADD) },
                onLiveTest = { push(Routes.LIVE) },
                onMessageShown = connections::messageShown,
                inbox = InboxSummary(waitingForYou, toSend),
                onInbox = { push(Routes.INBOX) },
                wallet = walletState.wallet,
                onWallet = { push(Routes.WALLET) },
            )
        }
        route == Routes.WALLET ->
            WalletScreen(
                state = walletState,
                onChooseNetwork = wallet::chooseNetwork,
                onConnect = wallet::connect,
                onDisconnect = wallet::disconnect,
                onPublishAgain = wallet::publishAgain,
                onBack = pop,
            )
        route == Routes.ADD ->
            AddConnectionRoute(
                viewModel = connections,
                onBack = pop,
                // Show the new connection in place of the Add screen.
                onPaired = { stack = stack.dropLast(1) + (Routes.DETAILS + it.id) },
            )
        route == Routes.LIVE -> LiveTestRoute(live)
        route.startsWith(Routes.DETAILS) -> {
            val id = route.removePrefix(Routes.DETAILS)
            ConnectionDetailsRoute(connections, state, id, pop) { push(Routes.INBOX_FOR + id) }
        }
        route == Routes.INBOX || route.startsWith(Routes.INBOX_FOR) ->
            PendingRequestsScreen(
                state = inboxState,
                connectionId = route.removePrefix(Routes.INBOX).removePrefix("/").ifEmpty { null },
                now = Instant.now(),
                onOpen = { push("${Routes.REQUEST}${it.connectionId}/${it.requestId}") },
                onRefresh = {
                    inbox.refresh(
                        route.removePrefix(Routes.INBOX).removePrefix("/").ifEmpty { null }
                    )
                },
                onBack = pop,
            )
        route.startsWith(Routes.REQUEST) -> {
            val (connectionId, requestId) = route.removePrefix(Routes.REQUEST).split('/', limit = 2)
            val key = RequestKey(connectionId, requestId)
            val result = inboxState.inbox.result(key)
            val request = result?.request ?: inboxState.inbox.pendingRequest(key)
            if (request == null) {
                RequestGoneScreen(onBack = pop)
            } else {
                RequestDetailsScreen(
                    request = request,
                    source = inboxState.connections.firstOrNull { it.id == connectionId },
                    result = result,
                    sending = key in inboxState.sending,
                    now = Instant.now(),
                    onAnswer = { inbox.answer(key, it) },
                    onApprove = { inbox.approve(key, inboxState.wallet) },
                    onSendAgain = { inbox.sendAgain(key) },
                    wallet = inboxState.wallet,
                    signingProblem = inboxState.problem.takeIf { inboxState.problemKey == key },
                    preparation = inboxState.preparations[key],
                    onPrepareAgain = { inbox.prepare(key, force = true) },
                    onApproveTransfer = {
                        inbox.approveTransfer(
                            key,
                            inboxState.preparations[key] as? Preparation.Ready,
                        )
                    },
                    checking = key in inboxState.checking,
                    onCheckStatus = { inbox.checkStatus(key) },
                    onBack = pop,
                )
                // Opening a transfer fetches a fresh transaction and reads it on this phone. It
                // is a read and nothing more: no wallet opens until the owner taps Approve.
                LaunchedEffect(key) { inbox.prepare(key) }
            }
        }
    }
}

private object Routes {
    const val CONNECTIONS = "connections"
    const val ADD = "add"
    const val LIVE = "live"
    const val WALLET = "wallet"
    const val DETAILS = "details/"
    const val INBOX = "inbox"
    const val INBOX_FOR = "inbox/"
    const val REQUEST = "request/"
}

@Composable
private fun ConnectionDetailsRoute(
    viewModel: ConnectionsViewModel,
    state: ConnectionsUiState,
    id: String,
    onBack: () -> Unit,
    onPendingRequests: () -> Unit,
) {
    val connection = state.connections.firstOrNull { it.id == id }
    if (connection == null) {
        // Removed, or gone after a restart: back to the list.
        LaunchedEffect(id, state.loaded) { if (state.loaded) onBack() }
        return
    }
    // The phone fetches when the owner selects a connection (docs/protocol.md).
    LaunchedEffect(id) { viewModel.refresh(id) }
    ConnectionDetailsScreen(
        connection = connection,
        refreshing = id in state.refreshing,
        disconnect = state.disconnect?.takeIf { it.id == id },
        message = state.message,
        onBack = onBack,
        onRefresh = { viewModel.refresh(id) },
        onRename = { viewModel.rename(id, it) },
        onDisconnect = { viewModel.askToDisconnect(id) },
        onConfirmDisconnect = viewModel::confirmDisconnect,
        onConfirmRemove = viewModel::confirmRemove,
        onDismissDisconnect = viewModel::dismissDisconnect,
        onMessageShown = viewModel::messageShown,
        onPendingRequests = onPendingRequests,
    )
}

@Composable
private fun LiveTestRoute(viewModel: LiveCommandViewModel) {
    val activity = LocalActivity.current
    // The live stream exists only while its screen is open (docs/protocol.md); a rotation keeps it.
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.disconnect() }
    }
    LiveCommandRoute(viewModel)
}
