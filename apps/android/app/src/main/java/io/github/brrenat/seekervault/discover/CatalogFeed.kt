package io.github.brrenat.seekervault.discover

import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.gateway.v1.ListRecommendedFeedsResponse
import io.github.brrenat.seekervault.gateway.v1.RecommendedFeed
import io.github.brrenat.seekervault.server.v1.FeedAccessPolicy
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.FeedReferences
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.servers.supportedNetworksOf
import io.github.brrenat.seekervault.wallet.WalletNetwork

/**
 * One feed in a gateway's Discover catalog (SEE-176, docs/wiki/discover.md).
 *
 * It is what the gateway says about a feed its operator listed, read and bounded here, and it is
 * only ever a way to *start* onboarding: nothing in it is stored, and adding the feed reads and
 * validates the feed's own manifest exactly as a pasted reference would. So a card that went stale
 * — a feed made restricted since the page was read, a new name — is caught when the owner acts on
 * it, never believed.
 */
data class CatalogFeed(
    /** The gateway's canonical origin, as the feed's manifest names it. */
    val gatewayUrl: String,
    val serverId: String,
    val name: String,
    /** The operator's public description: plain text, never markup. */
    val description: String,
    /** The gateway registered the feed as restricted: reading it needs the publisher's approval. */
    val restricted: Boolean,
    /** Declared networks in canonical order; empty when the feed declares none. */
    val networks: List<WalletNetwork>,
    /** The client plugins the feed's operations need, by ID. */
    val plugins: List<String>,
) {
    /** A catalog card's identity: the canonical gateway origin plus the server ID. */
    val key: CatalogKey
        get() = CatalogKey(gatewayUrl, serverId)

    /**
     * The reference the owner adds this feed from. A restricted listing keeps the restricted floor
     * ([FeedReference.restricted]), so a manifest that has since claimed to be public is refused
     * rather than added as an open feed; a listing that was public when read is added as whatever
     * the manifest says now.
     */
    val reference: FeedReference
        get() = FeedReference(gatewayUrl = gatewayUrl, serverId = serverId, restricted = restricted)
}

data class CatalogKey(val gatewayUrl: String, val serverId: String)

/** One page of a catalog, as this phone accepted it. */
data class CatalogPage(val feeds: List<CatalogFeed>, val nextPageToken: String?)

object CatalogFeeds {
    /** The most characters of a name or description kept; the gateway bounds both lower. */
    const val MAX_NAME = 120
    const val MAX_DESCRIPTION = 500
    const val MAX_PLUGINS = 16

    /**
     * The page the gateway answered, with every item this phone could not onboard from left out: a
     * malformed identity, a gateway origin this build may not reach, a channel that is not the
     * server's own, no name or description, an unknown access policy, or networks it cannot read.
     * One bad item never costs the others.
     */
    fun page(
        answer: ListRecommendedFeedsResponse,
        cleartextPermitted: (host: String) -> Boolean,
    ): CatalogPage =
        CatalogPage(
            feeds =
                answer.feedsList.mapNotNull { from(it, cleartextPermitted) }.distinctBy { it.key },
            nextPageToken = answer.nextPageToken.takeIf { it.isNotEmpty() },
        )

    fun from(wire: RecommendedFeed, cleartextPermitted: (host: String) -> Boolean): CatalogFeed? {
        if (!isConnectionId(wire.serverId)) return null
        if (FeedReferences.gatewayUrlProblem(wire.gatewayUrl, cleartextPermitted) != null) {
            return null
        }
        if (wire.channel != channelFor(wire.serverId)) return null
        val name = wire.displayName.trim()
        val description = wire.description.trim()
        if (name.isEmpty() || name.length > MAX_NAME) return null
        if (description.isEmpty() || description.length > MAX_DESCRIPTION) return null
        if (description.any { it.isISOControl() && it != '\n' }) return null
        if (name.any { it.isISOControl() }) return null
        val restricted =
            when (wire.access.policy) {
                FeedAccessPolicy.FEED_ACCESS_POLICY_RESTRICTED -> true
                // An absent policy is public, as it is on a manifest.
                FeedAccessPolicy.FEED_ACCESS_POLICY_PUBLIC,
                FeedAccessPolicy.FEED_ACCESS_POLICY_UNSPECIFIED -> false
                else -> return null
            }
        if (restricted && wire.access.authOrigin.isEmpty()) return null
        val networks = supportedNetworksOf(wire.supportedNetworksValueList) ?: return null
        if (wire.requiredPluginsCount > MAX_PLUGINS) return null
        return CatalogFeed(
            gatewayUrl = PairingCodes.normalizeServerUrl(wire.gatewayUrl),
            serverId = wire.serverId,
            name = name,
            description = description,
            restricted = restricted,
            networks = WalletNetwork.entries.filter { it in networks },
            plugins = wire.requiredPluginsList.map { it.pluginId },
        )
    }
}
