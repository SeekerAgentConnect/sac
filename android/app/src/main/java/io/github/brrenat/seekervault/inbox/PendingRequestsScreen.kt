package io.github.brrenat.seekervault.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.hasProblem
import io.github.brrenat.seekervault.connections.statusText as connectionStatusText
import io.github.brrenat.seekervault.request.v1.ActionRequest
import java.time.Instant

/**
 * Pending requests: what agents asked, from every connection or from [connectionId] only, with the
 * owner's answers that are waiting to be sent or already settled. Stock Material 3 only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingRequestsScreen(
    state: InboxUiState,
    connectionId: String?,
    now: Instant,
    onOpen: (RequestKey) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = state.connections.associate { it.id to it.label }
    val shown = state.connections.filter { connectionId == null || it.id == connectionId }
    val items = inboxItems(state.inbox, connectionId)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.inbox_title))
                        connectionId
                            ?.let { labels[it] }
                            ?.let {
                                Text(
                                    stringResource(R.string.inbox_from, it),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !state.refreshing,
                        modifier = Modifier.testTag(InboxTags.REFRESH),
                    ) {
                        Text(stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(contentPadding = innerPadding, modifier = Modifier.testTag(InboxTags.LIST)) {
            if (state.refreshing) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            // A connection that couldn't be fetched says so; its last list stays below.
            items(shown.filter(::hasProblem), key = { "problem:${it.id}" }) {
                Text(
                    stringResource(R.string.inbox_problem, it.label, connectionStatusText(it)),
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag(InboxTags.PROBLEM),
                )
            }
            when {
                state.connections.isEmpty() ->
                    item { Message(R.string.inbox_no_connections, InboxTags.NO_CONNECTIONS) }
                items.isEmpty() -> item { Message(R.string.inbox_empty, InboxTags.EMPTY) }
            }
            section(R.string.inbox_section_to_send, InboxTags.SECTION_TO_SEND, items.toSend) {
                ResultItem(it, labels[it.connectionId], onOpen)
            }
            section(R.string.inbox_section_pending, InboxTags.SECTION_PENDING, items.pending) {
                RequestItem(it, labels[it.ref.connectionId], now, onOpen)
            }
            section(R.string.inbox_section_answered, InboxTags.SECTION_ANSWERED, items.answered) {
                ResultItem(it, labels[it.connectionId], onOpen)
            }
        }
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
            modifier = Modifier.padding(16.dp).testTag(tag),
        )
    }
    items(entries) {
        content(it)
        HorizontalDivider()
    }
}

@Composable
private fun Message(@StringRes text: Int, tag: String) {
    Text(stringResource(text), modifier = Modifier.padding(16.dp).testTag(tag))
}

@Composable
private fun RequestItem(
    request: ActionRequest,
    source: String?,
    now: Instant,
    onOpen: (RequestKey) -> Unit,
) {
    val action = actionText(request)
    ListItem(
        overlineContent = { Text(source ?: request.ref.connectionId) },
        headlineContent = {
            Text(request.text() ?: action, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                stringResource(
                    R.string.inbox_request_summary,
                    action,
                    relativeTime(request.createdAt.instant(), now),
                    shortTime(request.expiresAt.instant()),
                )
            )
        },
        modifier = Modifier.clickable { onOpen(request.key) }.testTag(InboxTags.item(request.key)),
    )
}

@Composable
private fun ResultItem(result: LocalResult, source: String?, onOpen: (RequestKey) -> Unit) {
    ListItem(
        overlineContent = { Text(source ?: result.connectionId) },
        headlineContent = {
            Text(
                result.request.text() ?: actionText(result.request),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(resultSummary(result)) },
        modifier = Modifier.clickable { onOpen(result.key) }.testTag(InboxTags.item(result.key)),
    )
}

/** How many requests wait for the owner, and how many answers wait to be sent. */
fun inboxCounts(state: InboxUiState): Pair<Int, Int> {
    val items = inboxItems(state.inbox, null)
    return items.pending.size to items.toSend.size
}

/** The problem connections, for tests and summaries. */
fun problemConnections(connections: List<Connection>) = connections.filter(::hasProblem)
