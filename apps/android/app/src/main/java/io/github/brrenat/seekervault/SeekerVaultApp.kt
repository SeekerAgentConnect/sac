package io.github.brrenat.seekervault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.activity.ActivityRoute
import io.github.brrenat.seekervault.activity.ActivityViewModel
import io.github.brrenat.seekervault.activity.openDestination
import io.github.brrenat.seekervault.connections.AddConnectionRoute
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ConnectionDetailLibraryScreen
import io.github.brrenat.seekervault.connections.ConnectionWalletPicker
import io.github.brrenat.seekervault.connections.ConnectionsUiState
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.connections.HomeRoute
import io.github.brrenat.seekervault.connections.HomeRouteCallbacks
import io.github.brrenat.seekervault.connections.InboxSummary
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.bindingText
import io.github.brrenat.seekervault.connections.connectionWalletFacts
import io.github.brrenat.seekervault.connections.connectionWalletRow
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.connections.sourceColour
import io.github.brrenat.seekervault.designsystem.CatalogDetailSheet
import io.github.brrenat.seekervault.designsystem.HistoryDetailLink
import io.github.brrenat.seekervault.designsystem.InboxTab
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.SheetMotion
import io.github.brrenat.seekervault.discover.CatalogAction
import io.github.brrenat.seekervault.discover.DiscoverCallbacks
import io.github.brrenat.seekervault.discover.DiscoverRoute
import io.github.brrenat.seekervault.discover.DiscoverScreen
import io.github.brrenat.seekervault.discover.DiscoverUiState
import io.github.brrenat.seekervault.discover.DiscoverViewModel
import io.github.brrenat.seekervault.discover.catalogCardState
import io.github.brrenat.seekervault.discover.catalogDetailState
import io.github.brrenat.seekervault.history.HistoryDetailRoute
import io.github.brrenat.seekervault.history.PositionSaleRoute
import io.github.brrenat.seekervault.history.holdingFor
import io.github.brrenat.seekervault.history.retainedPurchases
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
import io.github.brrenat.seekervault.inbox.rememberInboxViewState
import io.github.brrenat.seekervault.live.LiveCommandRoute
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.notifications.ArrivalLedger
import io.github.brrenat.seekervault.notifications.InAppNotices
import io.github.brrenat.seekervault.notifications.InAppNotificationTarget
import io.github.brrenat.seekervault.notifications.InAppNotifications
import io.github.brrenat.seekervault.notifications.LocalInAppNotices
import io.github.brrenat.seekervault.notifications.RequestNotificationPermission
import io.github.brrenat.seekervault.operations.OperationViewModel
import io.github.brrenat.seekervault.operations.OperationsUiState
import io.github.brrenat.seekervault.operations.PredictionParametersSheet
import io.github.brrenat.seekervault.operations.PredictionReviewScreen
import io.github.brrenat.seekervault.operations.PredictionReviewSource
import io.github.brrenat.seekervault.operations.ProposalReviewScreen
import io.github.brrenat.seekervault.operations.choiceRows
import io.github.brrenat.seekervault.operations.requiresWalletHandoff
import io.github.brrenat.seekervault.operations.reviewedAsPrediction
import io.github.brrenat.seekervault.policy.PolicyAddressKind
import io.github.brrenat.seekervault.policy.PolicyAddressLibraryScreen
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.PolicyAssetEditorKind
import io.github.brrenat.seekervault.policy.PolicyAssetLibraryScreen
import io.github.brrenat.seekervault.policy.PolicyEditorDraft
import io.github.brrenat.seekervault.policy.PolicyEditorViewModel
import io.github.brrenat.seekervault.policy.PolicyLibrarySheetScreen
import io.github.brrenat.seekervault.positions.PositionsState
import io.github.brrenat.seekervault.positions.PositionsViewModel
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedAccess
import io.github.brrenat.seekervault.servers.FeedReferences
import io.github.brrenat.seekervault.servers.executable
import io.github.brrenat.seekervault.servers.feedAccess
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.ui.SeekerSheet
import io.github.brrenat.seekervault.ui.SheetInputBarrier
import io.github.brrenat.seekervault.wallet.WalletRoute
import io.github.brrenat.seekervault.wallet.WalletViewModel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Above `SeekerSheet`'s `10f + index`, so the banner is over the whole sheet stack. */
private const val InAppNotificationZIndex = 100f

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
    positions: PositionsViewModel? = null,
    /**
     * Which waiting items reached the phone as news (SEE-175). Without one nothing is marked, so no
     * request or signal raises a banner; disconnections and service messages still do.
     */
    arrivals: ArrivalLedger? = null,
    /**
     * The Discover tab's catalog (SEE-176). Without one the tab says this build has no catalog
     * configured, which is also what a build with no discovery origin says.
     */
    discover: DiscoverViewModel? = null,
    /** The gateway origin [discover] reads from, for what the tab says when it can't reach it. */
    discoveryUrl: String = "",
) {
    val navigator =
        rememberAppNavigator(
            if (startInLiveTest) NavigationState(AppScreen.LiveTest) else NavigationState()
        )
    val navigation = navigator.state
    val sheets = navigation.sheets
    // Kept here rather than in the Inbox, so a record's details and Back return to the same tab,
    // source filter and History scroll offset (SEE-161).
    val inboxView = rememberInboxViewState()
    // Kept here for the same reason: a card's details, and a Connect that goes to Add connection
    // and comes back, return to the same place in the catalog (SEE-176).
    val discoverScroll = rememberScrollState()
    val openHistoryDetail: (ReviewIdentity) -> Unit = { identity ->
        inboxView.tab = InboxTab.History
        navigator.openHistoryDetail(identity)
    }
    var closingTo by remember { mutableStateOf<Int?>(null) }
    var backplateTargetSize by remember { mutableStateOf<Int?>(null) }
    var requestedPolicyClose by remember { mutableStateOf<AppSheet?>(null) }
    var policyCloseRequest by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val dismissSheetsThen: (Int, () -> Unit) -> Unit = { keep, then ->
        if (closingTo == null) {
            if (navigator.state.sheets.size <= keep) {
                then()
            } else {
                closingTo = keep
                scope.launch {
                    delay(SheetMotion.ExitMs.toLong())
                    then()
                    closingTo = null
                }
            }
        }
    }
    val popTo: (Int) -> Unit = { keep ->
        dismissSheetsThen(keep) {
            while (navigator.state.sheets.size > keep) navigator.back()
        }
    }
    val requestPopTo: (Int) -> Unit = { keep ->
        val current = navigator.state.sheets
        if (current.size > keep && closingTo == null) {
            val top = current.last()
            if (top == AppSheet.GlobalRules || top is AppSheet.ConnectionRules) {
                policyCloseRequest += 1
                requestedPolicyClose = top
                backplateTargetSize = keep
            } else {
                popTo(keep)
            }
        }
    }
    val pop = {
        val size = navigator.state.sheets.size
        if (size > 0) requestPopTo(size - 1)
    }
    val selectTabAfterSheets: (AppScreen.Tab) -> Unit = { tab ->
        dismissSheetsThen(0) { navigator.selectTab(tab) }
    }
    val resetTransientSheetState = {
        closingTo = null
        backplateTargetSize = null
        requestedPolicyClose = null
    }

    val state by connections.state.collectAsStateWithLifecycle()
    val inboxState by inbox.state.collectAsStateWithLifecycle()
    // What the phone itself found on chain, one source for every History surface (SEE-165).
    val historyState by history.state.collectAsStateWithLifecycle()
    val chainChecks by history.chainChecks.collectAsStateWithLifecycle()
    val walletState by wallet.state.collectAsStateWithLifecycle()
    val liveArrivals by
        remember(arrivals) { arrivals?.live ?: MutableStateFlow(emptySet<ReviewIdentity>()) }
            .collectAsStateWithLifecycle()
    val lateArrivals by
        remember(arrivals) { arrivals?.late ?: MutableStateFlow(emptySet<ReviewIdentity>()) }
            .collectAsStateWithLifecycle()
    val policyState by policy.state.collectAsStateWithLifecycle()
    val globalPolicyState by globalPolicy.state.collectAsStateWithLifecycle()
    val notificationTap by notificationTaps.collectAsStateWithLifecycle()
    val feedTap by feedTaps.collectAsStateWithLifecycle()
    val connectionLinkTap by connectionLinkTaps.collectAsStateWithLifecycle()
    val operationsState by
        (operations?.state ?: MutableStateFlow(OperationsUiState())).collectAsStateWithLifecycle()
    val openOperation by
        (operations?.review ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    val positionsState by
        (positions?.state ?: MutableStateFlow(PositionsState())).collectAsStateWithLifecycle()
    // The wallet profile that owns the open History item's position — its original owner on its
    // original network, never the wallet its feed is bound to now (SEE-172, SEE-174).
    val positionWallet =
        (navigation.screen as? AppScreen.HistoryDetail)?.identity?.let { identity ->
            positionsState.holdingFor(identity)?.held?.account?.let { account ->
                positions?.ownerWallet(account)
            }
        }
    // Tracked purchases whose feed record is gone: removing a connection removes its proposals,
    // not the owner's positions (SEE-172). Only an answer once both have been read.
    val retained =
        remember(positionsState, operationsState, historyState.records) {
            if (!operationsState.loaded || !positionsState.loaded) emptyList()
            else retainedPurchases(positionsState, operationsState.records, historyState.records)
        }
    val historyContext = LocalContext.current
    val historyResources = LocalResources.current

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
    LaunchedEffect(feedTap?.sequence, state.loaded, operationsState.loaded) {
        val ref = feedTap?.ref ?: return@LaunchedEffect
        if (!state.loaded || (operations != null && !operationsState.loaded)) return@LaunchedEffect
        resetTransientSheetState()
        navigator.selectTab(AppScreen.Home)
        val connection = state.connections.firstOrNull { it.id == ref.connectionId }
        val record =
            operationsState.records.firstOrNull {
                it.connectionId == ref.connectionId && it.key.proposalId == ref.proposalId
            }
        val closed =
            record != null &&
                operations?.standing(record)?.let {
                    it !is io.github.brrenat.seekervault.proposals.ProposalStanding.Open
                } == true
        if (connection?.mode == ConnectionMode.GatewayFeed && closed) {
            // A signal that has closed since the alert opens as its History record (SEE-161).
            inboxView.reset()
            navigator.selectTab(AppScreen.Inbox)
            openHistoryDetail(ReviewIdentity.Signal(ref.connectionId, ref.proposalId))
        } else if (connection?.mode == ConnectionMode.GatewayFeed) {
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
    // The same reading of the owner's rules a signal's review makes when it opens, so its tile's
    // chip and the review's verdict card agree (SEE-158).
    val signalAssessments =
        remember(
            commonPending,
            policyState.stored,
            globalPolicyState.stored,
            walletState.readiness,
            openOperation?.assessment,
        ) {
            commonPending
                .filterIsInstance<PendingItem.Signal>()
                .mapNotNull { item ->
                    operations?.assessment(item.connectionId, item.requestId)?.let {
                        RequestKey(item.connectionId, item.requestId) to it
                    }
                }
                .toMap()
        }
    // Rules edited from a review's verdict are read again by the review they were edited over.
    LaunchedEffect(policyState.stored, globalPolicyState.stored) { operations?.reload() }
    val rootModifier =
        Modifier.navigationBarsPadding()
            .then(if (sheets.isNotEmpty()) Modifier.clearAndSetSemantics {} else Modifier)
    val screenNavigationCallbacks =
        ScreenNavigationCallbacks(
            onHome = { selectTabAfterSheets(AppScreen.Home) },
            onInbox = {
                inboxView.reset()
                selectTabAfterSheets(AppScreen.Inbox)
            },
            onWallet = { selectTabAfterSheets(AppScreen.Wallet) },
            onActivity = { selectTabAfterSheets(AppScreen.Activity) },
            onDiscover = { selectTabAfterSheets(AppScreen.Discover) },
        )
    // A Discover card's button (SEE-176). Connect and Request access are the normal Add connection
    // flow with the reference prefilled — its confirmation, its manifest check, then the wallet
    // picker on the connection's own sheet — and Open is the connection this phone already holds.
    // Nothing here adds, signs or requests anything by itself. From a card's details, the details
    // sheet leaves the stack first: both destinations open from the tab itself, never over a sheet.
    val catalogAction: (CatalogAction) -> Unit = { action ->
        val fromTab: (() -> Unit) -> Unit = { then ->
            dismissSheetsThen(0) {
                while (navigator.state.sheets.isNotEmpty()) navigator.back()
                then()
            }
        }
        when (action) {
            is CatalogAction.Onboard ->
                fromTab {
                    if (navigator.openCatalogConnect()) {
                        connections.onCode(FeedReferences.format(action.feed.reference))
                    }
                }
            is CatalogAction.Open -> fromTab { navigator.openConnectionDetail(action.connectionId) }
            CatalogAction.None -> Unit
        }
    }

    LaunchedEffect(pendingKeys, policyState.stored, globalPolicyState.stored) {
        pendingKeys.forEach(inbox::review)
    }
    // Service messages from any screen go to the top banner, not to a snackbar at the bottom.
    val notices = remember { InAppNotices() }
    CompositionLocalProvider(LocalInAppNotices provides notices) {
        Box(Modifier.fillMaxSize()) {
            when (navigation.screen) {
                AppScreen.Home -> {
                    val (_, toSend) = inboxCounts(inboxState)
                    HomeRoute(
                        connectionsState =
                            if (sheets.isEmpty()) state else state.copy(message = null),
                        inboxSummary = InboxSummary(commonPending.size, toSend),
                        // One profile is shown as the wallet; several are counted (SEE-174).
                        wallet = walletState.profiles.singleOrNull()?.selected(),
                        walletApp = walletState.profiles.singleOrNull()?.walletApp,
                        profileCount = walletState.profiles.size,
                        walletReadiness = walletState.readiness,
                        requestAssessments = inboxState.assessments,
                        signalAssessments = signalAssessments,
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
                                onDiscover = screenNavigationCallbacks.onDiscover,
                                onOpenPending = { item ->
                                    navigator.openReview(
                                        when (item) {
                                            is PendingItem.Private ->
                                                ReviewIdentity.Private(
                                                    item.request.key.connectionId,
                                                    item.request.key.requestId,
                                                )
                                            is PendingItem.Signal ->
                                                ReviewIdentity.Signal(
                                                    item.connectionId,
                                                    item.requestId,
                                                )
                                        }
                                    )
                                },
                            ),
                        modifier = rootModifier,
                    )
                }
                AppScreen.Discover -> {
                    val callbacks =
                        DiscoverCallbacks(
                            onOpenFeed = { feed ->
                                navigator.openCatalogDetail(feed.gatewayUrl, feed.serverId)
                            },
                            onAction = catalogAction,
                            onRefresh = { discover?.refresh() },
                            onRetry = { discover?.retry() },
                            onLoadMore = { discover?.loadMore() },
                            onBack = { selectTabAfterSheets(AppScreen.Home) },
                            navigation = screenNavigationCallbacks,
                        )
                    if (discover != null) {
                        DiscoverRoute(
                            viewModel = discover,
                            connections = state,
                            gatewayUrl = discoveryUrl,
                            scrollState = discoverScroll,
                            callbacks = callbacks,
                            modifier = rootModifier,
                        )
                    } else {
                        DiscoverScreen(
                            state = DiscoverUiState(configured = false),
                            standing = { feed -> catalogCardState(feed, state) },
                            gatewayUrl = discoveryUrl,
                            scrollState = discoverScroll,
                            callbacks = callbacks,
                            modifier = rootModifier,
                        )
                    }
                }
                AppScreen.Wallet ->
                    WalletRoute(
                        viewModel = wallet,
                        onBack = { selectTabAfterSheets(AppScreen.Home) },
                        navigationCallbacks = screenNavigationCallbacks,
                        modifier = rootModifier,
                    )
                AppScreen.Activity ->
                    ActivityRoute(
                        viewModel = history,
                        onBack = { selectTabAfterSheets(AppScreen.Home) },
                        navigationCallbacks = screenNavigationCallbacks,
                        modifier = rootModifier,
                    )
                AppScreen.Inbox ->
                    InboxRoute(
                        state = inboxState,
                        chainChecks = chainChecks,
                        feedRecords = operationsState.records,
                        feedStanding = { record ->
                            operations?.standing(record)
                                ?: io.github.brrenat.seekervault.proposals.ProposalStanding.Expired
                        },
                        now = Instant.now(),
                        retained = retained,
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
                                onOpenHistory = openHistoryDetail,
                            ),
                        modifier = rootModifier,
                        view = inboxView,
                    )
                is AppScreen.HistoryDetail ->
                    HistoryDetailRoute(
                        identity = navigation.screen.identity,
                        connections = state.connections,
                        inboxState = inboxState,
                        feedRecords = operationsState.records,
                        feedStanding = { record ->
                            operations?.standing(record)
                                ?: io.github.brrenat.seekervault.proposals.ProposalStanding.Expired
                        },
                        signalChoice = { record ->
                            val context = LocalContext.current
                            val choice = record.execution?.binding?.choice
                            remember(record, operations) {
                                if (choice == null || operations == null) {
                                    emptyList()
                                } else {
                                    choiceRows(
                                        choice,
                                        operations.parameterForm(record),
                                        context::getString,
                                    )
                                }
                            }
                        },
                        onSendAgain = inbox::sendAgain,
                        onCheckStatus = inbox::checkStatus,
                        onBack = { navigator.back() },
                        modifier = rootModifier,
                        chainChecks = chainChecks,
                        checkingChain = historyState.checking,
                        onCheckChain = history::checkChain,
                        positions = positionsState,
                        retained = retained,
                        wallet = positionWallet,
                        positionLinks = { held ->
                            positions?.destinations(held).orEmpty().map {
                                HistoryDetailLink(historyResources.getString(it.label), it.url)
                            }
                        },
                        onRefreshPosition = { account -> positions?.refresh(account) },
                        onSellPosition = { _ ->
                            navigator.openPositionSale(navigation.screen.identity)
                        },
                        onOpenProvider = { url -> openDestination(historyContext, url, url) },
                    )
                is AppScreen.AddConnection ->
                    AddConnectionRoute(
                        viewModel = connections,
                        onBack = navigator::back,
                        // A new connection chooses its wallet next, on its own detail sheet
                        // (SEE-174): the pairing code is already spent and the manifest read, so
                        // the picker knows which networks to offer. It opens over the tab Add
                        // connection came from, so a feed added from Discover returns there.
                        onAdded = { connection ->
                            navigator.selectTab(
                                (navigation.screen as? AppScreen.AddConnection)?.from
                                    ?: AppScreen.Home
                            )
                            if (connection.retirement == null) {
                                wallet.beginSetup(connection.id)
                                navigator.openConnectionDetail(connection.id)
                            }
                        },
                        modifier = rootModifier,
                        navigationCallbacks = screenNavigationCallbacks,
                    )
                AppScreen.LiveTest -> LiveTestRoute(live)
            }

            if (sheets.isNotEmpty()) SheetInputBarrier(Modifier.zIndex(5f))
            val visibleCount = closingTo ?: sheets.size
            sheets.forEachIndexed { index, sheetRoute ->
                key(sheetRoute) {
                    val active = index == sheets.lastIndex && index < visibleCount
                    val back =
                        if (index >= visibleCount) sheets.lastIndex - index
                        else (visibleCount - 1 - index).coerceAtLeast(0)
                    SeekerSheet(
                        index = index,
                        back = back,
                        motionKey = sheetRoute,
                        visible = index < visibleCount,
                        onDismiss = pop,
                        onPeekClick = { requestPopTo(index + 1) },
                        chrome =
                            (sheetRoute is AppSheet.RequestReview &&
                                !predictionSheet(sheetRoute.identity, operationsState.records)) ||
                                (sheetRoute is AppSheet.ConnectionRules &&
                                    !policyState.readyForLibrarySheet(sheetRoute.connectionId)) ||
                                (sheetRoute is AppSheet.GlobalRules &&
                                    !globalPolicyState.readyForLibrarySheet()),
                        aboveKeyboard = sheetRoute is AppSheet.OwnerInput,
                    ) {
                        when (val activeRoute = sheetRoute) {
                            is AppSheet.CatalogDetail -> {
                                val discoverState =
                                    discover?.state?.collectAsStateWithLifecycle()?.value
                                val feed =
                                    discoverState?.feeds?.firstOrNull {
                                        it.gatewayUrl == activeRoute.gatewayUrl &&
                                            it.serverId == activeRoute.serverId
                                    }
                                if (feed == null) {
                                    // Left the catalog in a refresh since it was opened: there
                                    // is nothing current to show, so the sheet goes.
                                    LaunchedEffect(activeRoute) { if (active) pop() }
                                } else {
                                    CatalogDetailSheet(
                                        state = catalogDetailState(feed, state),
                                        onClose = pop,
                                        onAction = {
                                            catalogAction(catalogCardState(feed, state).action)
                                        },
                                    )
                                }
                            }
                            is AppSheet.ConnectionDetail -> {
                                ConnectionDetailsRoute(
                                    viewModel = connections,
                                    state = state,
                                    wallet = wallet,
                                    walletState = walletState,
                                    id = activeRoute.connectionId,
                                    onBack = pop,
                                    onRules = {
                                        navigator.openConnectionRules(activeRoute.connectionId)
                                    },
                                    onInbox = {
                                        inboxView.reset(sourceFilter = activeRoute.connectionId)
                                        selectTabAfterSheets(AppScreen.Inbox)
                                    },
                                    onPairDirect = {
                                        dismissSheetsThen(0) {
                                            navigator.selectTab(AppScreen.Home)
                                            navigator.openAddConnection()
                                        }
                                    },
                                    overrideCount =
                                        policyState.overrideCount(activeRoute.connectionId),
                                    operationRefreshing =
                                        activeRoute.connectionId in operationsState.refreshing,
                                    onOperationRefresh = {
                                        operations?.refresh(activeRoute.connectionId)
                                    },
                                )
                                if (active) {
                                    LaunchedEffect(activeRoute.connectionId) {
                                        policy.open(activeRoute.connectionId)
                                    }
                                }
                            }
                            is AppSheet.ConnectionRules -> {
                                val id = activeRoute.connectionId
                                val close = {
                                    val target =
                                        backplateTargetSize ?: (navigator.state.sheets.size - 1)
                                    requestedPolicyClose = null
                                    backplateTargetSize = null
                                    policy.close()
                                    popTo(target)
                                }
                                PolicyLibrarySheetScreen(
                                    label =
                                        state.connections
                                            .firstOrNull { it.id == id }
                                            ?.label
                                            .orEmpty(),
                                    state = policyState,
                                    onEdit = policy::edit,
                                    onStartOver = policy::startOver,
                                    onResetConnection = policy::resetConnectionOverrides,
                                    onOpenGlobal = navigator::openGlobalRules,
                                    onSave = policy::save,
                                    onMessageShown = policy::messageShown,
                                    onClose = close,
                                    closeRequest =
                                        if (requestedPolicyClose == activeRoute) policyCloseRequest
                                        else 0,
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
                                if (active) {
                                    LaunchedEffect(id) { policy.open(id) }
                                }
                            }
                            AppSheet.GlobalRules -> {
                                val close = {
                                    val target =
                                        backplateTargetSize ?: (navigator.state.sheets.size - 1)
                                    requestedPolicyClose = null
                                    backplateTargetSize = null
                                    globalPolicy.close()
                                    popTo(target)
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
                                        if (requestedPolicyClose == activeRoute) policyCloseRequest
                                        else 0,
                                    onCloseRequestCancelled = {
                                        if (requestedPolicyClose == activeRoute) {
                                            requestedPolicyClose = null
                                            backplateTargetSize = null
                                        }
                                    },
                                )
                                if (active) {
                                    LaunchedEffect(Unit) { globalPolicy.openGlobal() }
                                }
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
                                if (active) {
                                    LaunchedEffect(activeRoute.connectionId) {
                                        policy.open(activeRoute.connectionId)
                                    }
                                }
                            }
                            is AppSheet.AddressEditor -> {
                                PolicyAddressLibraryScreen(
                                    state = policyState,
                                    kind = activeRoute.kind.toPolicyKind(),
                                    onEdit = policy::edit,
                                    onBack = pop,
                                )
                                if (active) {
                                    LaunchedEffect(activeRoute.connectionId) {
                                        policy.open(activeRoute.connectionId)
                                    }
                                }
                            }
                            is AppSheet.OwnerInput -> {
                                val open = openOperation?.takeIf {
                                    it.connectionId == activeRoute.identity.connectionId &&
                                        it.proposalId == activeRoute.identity.requestId
                                }
                                if (operations == null || open == null) {
                                    RequestGoneScreen(onBack = pop)
                                } else {
                                    PredictionParametersSheet(
                                        review = open,
                                        onUse = { values ->
                                            // What was chosen replaces the old choice, and the
                                            // review
                                            // quotes again from it at once: "Use these" is the
                                            // re-quote (SEE-158).
                                            values.forEach { (key, value) ->
                                                operations.choose(key, value)
                                            }
                                            operations.prepare()
                                            pop()
                                        },
                                        onClose = pop,
                                    )
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
                                    onClosed = { identity ->
                                        inboxView.reset()
                                        openHistoryDetail(identity)
                                    },
                                )
                            is AppSheet.PositionSale ->
                                if (positions == null) {
                                    RequestGoneScreen(onBack = pop)
                                } else {
                                    PositionSaleRoute(
                                        identity = activeRoute.identity,
                                        positions = positions,
                                        onClose = pop,
                                    )
                                }
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
                                    onLeave = pop,
                                    onCloseReview = { identity ->
                                        dismissSheetsThen(0) { navigator.closeReview(identity) }
                                    },
                                )
                        }
                    }
                }
            }

            // Above the content and above every sheet, and the last child so nothing can draw over
            // it.
            // A touch on the banner is the banner's; a touch anywhere else is not intercepted at
            // all.
            InAppNotifications(
                // Every list a banner reads, filled at least once, and not just the connections:
                // the
                // stored connections carry no pending requests and no proposals, so a baseline
                // taken
                // at `state.loaded` would be empty and the opening fetch and the stored proposals
                // would arrive as banners for things the owner has had for days. A build without
                // operations holds no proposals, so there is nothing there to wait for.
                ready = state.fetched && (operations == null || operationsState.loaded),
                connections = state.connections,
                waiting = commonPending,
                live = liveArrivals,
                late = lateArrivals,
                reviewOpen = { identity ->
                    // Read at the moment the banner would be raised, not at the last recomposition.
                    navigator.state.sheets.any { sheet ->
                        (sheet as? AppSheet.RequestReview)?.identity == identity ||
                            (sheet as? AppSheet.WalletHandoff)?.identity == identity
                    }
                },
                onOpen = { target ->
                    // The same approach as a system notification's tap: back to a base destination
                    // the graph allows the destination to be pushed from, then push it.
                    resetTransientSheetState()
                    navigator.selectTab(AppScreen.Home)
                    when (target) {
                        is InAppNotificationTarget.Review -> navigator.openReview(target.identity)
                        is InAppNotificationTarget.PairAgain -> navigator.openAddConnection()
                        // Several at once: the Inbox's pending list, where every one of them is.
                        InAppNotificationTarget.Inbox -> {
                            inboxView.reset()
                            navigator.selectTab(AppScreen.Inbox)
                        }
                        InAppNotificationTarget.Dismiss -> Unit
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter).zIndex(InAppNotificationZIndex),
            )
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
    /** A notification about an item answered and closed since: its History record instead. */
    onClosed: (ReviewIdentity) -> Unit = {},
) {
    when (val identity = route.identity) {
        is ReviewIdentity.Private -> {
            val key = RequestKey(identity.connectionId, identity.requestId)
            val result = inboxState.inbox.result(key)
            val request = result?.request ?: inboxState.inbox.pendingRequest(key)
            val notificationOpen = inboxState.notificationOpen?.takeIf { it.key == key }
            if (notificationOpen?.status == NotificationOpenStatus.Closed) {
                LaunchedEffect(key) { onClosed(identity) }
            } else if (
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
                            inbox.approve(key, inboxState.walletFor(key.connectionId))
                        }
                    },
                    onSendAgain = { inbox.sendAgain(key) },
                    wallet = inboxState.walletFor(key.connectionId),
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
            } else if (open.record.reviewedAsPrediction) {
                val connection = state.connections.firstOrNull { it.id == identity.connectionId }
                val linkContext = LocalContext.current
                PredictionReviewScreen(
                    review = open,
                    source =
                        PredictionReviewSource(
                            name = connection?.label.orEmpty(),
                            colour = connection?.colour?.sourceColour(),
                        ),
                    wallet = open.wallet,
                    now = Instant.now(),
                    onOwnerInput = { navigator.openOwnerInput(identity) },
                    onPrepare = operations::prepare,
                    onApprove = {
                        if (open.requiresWalletHandoff) {
                            navigator.openWalletHandoff(identity, WalletHandoffKind.Operation)
                        } else {
                            operations.approve(open.wallet)
                        }
                    },
                    onDismiss = {
                        // Dismiss finishes the review, as the design's footer says it does.
                        operations.dismiss(identity.connectionId, identity.requestId)
                        onBack()
                    },
                    onAcknowledge = operations::acknowledge,
                    onRules = { navigator.openConnectionRules(identity.connectionId) },
                    onOpenLink = { url, deepLink -> openDestination(linkContext, deepLink, url) },
                    onBack = {
                        operations.close()
                        onBack()
                    },
                )
            } else {
                val linkContext = LocalContext.current
                ProposalReviewScreen(
                    review = open,
                    label =
                        state.connections
                            .firstOrNull { it.id == identity.connectionId }
                            ?.label
                            .orEmpty(),
                    wallet = open.wallet,
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
                            operations.approve(open.wallet)
                        }
                    },
                    onDismiss = { operations.dismiss(identity.connectionId, identity.requestId) },
                    onAcknowledge = operations::acknowledge,
                    onBack = {
                        operations.close()
                        onBack()
                    },
                    onOpenLink = { url, deepLink ->
                        openDestination(linkContext, deepLink, url)
                    },
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
    onLeave: () -> Unit,
    onCloseReview: (ReviewIdentity) -> Unit,
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
                    result != null -> onCloseReview(identity)
                    problem != null && key !in inboxState.sending -> onLeave()
                }
            }
            if (request == null) {
                RequestGoneScreen(onBack = onLeave)
            } else {
                WalletHandoffScreen(
                    summary = walletHandoffSummary(request),
                    walletApp = inboxState.walletFor(key.connectionId)?.walletApp,
                    onApprove = {
                        approvalStarted = true
                        when (route.kind) {
                            WalletHandoffKind.Transfer ->
                                inbox.approveTransaction(
                                    key,
                                    inboxState.preparations[key] as? Preparation.Ready,
                                )
                            WalletHandoffKind.Signature ->
                                inbox.approve(key, inboxState.walletFor(key.connectionId))
                            WalletHandoffKind.Operation -> Unit
                        }
                    },
                    onDecline = {
                        inbox.answer(key, Answer.Reject)
                        onCloseReview(identity)
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
                        onCloseReview(identity)
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
                    summary = "$label will continue in ${open.wallet?.walletApp ?: "your wallet"}",
                    walletApp = open.wallet?.walletApp,
                    onApprove = {
                        approvalStarted = true
                        operations.approve(open.wallet)
                    },
                    onDecline = {
                        operations.dismiss(identity.connectionId, identity.requestId)
                        operations.close()
                        onCloseReview(identity)
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

/** Whether a review route shows the design's prediction sheet, which draws its own chrome. */
private fun predictionSheet(
    identity: ReviewIdentity,
    records: List<io.github.brrenat.seekervault.proposals.ProposalRecord>,
): Boolean =
    identity is ReviewIdentity.Signal &&
        records.any {
            it.connectionId == identity.connectionId &&
                it.key.proposalId == identity.requestId &&
                it.reviewedAsPrediction
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

@Composable
private fun ConnectionDetailsRoute(
    viewModel: ConnectionsViewModel,
    state: ConnectionsUiState,
    wallet: io.github.brrenat.seekervault.wallet.WalletViewModel,
    walletState: io.github.brrenat.seekervault.wallet.WalletUiState,
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
    val access = state.access[id]
    val restricted = connection.server.manifest?.feedAccess is FeedAccess.Restricted
    var picking by rememberSaveable(id) { mutableStateOf(false) }
    // Opening a restricted feed asks the publisher where this phone stands, signed with the
    // device key (SEE-156). It never opens the wallet: only the owner asking does that.
    LaunchedEffect(id) {
        viewModel.refresh(id)
        if (connection.mode == ConnectionMode.GatewayFeed) onOperationRefresh()
        if (restricted && access != null) viewModel.checkAccess(id)
    }
    ConnectionDetailLibraryScreen(
        connection = connection,
        connections = state.connections,
        refreshing = id in state.refreshing || operationRefreshing,
        disconnect = state.disconnect?.takeIf { it.id == id },
        message = state.message,
        onBack = onBack,
        onRefresh = {
            viewModel.refresh(id)
            if (connection.mode == ConnectionMode.GatewayFeed) onOperationRefresh()
            // Refresh is also the Retry the ticket asks for: a feed nothing has been asked of
            // yet asks — which opens the wallet once — and one that already has a request only
            // checks, which does not (SEE-156).
            if (restricted) {
                if (access == null) viewModel.requestAccess(id) else viewModel.checkAccess(id)
            }
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
        onColour = { viewModel.setColour(id, it) },
        onRequestAccess = { viewModel.requestAccess(id) },
        overrideCount = overrideCount,
        live = state.updates.connections[id],
        support = state.support[id],
        feed = state.feeds.gateways[connection.serverUrl],
        availability = state.feedStatus.availabilityOf(id),
        access = access,
        walletRow = connectionWalletRow(walletState.readiness[id]),
        walletFacts = connectionWalletFacts(walletState.readiness[id]),
        onWallet = { picking = true },
    )
    // What binding came to — how many requests a direct server cancelled, or that it couldn't be
    // told — said once, on this connection's own sheet.
    val notice = walletState.binding?.takeIf { it.connectionId == id }
    val noticeText = notice?.let { bindingText(it.outcome) }
    val notices = LocalInAppNotices.current
    LaunchedEffect(notice) {
        if (notice != null) {
            noticeText?.let(notices::show)
            wallet.bindingShown()
        }
    }
    if (picking || walletState.setup == id) {
        ConnectionWalletPicker(
            connection = connection,
            wallet = walletState,
            onUse = { profileId ->
                wallet.bind(id, profileId) {
                    // A restricted feed proves its reader with the wallet it is bound to, so a new
                    // binding asks for access with it; access proven with another address doesn't
                    // carry over (SEE-156, SEE-174).
                    if (restricted) viewModel.afterWalletBound(id)
                }
                picking = false
                wallet.endSetup()
            },
            onAdd = { network -> wallet.connect(network) },
            onChooseWalletApp = wallet::chooseWalletApp,
            onDismiss = {
                picking = false
                wallet.endSetup()
            },
        )
    }
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
