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
import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.activity.openLink
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.designsystem.DetailScreenScaffold
import io.github.brrenat.seekervault.designsystem.EmptyState
import io.github.brrenat.seekervault.designsystem.EmptyStateScreen
import io.github.brrenat.seekervault.designsystem.HistoryDetailCallbacks
import io.github.brrenat.seekervault.designsystem.HistoryDetailRow
import io.github.brrenat.seekervault.designsystem.HistoryDetailScreen
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.InboxUiState
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
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
    clock: HistoryDetailClock = HistoryDetailClock(),
    /** How often a transaction still waiting on the network is asked about while open. */
    pollMillis: Long = HISTORY_POLL_MILLIS,
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
                        signalHistoryDetail(
                            record = record,
                            standing = feedStanding(record),
                            connection = connection,
                            choice = signalChoice(record),
                            clock = clock,
                        )
                    }
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
            ),
        modifier = modifier,
    )
}

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
