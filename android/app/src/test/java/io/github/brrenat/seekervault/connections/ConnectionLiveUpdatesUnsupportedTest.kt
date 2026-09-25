package io.github.brrenat.seekervault.connections

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.ConnectionsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.designsystem.ServerRowState
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import io.github.brrenat.seekervault.sync.ForegroundUpdatesState
import io.github.brrenat.seekervault.sync.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A paired server that answers but does not stream, and what Home says about it (SEE-155).
 *
 * The bug: `ForegroundConnectionState.Unsupported` was counted as unreachable, so a server whose
 * unary API was answering perfectly well — which is *how* the phone learned it has no update
 * listener — was shown as "Couldn't reach the server", with a Retry control and nothing to retry.
 * The connection's own screen had said the accurate thing all along; Home contradicted it.
 *
 * So each test here pins one of four different things, which used to look identical on Home: a
 * reachable server that does not stream, a genuine network failure, a revoked pairing, and a server
 * whose advertised update address this build cannot use.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionLiveUpdatesUnsupportedTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun home(live: ForegroundConnectionState?, connection: Connection = HOME) =
        homeScreenState(
                connectionsState =
                    ConnectionsUiState(
                        connections = listOf(connection),
                        loaded = true,
                        updates =
                            ForegroundUpdatesState(
                                foreground = true,
                                connections =
                                    live?.let { mapOf(connection.id to it) } ?: emptyMap(),
                            ),
                    ),
                inboxSummary = null,
                wallet = null,
                pendingItems = emptyList(),
                requestAssessments = emptyMap(),
            )
            .servers
            .single()

    /**
     * The reported case. The last unary refresh succeeded — the fixture's own `lastCheck` is an
     * `Ok` with two pending — and discovery came back saying there is no update listener. Nothing
     * is unreachable, so the row is not, and the line says which capability is missing.
     */
    @Test
    fun aReachableServerWithNoUpdateListenerIsNotUnreachable() {
        val row = home(ForegroundConnectionState.Unsupported(UpdateAvailability.NotConfigured))

        assertEquals(ServerRowState.Connected, row.rowState)
        assertEquals("${HomeCopy.NoLiveUpdates} · 2 pending", row.model.statusText)
    }

    /** A sidecar too old for the update protocol says so, and Home says which it is. */
    @Test
    fun aSidecarThatMustBeUpgradedSaysSo() {
        val row = home(ForegroundConnectionState.Unsupported(UpdateAvailability.UpgradeRequired))

        assertEquals(ServerRowState.Connected, row.rowState)
        assertEquals("${HomeCopy.UpgradeForLiveUpdates} · 2 pending", row.model.statusText)
    }

    /**
     * An advertised update address this build cannot use is still a reachable server with a working
     * refresh. It is a configuration fault, and the sentence that says what to do about it is on
     * the connection's own screen, where there is room for it.
     */
    @Test
    fun anIncompatibleUpdateAddressIsNotAReachabilityProblem() {
        val row = home(ForegroundConnectionState.Unsupported(UpdateAvailability.Incompatible))

        assertEquals(ServerRowState.Connected, row.rowState)
        assertEquals("${HomeCopy.LiveUpdatesUnusable} · 2 pending", row.model.statusText)
    }

    /** A genuine network failure is still a genuine network failure, and still says so. */
    @Test
    fun aNetworkFailureIsStillUnreachable() {
        val row = home(ForegroundConnectionState.Unreachable(CheckOutcome.Unreachable))

        assertEquals(ServerRowState.Unreachable, row.rowState)
        assertEquals(HomeCopy.Unreachable, row.model.statusText.substringBefore(" ·"))
    }

    /** And a revoked pairing is the one case that does ask the owner to pair again. */
    @Test
    fun arevokedPairingStillAsksForRePairing() {
        val row = home(ForegroundConnectionState.Revoked)

        assertEquals(ServerRowState.Disconnected, row.rowState)
        assertEquals(HomeCopy.Disconnected, row.model.statusText)
    }

    /**
     * Discovery that has not finished, or that finished saying updates *are* available, is not an
     * "unsupported" state at all. If one arrives here it is a fault like any other and is shown as
     * one, rather than being quietly reported as a missing capability.
     */
    @Test
    fun anUnfinishedDiscoveryIsNotReportedAsAMissingCapability() {
        val row = home(ForegroundConnectionState.Unsupported(UpdateAvailability.Unknown))

        assertEquals(ServerRowState.Unreachable, row.rowState)
    }

    /**
     * And the detail screen, which Home is now consistent with: the same three states, each said in
     * the sentence the owner can act on. One screen, told each state in turn, so that what is being
     * compared is the mapping and not three separate compositions.
     */
    @Test
    fun theDetailScreenSaysTheSameThreeThingsAtLength() {
        val availability = mutableStateOf(UpdateAvailability.NotConfigured)
        compose.setContent {
            SeekerTheme {
                ConnectionDetailsScreen(
                    connection = HOME,
                    refreshing = false,
                    disconnect = null,
                    message = null,
                    onBack = {},
                    onRefresh = {},
                    onRename = { null },
                    onDisconnect = {},
                    onConfirmDisconnect = {},
                    onConfirmRemove = {},
                    onDismissDisconnect = {},
                    onMessageShown = {},
                    live = ForegroundConnectionState.Unsupported(availability.value),
                    support = ServerSupport.Supported,
                )
            }
        }

        mapOf(
                UpdateAvailability.NotConfigured to
                    R.string.connection_status_updates_not_configured,
                UpdateAvailability.UpgradeRequired to R.string.connection_status_upgrade_required,
                UpdateAvailability.Incompatible to R.string.connection_status_updates_incompatible,
            )
            .forEach { (state, string) ->
                availability.value = state
                compose.waitForIdle()
                compose
                    .onNodeWithTag(ConnectionsTags.STATUS)
                    .assertTextEquals(context.getString(string))
            }
    }
}
