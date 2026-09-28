package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExtensionOff
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import kotlinx.coroutines.delay

/*
 * The read-only record behind one Inbox History row (SEE-161,
 * docs/design/history-details/TASK.md). Everything here is drawn from a model the app builds from
 * its stored record; nothing in this file can approve, decline, edit, simulate or re-quote.
 */

/** Whether the record was a private request or a feed's signal. */
enum class HistoryDetailOrigin {
    Request,
    Signal,
}

/** The promise the record was made under, as recorded — never the connection's setting now. */
enum class HistoryDetailEnvironment {
    Production,
    Sandbox,
}

/** The final status (A2). Approved never means the transaction succeeded; that is A5's. */
enum class HistoryDetailStatus {
    Approved,
    Declined,
    Dismissed,
    Expired,
    Cancelled,

    /** This phone refused a signal whose publisher contradicted itself. */
    Refused,

    /** This build can't carry out what the signal's server needs. */
    Unsupported,
}

/** What ran after the decision (A5). */
enum class HistoryDetailExecutionState {
    Pending,
    Confirmed,
    Failed,
    Signed,
    Simulated,
}

/** One transaction's own state (A7). */
enum class HistoryDetailTransactionStatus {
    Pending,
    Confirmed,
    Failed,
}

/** Each event the timeline can show (A8); only recorded events are ever passed. */
enum class HistoryDetailEvent {
    Received,
    Approved,
    Declined,
    Dismissed,
    Sent,
    Confirmed,
    Failed,
    Signed,
    Simulated,
    Delivered,
    DeliveryUnconfirmed,
    Expired,
    Cancelled,
}

/** How a label and its value are laid out. */
enum class HistoryDetailRowLayout {
    /** Label left, value right. */
    Inline,

    /** Label above a mono inner box that wraps: addresses, messages, anything never to be cut. */
    Block,
}

data class HistoryDetailRow(
    val label: String,
    val value: String,
    val layout: HistoryDetailRowLayout = HistoryDetailRowLayout.Inline,
)

/** A1 — title and source. */
data class HistoryDetailHeader(
    val origin: HistoryDetailOrigin,
    /** The connection's label; for a signal, the feed's name. */
    val sourceName: String,
    /** The colour the owner assigned, or null for the palette's own pick by name. */
    val sourceColour: SourceColour?,
    val title: String,
    val environment: HistoryDetailEnvironment,
    /** "Mainnet", "Devnet" or "Testnet"; null leaves the chip out rather than guessing one. */
    val networkText: String?,
)

/** A2 — the final status, with who acted and where, and a server's reason when it gave one. */
data class HistoryDetailStatusModel(
    val status: HistoryDetailStatus,
    val explanation: String,
    val reasonLabel: String? = null,
    val reason: String? = null,
)

/** A3 — only what the owner actually submitted. */
sealed interface HistoryDetailResponse {
    data class Sent(
        /** With seconds, such as `Sep 26, 9:00:41 PM`. */
        val timestampText: String,
        val rows: List<HistoryDetailRow>,
        val message: String? = null,
        val note: String? = null,
    ) : HistoryDetailResponse

    /** Nothing was sent, and [explanation] says it isn't a decline. */
    data class None(val explanation: String) : HistoryDetailResponse
}

/** A4 — a stored response the server hasn't acknowledged. */
data class HistoryDetailDelivery(
    val title: String,
    val body: String,
    /** True while a resend is in flight, so the one button can't be tapped twice. */
    val sending: Boolean = false,
    /** False when the server no longer accepts this phone, so sending again can't help. */
    val canSendAgain: Boolean = true,
)

/** A5 — shown only when something ran: a transaction, a signature or a simulation. */
data class HistoryDetailExecution(
    val state: HistoryDetailExecutionState,
    val title: String,
    val body: String,
    val rows: List<HistoryDetailRow> = emptyList(),
    /** For [HistoryDetailExecutionState.Failed]: the readable reason, never the raw error. */
    val failureReason: String? = null,
    /**
     * Whether the owner can ask the network about this transaction now (SEE-165); null when there
     * is nothing a check could change. It asks, and never signs or sends anything.
     */
    val checkStatus: HistoryDetailCheckStatus? = null,
)

/** The execution card's "Check status" control, and whether a check is already out. */
data class HistoryDetailCheckStatus(val checking: Boolean = false)

