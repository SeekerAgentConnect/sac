package io.github.brrenat.seekervault.operations

import android.content.res.Resources
import android.text.format.DateFormat
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.DailyLimitRowScope
import io.github.brrenat.seekervault.designsystem.DailyLimitRowState
import io.github.brrenat.seekervault.designsystem.FactRowValueStyle
import io.github.brrenat.seekervault.designsystem.NetworkChipNetwork
import io.github.brrenat.seekervault.designsystem.OwnerInputCardKind
import io.github.brrenat.seekervault.designsystem.OwnerInputCardState
import io.github.brrenat.seekervault.designsystem.ReviewSheetAction
import io.github.brrenat.seekervault.designsystem.ReviewSheetDailySpend
import io.github.brrenat.seekervault.designsystem.ReviewSheetDailySpendRow
import io.github.brrenat.seekervault.designsystem.ReviewSheetFactRow
import io.github.brrenat.seekervault.designsystem.ReviewSheetHeaderChip
import io.github.brrenat.seekervault.designsystem.ReviewSheetInfoBlock
import io.github.brrenat.seekervault.designsystem.ReviewSheetNote
import io.github.brrenat.seekervault.designsystem.ReviewSheetPreparationError
import io.github.brrenat.seekervault.designsystem.ReviewSheetSection
import io.github.brrenat.seekervault.designsystem.ReviewSheetStaleQuote
import io.github.brrenat.seekervault.designsystem.ReviewSheetState
import io.github.brrenat.seekervault.designsystem.ReviewSheetVerdict
import io.github.brrenat.seekervault.designsystem.ReviewSheetWarning
import io.github.brrenat.seekervault.designsystem.ReviewSheetYourPart
import io.github.brrenat.seekervault.designsystem.ScopeChipSource
import io.github.brrenat.seekervault.designsystem.SourceColour
import io.github.brrenat.seekervault.designsystem.VerdictWarningKind
import io.github.brrenat.seekervault.inbox.RequestAssessment
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginFailureCodes
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.ProviderNoteTopic
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionTermNames
import io.github.brrenat.seekervault.plugins.actions.depositUnit
import io.github.brrenat.seekervault.policy.DailyCheckScope
import io.github.brrenat.seekervault.policy.DailyPolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RuleSource
import io.github.brrenat.seekervault.policy.checkText
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.executable
import io.github.brrenat.seekervault.reviews.ReviewVerdict
import io.github.brrenat.seekervault.reviews.ReviewWarning
import io.github.brrenat.seekervault.reviews.WarningOrigin
import io.github.brrenat.seekervault.reviews.reviewVerdict
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.walletNetworkOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Where a signal came from, as the owner named and coloured the connection. */
data class PredictionReviewSource(val name: String, val colour: SourceColour? = null)

/**
 * The few lines of a signal sheet that name what kind of operation it is. Everything else — the
 * stages, the verdict, the checks, the provider, the identifiers — reads the same for a prediction
 * and a swap, and is said by the same code.
 */
internal data class SignalSheetWording(
    val aboutTitle: Int,
    val aboutSummary: Int,
    val technicalSummary: Int,
    /** What this phone read, once there is a transaction, and before there is one. */
    val checkRead: Int,
    val checkPending: Int,
    /** A daily limit that waits on the amount the transaction spends. */
    val dailyAfterQuote: Int,
    /** "Could not prepare the 5 USDC order…", with the owner's amount. */
    val insufficientFundsBody: Int,
)

internal val PredictionWording =
    SignalSheetWording(
        aboutTitle = R.string.prediction_section_about,
        aboutSummary = R.string.prediction_section_about_summary,
        technicalSummary = R.string.prediction_section_technical_summary,
        checkRead = R.string.prediction_review_check,
        checkPending = R.string.prediction_review_check_pending,
        dailyAfterQuote = R.string.prediction_daily_after_quote,
        insufficientFundsBody = R.string.prediction_review_insufficient_funds_body,
    )

/**
 * Where a signal review — a prediction's or a swap's — stands between choosing and approving
 * (SEE-180).
 *
 * Each stage says one thing in the sheet. Telling them apart is what keeps "nothing has been
 * prepared yet" from reading as "this phone could not account for the transaction": the first is
 * the absence of a transaction, the second a finding about one. Nothing here relaxes a gate — the
 * evaluation stays as conservative as it was, and only what the sheet says about it changes.
 */
internal enum class SignalStage {
    /** The owner's part — a side, an amount — is still to be chosen. */
    Unchosen,
    /** Both are chosen and nothing is prepared for them: the inputs or the wallet moved on. */
    NeedsQuote,
    Preparing,
    /** Nothing was prepared, and the provider or the plugin said why. */
    Failed,
    /** Bytes arrived and this phone could not account for them. Never approvable. */
    Blocked,
    /**
     * The quote ran out. The owner still decides: approving prepares it again first and goes on
     * only when the fresh bytes read the same way ([OperationViewModel.approve]).
     */
    Expired,
    /** Prepared, read in full and current: the owner decides. */
    Ready;

    /** Whether the owner can approve from here: a quote that ran out is refreshed by approving. */
    val decidable: Boolean
        get() = this == Ready || this == Expired
}

internal fun OperationReview.predictionStage(now: Instant): SignalStage =
    signalStage(now, chosen = sideOf() != null && depositOf() != null)

