package io.github.brrenat.seekervault.feeds

import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.FeedDelivery
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.feeds.storage.FeedCursorStore
import io.github.brrenat.seekervault.gateway.v1.FeedEvent
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.channelFor
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The listener's own behaviour: what it opens, what it reads, when it comes back, and when it stops
 * (SEE-91).
 *
 * Nothing here touches a network. The transport is a fake that hands out the pushes a test chooses,
 * the gateway is a fake that grants what a test says it hosts, and the repositories are a recorder
 * — so what these tests are about is the one thing this class decides: whether a phone can end up
 * believing a feed is current when it is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ForegroundFeedManagerTest {
    @get:Rule val folder = TemporaryFolder()

    private val feeds = MutableStateFlow(listOf(feed(SERVER_A)))
    private val host = Recorder()
    private val tickets = FakeTickets()
    private val stream = FakeStream()
    private val cursors by lazy { FeedCursorStore(File(folder.root, "feeds")) }

    private fun manager(scope: kotlinx.coroutines.CoroutineScope) =
        ForegroundFeedManager(
            connections = feeds,
            host = host,
            tickets = tickets,
            stream = stream,
            cursors = cursors,
            sleep = { kotlinx.coroutines.delay(it) },
            // No jitter, so the backoff in these tests is the number the policy chose.
            jitter = { it },
            ownerScope = scope,
        )

    // ---------------------------------------------------------------- opening

    @Test
    fun aFeedIsListenedToWithTheChannelTheGatewayGrantedForIt() = runTest {
        val listener = manager(backgroundScope)

        listener.onForeground()
        runCurrent()
        stream.open(recovered = false)
        runCurrent()

        // One ticket, for this feed's channel, and nothing else went out: a subscription says which
        // channel someone is interested in and that is all a gateway learns.
        assertEquals(listOf(GATEWAY to listOf(channelFor(SERVER_A))), tickets.asked)
        assertEquals(TICKET, stream.openings.single().ticket)
        assertEquals(GATEWAY, stream.openings.single().gatewayUrl)
        assertEquals(
            FeedListenerState.Live(1),
            listener.state.value.gateways.getValue(GATEWAY),
        )
    }

    /**
     * The broker could not prove it replayed everything, so the authoritative feed is read — and
     * the position it reported is kept before the read, so a publication that lands during the walk
     * arrives on the stream and is applied there.
     */
    @Test
    fun withoutProvenContinuityTheSnapshotIsRead() = runTest {
        manager(backgroundScope).onForeground()
        runCurrent()

        stream.open(recovered = false, offset = 44)
        runCurrent()

        assertEquals(listOf(FEED_A to 0L), host.read)
        assertEquals(FeedCursor(EPOCH, 44), cursors.get(SERVER_A)?.cursor)
        assertEquals(SEQUENCE, cursors.get(SERVER_A)?.sequence)
    }

    /** The broker replayed what was missed, so there is nothing to read from the gateway. */
    @Test
    fun withProvenContinuityNothingIsReadFromTheGateway() = runTest {
        cursors.put(FeedCursorStore.Progress(SERVER_A, FeedCursor(EPOCH, 41), sequence = 5))
        manager(backgroundScope).onForeground()
        runCurrent()

        stream.open(recovered = true)
        stream.publish(offset = 42, revision = 6)
        runCurrent()

        assertEquals(emptyList<Pair<String, Long>>(), host.read)
        // The document was applied, and the cursor moved to where it was applied from.
        assertEquals(listOf(FEED_A to 6L), host.proposals)
        assertEquals(FeedCursor(EPOCH, 42), cursors.get(SERVER_A)?.cursor)
        // The snapshot boundary is untouched: only a completed walk moves it, because only a walk
        // knows what it covered.
        assertEquals(5L, cursors.get(SERVER_A)?.sequence)
    }

    /**
     * History the broker replays when the stream opens is applied like anything else, and said to
     * be a replay: catching up is not news for the foreground banner (SEE-175). What is published
     * while the stream is open is.
     */
    @Test
    fun replayedHistoryIsAppliedAsAReplayAndWhatFollowsAsLive() = runTest {
        cursors.put(FeedCursorStore.Progress(SERVER_A, FeedCursor(EPOCH, 41), sequence = 5))
        manager(backgroundScope).onForeground()
        runCurrent()

        stream.open(recovered = true)
        stream.publish(offset = 42, revision = 6, replayed = true)
        stream.publish(offset = 43, revision = 7)
        runCurrent()

        assertEquals(listOf(FEED_A to 6L, FEED_A to 7L), host.proposals)
        assertEquals(listOf(FeedDelivery.Replayed, FeedDelivery.Live), host.deliveries)
    }

    @Test
    fun aRecoveredCursorIsWhatTheNextConnectionAsksToResumeFrom() = runTest {
        cursors.put(FeedCursorStore.Progress(SERVER_A, FeedCursor(EPOCH, 41), sequence = 5))
        manager(backgroundScope).onForeground()
        runCurrent()

        stream.open(recovered = true)
        stream.publish(offset = 42, revision = 6)
        runCurrent()
        stream.close(3001) // a node shutting down
        advanceTimeBy(2_000)
        runCurrent()
        runCurrent()

        assertEquals(2, stream.openings.size)
        assertEquals(mapOf(CHANNEL_A to FeedCursor(EPOCH, 42)), stream.openings[1].resume)
        // The same ticket: the broker said nothing about the grant, so asking for another would be
        // a poll against the gateway beside it.
        assertEquals(1, tickets.asked.size)
    }

    // ------------------------------------------------------------- publishing

    @Test
    fun settingsAndProposalsGoToTheirOwnPathsAndAnUnknownDocumentIsRead() = runTest {
        cursors.put(FeedCursorStore.Progress(SERVER_A, FeedCursor(EPOCH, 1), sequence = 5))
        manager(backgroundScope).onForeground()
        runCurrent()
        stream.open(recovered = true)
        runCurrent()
        host.read.clear()

        stream.publish(offset = 2, revision = 4)
        stream.deliver(
            FeedStreamEvent.Published(
                CHANNEL_A,
                3,
                FeedEvent.newBuilder()
                    .setSequence(9)
                    .setManifest(WireManifest.newBuilder().setSettingsRevision(12).build())
                    .build(),
            )
        )
        // An envelope from a later protocol: no document this version knows, which still means
        // something changed.
        stream.deliver(
            FeedStreamEvent.Published(CHANNEL_A, 4, FeedEvent.newBuilder().setSequence(10).build())
        )
        runCurrent()

        assertEquals(listOf(FEED_A to 4L), host.proposals)
        assertEquals(listOf(FEED_A to 12L), host.settings)
        assertEquals(listOf(FEED_A to 5L), host.read)
    }

    /**
     * Bytes the phone cannot parse are not silence. The channel moved, and the snapshot is how this
     * version finds out what happened.
     */
    @Test
    fun anUnreadablePublicationSendsTheListenerToTheSnapshot() = runTest {
        cursors.put(FeedCursorStore.Progress(SERVER_A, FeedCursor(EPOCH, 1), sequence = 5))
        manager(backgroundScope).onForeground()
        runCurrent()
        stream.open(recovered = true)
        runCurrent()
        host.read.clear()

        stream.deliver(FeedStreamEvent.Unreadable(CHANNEL_A, 5))
        runCurrent()

        assertEquals(listOf(FEED_A to 5L), host.read)
    }

    // ---------------------------------------------------------- coming back

    @Test
    fun aShutdownIsComeBackFromAndATerminalCodeStops() = runTest {
        val listener = manager(backgroundScope)
        listener.onForeground()
        runCurrent()
        stream.open(recovered = false)
        runCurrent()

        stream.close(3001)
        advanceTimeBy(2_000)
        runCurrent()
        runCurrent()
        assertEquals(2, stream.openings.size)

        stream.close(3505) // the broker's channel limit: something about this listener is wrong
        advanceTimeBy(60_000)
        runCurrent()
        runCurrent()
        assertEquals(2, stream.openings.size)
        assertEquals(
            FeedListenerState.Refused(3505),
            listener.state.value.gateways.getValue(GATEWAY),
        )
    }

    @Test
    fun anExpiredGrantIsReplacedRatherThanRetried() = runTest {
        manager(backgroundScope).onForeground()
        runCurrent()
        stream.open(recovered = false)
        runCurrent()

        stream.close(3005) // connection expired
        advanceTimeBy(2_000)
        runCurrent()
        runCurrent()

        assertEquals(2, tickets.asked.size)
        assertEquals(2, stream.openings.size)
    }

    @Test
    fun aDroppedSubscriptionReopensTheStreamBecauseItCannotBeResubscribed() = runTest {
        manager(backgroundScope).onForeground()
        runCurrent()
        stream.open(recovered = true)
        runCurrent()

        stream.deliver(FeedStreamEvent.Dropped(CHANNEL_A, 2500, "insufficient state"))
        advanceTimeBy(2_000)
        runCurrent()
        runCurrent()

        assertEquals(2, stream.openings.size)
    }

    @Test
    fun aGatewayThatIsNotThereIsRetriedWithGrowingPatience() = runTest {
        val listener = manager(backgroundScope)
        tickets.failure = GatewayException.Kind.Unreachable
        listener.onForeground()
        runCurrent()

        assertEquals(
            FeedListenerState.Unreachable(CheckOutcome.Unreachable),
            listener.state.value.gateways.getValue(GATEWAY),
        )
        val first = tickets.asked.size
        advanceTimeBy(1_100)
        runCurrent()
        runCurrent()
        assertTrue("it tried again", tickets.asked.size > first)
        // Growing: the second wait is twice the first, so a gateway that is down is not polled.
        val second = tickets.asked.size
        advanceTimeBy(1_100)
        runCurrent()
        runCurrent()
        assertEquals("not yet", second, tickets.asked.size)
        advanceTimeBy(1_000)
        runCurrent()
        runCurrent()
        assertTrue(tickets.asked.size > second)
    }

    /**
     * A gateway with no broker is a working deployment: it holds the documents and answers reads.
     * The listener says so once and stops, rather than asking again for ever.
     */
    @Test
    fun aGatewayWithNoStreamIsNotAFailureAndIsNotRetried() = runTest {
        val listener = manager(backgroundScope)
        tickets.failure = GatewayException.Kind.Unimplemented
        listener.onForeground()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        runCurrent()

        assertEquals(FeedListenerState.NoStream, listener.state.value.gateways.getValue(GATEWAY))
        assertEquals(1, tickets.asked.size)
        assertTrue(stream.openings.isEmpty())
    }

    // ------------------------------------------------------- what the owner does

    @Test
    fun aFeedAddedWhileForegroundOpensItsStreamAndReadsItsFirstSnapshot() = runTest {
        feeds.value = emptyList()
        val listener = manager(backgroundScope)
        listener.onForeground()
        runCurrent()

        // This is the repository flow changing after onboarding. No app restart or foreground
        // transition gives the manager another nudge.
        feeds.value = listOf(feed(SERVER_A))
        advanceTimeBy(2_000)
        runCurrent()
        stream.open(recovered = false)
        runCurrent()

        assertEquals(listOf(FEED_A to 0L), host.read)
        assertEquals(listOf(GATEWAY to listOf(channelFor(SERVER_A))), tickets.asked)
        assertEquals(FeedListenerState.Live(1), listener.state.value.gateways[GATEWAY])
    }

    @Test
    fun addingAFeedReopensTheStreamOnceWithBothChannels() = runTest {
        manager(backgroundScope).onForeground()
        runCurrent()
        stream.open(recovered = false)
        runCurrent()

        feeds.value = listOf(feed(SERVER_A), feed(SERVER_B))
        runCurrent()
        // Debounced: adding two in a row is one reconnect, because the channels are fixed by the
        // ticket a stream was opened with and there is no way to add one to a stream that is open.
        feeds.value = listOf(feed(SERVER_A), feed(SERVER_B), feed(SERVER_C))
        advanceTimeBy(2_000)
        runCurrent()
        runCurrent()

        assertEquals(2, stream.openings.size)
        assertEquals(
            listOf(channelFor(SERVER_A), channelFor(SERVER_B), channelFor(SERVER_C)),
            tickets.asked.last().second,
        )
    }

    /** Removing a feed stops its stream. Nothing else on that gateway is disturbed. */
    @Test
    fun removingTheLastFeedStopsListeningAltogether() = runTest {
        val listener = manager(backgroundScope)
        listener.onForeground()
        runCurrent()
        stream.open(recovered = false)
        runCurrent()

        feeds.value = emptyList()
        advanceTimeBy(2_000)
        runCurrent()
        runCurrent()

        assertTrue(listener.state.value.gateways.isEmpty())
        assertEquals(1, stream.openings.size)
    }

    @Test
    fun twoGatewaysAreListenedToIndependently() = runTest {
        feeds.value = listOf(feed(SERVER_A), feed(SERVER_B, gateway = OTHER_GATEWAY))
        val listener = manager(backgroundScope)

        listener.onForeground()
        runCurrent()
        stream.open(recovered = false, opening = 0)
        stream.open(recovered = false, opening = 1)
        runCurrent()

        assertEquals(setOf(GATEWAY, OTHER_GATEWAY), listener.state.value.gateways.keys)
        assertEquals(2, stream.openings.size)
        // One ticket each, each naming only its own gateway's channel.
        assertEquals(
            setOf(
                GATEWAY to listOf(channelFor(SERVER_A)),
                OTHER_GATEWAY to listOf(channelFor(SERVER_B)),
            ),
            tickets.asked.toSet(),
        )
    }

    // ----------------------------------------------------------- the lifecycle

    @Test
    fun goingAwayClosesTheStreamAndComingBackOpensOneAgain() = runTest {
        val listener = manager(backgroundScope)
        listener.onForeground()
        runCurrent()
        stream.open(recovered = false)
        runCurrent()

        listener.onBackground()
        runCurrent()
        assertTrue(listener.state.value.gateways.isEmpty())
        assertEquals(false, listener.state.value.foreground)
        assertTrue("the stream was closed", stream.closed.contains(0))

        listener.onForeground()
        runCurrent()
        assertEquals(2, stream.openings.size)
        assertEquals(true, listener.state.value.foreground)
    }

    /** A rotation recreates the activity and must not open a second stream. */
    @Test
    fun comingToTheForegroundTwiceKeepsOneStream() = runTest {
        val listener = manager(backgroundScope)
        listener.onForeground()
        runCurrent()
        listener.onForeground()
        runCurrent()

        assertEquals(1, stream.openings.size)
    }

    @Test
    fun nothingIsListenedToWithNoFeeds() = runTest {
        feeds.value = emptyList()
        val listener = manager(backgroundScope)

        listener.onForeground()
        runCurrent()

        assertTrue(tickets.asked.isEmpty())
        assertTrue(stream.openings.isEmpty())
        assertNull(listener.state.value.gateways[GATEWAY])
    }

    // ------------------------------------------------------------------ fakes

    private class Recorder : FeedHost {
        val proposals = mutableListOf<Pair<String, Long>>()
        val deliveries = mutableListOf<FeedDelivery>()
        val settings = mutableListOf<Pair<String, Long>>()
        val read = mutableListOf<Pair<String, Long>>()

        override suspend fun applyProposal(
            connectionId: String,
            message: WireProposal,
            delivery: FeedDelivery,
        ) {
            proposals += connectionId to message.revision
            deliveries += delivery
        }

        override suspend fun applySettings(connectionId: String, message: WireManifest) {
            settings += connectionId to message.settingsRevision
        }

        override suspend fun readFeed(connectionId: String, knownSequence: Long): Long {
            read += connectionId to knownSequence
            return SEQUENCE
        }
    }

    private class FakeTickets : FeedTickets {
        val asked = mutableListOf<Pair<String, List<String>>>()
        var failure: GatewayException.Kind? = null

        override suspend fun ticket(gatewayUrl: String, channels: List<String>): FeedGrant {
            asked += gatewayUrl to channels
            failure?.let { throw GatewayException(it, "fake $it") }
            return FeedGrant(
                ticket = TICKET,
                channels = channels.map { GrantedChannel(it, "feed:$it") },
                lifetime = 1.hours,
            )
        }
    }

    /** A transport a test drives: one channel of events per stream it was asked to open. */
    private class FakeStream : FeedStream {
        data class Opening(
            val gatewayUrl: String,
            val ticket: String,
            val resume: Map<String, FeedCursor>,
        )

        val openings = mutableListOf<Opening>()
        val closed = mutableListOf<Int>()
        private val events = mutableListOf<Channel<FeedStreamEvent>>()

        override fun listen(
            gatewayUrl: String,
            ticket: String,
            resume: Map<String, FeedCursor>,
        ): Flow<FeedStreamEvent> = flow {
            val index = openings.size
            openings += Opening(gatewayUrl, ticket, resume)
            val channel = Channel<FeedStreamEvent>(Channel.UNLIMITED)
            events += channel
            try {
                for (event in channel) emit(event)
            } finally {
                closed += index
            }
        }

        suspend fun deliver(event: FeedStreamEvent, opening: Int = events.lastIndex) {
            events[opening].send(event)
        }

        suspend fun open(
            recovered: Boolean,
            offset: Long = 44,
            opening: Int = events.lastIndex,
        ) {
            val channel = openings[opening].resume.keys.firstOrNull() ?: CHANNEL_A
            deliver(
                FeedStreamEvent.Opened(
                    mapOf(
                        channel to
                            FeedSubscription(
                                epoch = EPOCH,
                                offset = offset,
                                recoverable = true,
                                recovered = recovered,
                                wasRecovering = recovered,
                            )
                    )
                ),
                opening,
            )
        }

        suspend fun publish(
            offset: Long,
            revision: Long,
            opening: Int = events.lastIndex,
            replayed: Boolean = false,
        ) {
            deliver(
                FeedStreamEvent.Published(
                    CHANNEL_A,
                    offset,
                    FeedEvent.newBuilder()
                        .setSequence(offset)
                        .setProposal(WireProposal.newBuilder().setRevision(revision).build())
                        .build(),
                    replayed,
                ),
                opening,
            )
        }

        suspend fun close(code: Int, opening: Int = events.lastIndex) {
            deliver(FeedStreamEvent.Closed(code, "test"), opening)
        }
    }

    private companion object {
        const val GATEWAY = "https://feeds.example.com"
        const val OTHER_GATEWAY = "https://other.example.com"
        const val SERVER_A = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val SERVER_C = "11111111-2222-4333-8444-555555555555"
        const val FEED_A = "feed-a"
        const val TICKET = "a-ticket"
        const val EPOCH = "epoch-1"
        const val SEQUENCE = 12L
        val CHANNEL_A = "feed:${channelFor(SERVER_A)}"

        fun feed(serverId: String, gateway: String = GATEWAY) =
            Connection(
                id = if (serverId == SERVER_A) FEED_A else "feed-$serverId",
                label = "A feed",
                serverUrl = gateway,
                serverId = serverId,
                deviceName = "",
                pairedAt = Instant.parse("2026-09-17T09:00:00Z"),
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server = ServerRecord.Known(manifest(serverId, gateway)),
            )

        fun manifest(serverId: String, gateway: String) =
            io.github.brrenat.seekervault.servers.ServerManifest(
                serverId = serverId,
                protocolVersion = 1,
                settingsRevision = 1,
                mode = ConnectionMode.GatewayFeed,
                reference =
                    io.github.brrenat.seekervault.servers.ServerReference.Feed(
                        gatewayUrl = gateway,
                        channel = channelFor(serverId),
                    ),
                environments =
                    setOf(io.github.brrenat.seekervault.plugins.PluginEnvironment.Production),
            )
    }
}
