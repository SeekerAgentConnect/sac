package io.github.brrenat.seekervault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.connections.AddConnectionRoute
import io.github.brrenat.seekervault.connections.ConnectionDetailsScreen
import io.github.brrenat.seekervault.connections.ConnectionsScreen
import io.github.brrenat.seekervault.connections.ConnectionsUiState
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.live.LiveCommandRoute
import io.github.brrenat.seekervault.live.LiveCommandViewModel

/**
 * The app's screens: Connections first, then a connection's details, Add connection, and the Stage
 * 1 live test. The back stack is a list of route strings, so it survives rotation and process
 * death; no route carries a secret.
 */
@Composable
fun SeekerVaultApp(connections: ConnectionsViewModel, live: LiveCommandViewModel) {
    var stack by rememberSaveable { mutableStateOf(listOf(Routes.CONNECTIONS)) }
    val push = { route: String -> stack = stack + route }
    val pop = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }
    val state by connections.state.collectAsStateWithLifecycle()
    val route = stack.last()
    when {
        route == Routes.CONNECTIONS ->
            ConnectionsScreen(
                state = state,
                onOpen = { push(Routes.DETAILS + it) },
                onAdd = { push(Routes.ADD) },
                onLiveTest = { push(Routes.LIVE) },
                onMessageShown = connections::messageShown,
            )
        route == Routes.ADD ->
            AddConnectionRoute(
                viewModel = connections,
                onBack = pop,
                // Show the new connection in place of the Add screen.
                onPaired = { stack = stack.dropLast(1) + (Routes.DETAILS + it.id) },
            )
        route == Routes.LIVE -> LiveTestRoute(live)
        route.startsWith(Routes.DETAILS) ->
            ConnectionDetailsRoute(connections, state, route.removePrefix(Routes.DETAILS), pop)
    }
}

private object Routes {
    const val CONNECTIONS = "connections"
    const val ADD = "add"
    const val LIVE = "live"
    const val DETAILS = "details/"
}

@Composable
private fun ConnectionDetailsRoute(
    viewModel: ConnectionsViewModel,
    state: ConnectionsUiState,
    id: String,
    onBack: () -> Unit,
) {
    val connection = state.connections.firstOrNull { it.id == id }
    if (connection == null) {
        // Removed, or gone after a restart: back to the list.
        LaunchedEffect(id, state.loaded) { if (state.loaded) onBack() }
        return
    }
    // The phone fetches when the owner selects a connection (docs/protocol.md).
    LaunchedEffect(id) { viewModel.refresh(id) }
    ConnectionDetailsScreen(
        connection = connection,
        refreshing = id in state.refreshing,
        disconnect = state.disconnect?.takeIf { it.id == id },
        message = state.message,
        onBack = onBack,
        onRefresh = { viewModel.refresh(id) },
        onRename = { viewModel.rename(id, it) },
        onDisconnect = { viewModel.askToDisconnect(id) },
        onConfirmDisconnect = viewModel::confirmDisconnect,
        onConfirmRemove = viewModel::confirmRemove,
        onDismissDisconnect = viewModel::dismissDisconnect,
        onMessageShown = viewModel::messageShown,
    )
}

@Composable
private fun LiveTestRoute(viewModel: LiveCommandViewModel) {
    val activity = LocalActivity.current
    // The live stream exists only while its screen is open (docs/protocol.md); a rotation keeps it.
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.disconnect() }
    }
    LiveCommandRoute(viewModel)
}