/** [SignalStage] for a review whose owner's part is [chosen], or not yet. */
internal fun OperationReview.signalStage(now: Instant, chosen: Boolean): SignalStage {
    val prepared = prepared
    return when {
        preparing -> SignalStage.Preparing
        failure != null -> SignalStage.Failed
        prepared != null && inspection?.approvable != true -> SignalStage.Blocked
        prepared != null && prepared.expiresAtEpochSeconds?.let { now.epochSecond >= it } == true ->
            SignalStage.Expired
        prepared != null -> SignalStage.Ready
        !chosen -> SignalStage.Unchosen
        else -> SignalStage.NeedsQuote
    }
}

/**
 * A `prediction.buy` signal, read for the review sheet the design draws (SEE-158).
 *
 * Everything the old column showed is still here, and each value once: the publisher's terms as
 * labelled facts rather than their wire keys, amounts in whole tokens with their unit rather than
 * base units, times in the phone's own zone rather than ISO strings, and addresses shortened at the
 * middle so both ends stay readable.
 *
 * What the owner decides on is open: the market, where the order goes, their side, amount and
 * wallet, the verdict, and what stopped a quote if one was. Everything behind that sits in four
 * collapsed sections — limits and checks, the order and its risks, the provider and its terms, and
 * the technical identifiers — so the sheet is short without anything being dropped (SEE-180).
 *
 * It is a pure reading of [OperationReview]. Nothing here decides whether anything may be approved
 * — the view model and the binding gate do — it only says so in the sheet's terms.
 */
fun OperationReview.toPredictionSheet(
    resources: Resources,
    source: PredictionReviewSource,
    wallet: SelectedWallet?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = resources.configuration.locales[0],
): ReviewSheetState {
    // A refresh keeps the bytes it is replacing until the provider answers. None of them is shown
    // meanwhile — not as the quote, not as the verdict, not as accounts read out of them — so an
    // expired or refused quote never reads as the current one while the new one is fetched.
    if (preparing && inspection != null) {
        return copy(inspection = null)
            .toPredictionSheet(resources, source, wallet, now, zone, locale)
    }
    val proposal = record.proposal
    val terms = (payload as? ActionPayload.PredictionBuy)?.payload
    val executed = record.execution != null
    val choosable = standing.executable && served && form.problem == null && !executed
    val stage = predictionStage(now)
    val stake = stakeOf(terms, resources)
    // Before there is a transaction, its absence is not a finding (SEE-180).
    val verdict = assessment?.decision?.reviewVerdict(prepared = inspection != null)
    val warnings = verdict as? ReviewVerdict.Warnings
    // Only a real advisory warning asks for the deliberate tick (SAW-028). "No rules" is neutral
    // — nothing was checked, so there is nothing to go past (SEE-181). And the tick is only ever
    // asked for a transaction that could be approved: a warning about nothing yet, or about bytes
    // that are refused anyway, is read and not consented to.
    // A risk in the order itself — nobody is buying the chosen side, so it cannot be sold — asks
    // for the same deliberate tick, with or without a rule warning beside it.
    val cautions = inspection?.cautions.orEmpty().takeIf { stage.decidable }.orEmpty()
    val needsAcknowledging =
        stage.decidable && (verdict is ReviewVerdict.Warnings || cautions.isNotEmpty())
    val approvable = stage.decidable && standing.executable && !executed && !sending
    val closing = shortTime(proposal.expiresAt, zone, locale)

    return ReviewSheetState(
        title = resources.getString(R.string.prediction_review_title),
        // The market stays the headline; the owner's side and stake are on the "your part" card
        // (SEE-160).
        headline = proposal.title.ifBlank { terms?.marketId ?: proposal.key.proposalId },
        // Where the order is placed, under the name its provider requires, and when the market
        // closes. The market's identifier is metadata, and sits with the technical details.
        subline =
            resources.getString(
                R.string.prediction_review_subline,
                about?.name
                    ?: resources.getString(R.string.prediction_review_provider, providerName()),
                closing,
            ),
        headerChips = signalHeaderChips(source),
        // Just previewed: nothing was signed or sent, and the signal is still open (SEE-185).
        sandboxNotice =
            if (previewed && !executed) resources.getString(R.string.prediction_previewed_notice)
            else null,
        statusBlocks = statusBlocks(resources),
        // The wallet that signs is chosen with the side and the stake, in the same sheet, as a
        // swap's is with its amount (SEE-185): "5 USDC on Yes · Main wallet".
        yourPart =
            if (choosable) {
                val summary = stake?.let { chosen ->
                    wallet?.let {
                        resources.getString(
                            R.string.swap_review_your_part,
                            chosen,
                            walletName(it),
                        )
                    }
                }
                ReviewSheetYourPart(
                    kind = OwnerInputCardKind.Prediction,
                    state =
                        if (summary == null) OwnerInputCardState.Unchosen
                        else OwnerInputCardState.Chosen,
                    summary = summary,
                )
            } else null,
        verdict = verdictOf(verdict, terms?.depositUnit().orEmpty(), resources),
        infoBlocks = cautionBlocks(cautions, resources) + noRulesBlocks(verdict, resources),
        staleQuote = staleQuoteOf(stage, choosable, stake, resources),
        preparationError =
            preparationErrorOf(
                stage = stage,
                choosable = choosable,
                chosen = depositText(terms, resources),
                retryable = sideOf() != null && depositOf() != null,
                wording = PredictionWording,
                resources = resources,
            ),
        expiry = longTime(proposal.expiresAt, zone, locale),
        confirmationCheckbox =
            if (needsAcknowledging) {
                resources.getString(
                    when {
                        cautions.isNotEmpty() && warnings != null ->
                            R.string.prediction_acknowledge_caution_and_warnings
                        cautions.isNotEmpty() -> R.string.prediction_acknowledge_caution
                        (warnings?.warnings?.size ?: 1) > 1 -> R.string.prediction_acknowledge_many
                        else -> R.string.prediction_acknowledge_one
                    }
                )
            } else null,
        confirmationChecked = acknowledged,
        primaryAction =
            ReviewSheetAction(
                label = resources.getString(R.string.prediction_approve),
                enabled = approvable,
            ),
        // Preview without sending, beside the real thing and chosen here, per operation (SEE-185).
        previewAction =
            if (previewable && !executed) {
                ReviewSheetAction(
                    label = resources.getString(R.string.prediction_simulate),
                    enabled = approvable,
                )
            } else null,
        secondaryAction =
            ReviewSheetAction(
                label = resources.getString(R.string.prediction_dismiss),
                enabled = record.dismissed == null && !executed && !sending,
            ),
        footerCaption = resources.getString(R.string.prediction_dismiss_note, source.name),
        // Placed, dismissed, withdrawn or expired: a signal in History is read, not answered, so
        // the decision footer goes with it.
        actionsShown = standing is ProposalStanding.Open,
        // While a quote is fetched there is nothing to decide on yet: the footer says so instead
        // of offering actions that cannot be taken, and they come back once it answers.
        footerStatus = quotingStatus(stage, resources),
        sections =
            listOf(
                checksSection(stage, PredictionWording, resources),
                aboutSection(zone, locale, PredictionWording, resources),
                providerSection(terms?.marketProvider, source, resources),
                technicalSection(factsOf(terms, resources), PredictionWording, resources),
            ),
    )
}