/** A7 — one transaction, in execution order. */
data class HistoryDetailTransaction(
    val label: String,
    /** The full signature; the card shortens it and copies it whole. */
    val signature: String,
    val status: HistoryDetailTransactionStatus,
    /** "View on explorer · Mainnet". */
    val explorerLabel: String,
    /** Null when the record names no cluster: a guessed link is a wrong link. */
    val explorerUrl: String?,
)

/** A8 — one recorded event. */
data class HistoryDetailTimelineEntry(
    val event: HistoryDetailEvent,
    val text: String,
    val timeText: String,
)

/** The whole page, in the fixed order. A null or empty optional section is left out entirely. */
data class HistoryDetailModel(
    val header: HistoryDetailHeader,
    val status: HistoryDetailStatusModel,
    val response: HistoryDetailResponse,
    val delivery: HistoryDetailDelivery? = null,
    val execution: HistoryDetailExecution? = null,
    val originalDescription: String? = null,
    val originalRows: List<HistoryDetailRow> = emptyList(),
    val transactions: List<HistoryDetailTransaction> = emptyList(),
    val timeline: List<HistoryDetailTimelineEntry> = emptyList(),
    val identifiers: List<HistoryDetailRow> = emptyList(),
    val footnote: String,
    /**
     * The position a prediction purchase went into, read live from the provider (SEE-172). It is
     * never part of the stored record: the purchase above stays what it was, and this says what the
     * wallet holds now.
     */
    val position: HistoryDetailPosition? = null,
)

/** Where the live position stands (SEE-172). */
enum class HistoryDetailPositionState {
    /** Not read yet. */
    Loading,
    /** Read just now. */
    Live,
    /** The last good read is shown; the latest attempt failed or is old. */
    Stale,
    /** Nothing can be said: no reference, not indexed yet, or no provider for it. */
    Unavailable,
    /** Sold, or nothing is held any more. */
    Closed,
    /** The market settled. Claimed on the provider, never sold here. */
    Settled,
}

/** How a sale attempt stands. */
enum class HistoryDetailSaleState {
    Pending,
    Done,
    Failed,
}

/** One sale attempt, its own operation beside the purchase. */
data class HistoryDetailSale(
    val title: String,
    val state: HistoryDetailSaleState,
    val stateText: String,
    val rows: List<HistoryDetailRow> = emptyList(),
)

/** Selling, when it is offered at all: enabled, or disabled with the precise reason. */
data class HistoryDetailSell(val enabled: Boolean, val reason: String? = null)

/** A place on the provider's own platform. */
data class HistoryDetailLink(val label: String, val url: String)

data class HistoryDetailPosition(
    val state: HistoryDetailPositionState,
    /** The chip: "Live", "Last known · 9:01 PM", "Not indexed yet". */
    val stateText: String,
    /** That this is the wallet's whole holding of the outcome, not only this signal's stake. */
    val scope: String? = null,
    /** The purchase's own order as the provider reports it, apart from the chain result. */
    val orderRows: List<HistoryDetailRow> = emptyList(),
    val rows: List<HistoryDetailRow> = emptyList(),
    val note: String? = null,
    val refreshing: Boolean = false,
    val sell: HistoryDetailSell? = null,
    val links: List<HistoryDetailLink> = emptyList(),
    val sales: List<HistoryDetailSale> = emptyList(),
)

data class HistoryDetailCallbacks(
    val onBack: () -> Unit,
    /** Re-sends the stored response only; it never changes the decision. */
    val onSendAgain: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onOpenExplorer: (String) -> Unit = {},
    /** Asks the network about the transaction again; it never signs or sends (SEE-165). */
    val onCheckStatus: () -> Unit = {},
    /** Reads the position again. It never prepares, signs or sends (SEE-172). */
    val onRefreshPosition: () -> Unit = {},
    /** Opens the sale review. Nothing is built until the review asks for it. */
    val onSellPosition: () -> Unit = {},
    /** Opens a provider link, app first. */
    val onOpenProvider: (String) -> Unit = {},
)

