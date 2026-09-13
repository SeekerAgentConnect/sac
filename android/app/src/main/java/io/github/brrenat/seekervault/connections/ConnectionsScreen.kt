package io.github.brrenat.seekervault.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.inbox.InboxUiState
import io.github.brrenat.seekervault.inbox.RulePill
import io.github.brrenat.seekervault.inbox.detailOf
import io.github.brrenat.seekervault.inbox.headlineOf
import io.github.brrenat.seekervault.inbox.inboxItems
import io.github.brrenat.seekervault.inbox.key
import io.github.brrenat.seekervault.inbox.kindIcon
import io.github.brrenat.seekervault.inbox.kindOf
import io.github.brrenat.seekervault.inbox.stakeText
import io.github.brrenat.seekervault.inbox.stakeWeight
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.ui.CardDivider
import io.github.brrenat.seekervault.ui.Glass
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassScreen
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.IconChip
import io.github.brrenat.seekervault.ui.InitialsChip
import io.github.brrenat.seekervault.ui.MessageOverlay
import io.github.brrenat.seekervault.ui.MonoText
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.PillButton
import io.github.brrenat.seekervault.ui.PillTone
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space
import io.github.brrenat.seekervault.ui.TabBar
import io.github.brrenat.seekervault.ui.TextLink
import io.github.brrenat.seekervault.ui.dashedBorder
import io.github.brrenat.seekervault.ui.glass
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.networkText

/** How many requests wait for the owner, and how many answers wait to be sent. */
data class InboxSummary(val waitingForYou: Int, val toSend: Int)

/**
 * Home (SEE-57): what is waiting, and which servers are paired.
 *
 * Two things in one screen, in the order they matter. The carousel is what is waiting — it browses
 * and nothing else, because an answer that reaches a wallet is given in the review where the
 * transaction has been read. Under it are the servers this phone is paired with, and the way to
 * pair another.
 */
