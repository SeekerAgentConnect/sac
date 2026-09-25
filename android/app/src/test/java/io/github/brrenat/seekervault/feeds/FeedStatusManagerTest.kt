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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // Batching (SEE-155). The gateway refuses a read naming more than thirty-two channels, and the
    // manager used to send every channel in one request — so an owner's thirty-third feed cost them
    // the presence of all the others, on that gateway, on every poll, for good.

    /** Thirty-two is the bound, so thirty-two is still one call. */
    @Test
    fun thirtyTwoFeedsAreStillOneRead() = runTest {
        feeds.value = manyFeeds(32)
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        assertEquals(1, gateway.asked.size)
        assertEquals(32, gateway.asked.single().second.size)
    }

    /**
     * And the thirty-third is a second call rather than the end of presence on that gateway. Every
     * feed gets its own answer, including the ones in the second batch.
     */
    @Test
    fun theThirtyThirdFeedIsAskedAboutInASecondBatch() = runTest {
        feeds.value = manyFeeds(33)
        (1..33).forEach { index ->
            gateway.answers[channelFor(serverIdOf(index))] =
                if (index % 2 == 0) FeedAvailability.Offline else FeedAvailability.Online
        }
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        assertEquals(listOf(32, 1), gateway.asked.map { it.second.size })
        // Nothing was truncated: the two batches together are exactly the feeds the owner holds.
        assertEquals((1..33).map { channelFor(serverIdOf(it)) }.toSet(), gateway.everAsked())
        // Mixed answers, each one its own feed's.
        (1..33).forEach { index ->
            val expected = if (index % 2 == 0) FeedAvailability.Offline else FeedAvailability.Online
            assertEquals(expected, status.state.value.availabilityOf("feed-$index"))
        }
    }

    /** Sixty-five is three batches, and the last one holds the single feed that overflowed. */
    @Test
    fun sixtyFiveFeedsAreThreeBatches() = runTest {
        feeds.value = manyFeeds(65)
        (1..65).forEach { gateway.answers[channelFor(serverIdOf(it))] = FeedAvailability.Online }
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        assertEquals(listOf(32, 32, 1), gateway.asked.map { it.second.size })
        assertEquals((1..65).map { channelFor(serverIdOf(it)) }.toSet(), gateway.everAsked())
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf("feed-65"))
    }

    /**
     * A batch that fails costs only its own feeds their fresh answer. The rest of the gateway is
     * published, and the failed batch's feeds keep what they had — because this phone was not told
     * those publishers had stopped, which is the same reason a whole failed gateway keeps its own.
     */
    @Test
    fun aFailedBatchKeepsOnlyItsOwnFeedsAsTheyWere() = runTest {
        feeds.value = manyFeeds(33)
        (1..33).forEach { gateway.answers[channelFor(serverIdOf(it))] = FeedAvailability.Online }
        val status = manager(backgroundScope)
        status.onForeground()
        runCurrent()
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf("feed-33"))

        // The overflow batch alone now fails, and every publisher has meanwhile stopped.
        gateway.failingChannels = setOf(channelFor(serverIdOf(33)))
        (1..33).forEach { gateway.answers[channelFor(serverIdOf(it))] = FeedAvailability.Offline }
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(FeedAvailability.Offline, status.state.value.availabilityOf("feed-1"))
        assertEquals(FeedAvailability.Offline, status.state.value.availabilityOf("feed-32"))
        // Not offline, and not unknown: unchanged.
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf("feed-33"))
    }

    /** A gateway needing several batches does not stop another gateway being read. */
    @Test
    fun batchingOneGatewayDoesNotStopAnother() = runTest {
        feeds.value = manyFeeds(33) + feed(SERVER_B, gateway = OTHER_GATEWAY)
        (1..33).forEach { gateway.answers[channelFor(serverIdOf(it))] = FeedAvailability.Online }
        gateway.answers[channelFor(SERVER_B)] = FeedAvailability.Offline
        gateway.failingChannels = setOf(channelFor(serverIdOf(1)))
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()

        // The first gateway's first batch failed; its second still landed, and so did the other
        // gateway's only one.
        assertEquals(FeedAvailability.Unknown, status.state.value.availabilityOf("feed-1"))
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf("feed-33"))
        assertEquals(FeedAvailability.Offline, status.state.value.availabilityOf(FEED_B))
    }

    // Ordering and removal, for the two reads that are now in flight at once (SEE-155).

    /**
     * A refresh and the periodic pass are two reads of one gateway, and nothing makes them land in
     * the order they were sent. The older answer is dropped rather than published — otherwise a
     * publisher that has just come back is shown as offline again by a read that predates it.
     */
    @Test
    fun anOlderAnswerDoesNotOverwriteANewerOne() = runTest {
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Offline
        // The poll's read is slow; the refresh sent after it is not.
        gateway.takes = { call -> if (call == 1) 5_000L else 0L }
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()
        // The gateway learns the publisher is back while the first read is still out.
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        status.refresh()
        runCurrent()
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))

        // Now the first read answers, carrying what the gateway said before.
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
    }

    /**
     * The same race, on the threads it actually happens on (SEE-155).
     *
     * The test above runs both reads on one test dispatcher, which never descheduled a read part
     * way through publishing its answer — and that is exactly where the hole was. On
     * [kotlinx.coroutines.Dispatchers.IO] the two reads are two threads: the older one checked its
     * stamp, was descheduled before writing, the newer one checked its own stamp and published, and
     * then the older one woke up and wrote its staler answer over the top. The stamp was read under
     * a lock the write was not.
     *
     * So the interleaving is arranged rather than hoped for. [whilePublishing] holds the older read
     * between claiming its channel and writing it, for as long as it takes the newer answer to
     * land. Under the fix the newer read cannot get in there — it waits for the lock, the hold
     * times out, and the answers land oldest first — so what is asserted is what the owner sees:
     * the newest answer, last.
     */
    @Test
    fun anOlderAnswerCannotOverwriteANewerOneOnAnotherThread() {
        val threads = Executors.newFixedThreadPool(6)
        val scope = CoroutineScope(SupervisorJob() + threads.asCoroutineDispatcher())
        try {
            val watching = CountDownLatch(1)
            val holding = CountDownLatch(1)
            val newerLanded = CountDownLatch(1)
            gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Offline
            val status =
                FeedStatusManager(
                    connections = feeds,
                    statuses = gateway,
                    sleep = { delay(it) },
                    jitter = { it },
                    ownerScope = scope,
                    whilePublishing = {
                        // Only the first read to get this far, which is the poll's and so the older
                        // one. It waits for the newer answer; a second is long enough for the newer
                        // read to publish if anything lets it, and the fix is that nothing does.
                        if (holding.count > 0) {
                            holding.countDown()
                            newerLanded.await(1, TimeUnit.SECONDS)
                        }
                    },
                )
            scope.launch {
                status.state.collect { state ->
                    watching.countDown()
                    if (state.availabilityOf(FEED_A) == FeedAvailability.Online) {
                        newerLanded.countDown()
                    }
                }
            }
            assertTrue("nothing was watching the state", watching.await(5, TimeUnit.SECONDS))

            status.onForeground()
            assertTrue(
                "the older read never reached its publication",
                holding.await(5, TimeUnit.SECONDS),
            )
            // The gateway has meanwhile learned the publisher is back, and the refresh reads that
            // while the older read is still inside its own publication.
            gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
            scope.launch { status.refresh() }

            assertTrue("the newer answer never landed", newerLanded.await(10, TimeUnit.SECONDS))
            // The older read is released the moment the newer answer lands, so this is the window
            // it would have overwritten it in.
            Thread.sleep(500)
            assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
        } finally {
            scope.cancel()
            threads.shutdownNow()
        }
    }

    /**
     * The other side of that: a newer read that got no answer at all is not newer than anything. If
     * it were, a refresh that failed fast would drop the periodic read still out — the only one
     * that heard from the gateway — and the row would wait another interval for the truth.
     */
    @Test
    fun aNewerReadThatFailedDoesNotDropAnOlderAnswer() = runTest {
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        // The poll's read is slow and will succeed; the refresh sent after it fails at once.
        gateway.takes = { call -> if (call == 1) 5_000L else 0L }
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()
        gateway.failing = GATEWAY
        status.refresh()
        runCurrent()
        gateway.failing = null

        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
    }

    /**
     * A read does not succeed or fail as a whole, so neither does its claim on a gateway (SEE-155).
     *
     * With a thirty-third feed one gateway is two batches, and a newer read can be answered for the
     * first thirty-two channels and refused for the thirty-third. It has heard nothing about that
     * channel — so an older read that did hear about it is still the best thing known, and there is
     * no reason for that one feed to sit stale. What the newer read did read stays its own.
     */
    @Test
    fun aNewerReadsFailedBatchLeavesItsChannelsToTheOlderRead() = runTest {
        feeds.value = manyFeeds(33)
        (1..33).forEach { gateway.answers[channelFor(serverIdOf(it))] = FeedAvailability.Offline }
        // The poll's first batch is slow, so its second is asked for after the refresh has been and
        // gone; the refresh's own batches are not.
        gateway.takes = { call -> if (call == 1) 5_000L else 0L }
        // And the overflow batch — feed thirty-three's, alone on it — is the one the gateway
        // refuses
        // while the refresh is reading.
        gateway.failingChannels = setOf(channelFor(serverIdOf(33)))
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()
        // Every publisher comes back while the poll's first batch is still out. The refresh hears
        // that for the first thirty-two channels and is refused for the thirty-third.
        (1..33).forEach { gateway.answers[channelFor(serverIdOf(it))] = FeedAvailability.Online }
        status.refresh()
        runCurrent()
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf("feed-1"))
        assertEquals(FeedAvailability.Unknown, status.state.value.availabilityOf("feed-33"))

        // Now the poll's batches finish. It was refused nothing, and its second batch is the only
        // thing anybody has heard about feed thirty-three.
        gateway.failingChannels = emptySet()
        advanceTimeBy(5_001)
        runCurrent()

        // The newer answer stands where the newer read actually had one: the poll read the first
        // thirty-two channels before the publishers came back and must not put them back to
        // offline.
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf("feed-1"))
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf("feed-32"))
        // And feed thirty-three is answered rather than kept stale by a read that never asked about
        // it.
        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf("feed-33"))
    }

    /** And a feed removed while a read was out is not written back in by that read's answer. */
    @Test
    fun aFeedRemovedWhileAReadIsInFlightIsNotReintroduced() = runTest {
        feeds.value = listOf(feed(SERVER_A), feed(SERVER_B))
        gateway.answers[channelFor(SERVER_A)] = FeedAvailability.Online
        gateway.answers[channelFor(SERVER_B)] = FeedAvailability.Online
        gateway.takes = { 5_000L }
        val status = manager(backgroundScope)

        status.onForeground()
        runCurrent()
        feeds.value = listOf(feed(SERVER_A))
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(FeedAvailability.Online, status.state.value.availabilityOf(FEED_A))
        assertEquals(FeedAvailability.Unknown, status.state.value.availabilityOf(FEED_B))
    }

    /** Two taps in a row are one read: the answer in flight is the answer the owner waits for. */
    @Test
    fun twoRefreshesInARowAreOneRead() = runTest {
        gateway.takes = { 5_000L }
        val status = manager(backgroundScope)

        status.refresh()
        status.refresh()
        runCurrent()

        assertEquals(1, gateway.asked.size)
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

        /**
         * Thrown by a call that names any of these channels, so a test can fail one batch of
         * several on a gateway that is otherwise answering (SEE-155).
         */
        var failingChannels: Set<String> = emptySet()

        /**
         * How long call number n takes, counting from one. It is what lets a test make one read
         * overtake another, which is the race a stamp exists for.
         */
        var takes: (Int) -> Long = { 0L }

        override suspend fun statuses(
            gatewayUrl: String,
            channels: List<String>,
        ): Map<String, FeedAvailability> {
            asked += gatewayUrl to channels
            // What the gateway held when it was asked, not when it answers: a slow call carries
            // the older truth, which is exactly the thing that must not overwrite a newer one.
            val held = answers.toMap()
            takes(asked.size).takeIf { it > 0 }?.let { delay(it) }
            fail?.let { throw it }
            if (gatewayUrl == failing) {
                throw GatewayException(GatewayException.Kind.Unreachable, "no route")
            }
            if (channels.any { it in failingChannels }) {
                throw GatewayException(GatewayException.Kind.Unreachable, "no route")
            }
            return channels.mapNotNull { channel -> held[channel]?.let { channel to it } }.toMap()
        }

        /** Every channel this gateway was ever asked about, over all the batches. */
        fun everAsked(gatewayUrl: String = GATEWAY): Set<String> =
            asked.filter { it.first == gatewayUrl }.flatMap { it.second }.toSet()
    }

    private companion object {
        const val GATEWAY = "https://feeds.example.com"
        const val OTHER_GATEWAY = "https://other.example.com"
        const val SERVER_A = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val FEED_A = "feed-a"
        const val FEED_B = "feed-b"

        /** A run of feeds on one gateway, for the batching sizes the gateway's bound is about. */
        fun manyFeeds(count: Int, gateway: String = GATEWAY): List<Connection> =
            (1..count).map { index -> feed(serverIdOf(index), gateway, id = "feed-$index") }

        /** A distinct, well-formed server id per index. */
        fun serverIdOf(index: Int): String = "3f1b2c4d-5e6f-4a7b-8c9d-%012d".format(index)

        fun feed(serverId: String, gateway: String = GATEWAY, id: String? = null) =
            Connection(
                id = id ?: if (serverId == SERVER_A) FEED_A else FEED_B,
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
