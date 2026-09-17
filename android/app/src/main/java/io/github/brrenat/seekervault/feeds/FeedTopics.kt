package io.github.brrenat.seekervault.feeds

/**
 * Where a gateway sends word that a feed changed, for a phone nobody is looking at (SEE-92).
 *
 * The stream next door ([FeedStream]) is the live half, and it lives and dies with the foreground:
 * a phone that is being held gets the documents themselves, in order, as they are published. This
 * is the other half, and it is deliberately almost nothing — a name to subscribe to, so that the
 * platform can wake the app up when a feed moves, and the app can then read the feed the way it
 * always does.
 *
 * ## Why the gateway is asked instead of the name being worked out here
 *
 * Because it has to be the same name on both sides, and a name derived twice is a mismatch that
 * shows up as silence rather than as an error. The deployment also scopes it — a sandbox gateway
 * and a production one must not share — and that scope is the deployment's business, not something
 * this app could know. So the gateway states it, one call, the same shape as the grant a listener
 * asks for ([FeedTickets]).
 *
 * ## What a topic is not
 *
 * It is not a credential and not proof of anything. Holding one lets somebody receive the news that
 * a public broadcast changed, which is news anyone can also get by reading the broadcast. Nothing
 * arrives on it but a fixed, content-free hint, and nothing the app does with a hint depends on
 * trusting it: the documents come from the gateway's own API, through the same validators a
 * snapshot's documents go through (docs/security.md).
 */
interface FeedTopics {
    /**
     * Where hints about [channels] at [gatewayUrl] arrive — each channel one the phone holds a feed
     * reference for.
     *
     * The answer names only the channels this gateway both hosts and relays, in the order they were
     * asked for. A channel that is missing from it is not an error: the phone keeps the hints for
     * its other feeds and reads that one when the owner looks.
     *
     * Throws [io.github.brrenat.seekervault.connections.GatewayException] with
     * [io.github.brrenat.seekervault.connections.GatewayException.Kind.Unimplemented] when the
     * gateway relays nothing at all, which is a smaller deployment rather than a failure.
     */
    suspend fun topics(gatewayUrl: String, channels: List<String>): List<FeedChannelTopic>
}

/**
 * A channel this phone knows, and the topic the same feed's hints arrive on.
 *
 * Two strings and nothing else, for the reason [GrantedChannel] has two: the first is the
 * protocol's, which the phone validates and stores, and the second is a transport's name that the
 * phone only ever hands back to the platform.
 */
data class FeedChannelTopic(val channel: String, val topic: String)
