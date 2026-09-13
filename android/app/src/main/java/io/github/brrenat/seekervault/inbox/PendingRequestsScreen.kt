package io.github.brrenat.seekervault.inbox

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.hasProblem
import io.github.brrenat.seekervault.connections.statusText as connectionStatusText
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.ui.Gesture
import io.github.brrenat.seekervault.ui.Glass
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassListScreen
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.IconChip
import io.github.brrenat.seekervault.ui.Motion
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space
import io.github.brrenat.seekervault.ui.TabBar
import io.github.brrenat.seekervault.ui.TextLink
import io.github.brrenat.seekervault.ui.glass
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Requests (SEE-57): everything an agent has asked for, from every connection or from
 * [connectionId] only, and the answers already given.
 *
 * A waiting request is a swipe row. Which answers a swipe can give is deliberately not the same in
 * both directions, and that asymmetry is the point:
 *
 * - **Left rejects, always.** Saying no is the safe answer to every kind of request, it needs
 *   nothing read and nothing prepared, and it reaches no wallet.
 * - **Right acknowledges an acknowledgement, and opens everything else.** Nothing that reaches a
 *   wallet is ever answered from a list. A transfer's transaction has not been read at this point —
 *   there isn't one yet — and a message's bytes are not on screen here, so the swipe takes the
 *   owner to where both are, which is the review. The wallet is never skipped, and neither is
 *   reading what would be signed (docs/security.md#inspecting-a-transfer).
 */
@Composable
fun PendingRequestsScreen(
    state: InboxUiState,
    connectionId: String?,
    now: Instant,
    onOpen: (RequestKey) -> Unit,
    onRefresh: () -> Unit,
    onAnswer: (RequestKey, Answer) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    tabs: TabBar? = null,
) {
    val labels = state.connections.associate { it.id to it.label }
    val shown = state.connections.filter { connectionId == null || it.id == connectionId }
    val items = inboxItems(state.inbox, connectionId)
    GlassListScreen(
        title = stringResource(R.string.requests_title),
        subtitle =
            connectionId?.let { labels[it] }?.let { stringResource(R.string.inbox_from, it) },
        onBack = onBack,
        headerAction = {
            TextLink(
                stringResource(R.string.refresh),
                onRefresh,
                Modifier.testTag(InboxTags.REFRESH),
                enabled = !state.refreshing,
            )
        },
        tabs = tabs,
        modifier = modifier,
        listTag = InboxTags.LIST,
    ) {
        // A connection that couldn't be fetched says so; its last list stays below.
        items(shown.filter(::hasProblem), key = { "problem:${it.id}" }) {
            GlassCard {
                Text(
                    stringResource(R.string.inbox_problem, it.label, connectionStatusText(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Danger,
                    modifier = Modifier.testTag(InboxTags.PROBLEM),
                )
            }
        }
        when {
            state.connections.isEmpty() ->
                item { Message(R.string.inbox_no_connections, InboxTags.NO_CONNECTIONS) }
            items.isEmpty() -> item { Message(R.string.inbox_empty, InboxTags.EMPTY) }
        }
        section(R.string.requests_to_send, InboxTags.SECTION_TO_SEND, items.toSend) {
            AnsweredRow(it, labels[it.connectionId], onOpen)
        }
        section(R.string.requests_waiting_swipe, InboxTags.SECTION_PENDING, items.pending) {
            SwipeRow(
                request = it,
                source = labels[it.ref.connectionId],
                assessment = state.assessments[it.key],
                answering = it.key in state.sending,
                onOpen = { onOpen(it.key) },
                onReject = { onAnswer(it.key, Answer.Reject) },
                onAcknowledge = { onAnswer(it.key, Answer.Acknowledge) },
            )
        }
        section(R.string.requests_answered, InboxTags.SECTION_ANSWERED, items.answered) {
            AnsweredRow(it, labels[it.connectionId], onOpen)
        }
    }
}

private fun <T> LazyListScope.section(
    title: Int,
    tag: String,
    entries: List<T>,
    content: @Composable (T) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = tag) {
        SectionLabel(stringResource(title), Modifier.padding(top = Space.Sm).testTag(tag))
    }
    items(entries) { content(it) }
}

@Composable
private fun Message(text: Int, tag: String) {
    GlassCard {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral400,
            modifier = Modifier.testTag(tag),
        )
    }
}

/**
 * One waiting request, and the two answers a finger can reach.
 *
 * The cues behind the row fade in with the drag rather than appearing at the threshold, so the
 * owner sees what the swipe would do while there is still time not to do it. Under the threshold
 * the row springs back and nothing is answered.
 */
@Composable
private fun SwipeRow(
    request: ActionRequest,
    source: String?,
    assessment: RequestAssessment?,
    answering: Boolean,
    onOpen: () -> Unit,
    onReject: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val kind = kindOf(request)
    val density = LocalDensity.current
    val threshold = with(density) { Gesture.RowThreshold.toPx() }
    val travel = with(density) { Gesture.RowCueTravel.toPx() }
    val offset = remember(request.key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // The right swipe answers only what can be answered without reading anything more.
    val answersOutright = kind == RequestKind.Acknowledge
    val rejectLabel = stringResource(R.string.swipe_reject)
    val approveLabel =
        stringResource(
            if (answersOutright) R.string.swipe_acknowledge else R.string.swipe_read_first
        )
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Row))
            .background(Nocturne.text(0.09f))
    ) {
        val progress = (abs(offset.value) / travel).coerceAtMost(1f)
        Row(
            Modifier.matchParentSize().padding(horizontal = Space.Inset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    if (answersOutright) R.string.swipe_acknowledge else R.string.swipe_read_first
                ),
                style = MaterialTheme.typography.titleSmall,
                color = Nocturne.Accent100,
                modifier =
                    Modifier.weight(1f)
                        .alpha(if (offset.value > 0f) progress else 0f)
                        .clearAndSetSemantics {},
            )
            Text(
                stringResource(R.string.swipe_reject),
                style = MaterialTheme.typography.titleSmall,
                color = Nocturne.Neutral200,
                modifier =
                    Modifier.alpha(if (offset.value < 0f) progress else 0f).clearAndSetSemantics {},
            )
        }
        Row(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(offset.value.toInt(), 0) }
                .fillMaxWidth()
                .glass(Glass.row(), RoundedCornerShape(Radius.Row))
                .clickable(enabled = !answering, onClick = onOpen)
                // The design's own slop: four pixels of sideways travel is a swipe, and from
                // there the row takes the gesture over. Consuming it is what cancels the tap, so
                // a swipe never also opens the request; under four pixels nothing is consumed and
                // the list keeps its own scrolling.
                .pointerInput(request.key, answering) {
                    if (answering) return@pointerInput
                    val slop = Gesture.TapSlop.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var travelled = 0f
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!dragging && change.isConsumed) break
                            if (!change.pressed) {
                                if (dragging) {
                                    val settled = offset.value
                                    scope.launch { offset.animateTo(0f, Motion.springBack()) }
                                    when {
                                        settled >= threshold ->
                                            if (answersOutright) onAcknowledge() else onOpen()
                                        settled <= -threshold -> onReject()
                                    }
                                    change.consume()
                                }
                                break
                            }
                            val delta = change.positionChange().x
                            travelled += abs(delta)
                            if (!dragging && travelled > slop) dragging = true
                            if (dragging) {
                                change.consume()
                                scope.launch { offset.snapTo(offset.value + delta) }
                            }
                        }
                    }
                }
                .testTag(InboxTags.item(request.key))
                // A swipe is not the only way to give the two answers it gives.
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(rejectLabel) {
                                onReject()
                                true
                            },
                            CustomAccessibilityAction(approveLabel) {
                                if (answersOutright) onAcknowledge() else onOpen()
                                true
                            },
                        )
                }
                .padding(horizontal = Space.Inset, vertical = Space.Md),
            horizontalArrangement = Arrangement.spacedBy(Space.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(kindIcon(kind), contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    rowTitleOf(request),
                    style = MaterialTheme.typography.titleMedium,
                    color = Nocturne.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.carousel_source,
                        source ?: request.ref.connectionId,
                        stakeText(kind),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RulePill(assessment)
            }
            Icon(
                Glyph.Forward,
                contentDescription = null,
                tint = Nocturne.Neutral500,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The one line that names a waiting request in a list. */
@Composable
private fun rowTitleOf(request: ActionRequest): String =
    when (kindOf(request)) {
        RequestKind.Acknowledge -> request.text() ?: actionText(request)
        RequestKind.Transfer -> headlineOf(request)
        else -> actionText(request)
    }

/** A request that has been answered: what it was, and what became of the answer. */
@Composable
private fun AnsweredRow(result: LocalResult, source: String?, onOpen: (RequestKey) -> Unit) {
    GlassCard(padding = Space.Sm, spacing = 0.dp) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(Radius.InnerTight))
                .clickable { onOpen(result.key) }
                .padding(Space.Sm)
                .testTag(InboxTags.item(result.key))
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Space.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(kindIcon(kindOf(result.request)), contentDescription = null, accent = false)
            Column(Modifier.weight(1f)) {
                Text(
                    result.request.text() ?: actionText(result.request),
                    style = MaterialTheme.typography.titleMedium,
                    color = Nocturne.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    resultSummary(result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral500,
                )
                if (source != null) {
                    Text(
                        source,
                        style = MaterialTheme.typography.bodySmall,
                        color = Nocturne.Neutral600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
