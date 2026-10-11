package io.github.brrenat.seekervault.operations

import android.content.res.Resources
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.OwnerInputCardKind
import io.github.brrenat.seekervault.designsystem.OwnerInputCardState
import io.github.brrenat.seekervault.designsystem.ReviewSheetAction
import io.github.brrenat.seekervault.designsystem.ReviewSheetFactRow
import io.github.brrenat.seekervault.designsystem.ReviewSheetState
import io.github.brrenat.seekervault.designsystem.ReviewSheetYourPart
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.SwapParameterNames
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.SwapTermNames
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.executable
import io.github.brrenat.seekervault.reviews.ReviewVerdict
import io.github.brrenat.seekervault.reviews.reviewVerdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Whether a held proposal is reviewed in the design's swap sheet: a swap nobody has acted on yet.
 * One that was executed keeps the older review, which carries where to look afterwards — the same
 * split the prediction sheet makes ([reviewedAsPrediction]).
 */
val ProposalRecord.reviewedAsSwap: Boolean
    get() = proposal.action == SWAP_ACTION && execution == null

internal val SwapWording =
    SignalSheetWording(
        aboutTitle = R.string.swap_section_about,
        aboutSummary = R.string.swap_section_about_summary,
        technicalSummary = R.string.swap_section_technical_summary,
        checkRead = R.string.swap_review_check,
        checkPending = R.string.swap_review_check_pending,
        dailyAfterQuote = R.string.swap_daily_after_quote,
        insufficientFundsBody = R.string.swap_review_insufficient_funds_body,
    )

/**
 * A `swap` signal, read for the review sheet the design draws (`screens/sheet-swap`), the way
 * [toPredictionSheet] reads a prediction: the pair is the headline, the owner's amount and wallet
 * are one card opening the sheet stacked over this one, the verdict and what stopped a quote are
 * said once, and everything behind that sits in the same four collapsed sections.
 *
 * The stages, the verdict, the checks, the provider and the identifiers are the prediction sheet's
 * own code; only what names the operation is a swap's. It is a pure reading of [OperationReview]
 * and decides nothing about whether anything may be approved.
 */
fun OperationReview.toSwapSheet(
    resources: Resources,
    source: PredictionReviewSource,
    wallet: SelectedWallet?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = resources.configuration.locales[0],
): ReviewSheetState {
    // A refresh keeps the bytes it replaces until the provider answers; none of them is shown
    // meanwhile, so an expired or refused quote never reads as the current one.
    if (preparing && inspection != null) {
        return copy(inspection = null).toSwapSheet(resources, source, wallet, now, zone, locale)
    }
    val proposal = record.proposal
    val terms = (payload as? ActionPayload.Swap)?.payload
    val executed = record.execution != null
    val choosable = standing.executable && served && form.problem == null && !executed
    val spend = spendText(terms, resources)
    val stage = signalStage(now, chosen = spend != null)
    val verdict = assessment?.decision?.reviewVerdict(prepared = inspection != null)
    val warnings = verdict as? ReviewVerdict.Warnings
    val needsAcknowledging = stage.decidable && verdict is ReviewVerdict.Warnings
    val approvable = stage.decidable && standing.executable && !executed && !sending

    return ReviewSheetState(
        title = resources.getString(R.string.swap_review_title),
        headline = pairOf(terms, resources) ?: proposal.title.ifBlank { proposal.key.proposalId },
        // Who routes it, and the most the price may move: the one term the publisher sets that
        // the owner's own choice is held to.
        subline =
            terms?.let {
                resources.getString(
                    R.string.swap_review_subline,
                    about?.name ?: providerName(),
                    percent(it.maxSlippageBps, resources),
                )
            } ?: (about?.name ?: providerName()),
        headerChips = signalHeaderChips(source),
        sandboxNotice =
            if (previewed && !executed) resources.getString(R.string.swap_previewed_notice)
            else null,
        statusBlocks = statusBlocks(resources),
        // "1.5 SOL · Main wallet": the amount and the wallet that signs, chosen together.
        yourPart =
            if (choosable) {
                val summary = spend?.let { chosen ->
                    wallet?.let {
                        resources.getString(R.string.swap_review_your_part, chosen, walletName(it))
                    }
                }
                ReviewSheetYourPart(
                    kind = OwnerInputCardKind.Swap,
                    state =
                        if (summary == null) OwnerInputCardState.Unchosen
                        else OwnerInputCardState.Chosen,
                    summary = summary,
                )
            } else null,
        verdict = verdictOf(verdict, terms?.inputSymbol.orEmpty(), resources),
        infoBlocks = noRulesBlocks(verdict, resources),
        staleQuote = staleQuoteOf(stage, choosable, spend, resources),
        preparationError =
            preparationErrorOf(
                stage = stage,
                choosable = choosable,
                chosen = spend,
                retryable = spend != null,
                wording = SwapWording,
                resources = resources,
            ),
        expiry = longTime(proposal.expiresAt, zone, locale),
        confirmationCheckbox =
            if (needsAcknowledging) {
                resources.getString(
                    if ((warnings?.warnings?.size ?: 1) > 1) R.string.prediction_acknowledge_many
                    else R.string.prediction_acknowledge_one
                )
            } else null,
        confirmationChecked = acknowledged,
        primaryAction =
            ReviewSheetAction(
                label = resources.getString(R.string.operation_approve),
                enabled = approvable,
            ),
        previewAction =
            if (previewable && !executed) {
                ReviewSheetAction(
                    label = resources.getString(R.string.operation_simulate),
                    enabled = approvable,
                )
            } else null,
        secondaryAction =
            ReviewSheetAction(
                label = resources.getString(R.string.prediction_dismiss),
                enabled = record.dismissed == null && !executed && !sending,
            ),
        footerCaption = resources.getString(R.string.prediction_dismiss_note, source.name),
        actionsShown = standing is ProposalStanding.Open,
        footerStatus = quotingStatus(stage, resources),
        sections =
            listOf(
                checksSection(stage, SwapWording, resources),
                aboutSection(zone, locale, SwapWording, resources),
                providerSection(marketSource = null, source, resources),
                technicalSection(swapFacts(terms, resources), SwapWording, resources),
            ),
    )
}

