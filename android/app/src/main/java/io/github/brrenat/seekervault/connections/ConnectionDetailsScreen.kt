package io.github.brrenat.seekervault.connections

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.policy.PolicyTags
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import io.github.brrenat.seekervault.ui.Identifier
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.SeekerSnackbarHost
import io.github.brrenat.seekervault.ui.SolidDialog
import io.github.brrenat.seekervault.ui.seekerTextFieldColors

/** Connection facts and actions, rendered as the first layer of the detail sheet stack. */
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
    onPendingRequests: (() -> Unit)? = null,
    onRules: (() -> Unit)? = null,
    live: ForegroundConnectionState? = null,
    /** Whether this build supports this connection's server (SEE-88); null until worked out. */
    support: ServerSupport? = null,
) {
    val problem = hasProblem(connection, live, support)
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(message, snackbar, onMessageShown)
    var renaming by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    connection.label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                CloseButton(onBack, MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SeekerCard(
                    Modifier.fillMaxWidth(),
                    color =
                        if (problem) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (problem) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint =
                                if (problem) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                statusText(connection, live, support),
                                style = MaterialTheme.typography.titleMedium,
                                color =
                                    if (problem) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                modifier = Modifier.testTag(ConnectionsTags.STATUS),
                            )
                            connection.lastCheck?.let {
                                Text(
                                    stringResource(R.string.checked_at, formatInstant(it.at)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (problem) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                )
                            }
                        }
                        Box(
                            Modifier.size(40.dp)
                                .background(
                                    if (problem) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    CircleShape,
                                )
                                .clickable(
                                    enabled = connection.usable && !refreshing,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onRefresh,
                                )
                                .testTag(ConnectionsTags.REFRESH),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                    }
                }
                if (refreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                Field(R.string.field_server, connection.serverUrl, "server")
                Field(R.string.field_server_id, connection.serverId, "serverId")
                Field(R.string.field_connection_id, connection.id, "connectionId")
                Field(R.string.field_paired, formatInstant(connection.pairedAt), "paired")
                Field(R.string.field_device_name, connection.deviceName, "deviceName")
                if (onRules != null) {
                    SeekerCard(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onRules,
                                )
                                .testTag(PolicyTags.RULES)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp, 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(40.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Tune, contentDescription = null)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.policy_rules),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.connection_rules_note),
                                    style = MaterialTheme.typography.bodyMedium,
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
                Text(
                    stringResource(R.string.connection_rules_advisory),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeekerButton(
                        text = stringResource(R.string.rename),
                        onClick = { renaming = true },
                        role = SeekerButtonRole.Neutral,
                        modifier = Modifier.weight(1f).testTag(ConnectionsTags.RENAME),
                    )
                    if (onPendingRequests != null && connection.usable) {
                        SeekerButton(
                            text = stringResource(R.string.pending_requests),
                            onClick = onPendingRequests,
                            role = SeekerButtonRole.Neutral,
                            modifier = Modifier.weight(1f).testTag(ConnectionsTags.PENDING),
                        )
                    }
                }
                SeekerCard(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(
                                if (connection.usable) R.string.disconnect_text
                                else R.string.remove_text
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        SeekerButton(
                            text =
                                stringResource(
                                    if (connection.usable) R.string.connection_disconnect
                                    else R.string.connection_remove
                                ),
                            onClick = onDisconnect,
                            role = SeekerButtonRole.StrongError,
                            modifier =
                                Modifier.testTag(
                                    if (connection.usable) ConnectionsTags.DISCONNECT
                                    else ConnectionsTags.REMOVE
                                ),
                        )
                    }
                }
            }
        }
        SeekerSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
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
}

@Composable
private fun Field(@StringRes label: Int, value: String, name: String) {
    SeekerCard(
        Modifier.fillMaxWidth().testTag(ConnectionsTags.field(name)).semantics(
            mergeDescendants = true
        ) {}
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Identifier(value, Modifier.padding(top = 3.dp), maxLines = 3)
        }
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onSave: (String) -> LabelProblem?,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(current) }
    var problem by remember { mutableStateOf<LabelProblem?>(null) }
    SolidDialog(
        title = stringResource(R.string.rename_title),
        body = {
            TextField(
                value = value,
                onValueChange = {
                    value = it
                    problem = null
                },
                label = { Text(stringResource(R.string.rename_label)) },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { { Text(labelProblemText(it)) } },
                colors = seekerTextFieldColors(),
                modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.LABEL_FIELD),
            )
        },
        actions = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeekerButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    role = SeekerButtonRole.Neutral,
                    modifier = Modifier.weight(1f).testTag(ConnectionsTags.DIALOG_DISMISS),
                )
                SeekerButton(
                    text = stringResource(R.string.save),
                    onClick = { problem = onSave(value) },
                    modifier = Modifier.weight(1f).testTag(ConnectionsTags.DIALOG_CONFIRM),
                )
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
    SolidDialog(
        title = title,
        body = { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        actions = {
            if (confirm != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SeekerButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        role = SeekerButtonRole.Neutral,
                        modifier = Modifier.weight(1f).testTag(ConnectionsTags.DIALOG_DISMISS),
                    )
                    SeekerButton(
                        text = confirm,
                        onClick = action,
                        role = SeekerButtonRole.Error,
                        modifier = Modifier.weight(1f).testTag(ConnectionsTags.DIALOG_CONFIRM),
                    )
                }
            }
        },
    )
}
