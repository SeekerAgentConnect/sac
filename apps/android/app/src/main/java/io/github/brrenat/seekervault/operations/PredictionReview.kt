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
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionTermNames
import io.github.brrenat.seekervault.plugins.actions.depositUnit
import io.github.brrenat.seekervault.policy.DailyCheckScope
import io.github.brrenat.seekervault.policy.DailyPolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.RuleSource
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.executable
import io.github.brrenat.seekervault.reviews.ReviewVerdict
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
 * A `prediction.buy` signal, read for the review sheet the design draws (SEE-158).
 *
 * Everything the old column showed is still here, and each value once: the publisher's terms as
 * labelled facts rather than their wire keys, amounts in whole tokens with their unit rather than
 * base units, times in the phone's own zone rather than ISO strings, and addresses shortened at the
 * middle so both ends stay readable. What changes is where each thing sits, which is the design's
 * order: who, what, your part, the verdict, the quote, then the facts behind them.
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
    val stake = stakeOf(terms, resources)
    val verdict = assessment?.decision?.reviewVerdict()
    val warnings = verdict as? ReviewVerdict.Warnings
    // The design draws "no rules" as orange with a row of its own, and asks for the same
    // deliberate tick before approving as any other warning.
    val needsAcknowledging = verdict is ReviewVerdict.Warnings || verdict == ReviewVerdict.NoRules
    val stale = prepared?.expiresAtEpochSeconds?.let { now.epochSecond >= it } == true
    val approvable =
        inspection?.approvable == true &&
            standing.executable &&
            !executed &&
            !stale &&
            !preparing &&
            !sending
    val closing = shortTime(proposal.expiresAt, zone, locale)

    return ReviewSheetState(
        title = resources.getString(R.string.prediction_review_title),
        // The market stays the headline; the owner's side and stake are on the "your part" card
        // (SEE-160).
        headline = proposal.title.ifBlank { terms?.marketId ?: proposal.key.proposalId },
        subline =
            resources.getString(
                R.string.prediction_review_subline,
                resources.getString(R.string.prediction_review_provider, providerName()),
                terms?.marketId ?: proposal.value(PredictionTermNames.MARKET_ID).orEmpty(),
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
            if (stale && choosable) {
                ReviewSheetStaleQuote(
                    message = resources.getString(R.string.prediction_review_stale),
                    actionLabel = resources.getString(R.string.prediction_review_refresh),
                )
            } else null,
        terms = if (choosable || inspection != null) termsOf(resources) else null,
        dailySpend = assessment?.let { dailySpendOf(it, sandbox, resources) },
        infoBlocks =
            listOf(ReviewSheetInfoBlock(resources.getString(R.string.prediction_review_check))) +
                aboutBlocks(resources),
        factRows = factsOf(terms, source, wallet, resources),
        note =
            proposal.note.takeIf(String::isNotBlank)?.let {
                ReviewSheetNote(
                    label = resources.getString(R.string.prediction_review_note_label),
                    body = localTimes(it, zone, locale),
                )
            },
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
        // the
        // decision footer goes with it.
        actionsShown = standing is ProposalStanding.Open,
    )
}

/**
 * "5 USDC on Yes" once both halves of the owner's part are chosen, and null until then. It is the
 * summary on the "your part" card; the headline stays the market's name.
 */
private fun OperationReview.stakeOf(terms: PredictionPayload?, resources: Resources): String? {
    val side =
        (choice.values[PredictionParameterNames.OUTCOME] as? ParameterValue.Selected)?.option
            ?: return null
    val deposit =
        (choice.values[PredictionParameterNames.DEPOSIT] as? ParameterValue.Amount)?.baseUnits
            ?: return null
    val decimals =
        terms?.depositDecimals
            ?: form.fields
                .map { it.kind }
                .filterIsInstance<ParameterKind.Amount>()
                .firstOrNull()
                ?.decimals
            ?: return null
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
    return resources.getString(
        R.string.prediction_review_stake_on,
        amount(deposit, decimals, terms?.depositUnit().orEmpty(), resources),
        sideLabel,
    )
}

