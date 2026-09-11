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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R

/** The Connections screen: every sidecar this phone is paired with. Stock Material 3 only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onLiveTest: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
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
        if (state.loaded && state.connections.isEmpty()) {
            Text(
                stringResource(R.string.connections_empty),
                modifier =
                    Modifier.padding(innerPadding).padding(16.dp).testTag(ConnectionsTags.EMPTY),
            )
        } else {
            LazyColumn(contentPadding = innerPadding) {
                items(state.connections, key = { it.id }) { connection ->
                    ConnectionItem(connection, onClick = { onOpen(connection.id) })
                    HorizontalDivider()
                }
            }
        }
    }
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
