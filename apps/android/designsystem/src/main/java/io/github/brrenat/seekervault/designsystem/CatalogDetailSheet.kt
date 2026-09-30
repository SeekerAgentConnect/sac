package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/** One labelled fact on a catalog feed's detail sheet. */
data class CatalogDetailFact(val label: String, val value: String, val mono: Boolean = false)

/**
 * A Discover feed's detail sheet (SEE-176, design/components/catalog-card/spec.md): the card's
 * content in full, the facts behind it, and the same one action.
 */
data class CatalogDetailSheetState(
    val card: CatalogCardModel,
    val status: CatalogCardStatus,
    /** What reading the feed takes, in a sentence. */
    val accessExplanation: String,
    val facts: List<CatalogDetailFact>,
    /** The closing caption: what happens when the owner acts. */
    val caption: String,
    val closeLabel: String,
    val actionTag: String? = null,
)

@Composable
fun CatalogDetailSheet(
    state: CatalogDetailSheetState,
    onClose: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.card
    SheetScaffold(
        title = card.name,
        variant = SheetScaffoldVariant.Plain,
        onClose = onClose,
        modifier = modifier,
        body = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceAvatar(sourceName = card.name, initials = card.initials)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.accessLabel,
                        color =
                            when (card.access) {
                                CatalogCardAccess.Public ->
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                CatalogCardAccess.Restricted -> MaterialTheme.colorScheme.primary
                            },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    card.statusText?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Text(text = card.description, style = MaterialTheme.typography.bodyLarge)
            if (card.networks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs)) {
                    card.networks.forEach { NetworkChip(network = it) }
                }
            }
            Text(
                text = state.accessExplanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus)) {
                state.facts.forEach { fact ->
                    FactRow(
                        label = fact.label,
                        value = fact.value,
                        valueStyle =
                            if (fact.mono) FactRowValueStyle.MonoWrap else FactRowValueStyle.Plain,
                    )
                }
            }
            Text(
                text = state.caption,
                modifier = Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.spacing.xs),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        actions = {
            SeekerButton(
                label = state.closeLabel,
                onClick = onClose,
                variant = SeekerButtonVariant.Neutral,
                size = SeekerButtonSize.Lg,
                modifier = Modifier.weight(1f),
            )
            SeekerButton(
                label = card.actionLabel,
                onClick = onAction,
                variant =
                    when (state.status) {
                        CatalogCardStatus.Available -> SeekerButtonVariant.Filled
                        CatalogCardStatus.Working -> SeekerButtonVariant.Disabled
                        CatalogCardStatus.Waiting,
                        CatalogCardStatus.Stopped -> SeekerButtonVariant.Tonal
                        CatalogCardStatus.Connected -> SeekerButtonVariant.Tonal
                    },
                size = SeekerButtonSize.Lg,
                enabled = state.status != CatalogCardStatus.Working,
                modifier =
                    Modifier.weight(1f).let { base ->
                        state.actionTag?.let { base.testTag(it) } ?: base
                    },
            )
        },
    )
}

@DesignRef(component = "catalog-card", variant = "sheet=detail")
@Preview(
    name = "catalog-card/sheet-detail",
    widthDp = 390,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun CatalogDetailSheetPreview() {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            CatalogDetailSheet(
                state =
                    CatalogDetailSheetState(
                        card =
                            CatalogCardModel(
                                name = "Members desk",
                                initials = "MD",
                                description =
                                    "Swap ideas from a desk of three traders, for members.\n" +
                                        "Each one is a proposal you review and sign yourself.",
                                access = CatalogCardAccess.Restricted,
                                accessLabel = "Restricted · approval needed",
                                networks = listOf(NetworkChipNetwork.Mainnet),
                                statusText = null,
                                actionLabel = "Request access",
                            ),
                        status = CatalogCardStatus.Available,
                        accessExplanation =
                            "Only devices its publisher approves. You prove you control your " +
                                "wallet by signing a message, and the publisher decides.",
                        facts =
                            listOf(
                                CatalogDetailFact("Required client plugins", "jupiter.swap"),
                                CatalogDetailFact("Gateway", "https://feeds.example.com", true),
                            ),
                        caption = "Details are checked again when you connect.",
                        closeLabel = "Close",
                    ),
                onClose = {},
                onAction = {},
            )
        }
    }
}
