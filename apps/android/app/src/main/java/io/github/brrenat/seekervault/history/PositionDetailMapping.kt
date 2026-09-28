package io.github.brrenat.seekervault.history

import io.github.brrenat.seekervault.activity.explorerUrl
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.designsystem.HistoryDetailLink
import io.github.brrenat.seekervault.designsystem.HistoryDetailPosition
import io.github.brrenat.seekervault.designsystem.HistoryDetailPositionState
import io.github.brrenat.seekervault.designsystem.HistoryDetailRow
import io.github.brrenat.seekervault.designsystem.HistoryDetailSale
import io.github.brrenat.seekervault.designsystem.HistoryDetailSaleState
import io.github.brrenat.seekervault.designsystem.HistoryDetailSell
import io.github.brrenat.seekervault.designsystem.HistoryDetailTransaction
import io.github.brrenat.seekervault.designsystem.PositionSaleFact
import io.github.brrenat.seekervault.designsystem.PositionSaleSheetState
import io.github.brrenat.seekervault.designsystem.PositionSaleVerdict
import io.github.brrenat.seekervault.designsystem.TermsCardRow
import io.github.brrenat.seekervault.plugins.MarketStanding
import io.github.brrenat.seekervault.plugins.OrderFillState
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PositionReading
import io.github.brrenat.seekervault.plugins.SaleBlock
import io.github.brrenat.seekervault.plugins.saleBlockOf
import io.github.brrenat.seekervault.positions.HoldingRecord
import io.github.brrenat.seekervault.positions.OrderSnapshot
import io.github.brrenat.seekervault.positions.PositionsState
import io.github.brrenat.seekervault.positions.PositionTracker
import io.github.brrenat.seekervault.positions.RefreshProblem
import io.github.brrenat.seekervault.positions.SaleRecord
import io.github.brrenat.seekervault.positions.SaleResult
import io.github.brrenat.seekervault.positions.SaleReviewState
import io.github.brrenat.seekervault.positions.SaleStage
import io.github.brrenat.seekervault.positions.stale
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.time.Instant

/**
 * The live position block of a prediction purchase's History item, and its sale review (SEE-172).
 *
 * Pure mapping, like the rest of the page, with one difference said out loud: the rest of the page
 * is the stored record, and this block is **what the provider says now**, with the time it said it.
 * It never rewrites the purchase above it — the stake, the review and the approval stay what they
 * were — and a value nobody quoted is shown as unavailable rather than as zero.
 */

/** Whether [record] is a purchase whose position this phone would follow. */
val ProposalRecord.purchasedPrediction: Boolean
    get() {
        val execution = execution ?: return false
        if (execution.binding.action != PREDICTION_BUY_ACTION) return false
        if (execution.binding.environment != PluginEnvironment.Production) return false
        return execution.outcome is ProposalOutcome.Submitted ||
            execution.outcome is ProposalOutcome.Unresolved
    }

/** The tracked position [record]'s purchase went into, if this phone links one. */
fun PositionsState.holdingOf(record: ProposalRecord): HoldingRecord? =
    holdings.values.firstOrNull { holding ->
        holding.purchases.any {
            it.connectionId == record.connectionId && it.proposalId == record.key.proposalId
        }
    }