object HistoryDetailTags {
    const val Page = "historyDetail"
    const val Title = "historyDetailTitle"
    const val Status = "historyDetailStatus"
    const val Response = "historyDetailResponse"
    const val Delivery = "historyDetailDelivery"
    const val SendAgain = "historyDetailSendAgain"
    const val Execution = "historyDetailExecution"
    const val CheckStatus = "historyDetailCheckStatus"
    const val Original = "historyDetailOriginal"
    const val Transactions = "historyDetailTransactions"
    const val Timeline = "historyDetailTimeline"
    const val Identifiers = "historyDetailIdentifiers"
    const val Footnote = "historyDetailFootnote"
    const val Position = "historyDetailPosition"
    const val PositionState = "historyDetailPositionState"
    const val PositionRefresh = "historyDetailPositionRefresh"
    const val PositionSell = "historyDetailPositionSell"
    const val PositionSellReason = "historyDetailPositionSellReason"

    fun positionLink(index: Int) = "historyDetailPositionLink:$index"

    fun positionSale(index: Int) = "historyDetailPositionSale:$index"

    fun copy(index: Int) = "historyDetailCopy:$index"

    fun explorer(index: Int) = "historyDetailExplorer:$index"
}

/** The full page: app bar with Back and the title "History", and no bottom navigation. */
@Composable
fun HistoryDetailScreen(
    model: HistoryDetailModel,
    callbacks: HistoryDetailCallbacks,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    DetailScreenScaffold(title = "History", onBack = callbacks.onBack, modifier = modifier) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .testTag(HistoryDetailTags.Page)
                .padding(
                    start = SeekerTheme.spacing.xl,
                    top = SeekerTheme.spacing.xs,
                    end = SeekerTheme.spacing.xl,
                    // 24 of breathing room plus the 34 the home indicator takes.
                    bottom =
                        SeekerTheme.spacing.xxxl +
                            SeekerTheme.spacing.jumbo +
                            SeekerTheme.spacing.xxs,
                )
        ) {
            HistoryDetailBody(model = model, callbacks = callbacks)
        }
    }
}

/** The page's content without the scaffold, in the fixed order (TASK.md §2). */
@Composable
fun HistoryDetailBody(
    model: HistoryDetailModel,
    callbacks: HistoryDetailCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        HistoryDetailTitle(model.header)
        HistoryDetailStatusCard(model.status, model.header.sourceName)
        HistoryDetailResponseCard(model.response)
        model.delivery?.let { HistoryDetailDeliveryCard(it, callbacks.onSendAgain) }
        model.execution?.let { HistoryDetailExecutionCard(it, callbacks.onCheckStatus) }
        model.position?.let { HistoryDetailPositionCard(it, callbacks) }
        HistoryDetailOriginalCard(
            origin = model.header.origin,
            description = model.originalDescription,
            rows = model.originalRows,
        )
        if (model.transactions.isNotEmpty()) {
            HistoryDetailTransactions(
                transactions = model.transactions,
                onCopy = callbacks.onCopy,
                onOpenExplorer = callbacks.onOpenExplorer,
            )
        }
        if (model.timeline.isNotEmpty()) HistoryDetailTimeline(model.timeline)
        if (model.identifiers.isNotEmpty()) HistoryDetailIdentifiers(model.identifiers)
        Text(
            text = model.footnote,
            modifier =
                Modifier.padding(top = SeekerTheme.spacing.xs).testTag(HistoryDetailTags.Footnote),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// A1 ------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryDetailTitle(header: HistoryDetailHeader) {
    Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (header.origin == HistoryDetailOrigin.Signal) {
                HistoryDetailChip(text = "Signal", icon = Icons.Outlined.RssFeed)
            }
            SourceChip(
                sourceName = header.sourceName,
                colour = header.sourceColour,
                size = SourceChipSize.Compact,
            )
        }
        Text(
            text = header.title,
            modifier = Modifier.testTag(HistoryDetailTags.Title).semantics { heading() },
            style =
                MaterialTheme.typography.headlineLarge.copy(
                    lineHeight = MaterialTheme.typography.headlineLarge.fontSize * TitleLineHeight
                ),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
        ) {
            when (header.environment) {
                HistoryDetailEnvironment.Production -> HistoryDetailChip(text = "Production")
                // Past tense: this is what happened, not what the pending flow says will.
                HistoryDetailEnvironment.Sandbox ->
                    HistoryDetailChip(
                        text = "Sandbox · no funds moved",
                        tone = HistoryDetailChipTone.Tertiary,
                    )
            }
            header.networkText?.let { HistoryDetailChip(text = it, icon = Icons.Outlined.Public) }
        }
    }
}

