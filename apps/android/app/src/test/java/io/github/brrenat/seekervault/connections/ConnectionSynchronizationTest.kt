package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.notifications.ArrivalLedger
import io.github.brrenat.seekervault.sync.ConnectionSyncState
import io.github.brrenat.seekervault.sync.ServerRequest
import io.github.brrenat.seekervault.sync.SyncDelivery
import io.github.brrenat.seekervault.sync.UpdateEndpoint
import io.github.brrenat.seekervault.sync.UpdateTransport
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import io.github.brrenat.seekervault.update.v1.SyncedRequest
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionSynchronizationTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val transport = SnapshotTransport()
    private val key = softwareKey()
    private val history by lazy {
        ActivityLog(ActivityStore(File(folder.root, "files/activity"))) { NOW }
    }

    private fun newRepository(log: ActivityLog = history, arrivals: ArrivalLedger? = null) =
        ConnectionRepository(
            ConnectionStore(File(folder.root, "files/connections")),
            CredentialVault(File(folder.root, "no_backup/credentials")) { key },
            ResultStore(File(folder.root, "files/results")),
            gateway,
            log,
            deviceName = "Seeker",
            now = { NOW },
            io = Dispatchers.Unconfined,
            syncStore = SyncStore(File(folder.root, "files/sync")),
            updateTransport = transport,
            arrivals = arrivals,
        )

    private val repository by lazy { newRepository() }

    @Test
    fun manualRefreshUsesSharedSyncAndPublishesItsDurablePendingCache() = runBlocking {
        val connection = repository.pair(server.issue(URL))
        val pending = server.addPending(connection.id, REQUEST)
        transport.response = { snapshot(it.connectionId, pending) }

        repository.refresh(connection.id)

        assertEquals(
            listOf(REQUEST),
            repository.inbox.value.pending[connection.id]?.map { it.ref.requestId },
        )
        assertEquals(1, transport.calls)
        // The pending list came from the snapshot: the shared sync path replaces ListPending, and
        // reading what the server says about itself (SEE-88) is not a fetch of requests.
        assertEquals(0, server.lists)
        assertEquals(CheckOutcome.Ok, repository.connection(connection.id)?.lastCheck?.outcome)
    }

    @Test
    fun syncRetriesOnlyAResultTheOwnerAlreadyRecordedAndReconcilesActivity() = runBlocking {
        val connection = repository.pair(server.issue(URL))
        val pending = server.addPending(connection.id, REQUEST)
        transport.response = { snapshot(it.connectionId, pending) }
        repository.refresh(connection.id)

        server.failure = GatewayException.Kind.Unreachable
        val waiting = repository.answer(RequestKey(connection.id, REQUEST), Answer.Acknowledge)
        assertEquals(Delivery.Waiting, waiting.delivery)
        assertEquals(ActivityOutcome.Waiting, history.records.value.single().outcome)

        server.failure = null
        transport.response = { request ->
            snapshot(
                request.connectionId,
                checkNotNull(server.settled[request.connectionId]?.get(REQUEST)),
                revision = 2,
            )
        }
        repository.refresh(connection.id)

        assertEquals(1, gateway.submits.size)
        assertEquals(Delivery.Accepted, repository.inbox.value.results.single().delivery)
        assertEquals(ActivityOutcome.Acknowledged, history.records.value.single().outcome)
    }

    @Test
    fun workerOnlyEntryLoadsConnectionsAndCredentialsAfterProcessDeath() = runBlocking {
        val connection = repository.pair(server.issue(URL))
        val pending = server.addPending(connection.id, REQUEST)
        transport.response = { snapshot(it.connectionId, pending) }

        val restarted =
            newRepository(ActivityLog(ActivityStore(File(folder.root, "files/activity"))) { NOW })
        val outcomes = restarted.synchronizeAll()

        assertTrue(
            outcomes.getValue(connection.id)
                is io.github.brrenat.seekervault.sync.SynchronizeOutcome.Updated
        )
        assertEquals(
            listOf(REQUEST),
            restarted.inbox.value.pending[connection.id]?.map { it.ref.requestId },
        )
    }

    /**
     * A newly paired server's first synchronization is its backlog; only a request a later one
     * brings, or a stream event creates, reached the phone as news (SEE-175).
     */
    @Test
    fun onlyRequestsCreatedAfterTheFirstReadOfTheSessionAreMarkedAsNews() = runBlocking {
        val arrivals = ArrivalLedger().apply { onForeground() }
        val repository = newRepository(arrivals = arrivals)
        val connection = repository.pair(server.issue(URL))
        val first = server.addPending(connection.id, REQUEST)
        transport.response = { snapshot(it.connectionId, first) }

        repository.refresh(connection.id)
        assertEquals(emptySet<ReviewIdentity>(), arrivals.live.value)
        assertTrue(arrivals.wasRead(connection.id))

        val second = server.addPending(connection.id, OTHER_REQUEST)
        transport.response = { snapshot(it.connectionId, first, second) }
        repository.refresh(connection.id)
        // The one it already showed is not an arrival for being delivered again.
        assertEquals(
            setOf(ReviewIdentity.Private(connection.id, OTHER_REQUEST)),
            arrivals.live.value,
        )

        // After the app has been away, the first read is catching up again.
        arrivals.onBackground()
        arrivals.onForeground()
        val third = server.addPending(connection.id, THIRD_REQUEST)
        transport.response = { snapshot(it.connectionId, first, second, third) }
        repository.refresh(connection.id)
        assertEquals(emptySet<ReviewIdentity>(), arrivals.live.value)
        assertEquals(3, repository.inbox.value.pending[connection.id]?.size)
    }

    @Test
    fun aStreamEventIsNewsAndACachedStateNever() = runBlocking {
        val arrivals = ArrivalLedger().apply { onForeground() }
        val repository = newRepository(arrivals = arrivals)
        val connection = repository.pair(server.issue(URL))
        val request = server.addPending(connection.id, REQUEST)
        val state =
            ConnectionSyncState(
                connectionId = connection.id,
                requests =
                    mapOf(
                        REQUEST to
                            ServerRequest(RequestKey(connection.id, REQUEST), 1, request = request)
                    ),
            )

        repository.applyCache(state, SyncDelivery.Cached)
        assertEquals(emptySet<ReviewIdentity>(), arrivals.live.value)

        val other = server.addPending(connection.id, OTHER_REQUEST)
        repository.applyCache(
            state.copy(
                requests =
                    state.requests +
                        (OTHER_REQUEST to
                            ServerRequest(
                                RequestKey(connection.id, OTHER_REQUEST),
                                1,
                                request = other,
                            ))
            ),
            SyncDelivery.Event,
        )
        assertEquals(
            setOf(ReviewIdentity.Private(connection.id, OTHER_REQUEST)),
            arrivals.live.value,
        )
    }

    @Test
    fun whatEventsCreatedWhileTheFirstSnapshotWasReadIsNews() = runBlocking {
        val arrivals = ArrivalLedger().apply { onForeground() }
        val repository = newRepository(arrivals = arrivals)
        val connection = repository.pair(server.issue(URL))
        val backlog = server.addPending(connection.id, REQUEST)
        val live = server.addPending(connection.id, OTHER_REQUEST)
        val state =
            ConnectionSyncState(
                connectionId = connection.id,
                requests =
                    listOf(backlog, live).associate {
                        it.ref.requestId to
                            ServerRequest(
                                RequestKey(connection.id, it.ref.requestId),
                                1,
                                request = it,
                            )
                    },
            )

        repository.applyCache(state, SyncDelivery.Snapshot(live = setOf(OTHER_REQUEST)))

        assertEquals(
            setOf(ReviewIdentity.Private(connection.id, OTHER_REQUEST)),
            arrivals.live.value,
        )
        assertEquals(2, repository.inbox.value.pending[connection.id]?.size)
    }

    /**
     * The app's repositories are shared with the background workers. A read that completes while
     * the app is away must not make the first foreground read after it news (SEE-175 review).
     */
    @Test
    fun aWorkerReadWhileTheAppIsAwayLeavesTheNextCatchUpQuiet() = runBlocking {
        val arrivals = ArrivalLedger().apply { onForeground() }
        val repository = newRepository(arrivals = arrivals)
        val connection = repository.pair(server.issue(URL))
        val first = server.addPending(connection.id, REQUEST)
        transport.response = { snapshot(it.connectionId, first) }
        repository.refresh(connection.id)

        arrivals.onBackground()
        // The worker's read, while the app is away.
        repository.refresh(connection.id)
        assertFalse(arrivals.wasRead(connection.id))
        // Published while still away.
        val second = server.addPending(connection.id, OTHER_REQUEST)
        val third = server.addPending(connection.id, THIRD_REQUEST)
        transport.response = { snapshot(it.connectionId, first, second, third) }

        // Back in the foreground: the first read is catching up.
        arrivals.onForeground()
        repository.refresh(connection.id)

        assertEquals(emptySet<ReviewIdentity>(), arrivals.live.value)
        assertEquals(3, repository.inbox.value.pending[connection.id]?.size)
        assertTrue(arrivals.wasRead(connection.id))
    }

    /**
     * A request the first snapshot caught as backlog, whose live event then arrives identical, was
     * created while the snapshot was read: it is handed over late. Nothing else is (SEE-175
     * review).
     */
    @Test
    fun aRequestTheFirstSnapshotCaughtIsHandedOverByItsLiveEvent() = runBlocking {
        val arrivals = ArrivalLedger().apply { onForeground() }
        val repository = newRepository(arrivals = arrivals)
        val connection = repository.pair(server.issue(URL))
        val request = server.addPending(connection.id, REQUEST)
        val state =
            ConnectionSyncState(
                connectionId = connection.id,
                requests =
                    mapOf(
                        REQUEST to
                            ServerRequest(RequestKey(connection.id, REQUEST), 1, request = request)
                    ),
            )
        repository.applyCache(state, SyncDelivery.Snapshot())
        assertEquals(emptySet<ReviewIdentity>(), arrivals.live.value)

        // A request this session never read quietly is only a duplicate.
        repository.handOver(connection.id, OTHER_REQUEST)
        assertEquals(emptySet<ReviewIdentity>(), arrivals.late.value)

        repository.handOver(connection.id, REQUEST)
        assertEquals(setOf(ReviewIdentity.Private(connection.id, REQUEST)), arrivals.late.value)
    }

    /** A replayed event is history: news only once this session has read the connection. */
    @Test
    fun aReplayedEventIsCatchingUpUntilTheConnectionHasBeenRead() = runBlocking {
        val arrivals = ArrivalLedger().apply { onForeground() }
        val repository = newRepository(arrivals = arrivals)
        val connection = repository.pair(server.issue(URL))
        val request = server.addPending(connection.id, REQUEST)
        val state =
            ConnectionSyncState(
                connectionId = connection.id,
                requests =
                    mapOf(
                        REQUEST to
                            ServerRequest(RequestKey(connection.id, REQUEST), 1, request = request)
                    ),
            )

        repository.applyCache(state, SyncDelivery.Replayed)
        assertEquals(emptySet<ReviewIdentity>(), arrivals.live.value)
        assertEquals(1, repository.inbox.value.pending[connection.id]?.size)
    }

    private fun snapshot(
        connectionId: String,
        vararg requests: io.github.brrenat.seekervault.request.v1.ActionRequest,
    ): SyncResponse =
        SyncResponse.newBuilder()
            .setConnectionId(connectionId)
            .setServerInstanceId("instance")
            .setSnapshotCursor("cursor-1")
            .apply {
                requests.forEach {
                    addRequests(SyncedRequest.newBuilder().setRequest(it).setRevision(1))
                }
            }
            .build()

    private fun snapshot(
        connectionId: String,
        request: io.github.brrenat.seekervault.request.v1.ActionRequest,
        revision: Long = 1,
    ): SyncResponse =
        SyncResponse.newBuilder()
            .setConnectionId(connectionId)
            .setServerInstanceId("instance")
            .setSnapshotCursor("cursor-$revision")
            .addRequests(SyncedRequest.newBuilder().setRequest(request).setRevision(revision))
            .build()

    private class SnapshotTransport : UpdateTransport {
        var calls = 0
        lateinit var response: (SyncRequest) -> SyncResponse

        override suspend fun discover(
            serverUrl: String,
            credential: String,
            connectionId: String,
        ): UpdateEndpoint = UpdateEndpoint(1, "https://a.example.com")

        override suspend fun sync(
            endpoint: UpdateEndpoint,
            credential: String,
            request: SyncRequest,
        ): SyncResponse {
            calls++
            assertTrue(credential.isNotEmpty())
            return response(request)
        }
    }

    private companion object {
        const val URL = "https://a.example.com"
        const val REQUEST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_REQUEST = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val THIRD_REQUEST = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val NOW: Instant = Instant.parse("2026-09-13T12:00:00Z")
    }
}
