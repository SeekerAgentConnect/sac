package io.github.brrenat.seekervault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.activity.ActivityDetailsScreen
import io.github.brrenat.seekervault.activity.ActivityScreen
import io.github.brrenat.seekervault.activity.ActivityViewModel
import io.github.brrenat.seekervault.activity.openLink
import io.github.brrenat.seekervault.connections.AddConnectionRoute
import io.github.brrenat.seekervault.connections.ConnectionDetailsScreen
import io.github.brrenat.seekervault.connections.ConnectionsScreen
import io.github.brrenat.seekervault.connections.ConnectionsUiState
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.connections.InboxSummary
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.inbox.InboxViewModel
import io.github.brrenat.seekervault.inbox.PendingRequestsScreen
import io.github.brrenat.seekervault.inbox.RequestGoneScreen
import io.github.brrenat.seekervault.inbox.RequestPanel
import io.github.brrenat.seekervault.inbox.inboxCounts
import io.github.brrenat.seekervault.inbox.pendingKeys
import io.github.brrenat.seekervault.live.LiveCommandRoute
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.policy.PolicyEditorScreen
import io.github.brrenat.seekervault.policy.PolicyEditorViewModel
import io.github.brrenat.seekervault.ui.ChromeTags
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.Tab
import io.github.brrenat.seekervault.ui.TabBar
import io.github.brrenat.seekervault.wallet.WalletScreen
import io.github.brrenat.seekervault.wallet.WalletViewModel
import java.time.Instant

/**
 * The app's screens and the chrome that holds them (SEE-57).
 *
 * Four roots sit under the floating tab bar — Home, Requests, Wallet, Activity — and everything
 * else is pushed on top of the root the owner was on: a connection's details and its Rules, Add
 * connection, one activity record, and the Stage 1 live test. The back stack is still a list of
 * route strings, so it survives rotation and process death, and no route carries a secret.
 *
 * One screen is not pushed but layered: the request review. It opens *over* whatever the owner was
 * looking at, which is why the route under it is drawn first and the panel over it — the owner
 * never loses their place to read one request.
 */
@Composable
fun SeekerVaultApp(
    connections: ConnectionsViewModel,
    inbox: InboxViewModel,
    wallet: WalletViewModel,
    history: ActivityViewModel,
    policy: PolicyEditorViewModel,
    live: LiveCommandViewModel,
) {
    var stack by rememberSaveable { mutableStateOf(listOf(Routes.HOME)) }
    val push = { route: String -> stack = stack + route }
    val pop = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }
    val state by connections.state.collectAsStateWithLifecycle()
    val inboxState by inbox.state.collectAsStateWithLifecycle()
    val walletState by wallet.state.collectAsStateWithLifecycle()
    val historyState by history.state.collectAsStateWithLifecycle()
    val policyState by policy.state.collectAsStateWithLifecycle()
    val route = stack.last()
    val (waitingForYou, _) = inboxCounts(inboxState)
    val tabs =
        TabBar(
            tabs =
                listOf(
                    Tab(stringResource(R.string.tab_home), Glyph.Home, ChromeTags.HOME),
                    Tab(
                        stringResource(R.string.tab_requests),
                        Glyph.Requests,
                        ChromeTags.REQUESTS,
                        alerts = waitingForYou > 0,
                    ),
                    Tab(stringResource(R.string.tab_wallet), Glyph.Wallet, ChromeTags.WALLET),
                    Tab(stringResource(R.string.tab_activity), Glyph.Activity, ChromeTags.ACTIVITY),
                ),
            selected = Routes.ROOTS.indexOf(stack.first()).coerceAtLeast(0),
            // A tab is a root, not a push: it replaces the stack rather than growing it, so the
            // fourth tap on a tab is the same screen as the first.
            onSelect = { stack = listOf(Routes.ROOTS[it]) },
        )
    if (route.startsWith(Routes.REQUEST)) {
        // The review opens over the screen it was opened from, and that screen keeps its place.
        val under = stack.getOrNull(stack.size - 2) ?: Routes.REQUESTS
        Box(Modifier.fillMaxSize()) {
            Screen(
                route = under,
                stack = stack,
                setStack = { stack = it },
                connections = connections,
                inbox = inbox,
                wallet = wallet,
                history = history,
                policy = policy,
                live = live,
                state = state,
                inboxState = inboxState,
                walletState = walletState,
                historyState = historyState,
                policyState = policyState,
                tabs = tabs,
            )
            val (connectionId, requestId) = route.removePrefix(Routes.REQUEST).split('/', limit = 2)
            val key = RequestKey(connectionId, requestId)
            val known =
                inboxState.inbox.result(key) != null || inboxState.inbox.pendingRequest(key) != null
            if (!known) {
                RequestGoneScreen(onBack = pop)
            } else {
                RequestPanel(
                    key = key,
                    state = inboxState,
                    now = Instant.now(),
                    viewModel = inbox,
                    // Moving between pending requests is a gesture inside the panel, so it swaps
                    // the route in place instead of stacking one review on another.
                    onMoveTo = { stack = stack.dropLast(1) + requestRoute(it) },
                    onRules = { push(Routes.POLICY + connectionId) },
                    onDismiss = pop,
                )
            }
        }
    } else {
        Screen(
            route = route,
            stack = stack,
            setStack = { stack = it },
            connections = connections,
            inbox = inbox,
            wallet = wallet,
            history = history,
            policy = policy,
            live = live,
            state = state,
            inboxState = inboxState,
            walletState = walletState,
            historyState = historyState,
            policyState = policyState,
            tabs = tabs,
        )
    }
}

