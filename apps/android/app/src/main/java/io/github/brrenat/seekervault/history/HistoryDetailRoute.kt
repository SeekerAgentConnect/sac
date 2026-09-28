package io.github.brrenat.seekervault.history

import android.content.ClipData
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.activity.openLink
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.designsystem.DetailScreenScaffold
import io.github.brrenat.seekervault.designsystem.EmptyState
import io.github.brrenat.seekervault.designsystem.EmptyStateScreen
import io.github.brrenat.seekervault.designsystem.HistoryDetailCallbacks
import io.github.brrenat.seekervault.designsystem.HistoryDetailLink
import io.github.brrenat.seekervault.designsystem.HistoryDetailModel
import io.github.brrenat.seekervault.designsystem.HistoryDetailRow
import io.github.brrenat.seekervault.designsystem.HistoryDetailScreen
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.InboxUiState
import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.positions.HoldingRecord
import io.github.brrenat.seekervault.positions.PositionsState
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The History record page for one closed item (SEE-161). It reads the stored records the app
 * already publishes, so a transfer the sidecar learns more about while the page is open updates in
 * place — execution, transaction chip and timeline together — and it asks for nothing else: no
 * rules, no quote, no review.
 */
@Composable
fun HistoryDetailRoute(
    identity: ReviewIdentity,
    connections: List<Connection>,
    inboxState: InboxUiState,
    feedRecords: List<ProposalRecord>,
    feedStanding: (ProposalRecord) -> ProposalStanding,
    /** The owner's recorded choice for a signal, in words; see [choiceRows]. */
    signalChoice: @Composable (ProposalRecord) -> List<HistoryDetailRow>,
    onSendAgain: (RequestKey) -> Unit,
    onCheckStatus: (RequestKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the phone itself last found on chain, by request (SEE-165). */
    chainChecks: Map<RequestKey, ChainCheck> = emptyMap(),
    /** Requests whose owner-requested chain check is still out. */
    checkingChain: Set<RequestKey> = emptySet(),
    /** Asks the network about one transaction now; it never signs or sends. */
    onCheckChain: (RequestKey) -> Unit = {},
    clock: HistoryDetailClock = HistoryDetailClock(),
    /** How often a transaction still waiting on the network is asked about while open. */
    pollMillis: Long = HISTORY_POLL_MILLIS,
    /** The positions this phone follows and its sale attempts (SEE-172). */
    positions: PositionsState = PositionsState(),
    /** The wallet selected now, which decides only whether Sell is offered. */
    wallet: SelectedWallet? = null,
    /** Where the owner continues on the provider, for a position. */
    positionLinks: (HeldPosition) -> List<HistoryDetailLink> = { emptyList() },
    /** Reads a position again; never prepares, signs or sends. */
    onRefreshPosition: (String) -> Unit = {},
    /** Opens the sale review of a position. */
    onSellPosition: (String) -> Unit = {},
    /** Opens a provider link, app first. */
    onOpenProvider: (String) -> Unit = {},
    now: () -> Instant = Instant::now,
    /**
     * Tracked purchases whose feed record is gone (SEE-172). A signal found here and not in
     * [feedRecords] is rebuilt from the owner's own records, so its position stays reachable.
     */
    retained: List<RetainedPurchase> = emptyList(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val connection = connections.firstOrNull { it.id == identity.connectionId }
    val model =
        when (identity) {
            is ReviewIdentity.Private -> {
                val key = RequestKey(identity.connectionId, identity.requestId)
                inboxState.inbox.result(key)?.let { result ->
                    // Keeps asking the sidecar what it has learned from the chain while the page is
                    // open and the answer is still out; it opens no wallet and sends nothing.
                    if (result.awaitingChain) {
                        LaunchedEffect(key) {
                            while (true) {
                                onCheckStatus(key)
                                delay(pollMillis)
                            }
                        }
                    }
                    privateHistoryDetail(
                        result = result,
                        connection = connection,
                        clock = clock,
                        sending = key in inboxState.sending,
                        chain = chainChecks[key],
                        checkingChain = key in checkingChain,
                    )
                }
            }
            is ReviewIdentity.Signal ->
                feedRecords
                    .firstOrNull {
                        it.connectionId == identity.connectionId &&
                            it.key.proposalId == identity.requestId
                    }
                    ?.let { record ->
                        val key = RequestKey(record.connectionId, record.key.proposalId)
                        val holding = positions.holdingOf(record)
                        holding?.let { PositionPolling(it, positions, onRefreshPosition) }
                        val base =
                            signalHistoryDetail(
                                record = record,
                                standing = feedStanding(record),
                                connection = connection,
                                choice = signalChoice(record),
                                clock = clock,
                                chain = chainChecks[key],
                                checkingChain = key in checkingChain,
                            )
                        val links =
                            holding?.held?.let(positionLinks)
                                ?: record.execution
                                    ?.binding
                                    ?.let { binding ->
                                        positionLinks(
                                            HeldPosition(
                                                provider = binding.provider,
                                                owner = binding.wallet,
                                                network = binding.network,
                                                account = "",
                                                marketId = binding.instrument.id,
                                                yes = true,
                                            )
                                        )
                                    }
                                    .orEmpty()
                        base.copy(
                            position =
                                positionDetail(
                                    record = record,
                                    positions = positions,
                                    wallet = wallet,
                                    links = links,
                                    now = now(),
                                    clock = clock,
                                ),
                            transactions =
                                base.transactions +
                                    saleTransactions(record, positions, chainChecks),
                        )
                    }
                    ?: retainedDetail(
                        key = RequestKey(identity.connectionId, identity.requestId),
                        retained = retained,
                        positions = positions,
                        wallet = wallet,
                        positionLinks = positionLinks,
                        onRefreshPosition = onRefreshPosition,
                        chainChecks = chainChecks,
                        checkingChain = checkingChain,
                        clock = clock,
                        now = now(),
                    )
        }

    if (model == null) {
        // A settled answer is kept for a week, and removing a connection removes its records:
        // the row that led here can outlive neither.
        DetailScreenScaffold(title = "History", onBack = onBack, modifier = modifier) {
            EmptyState(
                screen = EmptyStateScreen.Activity,
                title = HistoryDetailRouteCopy.GoneTitle,
                body = HistoryDetailRouteCopy.GoneBody,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(SeekerTheme.spacing.xl)
                        .testTag(HistoryDetailRouteTags.Gone),
            )
        }
        return
    }

    HistoryDetailScreen(
        model = model,
        callbacks =
            HistoryDetailCallbacks(
                onBack = onBack,
                onSendAgain = {
                    (identity as? ReviewIdentity.Private)?.let {
                        onSendAgain(RequestKey(it.connectionId, it.requestId))
                    }
                },
                onCopy = { value ->
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("Transaction signature", value))
                        )
                    }
                },
                onOpenExplorer = { url -> openLink(context, url) },
                onRefreshPosition = { positionOf(identity, positions)?.let(onRefreshPosition) },
                onSellPosition = { positionOf(identity, positions)?.let(onSellPosition) },
                onOpenProvider = onOpenProvider,
                onCheckStatus = {
                    val key = RequestKey(identity.connectionId, identity.requestId)
                    // The phone's own check first; for a direct request the server is asked too,
                    // and whichever settles it first is what the page shows.
                    onCheckChain(key)
                    if (identity is ReviewIdentity.Private) onCheckStatus(key)
                },
            ),
        modifier = modifier,
    )
}

