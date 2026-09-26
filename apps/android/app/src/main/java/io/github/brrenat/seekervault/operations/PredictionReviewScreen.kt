package io.github.brrenat.seekervault.operations

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.OwnerParametersChoice
import io.github.brrenat.seekervault.designsystem.OwnerParametersSheet
import io.github.brrenat.seekervault.designsystem.OwnerParametersSheetCallbacks
import io.github.brrenat.seekervault.designsystem.OwnerParametersSheetState
import io.github.brrenat.seekervault.designsystem.OwnerParametersSheetTags
import io.github.brrenat.seekervault.designsystem.ReviewSheet
import io.github.brrenat.seekervault.designsystem.ReviewSheetLayout
import io.github.brrenat.seekervault.designsystem.SegmentedNoSelection
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.plugins.actions.depositUnit
import io.github.brrenat.seekervault.policy.AmountEntry
import io.github.brrenat.seekervault.policy.readAmount
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether a held proposal is reviewed in the design's prediction sheet (SEE-158): a prediction
 * nobody has acted on yet. One that was executed keeps the older review, which carries where to
 * look afterwards.
 */
val ProposalRecord.reviewedAsPrediction: Boolean
    get() = proposal.action == PREDICTION_BUY_ACTION && execution == null

object PredictionReviewTags {
    const val SHEET = "prediction.review"
    const val PARAMS = "prediction.params"
    const val USE = "prediction.params.use"
    const val YES = "prediction.params.yes"
    const val NO = "prediction.params.no"
    const val AMOUNT = "prediction.params.amount"
}

/**
 * The review of one prediction signal, laid out as the design's review sheet (SEE-158).
 *
 * The header and the decision are pinned and only the body scrolls, so Approve is where the thumb
 * is rather than two thousand pixels down. The owner's side and stake are chosen in a sheet stacked
 * over this one ([PredictionParametersSheet]); choosing them re-quotes, and so does the stale-quote
 * card's Refresh.
 */
@Composable
fun PredictionReviewScreen(
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
) {
    val resources = LocalContext.current.resources
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // The quote runs out on its own. The sheet looks again at the moment it does, so the stale card
    // appears without anything else having to change first.
    var clock by remember(now) { mutableStateOf(now) }
    val expiresAt = review.prepared?.expiresAtEpochSeconds
    LaunchedEffect(expiresAt, now) {
        if (expiresAt == null) return@LaunchedEffect
        val wait = expiresAt * MILLIS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        clock = Instant.now()
    }
    ReviewSheet(
        state = review.toPredictionSheet(resources, source, wallet, clock),
        onPrimary = onApprove,
        onSecondary = onDismiss,
        onRules = onRules,
        onChoose = onOwnerInput,
        onClose = onBack,
        layout = ReviewSheetLayout.Pinned,
        onCopy = { value ->
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, value))) }
        },
        onOpenLink = { url ->
            onOpenLink(url, review.destinations.firstOrNull { it.url == url }?.deepLink)
        },
        onConfirmedChange = onAcknowledge,
        onRefreshQuote = onPrepare,
        modifier = modifier.testTag(PredictionReviewTags.SHEET),
    )
}

/**
 * The owner's side and stake, stacked over the review: `[review, params]` (design/navigation.md).
 *
 * What is typed stays here until **Use these**. Then it is checked against the market's bounds,
 * handed to the review in the asset's own base units — digits shifted, never multiplied — and the
 * review quotes again from it.
 */
