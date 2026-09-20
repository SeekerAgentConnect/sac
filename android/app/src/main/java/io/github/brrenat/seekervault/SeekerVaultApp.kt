package io.github.brrenat.seekervault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.activity.ActivityRoute
import io.github.brrenat.seekervault.activity.ActivityViewModel
import io.github.brrenat.seekervault.activity.openLink
import io.github.brrenat.seekervault.connections.AddConnectionRoute
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ConnectionDetailLibraryScreen
import io.github.brrenat.seekervault.connections.ConnectionsUiState
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.connections.HomeRoute
import io.github.brrenat.seekervault.connections.HomeRouteCallbacks
import io.github.brrenat.seekervault.connections.InboxSummary
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.inbox.InboxRoute
import io.github.brrenat.seekervault.inbox.InboxRouteCallbacks
import io.github.brrenat.seekervault.inbox.InboxViewModel
import io.github.brrenat.seekervault.inbox.NotificationOpenStatus
import io.github.brrenat.seekervault.inbox.NotificationRequestStateScreen
import io.github.brrenat.seekervault.inbox.PendingItem
import io.github.brrenat.seekervault.inbox.Preparation
import io.github.brrenat.seekervault.inbox.RequestDetailsScreen
import io.github.brrenat.seekervault.inbox.RequestGoneScreen
import io.github.brrenat.seekervault.inbox.inboxCounts
import io.github.brrenat.seekervault.inbox.inboxItems
import io.github.brrenat.seekervault.inbox.key
import io.github.brrenat.seekervault.inbox.pendingItems
import io.github.brrenat.seekervault.live.LiveCommandRoute
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.notifications.RequestNotificationPermission
import io.github.brrenat.seekervault.operations.OperationViewModel
import io.github.brrenat.seekervault.operations.OperationsUiState
import io.github.brrenat.seekervault.operations.ProposalReviewScreen
import io.github.brrenat.seekervault.operations.requiresWalletHandoff
import io.github.brrenat.seekervault.policy.PolicyAddressKind
import io.github.brrenat.seekervault.policy.PolicyAddressLibraryScreen
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.PolicyAssetEditorKind
import io.github.brrenat.seekervault.policy.PolicyAssetLibraryScreen
import io.github.brrenat.seekervault.policy.PolicyEditorDraft
import io.github.brrenat.seekervault.policy.PolicyEditorViewModel
import io.github.brrenat.seekervault.policy.PolicyLibrarySheetScreen
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.executable
import io.github.brrenat.seekervault.ui.SeekerSheet
import io.github.brrenat.seekervault.ui.SheetBackplate
import io.github.brrenat.seekervault.ui.SheetInputBarrier
import io.github.brrenat.seekervault.wallet.WalletRoute
import io.github.brrenat.seekervault.wallet.WalletViewModel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The design user-flow graph. Peer tabs replace the base destination, while every sheet is a typed
 * destination pushed over the still-mounted destination beneath it. Route state contains IDs only,
 * so it is safe to restore after rotation or process death.
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
    connectionLinkTaps: StateFlow<MainActivity.ConnectionLinkTap?> = MutableStateFlow(null),
    operations: OperationViewModel? = null,
    startInLiveTest: Boolean = false,
) {
    val navigator =
        rememberAppNavigator(
            if (startInLiveTest) NavigationState(AppScreen.LiveTest) else NavigationState()
        )
    val navigation = navigator.state
    val sheets = navigation.sheets
    val route = sheets.lastOrNull()
    var closingSheet by remember { mutableStateOf(false) }
    var promotedRoute by remember { mutableStateOf<AppSheet?>(null) }
    var backplateTargetSize by remember { mutableStateOf<Int?>(null) }
    var requestedPolicyClose by remember { mutableStateOf<AppSheet?>(null) }
    var policyCloseRequest by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val pop = {
        if (navigator.state.sheets.isNotEmpty() && !closingSheet) {
            val routeBeingClosed = navigator.state.sheets.last()
            closingSheet = true
            scope.launch {
                delay(240)
                val currentSheets = navigator.state.sheets
                if (currentSheets.lastOrNull() == routeBeingClosed) {
                    promotedRoute =
                        currentSheets.getOrNull(currentSheets.lastIndex - 1)?.takeIf {
                            currentSheets.size > 1
                        }
                    // Release the guard before exposing the sheet underneath so a fast, valid
                    // follow-up tap can push its next destination immediately.
                    closingSheet = false
                    navigator.back()
                } else {
                    closingSheet = false
                }
            }
        }
    }
    val resetTransientSheetState = {
        closingSheet = false
        promotedRoute = null
        backplateTargetSize = null
        requestedPolicyClose = null
    }

    val state by connections.state.collectAsStateWithLifecycle()
    val inboxState by inbox.state.collectAsStateWithLifecycle()
    val walletState by wallet.state.collectAsStateWithLifecycle()
    val policyState by policy.state.collectAsStateWithLifecycle()
    val globalPolicyState by globalPolicy.state.collectAsStateWithLifecycle()
    val notificationTap by notificationTaps.collectAsStateWithLifecycle()
    val feedTap by feedTaps.collectAsStateWithLifecycle()
    val connectionLinkTap by connectionLinkTaps.collectAsStateWithLifecycle()
    val operationsState by
        (operations?.state ?: MutableStateFlow(OperationsUiState())).collectAsStateWithLifecycle()
    val openOperation by
        (operations?.review ?: MutableStateFlow(null)).collectAsStateWithLifecycle()

    RequestNotificationPermission(
        enabled =
            BuildConfig.FIREBASE_CONFIGURED && state.loaded && state.connections.any { it.usable }
    )
    LaunchedEffect(notificationTap?.sequence, state.loaded) {
        val key = notificationTap?.key ?: return@LaunchedEffect
        if (!state.loaded) return@LaunchedEffect
        resetTransientSheetState()
        navigator.selectTab(AppScreen.Home)
        val connection = state.connections.firstOrNull { it.id == key.connectionId }
        if (connection?.mode == ConnectionMode.Direct) {
            navigator.openReview(ReviewIdentity.Private(key.connectionId, key.requestId))
            inbox.openFromNotification(key)
        } else if (connection?.retirement != null) {
            navigator.openConnectionDetail(key.connectionId)
        }
    }
    LaunchedEffect(feedTap?.sequence, state.loaded) {
        val ref = feedTap?.ref ?: return@LaunchedEffect
        if (!state.loaded) return@LaunchedEffect
        resetTransientSheetState()
        navigator.selectTab(AppScreen.Home)
        val connection = state.connections.firstOrNull { it.id == ref.connectionId }
        if (connection?.mode == ConnectionMode.GatewayFeed) {
            navigator.openReview(ReviewIdentity.Signal(ref.connectionId, ref.proposalId))
        } else if (connection?.retirement != null) {
            navigator.openConnectionDetail(ref.connectionId)
        }
    }
    LaunchedEffect(connectionLinkTap?.sequence) {
        val tap = connectionLinkTap ?: return@LaunchedEffect
        resetTransientSheetState()
        navigator.selectTab(AppScreen.Home)
        navigator.openAddConnection()
        connections.onCode(tap.uri)
    }
    BackHandler(enabled = sheets.isNotEmpty() || navigation.screen !is AppScreen.Tab) {
        if (sheets.isNotEmpty()) pop() else navigator.back()
    }
    LaunchedEffect(promotedRoute) {
        if (promotedRoute != null) {
            delay(320)
            promotedRoute = null
        }
    }

    val pendingKeys = inboxItems(inboxState.inbox, null).pending.map { it.key }
    val commonPending =
        pendingItems(
            inboxState.inbox,
            operationsState.records,
            { record ->
                operations?.standing(record)
                    ?: io.github.brrenat.seekervault.proposals.ProposalStanding.Expired
            },
        )
    val rootModifier =
        Modifier.navigationBarsPadding()
            .then(if (sheets.isNotEmpty()) Modifier.clearAndSetSemantics {} else Modifier)
    val screenNavigationCallbacks =
        ScreenNavigationCallbacks(
            onHome = {
                resetTransientSheetState()
                navigator.selectTab(AppScreen.Home)
            },
            onInbox = {
                resetTransientSheetState()
                navigator.selectTab(AppScreen.Inbox)
            },
            onWallet = {
                resetTransientSheetState()
                navigator.selectTab(AppScreen.Wallet)
            },
            onActivity = {
                resetTransientSheetState()
                navigator.selectTab(AppScreen.Activity)
            },
        )

    LaunchedEffect(pendingKeys, policyState.stored, globalPolicyState.stored) {
        pendingKeys.forEach(inbox::review)
    }
    LaunchedEffect(backplateTargetSize, sheets, closingSheet, requestedPolicyClose) {
        val targetSize = backplateTargetSize ?: return@LaunchedEffect
        if (sheets.size <= targetSize) {
            backplateTargetSize = null
            requestedPolicyClose = null
        } else if (!closingSheet && requestedPolicyClose == null) {
            val top = sheets.last()
            if (top == AppSheet.GlobalRules || top is AppSheet.ConnectionRules) {
                policyCloseRequest += 1
                requestedPolicyClose = top
            } else {
                pop()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (navigation.screen) {
            AppScreen.Home -> {
                val (_, toSend) = inboxCounts(inboxState)
                HomeRoute(
                    connectionsState = if (sheets.isEmpty()) state else state.copy(message = null),
                    inboxSummary = InboxSummary(commonPending.size, toSend),
                    wallet = walletState.wallet,
                    requestAssessments = inboxState.assessments,
                    pendingItems = commonPending,
                    callbacks =
                        HomeRouteCallbacks(
                            onOpenConnection = navigator::openConnectionDetail,
                            onRetryConnection = connections::refresh,
                            onAddConnection = navigator::openAddConnection,
                            onMessageShown = connections::messageShown,
                            onInbox = screenNavigationCallbacks.onInbox,
                            onWallet = screenNavigationCallbacks.onWallet,
                            onGlobalRules = navigator::openGlobalRules,
                            onActivity = screenNavigationCallbacks.onActivity,
                            onOpenPending = { item ->
                                navigator.openReview(
                                    when (item) {
                                        is PendingItem.Private ->
                                            ReviewIdentity.Private(
                                                item.request.key.connectionId,
                                                item.request.key.requestId,
                                            )
                                        is PendingItem.Signal ->
                                            ReviewIdentity.Signal(item.connectionId, item.requestId)
                                    }
                                )
                            },
                        ),
                    modifier = rootModifier,
                )
            }
            AppScreen.Wallet ->
                WalletRoute(
                    viewModel = wallet,
                    onBack = { navigator.selectTab(AppScreen.Home) },
                    navigationCallbacks = screenNavigationCallbacks,
                    modifier = rootModifier,
                )
            AppScreen.Activity ->
                ActivityRoute(
                    viewModel = history,
                    onBack = { navigator.selectTab(AppScreen.Home) },
                    navigationCallbacks = screenNavigationCallbacks,
                    modifier = rootModifier,
                )
            AppScreen.Inbox ->
                InboxRoute(
                    state = inboxState,
                    feedRecords = operationsState.records,
                    feedStanding = { record ->
                        operations?.standing(record)
                            ?: io.github.brrenat.seekervault.proposals.ProposalStanding.Expired
                    },
                    now = Instant.now(),
                    callbacks =
                        InboxRouteCallbacks(
                            onRefresh = {
                                inbox.refresh(null)
                                operationsState.feeds.forEach { operations?.refresh(it.id) }
                            },
                            onOpenRequest = {
                                navigator.openReview(
                                    ReviewIdentity.Private(it.connectionId, it.requestId)
                                )
                            },
                            onOpenSignal = {
                                navigator.openReview(
                                    ReviewIdentity.Signal(it.connectionId, it.key.proposalId)
                                )
                            },
                            navigation = screenNavigationCallbacks,
                        ),
                    modifier = rootModifier,
                )
            AppScreen.AddConnection ->
                AddConnectionRoute(
                    viewModel = connections,
                    onBack = navigator::back,
                    onAdded = { navigator.selectTab(AppScreen.Home) },
                    modifier = rootModifier,
                    navigationCallbacks = screenNavigationCallbacks,
                )
            AppScreen.LiveTest -> LiveTestRoute(live)
        }

        if (sheets.isNotEmpty()) SheetInputBarrier(Modifier.zIndex(5f))
        sheets.dropLast(1).forEachIndexed { index, backRoute ->
            SheetBackplate(
                depth = sheets.lastIndex - index,
                title = sheetTitle(backRoute, state),
                onClick = { backplateTargetSize = index + 1 },
            )
        }
        route?.let { activeRoute ->
            SeekerSheet(
                depth = (sheets.size - 1).coerceAtMost(1),
                motionKey = activeRoute,
                visible = !closingSheet,
                promoteFromBackplate = promotedRoute == activeRoute,
                chrome =
                    activeRoute is AppSheet.RequestReview ||
                        (activeRoute is AppSheet.ConnectionRules &&
                            !policyState.readyForLibrarySheet(activeRoute.connectionId)) ||
                        (activeRoute is AppSheet.GlobalRules &&
                            !globalPolicyState.readyForLibrarySheet()),
            ) {
                when (activeRoute) {
                    is AppSheet.ConnectionDetail -> {
                        ConnectionDetailsRoute(
                            viewModel = connections,
                            state = state,
                            id = activeRoute.connectionId,
                            onBack = pop,
                            onRules = {
                                navigator.openConnectionRules(activeRoute.connectionId)
                            },
                            onInbox = {
                                resetTransientSheetState()
                                navigator.selectTab(AppScreen.Inbox)
                            },
                            onPairDirect = {
                                resetTransientSheetState()
                                navigator.selectTab(AppScreen.Home)
                                navigator.openAddConnection()
                            },
                            overrideCount = policyState.overrideCount(activeRoute.connectionId),
                            operationRefreshing =
                                activeRoute.connectionId in operationsState.refreshing,
                            onOperationRefresh = {
                                operations?.refresh(activeRoute.connectionId)
                            },
                        )
                        LaunchedEffect(activeRoute.connectionId) {
                            policy.open(activeRoute.connectionId)
                        }
                    }
                    is AppSheet.ConnectionRules -> {
                        val id = activeRoute.connectionId
                        val close = {
                            if (requestedPolicyClose == activeRoute) requestedPolicyClose = null
                            policy.close()
                            pop()
                        }
                        PolicyLibrarySheetScreen(
                            label = state.connections.firstOrNull { it.id == id }?.label.orEmpty(),
                            state = policyState,
                            onEdit = policy::edit,
                            onStartOver = policy::startOver,
                            onResetConnection = policy::resetConnectionOverrides,
                            onOpenGlobal = navigator::openGlobalRules,
                            onSave = policy::save,
                            onMessageShown = policy::messageShown,
                            onClose = close,
                            closeRequest =
                                if (requestedPolicyClose == activeRoute) policyCloseRequest else 0,
                            onCloseRequestCancelled = {
                                if (requestedPolicyClose == activeRoute) {
                                    requestedPolicyClose = null
                                    backplateTargetSize = null
                                }
                            },
                            onOpenAsset = { asset, kind ->
                                navigator.openAssetEditor(
                                    id,
                                    kind.toRouteKind(),
                                    asset?.routeId(),
                                )
                            },
                            onOpenAddress = { kind ->
                                navigator.openAddressEditor(id, kind.toRouteKind())
                            },
                        )
                        LaunchedEffect(id) { policy.open(id) }
                    }
                    AppSheet.GlobalRules -> {
                        val close = {
                            if (requestedPolicyClose == activeRoute) requestedPolicyClose = null
                            globalPolicy.close()
                            pop()
                            policy.refreshGlobal()
                        }
                        PolicyLibrarySheetScreen(
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
                                if (requestedPolicyClose == activeRoute) policyCloseRequest else 0,
                            onCloseRequestCancelled = {
                                if (requestedPolicyClose == activeRoute) {
                                    requestedPolicyClose = null
                                    backplateTargetSize = null
                                }
                            },
                        )
                        LaunchedEffect(Unit) { globalPolicy.openGlobal() }
                    }
                    is AppSheet.AssetEditor -> {
                        PolicyAssetLibraryScreen(
                            state = policyState,
                            asset =
                                policyState.connectionAssets().firstOrNull {
                                    it.routeId() == activeRoute.assetId
                                },
                            kind = activeRoute.kind.toPolicyKind(),
                            onEdit = policy::edit,
                            onBack = pop,
                            onEditGlobal = navigator::openGlobalRules,
                        )
                        LaunchedEffect(activeRoute.connectionId) {
                            policy.open(activeRoute.connectionId)
                        }
                    }
                    is AppSheet.AddressEditor -> {
                        PolicyAddressLibraryScreen(
                            state = policyState,
                            kind = activeRoute.kind.toPolicyKind(),
                            onEdit = policy::edit,
                            onBack = pop,
                        )
                        LaunchedEffect(activeRoute.connectionId) {
                            policy.open(activeRoute.connectionId)
                        }
                    }
                    is AppSheet.RequestReview ->
                        RequestReviewRoute(
                            route = activeRoute,
                            state = state,
                            inbox = inbox,
                            inboxState = inboxState,
                            walletState = walletState,
                            operations = operations,
                            operationsState = operationsState,
                            openOperation = openOperation,
                            navigator = navigator,
                            onBack = pop,
                        )
                    is AppSheet.WalletHandoff ->
                        WalletHandoffRoute(
                            route = activeRoute,
                            state = state,
                            inbox = inbox,
                            inboxState = inboxState,
                            walletState = walletState,
                            operations = operations,
                            operationsState = operationsState,
                            openOperation = openOperation,
                            navigator = navigator,
                            onLeave = pop,
                        )
                }
            }
        }
    }
}

@Composable
private fun RequestReviewRoute(
    route: AppSheet.RequestReview,
    state: ConnectionsUiState,
    inbox: InboxViewModel,
    inboxState: io.github.brrenat.seekervault.inbox.InboxUiState,
    walletState: io.github.brrenat.seekervault.wallet.WalletUiState,
    operations: OperationViewModel?,
    operationsState: OperationsUiState,
    openOperation: io.github.brrenat.seekervault.operations.OperationReview?,
    navigator: AppNavigator,
    onBack: () -> Unit,
) {
    when (val identity = route.identity) {
        is ReviewIdentity.Private -> {
            val key = RequestKey(identity.connectionId, identity.requestId)
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
                    onBack = onBack,
                )
            } else if (request == null) {
                if (notificationOpen != null) {
                    NotificationRequestStateScreen(
                        status = NotificationOpenStatus.Gone,
                        onRetry = { inbox.openFromNotification(key) },
                        onBack = onBack,
                    )
                } else {
                    RequestGoneScreen(onBack = onBack)
                }
            } else {
                RequestDetailsScreen(
                    request = request,
                    source = inboxState.connections.firstOrNull { it.id == identity.connectionId },
                    result = result,
                    sending = key in inboxState.sending,
                    now = Instant.now(),
                    onAnswer = { inbox.answer(key, it) },
                    onApprove = {
                        if (request.signMessage() != null) {
                            navigator.openWalletHandoff(
                                identity,
                                WalletHandoffKind.Signature,
                            )
                        } else {
                            inbox.approve(key, inboxState.wallet)
                        }
                    },
                    onSendAgain = { inbox.sendAgain(key) },
                    wallet = inboxState.wallet,
                    signingProblem = inboxState.problem.takeIf { inboxState.problemKey == key },
                    preparation = inboxState.preparations[key],
                    onPrepareAgain = { inbox.prepare(key, force = true) },
                    onApproveTransfer = {
                        navigator.openWalletHandoff(identity, WalletHandoffKind.Transfer)
                    },
                    checking = key in inboxState.checking,
                    onCheckStatus = { inbox.checkStatus(key) },
                    assessment = inboxState.assessments[key],
                    acknowledged =
                        inboxState.acknowledged[key] != null &&
                            inboxState.acknowledged[key] == inboxState.assessments[key]?.consent,
                    onAcknowledge = { inbox.acknowledge(key, it) },
                    executable = inbox.support(identity.connectionId).executable,
                    onBack = onBack,
                )
                LaunchedEffect(key) { inbox.prepare(key) }
                LaunchedEffect(key) { inbox.review(key) }
            }
        }
        is ReviewIdentity.Signal -> {
            if (operations == null) {
                RequestGoneScreen(onBack = onBack)
                return
            }
            val open = openOperation?.takeIf {
                it.connectionId == identity.connectionId && it.proposalId == identity.requestId
            }
            if (open == null) {
                LaunchedEffect(identity.requestId, operationsState.loaded) {
                    operations.open(identity.connectionId, identity.requestId)
                    if (
                        operationsState.loaded &&
                            operationsState.records.none {
                                it.connectionId == identity.connectionId &&
                                    it.key.proposalId == identity.requestId
                            }
                    ) {
                        onBack()
                    }
                }
            } else {
                val linkContext = LocalContext.current
                ProposalReviewScreen(
                    review = open,
                    label =
                        state.connections
                            .firstOrNull { it.id == identity.connectionId }
                            ?.label
                            .orEmpty(),
                    wallet = walletState.wallet,
                    now = Instant.now(),
                    onChoose = operations::choose,
                    onPrepare = operations::prepare,
                    onApprove = {
                        if (open.requiresWalletHandoff) {
                            navigator.openWalletHandoff(
                                identity,
                                WalletHandoffKind.Operation,
                            )
                        } else {
                            operations.approve(walletState.wallet)
                        }
                    },
                    onDismiss = { operations.dismiss(identity.connectionId, identity.requestId) },
                    onAcknowledge = operations::acknowledge,
                    onBack = {
                        operations.close()
                        onBack()
                    },
                    onOpenLink = { openLink(linkContext, it) },
                )
            }
        }
    }
}

