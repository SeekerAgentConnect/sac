package io.github.brrenat.seekervault.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.connections.outcomeText
import io.github.brrenat.seekervault.request.v1.ActionRequest
import java.time.Instant

/**
 * Request details: who asked, what, and until when. A pending acknowledgement offers Acknowledge
 * and Reject; once answered, the screen shows the stored outcome instead of the buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetailsScreen(
    request: ActionRequest,
    source: Connection?,
    result: LocalResult?,
    sending: Boolean,
    now: Instant,
    onAnswer: (Answer) -> Unit,
    onSendAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            val problem =
                result != null &&
                    (result.delivery == Delivery.Superseded ||
                        result.delivery == Delivery.Undeliverable)
            Text(
                statusText(request, result, sending, now),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (problem) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(InboxTags.STATUS),
            )
            if (result?.delivery == Delivery.Waiting && result.lastFailure != null && !sending) {
                Text(
                    stringResource(R.string.status_last_failure, outcomeText(result.lastFailure)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (sending) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(16.dp).testTag(InboxTags.SENDING)
                )
            }
            Field(
                R.string.request_field_from,
                source?.let { "${it.label} (${PairingCodes.hostOf(it.serverUrl)})" }
                    ?: request.ref.connectionId,
                "from",
            )
            Field(R.string.request_field_action, actionText(request), "action")
            request.text()?.let { text ->
                // The agent's text, verbatim and as plain text: never parsed, linked, or executed.
                ListItem(
                    overlineContent = { Text(stringResource(R.string.request_field_message)) },
                    headlineContent = {
                        Text(text, modifier = Modifier.testTag(InboxTags.MESSAGE))
                    },
                )
            }
            if (request.agentNote.isNotEmpty()) {
                // Shown apart from the request itself: the agent wrote it, and nothing checked it.
                ListItem(
                    overlineContent = { Text(stringResource(R.string.request_field_note)) },
                    headlineContent = { Text(request.agentNote) },
                    modifier = Modifier.testTag(InboxTags.NOTE),
                )
            }
            val created = request.createdAt.instant()
            val expires = request.expiresAt.instant()
            Field(
                R.string.request_field_created,
                "${formatInstant(created)} (${relativeTime(created, now)})",
                "created",
            )
            Field(
                R.string.request_field_expires,
                "${formatInstant(expires)} (${relativeTime(expires, now)})",
                "expires",
            )
            Field(R.string.request_field_id, request.ref.requestId, "requestId")
            if (canAnswer(request, result, now)) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onAnswer(Answer.Acknowledge) },
                        enabled = !sending,
                        modifier = Modifier.testTag(InboxTags.ACKNOWLEDGE),
                    ) {
                        Text(stringResource(R.string.acknowledge))
                    }
                    OutlinedButton(
                        onClick = { onAnswer(Answer.Reject) },
                        enabled = !sending,
                        modifier = Modifier.testTag(InboxTags.REJECT),
                    ) {
                        Text(stringResource(R.string.reject))
                    }
                }
            }
            if (result?.delivery == Delivery.Waiting) {
                OutlinedButton(
                    onClick = onSendAgain,
                    enabled = !sending,
                    modifier = Modifier.padding(16.dp).testTag(InboxTags.SEND_AGAIN),
                ) {
                    Text(stringResource(R.string.send_again))
                }
            }
        }
    }
}

/** The request isn't known here: it's no longer pending, or the inbox hasn't been fetched yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestGoneScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { innerPadding ->
        Text(
            stringResource(R.string.status_gone),
            modifier = Modifier.padding(innerPadding).padding(16.dp).testTag(InboxTags.GONE),
        )
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String, name: String) {
    ListItem(
        overlineContent = { Text(stringResource(label)) },
        headlineContent = { Text(value) },
        modifier = Modifier.testTag(InboxTags.field(name)),
    )
}