@Composable
fun PredictionParametersSheet(
    review: OperationReview,
    onUse: (List<Pair<ParameterKey, ParameterValue>>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val side = review.form.fields.firstOrNull { it.kind is ParameterKind.Choice }
    val deposit = review.form.fields.firstOrNull { it.kind is ParameterKind.Amount }
    val sideKind = side?.kind as? ParameterKind.Choice
    val depositKind = deposit?.kind as? ParameterKind.Amount
    val terms = (review.payload as? ActionPayload.PredictionBuy)?.payload
    var sideIndex by
        rememberSaveable(review.proposalId) {
            mutableStateOf(
                sideKind?.options?.indexOfFirst {
                    (review.choice.values[side.key] as? ParameterValue.Selected)?.option == it.key
                } ?: SegmentedNoSelection
            )
        }
    var typed by
        rememberSaveable(review.proposalId) {
            mutableStateOf(
                depositKind
                    ?.let { kind ->
                        (review.choice.values[deposit.key] as? ParameterValue.Amount)?.let {
                            formatBaseUnits(it.baseUnits, kind.decimals)
                        }
                    }
                    .orEmpty()
            )
        }
    var error by rememberSaveable(review.proposalId) { mutableStateOf<String?>(null) }
    val entry = depositKind?.let { readAmount(typed, it.decimals) }
    OwnerParametersSheet(
        state =
            OwnerParametersSheetState(
                title = resources.getString(R.string.prediction_params_title),
                choice =
                    sideKind?.let { kind ->
                        OwnerParametersChoice(
                            label = resources.getString(side.label),
                            options = kind.options.map { resources.getString(it.label) },
                            selectedIndex = sideIndex,
                        )
                    },
                amountLabel = deposit?.let { resources.getString(it.label) }.orEmpty(),
                amount = typed,
                amountHelp = resources.getString(R.string.prediction_params_help),
                amountError = error,
                note = resources.getString(R.string.prediction_params_note),
                useLabel = resources.getString(R.string.prediction_params_use),
                canUse =
                    (sideKind == null || sideIndex != SegmentedNoSelection) &&
                        entry is AmountEntry.Amount,
            ),
        callbacks =
            OwnerParametersSheetCallbacks(
                onChoose = { sideIndex = it },
                onAmountChange = {
                    typed = it
                    error =
                        if (
                            depositKind != null &&
                                readAmount(it, depositKind.decimals) is AmountEntry.Problem
                        )
                            resources.getString(R.string.operation_amount_invalid)
                        else null
                },
                onUse = {
                    val amount = (entry as? AmountEntry.Amount)?.baseUnits
                    val problem = depositKind?.let {
                        amount?.let { outOfBounds(it, depositKind, terms, resources) }
                    }
                    if (amount == null || problem != null) {
                        error = problem ?: resources.getString(R.string.operation_amount_invalid)
                    } else {
                        onUse(
                            buildList {
                                if (side != null && sideKind != null) {
                                    add(
                                        side.key to
                                            ParameterValue.Selected(sideKind.options[sideIndex].key)
                                    )
                                }
                                if (deposit != null)
                                    add(deposit.key to ParameterValue.Amount(amount))
                            }
                        )
                    }
                },
                onClose = onClose,
            ),
        tags =
            OwnerParametersSheetTags(
                use = PredictionReviewTags.USE,
                options = listOf(PredictionReviewTags.YES, PredictionReviewTags.NO),
                amount = PredictionReviewTags.AMOUNT,
            ),
        focusAmount = true,
        modifier = modifier.testTag(PredictionReviewTags.PARAMS),
    )
}

/** The market's own bounds, said in the asset's units; null when [amount] is within them. */
private fun outOfBounds(
    amount: ULong,
    kind: ParameterKind.Amount,
    terms: PredictionPayload?,
    resources: android.content.res.Resources,
): String? {
    val unit = terms?.depositUnit().orEmpty()
    fun said(baseUnits: ULong) = "${formatBaseUnits(baseUnits, kind.decimals)} $unit".trim()
    val most = kind.most
    return when {
        amount < kind.least ->
            resources.getString(R.string.prediction_params_too_little, said(kind.least))
        most != null && amount > most ->
            resources.getString(R.string.prediction_params_too_much, said(most))
        else -> null
    }
}

private const val MILLIS = 1_000L