/** "SOL → USDC", each side by its symbol or else its shortened mint; null without terms. */
private fun pairOf(terms: SwapPayload?, resources: Resources): String? {
    terms ?: return null
    return resources.getString(
        R.string.swap_review_pair,
        terms.inputSymbol.ifBlank { terms.inputMint.middle() },
        terms.outputSymbol.ifBlank { terms.outputMint.middle() },
    )
}

/** "1.5 SOL": the amount the owner chose to spend, with its unit, or null until one is chosen. */
private fun OperationReview.spendText(terms: SwapPayload?, resources: Resources): String? {
    val field = form.fields.firstOrNull { it.kind is ParameterKind.Amount } ?: return null
    val decimals = (field.kind as ParameterKind.Amount).decimals
    val chosen = (choice.values[field.key] as? ParameterValue.Amount)?.baseUnits ?: return null
    return amount(chosen, decimals, terms?.inputSymbol.orEmpty(), resources)
}

/** Basis points as a percentage, "0.5%", without rounding anything away. */
private fun percent(bps: Int, resources: Resources): String =
    resources.getString(
        R.string.swap_review_percent,
        BigDecimal(bps).movePointLeft(2).stripTrailingZeros().toPlainString(),
    )

/**
 * The swap's technical rows: the publisher's terms under labels rather than their keys, the
 * slippage the owner chose, anything else the publisher sent, then every signal's own rows.
 */
private fun OperationReview.swapFacts(
    terms: SwapPayload?,
    resources: Resources,
): List<ReviewSheetFactRow> = buildList {
    val proposal = record.proposal
    fun plain(label: Int, value: String) = add(plainRow(label, value, resources))
    fun mono(label: Int, value: String) = add(monoRow(label, value, resources, copy = true))

    add(monoRow(R.string.prediction_fact_action, proposal.action.value, resources))
    if (terms != null) {
        terms.inputSymbol.takeIf(String::isNotBlank)?.let { plain(R.string.swap_fact_pay, it) }
        mono(R.string.swap_fact_pay_mint, terms.inputMint)
        plain(R.string.swap_fact_pay_decimals, terms.inputDecimals.toString())
        terms.outputSymbol.takeIf(String::isNotBlank)?.let {
            plain(R.string.swap_fact_receive, it)
        }
        mono(R.string.swap_fact_receive_mint, terms.outputMint)
        plain(R.string.swap_fact_receive_decimals, terms.outputDecimals.toString())
        (choice.values[SwapParameterNames.SLIPPAGE_BPS] as? ParameterValue.Count)?.let {
            plain(R.string.swap_fact_slippage, percent(it.value.toInt(), resources))
        }
        plain(R.string.swap_fact_max_slippage, percent(terms.maxSlippageBps, resources))
        if (proposal.value(SwapTermNames.LEAST_INPUT) != null) {
            plain(
                R.string.swap_fact_least_input,
                amount(terms.leastInput, terms.inputDecimals, terms.inputSymbol, resources),
            )
        }
        terms.mostInput?.let {
            plain(
                R.string.swap_fact_most_input,
                amount(it, terms.inputDecimals, terms.inputSymbol, resources),
            )
        }
    }
    // Anything the publisher said that this build does not read as a swap term, shown once.
    proposal.values
        .filter { terms == null || it.key !in KnownSwapTerms }
        .forEach { add(ReviewSheetFactRow(it.key.humanName(), it.text)) }
    addAll(transactionFacts(resources))
}

private val KnownSwapTerms =
    setOf(
        SwapTermNames.INPUT_MINT,
        SwapTermNames.INPUT_DECIMALS,
        SwapTermNames.INPUT_SYMBOL,
        SwapTermNames.OUTPUT_MINT,
        SwapTermNames.OUTPUT_DECIMALS,
        SwapTermNames.OUTPUT_SYMBOL,
        SwapTermNames.MAX_SLIPPAGE_BPS,
        SwapTermNames.LEAST_INPUT,
        SwapTermNames.MOST_INPUT,
    )