/** Everything that went differently from the ordinary review, said before the owner's part. */
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
        failure?.let {
            val said =
                it.detail?.takeIf(String::isNotEmpty)?.let { detail ->
                    resources.getString(R.string.operation_provider_said, detail)
                }
            add(
                ReviewSheetInfoBlock(
                    title = resources.getString(R.string.prediction_review_not_prepared),
                    body =
                        listOfNotNull(
                                it.explanation?.let(resources::getString)
                                    ?: resources.getString(R.string.operation_not_prepared_unknown),
                                said,
                            )
                            .joinToString("\n"),
                )
            )
        }
        inspection
            ?.findings
            ?.takeIf { it.isNotEmpty() }
            ?.let { findings ->
                add(
                    ReviewSheetInfoBlock(
                        title = resources.getString(R.string.prediction_review_findings),
                        body = findings.joinToString("\n") { resources.getString(it.message) },
                    )
                )
            }
        problem?.let { add(ReviewSheetInfoBlock(resources.getString(problemText(it)))) }
    }

private fun verdictOf(verdict: ReviewVerdict?, resources: Resources): ReviewSheetVerdict =
    when (verdict) {
        null ->
            ReviewSheetVerdict(heading = resources.getString(R.string.prediction_verdict_pending))
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
                        ReviewSheetWarning(
                            message =
                                listOfNotNull(resources.getString(warning.message), warning.detail)
                                    .joinToString(" "),
                            sourceLabel =
                                resources.getString(
                                    when (warning.source) {
                                        RuleSource.Global -> R.string.prediction_rule_global
                                        RuleSource.ConnectionOverride ->
                                            R.string.prediction_rule_connection
                                        RuleSource.NotConfigured -> R.string.prediction_rule_none
                                    }
                                ),
                            kind =
                                if (warning.daily) VerdictWarningKind.DailyLimit
                                else VerdictWarningKind.Finding,
                            source = warning.source.chip(),
                        )
                    }
            )
    }

/** The quoted operation, or the one line saying what a quote will show. */
private fun OperationReview.termsOf(resources: Resources): ReviewSheetTerms {
    val found = inspection
    val unit = (payload as? ActionPayload.PredictionBuy)?.payload?.depositUnit().orEmpty()
    val rows =
        if (found == null) emptyList()
        else
            buildList {
                details.forEach { add(TermsCardRow(resources.getString(it.label), it.value)) }
                found.facts?.let { facts ->
                    add(
                        TermsCardRow(
                            resources.getString(R.string.operation_fact_spends),
                            amount(facts.amount ?: 0UL, facts.decimals, unit, resources),
                        )
                    )
                }
                found.details.forEach { add(TermsCardRow(resources.getString(it.label), it.value)) }
            }
    return ReviewSheetTerms(
        rows = rows,
        kind = TermsCardKind.Prediction,
        emptyText =
            resources.getString(
                if (preparing) R.string.prediction_review_terms_fetching
                else R.string.prediction_review_terms_empty
            ),
    )
}

private fun dailySpendOf(
    assessment: RequestAssessment,
    sandbox: Boolean,
    resources: Resources,
): ReviewSheetDailySpend? {
    val checks = assessment.decision.dailyChecks.takeIf { it.isNotEmpty() } ?: return null
    return ReviewSheetDailySpend(
        title =
            resources.getString(
                if (sandbox) R.string.prediction_daily_title_sandbox
                else R.string.policy_daily_title
            ),
        rows = checks.map { it.row(assessment.facts.decimals, resources) },
    )
}