@Composable
private fun WalletHandoffRoute(
    route: AppSheet.WalletHandoff,
    state: ConnectionsUiState,
    inbox: InboxViewModel,
    inboxState: io.github.brrenat.seekervault.inbox.InboxUiState,
    walletState: io.github.brrenat.seekervault.wallet.WalletUiState,
    operations: OperationViewModel?,
    operationsState: OperationsUiState,
    openOperation: io.github.brrenat.seekervault.operations.OperationReview?,
    navigator: AppNavigator,
    onLeave: () -> Unit,
) {
    var approvalStarted by
        rememberSaveable(
            route.identity.connectionId,
            route.identity.requestId,
            route.kind,
        ) {
            mutableStateOf(false)
        }
    when (val identity = route.identity) {
        is ReviewIdentity.Private -> {
            val key = RequestKey(identity.connectionId, identity.requestId)
            val result = inboxState.inbox.result(key)
            val request = result?.request ?: inboxState.inbox.pendingRequest(key)
            val problem = inboxState.problem.takeIf { inboxState.problemKey == key }
            LaunchedEffect(approvalStarted, result, problem, inboxState.sending) {
                if (!approvalStarted) return@LaunchedEffect
                when {
                    result != null -> navigator.closeReview(identity)
                    problem != null && key !in inboxState.sending -> onLeave()
                }
            }
            if (request == null) {
                RequestGoneScreen(onBack = onLeave)
            } else {
                WalletHandoffScreen(
                    summary = walletHandoffSummary(request),
                    onApprove = {
                        approvalStarted = true
                        when (route.kind) {
                            WalletHandoffKind.Transfer ->
                                inbox.approveTransfer(
                                    key,
                                    inboxState.preparations[key] as? Preparation.Ready,
                                )
                            WalletHandoffKind.Signature -> inbox.approve(key, inboxState.wallet)
                            WalletHandoffKind.Operation -> Unit
                        }
                    },
                    onDecline = {
                        inbox.answer(key, Answer.Reject)
                        navigator.closeReview(identity)
                    },
                    onLeaveWithoutAnswering = onLeave,
                )
                LaunchedEffect(key) {
                    inbox.prepare(key)
                    inbox.review(key)
                }
            }
        }
        is ReviewIdentity.Signal -> {
            val label =
                state.connections.firstOrNull { it.id == identity.connectionId }?.label
                    ?: "This connection"
            val open = openOperation?.takeIf {
                it.connectionId == identity.connectionId && it.proposalId == identity.requestId
            }
            val stillHeld =
                operationsState.records.any {
                    it.connectionId == identity.connectionId &&
                        it.key.proposalId == identity.requestId
                }
            LaunchedEffect(approvalStarted, open?.standing, open?.sending, open?.problem) {
                if (!approvalStarted) return@LaunchedEffect
                when {
                    open?.standing is
                        io.github.brrenat.seekervault.proposals.ProposalStanding.Executed -> {
                        operations?.close()
                        navigator.closeReview(identity)
                    }
                    open?.problem != null && !open.sending -> onLeave()
                }
            }
            if (operations == null || (operationsState.loaded && !stillHeld)) {
                RequestGoneScreen(onBack = onLeave)
            } else if (open == null) {
                LaunchedEffect(identity.connectionId, identity.requestId, operationsState.loaded) {
                    operations.open(identity.connectionId, identity.requestId)
                }
            } else {
                WalletHandoffScreen(
                    summary = "$label will continue in Seed Vault Wallet",
                    onApprove = {
                        approvalStarted = true
                        operations.approve(walletState.wallet)
                    },
                    onDecline = {
                        operations.dismiss(identity.connectionId, identity.requestId)
                        operations.close()
                        navigator.closeReview(identity)
                    },
                    onLeaveWithoutAnswering = onLeave,
                )
                LaunchedEffect(open.proposalId, open.prepared) {
                    if (open.prepared == null && !open.preparing) operations.prepare()
                }
            }
        }
    }
}

