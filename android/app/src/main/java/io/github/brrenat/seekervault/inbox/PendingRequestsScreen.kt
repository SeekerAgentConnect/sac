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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerTheme
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.hasProblem
import io.github.brrenat.seekervault.connections.statusText as connectionStatusText
import io.github.brrenat.seekervault.request.v1.ActionRequest
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
    warningRequests: Set<RequestKey> = emptySet(),
    onReject: (RequestKey) -> Unit = {},
    inSheet: Boolean = false,
) {
    val labels = state.connections.associate { it.id to it.label }
    val shown = state.connections.filter { connectionId == null || it.id == connectionId }
    val inbox = inboxItems(state.inbox, connectionId)
    val startTab =
        if (inbox.pending.isEmpty() && inbox.toSend.isEmpty() && inbox.answered.isNotEmpty()) 1
        else 0
    var selected by rememberSaveable(startTab) { mutableIntStateOf(startTab) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .then(if (inSheet) Modifier else Modifier.statusBarsPadding())
    ) {
        Row(
            Modifier.fillMaxWidth()
                .height(if (inSheet) 56.dp else 64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connectionId == null) BackButton(onBack)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
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
            state.wallet?.let { NetworkChip(networkText(it.network)) }
            Box(
                Modifier.size(48.dp)
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
            if (connectionId != null) CloseButton(onBack)
        }
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            RequestTab(
                text =
                    stringResource(
                        R.string.inbox_tab_pending,
                        inbox.pending.size + inbox.toSend.size,
                    ),
                selected = selected == 0,
                onClick = { selected = 0 },
                modifier = Modifier.weight(1f),
            )
            RequestTab(
                text = stringResource(R.string.inbox_tab_answered, inbox.answered.size),
                selected = selected == 1,
                onClick = { selected = 1 },
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            when {
                state.connections.isEmpty() ->
                    item { Message(R.string.inbox_no_connections, InboxTags.NO_CONNECTIONS) }
                inbox.isEmpty() -> item { Message(R.string.inbox_empty, InboxTags.EMPTY) }
            }
            if (selected == 0) {
                section(R.string.inbox_section_to_send, InboxTags.SECTION_TO_SEND, inbox.toSend) {
                    ResultItem(it, labels[it.connectionId], onOpen)
                }
                section(R.string.inbox_section_pending, InboxTags.SECTION_PENDING, inbox.pending) {
                    RequestItem(
                        request = it,
                        source = labels[it.ref.connectionId],
                        now = now,
                        warning = it.key in warningRequests,
                        onOpen = onOpen,
                        onReject = onReject,
                    )
                }
            } else {
                section(
                    R.string.inbox_section_answered,
                    InboxTags.SECTION_ANSWERED,
                    inbox.answered,
                ) {
                    ResultItem(it, labels[it.connectionId], onOpen)
                }
            }
        }
    }
}

@Composable
private fun RequestTab(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
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
                .height(3.dp)
                .background(
                    if (selected) SeekerTheme.colors.primaryText
                    else MaterialTheme.colorScheme.surface
                )
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
            modifier = Modifier.padding(top = 4.dp).testTag(tag),
        )
    }
    items(entries) { content(it) }
}

@Composable
private fun Message(@StringRes text: Int, tag: String) {
    SeekerCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(text),
            modifier = Modifier.padding(18.dp).testTag(tag),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RequestItem(
    request: ActionRequest,
    source: String?,
    now: Instant,
    warning: Boolean,
    onOpen: (RequestKey) -> Unit,
    onReject: (RequestKey) -> Unit,
) {
    val action = actionText(request)
    SeekerCard(
        modifier = Modifier.fillMaxWidth().testTag(InboxTags.item(request.key)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { onOpen(request.key) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        action,
                        style = MaterialTheme.typography.labelMedium,
                        color = SeekerTheme.colors.primaryText,
                    )
                    Text(
                        source ?: request.ref.connectionId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (warning) {
                    Text(
                        stringResource(R.string.request_warning_badge),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                        modifier =
                            Modifier.background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                request.text() ?: action,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.inbox_request_summary,
                    action,
                    relativeTime(request.createdAt.instant(), now),
                    shortTime(request.expiresAt.instant()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeekerButton(
                    text = stringResource(R.string.review),
                    onClick = { onOpen(request.key) },
                    role = SeekerButtonRole.Tonal,
                    modifier = Modifier.weight(1f).height(40.dp),
                )
                Box(
                    Modifier.size(40.dp)
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
