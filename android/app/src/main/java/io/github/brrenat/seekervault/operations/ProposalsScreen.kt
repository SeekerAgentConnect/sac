package io.github.brrenat.seekervault.operations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.ui.SeekerCard
import java.time.Instant

/**
 * What a feed has proposed, for one connection (SEE-93).
 *
 * A publisher's signals, as they stand for this owner: the ones still open, then the ones already
 * answered here. Nothing on this screen reaches the publisher, and opening one tells nobody
 * anything (docs/wiki/shared-proposals.md).
 */
@Composable
fun ProposalsScreen(
    label: String,
    records: List<ProposalRecord>,
    standings: (ProposalRecord) -> ProposalStanding,
    refreshing: Boolean,
    now: Instant,
    onOpen: (ProposalRecord) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Open first, then whatever this phone or the publisher has since settled, newest of each
    // first: the thing waiting for somebody is the thing to show them.
    val (open, settled) =
        records
            .sortedByDescending { it.proposal.updatedAt }
            .partition {
                standings(it) is ProposalStanding.Open
            }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .height(SeekerTheme.dimensions.dp56)
                    .padding(horizontal = SeekerTheme.dimensions.dp8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = SeekerTheme.dimensions.dp8)) {
                    Text(
                        stringResource(R.string.operations_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.operations_from, label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier.size(SeekerTheme.dimensions.dp40)
                        .clickable(
                            enabled = !refreshing,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onRefresh,
                        )
                        .testTag(OperationTags.REFRESH),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                    )
                }
                CloseButton(onBack, MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            if (refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            if (records.isEmpty()) {
                Text(
                    stringResource(R.string.operations_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(SeekerTheme.dimensions.dp16).testTag(OperationTags.EMPTY),
                )
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag(OperationTags.LIST),
                contentPadding = PaddingValues(SeekerTheme.dimensions.dp16),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
            ) {
                items(open, key = { it.key.proposalId }) {
                    ProposalRow(it, standings(it), now, onOpen)
                }
                if (settled.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.operations_settled),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = SeekerTheme.dimensions.dp8),
                        )
                    }
                }
                items(settled, key = { it.key.proposalId }) {
                    ProposalRow(it, standings(it), now, onOpen)
                }
            }
        }
    }
}

@Composable
private fun ProposalRow(
    record: ProposalRecord,
    standing: ProposalStanding,
    now: Instant,
    onOpen: (ProposalRecord) -> Unit,
) {
    SeekerCard(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { onOpen(record) },
                )
                .testTag(OperationTags.row(record.key.proposalId))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp14),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp14),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(SeekerTheme.dimensions.dp40)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                // The operation at the protocol's own level. Which provider serves it is the
                // plugin's business and is not a thing this screen knows (SEE-86).
                Text(
                    record.proposal.operation.value,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(standingText(standing)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The publisher's own words, which are theirs and are never verified.
                if (record.proposal.note.isNotEmpty()) {
                    Text(
                        record.proposal.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(
                        if (record.proposal.expiresAt.isAfter(now)) R.string.operations_expires
                        else R.string.operations_expired,
                        formatInstant(record.proposal.expiresAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
