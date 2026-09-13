package io.github.brrenat.seekervault.inbox

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.ui.Gesture
import io.github.brrenat.seekervault.ui.Glass
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.Motion
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.Space
import io.github.brrenat.seekervault.ui.Tag
import io.github.brrenat.seekervault.ui.Veil
import io.github.brrenat.seekervault.ui.glass
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The request review, as a panel over the screen the owner was on (SEE-57).
 *
 * It is a layer rather than a destination: the list stays where it was, the veil dims it, and the
 * panel can be pushed away with a finger without answering anything. Dragging sideways moves
 * between the requests that are waiting, so three requests are three swipes rather than three trips
 * back to a list.
 *
 * The panel's own motion carries the one fact this screen exists to make plain: when an approval
 * goes to the wallet, the panel does not close and hand over — it *recedes*, and the wallet's sheet
 * rises in front of it. The owner can see that the app has stepped back and another app is being
 * asked. Nothing here signs, and nothing here answers on its own.
 */
@Composable
fun RequestPanel(
    key: RequestKey,
    state: InboxUiState,
    now: Instant,
    viewModel: InboxViewModel,
    onMoveTo: (RequestKey) -> Unit,
    onRules: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.inbox.result(key)
    val request = result?.request ?: state.inbox.pendingRequest(key) ?: return
    val waiting = state.inbox.pendingKeys()
    val at = waiting.indexOf(key)
    val density = LocalDensity.current
    val moveThreshold = with(density) { Gesture.PanelThreshold.toPx() }
    val dismissThreshold = with(density) { Gesture.PanelDismiss.toPx() }
    val lock = with(density) { Gesture.AxisLock.toPx() }
    val dx = remember(key) { Animatable(0f) }
    val dy = remember(key) { Animatable(0f) }
    var axis by remember(key) { mutableStateOf(Axis.Undecided) }
    val scope = rememberCoroutineScope()

    // The wallet is being asked. The panel steps back and the sheet comes up in front of it.
    val signable = request.transfer() != null || messagePreview(request) != null
    val handingOff = signable && key in state.sending
    val scale by animateFloatAsState(if (handingOff) 0.93f else 1f, Motion.panel(), label = "panel")
    val lift by animateFloatAsState(if (handingOff) -24f else 0f, Motion.panel(), label = "lift")

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize()) {
        Veil(onDismiss = onDismiss, alpha = 0.62f, tag = InboxTags.PANEL_VEIL)
        Column(
            Modifier.fillMaxSize()
                .padding(
                    start = Space.Edge,
                    end = Space.Edge,
                    top = top + 78.dp,
                    bottom = bottom + 78.dp,
                )
                .offset { IntOffset(dx.value.toInt(), dy.value.toInt()) }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = lift * density.density
                    // The further sideways the panel is dragged, the less of it there is: the
                    // owner can see they are leaving this request before they have left it.
                    alpha = (1f - abs(dx.value) / 420f).coerceAtLeast(0.4f)
                }
                .glass(Glass.panel(), RoundedCornerShape(Radius.Panel))
                .pointerInput(key, handingOff, waiting) {
                    if (handingOff) return@pointerInput
                    detectDragGestures(
                        onDragStart = { axis = Axis.Undecided },
                        onDragCancel = { scope.launch { settle(dx, dy) } },
                        onDragEnd = {
                            val movedX = dx.value
                            val movedY = dy.value
                            scope.launch { settle(dx, dy) }
                            when {
                                axis == Axis.Vertical && movedY >= dismissThreshold -> onDismiss()
                                axis == Axis.Horizontal && movedX <= -moveThreshold ->
                                    waiting.getOrNull(at + 1)?.let(onMoveTo)
                                axis == Axis.Horizontal && movedX >= moveThreshold ->
                                    waiting.getOrNull(at - 1)?.let(onMoveTo)
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        if (axis == Axis.Undecided) {
                            val total = dx.value + amount.x to dy.value + amount.y
                            if (abs(total.first) > lock || abs(total.second) > lock) {
                                axis =
                                    if (abs(total.first) >= abs(total.second)) Axis.Horizontal
                                    else Axis.Vertical
                            }
                        }
                        scope.launch {
                            when (axis) {
                                Axis.Horizontal -> {
                                    val next = dx.value + amount.x
                                    // At the ends there is nowhere to go, so the drag is given
                                    // most of the way back rather than pretending there is.
                                    val atEnd =
                                        (next < 0 && at >= waiting.lastIndex) ||
                                            (next > 0 && at <= 0)
                                    dx.snapTo(if (atEnd) next * Gesture.RUBBER_BAND else next)
                                }
                                // Only downwards: a panel dragged up would go nowhere.
                                Axis.Vertical -> dy.snapTo((dy.value + amount.y).coerceAtLeast(0f))
                                Axis.Undecided -> Unit
                            }
                        }
                    }
                }
                .testTag(InboxTags.PANEL),
            verticalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            GrabHandle()
            if (waiting.size > 1 && at >= 0) {
                Pager(
                    at = at,
                    of = waiting.size,
                    onPrevious = { waiting.getOrNull(at - 1)?.let(onMoveTo) },
                    onNext = { waiting.getOrNull(at + 1)?.let(onMoveTo) },
                )
            }
            RequestDetailsScreen(
                request = request,
                source = state.connections.firstOrNull { it.id == key.connectionId },
                result = result,
                sending = key in state.sending,
                now = now,
                onAnswer = { viewModel.answer(key, it) },
                onApprove = { viewModel.approve(key, state.wallet) },
                onSendAgain = { viewModel.sendAgain(key) },
                wallet = state.wallet,
                signingProblem = state.problem.takeIf { state.problemKey == key },
                preparation = state.preparations[key],
                onPrepareAgain = { viewModel.prepare(key, force = true) },
                onApproveTransfer = {
                    viewModel.approveTransfer(key, state.preparations[key] as? Preparation.Ready)
                },
                checking = key in state.checking,
                onCheckStatus = { viewModel.checkStatus(key) },
                assessment = state.assessments[key],
                acknowledged =
                    state.acknowledged[key] != null &&
                        state.acknowledged[key] == state.assessments[key]?.consent,
                onAcknowledge = { viewModel.acknowledge(key, it) },
                onRules = onRules,
                onBack = onDismiss,
            )
        }
        if (handingOff) {
            WalletHandoffSheet(
                signsAndSends = request.transfer() != null,
                wallet = state.wallet?.address,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    // Opening a transfer fetches a fresh transaction and reads it on this phone. It is a read and
    // nothing more: no wallet opens until the owner taps Approve.
    LaunchedEffect(key) { viewModel.prepare(key) }
    // And the rules are read for it, every time it is opened. Nothing is remembered between visits,
    // so rules changed in between are the ones that apply (SAW-028).
    LaunchedEffect(key) { viewModel.review(key) }
}

private enum class Axis {
    Undecided,
    Horizontal,
    Vertical,
}

private suspend fun settle(dx: Animatable<Float, *>, dy: Animatable<Float, *>) {
    dx.animateTo(0f, Motion.springBack())
    dy.animateTo(0f, Motion.springBack())
}

/** The 40 × 4 bar that says this panel can be pushed away. */
@Composable
private fun GrabHandle() {
    Box(Modifier.fillMaxWidth().padding(top = Space.Md), contentAlignment = Alignment.Center) {
        Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(Nocturne.text(0.22f)))
    }
}

/** Where this request sits among the ones that are waiting, and the way to its neighbours. */
@Composable
private fun Pager(at: Int, of: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.Inset).testTag(InboxTags.PANEL_PAGER),
        horizontalArrangement = Arrangement.spacedBy(Space.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagerButton(
            Glyph.Back,
            stringResource(R.string.review_previous),
            at > 0,
            onPrevious,
            InboxTags.PANEL_PREVIOUS,
        )
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(of) { index ->
                Box(
                    Modifier.width(if (index == at) 18.dp else 6.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (index == at) Nocturne.Accent300 else Nocturne.text(0.18f))
                )
            }
        }
        Text(
            stringResource(R.string.review_pager, at + 1, of),
            style = MaterialTheme.typography.bodySmall,
            color = Nocturne.Neutral500,
        )
        PagerButton(
            Glyph.Forward,
            stringResource(R.string.review_next),
            at < of - 1,
            onNext,
            InboxTags.PANEL_NEXT,
        )
    }
}

