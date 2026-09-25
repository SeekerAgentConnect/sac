package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/**
 * How the sheet is laid out in its host.
 *
 * [Unrolled] is the comparison canvas the previews capture: every section one after another with no
 * scroll container. [Pinned] is what a phone presents: the grabber and title stay at the top, the
 * decision stays at the bottom, and only the body between them scrolls (SEE-158).
 */
enum class ReviewSheetLayout {
    Unrolled,
    Pinned,
}

enum class ReviewSheetSublineStyle {
    Plain,
    Mono,
}

sealed interface ReviewSheetHeaderChip {
    data object Signal : ReviewSheetHeaderChip

    /** The connection the signal arrived on, in its own stored colour when it has one. */
    data class Feed(val name: String, val colour: SourceColour? = null) : ReviewSheetHeaderChip

    data class Environment(
        val value: EnvChipEnvironment,
        val verbosity: EnvChipVerbosity = EnvChipVerbosity.Full,
    ) : ReviewSheetHeaderChip

    data class Network(val value: NetworkChipNetwork) : ReviewSheetHeaderChip
}

data class ReviewSheetYourPart(
    val kind: OwnerInputCardKind,
    val state: OwnerInputCardState,
    val summary: String? = null,
)

data class ReviewSheetWarning(
    val message: String,
    val sourceLabel: String,
    val kind: VerdictWarningKind = VerdictWarningKind.Finding,
    val source: ScopeChipSource = ScopeChipSource.Global,
)

data class ReviewSheetVerdict(
    val warnings: List<ReviewSheetWarning> = emptyList(),
    val additionalContext: String? = null,
    /** Replaces the counted heading, e.g. "Outside rules · no rules set". */
    val heading: String? = null,
)

data class ReviewSheetDailySpend(
    val title: String,
    val rows: List<ReviewSheetDailySpendRow>,
)

data class ReviewSheetDailySpendRow(
    val headline: String,
    val supportingText: String,
    val state: DailyLimitRowState,
    val scope: DailyLimitRowScope,
)

data class ReviewSheetInfoBlock(val body: String, val title: String? = null)

data class ReviewSheetFactRow(
    val label: String,
    val value: String,
    val valueStyle: FactRowValueStyle = FactRowValueStyle.Plain,
    /** The full value a tap copies, when the row offers one; [value] may be shortened. */
    val copyValue: String? = null,
)

/** The quoted operation: rows once a quote exists, or the line saying what one will show. */
data class ReviewSheetTerms(
    val rows: List<TermsCardRow>,
    val emptyText: String? = null,
    val kind: TermsCardKind = TermsCardKind.Swap,
)

/** A quote that is no longer current, and the action that fetches a fresh one. */
data class ReviewSheetStaleQuote(val message: String, val actionLabel: String)

data class ReviewSheetNote(val label: String, val body: String)

data class ReviewSheetAction(val label: String, val enabled: Boolean = true)

/**
 * Display-only state for every review variant. Domain request, signal, policy, and wallet types
 * remain in `:app`; this model describes only what the design system renders.
 */
data class ReviewSheetState(
    val title: String,
    val headline: String,
    val subline: String,
    val headerChips: List<ReviewSheetHeaderChip>,
    val sandboxNotice: String? = null,
    val yourPart: ReviewSheetYourPart? = null,
    val verdict: ReviewSheetVerdict,
    val dailySpend: ReviewSheetDailySpend? = null,
    val infoBlocks: List<ReviewSheetInfoBlock> = emptyList(),
    val factRows: List<ReviewSheetFactRow> = emptyList(),
    val note: ReviewSheetNote? = null,
    val expiry: String,
    val confirmationCheckbox: String? = null,
    val primaryAction: ReviewSheetAction,
    val secondaryAction: ReviewSheetAction,
    val footerCaption: String,
    val sublineStyle: ReviewSheetSublineStyle = ReviewSheetSublineStyle.Plain,
    /** What went differently from the ordinary review: an outcome, a failure, a refusal. */
    val statusBlocks: List<ReviewSheetInfoBlock> = emptyList(),
    val staleQuote: ReviewSheetStaleQuote? = null,
    val terms: ReviewSheetTerms? = null,
    /** Null keeps the confirmation inside the sheet; a value hands it to the caller. */
    val confirmationChecked: Boolean? = null,
    /** False hides the decision footer entirely, for a request that can no longer be acted on. */
    val actionsShown: Boolean = true,
)

/**
 * The single review-sheet renderer. It deliberately has no domain model overloads and no internal
 * scroll container: previews capture the complete unrolled sheet, while the app owns whatever
 * bounded sheet host eventually presents it.
 */