// A2 ------------------------------------------------------------------------------------------

@Composable
private fun HistoryDetailStatusCard(model: HistoryDetailStatusModel, sourceName: String) {
    HistoryDetailCard(Modifier.testTag(HistoryDetailTags.Status)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg)) {
            val (container, content) =
                when (model.status) {
                    HistoryDetailStatus.Approved ->
                        SeekerTheme.colors.limeContainer to SeekerTheme.colors.onLimeContainer
                    HistoryDetailStatus.Declined ->
                        SeekerTheme.colors.destructive to SeekerTheme.colors.onDestructive
                    HistoryDetailStatus.Dismissed,
                    HistoryDetailStatus.Expired,
                    HistoryDetailStatus.Cancelled,
                    HistoryDetailStatus.Refused,
                    HistoryDetailStatus.Unsupported ->
                        SeekerTheme.colors.surface3 to MaterialTheme.colorScheme.onSurface
                }
            Box(
                Modifier.size(SeekerTheme.sizes.iconButton.medium.box)
                    .clip(CircleShape)
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = model.status.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(SeekerTheme.sizes.iconButton.large.glyph),
                    tint = content,
                )
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
            ) {
                HistoryDetailSmallLabel("Final status")
                Text(text = model.status.word(), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = model.explanation,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (model.reason != null) {
            HistoryDetailInnerBox(
                label = model.reasonLabel ?: "Reason given by $sourceName",
                text = model.reason,
            )
        }
    }
}

// A3 ------------------------------------------------------------------------------------------