fun positionDetail(
    record: ProposalRecord,
    positions: PositionsState,
    wallet: SelectedWallet?,
    links: List<HistoryDetailLink>,
    now: Instant,
    clock: HistoryDetailClock = HistoryDetailClock(),
): HistoryDetailPosition? {
    if (!record.purchasedPrediction) return null
    val holding = positions.holdingOf(record)
    if (holding == null) {
        return HistoryDetailPosition(
            state =
                if (positions.loaded) HistoryDetailPositionState.Unavailable
                else HistoryDetailPositionState.Loading,
            stateText = if (positions.loaded) PositionCopy.Untracked else PositionCopy.Reading,
            note = if (positions.loaded) PositionCopy.UntrackedBody else null,
            links = links,
        )
    }
    val purchase =
        holding.purchases.firstOrNull {
            it.connectionId == record.connectionId && it.proposalId == record.key.proposalId
        }
    val snapshot = holding.snapshot
    val sales = positions.salesOf(holding.held.account)
    val refreshing = holding.held.account in positions.refreshing
    val sold =
        sales.any { it.result == SaleResult.Closed } &&
            (snapshot == null ||
                snapshot.contractsMicro == 0UL ||
                holding.problem == RefreshProblem.NotFound)
    val settled = snapshot != null && (snapshot.market == MarketStanding.Settled || snapshot.claimable)
    val (state, stateText) =
        when {
            sold -> HistoryDetailPositionState.Closed to PositionCopy.Sold
            settled -> HistoryDetailPositionState.Settled to PositionCopy.Settled
            snapshot != null && snapshot.contractsMicro == 0UL ->
                HistoryDetailPositionState.Closed to PositionCopy.NothingHeld
            snapshot == null && holding.problem == RefreshProblem.NotFound ->
                HistoryDetailPositionState.Unavailable to PositionCopy.NotIndexed
            snapshot == null && holding.problem != null ->
                HistoryDetailPositionState.Unavailable to PositionCopy.CouldNotRead
            snapshot == null -> HistoryDetailPositionState.Loading to PositionCopy.Reading
            holding.problem != null || holding.stale(now) ->
                HistoryDetailPositionState.Stale to
                    "${PositionCopy.LastKnown} · ${clock.time(checkNotNull(holding.observedAt))}"
            else ->
                HistoryDetailPositionState.Live to
                    "${PositionCopy.Live} · ${clock.time(checkNotNull(holding.observedAt))}"
        }
    val note =
        when {
            settled -> PositionCopy.SettledBody
            snapshot == null && holding.problem == RefreshProblem.NotFound ->
                PositionCopy.NotIndexedBody
            holding.problem != null ->
                "${problemText(holding.problem)} " +
                    if (snapshot != null) PositionCopy.ShowingLastKnown else ""
            else -> null
        }?.trim()
    return HistoryDetailPosition(
        state = state,
        stateText = stateText,
        scope = if (snapshot != null && !sold) scopeText(holding) else null,
        orderRows = orderRows(purchase?.orderAccount?.let(holding.orders::get), purchase != null, clock),
        rows = snapshot?.takeUnless { sold }?.let { positionRows(it, holding, clock) }.orEmpty(),
        note = note,
        refreshing = refreshing,
        sell =
            if (sold || settled || (snapshot != null && snapshot.contractsMicro == 0UL)) null
            else sellOf(holding, sales, wallet),
        links = links,
        sales = sales.map { saleOf(it, clock) },
    )
}

/** Each sale's transaction, beside the purchase's own, with the phone's own chain result. */
fun saleTransactions(
    record: ProposalRecord,
    positions: PositionsState,
    chainChecks: Map<io.github.brrenat.seekervault.connections.RequestKey, ChainCheck>,
): List<HistoryDetailTransaction> {
    val holding = positions.holdingOf(record) ?: return emptyList()
    return positions.salesOf(holding.held.account).mapNotNull { sale ->
        val signature = sale.signature ?: return@mapNotNull null
        val view = chainView(local = chainChecks[PositionTracker.saleKey(sale.id)])
        HistoryDetailTransaction(
            label = PositionCopy.SaleTransaction,
            signature = signature,
            status = view.transactionStatus(),
            explorerLabel =
                "View on explorer" + (holding.held.network.word()?.let { " · $it" } ?: ""),
            explorerUrl = explorerUrl(signature, holding.held.network),
        )
    }
}

private fun scopeText(holding: HoldingRecord): String =
    if (holding.purchases.size > 1) {
        "Your wallet's whole position in this outcome. It includes ${holding.purchases.size} " +
            "purchases from signals on this phone, and anything bought for this side elsewhere."
    } else {
        PositionCopy.Scope
    }

private fun orderRows(
    order: OrderSnapshot?,
    hasPurchase: Boolean,
    clock: HistoryDetailClock,
): List<HistoryDetailRow> {
    if (!hasPurchase) return emptyList()
    if (order == null) return listOf(HistoryDetailRow("Fill", PositionCopy.FillUnknown))
    val reading = order.reading
    val filled = reading.filledContractsMicro?.let(::contracts)
    val asked = reading.contractsMicro?.let(::contracts)
    val fill =
        when (reading.fill) {
            OrderFillState.Pending -> "Waiting to fill"
            OrderFillState.PartiallyFilled ->
                "Partly filled" + if (filled != null && asked != null) " · $filled of $asked" else ""
            OrderFillState.Filled -> "Filled" + (filled?.let { " · $it contracts" } ?: "")
            OrderFillState.PartiallyFilledClosed ->
                "Partly filled, rest returned" +
                    if (filled != null && asked != null) " · $filled of $asked" else ""
            OrderFillState.Failed -> "Not filled"
            OrderFillState.Unknown -> "Not known (${reading.raw})"
        }
    return buildList {
        add(HistoryDetailRow("Fill", fill))
        reading.averageFillPriceMicroUsd
            ?.takeIf { it > 0UL }
            ?.let { add(HistoryDetailRow("Average price", "${dollars(it)} a contract")) }
        add(HistoryDetailRow("Reported", clock.time(order.observedAt)))
    }
}