private fun PolicyAsset.routeId(): String = "${network.number}:${mint.orEmpty()}"

private fun io.github.brrenat.seekervault.policy.PolicyUiState.connectionAssets():
    List<PolicyAsset> {
    val draft = (draft as? PolicyEditorDraft.Connection)?.rules ?: return emptyList()
    return buildList {
        addAll(draft.assets)
        addAll(draft.limits.map { it.asset })
    }
        .distinct()
}

private fun PolicyAddressKind.toRouteKind(): AddressEditorKind =
    when (this) {
        PolicyAddressKind.Recipient -> AddressEditorKind.Recipient
        PolicyAddressKind.Program -> AddressEditorKind.Program
    }

private fun PolicyAssetEditorKind.toRouteKind(): AssetEditorKind =
    when (this) {
        PolicyAssetEditorKind.Allowlisted -> AssetEditorKind.Allowlisted
        PolicyAssetEditorKind.SpendingLimit -> AssetEditorKind.SpendingLimit
    }

private fun AssetEditorKind.toPolicyKind(): PolicyAssetEditorKind =
    when (this) {
        AssetEditorKind.Allowlisted -> PolicyAssetEditorKind.Allowlisted
        AssetEditorKind.SpendingLimit -> PolicyAssetEditorKind.SpendingLimit
    }