@Composable
private fun HistoryDetailResponseCard(response: HistoryDetailResponse) {
    HistoryDetailCard(Modifier.testTag(HistoryDetailTags.Response)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Your response",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (response is HistoryDetailResponse.Sent) {
                Text(
                    text = response.timestampText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        when (response) {
            is HistoryDetailResponse.Sent -> {
                response.rows.forEach { HistoryDetailRowView(it) }
                response.message?.let {
                    HistoryDetailInnerBox(label = "Message you sent", text = it)
                }
                response.note?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            is HistoryDetailResponse.None -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RemoveCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(SeekerTheme.sizes.iconButton.large.glyph),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = "No response sent", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = response.explanation,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// A4 ------------------------------------------------------------------------------------------

@Composable
private fun HistoryDetailDeliveryCard(model: HistoryDetailDelivery, onSendAgain: () -> Unit) {
    HistoryDetailCard(
        modifier = Modifier.testTag(HistoryDetailTags.Delivery),
        container = SeekerTheme.colors.orangeContainer,
    ) {
        val content = SeekerTheme.colors.onOrangeContainer
        Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
            Icon(
                imageVector = Icons.Outlined.SyncProblem,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.sizes.iconButton.large.glyph),
                tint = content,
            )
            Text(
                text = model.title,
                modifier = Modifier.weight(1f),
                color = content,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(text = model.body, color = content, style = MaterialTheme.typography.bodyMedium)
        if (model.canSendAgain)
            SeekerButton(
                label = "Send again",
                onClick = onSendAgain,
                variant = SeekerButtonVariant.Tertiary,
                size = SeekerButtonSize.Md,
                enabled = !model.sending,
                modifier = Modifier.testTag(HistoryDetailTags.SendAgain),
            )
    }
}

// A5 ------------------------------------------------------------------------------------------

@Composable
private fun HistoryDetailExecutionCard(model: HistoryDetailExecution, onCheckStatus: () -> Unit) {
    val container =
        when (model.state) {
            HistoryDetailExecutionState.Failed -> SeekerTheme.colors.destructiveContainer
            HistoryDetailExecutionState.Simulated -> SeekerTheme.colors.orangeContainer
            else -> SeekerTheme.colors.surface1
        }
    val content =
        when (model.state) {
            HistoryDetailExecutionState.Failed -> SeekerTheme.colors.onDestructiveContainer
            HistoryDetailExecutionState.Simulated -> SeekerTheme.colors.onOrangeContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    val iconTint =
        when (model.state) {
            HistoryDetailExecutionState.Confirmed,
            HistoryDetailExecutionState.Signed -> SeekerTheme.colors.lime
            HistoryDetailExecutionState.Pending -> SeekerTheme.colors.orange
            HistoryDetailExecutionState.Failed,
            HistoryDetailExecutionState.Simulated -> content
        }
    val label =
        when (model.state) {
            HistoryDetailExecutionState.Signed -> "Result"
            HistoryDetailExecutionState.Simulated -> "Simulated result"
            else -> "Execution result"
        }
    HistoryDetailCard(
        modifier = Modifier.testTag(HistoryDetailTags.Execution),
        container = container,
    ) {
        Text(text = label, color = content, style = MaterialTheme.typography.labelMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = model.state.icon(),
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.sizes.iconButton.large.glyph),
                tint = iconTint,
            )
            Text(
                text = model.title,
                modifier = Modifier.weight(1f),
                color = content,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(text = model.body, color = content, style = MaterialTheme.typography.bodyMedium)
        model.rows.forEach { HistoryDetailRowView(it, content = content, labelColor = content) }
        model.failureReason?.let {
            HistoryDetailInnerBox(
                label = "Failure reason",
                text = it,
                container = SeekerTheme.colors.onDestructive,
                content = content,
                labelColor = content,
            )
        }
        // The delivery card's control, for the network rather than the server (SEE-165).
        model.checkStatus?.let {
            SeekerButton(
                label = if (it.checking) "Checking…" else "Check status",
                onClick = onCheckStatus,
                variant = SeekerButtonVariant.Tertiary,
                size = SeekerButtonSize.Md,
                enabled = !it.checking,
                modifier = Modifier.testTag(HistoryDetailTags.CheckStatus),
            )
        }
    }
}

// A6 ------------------------------------------------------------------------------------------

@Composable
private fun HistoryDetailOriginalCard(
    origin: HistoryDetailOrigin,
    description: String?,
    rows: List<HistoryDetailRow>,
) {
    HistoryDetailCard(Modifier.testTag(HistoryDetailTags.Original)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text =
                    when (origin) {
                        HistoryDetailOrigin.Request -> "Original request"
                        HistoryDetailOrigin.Signal -> "Original signal"
                    },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(SeekerTheme.spacing.lgPlus),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HistoryDetailSmallLabel("As received")
            }
        }
        description
            ?.takeIf { it.isNotBlank() }
            ?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        rows.forEach { HistoryDetailRowView(it) }
    }
}

// A7 ------------------------------------------------------------------------------------------

@Composable
private fun HistoryDetailTransactions(
    transactions: List<HistoryDetailTransaction>,
    onCopy: (String) -> Unit,
    onOpenExplorer: (String) -> Unit,
) {
    Column(
        Modifier.testTag(HistoryDetailTags.Transactions),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
    ) {
        HistoryDetailHeading(
            if (transactions.size == 1) "Transaction" else "Transactions · ${transactions.size}"
        )
        transactions.forEachIndexed { index, transaction ->
            HistoryDetailTransactionCard(
                index = index,
                transaction = transaction,
                onCopy = onCopy,
                onOpenExplorer = onOpenExplorer,
            )
        }
    }
}

@Composable
private fun HistoryDetailTransactionCard(
    index: Int,
    transaction: HistoryDetailTransaction,
    onCopy: (String) -> Unit,
    onOpenExplorer: (String) -> Unit,
) {
    HistoryDetailCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = transaction.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            when (transaction.status) {
                HistoryDetailTransactionStatus.Confirmed ->
                    HistoryDetailChip(text = "Confirmed", icon = Icons.Outlined.CheckCircle)
                HistoryDetailTransactionStatus.Pending ->
                    HistoryDetailChip(
                        text = "Pending",
                        icon = Icons.Outlined.HourglassTop,
                        tone = HistoryDetailChipTone.Tertiary,
                    )
                HistoryDetailTransactionStatus.Failed ->
                    HistoryDetailChip(
                        text = "Failed",
                        icon = Icons.Outlined.ErrorOutline,
                        tone = HistoryDetailChipTone.Error,
                    )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
            ) {
                Text(
                    text = "Signature",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = shortSignature(transaction.signature),
                    style = SeekerTheme.typography.identifier,
                )
            }
            HistoryDetailCopyButton(
                value = transaction.signature,
                onCopy = onCopy,
                modifier = Modifier.testTag(HistoryDetailTags.copy(index)),
            )
        }
        val explorerUrl = transaction.explorerUrl ?: return@HistoryDetailCard
        Row(
            modifier =
                Modifier.clip(RoundedCornerShape(SeekerTheme.radii.xs))
                    .clickable(role = Role.Button) { onOpenExplorer(explorerUrl) }
                    .testTag(HistoryDetailTags.explorer(index))
                    .padding(vertical = SeekerTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = transaction.explorerLabel,
                color = SeekerTheme.colors.lime,
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.lg + SeekerTheme.spacing.sm),
                tint = SeekerTheme.colors.lime,
            )
        }
    }
}

@Composable
private fun HistoryDetailCopyButton(
    value: String,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedMillis)
            copied = false
        }
    }
    OrganismIconAction(
        icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
        contentDescription = if (copied) "Copied" else "Copy signature",
        onClick = {
            onCopy(value)
            copied = true
        },
        size = OrganismIconActionSize.Medium,
        style = OrganismIconActionStyle.Neutral,
        modifier = modifier,
    )
}

