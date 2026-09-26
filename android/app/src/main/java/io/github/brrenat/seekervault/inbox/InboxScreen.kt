package io.github.brrenat.seekervault.inbox

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.isSandboxEnvironment
import io.github.brrenat.seekervault.designsystem.EmptyState
import io.github.brrenat.seekervault.designsystem.EmptyStateScreen
import io.github.brrenat.seekervault.designsystem.FilterBar
import io.github.brrenat.seekervault.designsystem.HistoryRow
import io.github.brrenat.seekervault.designsystem.HistoryRowModel
import io.github.brrenat.seekervault.designsystem.HistoryRowState
import io.github.brrenat.seekervault.designsystem.HistoryRowStatus
import io.github.brrenat.seekervault.designsystem.InboxRow
import io.github.brrenat.seekervault.designsystem.InboxRowKind
import io.github.brrenat.seekervault.designsystem.InboxRowModel
import io.github.brrenat.seekervault.designsystem.InboxRowOrigin
import io.github.brrenat.seekervault.designsystem.InboxRowTitleLines
import io.github.brrenat.seekervault.designsystem.InboxRowVerdict
import io.github.brrenat.seekervault.designsystem.InboxTab
import io.github.brrenat.seekervault.designsystem.ScreenCaption
import io.github.brrenat.seekervault.designsystem.ScreenDestination
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ScreenScaffold
import io.github.brrenat.seekervault.designsystem.ScreenScrollBody
import io.github.brrenat.seekervault.designsystem.SeekerTabBar
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.request.v2.Value
import io.github.brrenat.seekervault.requests.commonEnvelope
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class InboxPendingRowState(
    val id: String,
    val model: InboxRowModel,
    val kind: InboxRowKind,
    val origin: InboxRowOrigin,
    val verdict: InboxRowVerdict,
    val titleLines: InboxRowTitleLines = InboxRowTitleLines.One,
)

data class InboxHistoryRowState(
    val id: String,
    val model: HistoryRowModel,
    val rowState: HistoryRowState,
    val kind: InboxRowKind? = null,
    val status: HistoryRowStatus? = null,
)

/** The source the Inbox is narrowed to, and how much of the selected tab that leaves showing. */
data class InboxSourceFilterState(
    val sourceName: String,
    val visibleCount: Int,
    val totalCount: Int,
)

data class InboxScreenState(
    val selectedTab: InboxTab,
    val pending: List<InboxPendingRowState>,
    val history: List<InboxHistoryRowState>,
    val sourceFilter: InboxSourceFilterState? = null,
)

data class InboxScreenCallbacks(
    val onSelectTab: (InboxTab) -> Unit,
    val onReview: (String) -> Unit,
    val onOpenHistory: (String) -> Unit,
    val navigation: ScreenNavigationCallbacks,
    val onClearFilter: () -> Unit = {},
)

data class InboxRouteCallbacks(
    val onRefresh: () -> Unit,
    val onOpenRequest: (RequestKey) -> Unit,
    val onOpenSignal: (ProposalRecord) -> Unit,
    val navigation: ScreenNavigationCallbacks,
    /** A History row: the read-only record, never the review (SEE-161). */
    val onOpenHistory: (ReviewIdentity) -> Unit = {},
)

/**
 * What the Inbox is showing: the tab, the source it is narrowed to, and how far History is
 * scrolled. The app keeps it rather than the Inbox, so a visit to a record's details and Back — or
 * the process being recreated in between — return to exactly this (SEE-161).
 */
@Stable
class InboxViewState(tab: InboxTab? = null, sourceFilter: String? = null, historyScroll: Int = 0) {
    /** Null until the Inbox has chosen its opening tab. */
    var tab by mutableStateOf(tab)

    /** A connection ID, or null for every source. */
    var sourceFilter by mutableStateOf(sourceFilter)

    var historyScroll by mutableStateOf(ScrollState(historyScroll))
        private set