@Composable
private fun PagerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Box(
        Modifier.size(32.dp)
            .clip(CircleShape)
            .background(Nocturne.text(if (enabled) 0.09f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) Nocturne.Neutral200 else Nocturne.Neutral700,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The hand-off to the wallet (SEE-57).
 *
 * It rises in front of the panel while the owner's wallet is being asked, and it is deliberately
 * *not* a copy of the wallet's own screen: there are no Sign and Decline buttons on it, because
 * pressing them here would do nothing and showing them would teach the owner that this app is where
 * signing is confirmed. It says who is being asked, and that what happens next is decided there.
 */
@Composable
fun WalletHandoffSheet(
    signsAndSends: Boolean,
    wallet: String?,
    modifier: Modifier = Modifier,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(Nocturne.bg(0.58f)))
    Column(
        modifier
            .fillMaxWidth()
            .glass(
                Glass.sheet(),
                RoundedCornerShape(
                    topStart = Radius.Sheet,
                    topEnd = Radius.Sheet,
                    bottomStart = Radius.Frame,
                    bottomEnd = Radius.Frame,
                ),
            )
            .padding(start = Space.Xl, end = Space.Xl, top = Space.Xl, bottom = Space.Xl + bottom)
            .testTag(InboxTags.HANDOFF)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Space.Md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp)
                    .clip(RoundedCornerShape(Radius.ChipTight))
                    .background(Nocturne.accent(0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Glyph.WithinRules,
                    contentDescription = null,
                    tint = Nocturne.Accent100,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column {
                Text(
                    stringResource(R.string.handoff_wallet),
                    style = MaterialTheme.typography.titleSmall,
                    color = Nocturne.Text,
                )
                Text(
                    stringResource(R.string.handoff_other_app),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nocturne.Neutral500,
                )
            }
        }
        Text(
            stringResource(
                if (signsAndSends) R.string.handoff_title_send else R.string.handoff_title_sign
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = Nocturne.Text,
        )
        if (wallet != null) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.InnerTight))
                    .background(Nocturne.bg(0.55f))
                    .padding(Space.Md)
            ) {
                io.github.brrenat.seekervault.ui.MonoText(wallet, maxLines = 1)
            }
        }
        Text(
            stringResource(R.string.handoff_keys),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral400,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tag(stringResource(R.string.handoff_waiting), icon = Glyph.Refresh)
        }
    }
}