// A8 ------------------------------------------------------------------------------------------

@Composable
private fun HistoryDetailTimeline(entries: List<HistoryDetailTimelineEntry>) {
    Column(
        Modifier.testTag(HistoryDetailTags.Timeline),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
    ) {
        HistoryDetailHeading("Timeline")
        HistoryDetailCard(spacing = SeekerTheme.spacing.lg) {
            entries.forEach { entry ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = entry.event.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(SeekerTheme.spacing.lg + SeekerTheme.spacing.sm),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entry.text,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = entry.timeText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFeatureSettings = TabularNumbers
                            ),
                    )
                }
            }
        }
    }
}

// A9 ------------------------------------------------------------------------------------------

@Composable
private fun HistoryDetailIdentifiers(identifiers: List<HistoryDetailRow>) {
    Column(
        Modifier.testTag(HistoryDetailTags.Identifiers),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
    ) {
        HistoryDetailHeading("Identifiers")
        HistoryDetailCard(spacing = SeekerTheme.spacing.lg) {
            identifiers.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs)) {
                    HistoryDetailSmallLabel(row.label)
                    Text(text = row.value, style = SeekerTheme.typography.identifier)
                }
            }
        }
    }
}

// Shared pieces --------------------------------------------------------------------------------

// Position (SEE-172) ---------------------------------------------------------------------------

