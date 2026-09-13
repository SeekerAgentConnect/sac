package io.github.brrenat.seekervault.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.ui.CardDivider
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassDialog
import io.github.brrenat.seekervault.ui.GlassScreen
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.IconChip
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.Space
import io.github.brrenat.seekervault.ui.TabBar
import io.github.brrenat.seekervault.ui.TextLink

/**
 * Activity: what this phone has done, newest first (docs/guides/transfers.md#the-activity-record).
 * It reads the record and nothing else — no request is answered here, no server is called, and no
 * wallet is opened.
 */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onOpen: (RequestKey) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    tabs: TabBar? = null,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    GlassScreen(
        title = stringResource(R.string.activity_title),
        onBack = onBack,
        headerAction = {
            TextLink(
                stringResource(R.string.refresh),
                onRefresh,
                Modifier.testTag(ActivityTags.REFRESH),
            )
            if (state.records.isNotEmpty()) {
                TextLink(
                    stringResource(R.string.activity_clear),
                    { confirming = true },
                    Modifier.testTag(ActivityTags.CLEAR),
                )
            }
        },
        tabs = tabs,
        modifier = modifier,
    ) {
        // A history that couldn't be read says so and shows what it has. It is the owner's
        // record, and half of it is worth more than a screen that refuses to open.
        if (state.unreadable) {
            GlassCard {
                Text(
                    stringResource(R.string.activity_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Danger,
                    modifier = Modifier.testTag(ActivityTags.UNREADABLE),
                )
            }
        }
        if (state.loaded && state.records.isEmpty()) {
            GlassCard {
                Text(
                    stringResource(R.string.activity_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral400,
                    modifier = Modifier.testTag(ActivityTags.EMPTY),
                )
            }
        }
        if (state.records.isNotEmpty()) {
            GlassCard(
                padding = Space.Sm,
                spacing = 0.dp,
                modifier = Modifier.testTag(ActivityTags.LIST),
            ) {
                state.records.forEachIndexed { index, record ->
                    if (index > 0) CardDivider()
                    RecordRow(record, newest = index == 0, onOpen = onOpen)
                }
            }
        }
        Text(
            stringResource(R.string.activity_footer),
            style = MaterialTheme.typography.bodySmall,
            color = Nocturne.Neutral500,
        )
    }
    if (confirming) {
        GlassDialog(
            title = stringResource(R.string.activity_clear_title),
            onDismiss = { confirming = false },
            confirm = stringResource(R.string.activity_clear_confirm),
            onConfirm = {
                confirming = false
                onClear()
            },
            confirmTag = ActivityTags.CONFIRM_CLEAR,
        ) {
            Text(
                stringResource(R.string.activity_clear_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Nocturne.Neutral300,
            )
        }
    }
}

/** One thing this phone did: what it was, what became of it, and when. */
@Composable
private fun RecordRow(record: ActivityRecord, newest: Boolean, onOpen: (RequestKey) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.InnerTight))
            .then(if (newest) Modifier.background(Nocturne.accent(0.10f)) else Modifier)
            .clickable { onOpen(record.key) }
            .padding(Space.Sm)
            .testTag(ActivityTags.item(record))
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(
            recordIcon(record),
            contentDescription = null,
            size = 38.dp,
            accent = newest,
        )
        Column(Modifier.weight(1f)) {
            Text(
                operationText(record),
                style = MaterialTheme.typography.titleMedium,
                color = Nocturne.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.activity_summary,
                    stringResource(outcomeText(record.outcome)),
                    formatInstant(record.answeredAt),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Nocturne.Neutral500,
            )
            Text(
                record.source,
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The mark on a record: what kind of thing it was, or that it ended badly. */
private fun recordIcon(record: ActivityRecord) =
    when {
        record.outcome in FAILED_OUTCOMES -> Glyph.Warning
        record.kind == ActivityKind.Transfer -> Glyph.Transfer
        record.kind == ActivityKind.MessageSignature -> Glyph.Signature
        else -> Glyph.Acknowledge
    }

private val FAILED_OUTCOMES =
    setOf(
        ActivityOutcome.ChainFailed,
        ActivityOutcome.NotSigned,
        ActivityOutcome.Unknown,
        ActivityOutcome.NotDelivered,
        ActivityOutcome.Superseded,
    )
