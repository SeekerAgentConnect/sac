package io.github.brrenat.seekervault.discover

import io.github.brrenat.seekervault.gateway.v1.RecommendedFeed
import io.github.brrenat.seekervault.gateway.v1.listRecommendedFeedsResponse
import io.github.brrenat.seekervault.gateway.v1.recommendedFeed
import io.github.brrenat.seekervault.server.v1.FeedAccessPolicy
import io.github.brrenat.seekervault.server.v1.SolanaNetwork
import io.github.brrenat.seekervault.server.v1.feedAccess
import io.github.brrenat.seekervault.server.v1.pluginRequirement
import io.github.brrenat.seekervault.servers.FeedReferenceResult
import io.github.brrenat.seekervault.servers.FeedReferences
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.SERVER_A
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.wallet.WalletNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone accepts from a catalog page, and the reference it builds from a card (SEE-176).
 */
class CatalogFeedTest {
    private val noCleartext: (String) -> Boolean = { false }

    private fun wire(
        serverId: String = SERVER_B,
        gateway: String = GATEWAY,
        restricted: Boolean = false,
        change: (RecommendedFeed.Builder) -> Unit = {},
    ): RecommendedFeed = recommendedFeed {
        this.serverId = serverId
        gatewayUrl = gateway
        channel = channelFor(serverId)
        displayName = " Copy trading "
        description = "Daily swap ideas.\nReview each one yourself."
        access = feedAccess {
            if (restricted) {
                policy = FeedAccessPolicy.FEED_ACCESS_POLICY_RESTRICTED
                authOrigin = "https://auth.example.com"
            } else {
                policy = FeedAccessPolicy.FEED_ACCESS_POLICY_PUBLIC
            }
        }
        supportedNetworks += SolanaNetwork.SOLANA_NETWORK_DEVNET
        supportedNetworks += SolanaNetwork.SOLANA_NETWORK_MAINNET
        requiredPlugins += pluginRequirement {
            pluginId = "jupiter.swap"
            minContract = 1
            maxContract = 1
        }
    }
        .toBuilder()
        .also(change)
        .build()

    @Test
    fun aPublicAndARestrictedListingAreReadWithTheirNetworksInCanonicalOrder() {
        val public = CatalogFeeds.from(wire(), noCleartext)!!
        assertEquals("Copy trading", public.name)
        assertEquals("Daily swap ideas.\nReview each one yourself.", public.description)
        assertFalse(public.restricted)
        assertEquals(listOf(WalletNetwork.Mainnet, WalletNetwork.Devnet), public.networks)
        assertEquals(listOf("jupiter.swap"), public.plugins)
        assertEquals(CatalogKey(GATEWAY, SERVER_B), public.key)

        val restricted = CatalogFeeds.from(wire(restricted = true), noCleartext)!!
        assertTrue(restricted.restricted)
        assertTrue(restricted.reference.restricted)
    }

    @Test
    fun anItemThePhoneCouldNotOnboardFromIsLeftOutAndTheOthersKept() {
        val bad =
            listOf(
                wire(serverId = "not-a-uuid"),
                wire(gateway = "http://gateway.example.com"),
                wire(gateway = "https://gateway.example.com/path"),
                wire { it.channel = channelFor(SERVER_A) },
                wire { it.displayName = "  " },
                wire { it.description = "" },
                wire { it.description = "bell\u0007" },
                wire { it.description = "x".repeat(CatalogFeeds.MAX_DESCRIPTION + 1) },
                wire(restricted = true) {
                    it.access = it.access.toBuilder().setAuthOrigin("").build()
                },
                wire { it.addSupportedNetworks(SolanaNetwork.SOLANA_NETWORK_DEVNET) },
                wire { it.addSupportedNetworks(SolanaNetwork.SOLANA_NETWORK_UNSPECIFIED) },
            )
        bad.forEach { assertNull("accepted $it", CatalogFeeds.from(it, noCleartext)) }

        val page =
            CatalogFeeds.page(
                listRecommendedFeedsResponse {
                    feeds += bad
                    feeds += wire()
                    feeds += wire() // a repeated item is one card
                    nextPageToken = "next"
                },
                noCleartext,
            )
        assertEquals(listOf(SERVER_B), page.feeds.map { it.serverId })
        assertEquals("next", page.nextPageToken)
        assertNull(
            CatalogFeeds.page(listRecommendedFeedsResponse { feeds += wire() }, noCleartext)
                .nextPageToken
        )
    }

    @Test
    fun aLoopbackGatewayIsAcceptedOnlyWhereTheBuildPermitsCleartext() {
        val loopback = wire(gateway = "http://127.0.0.1:8080")
        assertNull(CatalogFeeds.from(loopback, noCleartext))
        assertEquals(
            "http://127.0.0.1:8080",
            CatalogFeeds.from(loopback) { it == "127.0.0.1" }?.gatewayUrl,
        )
    }

    /** A card's reference is the same text a pasted one would be, restricted floor included. */
    @Test
    fun aCardsReferenceReadsBackThroughTheOrdinaryParser() {
        listOf(false, true).forEach { restricted ->
            val feed = CatalogFeeds.from(wire(restricted = restricted), noCleartext)!!
            val parsed = FeedReferences.parse(FeedReferences.format(feed.reference), noCleartext)
            assertEquals(FeedReferenceResult.Valid(feed.reference), parsed)
        }
    }
}
