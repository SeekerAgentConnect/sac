package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/**
 * Reviewing the sale of a whole prediction position (SEE-172).
 *
 * Composed only of the guide's own pieces — the sheet scaffold, the terms card, fact rows, the stale
 * notice and the buttons — in the order a buy's review uses: what is being done, its scope, the
 * terms read from the bytes, what the provider only estimates, and then one decision. The sheet
 * never says a sale happened; it hands a reviewed transaction to the wallet and closes.
 */
enum class PositionSaleVerdict {
    /** The transaction is being built and read. */
    Preparing,
    /** Every check passed; it may be approved. */
    Verified,
    /** Something in the bytes forbids approving it. */
    Refused,
    /** Nothing could be prepared. */
    Unavailable,
    /** The wallet is open for it. */
    Signing,
}

data class PositionSaleFact(val label: String, val value: String, val mono: Boolean = false)

data class PositionSaleSheetState(
    val headline: String,
    val subline: String,
    /** What exactly is sold: the whole position, which may include other purchases. */
    val scope: String,
    val verdict: PositionSaleVerdict,
    val verdictText: String,
    /** Read from the bytes: quantity, floor, least proceeds. */
    val terms: List<TermsCardRow> = emptyList(),
    /** The provider's estimates and the binding: wallet, network, fees. */
    val facts: List<PositionSaleFact> = emptyList(),
    val findings: List<String> = emptyList(),
    /** Set when the review ran out or the position moved; the action prepares it again. */
    val staleNotice: String? = null,
    val expiry: String? = null,
    val primaryEnabled: Boolean = false,
)

object PositionSaleSheetTags {
    const val Sheet = "positionSale"
    const val Verdict = "positionSaleVerdict"
    const val Scope = "positionSaleScope"
    const val Sell = "positionSaleSell"
    const val Cancel = "positionSaleCancel"
    const val Stale = "positionSaleStale"
    const val Findings = "positionSaleFindings"
}

@Composable
fun PositionSaleSheet(
    state: PositionSaleSheetState,
    onSell: () -> Unit,
    onCancel: () -> Unit,
    onPrepareAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(
        title = "Sell position",
        variant = SheetScaffoldVariant.Plain,
        onClose = onCancel,
        modifier = modifier.testTag(PositionSaleSheetTags.Sheet),
        body = {
            Text(text = state.headline, style = MaterialTheme.typography.titleLarge)
            Text(
                text = state.subline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = state.scope,
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(SeekerTheme.radii.md))
                        .background(SeekerTheme.colors.orangeContainer)
                        .padding(SeekerTheme.spacing.lg)
                        .testTag(PositionSaleSheetTags.Scope),
                color = SeekerTheme.colors.onOrangeContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            state.staleNotice?.let {
                NoticeCard(
                    kind = NoticeCardKind.StaleQuote,
                    message = it,
                    actionLabel = "Prepare again",
                    onAction = onPrepareAgain,
                    modifier = Modifier.testTag(PositionSaleSheetTags.Stale),
                )
            }
            Text(
                text = state.verdictText,
                modifier = Modifier.testTag(PositionSaleSheetTags.Verdict),
                color =
                    when (state.verdict) {
                        PositionSaleVerdict.Verified -> SeekerTheme.colors.lime
                        PositionSaleVerdict.Refused,
                        PositionSaleVerdict.Unavailable -> SeekerTheme.colors.onDestructiveContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.findings.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(SeekerTheme.radii.md))
                        .background(SeekerTheme.colors.destructiveContainer)
                        .padding(SeekerTheme.spacing.lg)
                        .testTag(PositionSaleSheetTags.Findings),
                    verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
                ) {
                    state.findings.forEach {
                        Text(
                            text = it,
                            color = SeekerTheme.colors.onDestructiveContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (state.terms.isNotEmpty()) {
                TermsCard(rows = state.terms, kind = TermsCardKind.Prediction)
            } else if (state.verdict == PositionSaleVerdict.Preparing) {
                TermsCard(
                    rows = emptyList(),
                    kind = TermsCardKind.Prediction,
                    emptyText = "Building the sale and reading it…",
                )
            }
            state.facts.forEach {
                FactRow(
                    label = it.label,
                    value = it.value,
                    valueStyle = if (it.mono) FactRowValueStyle.Mono else FactRowValueStyle.Plain,
                )
            }
            state.expiry?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        actions = {
            SeekerButton(
                label = "Cancel",
                onClick = onCancel,
                variant = SeekerButtonVariant.Neutral,
                size = SeekerButtonSize.Lg,
                modifier = Modifier.weight(1f).testTag(PositionSaleSheetTags.Cancel),
            )
            SeekerButton(
                label =
                    if (state.verdict == PositionSaleVerdict.Signing) "In your wallet…"
                    else "Sell in wallet",
                onClick = onSell,
                variant =
                    if (state.primaryEnabled) SeekerButtonVariant.Filled
                    else SeekerButtonVariant.Disabled,
                size = SeekerButtonSize.Lg,
                enabled = state.primaryEnabled,
                modifier = Modifier.weight(1f).testTag(PositionSaleSheetTags.Sell),
            )
        },
    )
}
