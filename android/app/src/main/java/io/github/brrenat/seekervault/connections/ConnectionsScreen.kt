package io.github.brrenat.seekervault.connections

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import io.github.brrenat.seekervault.designsystem.EmptyState
import io.github.brrenat.seekervault.designsystem.EmptyStateScreen
import io.github.brrenat.seekervault.designsystem.HomeRulesRow
import io.github.brrenat.seekervault.designsystem.RequestCarousel
import io.github.brrenat.seekervault.designsystem.RequestCarouselItem
import io.github.brrenat.seekervault.designsystem.RequestTileKind
import io.github.brrenat.seekervault.designsystem.RequestTileModel
import io.github.brrenat.seekervault.designsystem.ScreenCaption
import io.github.brrenat.seekervault.designsystem.ScreenCaptionSize
import io.github.brrenat.seekervault.designsystem.ScreenDestination
import io.github.brrenat.seekervault.designsystem.ScreenFullBleed
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ScreenScaffold
import io.github.brrenat.seekervault.designsystem.ScreenScrollBody
import io.github.brrenat.seekervault.designsystem.SectionHeader
import io.github.brrenat.seekervault.designsystem.SectionHeaderTrailing
import io.github.brrenat.seekervault.designsystem.SeekerFab
import io.github.brrenat.seekervault.designsystem.ServerRow
import io.github.brrenat.seekervault.designsystem.ServerRowModel
import io.github.brrenat.seekervault.designsystem.ServerRowState
import io.github.brrenat.seekervault.designsystem.SourceColour
import io.github.brrenat.seekervault.designsystem.WalletBanner
import io.github.brrenat.seekervault.designsystem.WalletBannerVariant
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.inbox.PendingItem
import io.github.brrenat.seekervault.inbox.RequestAssessment
import io.github.brrenat.seekervault.inbox.key
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.request.v2.Value
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.executable
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.ui.SeekerSnackbarHost
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** How many requests wait for the owner, and how many answers wait to be sent. */
data class InboxSummary(val waitingForYou: Int, val toSend: Int)

data class HomeWalletState(val name: String, val address: String, val canCopy: Boolean)

data class HomeServerState(
    val id: String,
    val model: ServerRowModel,
    val rowState: ServerRowState,
)

/** UI-only state for the Home screen. */
data class HomeScreenState(
    val wallet: HomeWalletState,
    val pendingCount: Int,
    val pending: List<RequestCarouselItem>,
    val serversLoaded: Boolean,
    val servers: List<HomeServerState>,
)

data class HomeScreenCallbacks(
    val onWallet: () -> Unit,
    val onCopyWalletAddress: () -> Unit,
    val onSeeAll: () -> Unit,
    val onPending: (String) -> Unit,
    val onGlobalRules: () -> Unit,
    val onServer: (String) -> Unit,
    val onRetryServer: (String) -> Unit,
    val onAddConnection: () -> Unit,
    val navigation: ScreenNavigationCallbacks,
)

data class HomeRouteCallbacks(
    val onOpenConnection: (String) -> Unit,
    val onRetryConnection: (String) -> Unit,
    val onAddConnection: () -> Unit,
    val onMessageShown: () -> Unit,
    val onInbox: () -> Unit,
    val onWallet: () -> Unit,
    val onGlobalRules: () -> Unit,
    val onActivity: () -> Unit,
    val onOpenPending: (PendingItem) -> Unit,
)