/**
 * Signal, the feed in its colour, and the network the signal declares — never the wallet's
 * (SEE-185). Only mainnet is neutral: a test network is flagged in the sandbox orange (SEE-158).
 */
internal fun OperationReview.signalHeaderChips(
    source: PredictionReviewSource
): List<ReviewSheetHeaderChip> = buildList {
    add(ReviewSheetHeaderChip.Signal)
    add(ReviewSheetHeaderChip.Feed(source.name, source.colour))
    when (network?.let(::walletNetworkOf)) {
        WalletNetwork.Mainnet -> add(ReviewSheetHeaderChip.Network(NetworkChipNetwork.Mainnet))
        WalletNetwork.Devnet ->
            add(ReviewSheetHeaderChip.Network(NetworkChipNetwork.Devnet, flagged = true))
        WalletNetwork.Testnet,
        null -> Unit
    }
}

/** The cautions a plugin raised about the operation itself, each said before anything else. */
internal fun cautionBlocks(
    cautions: List<PluginFinding>,
    resources: Resources,
): List<ReviewSheetInfoBlock> = cautions.map {
    ReviewSheetInfoBlock(
        title = resources.getString(R.string.prediction_caution_title),
        body = resources.getString(it.message),
    )
}

/**
 * No rules is not a verdict: a neutral line where a verdict would be said, with no colour and
 * nothing to acknowledge (SEE-181).
 */
internal fun noRulesBlocks(
    verdict: ReviewVerdict?,
    resources: Resources,
): List<ReviewSheetInfoBlock> =
    listOfNotNull(
        ReviewSheetInfoBlock(
                title = resources.getString(R.string.prediction_no_rules_title),
                body = resources.getString(R.string.prediction_no_rules_body),
            )
            .takeIf { verdict == ReviewVerdict.NoRules }
    )

/**
 * There is no quote for what was chosen: said where the quote would be. One that ran out is not
 * said at all, because approving fetches a fresh one first.
 */
internal fun staleQuoteOf(
    stage: SignalStage,
    choosable: Boolean,
    chosen: String?,
    resources: Resources,
): ReviewSheetStaleQuote? =
    when {
        !choosable -> null
        stage == SignalStage.NeedsQuote ->
            ReviewSheetStaleQuote(
                message =
                    resources.getString(R.string.prediction_review_needs_quote, chosen.orEmpty()),
                actionLabel = resources.getString(R.string.prediction_review_get_quote),
            )
        else -> null
    }

/**
 * While a quote is fetched there is nothing to decide on yet: the footer says so instead of
 * offering actions that cannot be taken, and they come back once it answers.
 */
internal fun quotingStatus(stage: SignalStage, resources: Resources): String? =
    resources.getString(R.string.prediction_review_quoting).takeIf {
        stage == SignalStage.Preparing
    }

/** Section keys, stable across recompositions so an open section stays open (SEE-180). */
object PredictionReviewSections {
    const val CHECKS = "checks"
    const val ABOUT = "about"
    const val PROVIDER = "provider"
    const val TECHNICAL = "technical"
}