private fun requestRoute(key: RequestKey) = "${Routes.REQUEST}${key.connectionId}/${key.requestId}"

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun Screen(
    route: String,
    stack: List<String>,
    setStack: (List<String>) -> Unit,
    connections: ConnectionsViewModel,
    inbox: InboxViewModel,
    wallet: WalletViewModel,
    history: ActivityViewModel,
    policy: PolicyEditorViewModel,
    live: LiveCommandViewModel,
    state: ConnectionsUiState,
    inboxState: io.github.brrenat.seekervault.inbox.InboxUiState,
    walletState: io.github.brrenat.seekervault.wallet.WalletUiState,
    historyState: io.github.brrenat.seekervault.activity.ActivityUiState,
    policyState: io.github.brrenat.seekervault.policy.PolicyUiState,
    tabs: TabBar,
) {
    val push = { added: String -> setStack(stack + added) }
    val pop = { setStack(stack.dropLast(1)) }
    when {
        route == Routes.HOME -> {
            val (waitingForYou, toSend) = inboxCounts(inboxState)
            ConnectionsScreen(
                state = state,
                inboxState = inboxState,
                onOpen = { push(Routes.DETAILS + it) },
                onAdd = { push(Routes.ADD) },
                onLiveTest = { push(Routes.LIVE) },
                onMessageShown = connections::messageShown,
                inbox = InboxSummary(waitingForYou, toSend),
                onInbox = { setStack(listOf(Routes.REQUESTS)) },
                onOpenRequest = { push(requestRoute(it)) },
                wallet = walletState.wallet,
                onWallet = { setStack(listOf(Routes.WALLET)) },
                onRefreshConnection = connections::refresh,
                tabs = tabs,
            )
            // The rules are read for what is waiting, so a row can say what they make of it. It is
            // a read of this phone's own files and nothing else (SAW-028).
            LaunchedEffect(inboxState.inbox) { inbox.reviewPending() }
        }
        route == Routes.WALLET ->
            WalletScreen(
                state = walletState,
                onChooseNetwork = wallet::chooseNetwork,
                onConnect = wallet::connect,
                onDisconnect = wallet::disconnect,
                onPublishAgain = wallet::publishAgain,
                onBack = null,
                tabs = tabs,
            )
        route == Routes.ADD ->
            AddConnectionRoute(
                viewModel = connections,
                onBack = pop,
                // Show the new connection in place of the Add screen.
                onPaired = { setStack(stack.dropLast(1) + (Routes.DETAILS + it.id)) },
            )
        route == Routes.LIVE -> LiveTestRoute(live, pop)
        route == Routes.ACTIVITY ->
            ActivityScreen(
                state = historyState,
                onOpen = { push("${Routes.RECORD}${it.connectionId}/${it.requestId}") },
                onRefresh = history::refresh,
                onClear = history::clear,
                onBack = null,
                tabs = tabs,
            )
        route.startsWith(Routes.RECORD) -> {
            val (connectionId, requestId) = route.removePrefix(Routes.RECORD).split('/', limit = 2)
            val record = historyState.record(RequestKey(connectionId, requestId))
            if (record == null) {
                RequestGoneScreen(onBack = pop)
            } else {
                val context = LocalContext.current
                ActivityDetailsScreen(
                    record = record,
                    // The link goes to whatever app opens links. This app fetches nothing from it,
                    // and a phone with nothing to open it with is told so rather than left silent.
                    onOpenExplorer = { if (!openLink(context, it)) history.linkFailed() },
                    linkFailed = historyState.linkFailed,
                    onMessageShown = history::messageShown,
                    onBack = pop,
                )
            }
        }
        route.startsWith(Routes.DETAILS) -> {
            val id = route.removePrefix(Routes.DETAILS)
            ConnectionDetailsRoute(
                viewModel = connections,
                state = state,
                id = id,
                pending = inboxState.inbox.pendingKeys(id).size,
                onBack = pop,
                onPendingRequests = { push(Routes.INBOX_FOR + id) },
                onRules = { push(Routes.POLICY + id) },
            )
        }
        route.startsWith(Routes.POLICY) -> {
            val id = route.removePrefix(Routes.POLICY)
            val close = {
                policy.close()
                pop()
            }
            PolicyEditorScreen(
                label = state.connections.firstOrNull { it.id == id }?.label.orEmpty(),
                state = policyState,
                onEdit = policy::edit,
                onStartOver = policy::startOver,
                onSave = policy::save,
                onMessageShown = policy::messageShown,
                onClose = close,
            )
            // The rules are read from disk when the screen opens. Opening the connection that is
            // already open keeps unsaved edits, so a rotation doesn't throw them away.
            LaunchedEffect(id) { policy.open(id) }
        }
        route == Routes.REQUESTS || route.startsWith(Routes.INBOX_FOR) -> {
            val only = route.removePrefix(Routes.REQUESTS).removePrefix("/").ifEmpty { null }
            PendingRequestsScreen(
                state = inboxState,
                connectionId = only,
                now = Instant.now(),
                onOpen = { push(requestRoute(it)) },
                onRefresh = { inbox.refresh(only) },
                onAnswer = inbox::answer,
                onBack = if (only == null) null else pop,
                tabs = if (only == null) tabs else null,
            )
            LaunchedEffect(inboxState.inbox) { inbox.reviewPending() }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val REQUESTS = "requests"
    const val WALLET = "wallet"
    const val ACTIVITY = "activity"
    val ROOTS = listOf(HOME, REQUESTS, WALLET, ACTIVITY)

    const val ADD = "add"
    const val LIVE = "live"
    const val RECORD = "record/"
    const val DETAILS = "details/"
    const val POLICY = "policy/"
    const val INBOX_FOR = "requests/"
    const val REQUEST = "request/"
}

@Composable
private fun ConnectionDetailsRoute(
    viewModel: ConnectionsViewModel,
    state: ConnectionsUiState,
    id: String,
    pending: Int,
    onBack: () -> Unit,
    onPendingRequests: () -> Unit,
    onRules: () -> Unit,
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
        pending = pending,
        onBack = onBack,
        onRefresh = { viewModel.refresh(id) },
        onRename = { viewModel.rename(id, it) },
        onDisconnect = { viewModel.askToDisconnect(id) },
        onConfirmDisconnect = viewModel::confirmDisconnect,
        onConfirmRemove = viewModel::confirmRemove,
        onDismissDisconnect = viewModel::dismissDisconnect,
        onMessageShown = viewModel::messageShown,
        onPendingRequests = onPendingRequests,
        onRules = onRules,
    )
}

@Composable
private fun LiveTestRoute(viewModel: LiveCommandViewModel, onBack: () -> Unit) {
    val activity = LocalActivity.current
    // The live stream exists only while its screen is open (docs/protocol.md); a rotation keeps it.
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.disconnect() }
    }
    LiveCommandRoute(viewModel, onBack)
}
