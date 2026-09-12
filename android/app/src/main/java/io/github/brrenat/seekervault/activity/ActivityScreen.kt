package io.github.brrenat.seekervault.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.formatInstant

/**
 * Activity: what this phone has done, newest first (docs/guides/transfers.md#the-activity-record).
 * It reads the record and nothing else — no request is answered here, no server is called, and no
 * wallet is opened. Stock Material 3 only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onOpen: (RequestKey) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag(ActivityTags.REFRESH),
                    ) {
                        Text(stringResource(R.string.refresh))
                    }
                    if (state.records.isNotEmpty()) {
                        TextButton(
                            onClick = { confirming = true },
                            modifier = Modifier.testTag(ActivityTags.CLEAR),
                        ) {
                            Text(stringResource(R.string.activity_clear))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(contentPadding = innerPadding, modifier = Modifier.testTag(ActivityTags.LIST)) {
            // A history that couldn't be read says so and shows what it has. It is the owner's
            // record, and half of it is worth more than a screen that refuses to open.
            if (state.unreadable) {
                item(key = "unreadable") {
                    Text(
                        stringResource(R.string.activity_unreadable),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).testTag(ActivityTags.UNREADABLE),
                    )
                }
            }
            if (state.loaded && state.records.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.activity_empty),
                        modifier = Modifier.padding(16.dp).testTag(ActivityTags.EMPTY),
                    )
                }
            }
            items(state.records, key = { "${it.connectionId}/${it.requestId}" }) { record ->
                RecordItem(record, onOpen)
                HorizontalDivider()
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.activity_clear_title)) },
            text = { Text(stringResource(R.string.activity_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onClear()
                    },
                    modifier = Modifier.testTag(ActivityTags.CONFIRM_CLEAR),
                ) {
                    Text(stringResource(R.string.activity_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RecordItem(record: ActivityRecord, onOpen: (RequestKey) -> Unit) {
    ListItem(
        overlineContent = { Text(record.source) },
        headlineContent = {
            Text(operationText(record), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                stringResource(
                    R.string.activity_summary,
                    stringResource(outcomeText(record.outcome)),
                    formatInstant(record.answeredAt),
                )
            )
        },
        modifier = Modifier.clickable { onOpen(record.key) }.testTag(ActivityTags.item(record)),
    )
}