/** Stateless rendering of the SEE-121 Home reference. */
@Composable
fun HomeScreen(
    state: HomeScreenState,
    callbacks: HomeScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        title = HomeCopy.Title,
        selectedDestination = ScreenDestination.Home,
        navigationCallbacks = callbacks.navigation,
        modifier = modifier,
    ) {
        ScreenScrollBody(Modifier.testTag(ConnectionsTags.LIST)) {
            WalletBanner(
                walletName = state.wallet.name,
                address = state.wallet.address,
                statusText = null,
                variant = WalletBannerVariant.Compact,
                onCopyAddress = callbacks.onCopyWalletAddress.takeIf { state.wallet.canCopy },
                onClick = callbacks.onWallet,
                shortenAddress = state.wallet.canCopy,
                modifier = Modifier.testTag(ConnectionsTags.WALLET),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
            ) {
                SectionHeader(
                    title = HomeCopy.Waiting,
                    trailing = SectionHeaderTrailing.Button,
                    trailingLabel = "${state.pendingCount} · see all",
                    onTrailingClick = callbacks.onSeeAll,
                    trailingModifier = Modifier.testTag(ConnectionsTags.INBOX),
                )
                if (state.pending.isEmpty()) {
                    EmptyState(
                        screen = EmptyStateScreen.Inbox,
                        title = HomeCopy.EmptyPendingTitle,
                        body = HomeCopy.EmptyPendingBody,
                        modifier = Modifier.testTag(ConnectionsTags.PENDING_EMPTY),
                    )
                } else {
                    ScreenFullBleed {
                        RequestCarousel(
                            items = state.pending,
                            centredIndex = 0,
                            onItemClick = { callbacks.onPending(it.id) },
                            modifier = Modifier.testTag(ConnectionsTags.CAROUSEL),
                        )
                    }
                    ScreenCaption(HomeCopy.CarouselCaption, size = ScreenCaptionSize.Small)
                }
            }

            SectionHeader(title = HomeCopy.Rules, trailing = SectionHeaderTrailing.None)
            HomeRulesRow(
                title = HomeCopy.GlobalRules,
                supportingText = HomeCopy.GlobalRulesNote,
                onClick = callbacks.onGlobalRules,
                modifier = Modifier.testTag(ConnectionsTags.GLOBAL_RULES),
            )

            SectionHeader(title = HomeCopy.Servers, trailing = SectionHeaderTrailing.None)
            if (state.serversLoaded && state.servers.isEmpty()) {
                EmptyState(
                    screen = EmptyStateScreen.Servers,
                    title = HomeCopy.EmptyServersTitle,
                    body = HomeCopy.EmptyServersBody,
                    modifier = Modifier.testTag(ConnectionsTags.EMPTY),
                )
            } else {
                state.servers.forEach { server ->
                    ServerRow(
                        model = server.model,
                        state = server.rowState,
                        onOpen = { callbacks.onServer(server.id) },
                        onRetry =
                            if (server.rowState == ServerRowState.Unreachable) {
                                { callbacks.onRetryServer(server.id) }
                            } else {
                                null
                            },
                        modifier = Modifier.testTag(ConnectionsTags.item(server.id)),
                    )
                }
            }

            SeekerFab(
                label = HomeCopy.AddConnection,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = callbacks.onAddConnection,
                modifier = Modifier.testTag(ConnectionsTags.ADD),
            )
        }
    }
}

