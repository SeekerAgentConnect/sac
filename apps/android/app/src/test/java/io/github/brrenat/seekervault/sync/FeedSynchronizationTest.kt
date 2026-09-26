package io.github.brrenat.seekervault.sync

import androidx.work.NetworkType
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.feeds.ForegroundFeedsState
import io.github.brrenat.seekervault.notifications.ProposalRef
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a feed hint actually causes (SEE-92).
 *
 * The hint itself carries nothing, so everything worth testing is here: which feeds are read, which
 * are left to the stream that already has them, what is remembered so the next read is cheap, and
 * what the owner is shown afterwards. A hint is a signal; this is the only thing that acts on one.
 */
class FeedSynchronizationTest {
    private class Read(
        val sequences: MutableMap<String, Long?> = mutableMapOf(),
        val failures: MutableSet<String> = mutableSetOf(),
    ) {
        val read = mutableListOf<Pair<String, Long>>()
        val remembered = mutableListOf<Pair<String, Long>>()
    }

    private fun runner(
        connections: List<Connection>,
        foreground: ForegroundFeedsState = ForegroundFeedsState(),
        state: Read = Read(),
        progress: Map<String, Long> = emptyMap(),
        reviewable: List<Set<ProposalRef>> = listOf(emptySet(), emptySet()),
        reconciled: MutableList<Pair<Set<ProposalRef>, Set<ProposalRef>>> = mutableListOf(),
        loaded: MutableList<Unit> = mutableListOf(),
    ): FeedSyncRunner {
        var reads = 0
        return FeedSyncRunner(
            load = { loaded += Unit },
            connections = { connections },
            foreground = { foreground },
            progress = { serverId -> progress[serverId] ?: 0L },
            read = { connectionId, known ->
                state.read += connectionId to known
                if (connectionId in state.failures) error("the gateway is not reachable")
                state.sequences.getOrDefault(connectionId, 7L)
            },
            remember = { serverId, sequence -> state.remembered += serverId to sequence },
            reviewable = { reviewable[minOf(reads++, reviewable.size - 1)] },
            reconcileNotifications = { before, after -> reconciled += before to after },
        )
    }

    @Test
    fun readsEveryFeedAndRemembersWhereEachOneGotTo() = runTest {
        val state = Read()
        val loaded = mutableListOf<Unit>()

        val decision =
            runner(
                    listOf(feed(SERVER_A), feed(SERVER_B)),
                    state = state,
                    progress = mapOf(SERVER_A to 4L),
                    loaded = loaded,
                )
                .run()

        assertEquals(BackgroundSyncDecision.Complete, decision)
        // What it holds is read from disk first: a process woken by a hint holds nothing in memory.
        assertEquals(1, loaded.size)
        // Each feed, with the boundary it was last read at, so an unchanged feed costs one answer.
        assertEquals(listOf("feed-$SERVER_A" to 4L, "feed-$SERVER_B" to 0L), state.read)
        assertEquals(listOf(SERVER_A to 7L, SERVER_B to 7L), state.remembered)
    }

    /** A phone with no feeds does nothing at all: a hint for a feed it removed is a no-op. */
    @Test
    fun aPhoneWithNoFeedsReadsNothing() = runTest {
        val state = Read()

        val decision = runner(listOf(direct()), state = state).run()

        assertEquals(BackgroundSyncDecision.Complete, decision)
        assertTrue(state.read.isEmpty())
    }

    /**
     * A feed whose gateway is streaming to a foreground listener is skipped. That listener has the
     * documents already, in order, and reading the same feed again would only spend the radio.
     */
    @Test
    fun aFeedThatIsAlreadyLiveIsLeftToItsStream() = runTest {
        val state = Read()

        val decision =
            runner(
                    listOf(feed(SERVER_A), feed(SERVER_B, OTHER_GATEWAY)),
                    foreground =
                        ForegroundFeedsState(
                            foreground = true,
                            gateways = mapOf(GATEWAY to FeedListenerState.Live(1)),
                        ),
                    state = state,
                )
                .run()

        assertEquals(BackgroundSyncDecision.Complete, decision)
        assertEquals(listOf("feed-$SERVER_B" to 0L), state.read)
    }

