package io.github.brrenat.seekervault.feeds

import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerManifest as Manifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whether the publisher behind each feed is running, asked for while the app is being looked at
 * (SEE-150).
 *
 * The question these tests keep separate is the one the bug conflated: this manager never says a
 * feed is online because the gateway answered, and never says one is offline because the gateway
 * did not. The gateway is a fake that answers what a test chooses, including by failing, so what is
 * under test is exactly what this class concludes from an answer — or from the absence of one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FeedStatusManagerTest {
    private val feeds = MutableStateFlow(listOf(feed(SERVER_A)))
    private val gateway = FakeStatuses()

    private fun manager(scope: CoroutineScope) =
        FeedStatusManager(
            connections = feeds,
            statuses = gateway,
            sleep = { delay(it) },
            // No jitter, so the interval in these tests is the number the policy chose.
            jitter = { it },
            ownerScope = scope,
        )

    @Test
    fun theFirstReadHappensAtOnceAndEachFeedGetsItsOwnAnswer() = runTest {
        feeds.value = listOf(feed(SERVER_A), feed(SERVER_B))
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        gateway.answers[channelFor(SERVER_B)] = FeedAvailability.Offline
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        // One call for both feeds, because they are on one gateway, and the channels in it.
        assertEquals(1, gateway.asked.size)
        assertEquals(GATEWAY, gateway.asked.single().first)
        assertEquals(
            setOf(channelFor(SERVER_A), channelFor(SERVER_B)),
            gateway.asked.single().second.toSet(),
        )
        // One publisher being down says nothing about the other, which is the whole of what
        // "independently" means here.
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
        assertEquals(FeedAvailability.Offline, status.state.value.availabilityOf(FEED_B))
    }

    /** A feed nothing has been heard about is unknown, and unknown is never online. */
    @Test
    fun aFeedWithNoAnswerIsUnknown() = runTest {
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        assertEquals(FeedAvailability.Unknown, status.state.value.availabilityOf(FEED_A))
    }

    /**
     * A publisher that comes back is shown as back without anybody touching the app, which is the
     * acceptance the reporter asked for: the answer changes on the next pass and the state follows.
     */
    @Test
    fun aFeedComesBackOnTheNextPass() = runTest {
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Offline
        val status = manager(backgroundScope)
        status.onForeground()
        runCurrent()
        assertEquals(FeedAvailability.Offline, status.state.value.availabilityOf(FEED_A))

        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
        assertEquals(2, gateway.asked.size)
    }

    /**
     * A gateway that fails leaves its feeds as they were rather than calling their publishers dead.
     * Claiming otherwise would be the same conflation from the other side: an unreachable gateway
     * is a fact about this phone's network, and the owner is already told about it by the row's own
     * connectivity.
     */
    @Test
    fun aGatewayThatFailsDoesNotMakeItsFeedsOffline() = runTest {
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        val status = manager(backgroundScope)
        status.onForeground()
        runCurrent()

        gateway.fail = GatewayException(GatewayException.Kind.Unreachable, "no route")
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
    }

    /**
     * And a gateway older than SEE-150 answers `unimplemented`, which is a working deployment: its
     * feeds stay unknown and nothing is shown as wrong.
     */
    @Test
    fun aGatewayThatDoesNotAnswerPresenceLeavesItsFeedsUnknown() = runTest {
        gateway.fail = GatewayException(GatewayException.Kind.Unimplemented, "no such method")
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        assertEquals(FeedAvailability.Unknown, status.state.value.availabilityOf(FEED_A))
    }

    /** A gateway that fails does not stop the next one being asked. */
    @Test
    fun oneGatewaysFailureDoesNotStopTheOthers() = runTest {
        feeds.value = listOf(feed(SERVER_A), feed(SERVER_B, gateway = OTHER_GATEWAY))
        gateway.answers[channelFor(SERVER_B)] = FeedAvailability.Offline
        gateway.failing = GATEWAY
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        assertEquals(FeedAvailability.Unknown, status.state.value.availabilityOf(FEED_A))
        assertEquals(FeedAvailability.Offline, status.state.value.availabilityOf(FEED_B))
    }

    /**
     * Adding a feed does not restart anything — the channels are in the request, not in a ticket —
     * so the next pass simply asks about the new set.
     */
    @Test
    fun aFeedAddedLaterIsAskedAboutOnTheNextPass() = runTest {
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        gateway.answers[channelFor(SERVER_B)] = FeedAvailability.Offline
        val status = manager(backgroundScope)
        status.onForeground()
        runCurrent()

        feeds.value = listOf(feed(SERVER_A), feed(SERVER_B))
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(FeedAvailability.Offline, status.state.value.availabilityOf(FEED_B))
    }

    /** And a feed the owner removed stops being an answer about anything. */
    @Test
    fun aRemovedFeedIsForgotten() = runTest {
        feeds.value = listOf(feed(SERVER_A), feed(SERVER_B))
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        gateway.answers[channelFor(SERVER_B)] = FeedAvailability.Offline
        val status = manager(backgroundScope)
        status.onForeground()
        runCurrent()

        feeds.value = listOf(feed(SERVER_A))
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(FeedAvailability.Unknown, status.state.value.availabilityOf(FEED_B))
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
    }

    /**
     * Nothing is asked while the app is away, and what was known is kept for the owner's return.
     */
    @Test
    fun nothingIsAskedInTheBackgroundAndTheLastAnswerIsKept() = runTest {
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Offline
        val status = manager(backgroundScope)
        status.onForeground()
        runCurrent()
        val asked = gateway.asked.size

        status.onBackground()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(asked, gateway.asked.size)
        assertEquals(false, status.state.value.foreground)
        // Kept rather than cleared: what the gateway said is the best thing known until the first
        // read of the next session lands, and blanking it would show a stopped feed as unknown
        // every
        // time the app is reopened.
        assertEquals(FeedAvailability.Offline, status.state.value.availabilityOf(FEED_A))
    }

    /** A configuration change must not start a second poll. */
    @Test
    fun comingToTheForegroundTwiceAsksOnce() = runTest {
        val status = manager(backgroundScope)

        status.onForeground()
        status.onForeground()
        runCurrent()

        assertEquals(1, gateway.asked.size)
    }

    /**
     * A manual refresh asks now, so the owner pulling to refresh is never made to wait an interval.
     */
    @Test
    fun aRefreshAsksAtOnce() = runTest {
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Offline
        val status = manager(backgroundScope)
        status.onForeground()
        runCurrent()

        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        status.refresh()
        runCurrent()

        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
    }

    /** A revoked feed is not asked about: there is nothing left to be online. */
    @Test
    fun aRevokedFeedIsNotAskedAbout() = runTest {
        feeds.value = listOf(feed(SERVER_A).copy(revokedAt = Instant.parse("2026-09-17T10:00:00Z")))
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        assertEquals(emptyList<Pair<String, List<String>>>(), gateway.asked)
    }

    /** A direct connection has no publisher behind it and is never asked about. */
    @Test
    fun aDirectConnectionIsNotAskedAbout() = runTest {
        feeds.value =
            listOf(
                feed(SERVER_A)
                    .copy(
                        mode = ConnectionMode.Direct,
                        hasCredential = true,
                        // A direct connection's manifest is a direct one, and it may have none at
                        // all: the phone pairs with it before it has read one.
                        server = ServerRecord.Unknown,
                    )
            )
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        assertEquals(emptyList<Pair<String, List<String>>>(), gateway.asked)
    }

    /**
     * A gateway that answers what it was not asked is the client's refusal, and it is not fatal
     * here.
     */
    private class FakeStatuses : FeedStatuses {
        val asked = mutableListOf<Pair<String, List<String>>>()
        val answers = mutableMapOf<String, FeedAvailability>()

        /** Thrown by every call when set. */
        var fail: GatewayException? = null

        /** Thrown by calls to one gateway only. */
        var failing: String? = null

        override suspend fun statuses(
            gatewayUrl: String,
            channels: List<String>,
        ): Map<String, FeedAvailability> {
            asked += gatewayUrl to channels
            fail?.let { throw it }
            if (gatewayUrl == failing) {
                throw GatewayException(GatewayException.Kind.Unreachable, "no route")
            }
            return channels
                .mapNotNull { channel -> answers[channel]?.let { channel to it } }
                .toMap()
        }
    }

    private companion object {
        const val GATEWAY = "https://feeds.example.com"
        const val OTHER_GATEWAY = "https://other.example.com"
        const val SERVER_A = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val FEED_A = "feed-a"
        const val FEED_B = "feed-b"

        fun feed(serverId: String, gateway: String = GATEWAY) =
            Connection(
                id = if (serverId == SERVER_A) FEED_A else FEED_B,
                label = "A feed",
                serverUrl = gateway,
                serverId = serverId,
                deviceName = "",
                pairedAt = Instant.parse("2026-09-17T09:00:00Z"),
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server =
                    ServerRecord.Known(
                        Manifest(
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
    }
}
