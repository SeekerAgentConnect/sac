package io.github.brrenat.seekervault.operations

import android.content.res.Resources
import android.text.format.DateFormat
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.DailyLimitRowScope
import io.github.brrenat.seekervault.designsystem.DailyLimitRowState
import io.github.brrenat.seekervault.designsystem.EnvChipEnvironment
import io.github.brrenat.seekervault.designsystem.EnvChipVerbosity
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
import io.github.brrenat.seekervault.designsystem.ReviewSheetTerms
import io.github.brrenat.seekervault.designsystem.ReviewSheetVerdict
import io.github.brrenat.seekervault.designsystem.ReviewSheetWarning
import io.github.brrenat.seekervault.designsystem.ReviewSheetYourPart
import io.github.brrenat.seekervault.designsystem.ScopeChipSource
import io.github.brrenat.seekervault.designsystem.SourceColour
import io.github.brrenat.seekervault.designsystem.TermsCardKind
import io.github.brrenat.seekervault.designsystem.TermsCardRow
import io.github.brrenat.seekervault.designsystem.VerdictWarningKind
import io.github.brrenat.seekervault.inbox.RequestAssessment
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFailureCodes
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
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.executable
import io.github.brrenat.seekervault.reviews.ReviewVerdict
import io.github.brrenat.seekervault.reviews.WarningOrigin
import io.github.brrenat.seekervault.reviews.reviewVerdict
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Where a signal came from, as the owner named and coloured the connection. */
data class PredictionReviewSource(val name: String, val colour: SourceColour? = null)

/**
 * Where a prediction review stands between choosing and approving (SEE-180).
 *
 * Each stage says one thing in the sheet. Telling them apart is what keeps "nothing has been
 * prepared yet" from reading as "this phone could not account for the transaction": the first is
 * the absence of a transaction, the second a finding about one. Nothing here relaxes a gate — the
 * evaluation stays as conservative as it was, and only what the sheet says about it changes.
 */
internal enum class PredictionStage {
    /** The side, the amount or both are still to be chosen. */
    Unchosen,
    /** Both are chosen and nothing is prepared for them: the inputs or the wallet moved on. */
    NeedsQuote,
    Preparing,
    /** Nothing was prepared, and the provider or the plugin said why. */
    Failed,
    /** Bytes arrived and this phone could not account for them. Never approvable. */
    Blocked,
    /** The quote ran out. */
    Expired,
    /** Prepared, read in full and current: the owner decides. */
    Ready,
}

