package io.github.brrenat.seekervault.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.designsystem.PositionSaleSheet
import io.github.brrenat.seekervault.positions.PositionsViewModel
import io.github.brrenat.seekervault.positions.SaleReviewState
import io.github.brrenat.seekervault.positions.notice
import io.github.brrenat.seekervault.proposals.ProposalRecord
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * The sale review over a History item (SEE-172).
 *
 * Opening it asks for a sale to be built and read; nothing else happens until the owner taps Sell,
 * and that hands exactly the reviewed bytes to the wallet once. When the review runs out or the
 * position moves, Sell is disabled and the only way on is to prepare it again and read it again.
 */
@Composable
fun PositionSaleRoute(
    identity: ReviewIdentity,
    feedRecords: List<ProposalRecord>,
    positions: PositionsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    clock: HistoryDetailClock = HistoryDetailClock(),
) {
    val context = LocalContext.current
    val state by positions.state.collectAsState()
    val notices by positions.notices.collectAsState()
    val record =
        feedRecords.firstOrNull {
            it.connectionId == identity.connectionId && it.key.proposalId == identity.requestId
        }
    val holding = record?.let(state::holdingOf)
    val account = holding?.held?.account
    if (account == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val review = state.reviews[account]
    var now by remember { mutableStateOf(Instant.now()) }
    var handedOff by remember { mutableStateOf(false) }
    LaunchedEffect(account) { if (review == null) positions.prepareSale(account) }
    // The review's expiry is on screen; the clock moves so Sell turns off the moment it runs out.
    LaunchedEffect(review) {
        while (review is SaleReviewState.Ready) {
            now = Instant.now()
            delay(1_000)
        }
    }
    if (review == SaleReviewState.Signing) handedOff = true
    // Handed to the wallet and settled: the sale is on the History item now, not in this sheet.
    LaunchedEffect(handedOff, review) {
        if (handedOff && review == null) onClose()
    }
    PositionSaleSheet(
        state =
            positionSaleSheet(
                review = review,
                holding = holding,
                notice = notices[account]?.notice(),
                now = now,
                text = context::getString,
                clock = clock,
            ),
        onSell = { positions.sell(account) },
        onCancel = {
            positions.closeSale(account)
            onClose()
        },
        onPrepareAgain = { positions.prepareSale(account) },
        modifier = modifier,
    )
}