    /** A fresh visit: the Inbox picks its tab again, for every source, from the top. */
    fun reset(sourceFilter: String? = null) {
        tab = null
        this.sourceFilter = sourceFilter
        historyScroll = ScrollState(0)
    }

    companion object {
        val Saver: Saver<InboxViewState, Any> =
            listSaver(
                save = {
                    listOf(
                        it.tab?.name.orEmpty(),
                        it.sourceFilter.orEmpty(),
                        it.historyScroll.value,
                    )
                },
                restore = { saved ->
                    InboxViewState(
                        tab =
                            (saved[0] as String).takeIf(String::isNotEmpty)?.let(InboxTab::valueOf),
                        sourceFilter = (saved[1] as String).takeIf(String::isNotEmpty),
                        historyScroll = saved[2] as Int,
                    )
                },
            )
    }
}

@Composable
fun rememberInboxViewState(): InboxViewState =
    rememberSaveable(saver = InboxViewState.Saver) { InboxViewState() }

/**
 * Stateless SEE-121 root Inbox. Connection-filtered sheets continue to use PendingRequestsScreen.
 */
@Composable
fun InboxScreen(
    state: InboxScreenState,
    callbacks: InboxScreenCallbacks,
    modifier: Modifier = Modifier,
    historyScroll: ScrollState = rememberScrollState(),
) {
    ScreenScaffold(
        title = InboxCopy.Title,
        selectedDestination = ScreenDestination.Inbox,
        navigationCallbacks = callbacks.navigation,
        modifier = modifier,
        onBack = callbacks.navigation.onHome,
    ) {
        Column(Modifier.fillMaxSize()) {
            SeekerTabBar(
                selected = state.selectedTab,
                onSelect = callbacks.onSelectTab,
                modifier = Modifier.testTag(InboxScreenTags.Tabs),
            )
            // A swipe left or right moves between the tabs, and a tap on a tab moves the pages: the
            // selected tab stays the one source of truth, and the pager follows it both ways.
            val pager =
                rememberPagerState(initialPage = state.selectedTab.ordinal) {
                    InboxTab.entries.size
                }
            val select by rememberUpdatedState(callbacks.onSelectTab)
            LaunchedEffect(state.selectedTab) {
                if (pager.targetPage != state.selectedTab.ordinal) {
                    pager.animateScrollToPage(state.selectedTab.ordinal)
                }
            }
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.collect { select(InboxTab.entries[it]) }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
            ) { page ->
                val tab = InboxTab.entries[page]
                ScreenScrollBody(
                    Modifier.fillMaxSize().testTag(InboxTags.LIST),
                    state = if (tab == InboxTab.History) historyScroll else rememberScrollState(),
                ) {
                    // The first row sits as far below the tabs as the rows sit from the screen's
                    // sides: the body's own top padding plus one row gap make the side margin.
                    Spacer(
                        Modifier.height(
                            SeekerTheme.spacing.xl - SeekerTheme.spacing.xs - SeekerTheme.spacing.lg
                        )
                    )
                    state.sourceFilter?.let { filter ->
                        FilterBar(
                            sourceName = filter.sourceName,
                            visibleCount = filter.visibleCount,
                            totalCount = filter.totalCount,
                            onClear = callbacks.onClearFilter,
                            modifier = Modifier.testTag(InboxScreenTags.Filter),
                        )
                    }
                    when (tab) {
                        InboxTab.Pending -> {
                            if (state.pending.isEmpty()) {
                                EmptyState(
                                    screen = EmptyStateScreen.Inbox,
                                    title = InboxCopy.EmptyPendingTitle,
                                    body = InboxCopy.EmptyPendingBody,
                                    modifier = Modifier.testTag(InboxTags.EMPTY),
                                )
                            } else {
                                state.pending.forEach { item ->
                                    InboxRow(
                                        model = item.model,
                                        kind = item.kind,
                                        origin = item.origin,
                                        verdict = item.verdict,
                                        titleLines = item.titleLines,
                                        onReview = { callbacks.onReview(item.id) },
                                        modifier =
                                            Modifier.testTag(InboxScreenTags.pending(item.id)),
                                    )
                                }
                                ScreenCaption(InboxCopy.FooterCaption)
                            }
                        }
                        InboxTab.History -> {
                            if (state.history.isEmpty()) {
                                EmptyState(
                                    screen = EmptyStateScreen.Activity,
                                    title = InboxCopy.EmptyHistoryTitle,
                                    body = InboxCopy.EmptyHistoryBody,
                                    modifier = Modifier.testTag(InboxScreenTags.EmptyHistory),
                                )
                            } else {
                                state.history.forEach { item ->
                                    HistoryRow(
                                        model = item.model,
                                        state = item.rowState,
                                        onClick = { callbacks.onOpenHistory(item.id) },
                                        modifier =
                                            Modifier.testTag(InboxScreenTags.history(item.id)),
                                        kind = item.kind,
                                        status = item.status,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Thin state/callback adapter for the root destination. */
@Composable
fun InboxRoute(
    state: InboxUiState,
    feedRecords: List<ProposalRecord>,
    feedStanding: (ProposalRecord) -> ProposalStanding,
    now: Instant,
    callbacks: InboxRouteCallbacks,
    modifier: Modifier = Modifier,
    view: InboxViewState = rememberInboxViewState(),
) {
    val initial =
        inboxInitialTab(
            state = state,
            feedRecords = feedRecords,
            feedStanding = feedStanding,
        )
    // The opening tab is chosen once per visit, from what is there when the Inbox opens.
    SideEffect { if (view.tab == null) view.tab = initial }
    val selected = view.tab ?: initial
    val mapped =
        inboxScreenState(
            state = state,
            feedRecords = feedRecords,
            feedStanding = feedStanding,
            selectedTab = selected,
            now = now,
            sourceFilter = view.sourceFilter,
        )
    val recordById = feedRecords.associateBy(::signalId)

    LaunchedEffect(Unit) { callbacks.onRefresh() }
    InboxScreen(
        state = mapped,
        callbacks =
            InboxScreenCallbacks(
                onSelectTab = { view.tab = it },
                onReview = { id ->
                    privateKey(id)?.let(callbacks.onOpenRequest)
                        ?: recordById[id]?.let(callbacks.onOpenSignal)
                },
                onOpenHistory = { id ->
                    val identity =
                        privateKey(id)?.let {
                            ReviewIdentity.Private(it.connectionId, it.requestId)
                        }
                            ?: recordById[id]?.let {
                                ReviewIdentity.Signal(it.connectionId, it.key.proposalId)
                            }
                    identity?.let(callbacks.onOpenHistory)
                },
                navigation = callbacks.navigation,
                onClearFilter = { view.sourceFilter = null },
            ),
        modifier = modifier,
        historyScroll = view.historyScroll,
    )
}

fun inboxInitialTab(
    state: InboxUiState,
    feedRecords: List<ProposalRecord>,
    feedStanding: (ProposalRecord) -> ProposalStanding,
): InboxTab {
    val privateItems = inboxItems(state.inbox, null)
    val hasPending = pendingItems(state.inbox, feedRecords, feedStanding).isNotEmpty()
    val hasHistory =
        privateItems.toSend.isNotEmpty() ||
            privateItems.answered.isNotEmpty() ||
            feedRecords.any { feedStanding(it) !is ProposalStanding.Open }
    return if (!hasPending && hasHistory) InboxTab.History else InboxTab.Pending
}

fun inboxScreenState(
    state: InboxUiState,
    feedRecords: List<ProposalRecord>,
    feedStanding: (ProposalRecord) -> ProposalStanding,
    selectedTab: InboxTab,
    now: Instant,
    formatTime: (Instant) -> String = ::inboxShortTime,
    sourceFilter: String? = null,
): InboxScreenState {
    val connections = state.connections.associateBy(Connection::id)
    // A filter for a connection that is gone narrows to nothing, so it is dropped instead.
    val filter = sourceFilter?.takeIf { it in connections }
    val unfiltered =
        if (filter == null) null
        else inboxScreenState(state, feedRecords, feedStanding, selectedTab, now, formatTime)
    val privateItems = inboxItems(state.inbox, filter)
    val shownRecords = feedRecords.filter { filter == null || it.connectionId == filter }
    val pending =
        pendingItems(state.inbox, shownRecords, feedStanding)
            .filter { filter == null || it.connectionId == filter }
            .sortedWith(
                compareByDescending<PendingItem> { it.at }
                    .thenBy { it.namespace }
                    .thenBy { it.connectionId }
                    .thenBy { it.requestId }
            )
            .map { item ->
                item.toInboxPendingRow(
                    connection = connections[item.connectionId],
                    assessment =
                        (item as? PendingItem.Private)?.request?.key?.let(state.assessments::get),
                    walletNetwork = state.wallet?.network,
                    now = now,
                    formatTime = formatTime,
                )
            }
    val privateHistory =
        (privateItems.toSend + privateItems.answered).map { result ->
            result.toInboxHistoryRow(
                sourceName = connections[result.connectionId]?.label,
                formatTime = formatTime,
            )
        }
    val signalHistory = shownRecords.mapNotNull { record ->
        val standing = feedStanding(record)
        if (standing is ProposalStanding.Open) {
            null
        } else {
            record.toInboxHistoryRow(
                sourceName = connections[record.connectionId]?.label,
                standing = standing,
                formatTime = formatTime,
            )
        }
    }
    val history = (privateHistory + signalHistory).sortedByDescending { it.at }.map { it.row }
    return InboxScreenState(
        selectedTab = selectedTab,
        pending = pending,
        history = history,
        sourceFilter =
            if (filter == null || unfiltered == null) {
                null
            } else {
                InboxSourceFilterState(
                    sourceName = connections.getValue(filter).label,
                    visibleCount =
                        if (selectedTab == InboxTab.History) history.size else pending.size,
                    totalCount =
                        if (selectedTab == InboxTab.History) unfiltered.history.size
                        else unfiltered.pending.size,
                )
            },
    )
}

private fun PendingItem.toInboxPendingRow(
    connection: Connection?,
    assessment: RequestAssessment?,
    walletNetwork: WalletNetwork?,
    now: Instant,
    formatTime: (Instant) -> String,
): InboxPendingRowState {
    val request = envelope
    val capability = request.action.capabilityId
    val kind = capability.inboxKind()
    val sourceName = connection?.label ?: request.identity.sourceId
    val warningCount =
        assessment?.decision?.takeIf { it.warns }?.reasons?.size?.coerceAtLeast(1)
            ?: if (this is PendingItem.Signal) 1 else 0
    val sandbox = connection?.isSandboxEnvironment() == true
    return InboxPendingRowState(
        id =
            when (this) {
                is PendingItem.Private -> privateId(this.request.key)
                is PendingItem.Signal -> signalId(record)
            },
        model =
            InboxRowModel(
                title = request.inboxTitle(capability),
                supportingText = request.inboxSupportingText(capability),
                sourceName = sourceName,
                timestampAndExpiryText =
                    "${formatTime(at)} · ${expiryText(request.lifecycle.expiresAt.instant(), now)}",
                environmentText =
                    if (sandbox) {
                        InboxCopy.Sandbox
                    } else {
                        InboxCopy.Production
                    },
                networkText = request.inboxNetwork(capability, walletNetwork),
                warningCount = warningCount,
            ),
        kind = kind,
        origin = if (this is PendingItem.Signal) InboxRowOrigin.Signal else InboxRowOrigin.Request,
        verdict = if (warningCount > 0) InboxRowVerdict.Warning else InboxRowVerdict.Ok,
        titleLines = InboxRowTitleLines.One,
    )
}

private fun LocalResult.toInboxHistoryRow(
    sourceName: String?,
    formatTime: (Instant) -> String,
): TimedHistoryRow {
    val cancelled =
        delivery == Delivery.Superseded && request.state == RequestState.REQUEST_STATE_CANCELLED
    val expired =
        delivery == Delivery.Superseded && request.state == RequestState.REQUEST_STATE_EXPIRED
    val rowState =
        when {
            cancelled -> HistoryRowState.Cancelled
            expired -> HistoryRowState.Expired
            signing is SigningOutcome.Sent -> HistoryRowState.Sent
            else -> HistoryRowState.Unknown
        }
    val outcome =
        when {
            cancelled -> "Cancelled by the sender"
            expired -> "Expired before you answered"
            delivery == Delivery.Undeliverable -> "Couldn’t tell the server"
            delivery == Delivery.Waiting && answer == Answer.Acknowledge ->
                "Waiting to tell the server you acknowledged"
            delivery == Delivery.Waiting && answer == Answer.Reject ->
                "Waiting to tell the server you rejected"
            delivery == Delivery.Waiting && signing is SigningOutcome.Signed ->
                "Waiting to send the wallet signature"
            delivery == Delivery.Waiting && signing is SigningOutcome.Sent ->
                "Waiting to tell the server the transaction was sent"
            delivery == Delivery.Waiting -> "Waiting to tell the server"
            answer == Answer.Acknowledge -> "Acknowledged"
            answer == Answer.Reject -> "Rejected"
            signing is SigningOutcome.Sent &&
                request.state == RequestState.REQUEST_STATE_CONFIRMED -> "Confirmed on the network"
            signing is SigningOutcome.Sent && request.state == RequestState.REQUEST_STATE_FAILED ->
                "Failed on the network"
            signing is SigningOutcome.Sent -> "Sent to the network"
            signing is SigningOutcome.Signed -> "Signed by your wallet"
            signing == SigningOutcome.Declined -> "Declined in the wallet"
            signing is SigningOutcome.Unresolved -> "Wallet declined"
            signing is SigningOutcome.Failed -> "Wallet did not complete it"
            else -> "Waiting for the wallet"
        }
    val status =
        when {
            cancelled -> HistoryRowStatus.Cancelled
            expired -> HistoryRowStatus.Expired
            answer == Answer.Reject -> HistoryRowStatus.Declined
            answer == Answer.Acknowledge -> HistoryRowStatus.Confirmed
            else ->
                when (signing) {
                    null -> HistoryRowStatus.Pending
                    is SigningOutcome.Signed -> HistoryRowStatus.Signed
                    is SigningOutcome.Sent ->
                        when (request.state) {
                            RequestState.REQUEST_STATE_CONFIRMED -> HistoryRowStatus.Confirmed
                            RequestState.REQUEST_STATE_FAILED -> HistoryRowStatus.Failed
                            else -> HistoryRowStatus.Pending
                        }
                    SigningOutcome.Declined -> HistoryRowStatus.Declined
                    is SigningOutcome.Failed -> HistoryRowStatus.Failed
                    is SigningOutcome.Unresolved -> HistoryRowStatus.Unknown
                }
        }
    val envelope = request.commonEnvelope()
    return TimedHistoryRow(
        row =
            InboxHistoryRowState(
                id = privateId(key),
                model =
                    HistoryRowModel(
                        title = envelope.inboxTitle(envelope.action.capabilityId),
                        sourceName = sourceName ?: connectionId,
                        outcomeText = outcome,
                        timestampText = formatTime(answeredAt),
                        isSignal = false,
                    ),
                rowState = rowState,
                kind = envelope.action.capabilityId.inboxKind(),
                status = status,
            ),
        at = answeredAt,
    )
}

private fun ProposalRecord.toInboxHistoryRow(
    sourceName: String?,
    standing: ProposalStanding,
    formatTime: (Instant) -> String,
): TimedHistoryRow {
    val status =
        when (standing) {
            is ProposalStanding.Executed ->
                when (standing.outcome) {
                    is ProposalOutcome.Submitted,
                    ProposalOutcome.Pending -> HistoryRowStatus.Pending
                    ProposalOutcome.Simulated -> HistoryRowStatus.Simulated
                    ProposalOutcome.Declined -> HistoryRowStatus.Declined
                    is ProposalOutcome.Failed -> HistoryRowStatus.Failed
                    is ProposalOutcome.Unresolved -> HistoryRowStatus.Unknown
                }
            is ProposalStanding.Dismissed -> HistoryRowStatus.Dismissed
            ProposalStanding.Cancelled -> HistoryRowStatus.Cancelled
            ProposalStanding.Expired -> HistoryRowStatus.Expired
            is ProposalStanding.Refused,
            is ProposalStanding.Unsupported,
            ProposalStanding.Open -> HistoryRowStatus.Unknown
        }
    val (rowState, outcome) =
        when (standing) {
            is ProposalStanding.Executed ->
                when (standing.outcome) {
                    is ProposalOutcome.Submitted -> HistoryRowState.Sent to "Sent to the network"
                    ProposalOutcome.Simulated ->
                        HistoryRowState.Simulated to "Simulated on this phone · no funds moved"
                    ProposalOutcome.Declined -> HistoryRowState.Unknown to "Declined in the wallet"
                    is ProposalOutcome.Failed ->
                        HistoryRowState.Unknown to "Wallet did not complete it"
                    is ProposalOutcome.Unresolved -> HistoryRowState.Unknown to "Wallet declined"
                    ProposalOutcome.Pending -> HistoryRowState.Unknown to "In progress"
                }
            is ProposalStanding.Dismissed -> HistoryRowState.Dismissed to "Dismissed on this phone"
            ProposalStanding.Cancelled -> HistoryRowState.Cancelled to "Cancelled by the publisher"
            ProposalStanding.Expired -> HistoryRowState.Expired to "Expired"
            is ProposalStanding.Refused -> HistoryRowState.Unknown to "Refused on this phone"
            is ProposalStanding.Unsupported ->
                HistoryRowState.Unknown to "Unsupported by this build"
            ProposalStanding.Open -> error("Open proposals are not history")
        }
    val at =
        when (standing) {
            is ProposalStanding.Executed ->
                execution?.settledAt ?: execution?.startedAt ?: proposal.updatedAt
            is ProposalStanding.Dismissed -> standing.at
            ProposalStanding.Expired -> proposal.expiresAt
            else -> proposal.updatedAt
        }
    val request = proposal.commonEnvelope()
    return TimedHistoryRow(
        row =
            InboxHistoryRowState(
                id = signalId(this),
                model =
                    HistoryRowModel(
                        title = request.inboxTitle(request.action.capabilityId),
                        sourceName = sourceName ?: proposal.key.serverId,
                        outcomeText = outcome,
                        timestampText = formatTime(at),
                        isSignal = true,
                    ),
                rowState = rowState,
                kind = request.action.capabilityId.inboxKind(),
                status = status,
            ),
        at = at,
    )
}

private data class TimedHistoryRow(val row: InboxHistoryRowState, val at: Instant)

internal fun Request.inboxTitle(capability: String): String =
    when (capability) {
        InboxCapability.Acknowledgement -> "Acknowledge a message"
        InboxCapability.Prediction -> presentation.title
        InboxCapability.Transfer -> "Send ${transferAmount()}"
        InboxCapability.Swap -> presentation.title
        InboxCapability.Signature -> "Sign a message"
        else -> presentation.title.ifBlank { "Review request" }
    }

private fun Request.inboxSupportingText(capability: String): String =
    when (capability) {
        InboxCapability.Acknowledgement -> "“${parameter("text").orEmpty()}” · nothing is signed"
        InboxCapability.Prediction,
        InboxCapability.Swap -> presentation.description
        InboxCapability.Transfer -> "funds move"
        InboxCapability.Signature -> "${messageByteCount()} bytes · no funds move"
        else -> presentation.description
    }

private fun Request.inboxNetwork(
    capability: String,
    walletNetwork: WalletNetwork?,
): String? {
    if (capability == InboxCapability.Acknowledgement) return null
    val requestNetwork = parameter("network")
    return when (requestNetwork) {
        Network.NETWORK_MAINNET.name -> "Solana mainnet"
        Network.NETWORK_DEVNET.name -> "Solana devnet"
        Network.NETWORK_TESTNET.name -> "Solana testnet"
        else ->
            when (walletNetwork) {
                WalletNetwork.Mainnet -> "Solana mainnet"
                WalletNetwork.Devnet -> "Solana devnet"
                WalletNetwork.Testnet -> "Solana testnet"
                null -> null
            }
    }
}

private fun Request.transferAmount(): String {
    val amount = parameter("amount").orEmpty()
    return if (parameter("asset_native_sol") == true.toString()) {
        val display =
            amount.toULongOrNull()?.let { formatBaseUnits(it, LAMPORT_DECIMALS) } ?: amount
        "$display SOL"
    } else {
        "$amount units"
    }
}

private fun Request.messageByteCount(): Int =
    action.parametersList.firstOrNull { it.key == "data" }?.opaque?.size()
        ?: parameter("text")?.encodeToByteArray()?.size
        ?: 0

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

private fun String.inboxKind(): InboxRowKind =
    when (this) {
        InboxCapability.Prediction -> InboxRowKind.Prediction
        InboxCapability.Transfer -> InboxRowKind.Transfer
        InboxCapability.Swap -> InboxRowKind.Swap
        InboxCapability.Signature -> InboxRowKind.Signature
        else -> InboxRowKind.Acknowledgement
    }

private fun expiryText(expiry: Instant, now: Instant): String {
    val seconds = Duration.between(now, expiry).seconds
    if (seconds <= 0) return "expired"
    val hours = (seconds + SecondsPerHour - 1) / SecondsPerHour
    if (hours >= 1) return "expires in $hours ${if (hours == 1L) "hour" else "hours"}"
    val minutes = (seconds + SecondsPerMinute - 1) / SecondsPerMinute
    return "expires in $minutes ${if (minutes == 1L) "minute" else "minutes"}"
}

private fun inboxShortTime(instant: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)

private fun privateId(key: RequestKey): String = "private/${key.connectionId}/${key.requestId}"

private fun signalId(record: ProposalRecord): String =
    "feed/${record.connectionId}/${record.key.proposalId}"

private fun privateKey(id: String): RequestKey? {
    if (!id.startsWith(PrivatePrefix)) return null
    val parts = id.split('/')
    return if (parts.size == PrivateIdParts) RequestKey(parts[1], parts[2]) else null
}

private object InboxCapability {
    const val Acknowledgement = "ack"
    const val Prediction = "prediction"
    const val Transfer = "transfer"
    const val Swap = "swap"
    const val Signature = "sign_message"
}

object InboxCopy {
    const val Title = "Inbox"
    const val Production = "Production"
    const val Sandbox = "Sandbox · no funds will move"
    const val FooterCaption =
        "Requests and signals wait in one list, newest first. Review opens the whole operation: " +
            "a production request hands off to the wallet, a sandbox item is only simulated on " +
            "this phone."
    const val EmptyPendingTitle = "Nothing is waiting for you"
    const val EmptyPendingBody =
        "Everything you answered, dismissed, or that expired or was cancelled, is under History."
    const val EmptyHistoryTitle = "No history yet"
    const val EmptyHistoryBody =
        "Answered, dismissed, expired, and cancelled items will appear here."
}

object InboxScreenTags {
    const val Tabs = "inboxRootTabs"
    const val EmptyHistory = "inboxHistoryEmpty"
    const val Filter = "inboxSourceFilter"

    fun pending(id: String) =
        if (id.startsWith(PrivatePrefix)) {
            "request:${id.removePrefix(PrivatePrefix)}"
        } else {
            "inboxPending:$id"
        }

    fun history(id: String) =
        if (id.startsWith(PrivatePrefix)) {
            "request:${id.removePrefix(PrivatePrefix)}"
        } else {
            "inboxHistory:$id"
        }
}

private const val SecondsPerMinute = 60L
private const val SecondsPerHour = 60L * SecondsPerMinute
private const val PrivatePrefix = "private/"
private const val PrivateIdParts = 3
