package io.github.brrenat.seekervault.push

import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.feeds.FeedChannelTopic
import io.github.brrenat.seekervault.feeds.FeedTopics
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which feeds' hints this phone asks for, and which it stops asking for (SEE-92).
 *
 * The manager holds no list on disk on purpose, so what these tests are really about is whether
 * reconciliation is enough: a feed added is subscribed, a feed removed is unsubscribed, a
 * registration says everything again, a gateway that relays nothing is left alone, and the one case
 * a derived intent cannot cover — a subscription left behind while the app was not running — is
 * cured by the hint that arrives on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedTopicManagerTest {
    /** A gateway that names topics the way the relay does, and can be made to fail. */
    private class Namer(
        var failure: Exception? = null,
        val named: MutableList<List<String>> = mutableListOf(),
        val environment: String = "production",
        val silent: Set<String> = emptySet(),
    ) : FeedTopics {
        override suspend fun topics(
            gatewayUrl: String,
            channels: List<String>,
        ): List<FeedChannelTopic> {
            named += channels
            failure?.let { throw it }
            return channels
                .filterNot { it in silent }
                .map {
                    FeedChannelTopic(it, "feed.$environment.${it.removePrefix("server/")}")
                }
        }
    }

    /** Firebase's topic membership, as this phone asks for it. */
    private class Membership(var fail: Boolean = false) : FeedTopicClient {
        val joined = mutableListOf<String>()
        val left = mutableListOf<String>()

        override suspend fun subscribe(topic: String) {
            if (fail) error("firebase is not available")
            joined += topic
        }

        override suspend fun unsubscribe(topic: String) {
            if (fail) error("firebase is not available")
            left += topic
        }
    }

    @Test
    fun subscribesOnlyAfterTheConnectionsAreLoadedAndOnlyToFeeds() = runTest {
        val loaded = MutableStateFlow(false)
        val connections = MutableStateFlow(listOf(feed(SERVER_A), direct()))
        val gateway = Namer()
        val membership = Membership()
        val manager = manager(loaded, connections, gateway, membership)

        manager.start()
        runCurrent()
        // Nothing before the phone has read what it holds: a subscription made from an empty list
        // would be a subscription to nothing, and one made from a half-read list would be worse.
        assertTrue(membership.joined.isEmpty())
        assertTrue(gateway.named.isEmpty())

        loaded.value = true
        runCurrent()

        assertEquals(listOf(TOPIC_A), membership.joined)
        // The direct connection is not a feed and was never asked about: a paired sidecar's
        // requests reach this phone by its own path (SAW-055).
        assertEquals(listOf(listOf(channelFor(SERVER_A))), gateway.named)
        assertEquals(mapOf(channelFor(SERVER_A) to TOPIC_A), manager.state.value.topics)
        manager.close()
    }

    @Test
    fun aFeedAddedIsSubscribedAndAFeedRemovedIsUnsubscribed() = runTest {
        val connections = MutableStateFlow(listOf(feed(SERVER_A)))
        val gateway = Namer()
        val membership = Membership()
        val manager = manager(MutableStateFlow(true), connections, gateway, membership)
        manager.start()
        runCurrent()

        connections.value = listOf(feed(SERVER_A), feed(SERVER_B))
        runCurrent()
        assertEquals(listOf(TOPIC_A, TOPIC_B), membership.joined)
        // Only the new one is asked about: the first is already subscribed.
        assertEquals(
            listOf(listOf(channelFor(SERVER_A)), listOf(channelFor(SERVER_B))),
            gateway.named,
        )

        connections.value = listOf(feed(SERVER_B))
        runCurrent()
        assertEquals(listOf(TOPIC_A), membership.left)
        assertEquals(mapOf(channelFor(SERVER_B) to TOPIC_B), manager.state.value.topics)
        manager.close()
    }

    /** Two gateways are asked separately, because each names its own topics. */
    @Test
    fun eachGatewayIsAskedAboutItsOwnFeeds() = runTest {
        val connections = MutableStateFlow(listOf(feed(SERVER_A), feed(SERVER_B, OTHER_GATEWAY)))
        val gateway = Namer()
        val membership = Membership()
        val manager = manager(MutableStateFlow(true), connections, gateway, membership)

        manager.start()
        runCurrent()

        assertEquals(
            listOf(listOf(channelFor(SERVER_A)), listOf(channelFor(SERVER_B))),
            gateway.named,
        )
        assertEquals(setOf(TOPIC_A, TOPIC_B), membership.joined.toSet())
        manager.close()
    }

    /**
     * A gateway that relays nothing is a working deployment: it is recorded and left alone rather
     * than retried, and the feeds on it are still read and still streamed.
     */
    @Test
    fun aGatewayThatRelaysNothingIsNotRetried() = runTest {
        val gateway =
            Namer(failure = GatewayException(GatewayException.Kind.Unimplemented, "no push here"))
        val membership = Membership()
        val manager =
            manager(
                MutableStateFlow(true),
                MutableStateFlow(listOf(feed(SERVER_A))),
                gateway,
                membership,
            )

        manager.start()
        advanceUntilIdle()

        assertEquals(1, gateway.named.size)
        assertTrue(membership.joined.isEmpty())
        assertEquals(setOf(GATEWAY), manager.state.value.unsupported)
        assertTrue(manager.state.value.failed.isEmpty())
        manager.close()
    }

    /** A gateway that could not be asked is retried, and the retry is bounded. */
    @Test
    fun aGatewayThatCannotBeAskedIsRetriedABoundedNumberOfTimes() = runTest {
        val gateway =
            Namer(failure = GatewayException(GatewayException.Kind.Unreachable, "no network"))
        val membership = Membership()
        val manager =
            manager(
                MutableStateFlow(true),
                MutableStateFlow(listOf(feed(SERVER_A))),
                gateway,
                membership,
            )

        manager.start()
        advanceUntilIdle()

        assertEquals(1 + FeedTopicManager.MOST_RETRIES, gateway.named.size)
        assertEquals(setOf(GATEWAY), manager.state.value.failed)
        manager.close()
    }

    /** Firebase refusing to join is the same kind of failure, and is retried the same way. */
    @Test
    fun firebaseRefusingToJoinIsRetried() = runTest {
        val gateway = Namer()
        val membership = Membership(fail = true)
        val manager =
            manager(
                MutableStateFlow(true),
                MutableStateFlow(listOf(feed(SERVER_A))),
                gateway,
                membership,
            )

        manager.start()
        advanceUntilIdle()

        assertEquals(1 + FeedTopicManager.MOST_RETRIES, gateway.named.size)
        assertTrue(membership.joined.isEmpty())
        // Nothing is claimed as subscribed that Firebase did not accept.
        assertTrue(manager.state.value.topics.isEmpty())
        manager.close()
    }

    /**
     * A registration says everything again. Topic membership is attached to the installation, and a
     * phone that quietly stopped receiving hints is worse than one call per feed.
     */
    @Test
    fun aRegistrationSubscribesEverythingAgain() = runTest {
        val gateway = Namer()
        val membership = Membership()
        val manager =
            manager(
                MutableStateFlow(true),
                MutableStateFlow(listOf(feed(SERVER_A))),
                gateway,
                membership,
            )
        manager.start()
        runCurrent()
        assertEquals(listOf(TOPIC_A), membership.joined)

        manager.onRegistered()
        runCurrent()

        assertEquals(listOf(TOPIC_A, TOPIC_A), membership.joined)
        assertEquals(mapOf(channelFor(SERVER_A) to TOPIC_A), manager.state.value.topics)
        manager.close()
    }

    /**
     * The one case a derived intent cannot cover: a feed removed while the app was not running
     * leaves a subscription nothing here remembers. The hint that arrives on it is what cures it.
     */
    @Test
    fun aHintForATopicNothingWantsUnsubscribesFromIt() = runTest {
        val gateway = Namer()
        val membership = Membership()
        val manager =
            manager(
                MutableStateFlow(true),
                MutableStateFlow(listOf(feed(SERVER_A))),
                gateway,
                membership,
            )
        manager.start()
        runCurrent()

        // A hint for the feed the owner has changes nothing here.
        manager.onHint(TOPIC_A)
        runCurrent()
        assertTrue(membership.left.isEmpty())

        // A hint for one nothing wants is left.
        manager.onHint(TOPIC_B)
        runCurrent()
        assertEquals(listOf(TOPIC_B), membership.left)
        manager.close()
    }

    /**
     * And it does not act on an unknown topic while a gateway could not be asked: the phone does
     * not know what it wants then, and unsubscribing would drop the hints for a feed the owner
     * still has.
     */
    @Test
    fun anUnknownTopicIsLeftAloneWhileAGatewayCannotBeAsked() = runTest {
        val gateway =
            Namer(failure = GatewayException(GatewayException.Kind.Unreachable, "no network"))
        val membership = Membership()
        val manager =
            manager(
                MutableStateFlow(true),
                MutableStateFlow(listOf(feed(SERVER_A))),
                gateway,
                membership,
            )
        manager.start()
        runCurrent()
        assertEquals(setOf(GATEWAY), manager.state.value.failed)

        manager.onHint(TOPIC_B)
        runCurrent()

        assertTrue(membership.left.isEmpty())
        manager.close()
    }

    /** A name that is not a topic never reaches Firebase, wherever it came from. */
    @Test
    fun aNameThatIsNotATopicIsNotOne() = runTest {
        val gateway = Namer(silent = setOf(channelFor(SERVER_A)))
        val membership = Membership()
        val manager =
            manager(
                MutableStateFlow(true),
                MutableStateFlow(listOf(feed(SERVER_A))),
                gateway,
                membership,
            )
        manager.start()
        runCurrent()
        // A gateway that hosts the feed but named no topic for it: nothing is subscribed, and it is
        // not a failure — the feed is still read on every glance.
        assertTrue(membership.joined.isEmpty())
        assertTrue(manager.state.value.failed.isEmpty())

        for (invalid in
            listOf(
                "",
                "topics/feed.production.x",
                "feed",
                "not-a-feed-topic",
                "feed.production.a b",
                "feed.production.🙂",
                "feed." + "x".repeat(1_000),
            )) {
            manager.onHint(invalid)
        }
        runCurrent()
        assertTrue(membership.left.isEmpty())

        assertTrue(isFeedTopic("feed.production.3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"))
        assertTrue(isFeedTopic("feed.sandbox.3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"))
        assertFalse(isFeedTopic("feeds.production.x"))
        manager.close()
    }

    private fun TestScope.manager(
        loaded: MutableStateFlow<Boolean>,
        connections: MutableStateFlow<List<Connection>>,
        gateway: FeedTopics,
        client: FeedTopicClient,
    ) =
        FeedTopicManager(
            loaded = loaded,
            connections = connections,
            gateway = gateway,
            client = client,
            loadConnections = {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    private companion object {
        const val GATEWAY = "https://feeds.example.com"
        const val OTHER_GATEWAY = "https://other.example.com"
        const val SERVER_A = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val TOPIC_A = "feed.production.$SERVER_A"
        const val TOPIC_B = "feed.production.$SERVER_B"

        fun feed(serverId: String, gateway: String = GATEWAY) =
            Connection(
                id = "feed-$serverId",
                label = "A feed",
                serverUrl = gateway,
                serverId = serverId,
                deviceName = "",
                pairedAt = Instant.parse("2026-09-17T09:00:00Z"),
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server =
                    ServerRecord.Known(
                        ServerManifest(
                            serverId = serverId,
                            protocolVersion = 1,
                            settingsRevision = 1,
                            mode = ConnectionMode.GatewayFeed,
                            reference =
                                ServerReference.Feed(
                                    gatewayUrl = gateway,
                                    channel = channelFor(serverId),
                                ),
                            environments = setOf(PluginEnvironment.Production),
                        )
                    ),
            )

        fun direct() =
            Connection(
                id = "00000000-0000-4000-8000-00000000000d",
                label = "My sidecar",
                serverUrl = "https://sidecar.example.com",
                serverId = "00000000-0000-4000-8000-00000000000d",
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-17T09:00:00Z"),
            )
    }
}
