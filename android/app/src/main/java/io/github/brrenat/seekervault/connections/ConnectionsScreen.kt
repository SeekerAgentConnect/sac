package io.github.brrenat.seekervault.connections

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerTheme
import io.github.brrenat.seekervault.inbox.actionText
import io.github.brrenat.seekervault.inbox.key
import io.github.brrenat.seekervault.inbox.messagePreview
import io.github.brrenat.seekervault.inbox.text
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.transactions.mint
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.ui.NetworkChip
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.SeekerSnackbarHost
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.networkText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many requests wait for the owner, and how many answers wait to be sent. */
data class InboxSummary(val waitingForYou: Int, val toSend: Int)

/** The v4 Home dashboard. Every action still delegates to the existing feature owner. */
@Composable
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
    warningRequests: Set<RequestKey> = emptySet(),
    onOpenRequest: (RequestKey) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val compact = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48
    MessageEffect(state.message, snackbar, onMessageShown)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 68.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag(ConnectionsTags.LIST),
        ) {
            item(key = "wallet") { WalletCard(wallet, onWallet) }
            if (inbox != null || requests.isNotEmpty()) {
                item(key = "requests") {
                    RequestCarousel(
                        requests = requests,
                        connections = state.connections,
                        warningRequests = warningRequests,
                        onOpen = onOpenRequest,
                        inbox = inbox,
                        onInbox = onInbox,
                    )
                }
            }
            item(key = "rules-heading") { SectionHeading(stringResource(R.string.rules_heading)) }
            item(key = "global-rules") { GlobalRulesItem(onGlobalRules) }
            item(key = "servers-heading") {
                SectionHeading(stringResource(R.string.paired_servers_heading))
            }
            itemsIndexed(state.connections, key = { _, connection -> connection.id }) {
                _,
                connection ->
                ConnectionItem(connection, onClick = { onOpen(connection.id) })
            }
            if (state.loaded && state.connections.isEmpty()) {
                item(key = "empty") {
                    SeekerCard(Modifier.padding(horizontal = 16.dp), radius = 16.dp) {
                        Text(
                            stringResource(R.string.connections_empty),
                            modifier = Modifier.padding(16.dp).testTag(ConnectionsTags.EMPTY),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Compatibility for the old direct component entry point. The app uses the bottom nav.
            if (activity != null) {
                item(key = "activity") { ActivityItem(activity, onActivity) }
            }
            item(key = "add") { AddConnectionAction(onAdd) }
        }
        HomeAppBar(
            wallet = wallet,
            compact = compact,
            onLiveTest = onLiveTest,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        SeekerSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun HomeAppBar(
    wallet: SelectedWallet?,
    compact: Boolean,
    onLiveTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (compact && wallet != null) walletSlug(wallet)
            else stringResource(R.string.home_title),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (compact) {
            NetworkChip(
                wallet?.let { networkText(it.network) } ?: stringResource(R.string.network_none)
            )
            Spacer(Modifier.width(4.dp))
        }
        Box(
            Modifier.size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onLiveTest,
                )
                .testTag(ConnectionsTags.LIVE_TEST),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.live_title))
        }
    }
}

private fun walletSlug(wallet: SelectedWallet): String =
    wallet.label?.takeIf { it.isNotBlank() }
        ?: if (wallet.address.length <= 12) wallet.address
        else "${wallet.address.take(5)}…${wallet.address.takeLast(4)}"

private fun shortAddress(address: String): String =
    if (address.length <= 16) address else "${address.take(9)}…${address.takeLast(7)}"

@Composable
private fun WalletCard(wallet: SelectedWallet?, onClick: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(wallet?.address) { mutableStateOf(false) }
    SeekerCard(
        modifier = Modifier.padding(horizontal = 16.dp).testTag(ConnectionsTags.WALLET),
        color = MaterialTheme.colorScheme.primaryContainer,
        radius = 16.dp,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AccountBalanceWallet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    wallet?.let(::walletSlug) ?: stringResource(R.string.wallet_row),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        copied -> stringResource(R.string.copied)
                        wallet == null -> stringResource(R.string.wallet_row_none)
                        else -> shortAddress(wallet.address)
                    },
                    style =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (wallet == null) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Box(
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "Wallet address",
                                                wallet.address,
                                            )
                                        )
                                    )
                                    copied = true
                                    delay(1_600)
                                    copied = false
                                }
                            },
                        )
                        .testTag(ConnectionsTags.WALLET_COPY),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.copy_wallet_address),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestCarousel(
    requests: List<ActionRequest>,
    connections: List<Connection>,
    warningRequests: Set<RequestKey>,
    onOpen: (RequestKey) -> Unit,
    inbox: InboxSummary?,
    onInbox: () -> Unit,
) {
    val waiting = inbox?.waitingForYou ?: requests.size
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.waiting_for_you),
                style = MaterialTheme.typography.labelLarge,
                color = SeekerTheme.colors.primaryText,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onInbox,
                    )
                    .padding(horizontal = 12.dp)
                    .testTag(ConnectionsTags.INBOX),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.requests_see_all, waiting),
                    style = MaterialTheme.typography.labelMedium,
                    color = SeekerTheme.colors.primaryText,
                )
            }
        }
        if (requests.isNotEmpty()) {
            val carouselState = rememberLazyListState()
            LazyRow(
                state = carouselState,
                flingBehavior = rememberSnapFlingBehavior(carouselState),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    requests,
                    key = { _, request ->
                        "${request.ref.connectionId}/${request.ref.requestId}"
                    },
                ) { index, request ->
                    val source = connections.firstOrNull { it.id == request.ref.connectionId }
                    RequestTile(
                        request = request,
                        source = source,
                        warning = request.key in warningRequests,
                        active = index == carouselState.firstVisibleItemIndex,
                        onOpen = { onOpen(request.key) },
                    )
                }
            }
            Text(
                stringResource(R.string.carousel_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SeekerCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), radius = 16.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.requests_none),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.requests_none_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class RequestTileCopy(
    val icon: ImageVector,
    val kind: String,
    val headline: String,
    val detail: String,
    val consequence: String,
)