/** Thin runtime adapter. Repository/ViewModel work remains in the existing feature owners. */
@Composable
fun HomeRoute(
    connectionsState: ConnectionsUiState,
    inboxSummary: InboxSummary?,
    wallet: SelectedWallet?,
    pendingItems: List<PendingItem>,
    requestAssessments: Map<RequestKey, RequestAssessment>,
    callbacks: HomeRouteCallbacks,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val itemById = pendingItems.associateBy(PendingItem::homeId)
    MessageEffect(connectionsState.message, snackbar, callbacks.onMessageShown)

    Box(modifier.fillMaxSize()) {
        HomeScreen(
            state =
                homeScreenState(
                    connectionsState = connectionsState,
                    inboxSummary = inboxSummary,
                    wallet = wallet,
                    pendingItems = pendingItems,
                    requestAssessments = requestAssessments,
                ),
            callbacks =
                HomeScreenCallbacks(
                    onWallet = callbacks.onWallet,
                    onCopyWalletAddress = {
                        wallet?.let { selected ->
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("Wallet address", selected.address)
                                    )
                                )
                            }
                        }
                    },
                    onSeeAll = callbacks.onInbox,
                    onPending = { id -> itemById[id]?.let(callbacks.onOpenPending) },
                    onGlobalRules = callbacks.onGlobalRules,
                    onServer = callbacks.onOpenConnection,
                    onRetryServer = callbacks.onRetryConnection,
                    onAddConnection = callbacks.onAddConnection,
                    navigation =
                        ScreenNavigationCallbacks(
                            onHome = {},
                            onInbox = callbacks.onInbox,
                            onWallet = callbacks.onWallet,
                            onActivity = callbacks.onActivity,
                        ),
                ),
            modifier = Modifier.fillMaxSize(),
        )
        SeekerSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Compatibility entry point while the app root adopts [HomeRoute]. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ConnectionsScreen(
    state: ConnectionsUiState,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onLiveTest: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    inbox: InboxSummary? = null,
    onInbox: () -> Unit = {},
    wallet: SelectedWallet? = null,
    onWallet: () -> Unit = {},
    onGlobalRules: () -> Unit = {},
    activity: Int? = null,
    onActivity: () -> Unit = {},
    requests: List<ActionRequest> = emptyList(),
    requestAssessments: Map<RequestKey, RequestAssessment> = emptyMap(),
    onOpenRequest: (RequestKey) -> Unit = {},
    pendingItems: List<PendingItem>? = null,
    onOpenPending: (PendingItem) -> Unit = {},
    onRetry: (String) -> Unit = {},
) {
    val commonItems = pendingItems ?: requests.map(PendingItem::Private)
    HomeRoute(
        connectionsState = state,
        inboxSummary = inbox,
        wallet = wallet,
        pendingItems = commonItems,
        requestAssessments = requestAssessments,
        callbacks =
            HomeRouteCallbacks(
                onOpenConnection = onOpen,
                onRetryConnection = onRetry,
                onAddConnection = onAdd,
                onMessageShown = onMessageShown,
                onInbox = onInbox,
                onWallet = onWallet,
                onGlobalRules = onGlobalRules,
                onActivity = onActivity,
                onOpenPending = { item ->
                    if (pendingItems == null && item is PendingItem.Private) {
                        onOpenRequest(item.request.key)
                    } else {
                        onOpenPending(item)
                    }
                },
            ),
        modifier = modifier,
    )
}

fun homeScreenState(
    connectionsState: ConnectionsUiState,
    inboxSummary: InboxSummary?,
    wallet: SelectedWallet?,
    pendingItems: List<PendingItem>,
    requestAssessments: Map<RequestKey, RequestAssessment>,
    formatTime: (Instant) -> String = ::homeShortTime,
): HomeScreenState {
    val connections = connectionsState.connections.associateBy(Connection::id)
    val newestFirst =
        pendingItems.sortedWith(
            compareByDescending<PendingItem> { it.at }
                .thenBy { it.namespace }
                .thenBy { it.connectionId }
                .thenBy { it.requestId }
        )
    return HomeScreenState(
        wallet =
            HomeWalletState(
                name = wallet?.label?.takeIf(String::isNotBlank) ?: HomeCopy.Wallet,
                address = wallet?.address ?: HomeCopy.NoWallet,
                canCopy = wallet != null,
            ),
        pendingCount = inboxSummary?.waitingForYou ?: newestFirst.size,
        pending =
            newestFirst.map { item ->
                item.toHomeCarouselItem(
                    sourceName = connections[item.connectionId]?.label,
                    sourceColour = connections[item.connectionId]?.colour?.sourceColour(),
                    assessment =
                        (item as? PendingItem.Private)?.request?.key?.let(requestAssessments::get),
                )
            },
        serversLoaded = connectionsState.loaded,
        servers =
            connectionsState.connections.map { connection ->
                connection.toHomeServerState(
                    live = connectionsState.updates.connections[connection.id],
                    feed = connectionsState.feeds.gateways[connection.serverUrl],
                    pending =
                        newestFirst.count {
                            it is PendingItem.Signal && it.connectionId == connection.id
                        },
                    support = connectionsState.support[connection.id],
                    formatTime = formatTime,
                )
            },
    )
}

