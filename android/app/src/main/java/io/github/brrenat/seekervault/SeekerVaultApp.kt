package io.github.brrenat.seekervault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import io.github.brrenat.seekervault.inbox.NotificationOpenStatus
import io.github.brrenat.seekervault.inbox.NotificationRequestStateScreen
import io.github.brrenat.seekervault.inbox.PendingRequestsScreen
import io.github.brrenat.seekervault.inbox.Preparation
import io.github.brrenat.seekervault.inbox.RequestDetailsScreen
import io.github.brrenat.seekervault.inbox.RequestGoneScreen
import io.github.brrenat.seekervault.inbox.inboxCounts
import io.github.brrenat.seekervault.inbox.inboxItems
import io.github.brrenat.seekervault.inbox.key
import io.github.brrenat.seekervault.live.LiveCommandRoute
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.notifications.RequestNotificationPermission
import io.github.brrenat.seekervault.operations.OperationViewModel
import io.github.brrenat.seekervault.operations.OperationsUiState
import io.github.brrenat.seekervault.operations.ProposalReviewScreen
import io.github.brrenat.seekervault.operations.ProposalsScreen
import io.github.brrenat.seekervault.policy.PolicyEditorScreen
import io.github.brrenat.seekervault.policy.PolicyEditorViewModel
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.executable
import io.github.brrenat.seekervault.ui.BottomDestination
import io.github.brrenat.seekervault.ui.SeekerBottomBar
import io.github.brrenat.seekervault.ui.SeekerSheet
import io.github.brrenat.seekervault.ui.SheetBackplate
import io.github.brrenat.seekervault.ui.SheetInputBarrier
import io.github.brrenat.seekervault.wallet.WalletScreen
import io.github.brrenat.seekervault.wallet.WalletViewModel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The app's screens: Connections first, then Global rules or a connection's details and overrides,
 * Add connection, Pending requests and Request details, Activity and one record, Wallet, and the
 * Stage 1 live test. The back stack is a list of route strings, so it survives rotation and process
 * death; no route carries a secret.
 */
