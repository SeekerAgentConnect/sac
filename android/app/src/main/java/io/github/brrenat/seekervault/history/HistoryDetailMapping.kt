package io.github.brrenat.seekervault.history

import io.github.brrenat.seekervault.activity.explorerUrl
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.sourceColour
import io.github.brrenat.seekervault.designsystem.HistoryDetailDelivery
import io.github.brrenat.seekervault.designsystem.HistoryDetailEnvironment
import io.github.brrenat.seekervault.designsystem.HistoryDetailEvent
import io.github.brrenat.seekervault.designsystem.HistoryDetailExecution
import io.github.brrenat.seekervault.designsystem.HistoryDetailExecutionState
import io.github.brrenat.seekervault.designsystem.HistoryDetailHeader
import io.github.brrenat.seekervault.designsystem.HistoryDetailModel
import io.github.brrenat.seekervault.designsystem.HistoryDetailOrigin
import io.github.brrenat.seekervault.designsystem.HistoryDetailResponse
import io.github.brrenat.seekervault.designsystem.HistoryDetailRow
import io.github.brrenat.seekervault.designsystem.HistoryDetailRowLayout
import io.github.brrenat.seekervault.designsystem.HistoryDetailStatus
import io.github.brrenat.seekervault.designsystem.HistoryDetailStatusModel
import io.github.brrenat.seekervault.designsystem.HistoryDetailTimelineEntry
import io.github.brrenat.seekervault.designsystem.HistoryDetailTransaction
import io.github.brrenat.seekervault.designsystem.HistoryDetailTransactionStatus
import io.github.brrenat.seekervault.inbox.inboxTitle
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.SignMessageAction
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.requests.commonEnvelope
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Locale

/*
 * The History record page's model, built from what this phone stored when it happened (SEE-161,
 * docs/wiki/history-details.md). Every input is a stored record: nothing here reads the rules, a
 * quote, a provider or a setting, so what the page says cannot drift with any of them. A value the
 * record does not hold is left out, or said to be unknown — never guessed, and never success.
 */

/** How times are written on the page; replaceable so a test can pin the zone and the locale. */
class HistoryDetailClock(
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
) {
    private val short = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val seconds = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale)
    private val day = DateTimeFormatter.ofPattern("MMM d", locale)
    private val zone = zone

    /** `9:01 PM` */
    fun time(at: Instant): String = short.format(at.atZone(zone))

    /** `9:00:41 PM` */
    fun preciseTime(at: Instant): String = seconds.format(at.atZone(zone))

    /** `Sep 26, 9:00:41 PM` */
    fun stamp(at: Instant): String =
        "${day.format(at.atZone(zone))}, ${seconds.format(at.atZone(zone))}"
}

// A private request ---------------------------------------------------------------------------

/**
 * The page for an answer given on this phone. [connection] supplies only the owner's own name and
 * colour for the source; a direct connection is always production (docs/wiki/environments.md).
 */