private fun AddressEditorKind.toPolicyKind(): PolicyAddressKind =
    when (this) {
        AddressEditorKind.Recipient -> PolicyAddressKind.Recipient
        AddressEditorKind.Program -> PolicyAddressKind.Program
    }

private fun sheetTitle(route: AppSheet, state: ConnectionsUiState): String =
    when (route) {
        is AppSheet.ConnectionDetail ->
            state.connections.firstOrNull { it.id == route.connectionId }?.label ?: "Connection"
        is AppSheet.ConnectionRules -> "Rules"
        AppSheet.GlobalRules -> "Global rules"
        is AppSheet.RequestReview -> "Review request"
        is AppSheet.WalletHandoff -> "Wallet"
        is AppSheet.AssetEditor -> if (route.assetId == null) "Add asset" else "Edit asset"
        is AppSheet.AddressEditor -> "Add address"
    }

@Composable
private fun ConnectionDetailsRoute(
    viewModel: ConnectionsViewModel,
    state: ConnectionsUiState,
    id: String,
    onBack: () -> Unit,
    onRules: () -> Unit,
    onInbox: () -> Unit,
    onPairDirect: () -> Unit,
    overrideCount: Int,
    operationRefreshing: Boolean = false,
    onOperationRefresh: () -> Unit = {},
) {
    val connection = state.connections.firstOrNull { it.id == id }
    if (connection == null) {
        LaunchedEffect(id, state.loaded) { if (state.loaded) onBack() }
        return
    }
    LaunchedEffect(id) {
        viewModel.refresh(id)
        if (connection.mode == ConnectionMode.GatewayFeed) onOperationRefresh()
    }
    ConnectionDetailLibraryScreen(
        connection = connection,
        refreshing = id in state.refreshing || operationRefreshing,
        disconnect = state.disconnect?.takeIf { it.id == id },
        message = state.message,
        onBack = onBack,
        onRefresh = {
            viewModel.refresh(id)
            if (connection.mode == ConnectionMode.GatewayFeed) onOperationRefresh()
        },
        onRename = { viewModel.rename(id, it) },
        onDisconnect = { viewModel.askToDisconnect(id) },
        onConfirmDisconnect = viewModel::confirmDisconnect,
        onConfirmRemove = viewModel::confirmRemove,
        onDismissDisconnect = viewModel::dismissDisconnect,
        onMessageShown = viewModel::messageShown,
        onRules = onRules,
        onInbox = onInbox,
        onPairDirect = onPairDirect,
        overrideCount = overrideCount,
        live = state.updates.connections[id],
        support = state.support[id],
    )
}

private fun io.github.brrenat.seekervault.policy.PolicyUiState.readyForLibrarySheet(
    expectedConnectionId: String? = null
): Boolean =
    loaded &&
        unreadable == null &&
        draft != null &&
        (expectedConnectionId == null || connectionId == expectedConnectionId)

private fun io.github.brrenat.seekervault.policy.PolicyUiState.overrideCount(
    expectedConnectionId: String
): Int {
    if (connectionId != expectedConnectionId) return 0
    val rules = (draft as? PolicyEditorDraft.Connection)?.rules ?: return 0
    return listOf(
            rules.overrideActions,
            rules.overrideAssets || rules.limits.any { it.configuresSomething },
            rules.overrideRecipients,
            rules.overridePrograms,
        )
        .count { it }
}

@Composable
private fun LiveTestRoute(viewModel: LiveCommandViewModel) {
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.disconnect() }
    }
    LiveCommandRoute(viewModel)
}