@Composable
fun SeekerVaultApp(
    connections: ConnectionsViewModel,
    inbox: InboxViewModel,
    wallet: WalletViewModel,
    history: ActivityViewModel,
    policy: PolicyEditorViewModel,
    globalPolicy: PolicyEditorViewModel,
    live: LiveCommandViewModel,
    notificationTaps: StateFlow<MainActivity.NotificationTap?>,
    feedTaps: StateFlow<MainActivity.FeedTap?> = MutableStateFlow(null),
    /** A publisher's proposals, and the one path from one of them to the wallet (SEE-93). */
    operations: OperationViewModel? = null,
) {
    var stack by rememberSaveable { mutableStateOf(listOf(Routes.CONNECTIONS)) }
    var closingSheet by remember { mutableStateOf(false) }
    var promotedRoute by remember { mutableStateOf<String?>(null) }
    var backplateTargetSize by rememberSaveable { mutableStateOf<Int?>(null) }
    var requestedPolicyClose by remember { mutableStateOf<String?>(null) }
    var policyCloseRequest by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val push = { route: String -> stack = stack + route }
    val pop = {
        if (stack.size > 1 && !closingSheet) {
            val routeBeingClosed = stack.last()
            closingSheet = true
            scope.launch {
                delay(240)
                // A root-navigation tap can replace the stack during the exit motion. In that
                // case there is no longer a sheet to remove.
                if (stack.size > 1 && stack.last() == routeBeingClosed) {
                    promotedRoute = stack.getOrNull(stack.lastIndex - 1)?.takeIf { stack.size > 2 }
                    stack = stack.dropLast(1)
                }
                closingSheet = false
            }
        }
    }
    val state by connections.state.collectAsStateWithLifecycle()
    val inboxState by inbox.state.collectAsStateWithLifecycle()
    val walletState by wallet.state.collectAsStateWithLifecycle()
    val historyState by history.state.collectAsStateWithLifecycle()
    val policyState by policy.state.collectAsStateWithLifecycle()
    val globalPolicyState by globalPolicy.state.collectAsStateWithLifecycle()
    val notificationTap by notificationTaps.collectAsStateWithLifecycle()
    val feedTap by feedTaps.collectAsStateWithLifecycle()
    val operationsState by
        (operations?.state ?: MutableStateFlow(OperationsUiState())).collectAsStateWithLifecycle()
    val openOperation by
        (operations?.review ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    val root = stack.first()
    val route = stack.last()
    RequestNotificationPermission(
        enabled =
            BuildConfig.FIREBASE_CONFIGURED && state.loaded && state.connections.any { it.usable }
    )
    LaunchedEffect(notificationTap?.sequence) {
        val key = notificationTap?.key ?: return@LaunchedEffect
        closingSheet = false
        promotedRoute = null
        backplateTargetSize = null
        requestedPolicyClose = null
        stack = listOf(Routes.CONNECTIONS, requestRoute(key))
        inbox.openFromNotification(key)
    }
    // A proposal alert opens the proposal it is about (SEE-92 routed it, SEE-93 gave it somewhere
    // to land). Both IDs travelled with the notification and were validated before they got here,
    // and the screen underneath is the feed's own list, so Back goes where it would have anyway.
    // Arriving prepares nothing, signs nothing and sends nothing: it opens a review.
    LaunchedEffect(feedTap?.sequence) {
        val ref = feedTap?.ref ?: return@LaunchedEffect
        closingSheet = false
        promotedRoute = null
        backplateTargetSize = null
        requestedPolicyClose = null
        stack =
            listOf(
                Routes.CONNECTIONS,
                Routes.DETAILS + ref.connectionId,
                Routes.OPERATIONS + ref.connectionId,
                operationRoute(ref.connectionId, ref.proposalId),
            )
    }
    BackHandler(enabled = stack.size > 1 || root != Routes.CONNECTIONS) {
        if (stack.size > 1) pop() else stack = listOf(Routes.CONNECTIONS)
    }
    LaunchedEffect(promotedRoute) {
        if (promotedRoute != null) {
            delay(320)
            promotedRoute = null
        }
    }
    val pendingKeys = inboxItems(inboxState.inbox, null).pending.map { it.key }
    val rootModifier =
        Modifier.navigationBarsPadding()
            .padding(bottom = 80.dp)
            .then(if (stack.size > 1) Modifier.clearAndSetSemantics {} else Modifier)
    // Badges and any open review follow successful rule writes immediately. The stored drafts
    // change only after disk writes succeed, so in-flight edits never affect an assessment.
    LaunchedEffect(pendingKeys, policyState.stored, globalPolicyState.stored) {
        pendingKeys.forEach(inbox::review)
    }
    // A backplate can jump over more than one sheet, but each editor still gets its own guarded
    // close request. Dirty rules therefore ask before they are discarded and each ViewModel is
    // cleared before the next sheet is removed.
    LaunchedEffect(backplateTargetSize, stack, closingSheet, requestedPolicyClose) {
        val targetSize = backplateTargetSize ?: return@LaunchedEffect
        if (stack.size <= targetSize) {
            backplateTargetSize = null
            requestedPolicyClose = null
        } else if (!closingSheet && requestedPolicyClose == null) {
            val top = stack.last()
            if (top == Routes.GLOBAL_POLICY || top.startsWith(Routes.POLICY)) {
                policyCloseRequest += 1
                requestedPolicyClose = top
            } else {
                pop()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        when (root) {
            Routes.CONNECTIONS -> {
                val (waitingForYou, toSend) = inboxCounts(inboxState)
                val pending = inboxItems(inboxState.inbox, null).pending
                ConnectionsScreen(
                    // A detail sheet owns transient connection messages while it is open.
                    // Keeping
                    // the Home message host quiet avoids announcing the same result twice
                    // through
                    // the still-mounted layer underneath.
                    state = if (stack.size == 1) state else state.copy(message = null),
                    onOpen = { push(Routes.DETAILS + it) },
                    onAdd = { stack = listOf(Routes.ADD) },
                    onLiveTest = { push(Routes.LIVE) },
                    onMessageShown = connections::messageShown,
                    inbox = InboxSummary(waitingForYou, toSend),
                    onInbox = { stack = listOf(Routes.INBOX) },
                    wallet = walletState.wallet,
                    onWallet = { stack = listOf(Routes.WALLET) },
                    onGlobalRules = { push(Routes.GLOBAL_POLICY) },
                    requests = pending,
                    requestAssessments = inboxState.assessments,
                    onOpenRequest = {
                        push("${Routes.REQUEST}${it.connectionId}/${it.requestId}")
                    },
                    modifier = rootModifier,
                )
            }
            Routes.WALLET ->
                WalletScreen(
                    state = walletState,
                    onChooseNetwork = wallet::chooseNetwork,
                    onConnect = wallet::connect,
                    onDisconnect = wallet::disconnect,
                    onPublishAgain = wallet::publishAgain,
                    onBack = { stack = listOf(Routes.CONNECTIONS) },
                    modifier = rootModifier,
                )
            Routes.ACTIVITY ->
                ActivityScreen(
                    state = historyState,
                    onOpen = { push("${Routes.RECORD}${it.connectionId}/${it.requestId}") },
                    onRefresh = history::refresh,
                    onClear = history::clear,
                    onBack = { stack = listOf(Routes.CONNECTIONS) },
                    modifier = rootModifier,
                    network =
                        walletState.wallet?.let {
                            io.github.brrenat.seekervault.wallet.networkText(it.network)
                        },
                )
            Routes.INBOX ->
                PendingRequestsScreen(
                    state = inboxState,
                    connectionId = null,
                    now = Instant.now(),
                    onOpen = { push("${Routes.REQUEST}${it.connectionId}/${it.requestId}") },
                    onRefresh = { inbox.refresh(null) },
                    onBack = { stack = listOf(Routes.CONNECTIONS) },
                    modifier = rootModifier,
                    onReject = {
                        inbox.answer(
                            it,
                            io.github.brrenat.seekervault.connections.Answer.Reject,
                        )
                    },
                )
            Routes.ADD ->
                AddConnectionRoute(
                    viewModel = connections,
                    onBack = { stack = listOf(Routes.CONNECTIONS) },
                    onPaired = {
                        stack = listOf(Routes.CONNECTIONS, Routes.DETAILS + it.id)
                    },
                    modifier = rootModifier,
                )
        }

        if (stack.size > 1) SheetInputBarrier(Modifier.zIndex(5f))
        stack.drop(1).dropLast(1).forEachIndexed { index, backRoute ->
            val routeIndex = index + 1
            SheetBackplate(
                depth = stack.lastIndex - routeIndex,
                title = sheetTitle(backRoute, state),
                onClick = { backplateTargetSize = routeIndex + 1 },
            )
        }
        if (stack.size > 1) {
            SeekerSheet(
                depth = (stack.size - 2).coerceAtMost(1),
                motionKey = route,
                visible = !closingSheet,
                promoteFromBackplate = promotedRoute == route,
            ) {
                when {
                    route == Routes.LIVE -> LiveTestRoute(live)
                    route.startsWith(Routes.RECORD) -> {
                        val (connectionId, requestId) =
                            route.removePrefix(Routes.RECORD).split('/', limit = 2)
                        val record = historyState.record(RequestKey(connectionId, requestId))
                        if (record == null) {
                            RequestGoneScreen(onBack = pop)
                        } else {
                            val context = LocalContext.current
                            ActivityDetailsScreen(
                                record = record,
                                // The link goes to whatever app opens links. This app fetches
                                // nothing from it,
                                // and a phone with nothing to open it with is told so rather than
                                // left silent.
                                onOpenExplorer = {
                                    if (!openLink(context, it)) history.linkFailed()
                                },
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
                            onBack = pop,
                            onPendingRequests = { push(Routes.INBOX_FOR + id) },
                            onRules = { push(Routes.POLICY + id) },
                            // A feed proposes rather than requests, so its signals are where its
                            // pending requests would be (SEE-93).
                            onSignals =
                                if (operations == null) null
                                else ({ push(Routes.OPERATIONS + id) }),
                            signals = operationsState.records.count { it.connectionId == id },
                        )
                    }
                    route.startsWith(Routes.OPERATIONS) && operations != null -> {
                        val id = route.removePrefix(Routes.OPERATIONS)
                        ProposalsScreen(
                            label = state.connections.firstOrNull { it.id == id }?.label.orEmpty(),
                            records = operationsState.records.filter { it.connectionId == id },
                            standings = operations::standing,
                            refreshing = id in operationsState.refreshing,
                            now = Instant.now(),
                            onOpen = { push(operationRoute(id, it.key.proposalId)) },
                            onRefresh = { operations.refresh(id) },
                            onBack = pop,
                        )
                        // The feed is read when the owner opens it, exactly as a connection's
                        // requests are (docs/protocol.md). It publishes nothing.
                        LaunchedEffect(id) { operations.refresh(id) }
                    }
                    route.startsWith(Routes.OPERATION) && operations != null -> {
                        val (id, proposalId) =
                            route.removePrefix(Routes.OPERATION).split('/', limit = 2).let {
                                (it.firstOrNull() ?: "") to (it.getOrNull(1) ?: "")
                            }
                        val open = openOperation?.takeIf { it.proposalId == proposalId }
                        if (open == null) {
                            // Removed, expired out of the feed, or opened before the store was
                            // read: back to the list rather than an empty review.
                            LaunchedEffect(proposalId, operationsState.loaded) {
                                operations.open(id, proposalId)
                                if (
                                    operationsState.loaded &&
                                        operationsState.records.none {
                                            it.connectionId == id && it.key.proposalId == proposalId
                                        }
                                ) {
                                    pop()
                                }
                            }
                        } else {
                            val linkContext = LocalContext.current
                            ProposalReviewScreen(
                                review = open,
                                label =
                                    state.connections.firstOrNull { it.id == id }?.label.orEmpty(),
                                wallet = walletState.wallet,
                                now = Instant.now(),
                                onChoose = operations::choose,
                                onPrepare = operations::prepare,
                                onApprove = { operations.approve(walletState.wallet) },
                                onDismiss = { operations.dismiss(id, proposalId) },
                                onAcknowledge = operations::acknowledge,
                                onBack = {
                                    operations.close()
                                    pop()
                                },
                                onRules = { push(Routes.POLICY + id) },
                                // Handed to whatever opens links, exactly as a transfer's explorer
                                // link is. This app fetches nothing from any of them.
                                onOpenLink = { openLink(linkContext, it) },
                            )
                        }
                    }
                    route.startsWith(Routes.POLICY) -> {
                        val id = route.removePrefix(Routes.POLICY)
                        val close = {
                            if (requestedPolicyClose == route) requestedPolicyClose = null
                            policy.close()
                            pop()
                        }
                        PolicyEditorScreen(
                            label = state.connections.firstOrNull { it.id == id }?.label.orEmpty(),
                            state = policyState,
                            onEdit = policy::edit,
                            onStartOver = policy::startOver,
                            onResetConnection = policy::resetConnectionOverrides,
                            onOpenGlobal = { push(Routes.GLOBAL_POLICY) },
                            onSave = policy::save,
                            onMessageShown = policy::messageShown,
                            onClose = close,
                            closeRequest =
                                if (requestedPolicyClose == route) policyCloseRequest else 0,
                            onCloseRequestCancelled = {
                                if (requestedPolicyClose == route) {
                                    requestedPolicyClose = null
                                    backplateTargetSize = null
                                }
                            },
                        )
                        // The rules are read from disk when the screen opens. Opening the
                        // connection that is
                        // already open keeps unsaved edits, so a rotation doesn't throw them away.
                        LaunchedEffect(id) { policy.open(id) }
                    }
                    route == Routes.GLOBAL_POLICY -> {
                        val close = {
                            if (requestedPolicyClose == route) requestedPolicyClose = null
                            globalPolicy.close()
                            pop()
                            // A local draft underneath stays byte-for-byte intact. Only the
                            // inherited context
                            // is read again after a global edit.
                            policy.refreshGlobal()
                        }
                        PolicyEditorScreen(
                            label = "",
                            state = globalPolicyState,
                            onEdit = globalPolicy::edit,
                            onStartOver = globalPolicy::startOver,
                            onResetConnection = {},
                            onOpenGlobal = {},
                            onSave = globalPolicy::save,
                            onMessageShown = globalPolicy::messageShown,
                            onClose = close,
                            closeRequest =
                                if (requestedPolicyClose == route) policyCloseRequest else 0,
                            onCloseRequestCancelled = {
                                if (requestedPolicyClose == route) {
                                    requestedPolicyClose = null
                                    backplateTargetSize = null
                                }
                            },
                        )
                        LaunchedEffect(Unit) { globalPolicy.openGlobal() }
                    }
                    route.startsWith(Routes.INBOX_FOR) ->
                        PendingRequestsScreen(
                            state = inboxState,
                            connectionId =
                                route.removePrefix(Routes.INBOX).removePrefix("/").ifEmpty { null },
                            now = Instant.now(),
                            onOpen = {
                                push("${Routes.REQUEST}${it.connectionId}/${it.requestId}")
                            },
                            onRefresh = {
                                inbox.refresh(
                                    route.removePrefix(Routes.INBOX).removePrefix("/").ifEmpty {
                                        null
                                    }
                                )
                            },
                            onBack = pop,
                            onReject = {
                                inbox.answer(
                                    it,
                                    io.github.brrenat.seekervault.connections.Answer.Reject,
                                )
                            },
                            inSheet = true,
                        )
                    route.startsWith(Routes.REQUEST) -> {
                        val (connectionId, requestId) =
                            route.removePrefix(Routes.REQUEST).split('/', limit = 2)
                        val key = RequestKey(connectionId, requestId)
                        val result = inboxState.inbox.result(key)
                        val request = result?.request ?: inboxState.inbox.pendingRequest(key)
                        val notificationOpen = inboxState.notificationOpen?.takeIf { it.key == key }
                        if (
                            notificationOpen?.status == NotificationOpenStatus.Loading ||
                                notificationOpen?.status == NotificationOpenStatus.Removed ||
                                notificationOpen?.status == NotificationOpenStatus.Revoked ||
                                notificationOpen?.status == NotificationOpenStatus.Unavailable
                        ) {
                            NotificationRequestStateScreen(
                                status = notificationOpen.status,
                                onRetry = { inbox.openFromNotification(key) },
                                onBack = pop,
                            )
                        } else if (request == null) {
                            if (notificationOpen != null) {
                                NotificationRequestStateScreen(
                                    status = NotificationOpenStatus.Gone,
                                    onRetry = { inbox.openFromNotification(key) },
                                    onBack = pop,
                                )
                            } else {
                                RequestGoneScreen(onBack = pop)
                            }
                        } else {
                            RequestDetailsScreen(
                                request = request,
                                source =
                                    inboxState.connections.firstOrNull { it.id == connectionId },
                                result = result,
                                sending = key in inboxState.sending,
                                now = Instant.now(),
                                onAnswer = { inbox.answer(key, it) },
                                onApprove = { inbox.approve(key, inboxState.wallet) },
                                onSendAgain = { inbox.sendAgain(key) },
                                wallet = inboxState.wallet,
                                signingProblem =
                                    inboxState.problem.takeIf { inboxState.problemKey == key },
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
                                assessment = inboxState.assessments[key],
                                acknowledged =
                                    inboxState.acknowledged[key] != null &&
                                        inboxState.acknowledged[key] ==
                                            inboxState.assessments[key]?.consent,
                                onAcknowledge = { inbox.acknowledge(key, it) },
                                onRules = { push(Routes.POLICY + connectionId) },
                                // Whether this build supports the server this came from (SEE-88).
                                // Read here rather than stored: it depends on the plugins this
                                // build carries, which the connection on disk knows nothing about.
                                executable = inbox.support(connectionId).executable,
                                onBack = pop,
                            )
                            // Opening a transfer fetches a fresh transaction and reads it on this
                            // phone. It
                            // is a read and nothing more: no wallet opens until the owner taps
                            // Approve.
                            LaunchedEffect(key) { inbox.prepare(key) }
                            // And the rules are read for it, every time it is opened. Nothing is
                            // remembered
                            // between visits, so rules changed in between are the ones that apply
                            // (SAW-028).
                            LaunchedEffect(key) { inbox.review(key) }
                        }
                    }
                }
            }
        }

        SeekerBottomBar(
            destinations =
                listOf(
                    BottomDestination(
                        Routes.CONNECTIONS,
                        Icons.Outlined.Inbox,
                        stringResource(R.string.nav_home),
                    ),
                    BottomDestination(
                        Routes.INBOX,
                        Icons.Outlined.Draw,
                        stringResource(R.string.nav_requests),
                        inboxCounts(inboxState).first > 0,
                    ),
                    BottomDestination(
                        Routes.WALLET,
                        Icons.Outlined.Wallet,
                        stringResource(R.string.nav_wallet),
                    ),
                    BottomDestination(
                        Routes.ACTIVITY,
                        Icons.Outlined.History,
                        stringResource(R.string.nav_activity),
                    ),
                ),
            selected = root,
            onSelect = { stack = listOf(it) },
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .then(if (stack.size > 1) Modifier.clearAndSetSemantics {} else Modifier),
        )
    }
}

private fun sheetTitle(route: String, state: ConnectionsUiState): String =
    when {
        route.startsWith(Routes.DETAILS) ->
            state.connections.firstOrNull { it.id == route.removePrefix(Routes.DETAILS) }?.label
                ?: "Connection"
        route.startsWith(Routes.POLICY) -> "Rules"
        route == Routes.GLOBAL_POLICY -> "Global rules"
        route.startsWith(Routes.REQUEST) -> "Review request"
        route.startsWith(Routes.RECORD) -> "Activity"
        route == Routes.ADD -> "Add connection"
        route == Routes.LIVE -> "Live test"
        else -> "Details"
    }

private object Routes {
    const val CONNECTIONS = "connections"
    const val ADD = "add"
    const val LIVE = "live"
    const val WALLET = "wallet"
    const val ACTIVITY = "activity"
    const val RECORD = "record/"
    const val DETAILS = "details/"
    const val POLICY = "policy/"
    const val GLOBAL_POLICY = "policy-global"
    const val INBOX = "inbox"
    const val INBOX_FOR = "inbox/"
    const val REQUEST = "request/"
    const val OPERATIONS = "operations/"
    const val OPERATION = "operation/"
}

private fun operationRoute(connectionId: String, proposalId: String) =
    "${Routes.OPERATION}$connectionId/$proposalId"

private fun requestRoute(key: RequestKey) = "${Routes.REQUEST}${key.connectionId}/${key.requestId}"

@Composable
private fun ConnectionDetailsRoute(
    viewModel: ConnectionsViewModel,
    state: ConnectionsUiState,
    id: String,
    onBack: () -> Unit,
    onPendingRequests: () -> Unit,
    onRules: () -> Unit,
    onSignals: (() -> Unit)? = null,
    signals: Int = 0,
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
        onRules = onRules,
        // A feed has no requests addressed to it and no pending queue; what it has is signals,
        // which is the entry the same place would otherwise hold (SEE-93).
        onSignals = onSignals?.takeIf { connection.mode == ConnectionMode.GatewayFeed },
        signals = signals,
        live = state.updates.connections[id],
        support = state.support[id],
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