private fun DailyPolicyCheck.row(decimals: Int, resources: Resources): ReviewSheetDailySpendRow {
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
    return ReviewSheetDailySpendRow(
        headline = result.statusLine(resources),
        supportingText = listOfNotNull(scopeName, totals).joinToString(". "),
        state =
            when (result.status) {
                PolicyCheckStatus.Passed -> DailyLimitRowState.Within
                PolicyCheckStatus.Failed,
                PolicyCheckStatus.Unverified -> DailyLimitRowState.Over
                PolicyCheckStatus.NotConfigured -> DailyLimitRowState.NoLimit
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
 * The fact rows: every term the publisher sent under a label rather than its key, then the accounts
 * this phone read out of the transaction, then the signal's own identity.
 */
private fun OperationReview.factsOf(
    terms: PredictionPayload?,
    source: PredictionReviewSource,
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

    plain(R.string.prediction_fact_from, source.name)
    // The venue the order is placed on, named as its provider requires (SEE-173). It is not the
    // feed's publisher, and it is not the market's own source below.
    about?.let {
        add(ReviewSheetFactRow(resources.getString(it.role), it.name.substringBefore(" · ")))
    }
    val read = terms != null
    if (terms != null) {
        terms.marketProvider.takeIf(String::isNotBlank)?.let {
            plain(R.string.prediction_fact_provider, it.humanName())
        }
        mono(R.string.prediction_fact_action, proposal.action.value)
        terms.eventId.takeIf(String::isNotBlank)?.let { mono(R.string.prediction_fact_event, it) }
        mono(R.string.prediction_fact_market, terms.marketId)
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
    } else {
        mono(R.string.prediction_fact_action, proposal.action.value)
    }
    // Where the market is on the provider's own site or app, as history shows it once an order is
    // placed (SEE-157), so the owner can look before approving too.
    destinations.forEach {
        add(
            ReviewSheetFactRow(
                label = resources.getString(it.label),
                value = resources.getString(R.string.prediction_fact_open),
                valueStyle = FactRowValueStyle.Link,
                link = it.url,
            )
        )
    }
    // The provider's official pages: how it works, its terms, its privacy policy (SEE-173).
    about?.links?.forEach {
        add(
            ReviewSheetFactRow(
                label = resources.getString(it.label),
                value = resources.getString(R.string.prediction_fact_open),
                valueStyle = FactRowValueStyle.Link,
                link = it.url,
            )
        )
    }
    // Anything the publisher said that this build does not read as a prediction term, still shown
    // once, under a label made from its name rather than the name itself.
    proposal.values
        .filter { !read || it.key !in KnownTerms }
        .forEach { add(ReviewSheetFactRow(it.key.humanName(), it.text)) }

    val facts = inspection?.facts
    val payer = facts?.wallet
    if (payer != null) {
        mono(R.string.prediction_fact_payer, payer)
    } else {
        wallet?.address?.let { mono(R.string.prediction_fact_wallet, it) }
    }
    facts?.recipient?.let { mono(R.string.prediction_fact_receives, it) }
    inspection
        ?.references
        ?.filterNot { it.key == PredictionTermNames.MARKET_ID && it.value == terms?.marketId }
        ?.forEach {
            add(
                ReviewSheetFactRow(
                    label = it.key.humanName(),
                    value = it.value.middle(),
                    valueStyle = FactRowValueStyle.Mono,
                )
            )
        }
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

/**
 * What the provider says the owner should know before committing — real funds on mainnet, its fees,
 * where it is available, and what an order is and is not — as info blocks under the one that says
 * what this phone checks (SEE-173).
 */
private fun OperationReview.aboutBlocks(resources: Resources): List<ReviewSheetInfoBlock> {
    val about = about ?: return emptyList()
    return about.notes.mapIndexed { index, note ->
        ReviewSheetInfoBlock(
            body = resources.getString(note.text, *note.args.toTypedArray()),
            title = about.name.takeIf { index == 0 },
        )
    } + ReviewSheetInfoBlock(resources.getString(R.string.operation_about_signer))
}

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