private fun OperationReview.sideOf(): ParameterKey? =
    (choice.values[PredictionParameterNames.OUTCOME] as? ParameterValue.Selected)?.option

private fun OperationReview.depositOf(): ULong? =
    (choice.values[PredictionParameterNames.DEPOSIT] as? ParameterValue.Amount)?.baseUnits

/** "5 USDC": the chosen amount with its unit, or null until one is chosen. */
private fun OperationReview.depositText(terms: PredictionPayload?, resources: Resources): String? {
    val deposit = depositOf() ?: return null
    val decimals =
        terms?.depositDecimals
            ?: form.fields
                .map { it.kind }
                .filterIsInstance<ParameterKind.Amount>()
                .firstOrNull()
                ?.decimals
            ?: return null
    return amount(deposit, decimals, terms?.depositUnit().orEmpty(), resources)
}

/**
 * "5 USDC on Yes" once both halves of the owner's part are chosen, and null until then. It is the
 * summary on the "your part" card; the headline stays the market's name.
 */
private fun OperationReview.stakeOf(terms: PredictionPayload?, resources: Resources): String? {
    val side = sideOf() ?: return null
    val deposit = depositText(terms, resources) ?: return null
    val sideLabel =
        form.fields
            .map { it.kind }
            .filterIsInstance<ParameterKind.Choice>()
            .flatMap { it.options }
            .firstOrNull { it.key == side }
            ?.let { resources.getString(it.label) }
            ?: resources.getString(
                if (side == PredictionOutcomes.NO) R.string.action_prediction_outcome_no
                else R.string.action_prediction_outcome_yes
            )
    return resources.getString(R.string.prediction_review_stake_on, deposit, sideLabel)
}

/**
 * Everything that went differently from the ordinary review, said before the owner's part. What
 * stopped a quote is not here: it has a card of its own where the quote would be.
 */
internal fun OperationReview.statusBlocks(resources: Resources): List<ReviewSheetInfoBlock> =
    buildList {
        if (standing !is ProposalStanding.Open) {
            add(ReviewSheetInfoBlock(resources.getString(standingText(standing))))
        }
        record.execution?.let {
            add(ReviewSheetInfoBlock(resources.getString(outcomeText(it.outcome))))
        }
        if (!served) add(ReviewSheetInfoBlock(resources.getString(R.string.operation_unsupported)))
        form.problem?.let { add(ReviewSheetInfoBlock(resources.getString(it.message))) }
        problem?.let { add(ReviewSheetInfoBlock(resources.getString(problemText(it)))) }
    }

/**
 * What stopped the quote, said once, with the way on (SEE-180).
 *
 * A refusal for insufficient funds is named as itself, with the amount the owner chose and nothing
 * more: the provider's answer does not say which token ran short or by how much, so neither does
 * this. Its own words and its code stay under the technical details. Trying again prepares from the
 * same inputs for the same wallet; it signs and sends nothing.
 */
internal fun OperationReview.preparationErrorOf(
    stage: SignalStage,
    choosable: Boolean,
    /** The owner's amount as words, "5 USDC", or null before one is chosen. */
    chosen: String?,
    /** Whether the owner's whole part is chosen, so preparing again could go anywhere. */
    retryable: Boolean,
    wording: SignalSheetWording,
    resources: Resources,
): ReviewSheetPreparationError? =
    when (stage) {
        SignalStage.Failed -> {
            val failed = checkNotNull(failure)
            val funds = failed.code == PluginFailureCodes.INSUFFICIENT_FUNDS
            val deposit = chosen
            ReviewSheetPreparationError(
                title =
                    resources.getString(
                        if (funds) R.string.prediction_review_insufficient_funds
                        else R.string.prediction_review_not_prepared
                    ),
                message =
                    if (funds && deposit != null) {
                        resources.getString(wording.insufficientFundsBody, deposit)
                    } else {
                        failed.explanation?.let(resources::getString)
                            ?: resources.getString(R.string.operation_not_prepared_unknown)
                    },
                actionLabel =
                    resources.getString(R.string.prediction_review_retry).takeIf {
                        choosable && retryable
                    },
            )
        }
        SignalStage.Blocked ->
            ReviewSheetPreparationError(
                title = resources.getString(R.string.prediction_review_blocked),
                message =
                    inspection
                        ?.findings
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString("\n") { resources.getString(it.message) }
                        ?: resources.getString(R.string.prediction_review_blocked_unread),
                actionLabel =
                    resources.getString(R.string.prediction_review_new_quote).takeIf { choosable },
            )
        else -> null
    }