/**
 * The position account [identity]'s purchase went into, when this phone follows one. Read from the
 * holding's own link to the purchase, so it needs no feed record (SEE-172).
 */
private fun positionOf(identity: ReviewIdentity, positions: PositionsState): String? =
    positions.holdingFor(identity)?.held?.account

/** The page for a tracked purchase whose feed record is gone, with its live position. */
@Composable
private fun retainedDetail(
    key: RequestKey,
    retained: List<RetainedPurchase>,
    positions: PositionsState,
    wallet: SelectedWallet?,
    positionLinks: (HeldPosition) -> List<HistoryDetailLink>,
    onRefreshPosition: (String) -> Unit,
    chainChecks: Map<RequestKey, ChainCheck>,
    checkingChain: Set<RequestKey>,
    clock: HistoryDetailClock,
    now: Instant,
): HistoryDetailModel? {
    val purchase = retained.firstOrNull { it.key == key } ?: return null
    // The holding as it is now, not as it was when the list was built.
    val holding = positions.holdingFor(key) ?: return null
    PositionPolling(holding, positions, onRefreshPosition)
    val base =
        retainedHistoryDetail(
            retained = purchase.copy(holding = holding),
            clock = clock,
            chain = chainChecks[key] ?: purchase.activity?.chain,
            checkingChain = key in checkingChain,
        )
    return base.copy(
        position =
            positionDetail(
                key = key,
                positions = positions,
                wallet = wallet,
                links = positionLinks(holding.held),
                now = now,
                clock = clock,
            ),
        transactions = base.transactions + saleTransactions(key, positions, chainChecks),
    )
}

/**
 * Reads the position when the page opens, and — only while one of its orders or sales is still
 * unresolved — again with a growing pause, for a bounded time. A stable open position is not
 * polled: nobody is promised a live price the phone cannot keep up.
 */
@Composable
private fun PositionPolling(
    holding: HoldingRecord,
    positions: PositionsState,
    onRefresh: (String) -> Unit,
) {
    val account = holding.held.account
    val unresolved = holding.ordersUnresolved || positions.salesOf(account).any { it.inFlight }
    // On opening, and on every return to the app — from the wallet or from Jupiter, where the
    // position may have been changed by hand.
    LifecycleResumeEffect(account) {
        onRefresh(account)
        onPauseOrDispose {}
    }
    if (!unresolved) return
    LaunchedEffect(account, unresolved) {
        var pause = POSITION_POLL_FIRST_MILLIS
        var waited = 0L
        while (waited < POSITION_POLL_MOST_MILLIS) {
            delay(pause)
            waited += pause
            onRefresh(account)
            pause = (pause * 2).coerceAtMost(POSITION_POLL_LONGEST_MILLIS)
        }
    }
}

/** The first pause while an order or sale is unresolved; it doubles up to the longest. */
const val POSITION_POLL_FIRST_MILLIS = 5_000L

const val POSITION_POLL_LONGEST_MILLIS = 60_000L

/** How long a page keeps polling one unresolved position before it leaves it to Refresh. */
const val POSITION_POLL_MOST_MILLIS = 15 * 60_000L

object HistoryDetailRouteCopy {
    const val GoneTitle = "This record is no longer on this phone"
    const val GoneBody =
        "Answers are kept for a week after they settle, and removing a connection removes its " +
            "records."
}

object HistoryDetailRouteTags {
    const val Gone = "historyDetailGone"
}

const val HISTORY_POLL_MILLIS = 15_000L