    /**
     * Only a live listener counts. A gateway that is reconnecting, unreachable or streamless has
     * nothing in flight for this feed, so the read is the thing that recovers it.
     */
    @Test
    fun aListenerThatIsNotLiveIsNotARead() = runTest {
        for (listener in
            listOf(
                FeedListenerState.Connecting,
                FeedListenerState.Reconnecting(2),
                FeedListenerState.NoStream,
                FeedListenerState.Refused(3501),
            )) {
            val state = Read()
            runner(
                    listOf(feed(SERVER_A)),
                    foreground =
                        ForegroundFeedsState(
                            foreground = true,
                            gateways = mapOf(GATEWAY to listener),
                        ),
                    state = state,
                )
                .run()
            assertEquals(listener.toString(), listOf("feed-$SERVER_A" to 0L), state.read)
        }
        // And the app being in the background is not a reason to skip anything either.
        val state = Read()
        runner(
                listOf(feed(SERVER_A)),
                foreground =
                    ForegroundFeedsState(
                        foreground = false,
                        gateways = mapOf(GATEWAY to FeedListenerState.Live(1)),
                    ),
                state = state,
            )
            .run()
        assertEquals(listOf("feed-$SERVER_A" to 0L), state.read)
    }

    /**
     * One feed's gateway being unreachable is not the others' problem: every feed is still read,
     * and the job is worth running again.
     */
    @Test
    fun oneUnreachableGatewayIsRetriedAndDoesNotStopTheOthers() = runTest {
        val state = Read(failures = mutableSetOf("feed-$SERVER_A"))

        val decision = runner(listOf(feed(SERVER_A), feed(SERVER_B)), state = state).run()

        assertEquals(BackgroundSyncDecision.Retry, decision)
        assertEquals(listOf("feed-$SERVER_A", "feed-$SERVER_B"), state.read.map { it.first })
        // Nothing is remembered for the feed that could not be read: a boundary that was never
        // reached would make the next read skip what it missed.
        assertEquals(listOf(SERVER_B to 7L), state.remembered)
    }

    /** A read that answered nothing is the same: it is retried, and nothing is remembered. */
    @Test
    fun aReadThatAnsweredNothingIsRetried() = runTest {
        val state = Read(sequences = mutableMapOf("feed-$SERVER_A" to null))

        val decision = runner(listOf(feed(SERVER_A)), state = state).run()

        assertEquals(BackgroundSyncDecision.Retry, decision)
        assertTrue(state.remembered.isEmpty())
    }

    /**
     * The owner is shown what the read found, not what the hint said: the reviewable proposals
     * before and after are what the notifications are reconciled from.
     */
    @Test
    fun theOwnerIsShownWhatTheReadFound() = runTest {
        val reconciled = mutableListOf<Pair<Set<ProposalRef>, Set<ProposalRef>>>()
        val before = setOf(ProposalRef("feed-$SERVER_A", PROPOSAL_ONE))
        val after = setOf(ProposalRef("feed-$SERVER_A", PROPOSAL_TWO))

        runner(
                listOf(feed(SERVER_A)),
                reviewable = listOf(before, after),
                reconciled = reconciled,
            )
            .run()

        assertEquals(listOf(before to after), reconciled)
    }

    /** Nothing is reconciled when nothing was read: a no-op must not cancel an owner's alert. */
    @Test
    fun nothingIsReconciledWhenNothingWasRead() = runTest {
        val reconciled = mutableListOf<Pair<Set<ProposalRef>, Set<ProposalRef>>>()

        runner(listOf(direct()), reconciled = reconciled).run()

        assertTrue(reconciled.isEmpty())
    }

    /**
     * The work itself: one job at a time, a network constraint, an empty input, and deliberately
     * not expedited — a feed is an offer to everyone subscribed, not one server waiting for this
     * owner's answer.
     */
    @Test
    fun theWorkCarriesNoInputAndAsksForNoQuota() {
        val request = FeedSyncScheduler.request()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.input.keyValueMap.isEmpty())
        assertFalse(request.workSpec.expedited)
        assertEquals(
            setOf(FeedSyncScheduler.WORK_TAG),
            request.tags - FeedSyncWorker::class.java.name,
        )
        // And it is a different job from the private path's, so neither replaces the other.
        assertFalse(FeedSyncScheduler.UNIQUE_WORK_NAME == PushSyncScheduler.UNIQUE_WORK_NAME)
    }

    private companion object {
        const val GATEWAY = "https://feeds.example.com"
        const val OTHER_GATEWAY = "https://other.example.com"
        const val SERVER_A = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val PROPOSAL_ONE = "11111111-2222-4333-8444-555555555551"
        const val PROPOSAL_TWO = "11111111-2222-4333-8444-555555555552"

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