internal fun OperationReview.predictionStage(now: Instant): PredictionStage {
    val prepared = prepared
    return when {
        preparing -> PredictionStage.Preparing
        failure != null -> PredictionStage.Failed
        prepared != null && inspection?.approvable != true -> PredictionStage.Blocked
        prepared != null && prepared.expiresAtEpochSeconds?.let { now.epochSecond >= it } == true ->
            PredictionStage.Expired
        prepared != null -> PredictionStage.Ready
        sideOf() == null || depositOf() == null -> PredictionStage.Unchosen
        else -> PredictionStage.NeedsQuote
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
 * What the owner decides on is open: the market, where the order goes, their side and amount, the
 * verdict, the quote or what stopped it, and the wallet. Everything behind that sits in four
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
    val proposal = record.proposal
    val terms = (payload as? ActionPayload.PredictionBuy)?.payload
    val sandbox = environment == PluginEnvironment.Sandbox
    val executed = record.execution != null
    val choosable = standing.executable && served && form.problem == null && !executed
    val stage = predictionStage(now)
    val stake = stakeOf(terms, resources)
    // Before there is a transaction, its absence is not a finding (SEE-180).
    val verdict = assessment?.decision?.reviewVerdict(prepared = inspection != null)
    val warnings = verdict as? ReviewVerdict.Warnings
    // The design draws "no rules" as orange with a row of its own, and asks for the same
    // deliberate tick before approving as any other warning. The tick is only ever asked for a
    // transaction that could be approved: a warning about nothing yet, or about bytes that are
    // refused anyway, is read and not consented to.
    val needsAcknowledging =
        stage == PredictionStage.Ready &&
            (verdict is ReviewVerdict.Warnings || verdict == ReviewVerdict.NoRules)
    val approvable = stage == PredictionStage.Ready && standing.executable && !executed && !sending
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
        headerChips =
            buildList {
                add(ReviewSheetHeaderChip.Signal)
                add(ReviewSheetHeaderChip.Feed(source.name, source.colour))
                if (sandbox) {
                    add(
                        ReviewSheetHeaderChip.Environment(
                            EnvChipEnvironment.Sandbox,
                            EnvChipVerbosity.Short,
                        )
                    )
                }
                when (wallet?.network) {
                    WalletNetwork.Mainnet ->
                        add(ReviewSheetHeaderChip.Network(NetworkChipNetwork.Mainnet))
                    WalletNetwork.Devnet ->
                        // Only mainnet is neutral here: a test network is flagged in the
                        // sandbox orange, as the ticket's chip row asks (SEE-158).
                        add(
                            ReviewSheetHeaderChip.Network(NetworkChipNetwork.Devnet, flagged = true)
                        )
                    WalletNetwork.Testnet,
                    null -> Unit
                }
            },
        sandboxNotice =
            if (sandbox) resources.getString(R.string.prediction_sandbox_notice) else null,
        statusBlocks = statusBlocks(resources),
        yourPart =
            if (choosable) {
                ReviewSheetYourPart(
                    kind = OwnerInputCardKind.Prediction,
                    state =
                        if (stake == null) OwnerInputCardState.Unchosen
                        else OwnerInputCardState.Chosen,
                    summary = stake,
                )
            } else null,
        verdict = verdictOf(verdict, resources),
        staleQuote =
            when {
                !choosable -> null
                stage == PredictionStage.Expired ->
                    ReviewSheetStaleQuote(
                        message = resources.getString(R.string.prediction_review_stale),
                        actionLabel = resources.getString(R.string.prediction_review_refresh),
                    )
                stage == PredictionStage.NeedsQuote ->
                    ReviewSheetStaleQuote(
                        message =
                            resources.getString(
                                R.string.prediction_review_needs_quote,
                                stake.orEmpty(),
                            ),
                        actionLabel = resources.getString(R.string.prediction_review_get_quote),
                    )
                else -> null
            },
        preparationError = preparationErrorOf(stage, choosable, terms, resources),
        terms =
            when (stage) {
                // Each of these has its own card in place of the quote, and the quote card would
                // only repeat a placeholder that is no longer true.
                PredictionStage.NeedsQuote,
                PredictionStage.Failed,
                PredictionStage.Blocked -> null
                else -> if (choosable || inspection != null) termsOf(resources) else null
            },
        // The wallet stays in view: with several profiles, which one pays is part of the decision
        // (SEE-174).
        factRows = listOfNotNull(walletRow(wallet, resources)),
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
                label =
                    resources.getString(
                        if (sandbox) R.string.prediction_simulate else R.string.prediction_approve
                    ),
                enabled = approvable,
            ),
        secondaryAction =
            ReviewSheetAction(
                label = resources.getString(R.string.prediction_dismiss),
                enabled = record.dismissed == null && !executed && !sending,
            ),
        footerCaption = resources.getString(R.string.prediction_dismiss_note, source.name),
        // Placed, dismissed, withdrawn or expired: a signal in History is read, not answered, so
        // the decision footer goes with it.
        actionsShown = standing is ProposalStanding.Open,
        sections =
            listOf(
                checksSection(stage, sandbox, resources),
                aboutSection(zone, locale, resources),
                providerSection(terms, source, resources),
                technicalSection(stage, terms, wallet, resources),
            ),
    )
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
private fun OperationReview.statusBlocks(resources: Resources): List<ReviewSheetInfoBlock> =
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
private fun OperationReview.preparationErrorOf(
    stage: PredictionStage,
    choosable: Boolean,
    terms: PredictionPayload?,
    resources: Resources,
): ReviewSheetPreparationError? =
    when (stage) {
        PredictionStage.Failed -> {
            val failed = checkNotNull(failure)
            val funds = failed.code == PluginFailureCodes.INSUFFICIENT_FUNDS
            val deposit = depositText(terms, resources)
            ReviewSheetPreparationError(
                title =
                    resources.getString(
                        if (funds) R.string.prediction_review_insufficient_funds
                        else R.string.prediction_review_not_prepared
                    ),
                message =
                    if (funds && deposit != null) {
                        resources.getString(
                            R.string.prediction_review_insufficient_funds_body,
                            deposit,
                        )
                    } else {
                        failed.explanation?.let(resources::getString)
                            ?: resources.getString(R.string.operation_not_prepared_unknown)
                    },
                actionLabel =
                    resources.getString(R.string.prediction_review_retry).takeIf {
                        choosable && sideOf() != null && depositOf() != null
                    },
            )
        }
        PredictionStage.Blocked ->
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

private fun verdictOf(verdict: ReviewVerdict?, resources: Resources): ReviewSheetVerdict? =
    when (verdict) {
        // No assessment yet, or nothing to say until there is a transaction: no card, rather than
        // a lime one that claims a check that has not happened.
        null,
        ReviewVerdict.Pending -> null
        ReviewVerdict.Within -> ReviewSheetVerdict()
        ReviewVerdict.NoRules ->
            ReviewSheetVerdict(
                heading = resources.getString(R.string.prediction_verdict_no_rules),
                warnings =
                    listOf(
                        ReviewSheetWarning(
                            message = resources.getString(R.string.transfer_rules_no_policy_detail),
                            sourceLabel = resources.getString(R.string.prediction_rule_connection),
                            source = ScopeChipSource.Connection,
                        )
                    ),
            )
        is ReviewVerdict.Warnings ->
            ReviewSheetVerdict(
                warnings =
                    verdict.warnings.map { warning ->
                        val verification = warning.origin == WarningOrigin.Verification
                        ReviewSheetWarning(
                            message =
                                listOfNotNull(resources.getString(warning.message), warning.detail)
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

/** The quote: what is spent, what it buys, what it costs and what it could pay out. */
private fun OperationReview.termsOf(resources: Resources): ReviewSheetTerms {
    val found = inspection
    val unit = (payload as? ActionPayload.PredictionBuy)?.payload?.depositUnit().orEmpty()
    val rows =
        if (found == null) emptyList()
        else
            buildList {
                found.facts?.let { facts ->
                    add(
                        TermsCardRow(
                            resources.getString(R.string.operation_fact_spends),
                            amount(facts.amount ?: 0UL, facts.decimals, unit, resources),
                        )
                    )
                }
                found.details
                    .filterNot { it.technical }
                    .forEach { add(TermsCardRow(resources.getString(it.label), it.value)) }
                details.forEach { add(TermsCardRow(resources.getString(it.label), it.value)) }
            }
    return ReviewSheetTerms(
        rows = rows,
        kind = TermsCardKind.Prediction,
        title = resources.getString(R.string.prediction_review_terms_title),
        emptyText =
            resources.getString(
                if (preparing) R.string.prediction_review_terms_fetching
                else R.string.prediction_review_terms_empty
            ),
    )
}

/** The wallet that pays, as read out of the bytes once there are any. */
private fun OperationReview.walletRow(
    wallet: SelectedWallet?,
    resources: Resources,
): ReviewSheetFactRow? {
    val payer = inspection?.facts?.wallet
    val (label, address) =
        when {
            payer != null -> R.string.prediction_fact_payer to payer
            wallet != null -> R.string.prediction_fact_wallet to wallet.address
            else -> return null
        }
    return ReviewSheetFactRow(
        label = resources.getString(label),
        value = address.middle(),
        valueStyle = FactRowValueStyle.Mono,
        copyValue = address,
    )
}

/**
 * Limits and checks: the two daily rules, what this phone has read of the transaction, and what it
 * does and does not take on trust. An exceeded limit or any other material warning is also on the
 * verdict card, outside this section, so closing it hides nothing that matters to the decision.
 */
private fun OperationReview.checksSection(
    stage: PredictionStage,
    sandbox: Boolean,
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
            PredictionStage.Blocked -> R.string.prediction_checks_tx_unread
            PredictionStage.Ready,
            PredictionStage.Expired -> R.string.prediction_checks_tx_read
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
            assessment?.takeIf { !unset }?.let { dailySpendOf(it, sandbox, read, resources) },
        infoBlocks =
            listOfNotNull(
                ReviewSheetInfoBlock(
                        resources.getString(R.string.prediction_checks_daily_unset_body)
                    )
                    .takeIf { unset },
                ReviewSheetInfoBlock(
                    resources.getString(
                        if (read) R.string.prediction_review_check
                        else R.string.prediction_review_check_pending
                    )
                ),
            ),
        factRows =
            listOf(
                ReviewSheetFactRow(
                    resources.getString(R.string.prediction_fact_transaction),
                    resources.getString(
                        when (stage) {
                            PredictionStage.Preparing ->
                                R.string.prediction_fact_transaction_preparing
                            PredictionStage.Blocked -> R.string.prediction_fact_transaction_unread
                            PredictionStage.Ready,
                            PredictionStage.Expired -> R.string.prediction_fact_transaction_read
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
private fun OperationReview.aboutSection(
    zone: ZoneId,
    locale: Locale,
    resources: Resources,
): ReviewSheetSection =
    ReviewSheetSection(
        key = PredictionReviewSections.ABOUT,
        title = resources.getString(R.string.prediction_section_about),
        summary = resources.getString(R.string.prediction_section_about_summary),
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
private fun OperationReview.providerSection(
    terms: PredictionPayload?,
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
                terms?.marketProvider?.takeIf(String::isNotBlank)?.let {
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
 * transaction, the provider's supporting figures, where to look the market up, and — when nothing
 * was prepared — exactly what the provider said. Identifiers copy in full and links open.
 */
private fun OperationReview.technicalSection(
    stage: PredictionStage,
    terms: PredictionPayload?,
    wallet: SelectedWallet?,
    resources: Resources,
): ReviewSheetSection =
    ReviewSheetSection(
        key = PredictionReviewSections.TECHNICAL,
        title = resources.getString(R.string.prediction_section_technical),
        summary = resources.getString(R.string.prediction_section_technical_summary),
        factRows = factsOf(stage, terms, wallet, resources),
    )

private fun linkRow(label: Int, url: String, resources: Resources) =
    ReviewSheetFactRow(
        label = resources.getString(label),
        value = resources.getString(R.string.prediction_fact_open),
        valueStyle = FactRowValueStyle.Link,
        link = url,
    )

private fun dailySpendOf(
    assessment: RequestAssessment,
    sandbox: Boolean,
    read: Boolean,
    resources: Resources,
): ReviewSheetDailySpend? {
    val checks = assessment.decision.dailyChecks.takeIf { it.isNotEmpty() } ?: return null
    return ReviewSheetDailySpend(
        title =
            resources.getString(
                if (sandbox) R.string.prediction_daily_title_sandbox
                else R.string.policy_daily_title
            ),
        rows = checks.map { it.row(assessment.facts.decimals, read, resources) },
    )
}

private fun DailyPolicyCheck.row(
    decimals: Int,
    read: Boolean,
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
            if (waiting) resources.getString(R.string.prediction_daily_after_quote)
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
    stage: PredictionStage,
    terms: PredictionPayload?,
    wallet: SelectedWallet?,
    resources: Resources,
): List<ReviewSheetFactRow> = buildList {
    val proposal = record.proposal
    fun plain(label: Int, value: String) =
        add(ReviewSheetFactRow(resources.getString(label), value))
    fun mono(label: Int, value: String, copy: Boolean = false) =
        add(
            ReviewSheetFactRow(
                label = resources.getString(label),
                value = value.middle(),
                valueStyle = FactRowValueStyle.Mono,
                copyValue = value.takeIf { copy },
            )
        )

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

    // The wallet itself is in view above; here is who receives the stake.
    inspection?.facts?.recipient?.let { mono(R.string.prediction_fact_receives, it, copy = true) }
    inspection
        ?.references
        ?.filterNot { it.key == PredictionTermNames.MARKET_ID && it.value == terms?.marketId }
        ?.forEach {
            add(
                ReviewSheetFactRow(
                    label = it.key.humanName(),
                    value = it.value.middle(),
                    valueStyle = FactRowValueStyle.Mono,
                    copyValue = it.value,
                )
            )
        }
    // The provider's supporting figures; a transaction that is refused shows all of its figures
    // here, because none of them is a quote anybody could approve.
    inspection
        ?.details
        ?.filter { it.technical || stage == PredictionStage.Blocked }
        ?.forEach { add(ReviewSheetFactRow(resources.getString(it.label), it.value)) }
    // What stopped a preparation, in full: the card above says it once and plainly.
    failure?.let { failed ->
        plain(R.string.prediction_fact_failure_code, failed.code)
        failed.explanation?.let {
            plain(R.string.prediction_fact_failure_explanation, resources.getString(it))
        }
        failed.detail?.takeIf(String::isNotEmpty)?.let {
            plain(R.string.prediction_fact_failure_said, it)
        }
    }
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
private fun OperationReview.providerName(): String =
    (record.proposal.provider?.value ?: record.proposal.plugin.value.substringBefore('.'))
        .humanName()

private fun RuleSource.chip(): ScopeChipSource =
    when (this) {
        RuleSource.Global -> ScopeChipSource.Global
        RuleSource.ConnectionOverride -> ScopeChipSource.Connection
        RuleSource.NotConfigured -> ScopeChipSource.None
    }

private fun amount(baseUnits: ULong, decimals: Int, unit: String, resources: Resources): String =
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
