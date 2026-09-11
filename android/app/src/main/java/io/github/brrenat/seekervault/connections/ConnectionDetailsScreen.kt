package io.github.brrenat.seekervault.connections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R

/**
 * The Connection details screen: what the phone knows about one connection, and the refresh,
 * rename, and disconnect actions. It never shows the credential.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDetailsScreen(
    connection: Connection,
    refreshing: Boolean,
    disconnect: DisconnectState?,
    message: ConnectionMessage?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> LabelProblem?,
    onDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissDisconnect: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(message, snackbar, onMessageShown)
    var renaming by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(connection.label) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            Text(
                statusText(connection),
                color =
                    if (hasProblem(connection)) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(ConnectionsTags.STATUS),
            )
            connection.lastCheck?.let {
                Text(
                    stringResource(R.string.checked_at, formatInstant(it.at)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
            Field(R.string.field_server, connection.serverUrl, "server")
            Field(R.string.field_server_id, connection.serverId, "serverId")
            Field(R.string.field_connection_id, connection.id, "connectionId")
            Field(R.string.field_paired, formatInstant(connection.pairedAt), "paired")
            Field(R.string.field_device_name, connection.deviceName, "deviceName")
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = connection.usable && !refreshing,
                    modifier = Modifier.testTag(ConnectionsTags.REFRESH),
                ) {
                    Text(stringResource(R.string.refresh))
                }
                OutlinedButton(
                    onClick = { renaming = true },
                    modifier = Modifier.testTag(ConnectionsTags.RENAME),
                ) {
                    Text(stringResource(R.string.rename))
                }
            }
            if (connection.usable) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.padding(16.dp).testTag(ConnectionsTags.DISCONNECT),
                ) {
                    Text(stringResource(R.string.connection_disconnect))
                }
            } else {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.padding(16.dp).testTag(ConnectionsTags.REMOVE),
                ) {
                    Text(stringResource(R.string.connection_remove))
                }
            }
        }
    }
    if (renaming) {
        RenameDialog(
            current = connection.label,
            onSave = { label -> onRename(label).also { if (it == null) renaming = false } },
            onDismiss = { renaming = false },
        )
    }
    disconnect?.let {
        DisconnectDialog(
            label = connection.label,
            state = it,
            onDisconnect = onConfirmDisconnect,
            onRemove = onConfirmRemove,
            onDismiss = onDismissDisconnect,
        )
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String, name: String) {
    ListItem(
        overlineContent = { Text(stringResource(label)) },
        headlineContent = { Text(value) },
        modifier = Modifier.testTag(ConnectionsTags.field(name)),
    )
}

@Composable
private fun RenameDialog(
    current: String,
    onSave: (String) -> LabelProblem?,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(current) }
    var problem by remember { mutableStateOf<LabelProblem?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    problem = null
                },
                label = { Text(stringResource(R.string.rename_label)) },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { { Text(labelProblemText(it)) } },
                modifier = Modifier.testTag(ConnectionsTags.LABEL_FIELD),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { problem = onSave(value) },
                modifier = Modifier.testTag(ConnectionsTags.DIALOG_CONFIRM),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(ConnectionsTags.DIALOG_DISMISS),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DisconnectDialog(
    label: String,
    state: DisconnectState,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title: String
    val text: String
    val confirm: String?
    val action: () -> Unit
    when (state) {
        is DisconnectState.Confirm -> {
            title = stringResource(R.string.disconnect_title, label)
            text = stringResource(R.string.disconnect_text)
            confirm = stringResource(R.string.connection_disconnect)
            action = onDisconnect
        }
        is DisconnectState.Working -> {
            title = label
            text = stringResource(R.string.working)
            confirm = null
            action = {}
        }
        is DisconnectState.NotReached -> {
            title = stringResource(R.string.not_reached_title)
            text = stringResource(R.string.not_reached_text, outcomeText(state.outcome))
            confirm = stringResource(R.string.remove_anyway)
            action = onRemove
        }
        is DisconnectState.ConfirmRemove -> {
            title = stringResource(R.string.remove_title, label)
            text = stringResource(R.string.remove_text)
            confirm = stringResource(R.string.remove)
            action = onRemove
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            if (confirm != null) {
                TextButton(
                    onClick = action,
                    modifier = Modifier.testTag(ConnectionsTags.DIALOG_CONFIRM),
                ) {
                    Text(confirm)
                }
            }
        },
        dismissButton = {
            if (confirm != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(ConnectionsTags.DIALOG_DISMISS),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
