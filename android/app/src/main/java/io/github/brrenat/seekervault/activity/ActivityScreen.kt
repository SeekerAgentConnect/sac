package io.github.brrenat.seekervault.activity

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.ui.NetworkChip
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.SolidDialog

/** The owner's local history, newest first, with no side effects beyond refresh and clear. */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onOpen: (RequestKey) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    network: String? = null,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackButton(onBack)
                Text(
                    stringResource(R.string.activity_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                network?.let { NetworkChip(it) }
                Box(
                    Modifier.size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onRefresh,
                        )
                        .testTag(ActivityTags.REFRESH),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                    )
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).testTag(ActivityTags.LIST),
            ) {
                if (state.unreadable) {
                    item(key = "unreadable") {
                        SeekerCard(
                            Modifier.fillMaxWidth().testTag(ActivityTags.UNREADABLE),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                stringResource(R.string.activity_unreadable),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                if (state.loaded && state.records.isEmpty()) {
                    item(key = "empty") {
                        SeekerCard(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.activity_empty),
                                modifier = Modifier.padding(18.dp).testTag(ActivityTags.EMPTY),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(state.records, key = { "${it.connectionId}/${it.requestId}" }) {
                    RecordItem(it, onOpen)
                }
                if (state.records.isNotEmpty()) {
                    item(key = "clear") {
                        SeekerButton(
                            text = stringResource(R.string.activity_clear),
                            onClick = { confirming = true },
                            role = SeekerButtonRole.Error,
                            modifier = Modifier.fillMaxWidth().testTag(ActivityTags.CLEAR),
                        )
                    }
                }
            }
        }
        if (confirming) {
            SolidDialog(
                title = stringResource(R.string.activity_clear_title),
                body = {
                    Text(
                        stringResource(R.string.activity_clear_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                actions = {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SeekerButton(
                            text = stringResource(R.string.cancel),
                            onClick = { confirming = false },
                            role = SeekerButtonRole.Neutral,
                            modifier = Modifier.weight(1f),
                        )
                        SeekerButton(
                            text = stringResource(R.string.activity_clear_confirm),
                            onClick = {
                                confirming = false
                                onClear()
                            },
                            role = SeekerButtonRole.Error,
                            modifier = Modifier.weight(1f).testTag(ActivityTags.CONFIRM_CLEAR),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun RecordItem(record: ActivityRecord, onOpen: (RequestKey) -> Unit) {
    SeekerCard(
        modifier = Modifier.fillMaxWidth().testTag(ActivityTags.item(record)),
        onClick = { onOpen(record.key) },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp)
                    .background(
                        if (record.outcome == ActivityOutcome.Confirmed)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        CircleShape,
                    )
            )
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    record.source,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    operationText(record),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.activity_summary,
                        stringResource(outcomeText(record.outcome)),
                        formatInstant(record.answeredAt),
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
