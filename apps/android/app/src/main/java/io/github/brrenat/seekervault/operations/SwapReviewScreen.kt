package io.github.brrenat.seekervault.operations

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.time.Instant

object SwapReviewTags {
    const val SHEET = "swap.review"
}

/**
 * The review of one swap signal, laid out as the design's review sheet (`screens/sheet-swap`), the
 * same sheet a prediction is reviewed in ([PredictionReviewScreen]).
 *
 * The header and the decision are pinned and only the body scrolls. The owner's amount, slippage
 * and wallet are chosen in [SwapParametersSheet], stacked over this one; choosing them prepares,
 * and so does the stale-quote card's Refresh. An executed swap keeps [ProposalReviewScreen], which
 * says where to look afterwards.
 */
@Composable
fun SwapReviewScreen(
    review: OperationReview,
    source: PredictionReviewSource,
    wallet: SelectedWallet?,
    now: Instant,
    onOwnerInput: () -> Unit,
    onPrepare: () -> Unit,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
    onAcknowledge: (Boolean) -> Unit,
    onRules: () -> Unit,
    onBack: () -> Unit,
    onOpenLink: (url: String, deepLink: String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Previews the prepared swap without sending it (SEE-185). */
    onPreview: () -> Unit = {},
) {
    val resources = LocalContext.current.resources
    SignalReviewSheet(
        review = review,
        now = now,
        sheet = { clock -> review.toSwapSheet(resources, source, wallet, clock) },
        onOwnerInput = onOwnerInput,
        onPrepare = onPrepare,
        onApprove = onApprove,
        onDismiss = onDismiss,
        onAcknowledge = onAcknowledge,
        onRules = onRules,
        onBack = onBack,
        onOpenLink = onOpenLink,
        onPreview = onPreview,
        modifier = modifier.testTag(SwapReviewTags.SHEET),
    )
}
