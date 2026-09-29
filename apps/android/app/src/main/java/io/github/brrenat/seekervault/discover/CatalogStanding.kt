package io.github.brrenat.seekervault.discover

import androidx.annotation.StringRes
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.access.storage.FeedAccessStore
import io.github.brrenat.seekervault.connections.AddConnectionState
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionsUiState
import io.github.brrenat.seekervault.designsystem.CatalogCardStatus
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedAccess
import io.github.brrenat.seekervault.servers.feedAccess
import io.github.brrenat.seekervault.servers.manifest

/** What a Discover card's button does. */
sealed interface CatalogAction {
    /** Start the normal Add connection flow with this feed's reference prefilled. */
    data class Onboard(val feed: CatalogFeed) : CatalogAction

    /** Open the connection this phone already holds for the feed. */
    data class Open(val connectionId: String) : CatalogAction

    /** Nothing to do while something is in flight. */
    data object None : CatalogAction
}

/** A card's local state: how it is drawn, what it says, and what its button does. */
data class CatalogCardState(
    val status: CatalogCardStatus,
    @StringRes val statusText: Int?,
    @StringRes val actionLabel: Int,
    val action: CatalogAction,
    /** The connection this phone holds for the feed, when it holds one. */
    val connectionId: String? = null,
)

/**
 * The connection this phone already holds for [feed], by the repository's own duplicate rule: one
 * gateway feed per server ID
 * ([io.github.brrenat.seekervault.connections.ConnectionRepository.addFeed] answers Already for a
 * second one). Matching the same way means a card can never offer to add what adding would refuse.
 */
fun heldConnection(feed: CatalogFeed, connections: List<Connection>): Connection? =
    connections.firstOrNull {
        it.mode == ConnectionMode.GatewayFeed && it.serverId == feed.serverId
    }

/**
 * Where this phone stands with [feed], worked out from the connection repository and the persisted
 * access records — never from the catalog. A restricted feed is shown by its real access state and
 * never as connected until the gateway admits this device; whether a held feed is restricted is
 * read from the manifest it was added with, so a listing that has since changed cannot relabel it.
 */
fun catalogCardState(feed: CatalogFeed, connections: ConnectionsUiState): CatalogCardState {
    val held = heldConnection(feed, connections.connections)
    if (held == null) {
        val adding =
            when (val adding = connections.adding) {
                is AddConnectionState.AddingFeed -> adding.reference.serverId == feed.serverId
                else -> false
            }
        val label =
            if (feed.restricted) R.string.discover_request_access else R.string.discover_connect
        return if (adding) {
            CatalogCardState(
                CatalogCardStatus.Working,
                R.string.discover_status_adding,
                label,
                CatalogAction.None,
            )
        } else {
            CatalogCardState(CatalogCardStatus.Available, null, label, CatalogAction.Onboard(feed))
        }
    }
    val open = CatalogAction.Open(held.id)
    val restricted = held.server.manifest?.feedAccess is FeedAccess.Restricted
    if (!restricted) {
        return CatalogCardState(
            CatalogCardStatus.Connected,
            R.string.discover_status_connected,
            R.string.discover_open,
            open,
            held.id,
        )
    }
    if (held.id in connections.accessWorking) {
        return CatalogCardState(
            CatalogCardStatus.Working,
            R.string.discover_status_requesting,
            R.string.discover_view,
            CatalogAction.None,
            held.id,
        )
    }
    val (status, text, label) =
        when (connections.access[held.id]?.state) {
            // Added, but no request proven with this feed's wallet yet: no wallet chosen, the
            // signature was cancelled, or the wallet changed since.
            null ->
                Triple(
                    CatalogCardStatus.Stopped,
                    R.string.discover_status_not_requested,
                    R.string.discover_continue,
                )
            FeedAccessStore.State.Pending ->
                Triple(
                    CatalogCardStatus.Waiting,
                    R.string.discover_status_pending,
                    R.string.discover_view,
                )
            FeedAccessStore.State.Approved ->
                Triple(
                    CatalogCardStatus.Waiting,
                    R.string.discover_status_approved,
                    R.string.discover_view,
                )
            FeedAccessStore.State.Connected ->
                Triple(
                    CatalogCardStatus.Connected,
                    R.string.discover_status_connected,
                    R.string.discover_open,
                )
            FeedAccessStore.State.Rejected ->
                Triple(
                    CatalogCardStatus.Stopped,
                    R.string.discover_status_rejected,
                    R.string.discover_view,
                )
            FeedAccessStore.State.Revoked ->
                Triple(
                    CatalogCardStatus.Stopped,
                    R.string.discover_status_revoked,
                    R.string.discover_view,
                )
            FeedAccessStore.State.Expired ->
                Triple(
                    CatalogCardStatus.Stopped,
                    R.string.discover_status_expired,
                    R.string.discover_view,
                )
        }
    return CatalogCardState(status, text, label, open, held.id)
}
