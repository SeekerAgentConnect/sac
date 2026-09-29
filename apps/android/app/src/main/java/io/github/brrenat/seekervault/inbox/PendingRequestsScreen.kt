package io.github.brrenat.seekervault.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.hasProblem
import io.github.brrenat.seekervault.connections.statusText as connectionStatusText
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.operations.standingText
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.requests.commonEnvelope
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.ui.NetworkChip
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.wallet.networkText
import java.time.Instant

/**
 * Requests is one of the four root destinations; a connection-filtered copy can live in a sheet.
 */
@Composable
fun PendingRequestsScreen(
    state: InboxUiState,
    connectionId: String?,
    now: Instant,
    onOpen: (RequestKey) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onReject: (RequestKey) -> Unit = {},
    inSheet: Boolean = false,
    feedRecords: List<ProposalRecord> = emptyList(),
    feedStanding: (ProposalRecord) -> ProposalStanding = { ProposalStanding.Expired },
    onOpenSignal: (ProposalRecord) -> Unit = {},
) {
    val containerColor =
        if (inSheet) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surface
    val labels = state.connections.associate { it.id to it.label }
    val shown = state.connections.filter { connectionId == null || it.id == connectionId }
    val inbox = inboxItems(state.inbox, connectionId)
    val pending = pendingItems(state.inbox, feedRecords, feedStanding, connectionId)
    val relevantSignals = feedRecords.filter {
        connectionId == null || it.connectionId == connectionId
    }
    val settledSignals =
        relevantSignals
            .filter { feedStanding(it) !is ProposalStanding.Open }
            .sortedWith(
                compareByDescending<ProposalRecord> { it.proposal.updatedAt }
                    .thenBy { it.connectionId }
                    .thenBy { it.key.proposalId }
            )
    val startTab =
        if (
            pending.isEmpty() &&
                inbox.toSend.isEmpty() &&
                (inbox.answered.isNotEmpty() || settledSignals.isNotEmpty())
        )
            1
        else 0
    var selected by rememberSaveable(startTab) { mutableIntStateOf(startTab) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(containerColor)
                .then(if (inSheet) Modifier else Modifier.statusBarsPadding())
    ) {
        Row(
            Modifier.fillMaxWidth()
                .height(if (inSheet) SeekerTheme.dimensions.dp56 else SeekerTheme.dimensions.dp64)
                .padding(horizontal = SeekerTheme.dimensions.dp8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connectionId == null) BackButton(onBack)
            Column(Modifier.weight(1f).padding(horizontal = SeekerTheme.dimensions.dp8)) {
                Text(
                    stringResource(R.string.inbox_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                connectionId
                    ?.let { labels[it] }
                    ?.let {
                        Text(
                            stringResource(R.string.inbox_from, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
            // The connection's own wallet network; an inbox of every connection has no one
            // network to show (SEE-174).
            state.walletFor(connectionId)?.let { NetworkChip(networkText(it.network)) }
            Box(
                Modifier.size(SeekerTheme.dimensions.dp48)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(
                        enabled = !state.refreshing,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onRefresh,
                    )
                    .testTag(InboxTags.REFRESH),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
            }
            if (connectionId != null) CloseButton(onBack, containerColor)
        }
        Row(Modifier.fillMaxWidth().height(SeekerTheme.dimensions.dp48)) {
            RequestTab(
                text =
                    stringResource(
                        R.string.inbox_tab_pending,
                        pending.size + inbox.toSend.size,
                    ),
                selected = selected == 0,
                onClick = { selected = 0 },
                containerColor = containerColor,
                modifier = Modifier.weight(1f),
            )
            RequestTab(
                text =
                    stringResource(
                        R.string.inbox_tab_answered,
                        inbox.answered.size + settledSignals.size,
                    ),
                selected = selected == 1,
                onClick = { selected = 1 },
                containerColor = containerColor,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            contentPadding =
                PaddingValues(
                    start = SeekerTheme.dimensions.dp16,
                    top = SeekerTheme.dimensions.dp12,
                    end = SeekerTheme.dimensions.dp16,
                    bottom = SeekerTheme.dimensions.dp24,
                ),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
            modifier = Modifier.weight(1f).testTag(InboxTags.LIST),
        ) {
            if (state.refreshing) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            items(shown.filter(::hasProblem), key = { "problem:${it.id}" }) {
                SeekerCard(
                    modifier =
                        Modifier.testTag(InboxTags.PROBLEM).semantics(mergeDescendants = true) {},
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        stringResource(R.string.inbox_problem, it.label, connectionStatusText(it)),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(SeekerTheme.dimensions.dp16),
                    )
                }
            }
            when {
                state.connections.isEmpty() ->
                    item { Message(R.string.inbox_no_connections, InboxTags.NO_CONNECTIONS) }
                inbox.isEmpty() && relevantSignals.isEmpty() ->
                    item { Message(R.string.inbox_empty, InboxTags.EMPTY) }
            }
            if (selected == 0) {
                section(R.string.inbox_section_to_send, InboxTags.SECTION_TO_SEND, inbox.toSend) {
                    ResultItem(it, labels[it.connectionId], onOpen)
                }
                if (pending.isNotEmpty()) {
                    item(key = InboxTags.SECTION_PENDING) {
                        Text(
                            stringResource(R.string.inbox_section_pending),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.padding(top = SeekerTheme.dimensions.dp4)
                                    .testTag(InboxTags.SECTION_PENDING),
                        )
                    }
                }
                items(
                    pending,
                    key = { "${it.namespace}/${it.connectionId}/${it.requestId}" },
                ) { item ->
                    when (item) {
                        is PendingItem.Private ->
                            RequestItem(
                                request = item.request,
                                source = labels[item.connectionId],
                                now = now,
                                assessment = state.assessments[item.request.key],
                                onOpen = onOpen,
                                onReject = onReject,
                            )
                        is PendingItem.Signal ->
                            SignalItem(
                                item.record,
                                labels[item.connectionId],
                                feedStanding(item.record),
                                onOpenSignal,
                            )
                    }
                }
            } else {
                section(
                    R.string.inbox_section_answered,
                    InboxTags.SECTION_ANSWERED,
                    inbox.answered,
                ) {
                    ResultItem(it, labels[it.connectionId], onOpen)
                }
                items(
                    settledSignals,
                    key = { "feed/${it.connectionId}/${it.key.proposalId}" },
                ) {
                    SignalItem(it, labels[it.connectionId], feedStanding(it), onOpenSignal)
                }
            }
        }
    }
}

@Composable
private fun SignalItem(
    record: ProposalRecord,
    source: String?,
    standing: ProposalStanding,
    onOpen: (ProposalRecord) -> Unit,
) {
    SeekerCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { onOpen(record) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.SwapHoriz,
                contentDescription = null,
                tint = SeekerTheme.colors.primaryText,
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp4),
            ) {
                Text(
                    stringResource(R.string.request_category_signal),
                    style = MaterialTheme.typography.labelSmall,
                    color = SeekerTheme.colors.primaryText,
                    modifier =
                        Modifier.background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(SeekerTheme.dimensions.dp8),
                            )
                            .padding(
                                horizontal = SeekerTheme.dimensions.dp8,
                                vertical = SeekerTheme.dimensions.dp3,
                            ),
                )
                Text(
                    record.proposal.commonEnvelope().presentation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.request_from_feed, source ?: record.key.serverId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(standingText(standing)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun RequestTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    Box(
        modifier
            .height(SeekerTheme.dimensions.dp48)
            .background(containerColor)
            .selectable(
                selected = selected,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (selected) SeekerTheme.colors.primaryText
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SeekerTheme.dimensions.dp3)
                .background(if (selected) SeekerTheme.colors.primaryText else containerColor)
        )
    }
}

private fun <T> LazyListScope.section(
    @StringRes title: Int,
    tag: String,
    entries: List<T>,
    content: @Composable (T) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = tag) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SeekerTheme.dimensions.dp4).testTag(tag),
        )
    }
    items(entries) { content(it) }
}

@Composable
private fun Message(@StringRes text: Int, tag: String) {
    SeekerCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(text),
            modifier = Modifier.padding(SeekerTheme.dimensions.dp18).testTag(tag),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RequestItem(
    request: ActionRequest,
    source: String?,
    now: Instant,
    assessment: RequestAssessment?,
    onOpen: (RequestKey) -> Unit,
    onReject: (RequestKey) -> Unit,
) {
    val action = actionText(request)
    val acknowledgement = request.action.kindCase == Action.KindCase.ACK
    val signature = request.action.kindCase == Action.KindCase.SIGN_MESSAGE
    val summary =
        when {
            acknowledgement ->
                stringResource(
                    R.string.request_card_ack_summary,
                    source ?: request.ref.connectionId,
                    request.text() ?: action,
                )
            signature ->
                stringResource(
                    R.string.request_card_sign_summary,
                    source ?: request.ref.connectionId,
                    messagePreview(request)?.bytes ?: 0,
                )
            request.transfer() != null ->
                stringResource(
                    R.string.request_card_transfer_summary,
                    source ?: request.ref.connectionId,
                    relativeTime(request.expiresAt.instant(), now),
                )
            else ->
                stringResource(
                    R.string.request_card_other_summary,
                    source ?: request.ref.connectionId,
                    relativeTime(request.expiresAt.instant(), now),
                )
        }
    SeekerCard(
        modifier = Modifier.fillMaxWidth().testTag(InboxTags.item(request.key)),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when {
                            acknowledgement -> Icons.Rounded.DoneAll
                            signature -> Icons.Rounded.Draw
                            else -> Icons.Rounded.NorthEast
                        },
                        contentDescription = null,
                        tint = SeekerTheme.colors.primaryText,
                        modifier = Modifier.size(SeekerTheme.dimensions.dp22),
                    )
                    Text(
                        action,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val allowed = assessment?.decision?.allowed == true
                val warningCount =
                    assessment?.decision?.takeIf { it.warns }?.reasons?.size?.coerceAtLeast(1) ?: 0
                Text(
                    when {
                        warningCount > 0 ->
                            pluralStringResource(
                                R.plurals.request_warning_count,
                                warningCount,
                                warningCount,
                            )
                        allowed -> stringResource(R.string.request_in_rules)
                        else -> stringResource(R.string.request_not_checked)
                    },
                    color =
                        when {
                            warningCount > 0 -> MaterialTheme.colorScheme.onTertiaryContainer
                            allowed -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    style = MaterialTheme.typography.labelSmall,
                    modifier =
                        Modifier.background(
                                when {
                                    warningCount > 0 -> MaterialTheme.colorScheme.tertiaryContainer
                                    allowed -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                RoundedCornerShape(SeekerTheme.dimensions.dp8),
                            )
                            .padding(
                                horizontal = SeekerTheme.dimensions.dp10,
                                vertical = SeekerTheme.dimensions.dp5,
                            ),
                )
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
                SeekerButton(
                    text = stringResource(R.string.review),
                    onClick = { onOpen(request.key) },
                    role = SeekerButtonRole.Tonal,
                    modifier = Modifier.weight(1f).height(SeekerTheme.dimensions.dp40),
                )
                // This is a shortcut into the mandatory review, never an answer from the list.
                // Transfers still have to be decoded here and signatures still go to the wallet.
                SeekerButton(
                    text =
                        stringResource(
                            if (request.action.kindCase == Action.KindCase.ACK) {
                                R.string.acknowledge
                            } else {
                                R.string.review_approve
                            }
                        ),
                    onClick = { onOpen(request.key) },
                    modifier = Modifier.weight(1f).height(SeekerTheme.dimensions.dp40),
                    automationTag = InboxTags.QUICK_APPROVE,
                )
                Box(
                    Modifier.size(SeekerTheme.dimensions.dp40)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { onReject(request.key) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.reject),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultItem(result: LocalResult, source: String?, onOpen: (RequestKey) -> Unit) {
    SeekerCard(
        modifier = Modifier.fillMaxWidth().testTag(InboxTags.item(result.key)),
        onClick = { onOpen(result.key) },
    ) {
        Column(
            Modifier.padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp5),
        ) {
            Text(source ?: result.connectionId, style = MaterialTheme.typography.labelMedium)
            Text(
                result.request.text() ?: actionText(result.request),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                resultSummary(result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How many requests wait for the owner, and how many answers wait to be sent. */
fun inboxCounts(state: InboxUiState): Pair<Int, Int> {
    val items = inboxItems(state.inbox, null)
    return items.pending.size to items.toSend.size
}

/** The problem connections, for tests and summaries. */
fun problemConnections(connections: List<Connection>) = connections.filter(::hasProblem)