fun privateHistoryDetail(
    result: LocalResult,
    connection: Connection?,
    clock: HistoryDetailClock = HistoryDetailClock(),
    sending: Boolean = false,
): HistoryDetailModel {
    val request = result.request
    val server = connection?.label ?: result.connectionId
    val network = request.action.network()
    val envelope = request.commonEnvelope()
    val superseded = result.delivery == Delivery.Superseded
    val cancelled = superseded && request.state == RequestState.REQUEST_STATE_CANCELLED
    val expired = superseded && request.state == RequestState.REQUEST_STATE_EXPIRED
    val sent = result.signing as? SigningOutcome.Sent
    val signed = result.signing as? SigningOutcome.Signed
    val delivered = result.delivery == Delivery.Accepted

    val status =
        when {
            cancelled ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Cancelled,
                    explanation =
                        "$server withdrew it at ${clock.time(request.updatedAt.instant())}, " +
                            "before your answer reached it.",
                    reason = request.outcome.detail.takeIf(String::isNotBlank),
                    reasonLabel = "Reason given by $server",
                )
            expired ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Expired,
                    explanation =
                        "It expired at ${clock.time(request.expiresAt.instant())} before your " +
                            "answer reached $server.",
                )
            result.answer == Answer.Reject ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Declined,
                    explanation =
                        if (delivered) "You declined on this phone. $server was told."
                        else "You declined on this phone.",
                )
            result.answer == Answer.Acknowledge ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation = "You acknowledged it on this phone. Nothing was signed.",
                )
            else ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation = approvedExplanation(result),
                )
        }

    val decision =
        when (result.answer) {
            Answer.Acknowledge -> "Acknowledged"
            Answer.Reject -> "Declined"
            Answer.Approve -> "Approved"
        }
    val response =
        HistoryDetailResponse.Sent(
            timestampText = clock.stamp(result.answeredAt),
            rows =
                buildList {
                    add(HistoryDetailRow("Decision", decision))
                    if (delivered) {
                        add(
                            HistoryDetailRow(
                                "Delivered to",
                                result.settledAt?.let { "$server, ${clock.time(it)}" } ?: server,
                            )
                        )
                    }
                },
            note =
                when {
                    cancelled -> "It arrived after the request was cancelled, so it wasn't used."
                    expired -> "It arrived after the request expired, so it wasn't used."
                    else -> null
                },
        )

    val delivery =
        when (result.delivery) {
            Delivery.Waiting ->
                HistoryDetailDelivery(
                    title = "$server hasn't confirmed it received your response",
                    body =
                        "Your ${decision.lowercase()} is stored on this phone and is sent again " +
                            "on each refresh." +
                            if (result.lastFailure != null) {
                                " The last delivery attempt got no answer from $server."
                            } else {
                                ""
                            },
                    sending = sending,
                )
            Delivery.Undeliverable ->
                HistoryDetailDelivery(
                    title = "$server can no longer receive your response",
                    body =
                        "Your ${decision.lowercase()} is stored on this phone. $server no longer " +
                            "accepts this phone's connection" +
                            (result.settledAt?.let { " (since ${clock.time(it)})" } ?: "") +
                            ", so it can't be delivered.",
                    canSendAgain = false,
                )
            Delivery.Accepted,
            Delivery.Superseded -> null
        }

    val execution =
        when {
            signed != null ->
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Signed,
                    title = "Signed · no transaction",
                    body =
                        "Signing a message doesn't touch the chain, so there is nothing to " +
                            "confirm and no funds moved.",
                )
            sent != null -> chainExecution(request, network, clock)
            else -> null
        }

    val signature = (sent?.signature ?: signed?.signature)?.let { encodeBase58(it.toByteArray()) }
    val transactions =
        if (sent != null && signature != null) {
            listOf(
                HistoryDetailTransaction(
                    label = request.action.transactionLabel(),
                    signature = signature,
                    status =
                        when (request.state) {
                            RequestState.REQUEST_STATE_CONFIRMED,
                            RequestState.REQUEST_STATE_COMPLETED ->
                                HistoryDetailTransactionStatus.Confirmed
                            RequestState.REQUEST_STATE_FAILED ->
                                HistoryDetailTransactionStatus.Failed
                            else -> HistoryDetailTransactionStatus.Pending
                        },
                    explorerLabel = "View on explorer" + (network.word()?.let { " · $it" } ?: ""),
                    explorerUrl = explorerUrl(signature, network),
                )
            )
        } else {
            emptyList()
        }

    val timeline = buildList {
        request.createdAt.instantOrNull()?.let {
            add(entry(HistoryDetailEvent.Received, "Received", it, clock))
        }
        add(
            when (result.answer) {
                Answer.Reject ->
                    entry(HistoryDetailEvent.Declined, "You declined", result.answeredAt, clock)
                Answer.Acknowledge ->
                    entry(
                        HistoryDetailEvent.Approved,
                        "You acknowledged",
                        result.answeredAt,
                        clock,
                    )
                Answer.Approve ->
                    entry(HistoryDetailEvent.Approved, "You approved", result.answeredAt, clock)
            }
        )
        if (signed != null) {
            add(entry(HistoryDetailEvent.Signed, "Signed", result.answeredAt, clock))
        }
        if (sent != null) {
            add(entry(HistoryDetailEvent.Sent, "Sent to the network", result.answeredAt, clock))
            val settled =
                request.outcome.confirmation
                    .takeIf { request.outcome.hasConfirmation() }
                    ?.checkedAt
                    ?.instantOrNull() ?: request.updatedAt.instantOrNull()
            when (request.state) {
                RequestState.REQUEST_STATE_CONFIRMED,
                RequestState.REQUEST_STATE_COMPLETED ->
                    settled?.let {
                        add(entry(HistoryDetailEvent.Confirmed, "Confirmed", it, clock))
                    }
                RequestState.REQUEST_STATE_FAILED ->
                    settled?.let { add(entry(HistoryDetailEvent.Failed, "Failed", it, clock)) }
                else -> Unit
            }
        }
        if (delivered) {
            result.settledAt?.let {
                add(entry(HistoryDetailEvent.Delivered, "Delivered to $server", it, clock))
            }
        }
        if (result.delivery == Delivery.Undeliverable) {
            result.settledAt?.let {
                add(
                    entry(
                        HistoryDetailEvent.DeliveryUnconfirmed,
                        "Delivery to $server unconfirmed",
                        it,
                        clock,
                    )
                )
            }
        }
        if (expired) {
            add(entry(HistoryDetailEvent.Expired, "Expired", request.expiresAt.instant(), clock))
        }
        if (cancelled) {
            add(
                entry(
                    HistoryDetailEvent.Cancelled,
                    "Cancelled by $server",
                    request.updatedAt.instant(),
                    clock,
                )
            )
        }
    }
        .sortedBy { it.first }
        .map { it.second }

    val identifiers = buildList {
        add(HistoryDetailRow("Request ID", result.requestId))
        signature?.let {
            add(HistoryDetailRow(if (signed != null) "Message signature" else "Signature", it))
        }
        request.outcome.confirmation
            .takeIf { request.outcome.hasConfirmation() }
            ?.chainError
            ?.takeIf(String::isNotBlank)
            ?.let { add(HistoryDetailRow("Error", it)) }
    }

    return HistoryDetailModel(
        header =
            HistoryDetailHeader(
                origin = HistoryDetailOrigin.Request,
                sourceName = server,
                sourceColour = connection?.colour?.sourceColour(),
                title = envelope.inboxTitle(envelope.action.capabilityId),
                environment = HistoryDetailEnvironment.Production,
                networkText = network.word(),
            ),
        status = status,
        response = response,
        delivery = delivery,
        execution = execution,
        originalDescription = request.agentNote.takeIf(String::isNotBlank),
        originalRows = request.action.originalRows(),
        transactions = transactions,
        timeline = timeline,
        identifiers = identifiers,
        footnote = HistoryDetailCopy.ProductionFootnote,
    )
}