internal fun OperationReview.verdictOf(
    verdict: ReviewVerdict?,
    /** The unit a limit is counted in, "USDC" or "SOL", for the one-line warnings. */
    unit: String,
    resources: Resources,
): ReviewSheetVerdict? =
    when (verdict) {
        // No assessment yet, or nothing to say until there is a transaction: no card, rather than
        // a lime one that claims a check that has not happened. No rules has its own neutral line
        // instead of a card (SEE-181).
        null,
        ReviewVerdict.Pending,
        ReviewVerdict.NoRules -> null
        // What passed is what was configured, and nothing else (SEE-181): a check nobody wrote is
        // named as not checked rather than implied by the lime card.
        ReviewVerdict.Within ->
            ReviewSheetVerdict(
                heading = resources.getString(R.string.prediction_verdict_within),
                additionalContext =
                    assessment
                        ?.decision
                        ?.notChecked
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString { resources.getString(checkText(it)).lowercase(Locale.ROOT) }
                        ?.let { resources.getString(R.string.prediction_verdict_not_checked, it) },
            )
        is ReviewVerdict.Warnings ->
            ReviewSheetVerdict(
                warnings =
                    verdict.warnings.map { warning ->
                        val verification = warning.origin == WarningOrigin.Verification
                        ReviewSheetWarning(
                            message =
                                conciseWarning(warning, unit, resources)
                                    ?: listOfNotNull(
                                            resources.getString(warning.message),
                                            warning.detail,
                                        )
                                        .joinToString(" "),
                            sourceLabel =
                                resources.getString(
                                    if (verification) R.string.prediction_rule_check
                                    else
                                        when (warning.source) {
                                            RuleSource.Global -> R.string.prediction_rule_global
                                            RuleSource.ConnectionOverride ->
                                                R.string.prediction_rule_connection
                                            RuleSource.NotConfigured ->
                                                R.string.prediction_rule_none
                                        }
                                ),
                            kind =
                                if (warning.daily) VerdictWarningKind.DailyLimit
                                else VerdictWarningKind.Finding,
                            source =
                                if (verification) ScopeChipSource.Verification
                                else warning.source.chip(),
                        )
                    }
            )
    }

/**
 * An exceeded threshold, said in one line with the amounts that make it so (SEE-181): "Over the
 * global daily limit: 120 of 100 USDC today." The breakdown — confirmed, not yet settled, this
 * order — stays in the limits section. Null for every other warning, which keeps its own words.
 */
private fun OperationReview.conciseWarning(
    warning: ReviewWarning,
    unit: String,
    resources: Resources,
): String? {
    return when (warning.reason) {
        PolicyReason.OverDailyLimit -> {
            val check =
                assessment?.decision?.dailyChecks?.firstOrNull { it.scope == warning.dailyScope }
            val limit = check?.limit ?: return null
            val projected = check.projected ?: return null
            val decimals = checkNotNull(assessment).facts.decimals
            resources.getString(
                when (check.scope) {
                    DailyCheckScope.Global -> R.string.prediction_warning_daily_global
                    DailyCheckScope.Connection -> R.string.prediction_warning_daily_connection
                },
                amount(projected, decimals, unit, resources),
                amount(limit, decimals, unit, resources),
            )
        }
        PolicyReason.OverPerOperationLimit ->
            warning.detail?.let {
                resources.getString(
                    R.string.prediction_warning_per_request,
                    listOf(it, unit).filter(String::isNotBlank).joinToString(" "),
                )
            }
        else -> null
    }
}

/**
 * Limits and checks: the two daily rules, what this phone has read of the transaction, and what it
 * does and does not take on trust. An exceeded limit or any other material warning is also on the
 * verdict card, outside this section, so closing it hides nothing that matters to the decision.
 */
internal fun OperationReview.checksSection(
    stage: SignalStage,
    wording: SignalSheetWording,
    resources: Resources,
): ReviewSheetSection {
    val read = inspection != null
    val daily = assessment?.decision?.dailyChecks.orEmpty()
    val unset = daily.all { it.result.status == PolicyCheckStatus.NotConfigured }
    fun waiting(check: DailyPolicyCheck) =
        !read &&
            check.result.status == PolicyCheckStatus.Unverified &&
            check.result.reason != PolicyReason.DailyTotalUnverified
    val dailySummary =
        when {
            unset -> R.string.prediction_checks_daily_unset
            daily.any { it.result.status == PolicyCheckStatus.Failed } ->
                R.string.prediction_checks_daily_over
            daily.any { waiting(it) } -> R.string.prediction_checks_daily_unchecked
            daily.any { it.result.status == PolicyCheckStatus.Unverified } ->
                R.string.prediction_checks_daily_unverified
            else -> R.string.prediction_checks_daily_within
        }
    val transaction =
        when (stage) {
            SignalStage.Blocked -> R.string.prediction_checks_tx_unread
            SignalStage.Ready,
            SignalStage.Expired -> R.string.prediction_checks_tx_read
            else -> R.string.prediction_checks_tx_pending
        }
    return ReviewSheetSection(
        key = PredictionReviewSections.CHECKS,
        title = resources.getString(R.string.prediction_section_checks),
        summary =
            resources.getString(
                R.string.prediction_checks_summary,
                resources.getString(dailySummary),
                resources.getString(transaction),
            ),
        // Two "not configured" rows say less than one line does.
        dailySpend =
            assessment?.takeIf { !unset }?.let { dailySpendOf(it, read, wording, resources) },
        infoBlocks =
            listOfNotNull(
                ReviewSheetInfoBlock(
                        resources.getString(R.string.prediction_checks_daily_unset_body)
                    )
                    .takeIf { unset },
                ReviewSheetInfoBlock(
                    resources.getString(if (read) wording.checkRead else wording.checkPending)
                ),
            ),
        factRows =
            listOf(
                ReviewSheetFactRow(
                    resources.getString(R.string.prediction_fact_transaction),
                    resources.getString(
                        when (stage) {
                            SignalStage.Preparing -> R.string.prediction_fact_transaction_preparing
                            SignalStage.Blocked -> R.string.prediction_fact_transaction_unread
                            SignalStage.Ready,
                            SignalStage.Expired -> R.string.prediction_fact_transaction_read
                            else -> R.string.prediction_fact_transaction_none
                        }
                    ),
                )
            ),
    )
}

