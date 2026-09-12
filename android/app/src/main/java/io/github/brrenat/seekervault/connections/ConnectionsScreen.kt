package io.github.brrenat.seekervault.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.networkText

/** How many requests wait for the owner, and how many answers wait to be sent. */
data class InboxSummary(val waitingForYou: Int, val toSend: Int)

/**
 * The Connections screen: every sidecar this phone is paired with, the way to Pending requests when
 * [inbox] is given, and the way to the Wallet screen. Stock Material 3 only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onLiveTest: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    inbox: InboxSummary? = null,
    onInbox: () -> Unit = {},
    wallet: SelectedWallet? = null,
    onWallet: () -> Unit = {},
    /** How many actions this phone has recorded (SAW-023); null leaves the row out. */
    activity: Int? = null,
    onActivity: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, onMessageShown)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_title)) },
                actions = {
                    TextButton(
                        onClick = onLiveTest,
                        modifier = Modifier.testTag(ConnectionsTags.LIVE_TEST),
                    ) {
                        Text(stringResource(R.string.live_test))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.testTag(ConnectionsTags.ADD),
            ) {
                Text(stringResource(R.string.add_connection))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        // The wallet row comes first and is always there: the owner can connect a wallet before
        // they pair with anything.
        LazyColumn(contentPadding = innerPadding) {
            item(key = "wallet") {
                WalletItem(wallet, onWallet)
                HorizontalDivider()
            }
            if (inbox != null && state.connections.isNotEmpty()) {
                item(key = "inbox") {
                    InboxItem(inbox, onInbox)
                    HorizontalDivider()
                }
            }
            // The record of what this phone has done stays whether or not a connection does.
            if (activity != null) {
                item(key = "activity") {
                    ActivityItem(activity, onActivity)
                    HorizontalDivider()
                }
            }
            if (state.loaded && state.connections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.connections_empty),
                        modifier = Modifier.padding(16.dp).testTag(ConnectionsTags.EMPTY),
                    )
                }
            }
            items(state.connections, key = { it.id }) { connection ->
                ConnectionItem(connection, onClick = { onOpen(connection.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WalletItem(wallet: SelectedWallet?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.wallet_row)) },
        supportingContent = {
            // The address only: it's a public key, and nothing else about the wallet is here.
            Text(
                if (wallet == null) stringResource(R.string.wallet_row_none)
                else
                    stringResource(
                        R.string.wallet_row_connected,
                        wallet.address,
                        networkText(wallet.network),
                    )
            )
        },
        modifier = Modifier.clickable(onClick = onClick).testTag(ConnectionsTags.WALLET),
    )
}

@Composable
private fun InboxItem(inbox: InboxSummary, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.inbox_row)) },
        supportingContent = {
            Text(
                when {
                    inbox.toSend > 0 ->
                        stringResource(
                            R.string.inbox_row_waiting_and_to_send,
                            inbox.waitingForYou,
                            inbox.toSend,
                        )
                    inbox.waitingForYou > 0 ->
                        stringResource(R.string.inbox_row_waiting, inbox.waitingForYou)
                    else -> stringResource(R.string.inbox_row_nothing)
                }
            )
        },
        modifier = Modifier.clickable(onClick = onClick).testTag(ConnectionsTags.INBOX),
    )
}

@Composable
private fun ActivityItem(recorded: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.activity_row)) },
        supportingContent = {
            Text(
                if (recorded == 0) stringResource(R.string.activity_row_none)
                else pluralStringResource(R.plurals.activity_row_count, recorded, recorded)
            )
        },
        modifier = Modifier.clickable(onClick = onClick).testTag(ConnectionsTags.ACTIVITY),
    )
}

@Composable
private fun ConnectionItem(connection: Connection, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(connection.label) },
        supportingContent = {
            // The address and the status only: never the credential.
            Column {
                Text(PairingCodes.hostOf(connection.serverUrl))
                Text(
                    statusText(connection),
                    color =
                        if (hasProblem(connection)) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier =
            Modifier.clickable(onClick = onClick).testTag(ConnectionsTags.item(connection.id)),
    )
}