private fun approvedExplanation(result: LocalResult): String =
    when (val signing = result.signing) {
        null -> "You approved on this phone. The wallet hasn't answered yet."
        is SigningOutcome.Signed -> "You approved. Your wallet signed the message."
        is SigningOutcome.Sent ->
            when (result.request.state) {
                RequestState.REQUEST_STATE_FAILED ->
                    "You approved on this phone. The network rejected the transaction."
                RequestState.REQUEST_STATE_CONFIRMED,
                RequestState.REQUEST_STATE_COMPLETED ->
                    "You approved on this phone. Your wallet signed it."
                else -> "You approved on this phone. Your wallet sent it to the network."
            }
        SigningOutcome.Declined ->
            "You approved on this phone, then declined in the wallet. Nothing was signed."
        is SigningOutcome.Failed ->
            "You approved on this phone. The wallet didn't complete it: ${signing.detail}"
        is SigningOutcome.Unresolved ->
            "You approved on this phone. This phone never learned what the wallet did: " +
                signing.detail
    }

/**
 * What the sidecar has learned from the chain, and only that. Until it has confirmed or failed the
 * transaction, it is waiting — approving it is not the network's word that it went through.
 */
private fun chainExecution(
    request: ActionRequest,
    network: Network?,
    clock: HistoryDetailClock,
): HistoryDetailExecution {
    val confirmation = request.outcome.confirmation.takeIf { request.outcome.hasConfirmation() }
    val checkedAt = confirmation?.checkedAt?.instantOrNull()
    return when (request.state) {
        RequestState.REQUEST_STATE_CONFIRMED,
        RequestState.REQUEST_STATE_COMPLETED ->
            HistoryDetailExecution(
                state = HistoryDetailExecutionState.Confirmed,
                title = network.word()?.let { "Confirmed on $it" } ?: "Confirmed",
                body =
                    checkedAt?.let { "Confirmed by ${clock.preciseTime(it)}." }
                        ?: "The network confirmed it.",
            )
        RequestState.REQUEST_STATE_FAILED ->
            HistoryDetailExecution(
                state = HistoryDetailExecutionState.Failed,
                title = "Failed on the network",
                body =
                    (checkedAt?.let { "Rejected by ${clock.preciseTime(it)}. " } ?: "") +
                        "Nothing moved except the network fee.",
                failureReason =
                    request.outcome.detail.takeIf(String::isNotBlank)
                        ?: "The network didn't say why.",
            )
        else ->
            HistoryDetailExecution(
                state = HistoryDetailExecutionState.Pending,
                title = "Waiting for network confirmation",
                body =
                    "Sent and not confirmed yet. " +
                        (checkedAt?.let { "Last checked ${clock.time(it)}. " }
                            ?: "Not checked yet. ") +
                        "Approving it doesn't mean it went through.",
                rows =
                    if (request.action.hasSwap()) {
                        listOf(HistoryDetailRow("Received", HistoryDetailCopy.Unknown))
                    } else {
                        emptyList()
                    },
            )
    }
}