/**
 * About this order and risks: the publisher's own note, what an order is and is not as its provider
 * describes it, and who signs.
 */
internal fun OperationReview.aboutSection(
    zone: ZoneId,
    locale: Locale,
    wording: SignalSheetWording,
    resources: Resources,
): ReviewSheetSection =
    ReviewSheetSection(
        key = PredictionReviewSections.ABOUT,
        title = resources.getString(wording.aboutTitle),
        summary = resources.getString(wording.aboutSummary),
        note =
            record.proposal.note.takeIf(String::isNotBlank)?.let {
                ReviewSheetNote(
                    label = resources.getString(R.string.prediction_review_note_label),
                    body = localTimes(it, zone, locale),
                )
            },
        infoBlocks =
            about
                ?.notes
                .orEmpty()
                .filter { it.topic == ProviderNoteTopic.Order }
                .map {
                    ReviewSheetInfoBlock(resources.getString(it.text, *it.args.toTypedArray()))
                } + ReviewSheetInfoBlock(resources.getString(R.string.operation_about_signer)),
    )

/**
 * The provider and its terms: who carries the order out and under which name (SEE-173), which feed
 * the signal came from and where the market itself comes from — three different parties, each named
 * once — then what the provider says about its fees, availability and relationships, and its
 * official pages.
 */
internal fun OperationReview.providerSection(
    /** Where the market itself comes from, when that is a third party; null for a swap. */
    marketSource: String?,
    source: PredictionReviewSource,
    resources: Resources,
): ReviewSheetSection =
    ReviewSheetSection(
        key = PredictionReviewSections.PROVIDER,
        title = resources.getString(R.string.prediction_section_provider, providerName()),
        summary = resources.getString(R.string.prediction_section_provider_summary),
        infoBlocks =
            about
                ?.notes
                .orEmpty()
                .filter { it.topic == ProviderNoteTopic.Provider }
                .mapIndexed { index, note ->
                    ReviewSheetInfoBlock(
                        body = resources.getString(note.text, *note.args.toTypedArray()),
                        title = about?.name?.takeIf { index == 0 },
                    )
                },
        factRows =
            buildList {
                add(
                    ReviewSheetFactRow(
                        resources.getString(R.string.prediction_fact_from),
                        source.name,
                    )
                )
                about?.let {
                    add(
                        ReviewSheetFactRow(
                            resources.getString(it.role),
                            it.name.substringBefore(" · "),
                        )
                    )
                }
                marketSource?.takeIf(String::isNotBlank)?.let {
                    add(
                        ReviewSheetFactRow(
                            resources.getString(R.string.prediction_fact_market_source),
                            it.humanName(),
                        )
                    )
                }
                about?.links?.forEach { add(linkRow(it.label, it.url, resources)) }
            },
    )

/**
 * Technical details: the signal's own identifiers, the accounts this phone read out of the
 * transaction, the quote's and the provider's figures, where to look the market up, and — when
 * nothing was prepared — exactly what the provider said. Identifiers copy in full and links open.
 */
internal fun OperationReview.technicalSection(
    factRows: List<ReviewSheetFactRow>,
    wording: SignalSheetWording,
    resources: Resources,
): ReviewSheetSection =
    ReviewSheetSection(
        key = PredictionReviewSections.TECHNICAL,
        title = resources.getString(R.string.prediction_section_technical),
        summary = resources.getString(wording.technicalSummary),
        infoBlocks = diagnosticsOf(resources),
        factRows = factRows,
    )

/**
 * What stopped a preparation, in full: what it means, then exactly what the provider said. A
 * provider's message can be any length, so it wraps rather than sharing a line with its label.
 */
private fun OperationReview.diagnosticsOf(resources: Resources): List<ReviewSheetInfoBlock> {
    val failed = failure ?: return emptyList()
    return listOfNotNull(
        failed.explanation?.let {
            ReviewSheetInfoBlock(
                body = resources.getString(it),
                title = resources.getString(R.string.prediction_fact_failure_explanation),
            )
        },
        failed.detail?.takeIf(String::isNotEmpty)?.let {
            ReviewSheetInfoBlock(
                body = it,
                title = resources.getString(R.string.prediction_fact_failure_said),
            )
        },
    )
}

private fun linkRow(label: Int, url: String, resources: Resources) =
    ReviewSheetFactRow(
        label = resources.getString(label),
        value = resources.getString(R.string.prediction_fact_open),
        valueStyle = FactRowValueStyle.Link,
        link = url,
    )

private fun dailySpendOf(
    assessment: RequestAssessment,
    read: Boolean,
    wording: SignalSheetWording,
    resources: Resources,
): ReviewSheetDailySpend? {
    val checks = assessment.decision.dailyChecks.takeIf { it.isNotEmpty() } ?: return null
    return ReviewSheetDailySpend(
        title = resources.getString(R.string.policy_daily_title),
        rows = checks.map { it.row(assessment.facts.decimals, read, wording, resources) },
    )
}