@Composable
private fun requestTileCopy(request: ActionRequest, source: Connection?): RequestTileCopy {
    val sourceLabel = source?.label ?: request.ref.connectionId
    return when (request.action.kindCase) {
        Action.KindCase.TRANSFER -> {
            val transfer = requireNotNull(request.transfer())
            val amount =
                if (transfer.mint() == null) {
                    transfer.amount.toULongOrNull()?.let {
                        "${formatBaseUnits(it, LAMPORT_DECIMALS)} SOL"
                    } ?: transfer.amount
                } else {
                    "${transfer.amount} units"
                }
            RequestTileCopy(
                Icons.Outlined.NorthEast,
                stringResource(R.string.action_transfer),
                amount,
                "to ${shortAddress(transfer.recipient)}",
                stringResource(R.string.request_funds_move),
            )
        }
        Action.KindCase.SIGN_MESSAGE -> {
            val preview = requireNotNull(messagePreview(request))
            RequestTileCopy(
                Icons.Outlined.Draw,
                stringResource(R.string.request_signature),
                stringResource(R.string.request_bytes, preview.bytes),
                preview.display,
                stringResource(R.string.request_no_funds_move),
            )
        }
        Action.KindCase.ACK ->
            RequestTileCopy(
                Icons.Outlined.DoneAll,
                stringResource(R.string.request_acknowledge),
                request.text() ?: actionText(request),
                stringResource(R.string.request_source_asks, sourceLabel),
                stringResource(R.string.request_nothing_signed),
            )
        else ->
            RequestTileCopy(
                Icons.Outlined.Close,
                actionText(request),
                actionText(request),
                sourceLabel,
                stringResource(R.string.request_review_required),
            )
    }
}

@Composable
private fun RequestTile(
    request: ActionRequest,
    source: Connection?,
    warning: Boolean,
    active: Boolean,
    onOpen: () -> Unit,
) {
    val copy = requestTileCopy(request, source)
    val ink =
        if (active) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    val secondary =
        if (active) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    SeekerCard(
        modifier = Modifier.size(width = 204.dp, height = 192.dp),
        color =
            if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        radius = 20.dp,
        onClick = onOpen,
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    copy.icon,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    copy.kind,
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    copy.headline,
                    style = MaterialTheme.typography.headlineLarge,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    copy.detail,
                    style =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    copy.consequence,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
                RequestPill(warning = warning, active = active)
            }
        }
    }
}

@Composable
private fun RequestPill(warning: Boolean, active: Boolean) {
    val background =
        when {
            warning -> MaterialTheme.colorScheme.tertiaryContainer
            active -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        }
    val foreground =
        when {
            warning -> MaterialTheme.colorScheme.onTertiaryContainer
            active -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        }
    Box(
        Modifier.height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(
                if (warning) R.string.request_one_warning else R.string.request_in_rules
            ),
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = SeekerTheme.colors.primaryText,
    )
}

@Composable
private fun GlobalRulesItem(onClick: () -> Unit) {
    SeekerCard(
        modifier = Modifier.padding(horizontal = 16.dp).testTag(ConnectionsTags.GLOBAL_RULES),
        radius = 16.dp,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.Outlined.Public, MaterialTheme.colorScheme.primaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.global_rules_row),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.global_rules_row_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActivityItem(recorded: Int, onClick: () -> Unit) {
    SeekerCard(
        modifier = Modifier.padding(horizontal = 16.dp).testTag(ConnectionsTags.ACTIVITY),
        radius = 16.dp,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.Outlined.History, MaterialTheme.colorScheme.surfaceContainerHighest)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.activity_row),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (recorded == 0) stringResource(R.string.activity_row_none)
                    else pluralStringResource(R.plurals.activity_row_count, recorded, recorded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionItem(connection: Connection, onClick: () -> Unit) {
    SeekerCard(
        modifier =
            Modifier.padding(horizontal = 16.dp).testTag(ConnectionsTags.item(connection.id)),
        radius = 16.dp,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasProblem(connection))
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.tertiaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initials(connection.label),
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (hasProblem(connection)) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(connection.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    statusText(connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (hasProblem(connection)) SeekerTheme.colors.errorText
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun initials(label: String): String =
    label
        .split(Regex("\\s+|-"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "S" }

@Composable
private fun RoundIcon(icon: ImageVector, color: Color) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AddConnectionAction(onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 20.dp)
            .testTag(ConnectionsTags.ADD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            stringResource(R.string.add_connection),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
