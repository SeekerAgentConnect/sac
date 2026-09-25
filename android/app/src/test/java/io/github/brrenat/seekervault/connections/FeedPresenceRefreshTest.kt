package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.feeds.FeedAvailability
import io.github.brrenat.seekervault.feeds.FeedStatusManager
import io.github.brrenat.seekervault.feeds.FeedStatuses
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.servers.feedManifest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A manual refresh of a feed asks whether its publisher is running (SEE-155).
 *
 * The manager could already be asked for a fresh read, and had a unit test saying so, but nothing
 * in the app ever asked it: the refresh action fetched the connection and stopped there. So an
 * owner whose publisher had just come back could pull to refresh, watch the fetch succeed, and
 * still read "Feed offline" until the periodic poll came round half a minute later — which is the
 * one situation a person reaches for refresh in.
 *
 * These tests therefore go through the real [ConnectionsViewModel] action and a real
 * [FeedStatusManager], with only the gateway faked. Nothing here advances the clock except the test
 * that is specifically about the timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FeedPresenceRefreshTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val feedGateway = FakeFeedManifests()
    private val statuses = FakeStatuses()
    private val key = softwareKey()

    private val repository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "connections")),
            vault = CredentialVault(File(folder.root, "credentials")) { key },
            results = ResultStore(File(folder.root, "results")),
            gateway = gateway,
            feeds = feedGateway,
            deviceName = "Seeker",
            io = Dispatchers.Unconfined,
        )
    }

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    private fun presence(scope: CoroutineScope) =
        FeedStatusManager(
            connections = repository.connections,
            statuses = statuses,
            sleep = { delay(it) },
            jitter = { it },
            ownerScope = scope,
        )

    private fun viewModel(manager: FeedStatusManager) =
        ConnectionsViewModel(
            repository,
            foregroundFeedStatus = manager.state,
            refreshFeedStatus = manager::refresh,
            cleartextPermitted = { false },
        )

    /** The acceptance the finding asked for: refresh, and the row is right, with no timer. */
    @Test
    fun refreshingAFeedReadsItsPresenceAtOnce() = runTest {
        feedGateway.answer = feedManifest(name = "Copy trading")
        val feed =
            (repository.addFeed(FeedReference(GATEWAY, SERVER_B)) as FeedOutcome.Added).connection
        statuses.answers[channelFor(SERVER_B)] = FeedAvailability.Offline
        val manager = presence(backgroundScope)
        val viewModel = viewModel(manager)
        manager.onForeground()
        runCurrent()
        assertEquals(
            FeedAvailability.Offline,
            viewModel.state.value.feedStatus.availabilityOf(feed.id),
        )
        val readsSoFar = statuses.asked.size

        // The publisher comes back, and the owner pulls to refresh rather than waiting.
        statuses.answers[channelFor(SERVER_B)] = FeedAvailability.Online
        viewModel.refresh(feed.id)
        runCurrent()

        assertEquals(
            FeedAvailability.Online,
            viewModel.state.value.feedStatus.availabilityOf(feed.id),
        )
        // Exactly one extra read, and it was not the periodic one: no virtual time passed.
        assertEquals(readsSoFar + 1, statuses.asked.size)
    }

    /**
     * And the timer is untouched by it. The next periodic read still lands an interval after the
     * pass it belongs to, so refreshing does not quietly double the polling rate.
     */
    @Test
    fun refreshingDoesNotAdvanceOrResetThePeriodicRead() = runTest {
        feedGateway.answer = feedManifest(name = "Copy trading")
        repository.addFeed(FeedReference(GATEWAY, SERVER_B))
        statuses.answers[channelFor(SERVER_B)] = FeedAvailability.Online
        val manager = presence(backgroundScope)
        val viewModel = viewModel(manager)
        manager.onForeground()
        runCurrent()

        viewModel.refresh(repository.connections.value.single().id)
        runCurrent()
        assertEquals(2, statuses.asked.size)
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(3, statuses.asked.size)
    }

    /** A direct connection has no publisher behind it, so its refresh asks nobody about one. */
    @Test
    fun refreshingADirectConnectionAsksNothingAboutPresence() = runTest {
        val connection = repository.pair(server.issue(URL))
        val manager = presence(backgroundScope)
        val viewModel = viewModel(manager)

        viewModel.refresh(connection.id)
        runCurrent()

        assertEquals(emptyList<Pair<String, List<String>>>(), statuses.asked)
    }

    /**
     * Opening the app, and returning to it, fetch every connection — but presence is one read of
     * each gateway, asked for once by the manager's own foreground pass. Asking again per feed
     * would send the same read as many times as the owner has feeds.
     */
    @Test
    fun openingTheAppDoesNotAskForPresenceOncePerFeed() = runTest {
        feedGateway.answer = feedManifest(name = "Copy trading")
        repository.addFeed(FeedReference(GATEWAY, SERVER_B))
        val manager = presence(backgroundScope)
        val viewModel = viewModel(manager)
        runCurrent()
        assertEquals(emptyList<Pair<String, List<String>>>(), statuses.asked)

        viewModel.onAppHidden()
        viewModel.onAppVisible()
        runCurrent()

        assertEquals(emptyList<Pair<String, List<String>>>(), statuses.asked)
    }

    /** A gateway that answers whatever a test set, and records what it was asked. */
    private class FakeStatuses : FeedStatuses {
        val asked = mutableListOf<Pair<String, List<String>>>()
        val answers = mutableMapOf<String, FeedAvailability>()

        override suspend fun statuses(
            gatewayUrl: String,
            channels: List<String>,
        ): Map<String, FeedAvailability> {
            asked += gatewayUrl to channels
            return channels
                .mapNotNull { channel -> answers[channel]?.let { channel to it } }
                .toMap()
        }
    }

    /** Only enough of a feed gateway to add a feed to the store. */
    private class FakeFeedManifests : FeedGateway {
        var answer: WireManifest? = null

        override suspend fun resolve(reference: FeedReference, knownRevision: Long): FeedManifest =
            FeedManifest.Held(checkNotNull(answer) { "no manifest was published" })
    }

    private companion object {
        const val URL = "https://vault.example.com"
    }
}