private fun DailyPolicyCheck.row(
    decimals: Int,
    read: Boolean,
    wording: SignalSheetWording,
    resources: Resources,
): ReviewSheetDailySpendRow {
    val scopeName =
        resources.getString(
            when (scope) {
                DailyCheckScope.Global -> R.string.policy_daily_global
                DailyCheckScope.Connection -> R.string.policy_daily_connection
            }
        )
    val totals =
        total
            ?.let { total -> projected?.let { total to it } }
            ?.let { (total, projected) ->
                resources.getString(
                    R.string.policy_daily_totals,
                    formatBaseUnits(total.confirmed, decimals),
                    formatBaseUnits(total.unresolved, decimals),
                    formatBaseUnits(projected, decimals),
                )
            }
    // A limit that could not be applied only because there is no amount yet is waiting, not over.
    val waiting =
        !read &&
            result.status == PolicyCheckStatus.Unverified &&
            result.reason != PolicyReason.DailyTotalUnverified
    return ReviewSheetDailySpendRow(
        headline =
            if (waiting) resources.getString(wording.dailyAfterQuote)
            else result.statusLine(resources),
        supportingText = listOfNotNull(scopeName, totals).joinToString(". "),
        state =
            when {
                waiting -> DailyLimitRowState.NoLimit
                else ->
                    when (result.status) {
                        PolicyCheckStatus.Passed -> DailyLimitRowState.Within
                        PolicyCheckStatus.Failed,
                        PolicyCheckStatus.Unverified -> DailyLimitRowState.Over
                        PolicyCheckStatus.NotConfigured -> DailyLimitRowState.NoLimit
                    }
            },
        scope =
            when (result.source) {
                RuleSource.Global -> DailyLimitRowScope.Global
                RuleSource.ConnectionOverride -> DailyLimitRowScope.Connection
                RuleSource.NotConfigured -> DailyLimitRowScope.None
            },
    )
}

private fun io.github.brrenat.seekervault.policy.PolicyCheckResult.statusLine(
    resources: Resources
): String {
    val detail = detail
    return when (status) {
        PolicyCheckStatus.Passed ->
            if (detail == null) resources.getString(R.string.policy_status_passed_plain)
            else resources.getString(R.string.policy_status_passed, detail)
        PolicyCheckStatus.Failed ->
            if (detail == null) resources.getString(R.string.policy_status_failed_plain)
            else resources.getString(R.string.policy_status_failed, detail)
        PolicyCheckStatus.Unverified ->
            if (detail == null) resources.getString(R.string.policy_status_unverified_plain)
            else resources.getString(R.string.policy_status_unverified, detail)
        PolicyCheckStatus.NotConfigured ->
            resources.getString(R.string.policy_status_not_configured)
    }
}

/**
 * The technical rows: every term the publisher sent under a label rather than its key, the accounts
 * this phone read out of the transaction, the provider's supporting figures, what stopped a
 * preparation, then the signal's own identity.
 */
private fun OperationReview.factsOf(
    terms: PredictionPayload?,
    resources: Resources,
): List<ReviewSheetFactRow> = buildList {
    val proposal = record.proposal
    fun plain(label: Int, value: String) = add(plainRow(label, value, resources))
    fun mono(label: Int, value: String, copy: Boolean = false) =
        add(monoRow(label, value, resources, copy))

    val read = terms != null
    mono(R.string.prediction_fact_action, proposal.action.value)
    if (terms != null) {
        terms.eventId.takeIf(String::isNotBlank)?.let {
            mono(R.string.prediction_fact_event, it, copy = true)
        }
        mono(R.string.prediction_fact_market, terms.marketId, copy = true)
        terms.depositSymbol.takeIf(String::isNotBlank)?.let {
            plain(R.string.prediction_fact_asset, it)
        }
        mono(R.string.prediction_fact_asset_mint, terms.depositMint, copy = true)
        plain(R.string.prediction_fact_asset_decimals, terms.depositDecimals.toString())
        val unit = terms.depositUnit()
        if (proposal.value(PredictionTermNames.LEAST_DEPOSIT) != null) {
            plain(
                R.string.prediction_fact_least_deposit,
                amount(terms.leastDeposit, terms.depositDecimals, unit, resources),
            )
        }
        terms.mostDeposit?.let {
            plain(
                R.string.prediction_fact_most_deposit,
                amount(it, terms.depositDecimals, unit, resources),
            )
        }
    }
    // Anything the publisher said that this build does not read as a prediction term, still shown
    // once, under a label made from its name rather than the name itself.
    proposal.values
        .filter { !read || it.key !in KnownTerms }
        .forEach { add(ReviewSheetFactRow(it.key.humanName(), it.text)) }
    addAll(
        transactionFacts(resources) {
            it.key == PredictionTermNames.MARKET_ID && it.value == terms?.marketId
        }
    )
}

internal fun plainRow(label: Int, value: String, resources: Resources) =
    ReviewSheetFactRow(resources.getString(label), value)

internal fun monoRow(label: Int, value: String, resources: Resources, copy: Boolean = false) =
    ReviewSheetFactRow(
        label = resources.getString(label),
        value = value.middle(),
        valueStyle = FactRowValueStyle.Mono,
        copyValue = value.takeIf { copy },
    )