@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    inboxState: InboxUiState,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onLiveTest: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    inbox: InboxSummary? = null,
    onInbox: () -> Unit = {},
    onOpenRequest: (RequestKey) -> Unit = {},
    wallet: SelectedWallet? = null,
    onWallet: () -> Unit = {},
    onRefreshConnection: (String) -> Unit = {},
    tabs: TabBar? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, onMessageShown)
    val waiting = inboxItems(inboxState.inbox, null).pending
    GlassScreen(
        title = stringResource(R.string.home_title),
        subtitle = stringResource(R.string.home_subtitle),
        tag = wallet?.let { networkText(it.network) },
        headerAction = {
            TextLink(
                stringResource(R.string.live_test),
                onLiveTest,
                Modifier.testTag(ConnectionsTags.LIVE_TEST),
            )
        },
        tabs = tabs,
        modifier = modifier,
        overlay = { MessageOverlay(snackbar, Modifier.align(Alignment.BottomCenter)) },
    ) {
        // The wallet card comes first and only when there is no wallet: it is the one thing that
        // has to happen before an agent can ask for anything that needs one.
        if (wallet == null) WalletCard(onWallet)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.home_waiting), Modifier.weight(1f))
            if (waiting.isNotEmpty()) {
                TextLink(
                    stringResource(R.string.home_see_all, waiting.size),
                    onInbox,
                    Modifier.testTag(io.github.brrenat.seekervault.inbox.InboxTags.SEE_ALL),
                )
            }
        }
        if (waiting.isEmpty()) {
            GlassCard {
                Text(
                    stringResource(R.string.home_nothing_waiting),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Nocturne.Text,
                )
                Text(
                    stringResource(R.string.home_nothing_waiting_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral500,
                )
            }
        } else {
            RequestCarousel(waiting, inboxState, onOpenRequest)
        }
        SectionLabel(stringResource(R.string.home_servers))
        if (state.loaded && state.connections.isEmpty()) {
            GlassCard {
                Text(
                    stringResource(R.string.connections_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral400,
                    modifier = Modifier.testTag(ConnectionsTags.EMPTY),
                )
            }
        } else if (state.connections.isNotEmpty()) {
            GlassCard(padding = Space.Sm, spacing = 0.dp) {
                state.connections.forEachIndexed { index, connection ->
                    if (index > 0) CardDivider()
                    ServerRow(
                        connection,
                        onOpen = { onOpen(connection.id) },
                        onRefresh = {
                            onRefreshConnection(connection.id)
                        },
                    )
                }
            }
        }
        PillButton(
            stringResource(R.string.add_connection),
            onAdd,
            tone = PillTone.Accent,
            icon = Glyph.Add,
            modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.ADD),
        )
        // The inbox summary keeps its own row for the tests and for a reader who wants the count
        // in words rather than as a dot on a tab.
        if (inbox != null && state.connections.isNotEmpty()) {
            Text(
                when {
                    inbox.toSend > 0 ->
                        stringResource(
                            R.string.inbox_row_waiting_and_to_send,
                            inbox.waitingForYou,
                            inbox.toSend,
                        )
                    inbox.waitingForYou > 0 ->
                        stringResource(R.string.inbox_row_waiting, inbox.waitingForYou)
                    else -> stringResource(R.string.inbox_row_nothing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral500,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(onClick = onInbox)
                        .testTag(ConnectionsTags.INBOX),
            )
        }
    }
}

/**
 * The card that stands in for a wallet until there is one: dashed, because it is an empty place.
 */
@Composable
private fun WalletCard(onWallet: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .height(186.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Nocturne.text(0.05f))
            .dashedBorder(Nocturne.text(0.22f), 26.dp)
            .clickable(onClick = onWallet)
            .padding(Space.Xl)
            .testTag(ConnectionsTags.WALLET),
        verticalArrangement = Arrangement.spacedBy(Space.Md, Alignment.CenterVertically),
    ) {
        IconChip(Glyph.Wallet, contentDescription = null, size = 40.dp)
        Text(
            stringResource(R.string.home_wallet_card_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Nocturne.Text,
        )
        Text(
            stringResource(R.string.home_wallet_card_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral500,
        )
    }
}

/**
 * The carousel: one tile per waiting request, centre-snapped, dragged through with a finger.
 *
 * It browses. Dragging moves between requests and tapping opens the review; nothing on it answers
 * anything, which is why the design gives it no controls at all.
 */
@Composable
private fun RequestCarousel(
    waiting: List<ActionRequest>,
    inboxState: InboxUiState,
    onOpen: (RequestKey) -> Unit,
) {
    val listState = rememberLazyListState()
    val centred = listState.firstVisibleItemIndex
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        horizontalArrangement = Arrangement.spacedBy(Space.Gap),
        contentPadding = PaddingValues(end = Space.Edge),
        modifier =
            Modifier.fillMaxWidth().testTag(io.github.brrenat.seekervault.inbox.InboxTags.CAROUSEL),
    ) {
        itemsIndexed(
            items = waiting,
            key = { _, request -> "${request.ref.connectionId}/${request.ref.requestId}" },
        ) { index, request ->
            RequestTile(
                request = request,
                active = index == centred,
                assessment = inboxState.assessments[request.key],
                source = inboxState.connections.firstOrNull { it.id == request.ref.connectionId },
                onOpen = { onOpen(request.key) },
            )
        }
    }
}

@Composable
private fun RequestTile(
    request: ActionRequest,
    active: Boolean,
    assessment: io.github.brrenat.seekervault.inbox.RequestAssessment?,
    source: Connection?,
    onOpen: () -> Unit,
) {
    val kind = kindOf(request)
    val weight = stakeWeight(kind)
    Column(
        Modifier.width(214.dp)
            .height(198.dp)
            .scale(if (active) 1f else 0.945f)
            .glass(
                if (active) Glass.tileActive() else Glass.tileIdle(),
                RoundedCornerShape(Radius.Tile),
            )
            // The accent ring and the wash behind an active tile carry how much is at stake: a
            // transfer is lit more than a signature, and a signature more than an acknowledgement.
            .then(
                if (active)
                    Modifier.border(
                            1.dp,
                            Nocturne.accent(weight),
                            RoundedCornerShape(Radius.Tile),
                        )
                        .background(Nocturne.accent(0.04f + 0.08f * weight))
                else Modifier
            )
            .clickable(onClick = onOpen)
            .padding(Space.Inset)
            .testTag(io.github.brrenat.seekervault.inbox.InboxTags.tile(request.key)),
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                kindIcon(kind),
                contentDescription = null,
                tint = if (active) Nocturne.Accent200 else Nocturne.Neutral400,
                modifier = Modifier.size(19.dp),
            )
            Text(
                io.github.brrenat.seekervault.inbox.actionText(request),
                style = MaterialTheme.typography.titleSmall,
                color = if (active) Nocturne.Neutral200 else Nocturne.Neutral400,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            headlineOf(request),
            style = MaterialTheme.typography.headlineLarge,
            color = if (active) Nocturne.Text else Nocturne.Neutral400,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MonoText(
            detailOf(request),
            color = if (active) Nocturne.Neutral400 else Nocturne.Neutral600,
            maxLines = 1,
        )
        Box(Modifier.weight(1f)) { RulePill(assessment, Modifier.align(Alignment.BottomStart)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.carousel_source,
                    source?.label ?: request.ref.connectionId,
                    stakeText(kind),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Glyph.Forward,
                contentDescription = null,
                tint = Nocturne.Neutral500,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** One paired server: what it is called, where it stands, and the way into it. */
@Composable
private fun ServerRow(connection: Connection, onOpen: () -> Unit, onRefresh: () -> Unit) {
    val problem = hasProblem(connection)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.InnerTight))
            .clickable(onClick = onOpen)
            .padding(Space.Sm)
            .testTag(ConnectionsTags.item(connection.id))
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsChip(connection.label)
        Column(Modifier.weight(1f)) {
            Text(
                connection.label,
                style = MaterialTheme.typography.titleMedium,
                color = Nocturne.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusText(connection),
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem) Nocturne.Danger else Nocturne.Neutral500,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasProblem(connection) && connection.usable) {
            Box(
                Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(Nocturne.text(0.07f))
                    .clickable(onClick = onRefresh)
                    .testTag(ConnectionsTags.REFRESH),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Glyph.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                    tint = Nocturne.Neutral300,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Icon(
                Glyph.Forward,
                contentDescription = null,
                tint = Nocturne.Neutral500,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