private fun PendingItem.toHomeCarouselItem(
    sourceName: String?,
    sourceColour: SourceColour?,
    assessment: RequestAssessment?,
): RequestCarouselItem {
    val request = envelope
    val capability = request.action.capabilityId
    val source = sourceName ?: request.identity.sourceId
    val warnings = assessment?.decision?.takeIf { it.warns }?.reasons?.size?.coerceAtLeast(1) ?: 0
    val kind =
        when (capability) {
            HomeCapability.Acknowledgement -> RequestTileKind.Acknowledgement
            HomeCapability.Prediction -> RequestTileKind.PredictionSignal
            HomeCapability.Swap -> RequestTileKind.SwapSignal
            HomeCapability.Signature -> RequestTileKind.SignatureRequest
            HomeCapability.Transfer -> RequestTileKind.Transfer
            else -> RequestTileKind.Acknowledgement
        }
    return RequestCarouselItem(
        id = homeId(),
        kind = kind,
        tile =
            when (kind) {
                RequestTileKind.Acknowledgement ->
                    RequestTileModel(
                        title = request.parameter("text") ?: request.presentation.title,
                        sourceName = source,
                        supportingText = "$source asks",
                        warningCount = warnings,
                        sourceColour = sourceColour,
                    )
                RequestTileKind.PredictionSignal ->
                    RequestTileModel(
                        title = request.presentation.title,
                        sourceName = source,
                        supportingText = request.presentation.description,
                        warningCount = warnings,
                        footerText = request.parameter("provider")?.providerName() ?: source,
                        sourceColour = sourceColour,
                    )
                RequestTileKind.SwapSignal ->
                    RequestTileModel(
                        title = request.presentation.title,
                        sourceName = source,
                        supportingText = request.presentation.description,
                        warningCount = warnings,
                        sourceColour = sourceColour,
                    )
                RequestTileKind.SignatureRequest ->
                    RequestTileModel(
                        title = request.messageByteCount().toString(),
                        sourceName = source,
                        supportingText = request.parameter("text") ?: HomeCopy.MessageBytes,
                        sourceColour = sourceColour,
                        warningCount = warnings,
                        signatureByteCount = request.messageByteCount(),
                    )
                RequestTileKind.Transfer -> {
                    val amountAndAsset = transferAmountAndAsset(request)
                    RequestTileModel(
                        title = amountAndAsset.first,
                        sourceName = source,
                        supportingText =
                            request.parameter("recipient")?.let { "to ${it.homeShortAddress()}" }
                                ?: HomeCopy.RecipientUnavailable,
                        warningCount = warnings,
                        assetSymbol = amountAndAsset.second,
                        sourceColour = sourceColour,
                    )
                }
            },
    )
}

private fun transferAmountAndAsset(request: Request): Pair<String, String?> {
    val amount = request.parameter("amount").orEmpty()
    val native = request.parameter("asset_native_sol") == true.toString()
    return if (native) {
        val display =
            amount.toULongOrNull()?.let { formatBaseUnits(it, LAMPORT_DECIMALS) } ?: amount
        display to HomeCopy.Sol
    } else {
        amount to request.parameter("asset_mint")?.homeShortAddress()
    }
}

private fun Connection.toHomeServerState(
    live: ForegroundConnectionState?,
    feed: FeedListenerState?,
    pending: Int,
    support: ServerSupport?,
    formatTime: (Instant) -> String,
): HomeServerState {
    val liveState = directTransport(live)
    val feedState = feedTransport(feed)
    val disconnected =
        revokedAt != null ||
            (mode == ConnectionMode.Direct && !hasCredential) ||
            liveState == ForegroundConnectionState.Revoked
    val unreachable =
        !disconnected &&
            (support?.executable == false ||
                feedState is FeedListenerState.Unreachable ||
                feedState is FeedListenerState.Refused ||
                feedState is FeedListenerState.Reconnecting ||
                liveState is ForegroundConnectionState.Unreachable ||
                liveState is ForegroundConnectionState.Unsupported ||
                (mode == ConnectionMode.Direct &&
                    liveState == null &&
                    lastCheck?.outcome?.let { it != CheckOutcome.Ok } == true))
    val rowState =
        when {
            disconnected -> ServerRowState.Disconnected
            unreachable -> ServerRowState.Unreachable
            else -> ServerRowState.Connected
        }
    val status =
        when (rowState) {
            ServerRowState.Disconnected -> HomeCopy.Disconnected
            ServerRowState.Unreachable ->
                if (mode == ConnectionMode.GatewayFeed) {
                    if (feedState is FeedListenerState.Reconnecting) {
                        "${HomeCopy.Reconnecting} · $pending pending"
                    } else {
                        "${HomeCopy.Unreachable} · $pending pending"
                    }
                } else {
                    lastCheck?.at?.let { "${HomeCopy.Unreachable} · ${formatTime(it)}" }
                        ?: HomeCopy.Unreachable
                }
            ServerRowState.Connected -> {
                val currentPending =
                    if (mode == ConnectionMode.GatewayFeed) pending else lastCheck?.pending ?: 0
                if (mode == ConnectionMode.Direct && lastCheck?.morePending == true) {
                    "Connected · more than $currentPending pending"
                } else {
                    "Connected · $currentPending pending"
                }
            }
        }
    return HomeServerState(
        id = id,
        model =
            ServerRowModel(
                sourceName = label,
                initials = label.homeInitials(),
                statusText = status,
                sourceColour = colour?.sourceColour(),
            ),
        rowState = rowState,
    )
}

