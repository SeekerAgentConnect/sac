package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class TermsCardKind {
    Swap
}

enum class TermsCardState {
    Quoted
}

data class TermsCardRow(val label: String, val value: String)

data class TermsCardDailyRow(
    val headline: String,
    val supportingText: String,
    val state: DailyLimitRowState,
    val scope: DailyLimitRowScope,
)

/** Ticket-owned terms summary that composes the SEE-118 daily-row molecule. */
@Composable
fun TermsCard(
    dailyRows: List<TermsCardDailyRow>,
    modifier: Modifier = Modifier,
    title: String = "Daily spend, if you approve",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        dailyRows.forEach { row ->
            DailyRow(
                headline = row.headline,
                supportingText = row.supportingText,
                state = row.state,
                scope = row.scope,
            )
        }
    }
}

@Composable
fun TermsCard(
    rows: List<TermsCardRow>,
    kind: TermsCardKind = TermsCardKind.Swap,
    state: TermsCardState = TermsCardState.Quoted,
    modifier: Modifier = Modifier,
) {
    val heading =
        when (kind) {
            TermsCardKind.Swap ->
                when (state) {
                    TermsCardState.Quoted -> "The whole operation, quoted here"
                }
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
            ) {
                Text(
                    text = row.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = row.value,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private const val TermsCardPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@DesignRef(component = "terms-card", variant = "kind=swap state=quoted")
@Preview(
    name = "terms-card/kind-swap-state-quoted",
    widthDp = 358,
    uiMode = TermsCardPreviewDarkMode,
)
@Composable
private fun TermsCardSwapQuotedPreview() {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            TermsCard(
                rows =
                    listOf(
                        TermsCardRow("You pay", "1.5 SOL"),
                        TermsCardRow("Expected output", "≈ 318.60 USDC"),
                        TermsCardRow("Minimum received", "317.01 USDC"),
                        TermsCardRow("Most the price may move", "0.5% (50 bps)"),
                        TermsCardRow("Route", "Jupiter Aggregator · read on this phone"),
                    )
            )
        }
    }
}