/** The request's own operation, as stored. Addresses and messages are never cut. */
private fun Action.originalRows(): List<HistoryDetailRow> = buildList {
    when (kindCase) {
        Action.KindCase.ACK ->
            add(HistoryDetailRow("Message", ack.text, HistoryDetailRowLayout.Block))
        Action.KindCase.SIGN_MESSAGE -> {
            add(
                HistoryDetailRow("Message", signMessage.messageText(), HistoryDetailRowLayout.Block)
            )
            walletRow(signMessage.wallet)
        }
        Action.KindCase.TRANSFER -> {
            add(HistoryDetailRow("Amount", amountText(transfer.amount, transfer.asset)))
            add(tokenRow(transfer.asset))
            add(HistoryDetailRow("Recipient", transfer.recipient, HistoryDetailRowLayout.Block))
            walletRow(transfer.wallet)
        }
        Action.KindCase.STAKING -> {
            add(HistoryDetailRow("Operation", staking.operation.word()))
            if (staking.amount.isNotEmpty()) {
                add(HistoryDetailRow("Amount", amountText(staking.amount, null)))
            }
            walletRow(staking.wallet)
        }
        Action.KindCase.SWAP -> {
            add(HistoryDetailRow("You pay", amountText(swap.inputAmount, swap.inputAsset)))
            add(HistoryDetailRow("You receive", swap.outputAsset.symbol()))
            if (swap.outputAsset.hasTokenMint()) {
                add(
                    HistoryDetailRow(
                        "Token you receive",
                        swap.outputAsset.tokenMint,
                        HistoryDetailRowLayout.Block,
                    )
                )
            }
            add(HistoryDetailRow("Most the price could move", "${swap.slippageBps / 100.0}%"))
            walletRow(swap.wallet)
        }
        else -> Unit
    }
}

private fun MutableList<HistoryDetailRow>.walletRow(wallet: String) {
    if (wallet.isNotBlank()) add(HistoryDetailRow("Wallet", wallet, HistoryDetailRowLayout.Block))
}

private fun tokenRow(asset: Asset): HistoryDetailRow =
    if (asset.hasTokenMint()) {
        HistoryDetailRow("Token", asset.tokenMint, HistoryDetailRowLayout.Block)
    } else {
        HistoryDetailRow("Token", "SOL")
    }

/** Native SOL is written in SOL; a token amount stays in the base units it was sent in. */
private fun amountText(amount: String, asset: Asset?): String =
    if (asset == null || asset.hasNativeSol()) {
        amount.toULongOrNull()?.let { "${formatBaseUnits(it, LAMPORT_DECIMALS)} SOL" } ?: amount
    } else {
        "$amount base units"
    }

