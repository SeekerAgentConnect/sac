package io.github.brrenat.seekervault.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme

/** Test tags for the screen's controls. */
object LiveCommandTags {
    const val SERVER_URL = "serverUrl"
    const val PHONE_TOKEN = "phoneToken"
    const val CONNECT = "connect"
    const val DISCONNECT = "disconnect"
    const val CONNECTION_STATUS = "connectionStatus"
    const val LIMITATION = "limitation"
    const val COMMAND_TEXT = "commandText"
    const val OK = "ok"
    const val COMMAND_STATUS = "commandStatus"
}

@Composable
fun LiveCommandRoute(viewModel: LiveCommandViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiveCommandScreen(
        state = state,
        onServerUrlChange = viewModel::onServerUrlChange,
        onPhoneTokenChange = viewModel::onPhoneTokenChange,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onOk = viewModel::acknowledge,
    )
}

/** The Stage 1 live-test screen: stock Material 3 components only. */
@Composable
fun LiveCommandScreen(
    state: LiveCommandUiState,
    onServerUrlChange: (String) -> Unit,
    onPhoneTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val disconnected = state.connection is ConnectionState.Disconnected
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.live_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text(stringResource(R.string.server_url_label)) },
                enabled = disconnected,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag(LiveCommandTags.SERVER_URL),
            )
            OutlinedTextField(
                value = state.phoneToken,
                onValueChange = onPhoneTokenChange,
                label = { Text(stringResource(R.string.phone_token_label)) },
                enabled = disconnected,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                modifier = Modifier.fillMaxWidth().testTag(LiveCommandTags.PHONE_TOKEN),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = disconnected,
                    modifier = Modifier.testTag(LiveCommandTags.CONNECT),
                ) {
                    Text(stringResource(R.string.connect))
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !disconnected,
                    modifier = Modifier.testTag(LiveCommandTags.DISCONNECT),
                ) {
                    Text(stringResource(R.string.disconnect))
                }
            }
            ConnectionStatus(state.connection)
            HorizontalDivider()
            CommandSection(state.command, onOk)
        }
    }
}

@Composable
private fun ConnectionStatus(connection: ConnectionState) {
    val reason = (connection as? ConnectionState.Disconnected)?.reason
    val isProblem = reason != null && reason != DisconnectReason.Background
    Text(
        text = connectionText(connection),
        color =
            if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(LiveCommandTags.CONNECTION_STATUS),
    )
    if (
        reason is DisconnectReason.Lost ||
            reason == DisconnectReason.Replaced ||
            reason == DisconnectReason.Background
    ) {
        Text(
            text = stringResource(R.string.limitation_foreground_only),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(LiveCommandTags.LIMITATION),
        )
    }
}

@Composable
private fun connectionText(connection: ConnectionState): String =
    when (connection) {
        ConnectionState.Connecting -> stringResource(R.string.status_connecting)
        ConnectionState.Connected -> stringResource(R.string.status_connected)
        is ConnectionState.Disconnected ->
            when (val reason = connection.reason) {
                null -> stringResource(R.string.status_disconnected)
                DisconnectReason.InvalidUrl -> stringResource(R.string.status_invalid_url)
                DisconnectReason.MissingToken -> stringResource(R.string.status_missing_token)
                DisconnectReason.Background -> stringResource(R.string.status_background)
                DisconnectReason.Unauthenticated -> stringResource(R.string.status_unauthenticated)
                DisconnectReason.Replaced -> stringResource(R.string.status_replaced)
                DisconnectReason.CleartextBlocked ->
                    stringResource(R.string.status_cleartext_blocked)
                is DisconnectReason.Lost ->
                    reason.detail?.let { stringResource(R.string.status_lost_detail, it) }
                        ?: stringResource(R.string.status_lost)
            }
    }

@Composable
private fun CommandSection(command: ReceivedCommand?, onOk: () -> Unit) {
    if (command == null) {
        Text(stringResource(R.string.no_command))
        return
    }
    Text(stringResource(R.string.received_text_label), style = MaterialTheme.typography.labelLarge)
    // The agent's text, verbatim and as plain text: never parsed, linked, or executed.
    Text(
        text = command.text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(LiveCommandTags.COMMAND_TEXT),
    )
    Button(
        onClick = onOk,
        enabled = command.status == CommandStatus.AwaitingOk,
        modifier = Modifier.testTag(LiveCommandTags.OK),
    ) {
        Text(stringResource(R.string.ok))
    }
    Text(
        text = commandStatusText(command.status),
        modifier = Modifier.testTag(LiveCommandTags.COMMAND_STATUS),
    )
}

@Composable
private fun commandStatusText(status: CommandStatus): String =
    stringResource(
        when (status) {
            CommandStatus.AwaitingOk -> R.string.command_awaiting
            CommandStatus.Sending -> R.string.command_sending
            CommandStatus.Acknowledged -> R.string.command_acknowledged
            CommandStatus.TimedOut -> R.string.command_timed_out
            is CommandStatus.Failed ->
                when (status.reason) {
                    AcknowledgeFailure.Cancelled -> R.string.command_cancelled
                    AcknowledgeFailure.UnknownCommand -> R.string.command_unknown
                    AcknowledgeFailure.Unauthenticated -> R.string.command_unauthenticated
                    AcknowledgeFailure.Unreachable -> R.string.command_unreachable
                    AcknowledgeFailure.Other -> R.string.command_failed
                }
        }
    )

@Preview(showBackground = true)
@Composable
private fun LiveCommandScreenPreview() {
    SeekerVaultTheme {
        LiveCommandScreen(
            state =
                LiveCommandUiState(
                    phoneToken = "a-development-token",
                    connection = ConnectionState.Connected,
                    command = ReceivedCommand("c1", "Hello from Hermes", CommandStatus.AwaitingOk),
                ),
            onServerUrlChange = {},
            onPhoneTokenChange = {},
            onConnect = {},
            onDisconnect = {},
            onOk = {},
        )
    }
}
