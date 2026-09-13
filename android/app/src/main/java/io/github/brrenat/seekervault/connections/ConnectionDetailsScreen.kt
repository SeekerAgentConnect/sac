package io.github.brrenat.seekervault.connections

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.policy.PolicyTags
import io.github.brrenat.seekervault.ui.CardDivider
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassDialog
import io.github.brrenat.seekervault.ui.GlassField
import io.github.brrenat.seekervault.ui.GlassScreen
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.IconChip
import io.github.brrenat.seekervault.ui.MessageOverlay
import io.github.brrenat.seekervault.ui.MonoText
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.PillButton
import io.github.brrenat.seekervault.ui.PillTone
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space

/**
 * The Connection details screen: what the phone knows about one connection, its Rules, and the
 * refresh, rename and disconnect actions. It never shows the credential.
 */
@Composable
@Suppress("LongMethod")
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
    pending: Int = 0,
    onPendingRequests: (() -> Unit)? = null,
    onRules: (() -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(message, snackbar, onMessageShown)
    var renaming by rememberSaveable { mutableStateOf(false) }
    GlassScreen(
        title = connection.label,
        subtitle = PairingCodes.hostOf(connection.serverUrl),
        onBack = onBack,
        modifier = modifier,
        overlay = {
            MessageOverlay(snackbar, Modifier.align(Alignment.BottomCenter), overTabBar = false)
        },
    ) {
        GlassCard {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasProblem(connection)) Nocturne.Danger else Nocturne.Accent
                        )
                )
                Text(
                    if (hasProblem(connection) || pending == 0) statusText(connection)
                    else stringResource(R.string.connection_pending, pending),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (hasProblem(connection)) Nocturne.Danger else Nocturne.Text,
                    modifier = Modifier.weight(1f).testTag(ConnectionsTags.STATUS),
                )
            }
            connection.lastCheck?.let {
                Text(
                    stringResource(R.string.checked_at, formatInstant(it.at)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nocturne.Neutral500,
                )
            }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        GlassCard(spacing = 0.dp) {
            Field(R.string.field_server, "server") { MonoText(connection.serverUrl) }
            CardDivider()
            Field(R.string.field_server_id, "serverId") { MonoText(connection.serverId) }
            CardDivider()
            Field(R.string.field_connection_id, "connectionId") { MonoText(connection.id) }
            CardDivider()
            Field(R.string.field_paired, "paired") {
                Text(
                    formatInstant(connection.pairedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Text,
                )
            }
            CardDivider()
            Field(R.string.field_device_name, "deviceName") {
                Text(
                    connection.deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Text,
                )
            }
        }
        // The rules the owner set for this connection (SAW-027). They are reachable whatever
        // state the connection is in: a connection that can't be reached is exactly when the
        // owner may want to read what they had asked of it.
        if (onRules != null) {
            GlassCard(padding = Space.Sm) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.InnerTight))
                        .clickable(onClick = onRules)
                        .padding(Space.Sm)
                        .testTag(PolicyTags.RULES),
                    horizontalArrangement = Arrangement.spacedBy(Space.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconChip(Glyph.Rules, contentDescription = null)
                    Text(
                        stringResource(R.string.policy_rules),
                        style = MaterialTheme.typography.titleMedium,
                        color = Nocturne.Text,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Glyph.Forward,
                        contentDescription = null,
                        tint = Nocturne.Neutral500,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    stringResource(R.string.connection_rules_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nocturne.Neutral500,
                    modifier = Modifier.padding(horizontal = Space.Sm),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
            PillButton(
                stringResource(R.string.refresh),
                onRefresh,
                enabled = connection.usable && !refreshing,
                modifier = Modifier.weight(1f).testTag(ConnectionsTags.REFRESH),
            )
            PillButton(
                stringResource(R.string.rename),
                { renaming = true },
                modifier = Modifier.weight(1f).testTag(ConnectionsTags.RENAME),
            )
        }
        if (onPendingRequests != null && connection.usable) {
            PillButton(
                stringResource(R.string.connection_its_requests),
                onPendingRequests,
                modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.PENDING),
            )
        }
        GlassCard {
            SectionLabel(stringResource(R.string.connection_disconnect))
            Text(
                stringResource(R.string.connection_danger),
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral500,
            )
            if (connection.usable) {
                PillButton(
                    stringResource(R.string.connection_disconnect),
                    onDisconnect,
                    tone = PillTone.Danger,
                    modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.DISCONNECT),
                )
            } else {
                PillButton(
                    stringResource(R.string.connection_remove),
                    onDisconnect,
                    tone = PillTone.Danger,
                    modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.REMOVE),
                )
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
private fun Field(@StringRes label: Int, name: String, value: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = Space.Md)
            .testTag(ConnectionsTags.field(name))
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionLabel(stringResource(label))
        value()
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
    GlassDialog(
        title = stringResource(R.string.rename_title),
        onDismiss = onDismiss,
        confirm = stringResource(R.string.save),
        onConfirm = { problem = onSave(value) },
        confirmTag = ConnectionsTags.DIALOG_CONFIRM,
        dismissTag = ConnectionsTags.DIALOG_DISMISS,
    ) {
        GlassField(
            value = value,
            onValueChange = {
                value = it
                problem = null
            },
            label = stringResource(R.string.rename_label),
            problem = problem?.let { labelProblemText(it) },
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.testTag(ConnectionsTags.LABEL_FIELD),
        )
    }
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
    GlassDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = confirm,
        onConfirm = if (confirm == null) null else action,
        confirmTag = ConnectionsTags.DIALOG_CONFIRM,
        dismissTag = ConnectionsTags.DIALOG_DISMISS,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Nocturne.Neutral300)
    }
}