@Composable
fun ReviewSheet(
    state: ReviewSheetState,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onRules: () -> Unit,
    onChoose: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    layout: ReviewSheetLayout = ReviewSheetLayout.Unrolled,
    onCopy: (String) -> Unit = {},
    onConfirmedChange: (Boolean) -> Unit = {},
    onRefreshQuote: () -> Unit = {},
) {
    val hasWarnings = state.verdict.warnings.isNotEmpty()
    require(!hasWarnings || state.confirmationCheckbox != null) {
        "A warning verdict needs confirmation copy"
    }
    val requiresConfirmation = state.confirmationCheckbox != null
    var confirmedHere by
        rememberSaveable(state.title, state.headline, state.confirmationCheckbox) {
            mutableStateOf(false)
        }
    val confirmed = state.confirmationChecked ?: confirmedHere
    val primaryEnabled = state.primaryAction.enabled && (!requiresConfirmation || confirmed)
    val surface = sheetStackSurfaceColor()
    val ink = sheetStackContentColor()

    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SeekerTheme.radii.sheet))
                    .background(surface)
        ) {
            ReviewSheetGrabber()
            ReviewSheetTitle(title = state.title, onClose = onClose)
            Column(
                modifier =
                    when (layout) {
                            ReviewSheetLayout.Unrolled -> Modifier
                            ReviewSheetLayout.Pinned ->
                                Modifier.weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState())
                        }
                        .fillMaxWidth()
                        .padding(
                            start = SeekerTheme.spacing.xl,
                            top = SeekerTheme.spacing.xs,
                            end = SeekerTheme.spacing.xl,
                            bottom = SeekerTheme.spacing.xxxl,
                        ),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
            ) {
                ReviewSheetHeader(state)
                state.sandboxNotice?.let {
                    NoticeCard(kind = NoticeCardKind.Sandbox, message = it)
                }
                state.statusBlocks.forEach { ReviewSheetInfoBlock(it) }
                state.yourPart?.let {
                    OwnerInputCard(
                        kind = it.kind,
                        state = it.state,
                        summary = it.summary,
                        onChooseOrEdit = onChoose,
                    )
                }
                VerdictCard(
                    verdict =
                        if (hasWarnings) VerdictCardVerdict.Warning else VerdictCardVerdict.Ok,
                    warnings =
                        state.verdict.warnings.map {
                            VerdictWarning(
                                message = it.message,
                                scopeLabel = it.sourceLabel,
                                kind = it.kind,
                                scope = it.source,
                            )
                        },
                    onRulesClick = onRules,
                    additionalContext = state.verdict.additionalContext,
                    heading = state.verdict.heading,
                )
                state.staleQuote?.let {
                    NoticeCard(
                        kind = NoticeCardKind.StaleQuote,
                        message = it.message,
                        actionLabel = it.actionLabel,
                        onAction = onRefreshQuote,
                    )
                }
                state.terms?.let {
                    TermsCard(rows = it.rows, kind = it.kind, emptyText = it.emptyText)
                }
                state.dailySpend?.let { ReviewSheetDailySpend(it) }
                state.infoBlocks.forEach { ReviewSheetInfoBlock(it) }
                state.factRows.forEach { row ->
                    FactRow(
                        label = row.label,
                        value = row.value,
                        valueStyle = row.valueStyle,
                        onClick = row.copyValue?.let { copy -> { onCopy(copy) } },
                    )
                }
                state.note?.let { ReviewSheetNote(it) }
                Text(
                    text = "Expires ${state.expiry}.",
                    modifier = Modifier.padding(horizontal = SeekerTheme.spacing.xs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.actionsShown)
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = SeekerTheme.spacing.xl,
                                top = SeekerTheme.spacing.lg,
                                end = SeekerTheme.spacing.xl,
                                bottom = SeekerTheme.spacing.xxl,
                            ),
                    verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
                ) {
                    state.confirmationCheckbox?.let { label ->
                        CheckRow(
                            label = label,
                            state =
                                if (confirmed) CheckRowState.Checked else CheckRowState.Unchecked,
                            onStateChange = {
                                val checked = it == CheckRowState.Checked
                                confirmedHere = checked
                                onConfirmedChange(checked)
                            },
                            contentKind = CheckRowContentKind.WarningAcknowledgement,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                    ) {
                        SeekerButton(
                            label = state.primaryAction.label,
                            onClick = onPrimary,
                            variant =
                                if (primaryEnabled) SeekerButtonVariant.Filled
                                else SeekerButtonVariant.Disabled,
                            size = SeekerButtonSize.Lg,
                            modifier = Modifier.weight(1f),
                        )
                        SeekerButton(
                            label = state.secondaryAction.label,
                            onClick = onSecondary,
                            variant =
                                if (state.secondaryAction.enabled) SeekerButtonVariant.Neutral
                                else SeekerButtonVariant.Disabled,
                            size = SeekerButtonSize.Lg,
                        )
                    }
                    Text(
                        text = state.footerCaption,
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.spacing.xs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
    }
}

@Composable
private fun ReviewSheetGrabber() {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = SeekerTheme.spacing.lg, bottom = SeekerTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.width(SeekerTheme.spacing.jumbo)
                    .height(SeekerTheme.spacing.xs)
                    .clip(RoundedCornerShape(SeekerTheme.radii.xs))
                    .background(MaterialTheme.colorScheme.outline)
        )
    }
}