private fun positionRows(
    position: PositionReading,
    holding: HoldingRecord,
    clock: HistoryDetailClock,
): List<HistoryDetailRow> = buildList {
    val title = listOf(position.eventTitle, position.marketTitle).filter { it.isNotEmpty() }
    if (title.isNotEmpty()) add(HistoryDetailRow("Market", title.joinToString(" · ")))
    add(HistoryDetailRow("Outcome", if (position.yes) "Yes" else "No"))
    add(HistoryDetailRow("Contracts held", contracts(position.contractsMicro)))
    add(HistoryDetailRow("Value now", position.valueMicroUsd?.let(::dollars) ?: PositionCopy.NotQuoted))
    add(
        HistoryDetailRow(
            "Best bid",
            position.sellPriceMicroUsd?.let { "${dollars(it)} a contract" } ?: PositionCopy.NoBid,
        )
    )
    add(HistoryDetailRow("Cost basis", dollars(position.costMicroUsd)))
    position.averagePriceMicroUsd?.let {
        add(HistoryDetailRow("Average entry", "${dollars(it)} a contract"))
    }
    add(HistoryDetailRow("P&L", position.pnlMicroUsd?.let(::signedDollars) ?: PositionCopy.NotQuoted))
    position.pnlAfterFeesMicroUsd?.let { add(HistoryDetailRow("P&L after fees", signedDollars(it))) }
    add(
        HistoryDetailRow(
            "Market",
            position.marketResult?.let { "Settled · $it" } ?: position.marketStatus.ifEmpty { "Unknown" },
        )
    )
    holding.observedAt?.let { add(HistoryDetailRow("Last read", clock.stamp(it))) }
}

private fun sellOf(
    holding: HoldingRecord,
    sales: List<SaleRecord>,
    wallet: SelectedWallet?,
): HistoryDetailSell {
    val held = holding.held
    val reason =
        when {
            wallet == null -> "Connect the wallet that bought this position to sell it."
            wallet.address != held.owner ->
                "This position belongs to ${held.owner.shortAddress()}. Connect that wallet to " +
                    "sell it."
            wallet.network.network != held.network ->
                "Your wallet is on another network than this position."
            sales.any { it.inFlight } -> "A sale of this position is still being settled."
            holding.snapshot == null -> "The position hasn't been read yet."
            else ->
                when (saleBlockOf(checkNotNull(holding.snapshot))) {
                    SaleBlock.Settled -> PositionCopy.SettledBody
                    SaleBlock.MarketClosed -> "This market no longer trades."
                    SaleBlock.NothingHeld -> "This position holds no contracts."
                    SaleBlock.OpenOrders ->
                        "An order on this position is still open. Wait for it to finish."
                    SaleBlock.NoBid -> "Nobody is buying this side right now."
                    null -> null
                }
        }
    return HistoryDetailSell(enabled = reason == null, reason = reason)
}