private fun Asset.symbol(): String = if (hasTokenMint()) "Token" else "SOL"

private fun SignMessageAction.messageText(): String =
    when (contentCase) {
        SignMessageAction.ContentCase.TEXT -> text
        SignMessageAction.ContentCase.DATA -> Base64.getEncoder().encodeToString(data.toByteArray())
        else -> ""
    }

private fun StakingOperation.word(): String =
    when (this) {
        StakingOperation.STAKING_OPERATION_STAKE -> "Stake"
        StakingOperation.STAKING_OPERATION_UNSTAKE -> "Unstake"
        StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE -> "Cancel unstaking"
        StakingOperation.STAKING_OPERATION_WITHDRAW -> "Withdraw"
        else -> "Staking"
    }

private fun Action.transactionLabel(): String =
    when (kindCase) {
        Action.KindCase.TRANSFER -> "Transfer"
        Action.KindCase.SWAP -> "Swap"
        Action.KindCase.STAKING -> staking.operation.word()
        else -> "Transaction"
    }

private fun Action.network(): Network? =
    when (kindCase) {
        Action.KindCase.TRANSFER -> transfer.network
        Action.KindCase.SWAP -> swap.network
        Action.KindCase.STAKING -> staking.network
        else -> null
    }?.takeIf { it != Network.NETWORK_UNSPECIFIED && it != Network.UNRECOGNIZED }

// A feed's signal -----------------------------------------------------------------------------

/**
 * The page for a signal this phone closed. [choice] is the owner's recorded choice, already put
 * into words by the caller from the provider's compiled form; nothing about it is recomputed.
 */