@Composable
private fun ReviewSheetTitle(title: String, onClose: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(SeekerTheme.spacing.huge * ReviewSheetHeaderHeightUnits)
                .padding(start = SeekerTheme.spacing.xl, end = SeekerTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineSmall,
        )
        OrganismIconAction(
            icon = Icons.Outlined.Close,
            contentDescription = "Close",
            onClick = onClose,
            size = OrganismIconActionSize.Large,
            style = OrganismIconActionStyle.Transparent,
        )
    }
}

@Composable
private fun ReviewSheetHeader(state: ReviewSheetState) {
    val originChips =
        state.headerChips.filter {
            it is ReviewSheetHeaderChip.Signal || it is ReviewSheetHeaderChip.Feed
        }
    val contextChips = state.headerChips - originChips.toSet()
    Column(modifier = Modifier.fillMaxWidth()) {
        if (originChips.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = SeekerTheme.spacing.mdPlus),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                originChips.forEach { ReviewSheetHeaderChip(it) }
            }
        }
        Text(
            text = state.headline,
            style =
                MaterialTheme.typography.displayLarge.let {
                    it.copy(lineHeight = it.fontSize * ReviewSheetHeadlineLineHeight)
                },
        )
        Text(
            text =
                if (state.sublineStyle == ReviewSheetSublineStyle.Mono) {
                    state.subline.breakAnywhere()
                } else {
                    state.subline
                },
            modifier =
                Modifier.padding(top = SeekerTheme.spacing.sm).clearAndSetSemantics {
                    text = AnnotatedString(state.subline)
                },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style =
                when (state.sublineStyle) {
                    ReviewSheetSublineStyle.Plain -> MaterialTheme.typography.bodySmall
                    ReviewSheetSublineStyle.Mono -> SeekerTheme.typography.identifier
                },
        )
        if (contextChips.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = SeekerTheme.spacing.mdPlus),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                contextChips.forEach { ReviewSheetHeaderChip(it) }
            }
        }
    }
}

@Composable
private fun ReviewSheetHeaderChip(chip: ReviewSheetHeaderChip) {
    when (chip) {
        ReviewSheetHeaderChip.Signal -> ReviewSignalChip()
        is ReviewSheetHeaderChip.Feed ->
            SourceChip(chip.name, colour = chip.colour, size = SourceChipSize.Compact)
        is ReviewSheetHeaderChip.Environment -> EnvChip(chip.value, chip.verbosity)
        is ReviewSheetHeaderChip.Network -> NetworkChip(chip.value)
    }
}

@Composable
private fun ReviewSignalChip() {
    Row(
        modifier =
            Modifier.height(SeekerTheme.spacing.xxxl)
                .background(SeekerTheme.colors.surface3, MaterialTheme.shapes.small)
                .padding(horizontal = (SeekerTheme.spacing.md + SeekerTheme.spacing.mdPlus) / 2),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.RssFeed,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.lgPlus),
        )
        Text(text = "Signal", maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ReviewSheetDailySpend(dailySpend: ReviewSheetDailySpend) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        Text(
            text = dailySpend.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        dailySpend.rows.forEach { ReviewSheetDailySpendRow(it) }
    }
}

@Composable
private fun ReviewSheetDailySpendRow(row: ReviewSheetDailySpendRow) {
    val over = row.state == DailyLimitRowState.Over
    val contentColor = if (over) SeekerTheme.colors.orange else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = row.state.icon(),
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxl),
            tint = row.state.iconTint(),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            Text(
                text = row.headline,
                color = contentColor,
                style = SeekerTheme.typography.buttonLarge.copy(fontWeight = FontWeight.Normal),
            )
            Text(
                text = row.supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ScopeChip(source = row.scope.chipSource())
    }
}

@Composable
private fun ReviewSheetInfoBlock(info: ReviewSheetInfoBlock) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        info.title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
        }
        Text(
            text = info.body,
            color =
                if (info.title == null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReviewSheetNote(note: ReviewSheetNote) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl)
    ) {
        Text(
            text = note.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = note.body,
            modifier = Modifier.padding(top = SeekerTheme.spacing.xs),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val ReviewSheetHeaderHeightUnits = 2
private const val ReviewSheetHeadlineLineHeight = 1.1f

/** Compose wraps at words by default; the design's identifiers use CSS `word-break: break-all`. */
private fun String.breakAnywhere(): String =
    asIterable().joinToString(separator = "\u200B") { character ->
        if (character == ' ') "\u00A0" else character.toString()
    }