private fun saleOf(sale: SaleRecord, clock: HistoryDetailClock): HistoryDetailSale {
    val (state, text) =
        when {
            sale.stage == SaleStage.Signing -> HistoryDetailSaleState.Pending to "In your wallet"
            sale.result == SaleResult.Closed -> HistoryDetailSaleState.Done to "Sold"
            sale.result == SaleResult.Residual -> HistoryDetailSaleState.Done to "Partly sold"
            sale.stage == SaleStage.Declined ->
                HistoryDetailSaleState.Failed to "Declined in wallet"
            sale.result == SaleResult.NotExecuted -> HistoryDetailSaleState.Failed to "Not sold"
            sale.result == SaleResult.Lapsed -> HistoryDetailSaleState.Failed to "Never sent"
            sale.stage == SaleStage.Unresolved ->
                HistoryDetailSaleState.Pending to "Outcome unknown · checking"
            else -> HistoryDetailSaleState.Pending to "Sent · waiting to fill"
        }
    val order = sale.order?.reading
    val symbol = sale.proceedsSymbol
    fun money(micro: ULong) = "${formatBaseUnits(micro, sale.proceedsDecimals)} $symbol"
    return HistoryDetailSale(
        title = "Sale · ${clock.stamp(sale.createdAt)}",
        state = state,
        stateText = text,
        rows =
            buildList {
                add(HistoryDetailRow("Contracts", contracts(sale.contractsMicro)))
                add(HistoryDetailRow("Lowest price accepted", "${dollars(sale.floorPriceMicroUsd)} a contract"))
                val filled = order?.filledContractsMicro
                if (filled != null && order.fill != OrderFillState.Pending) {
                    add(HistoryDetailRow("Sold", "${contracts(filled)} contracts"))
                }
                // Only what the provider established. An estimate is never shown as a receipt.
                val proceeds = order?.netProceedsMicroUsd?.takeIf { sale.result.final && it > 0UL }
                add(
                    HistoryDetailRow(
                        "Proceeds",
                        proceeds?.let(::money) ?: if (sale.result.final) PositionCopy.NotReported
                        else PositionCopy.KnownAfterFill,
                    )
                )
                order?.feeMicroUsd?.takeIf { sale.result.final && it > 0UL }?.let {
                    add(HistoryDetailRow("Fees", money(it)))
                }
                sale.detail?.let { add(HistoryDetailRow("Detail", it)) }
            },
    )
}

/**
 * The sale review sheet's state for [review] of [holding], or a preparing sheet when nothing has
 * been asked for yet. [text] reads a string resource; [now] decides whether the review ran out.
 */
fun positionSaleSheet(
    review: SaleReviewState?,
    holding: HoldingRecord?,
    notice: String?,
    now: Instant,
    text: (Int) -> String,
    clock: HistoryDetailClock = HistoryDetailClock(),
): PositionSaleSheetState {
    val snapshot = holding?.snapshot
    val side = if (holding?.held?.yes != false) "Yes" else "No"
    val headline = "Sell your $side position"
    val subline =
        snapshot?.let { listOf(it.eventTitle, it.marketTitle).filter(String::isNotEmpty).joinToString(" · ") }
            ?.ifEmpty { null } ?: holding?.held?.marketId.orEmpty()
    val scope =
        "This sells your wallet's entire current position in this outcome — every contract, " +
            "including any bought through other signals or outside this app — not only this " +
            "signal's stake."
    return when (review) {
        null,
        SaleReviewState.Preparing ->
            PositionSaleSheetState(
                headline = headline,
                subline = subline,
                scope = scope,
                verdict = PositionSaleVerdict.Preparing,
                verdictText = "Reading the position and building the sale…",
                staleNotice = notice,
            )
        SaleReviewState.Signing ->
            PositionSaleSheetState(
                headline = headline,
                subline = subline,
                scope = scope,
                verdict = PositionSaleVerdict.Signing,
                verdictText = "Waiting for your wallet. Nothing more is sent from here.",
            )
        is SaleReviewState.Refused ->
            PositionSaleSheetState(
                headline = headline,
                subline = subline,
                scope = scope,
                verdict = PositionSaleVerdict.Unavailable,
                verdictText =
                    listOfNotNull(
                            review.failure.explanation.takeIf { it != 0 }?.let(text),
                            review.failure.detail,
                        )
                        .joinToString(" ")
                        .ifEmpty { "Nothing could be prepared." },
                staleNotice = notice,
            )
        is SaleReviewState.Ready -> {
            val draft = review.draft
            val terms = draft.prepared.terms
            val inspection = draft.prepared.inspection
            val expired = draft.expired(now)
            fun money(micro: ULong) =
                "${formatBaseUnits(micro, terms.proceedsDecimals)} ${terms.proceedsSymbol}"
            PositionSaleSheetState(
                headline = headline,
                subline = subline,
                scope =
                    "This sells your wallet's entire current position in this outcome — " +
                        "${contracts(terms.contractsMicro)} contracts, including any bought " +
                        "through other signals or outside this app — not only this signal's stake.",
                verdict =
                    if (inspection.approvable) PositionSaleVerdict.Verified
                    else PositionSaleVerdict.Refused,
                verdictText =
                    if (inspection.approvable) {
                        "Every check on this transaction's bytes passed."
                    } else {
                        "This transaction can't be approved."
                    },
                terms =
                    listOf(
                        TermsCardRow(
                            "Sells",
                            "${contracts(terms.contractsMicro)} $side contracts · all of them",
                        ),
                        TermsCardRow(
                            "Lowest price accepted",
                            "${dollars(terms.floorPriceMicroUsd)} a contract",
                        ),
                        TermsCardRow(
                            "You receive at least",
                            "${money(terms.leastGrossMicroUsd)} before fees",
                        ),
                    ),
                facts =
                    buildList {
                        add(
                            PositionSaleFact(
                                "Estimated proceeds",
                                terms.estimatedGrossMicroUsd?.let {
                                    "≈ ${money(it)} at today's bid, not guaranteed"
                                } ?: "No current bid",
                            )
                        )
                        add(
                            PositionSaleFact(
                                "Estimated fees",
                                "${money(terms.estimatedFeeMicroUsd)} · the provider's estimate",
                            )
                        )
                        add(PositionSaleFact("Paid out in", terms.proceedsSymbol))
                        add(PositionSaleFact("Proceeds go to", terms.proceedsAccount.shortAddress(), mono = true))
                        add(PositionSaleFact("Wallet", terms.owner.shortAddress(), mono = true))
                        add(PositionSaleFact("Network", draft.held.network.word() ?: "Unknown"))
                        inspection.details.forEach {
                            add(PositionSaleFact(text(it.label), it.value, mono = it.value.length > 32))
                        }
                    },
                findings = inspection.findings.map { text(it.message) },
                staleNotice =
                    notice ?: if (expired) "This review ran out. Prepare it again for current terms." else null,
                expiry =
                    "This review stands until " +
                        clock.preciseTime(Instant.ofEpochSecond(draft.prepared.expiresAtEpochSeconds)) +
                        ". Selling signs a new transaction in your wallet.",
                primaryEnabled = inspection.approvable && !expired && notice == null,
            )
        }
    }
}

