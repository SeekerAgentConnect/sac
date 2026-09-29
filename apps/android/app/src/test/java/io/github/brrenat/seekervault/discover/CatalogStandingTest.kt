package io.github.brrenat.seekervault.discover

import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.access.storage.FeedAccessStore
import io.github.brrenat.seekervault.connections.AddConnectionState
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionsUiState
import io.github.brrenat.seekervault.designsystem.CatalogCardStatus
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedAccess
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.SERVER_A
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Discover card's state is the phone's own (SEE-176): the connection repository and the persisted
 * access records decide it, never the catalog, and a restricted feed is never "connected" before
 * the gateway admits this device.
 */
class CatalogStandingTest {
    private val public = catalogFeed(restricted = false)
    private val restricted = catalogFeed(restricted = true)

    private fun catalogFeed(restricted: Boolean, serverId: String = SERVER_B) =
        CatalogFeed(
            GATEWAY,
            serverId,
            "Copy trading",
            "Ideas.",
            restricted,
            emptyList(),
            emptyList(),
        )

    private fun held(
        access: FeedAccess,
        id: String = "feed-1",
        mode: ConnectionMode = ConnectionMode.GatewayFeed,
    ) =
        Connection(
            id = id,
            label = "Copy trading",
            serverUrl = GATEWAY,
            serverId = SERVER_B,
            deviceName = "",
            pairedAt = Instant.parse("2026-09-29T12:00:00Z"),
            hasCredential = false,
            mode = mode,
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = SERVER_B,
                        protocolVersion = 1,
                        settingsRevision = 1,
                        mode = ConnectionMode.GatewayFeed,
                        reference = ServerReference.Feed(GATEWAY, channelFor(SERVER_B), access),
                        environments = setOf(PluginEnvironment.Production),
                        name = "Copy trading",
                    )
                ),
        )

    private fun record(state: FeedAccessStore.State) =
        FeedAccessStore.Record(
            connectionId = "feed-1",
            serverId = SERVER_B,
            wallet = "wallet",
            installation = "installation",
            requestId = "request",
            state = state,
            updatedAt = Instant.parse("2026-09-29T12:00:00Z"),
        )

    private val restrictedAccess = FeedAccess.Restricted("https://auth.example.com")

    @Test
    fun aFeedNothingHoldsOffersConnectOrRequestAccessAndOpensOnboarding() {
        val connect = catalogCardState(public, ConnectionsUiState())
        assertEquals(CatalogCardStatus.Available, connect.status)
        assertEquals(R.string.discover_connect, connect.actionLabel)
        assertEquals(CatalogAction.Onboard(public), connect.action)

        val request = catalogCardState(restricted, ConnectionsUiState())
        assertEquals(R.string.discover_request_access, request.actionLabel)
        assertEquals(CatalogAction.Onboard(restricted), request.action)
    }

    @Test
    fun aFeedBeingAddedCannotBeStartedTwice() {
        val adding =
            ConnectionsUiState(
                adding = AddConnectionState.AddingFeed(FeedReference(GATEWAY, SERVER_B))
            )
        val card = catalogCardState(public, adding)
        assertEquals(CatalogCardStatus.Working, card.status)
        assertEquals(CatalogAction.None, card.action)
        // Another feed being added is not this one.
        assertEquals(
            CatalogCardStatus.Available,
            catalogCardState(catalogFeed(false, SERVER_A), adding).status,
        )
    }

    @Test
    fun aPublicFeedAlreadyHeldIsConnectedAndOpensItsConnection() {
        val card =
            catalogCardState(
                public,
                ConnectionsUiState(connections = listOf(held(FeedAccess.Public))),
            )
        assertEquals(CatalogCardStatus.Connected, card.status)
        assertEquals(R.string.discover_open, card.actionLabel)
        assertEquals(CatalogAction.Open("feed-1"), card.action)
    }

    /** The repository's duplicate rule: one gateway feed per server ID, whatever the card says. */
    @Test
    fun aHeldFeedIsFoundByTheRepositorysOwnDuplicateRule() {
        // A direct server paired under the same ID is not the feed.
        val direct =
            Connection(
                id = "direct",
                label = "Studio Mac",
                serverUrl = "https://vault.example.com",
                serverId = SERVER_B,
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-29T12:00:00Z"),
            )
        assertEquals(
            CatalogCardStatus.Available,
            catalogCardState(public, ConnectionsUiState(connections = listOf(direct))).status,
        )
        // A card read as public for a feed held as restricted shows its real access state.
        val card =
            catalogCardState(
                public,
                ConnectionsUiState(
                    connections = listOf(held(restrictedAccess)),
                    access = mapOf("feed-1" to record(FeedAccessStore.State.Pending)),
                ),
            )
        assertEquals(CatalogCardStatus.Waiting, card.status)
    }

    @Test
    fun aRestrictedFeedShowsItsPersistedAccessStateAndIsConnectedOnlyWhenAdmitted() {
        val expected =
            mapOf(
                null to
                    Triple(
                        CatalogCardStatus.Stopped,
                        R.string.discover_status_not_requested,
                        R.string.discover_continue,
                    ),
                FeedAccessStore.State.Pending to
                    Triple(
                        CatalogCardStatus.Waiting,
                        R.string.discover_status_pending,
                        R.string.discover_view,
                    ),
                FeedAccessStore.State.Approved to
                    Triple(
                        CatalogCardStatus.Waiting,
                        R.string.discover_status_approved,
                        R.string.discover_view,
                    ),
                FeedAccessStore.State.Connected to
                    Triple(
                        CatalogCardStatus.Connected,
                        R.string.discover_status_connected,
                        R.string.discover_open,
                    ),
                FeedAccessStore.State.Rejected to
                    Triple(
                        CatalogCardStatus.Stopped,
                        R.string.discover_status_rejected,
                        R.string.discover_view,
                    ),
                FeedAccessStore.State.Revoked to
                    Triple(
                        CatalogCardStatus.Stopped,
                        R.string.discover_status_revoked,
                        R.string.discover_view,
                    ),
                FeedAccessStore.State.Expired to
                    Triple(
                        CatalogCardStatus.Stopped,
                        R.string.discover_status_expired,
                        R.string.discover_view,
                    ),
            )
        expected.forEach { (state, triple) ->
            val card =
                catalogCardState(
                    restricted,
                    ConnectionsUiState(
                        connections = listOf(held(restrictedAccess)),
                        access = state?.let { mapOf("feed-1" to record(it)) }.orEmpty(),
                    ),
                )
            assertEquals("$state", triple, Triple(card.status, card.statusText, card.actionLabel))
            // Every held feed opens its own connection; none starts onboarding again.
            assertEquals(CatalogAction.Open("feed-1"), card.action)
        }
    }

    @Test
    fun anAccessRequestInFlightIsWorkingAndOffersNothing() {
        val card =
            catalogCardState(
                restricted,
                ConnectionsUiState(
                    connections = listOf(held(restrictedAccess)),
                    accessWorking = setOf("feed-1"),
                ),
            )
        assertEquals(CatalogCardStatus.Working, card.status)
        assertEquals(CatalogAction.None, card.action)
    }
}