fun signalHistoryDetail(
    record: ProposalRecord,
    standing: ProposalStanding,
    connection: Connection?,
    choice: List<HistoryDetailRow> = emptyList(),
    clock: HistoryDetailClock = HistoryDetailClock(),
): HistoryDetailModel {
    val proposal = record.proposal
    val feed = connection?.label ?: proposal.key.serverId
    val execution = record.execution
    val binding = execution?.binding
    // The promise the operation was bound under; with nothing bound, the one the feed keeps.
    val environment =
        when (binding?.environment ?: connection?.environment ?: PluginEnvironment.Production) {
            PluginEnvironment.Sandbox -> HistoryDetailEnvironment.Sandbox
            PluginEnvironment.Production -> HistoryDetailEnvironment.Production
        }
    val sandbox = environment == HistoryDetailEnvironment.Sandbox
    val network = binding?.network?.takeIf { it != Network.NETWORK_UNSPECIFIED }
    val outcome = (standing as? ProposalStanding.Executed)?.outcome
    val submitted = outcome as? ProposalOutcome.Submitted
    val envelope = proposal.commonEnvelope()
    val executedAt = execution?.let { it.settledAt ?: it.startedAt }

    val status =
        when (standing) {
            is ProposalStanding.Executed ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation =
                        when (val it = standing.outcome) {
                            is ProposalOutcome.Submitted ->
                                "You approved on this phone. Your wallet sent it to the network."
                            ProposalOutcome.Simulated ->
                                "You approved the simulation on this phone. Nothing was signed."
                            ProposalOutcome.Declined ->
                                "You approved on this phone, then declined in the wallet. " +
                                    "Nothing was signed."
                            is ProposalOutcome.Failed ->
                                "You approved on this phone. Nothing was signed: ${it.detail}"
                            is ProposalOutcome.Unresolved ->
                                "You approved on this phone. This phone never learned whether " +
                                    "the wallet sent it."
                            ProposalOutcome.Pending ->
                                "You approved on this phone. The wallet hasn't answered yet."
                        },
                )
            is ProposalStanding.Dismissed ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Dismissed,
                    explanation = "You dismissed it on this phone.",
                )
            ProposalStanding.Cancelled ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Cancelled,
                    explanation =
                        "$feed withdrew it at ${clock.time(proposal.updatedAt)}, before you acted.",
                )
            ProposalStanding.Expired ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Expired,
                    explanation =
                        "It expired at ${clock.time(proposal.expiresAt)} before you acted.",
                )
            is ProposalStanding.Refused ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Refused,
                    explanation =
                        "$feed changed its terms without a new revision, so this phone won't act " +
                            "on either version.",
                )
            is ProposalStanding.Unsupported ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Unsupported,
                    explanation = "$feed needs something this version of the app doesn't have.",
                )
            ProposalStanding.Open ->
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Unsupported,
                    explanation = "It is still waiting for you under Pending.",
                )
        }

    val response =
        when {
            execution != null ->
                HistoryDetailResponse.Sent(
                    timestampText = clock.stamp(execution.startedAt),
                    rows =
                        listOf(
                            HistoryDetailRow(
                                "Decision",
                                if (sandbox) "Approved (simulation)" else "Approved",
                            )
                        ) + choice,
                )
            standing is ProposalStanding.Dismissed ->
                HistoryDetailResponse.Sent(
                    timestampText = clock.stamp(standing.at),
                    rows = listOf(HistoryDetailRow("Decision", "Dismissed")),
                    note = "Feeds aren't told when you dismiss a signal.",
                )
            standing == ProposalStanding.Expired ->
                HistoryDetailResponse.None(
                    "You didn't act on it before it expired. This isn't recorded as a decline."
                )
            standing == ProposalStanding.Cancelled ->
                HistoryDetailResponse.None(
                    "The feed withdrew it before you acted. Nothing was signed."
                )
            else -> HistoryDetailResponse.None("You didn't act on it. Nothing was signed.")
        }

    // Nothing in this build follows a signal's transaction to the chain, so a sent one stays
    // waiting: the page says so rather than implying a check that will never come.
    val executionModel =
        when {
            executedAt == null -> null
            outcome is ProposalOutcome.Submitted ->
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Pending,
                    title = "Waiting for network confirmation",
                    body =
                        "Sent at ${clock.preciseTime(executedAt)}. This phone doesn't follow a " +
                            "signal's transaction on the network; the explorer shows where it " +
                            "stands. Approving it doesn't mean it went through.",
                )
            outcome == ProposalOutcome.Simulated ->
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Simulated,
                    title = "Simulated · No funds moved",
                    body =
                        "Built and simulated at ${clock.preciseTime(executedAt)}. It was never " +
                            "signed or sent.",
                )
            else -> null
        }

    val signature = submitted?.signature?.let { encodeBase58(it.toByteArray()) }
    val transactions =
        if (signature != null && !sandbox) {
            listOf(
                HistoryDetailTransaction(
                    label = actionLabel(proposal.action.value),
                    signature = signature,
                    status = HistoryDetailTransactionStatus.Pending,
                    explorerLabel = "View on explorer" + (network.word()?.let { " · $it" } ?: ""),
                    explorerUrl = explorerUrl(signature, network),
                )
            )
        } else {
            emptyList()
        }

    val timeline = buildList {
        add(entry(HistoryDetailEvent.Received, "Received", proposal.createdAt, clock))
        if (execution != null) {
            add(entry(HistoryDetailEvent.Approved, "You approved", execution.startedAt, clock))
            val settled = executedAt ?: execution.startedAt
            when (outcome) {
                is ProposalOutcome.Submitted ->
                    add(entry(HistoryDetailEvent.Sent, "Sent to the network", settled, clock))
                ProposalOutcome.Simulated ->
                    add(entry(HistoryDetailEvent.Simulated, "Simulated", settled, clock))
                else -> Unit
            }
        }
        if (standing is ProposalStanding.Dismissed) {
            add(entry(HistoryDetailEvent.Dismissed, "You dismissed", standing.at, clock))
        }
        if (standing == ProposalStanding.Expired) {
            add(entry(HistoryDetailEvent.Expired, "Expired", proposal.expiresAt, clock))
        }
        if (standing == ProposalStanding.Cancelled) {
            add(
                entry(
                    HistoryDetailEvent.Cancelled,
                    "Cancelled by $feed",
                    proposal.updatedAt,
                    clock,
                )
            )
        }
    }
        .sortedBy { it.first }
        .map { it.second }

    val identifiers = buildList {
        add(HistoryDetailRow("Signal ID", proposal.key.proposalId))
        binding?.instrument?.id?.takeIf(String::isNotBlank)?.let {
            add(HistoryDetailRow("Market ID", it))
        }
        signature?.let { add(HistoryDetailRow("Signature", it)) }
        when (outcome) {
            is ProposalOutcome.Failed -> add(HistoryDetailRow("Error", outcome.detail))
            is ProposalOutcome.Unresolved -> add(HistoryDetailRow("Error", outcome.detail))
            else -> Unit
        }
    }

    return HistoryDetailModel(
        header =
            HistoryDetailHeader(
                origin = HistoryDetailOrigin.Signal,
                sourceName = feed,
                sourceColour = connection?.colour?.sourceColour(),
                title = envelope.inboxTitle(envelope.action.capabilityId),
                environment = environment,
                networkText = network.word(),
            ),
        status = status,
        response = response,
        execution = executionModel,
        originalDescription = proposal.note.takeIf(String::isNotBlank),
        // The publisher's own names and values, as they were written: carried, not interpreted.
        originalRows =
            proposal.values.map {
                HistoryDetailRow(
                    label = it.key,
                    value = it.text,
                    layout =
                        if (it.text.length > LongValue && ' ' !in it.text) {
                            HistoryDetailRowLayout.Block
                        } else {
                            HistoryDetailRowLayout.Inline
                        },
                )
            },
        transactions = transactions,
        timeline = timeline,
        identifiers = identifiers,
        footnote =
            if (sandbox) HistoryDetailCopy.SandboxFootnote
            else HistoryDetailCopy.ProductionFootnote,
    )
}