private fun problemText(problem: RefreshProblem): String =
    when (problem) {
        RefreshProblem.Unreachable -> "Couldn't reach Jupiter."
        RefreshProblem.RateLimited -> "Jupiter asked this phone to slow down."
        RefreshProblem.Refused -> "Jupiter refused the read."
        RefreshProblem.Unusable -> "Jupiter's answer couldn't be used."
        RefreshProblem.NotFound -> "Jupiter has no record of this position right now."
        RefreshProblem.Unsupported -> "This build can't read positions for this provider."
    }

/** Contracts are carried in millionths. */
internal fun contracts(micro: ULong): String = formatBaseUnits(micro, 6)

/** Micro-dollars, to the cent, rounded down: a figure shown is never more than was quoted. */
internal fun dollars(micro: ULong): String {
    val cents = micro / 10_000UL
    return "$" + "${cents / 100UL}." + "${cents % 100UL}".padStart(2, '0')
}

internal fun signedDollars(micro: Long): String =
    if (micro < 0) "−" + dollars((-micro).toULong()) else "+" + dollars(micro.toULong())

private fun String.shortAddress(): String =
    if (length <= 16) this else "${take(8)}…${takeLast(8)}"

object PositionCopy {
    const val Live = "Live"
    const val LastKnown = "Last known"
    const val Reading = "Reading…"
    const val Sold = "Sold"
    const val Settled = "Settled"
    const val NothingHeld = "Nothing held"
    const val NotIndexed = "Not found yet"
    const val CouldNotRead = "Couldn't read"
    const val Untracked = "Tracking unavailable"
    const val UntrackedBody =
        "This record doesn't name the position its order went into, so it can't be followed " +
            "here. It is still on Jupiter."
    const val NotIndexedBody =
        "Jupiter has no record of this position yet. A new fill can take a moment to be " +
            "indexed; this doesn't mean it was sold or lost."
    const val SettledBody =
        "This market has settled. Claim any payout in Jupiter; a settled position isn't sold."
    const val ShowingLastKnown = "Showing what was last read."
    const val Scope =
        "Your wallet's whole position in this outcome. It includes anything bought for this " +
            "side elsewhere, not only this signal's stake."
    const val FillUnknown = "Not reported yet"
    const val NotQuoted = "Not quoted"
    const val NoBid = "No bid now"
    const val KnownAfterFill = "Known after it fills"
    const val NotReported = "Not reported"
    const val SaleTransaction = "Sell position"
}
