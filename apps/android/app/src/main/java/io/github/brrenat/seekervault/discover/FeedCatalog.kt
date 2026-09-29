package io.github.brrenat.seekervault.discover

import io.github.brrenat.seekervault.gateway.v1.ListRecommendedFeedsResponse

/**
 * Where the Discover tab reads a gateway's catalog (SEE-176): `FeedService.ListRecommendedFeeds`,
 * one page at a time.
 *
 * Like every other feed read it is unauthenticated and says nothing about this phone — no session,
 * no identifier, not even which feeds it already follows — so asking for the catalog is the same
 * request from every phone, and nothing about it is written down on either side.
 */
fun interface FeedCatalog {
    /** Throws [io.github.brrenat.seekervault.connections.GatewayException] on any failure. */
    suspend fun recommended(
        gatewayUrl: String,
        pageSize: Int,
        pageToken: String,
    ): ListRecommendedFeedsResponse
}