private fun actionLabel(action: String): String =
    when {
        action.startsWith("prediction") -> "Place order"
        action == "swap" -> "Swap"
        else -> action.replaceFirstChar { it.uppercase() }
    }

// Shared --------------------------------------------------------------------------------------

private fun entry(
    event: HistoryDetailEvent,
    text: String,
    at: Instant,
    clock: HistoryDetailClock,
): Pair<Instant, HistoryDetailTimelineEntry> =
    at to HistoryDetailTimelineEntry(event = event, text = text, timeText = clock.time(at))

private fun Network?.word(): String? =
    when (this) {
        Network.NETWORK_MAINNET -> "Mainnet"
        Network.NETWORK_DEVNET -> "Devnet"
        Network.NETWORK_TESTNET -> "Testnet"
        else -> null
    }

private fun com.google.protobuf.Timestamp.instant(): Instant =
    Instant.ofEpochSecond(seconds, nanos.toLong())

private fun com.google.protobuf.Timestamp.instantOrNull(): Instant? = takeIf {
    seconds != 0L || nanos != 0
}
    ?.instant()

private const val LongValue = 32

object HistoryDetailCopy {
    const val ProductionFootnote =
        "Recorded on this phone when it happened. Current rules and market prices don't change " +
            "what's shown here."
    const val SandboxFootnote =
        "Recorded on this phone when it happened. A sandbox item was never signed or sent, so it " +
            "has no transaction."
    const val Unknown = "Known after confirmation"
}

/**
 * The owner's recorded choice, in the words of the provider's own compiled form: its labels, its
 * options and its decimals. Nothing is fetched and nothing is re-quoted; a key the form doesn't
 * name is shown by its key and its value as stored.
 */
fun choiceRows(
    choice: ParameterChoice,
    form: ParameterForm,
    text: (Int) -> String,
): List<HistoryDetailRow> =
    choice.values.entries
        .sortedBy { (key, _) ->
            form.fields.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
        .map { (key, value) ->
            val field = form.fields.firstOrNull { it.key == key }
            val kind = field?.kind
            HistoryDetailRow(
                label = field?.let { text(it.label) } ?: key.value,
                value =
                    when (value) {
                        is ParameterValue.Amount ->
                            if (kind is ParameterKind.Amount) {
                                formatBaseUnits(value.baseUnits, kind.decimals) +
                                    if (kind.mint == null) " SOL" else ""
                            } else {
                                value.baseUnits.toString()
                            }
                        is ParameterValue.Selected ->
                            (kind as? ParameterKind.Choice)
                                ?.options
                                ?.firstOrNull { it.key == value.option }
                                ?.let { text(it.label) } ?: value.option.value
                        is ParameterValue.Count -> value.value.toString()
                    },
            )
        }