private fun String.providerName(): String =
    split(Regex("[-_\\s]+")).filter(String::isNotBlank).joinToString(" ") { word ->
        word.replaceFirstChar(Char::uppercase)
    }

private fun PendingItem.homeId(): String = "$namespace/$connectionId/$requestId"

private fun String.homeInitials(): String =
    split(Regex("\\s+|-"))
        .filter(String::isNotBlank)
        .take(HomeInitialsCount)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { HomeCopy.ServerInitial }

private fun String.homeShortAddress(): String =
    if (length <= HomeAddressVisibleCharacters) {
        this
    } else {
        take(HomeAddressPrefixCharacters) + "…" + takeLast(HomeAddressSuffixCharacters)
    }

private fun Request.parameter(key: String): String? =
    action.parametersList
        .firstOrNull { it.key == key }
        ?.let { value ->
            when (value.valueCase) {
                Value.ValueCase.TEXT -> value.text
                Value.ValueCase.INTEGER -> value.integer
                Value.ValueCase.FLAG -> value.flag.toString()
                else -> null
            }
        }

private fun Request.messageByteCount(): Int =
    action.parametersList.firstOrNull { it.key == "data" }?.opaque?.size()
        ?: parameter("text")?.encodeToByteArray()?.size
        ?: 0

private fun homeShortTime(instant: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)

private object HomeCapability {
    const val Acknowledgement = "ack"
    const val Prediction = "prediction"
    const val Swap = "swap"
    const val Signature = "sign_message"
    const val Transfer = "transfer"
}

object HomeCopy {
    const val Title = "Seeker Agent Connect"
    const val Waiting = "Waiting for you"
    const val CarouselCaption =
        "Swipe to browse, tap to review. The carousel only browses — nothing is answered here."
    const val Rules = "Rules"
    const val GlobalRules = "Global rules"
    const val GlobalRulesNote = "Defaults for every connection · 4 of 4 sections on"
    const val Servers = "Paired servers"
    const val AddConnection = "Add connection"
    const val EmptyPendingTitle = "Nothing is waiting for you"
    const val EmptyPendingBody = "New requests and signals will appear here."
    const val EmptyServersTitle = "No paired servers"
    const val EmptyServersBody = "Add a connection to receive requests and signals."
    const val Wallet = "Wallet"
    const val NoWallet = "No wallet connected."
    const val MessageBytes = "Message bytes"
    const val RecipientUnavailable = "Recipient unavailable"
    const val Sol = "SOL"
    const val Disconnected = "Disconnected · pair again to reconnect"
    const val Reconnecting = "Reconnecting"
    const val Unreachable = "Couldn’t reach the server"
    const val ServerInitial = "S"
}

private const val HomeInitialsCount = 2
private const val HomeAddressPrefixCharacters = 8
private const val HomeAddressSuffixCharacters = 7
private const val HomeAddressVisibleCharacters =
    HomeAddressPrefixCharacters + HomeAddressSuffixCharacters
