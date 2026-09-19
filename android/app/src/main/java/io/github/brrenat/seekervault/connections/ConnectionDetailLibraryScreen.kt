package io.github.brrenat.seekervault.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.brrenat.seekervault.designsystem.ConnectionDetailFact
import io.github.brrenat.seekervault.designsystem.ConnectionDetailRules
import io.github.brrenat.seekervault.designsystem.ConnectionDetailSheet
import io.github.brrenat.seekervault.designsystem.ConnectionDetailSheetCallbacks
import io.github.brrenat.seekervault.designsystem.ConnectionDetailSheetState
import io.github.brrenat.seekervault.designsystem.ConnectionDetailStatus
import io.github.brrenat.seekervault.designsystem.ConnectionDetailStatusTone
import io.github.brrenat.seekervault.designsystem.FactRowValueStyle
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.policy.PolicyTags
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import io.github.brrenat.seekervault.ui.SeekerSnackbarHost

/** App-to-library adapter for the SEE-122 connection-detail sheet. */
@Composable
fun ConnectionDetailLibraryScreen(
    connection: Connection,
    refreshing: Boolean,
    disconnect: DisconnectState?,
    message: ConnectionMessage?,
    overrideCount: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> LabelProblem?,
    onDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissDisconnect: () -> Unit,
    onMessageShown: () -> Unit,
    onRules: () -> Unit,
    onInbox: () -> Unit,
    onPairDirect: () -> Unit,
    modifier: Modifier = Modifier,
    live: ForegroundConnectionState? = null,
    support: ServerSupport? = null,
    feed: FeedListenerState? = null,
) {
    val problem = hasProblem(connection, live, support, feed)
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(message, snackbar, onMessageShown)
    var renaming by rememberSaveable { mutableStateOf(false) }
    val overrideText =
        if (overrideCount == 0) "Uses global rules"
        else
            "Uses global rules · $overrideCount ${if (overrideCount == 1) "override" else "overrides"}"

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        ConnectionDetailSheet(
            state =
                ConnectionDetailSheetState(
                    title = connection.label,
                    initials = connection.label.initials(),
                    colourName = "Tangerine",
                    colourSupportingText = "marks this server everywhere",
                    status =
                        ConnectionDetailStatus(
                            headline = statusText(connection, live, support, feed),
                            supportingText =
                                connection.lastCheck?.let { "Checked ${formatInstant(it.at)}" },
                            tone =
                                if (problem) ConnectionDetailStatusTone.Problem
                                else ConnectionDetailStatusTone.Connected,
                            tag = ConnectionsTags.STATUS,
                            enabled =
                                (connection.usable ||
                                    connection.mode == ConnectionMode.GatewayFeed) && !refreshing,
                        ),
                    facts =
                        listOf(
                            ConnectionDetailFact(
                                if (connection.retirement != null) "Former gateway" else "Server",
                                connection.serverUrl,
                                FactRowValueStyle.MonoWrap,
                                ConnectionsTags.field("server"),
                            ),
                            ConnectionDetailFact(
                                "Server ID",
                                connection.serverId,
                                FactRowValueStyle.MonoWrap,
                                ConnectionsTags.field("serverId"),
                            ),
                            ConnectionDetailFact(
                                "Paired",
                                formatInstant(connection.pairedAt),
                                FactRowValueStyle.Plain,
                                ConnectionsTags.field("paired"),
                            ),
                            ConnectionDetailFact(
                                "This phone’s name there",
                                connection.deviceName,
                                FactRowValueStyle.Plain,
                                ConnectionsTags.field("deviceName"),
                            ),
                        ),
                    rules =
                        if (connection.retirement != null) null
                        else
                            ConnectionDetailRules(
                                title = "Rules",
                                supportingText = overrideText,
                                caption =
                                    "Rules highlight requests that need attention. You still approve " +
                                        "every request.",
                                tag = PolicyTags.RULES,
                            ),
                    renameLabel = "Rename",
                    inboxLabel =
                        if (connection.retirement != null) "Pair directly" else "Its inbox",
                    disconnectExplanation =
                        if (connection.usable) {
                            "The server revokes this phone's credential and cancels its pending " +
                                "requests. To connect again, pair with a new code."
                        } else {
                            "Remove this connection from this phone. To connect again, pair with " +
                                "a new code."
                        },
                    disconnectLabel = if (connection.usable) "Disconnect" else "Remove",
                    renameTag = ConnectionsTags.RENAME,
                    inboxTag =
                        if (connection.retirement != null) ConnectionsTags.PAIR_DIRECT
                        else ConnectionsTags.PENDING,
                    disconnectTag =
                        if (connection.usable) {
                            ConnectionsTags.DISCONNECT
                        } else {
                            ConnectionsTags.REMOVE
                        },
                    closeTag = ConnectionsTags.CLOSE,
                ),
            callbacks =
                ConnectionDetailSheetCallbacks(
                    onClose = onBack,
                    onRefresh = onRefresh,
                    onRules = onRules,
                    onRename = { renaming = true },
                    onInbox = if (connection.retirement != null) onPairDirect else onInbox,
                    onDisconnect = onDisconnect,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
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

private fun String.initials(): String =
    trim()
        .split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotEmpty)
        .take(2)
        .joinToString("") { it.take(1).uppercase() }
        .ifEmpty { take(2).uppercase() }