/**
 * The rows every signal's technical section ends with: what this phone read out of the transaction,
 * the provider's figures, what stopped a preparation, where to look, and the signal's own identity.
 * A reference [shown] elsewhere already is left out.
 */
internal fun OperationReview.transactionFacts(
    resources: Resources,
    shown: (io.github.brrenat.seekervault.plugins.PluginReference) -> Boolean = { false },
): List<ReviewSheetFactRow> = buildList {
    val proposal = record.proposal
    fun plain(label: Int, value: String) = add(plainRow(label, value, resources))
    fun mono(label: Int, value: String, copy: Boolean = false) =
        add(monoRow(label, value, resources, copy))

    // The wallet chosen is on the owner's part card; here is the one the bytes say pays and signs
    // (SEE-174), and who receives the stake.
    inspection?.facts?.wallet?.let { mono(R.string.prediction_fact_payer, it, copy = true) }
    inspection?.facts?.recipient?.let { mono(R.string.prediction_fact_receives, it, copy = true) }
    inspection?.references?.filterNot(shown)?.forEach {
        add(
            ReviewSheetFactRow(
                label = it.key.humanName(),
                value = it.value.middle(),
                valueStyle = FactRowValueStyle.Mono,
                copyValue = it.value,
            )
        )
    }
    // The quote's figures — what the order costs and what it buys — and the provider's supporting
    // ones. There is no quote card on the sheet: the owner's part already says what is spent, and
    // these are here to check it against.
    inspection?.details?.forEach {
        add(ReviewSheetFactRow(resources.getString(it.label), it.value))
    }
    details.forEach { add(ReviewSheetFactRow(resources.getString(it.label), it.value)) }
    // What stopped a preparation, in full: the card above says it once and plainly.
    // Only the code is a short value; the explanation and the provider's own words are prose of
    // any length, and are blocks that wrap above these rows.
    failure?.let { failed -> plain(R.string.prediction_fact_failure_code, failed.code) }
    // Where the market is on the provider's own site or app, as history shows it once an order is
    // placed (SEE-157), so the owner can look before approving too.
    destinations.forEach { add(linkRow(it.label, it.url, resources)) }
    mono(R.string.prediction_fact_proposal, proposal.key.proposalId, copy = true)
    mono(R.string.prediction_fact_publisher, proposal.key.serverId, copy = true)
    plain(R.string.prediction_fact_revision, proposal.revision.toString())
}

private val KnownTerms =
    setOf(
        PredictionTermNames.MARKET_ID,
        PredictionTermNames.EVENT_ID,
        PredictionTermNames.PROVIDER,
        PredictionTermNames.DEPOSIT_MINT,
        PredictionTermNames.DEPOSIT_DECIMALS,
        PredictionTermNames.DEPOSIT_SYMBOL,
        PredictionTermNames.LEAST_DEPOSIT,
        PredictionTermNames.MOST_DEPOSIT,
    )

/** The execution provider as a name, from its identifier. */
internal fun OperationReview.providerName(): String =
    (record.proposal.provider?.value ?: record.proposal.plugin.value.substringBefore('.'))
        .humanName()

private fun RuleSource.chip(): ScopeChipSource =
    when (this) {
        RuleSource.Global -> ScopeChipSource.Global
        RuleSource.ConnectionOverride -> ScopeChipSource.Connection
        RuleSource.NotConfigured -> ScopeChipSource.None
    }

internal fun amount(baseUnits: ULong, decimals: Int, unit: String, resources: Resources): String =
    if (unit.isBlank()) formatBaseUnits(baseUnits, decimals)
    else
        resources.getString(
            R.string.prediction_review_amount,
            formatBaseUnits(baseUnits, decimals),
            unit,
        )

/**
 * A key or a venue as words: `order_account` → "Order account", `polymarket` → "Polymarket". Only
 * the first word is capitalized, the way every other label on the sheet is written.
 */
internal fun String.humanName(): String =
    split('_', '-', '.', ' ')
        .filter(String::isNotBlank)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
        .replaceFirstChar { it.titlecase(Locale.ROOT) }

/**
 * An address or identifier with both ends kept: eight characters, an ellipsis, eight characters.
 * Cutting only the end loses the part people check; this keeps the part they check at both ends.
 */
internal fun String.middle(): String =
    if (length <= MIDDLE_KEEP * 2 + 1) this else take(MIDDLE_KEEP) + "…" + takeLast(MIDDLE_KEEP)

private const val MIDDLE_KEEP = 8

/** "Oct 2, 10:05 PM" in the phone's own zone. */
internal fun shortTime(instant: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMdjmm"), locale)
        .withZone(zone)
        .format(instant)

/** "Oct 2, 2026, 10:05 PM" in the phone's own zone. */
internal fun longTime(instant: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone)
        .format(instant)

private val IsoInstant =
    Regex("""\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})""")

/**
 * The publisher's words with every ISO timestamp in them rewritten as a time in the phone's zone.
 * The words are still theirs; only the machine spelling of a moment is made readable.
 */
internal fun localTimes(text: String, zone: ZoneId, locale: Locale): String =
    IsoInstant.replace(text) { match ->
        runCatching {
                shortTime(
                    java.time.OffsetDateTime.parse(match.value).toInstant(),
                    zone,
                    locale,
                )
            }
            .getOrDefault(match.value)
    }