@Composable
private fun HistoryDetailPositionCard(
    model: HistoryDetailPosition,
    callbacks: HistoryDetailCallbacks,
) {
    HistoryDetailCard(Modifier.testTag(HistoryDetailTags.Position)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Position now",
                modifier = Modifier.weight(1f).semantics { heading() },
                color = SeekerTheme.colors.lime,
                style = MaterialTheme.typography.titleSmall,
            )
            Box(Modifier.testTag(HistoryDetailTags.PositionState)) {
                when (model.state) {
                    HistoryDetailPositionState.Live ->
                        HistoryDetailChip(text = model.stateText, icon = Icons.Outlined.CheckCircle)
                    HistoryDetailPositionState.Loading,
                    HistoryDetailPositionState.Stale ->
                        HistoryDetailChip(
                            text = model.stateText,
                            icon = Icons.Outlined.HourglassTop,
                            tone = HistoryDetailChipTone.Tertiary,
                        )
                    HistoryDetailPositionState.Unavailable ->
                        HistoryDetailChip(
                            text = model.stateText,
                            icon = Icons.Outlined.SyncProblem,
                            tone = HistoryDetailChipTone.Tertiary,
                        )
                    HistoryDetailPositionState.Closed,
                    HistoryDetailPositionState.Settled ->
                        HistoryDetailChip(text = model.stateText, icon = Icons.Outlined.Verified)
                }
            }
        }
        model.scope?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (model.orderRows.isNotEmpty()) {
            HistoryDetailRowsBox(label = "Your order", rows = model.orderRows)
        }
        model.rows.forEach { HistoryDetailRowView(it) }
        model.note?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        model.sales.forEachIndexed { index, sale ->
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(SeekerTheme.radii.md))
                    .background(SeekerTheme.colors.surface3)
                    .padding(SeekerTheme.spacing.lg)
                    .testTag(HistoryDetailTags.positionSale(index)),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sale.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    when (sale.state) {
                        HistoryDetailSaleState.Pending ->
                            HistoryDetailChip(
                                text = sale.stateText,
                                icon = Icons.Outlined.HourglassTop,
                                tone = HistoryDetailChipTone.Tertiary,
                            )
                        HistoryDetailSaleState.Done ->
                            HistoryDetailChip(
                                text = sale.stateText,
                                icon = Icons.Outlined.CheckCircle,
                            )
                        HistoryDetailSaleState.Failed ->
                            HistoryDetailChip(
                                text = sale.stateText,
                                icon = Icons.Outlined.ErrorOutline,
                                tone = HistoryDetailChipTone.Error,
                            )
                    }
                }
                sale.rows.forEach { HistoryDetailRowView(it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
            SeekerButton(
                label = if (model.refreshing) "Refreshing…" else "Refresh",
                onClick = callbacks.onRefreshPosition,
                variant = SeekerButtonVariant.Tertiary,
                size = SeekerButtonSize.Md,
                enabled = !model.refreshing,
                modifier = Modifier.testTag(HistoryDetailTags.PositionRefresh),
            )
            model.sell?.let {
                SeekerButton(
                    label = "Sell position",
                    onClick = callbacks.onSellPosition,
                    variant = SeekerButtonVariant.Filled,
                    size = SeekerButtonSize.Md,
                    enabled = it.enabled,
                    modifier = Modifier.testTag(HistoryDetailTags.PositionSell),
                )
            }
        }
        model.sell?.reason?.let {
            Text(
                text = it,
                modifier = Modifier.testTag(HistoryDetailTags.PositionSellReason),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        model.links.forEachIndexed { index, link ->
            SeekerButton(
                label = link.label,
                onClick = { callbacks.onOpenProvider(link.url) },
                variant = SeekerButtonVariant.Tertiary,
                size = SeekerButtonSize.Sm,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(SeekerTheme.spacing.xxl),
                    )
                },
                modifier = Modifier.testTag(HistoryDetailTags.positionLink(index)),
            )
        }
    }
}

@Composable
private fun HistoryDetailRowsBox(label: String, rows: List<HistoryDetailRow>) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(SeekerTheme.radii.md))
            .background(SeekerTheme.colors.surface3)
            .padding(SeekerTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
    ) {
        HistoryDetailSmallLabel(label)
        rows.forEach { HistoryDetailRowView(it) }
    }
}

@Composable
private fun HistoryDetailCard(
    modifier: Modifier = Modifier,
    container: Color = SeekerTheme.colors.surface1,
    spacing: Dp = SeekerTheme.spacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(container)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
private fun HistoryDetailHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = SeekerTheme.spacing.md).semantics { heading() },
        color = SeekerTheme.colors.lime,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun HistoryDetailSmallLabel(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun HistoryDetailRowView(
    row: HistoryDetailRow,
    content: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    when (row.layout) {
        HistoryDetailRowLayout.Inline ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = row.label,
                    color = labelColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = row.value,
                    modifier = Modifier.weight(1f),
                    color = content,
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        HistoryDetailRowLayout.Block ->
            Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
                Text(
                    text = row.label,
                    color = labelColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(SeekerTheme.radii.md))
                        .background(SeekerTheme.colors.surface3)
                        .padding(SeekerTheme.spacing.lg)
                ) {
                    Text(text = row.value, style = SeekerTheme.typography.identifier)
                }
            }
    }
}

@Composable
private fun HistoryDetailInnerBox(
    label: String,
    text: String,
    container: Color = SeekerTheme.colors.surface3,
    content: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(SeekerTheme.radii.md))
            .background(container)
            .padding(SeekerTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
    ) {
        HistoryDetailSmallLabel(label, color = labelColor)
        Text(text = text, color = content, style = MaterialTheme.typography.bodyMedium)
    }
}

private enum class HistoryDetailChipTone {
    Neutral,
    Tertiary,
    Error,
}

/** Height 24, radius 8, 12/500 — the environment, network, Signal and transaction chips. */
@Composable
private fun HistoryDetailChip(
    text: String,
    icon: ImageVector? = null,
    tone: HistoryDetailChipTone = HistoryDetailChipTone.Neutral,
) {
    val (container, content) =
        when (tone) {
            HistoryDetailChipTone.Neutral ->
                SeekerTheme.colors.surface3 to MaterialTheme.colorScheme.onSurface
            HistoryDetailChipTone.Tertiary ->
                SeekerTheme.colors.orangeContainer to SeekerTheme.colors.onOrangeContainer
            HistoryDetailChipTone.Error ->
                SeekerTheme.colors.destructiveContainer to SeekerTheme.colors.onDestructiveContainer
        }
    Row(
        modifier =
            Modifier.height(SeekerTheme.spacing.xxxl)
                .background(container, RoundedCornerShape(SeekerTheme.radii.sm))
                .padding(horizontal = SeekerTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.lgPlus),
                tint = content,
            )
        }
        Text(
            text = text,
            color = content,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** The first 6 and the last 6, so an address is never cut in the middle of what matters. */
fun shortSignature(signature: String): String =
    if (signature.length <= ShortSignatureEdge * 2 + 1) {
        signature
    } else {
        "${signature.take(ShortSignatureEdge)}…${signature.takeLast(ShortSignatureEdge)}"
    }

private fun HistoryDetailStatus.icon(): ImageVector =
    when (this) {
        HistoryDetailStatus.Approved -> Icons.Outlined.CheckCircle
        HistoryDetailStatus.Declined -> Icons.Outlined.Cancel
        HistoryDetailStatus.Dismissed -> Icons.Outlined.DoNotDisturbOn
        HistoryDetailStatus.Expired -> Icons.Outlined.TimerOff
        HistoryDetailStatus.Cancelled -> Icons.Outlined.Block
        HistoryDetailStatus.Refused -> Icons.Outlined.GppMaybe
        HistoryDetailStatus.Unsupported -> Icons.Outlined.ExtensionOff
    }

private fun HistoryDetailStatus.word(): String =
    when (this) {
        HistoryDetailStatus.Approved -> "Approved"
        HistoryDetailStatus.Declined -> "Declined"
        HistoryDetailStatus.Dismissed -> "Dismissed"
        HistoryDetailStatus.Expired -> "Expired"
        HistoryDetailStatus.Cancelled -> "Cancelled by server"
        HistoryDetailStatus.Refused -> "Refused on this phone"
        HistoryDetailStatus.Unsupported -> "Not supported"
    }

private fun HistoryDetailExecutionState.icon(): ImageVector =
    when (this) {
        HistoryDetailExecutionState.Confirmed -> Icons.Outlined.CheckCircle
        HistoryDetailExecutionState.Pending -> Icons.Outlined.HourglassTop
        HistoryDetailExecutionState.Failed -> Icons.Outlined.ErrorOutline
        HistoryDetailExecutionState.Signed -> Icons.Outlined.Draw
        HistoryDetailExecutionState.Simulated -> Icons.Outlined.Science
    }

private fun HistoryDetailEvent.icon(): ImageVector =
    when (this) {
        HistoryDetailEvent.Received -> Icons.Outlined.MoveToInbox
        HistoryDetailEvent.Approved -> Icons.Outlined.Check
        HistoryDetailEvent.Declined -> Icons.Outlined.Close
        HistoryDetailEvent.Dismissed -> Icons.Outlined.DoNotDisturbOn
        HistoryDetailEvent.Sent -> Icons.AutoMirrored.Outlined.Send
        HistoryDetailEvent.Confirmed -> Icons.Outlined.Verified
        HistoryDetailEvent.Failed -> Icons.Outlined.Error
        HistoryDetailEvent.Signed -> Icons.Outlined.Draw
        HistoryDetailEvent.Simulated -> Icons.Outlined.Science
        HistoryDetailEvent.Delivered -> Icons.Outlined.MarkEmailRead
        HistoryDetailEvent.DeliveryUnconfirmed -> Icons.Outlined.SyncProblem
        HistoryDetailEvent.Expired -> Icons.Outlined.TimerOff
        HistoryDetailEvent.Cancelled -> Icons.Outlined.Block
    }

/** The title's line height is 1.15 of its size in the brief, tighter than the scale's 1.2. */
private const val TitleLineHeight = 1.15f
private const val TabularNumbers = "tnum"
private const val ShortSignatureEdge = 6
private const val CopiedMillis = 1_500L
